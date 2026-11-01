package com.phonecam

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Range

data class Mode(
    val cameraId: String,
    val front: Boolean,
    val sensorOrientation: Int,
    val width: Int,
    val height: Int,
    val fps: Int,
    val hevc: Boolean,
    val kbps: Int,
    val fpsRange: Range<Int>,
) {
    val codecName get() = if (hevc) "HEVC" else "H.264"
    val mime get() = if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
    /** Identifies the mode in [Caps.failed]. */
    val key get() = "$cameraId:${width}x$height@$fps:${if (hevc) "hevc" else "avc"}"
}

/** Works out the best video mode the camera AND a hardware encoder both support. */
object Caps {
    /** Highest first. Width x height @ fps. */
    val LADDER = listOf(
        Triple(3840, 2160, 60), Triple(3840, 2160, 30),
        Triple(2560, 1440, 60), Triple(2560, 1440, 30),
        Triple(1920, 1080, 60), Triple(1920, 1080, 30),
        Triple(1280, 720, 60), Triple(1280, 720, 30),
    )

    /** "Auto" goes up to this height. Higher modes need a strong PC to decode, so the user picks them on purpose. */
    const val AUTO_MAX_HEIGHT = 1080

    /** Modes that delivered no picture on this phone (since app start). [resolve] skips them, so the next try steps down. */
    val failed: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    fun cameraId(ctx: Context, front: Boolean): String? {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val want = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        return cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want }
    }

    /**
     * The camera feeds the encoder's input surface, so sizes and frame durations are checked for MediaCodec
     * (SurfaceTexture only as a fallback for old HALs that list nothing there). Returns the AE fps range to use.
     */
    private fun cameraSupports(ch: CameraCharacteristics, w: Int, h: Int, fps: Int): Range<Int>? {
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val codecSizes = map.getOutputSizes(MediaCodec::class.java).orEmpty()
        val useCodec = codecSizes.isNotEmpty()
        val sizes = if (useCodec) codecSizes else map.getOutputSizes(SurfaceTexture::class.java).orEmpty()
        val size = sizes.firstOrNull { it.width == w && it.height == h } ?: return null
        val minDuration = try {
            if (useCodec) map.getOutputMinFrameDuration(MediaCodec::class.java, size) else map.getOutputMinFrameDuration(SurfaceTexture::class.java, size)
        } catch (_: IllegalArgumentException) { 0L }
        if (minDuration > 0 && 1_000_000_000.0 / minDuration < fps * 0.98) return null
        val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        // Prefer a fixed range (60-60) so the exposure time cannot make frames late.
        return ranges.firstOrNull { it.lower == fps && it.upper == fps }
            ?: ranges.filter { it.upper == fps }.maxByOrNull { it.lower }
            ?: ranges.filter { it.upper > fps }.minByOrNull { it.upper }
    }

    private fun isSoftware(i: MediaCodecInfo): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 29) i.isSoftwareOnly else i.name.startsWith("OMX.google") || i.name.startsWith("c2.android")

    fun hasHardwareEncoder(mime: String, w: Int, h: Int, fps: Int, allowSoftware: Boolean = false): Boolean =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any {
            it.isEncoder && mime in it.supportedTypes && (allowSoftware || !isSoftware(it)) &&
                it.getCapabilitiesForType(mime).videoCapabilities.areSizeAndRateSupported(w, h, fps.toDouble())
        }

    fun autoKbps(w: Int, h: Int, fps: Int, hevc: Boolean, usb: Boolean): Int {
        val bitsPerPixel = if (hevc) 0.06 else 0.1
        val cap = if (usb) 100_000 else 50_000
        return (w.toDouble() * h * fps * bitsPerPixel / 1000).toInt().coerceIn(2_000, cap)
    }

    /** Best mode for the user's limits, or null when the camera has none. Modes that failed before are skipped. */
    fun resolve(ctx: Context, p: Prefs): Mode? =
        pick(ctx, p.front, p.mode == "usb", if (p.maxHeight == 0) AUTO_MAX_HEIGHT else p.maxHeight, p.maxFps, p.codec, p.bitrateMbit, skipFailed = true)

    /** Best mode the phone can do at all (for the "best this phone can do" line). */
    fun best(ctx: Context, front: Boolean, usb: Boolean): Mode? = pick(ctx, front, usb, 0, 0, "auto", 0, skipFailed = false)

    private fun pick(ctx: Context, front: Boolean, usb: Boolean, maxHeight: Int, maxFps: Int, codec: String, bitrateMbit: Int, skipFailed: Boolean): Mode? {
        val id = cameraId(ctx, front) ?: return null
        val ch = (ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager).getCameraCharacteristics(id)
        val sensor = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        // Second pass accepts a software encoder, but only up to 1080p: 4K would not keep up in software.
        for (soft in listOf(false, true)) {
            for ((w, h, fps) in LADDER) {
                if (maxHeight in 1 until h || maxFps in 1 until fps || (soft && h > 1080)) continue
                val range = cameraSupports(ch, w, h, fps) ?: continue
                val order = when (codec) { "h264" -> listOf(false); "hevc" -> listOf(true); else -> listOf(true, false) }
                for (hevc in order) {
                    val mime = if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
                    if (!hasHardwareEncoder(mime, w, h, fps, soft)) continue
                    val kbps = if (bitrateMbit > 0) bitrateMbit * 1000 else autoKbps(w, h, fps, hevc, usb)
                    val mode = Mode(id, front, sensor, w, h, fps, hevc, kbps, range)
                    if (skipFailed && mode.key in failed) continue
                    return mode
                }
            }
        }
        return null
    }

    /** Video modes this camera can really deliver (camera and encoder), best first. */
    fun available(ctx: Context, front: Boolean): List<Triple<Int, Int, Int>> {
        val id = cameraId(ctx, front) ?: return emptyList()
        val ch = (ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager).getCameraCharacteristics(id)
        return LADDER.filter { (w, h, fps) ->
            cameraSupports(ch, w, h, fps) != null && listOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC)
                .any { hasHardwareEncoder(it, w, h, fps, allowSoftware = h <= 1080) }
        }
    }
}
