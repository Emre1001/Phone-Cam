package com.phonecam

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.display.DisplayManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.view.Display
import android.view.OrientationEventListener
import android.view.Surface
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The camera delivered no picture in this mode; the next try steps down to a smaller one. */
private class NoPictureException(val mode: Mode) : IOException("no picture in ${mode.width}x${mode.height}@${mode.fps}")

/** Foreground service: connects to the PC, then runs camera -> hardware encoder -> network. */
class StreamService : Service() {
    @Volatile private var running = false
    private var worker: Thread? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    /** Device rotation in degrees (like Surface.ROTATION_*), -1 until the sensor answered. */
    @Volatile private var orientation = -1
    private var orientationListener: OrientationEventListener? = null

    override fun attachBaseContext(base: Context) = super.attachBaseContext(Prefs.wrap(base))
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopStreaming()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        running = true
        startInForeground(getString(R.string.notif_waiting))
        acquireLocks()
        watchThermal()
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(deg: Int) {
                if (deg == ORIENTATION_UNKNOWN) return
                orientation = (360 - ((deg + 45) / 90 * 90) % 360) % 360   // = Surface.ROTATION_* in degrees
            }
        }.also { if (it.canDetectOrientation()) it.enable() }
        worker = Thread({ loop() }, "phonecam-stream").also { it.start() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        orientationListener?.disable()
        releaseLocks()
        if (Build.VERSION.SDK_INT >= 29) {
            (thermalListener as? PowerManager.OnThermalStatusChangedListener)?.let { getSystemService(PowerManager::class.java)?.removeThermalStatusListener(it) }
        }
        if (Status.current.error == Err.NONE) Status.publish(Snap())
        super.onDestroy()
    }

    private fun stopStreaming() {
        running = false
        worker?.interrupt()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /* ---------- Main loop: connect, stream, reconnect ---------- */
    private fun wifiNetwork(): Network? = findWifiNetwork(this)

    private fun wifiHost(prefs: Prefs): String = prefs.host.ifBlank { Discovery.find(network = wifiNetwork()).firstOrNull()?.host.orEmpty() }

    private fun loop() {
        val prefs = Prefs(this)
        try {
            while (running) {
                val mode = Caps.resolve(this, prefs)
                Log.i(TAG, "mode=$mode")
                if (mode == null) {
                    Status.publish(Snap(error = if (Caps.failed.isEmpty()) Err.NO_CAMERA else Err.CAMERA, detail = "no picture"))
                    break
                }
                Status.publish(Snap(Snap.State.SEARCHING, mode = mode))
                val both = prefs.mode == "both"
                // USB first when it may be used: it needs no PIN and pins the PC key safely.
                val r = if (prefs.mode == "wifi") null else Session.start("127.0.0.1", null, prefs, prefs.pin, Device.name(this), mode, rotationFor(mode))
                val result = if (r is Session.Result.Ok || prefs.mode == "usb") r else {
                    val host = wifiHost(prefs)
                    if (host.isEmpty()) null
                    else { Status.publish(Snap(Snap.State.CONNECTING, mode = mode)); Session.start(host, wifiNetwork(), prefs, prefs.pin, Device.name(this), mode, rotationFor(mode)) }
                }
                when (result) {
                    null, is Session.Result.Failed -> { Status.publish(Snap(Snap.State.WAITING, mode = mode)); sleep(1200) }
                    is Session.Result.Untrusted -> { Status.publish(Snap(error = Err.UNTRUSTED, pcKey = result.key)); break }
                    is Session.Result.Refused -> when (result.code) {
                        1 -> { Status.publish(Snap(error = Err.WRONG_PIN)); break }
                        6 -> { Status.publish(Snap(error = Err.PIN_LOCKED)); break }
                        4 -> { Status.publish(Snap(error = Err.NO_FFMPEG)); break }
                        3 -> { Status.publish(Snap(error = Err.UNSUPPORTED)); break }
                        else -> { Status.publish(Snap(Snap.State.WAITING, mode = mode)); sleep(1500) }
                    }
                    is Session.Result.Ok -> {
                        try {
                            Log.i(TAG, "streaming via ${result.link.via}, pc ips ${result.pcIps}")
                            stream(mode, result, both)
                        } catch (e: NoPictureException) {
                            // This mode looked supported but delivers nothing on this phone: never use it again (this run).
                            Log.w(TAG, e.message.orEmpty())
                            Caps.failed.add(e.mode.key)
                        } catch (e: CameraAccessException) {
                            Log.e(TAG, "camera", e)
                            Status.publish(Snap(error = Err.CAMERA, detail = e.message.orEmpty())); break
                        } catch (e: SecurityException) {
                            Status.publish(Snap(error = Err.PERMISSION)); break
                        } catch (e: IOException) {
                            Log.w(TAG, "io", e)
                        } catch (_: InterruptedException) {
                            break
                        } catch (e: Exception) {
                            Log.e(TAG, "stream failed", e)
                            Status.publish(Snap(error = Err.CAMERA, detail = e.message.orEmpty())); break
                        } finally {
                            result.link.close()
                        }
                        if (running) { Status.publish(Snap(Snap.State.WAITING, mode = mode)); sleep(500) }
                    }
                }
            }
        } catch (_: InterruptedException) {
        } catch (t: Throwable) {
            Log.e(TAG, "stream loop failed", t)
            Status.publish(Snap(error = Err.CAMERA, detail = t.message.orEmpty()))
        } finally {
            val last = Status.current
            if (running) {
                running = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            if (last.state != Snap.State.IDLE && last.error == Err.NONE) Status.publish(Snap())
        }
    }

    private fun sleep(ms: Long) = Thread.sleep(ms)

    /** The orientation sensor answers within a few events; until then the display rotation is the best guess. */
    private fun deviceOrientation(): Int {
        val end = System.currentTimeMillis() + 400
        while (orientation < 0 && System.currentTimeMillis() < end) Thread.sleep(20)
        if (orientation >= 0) return orientation
        val d = getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        return (d?.rotation ?: 0) * 90
    }

    /** Degrees clockwise the PC must turn the picture so it appears upright. */
    private fun rotationFor(m: Mode): Int {
        val o = deviceOrientation()
        return if (m.front) (m.sensorOrientation + o) % 360 else (m.sensorOrientation - o + 360) % 360
    }

    /* ---------- One streaming session ---------- */
    @SuppressLint("MissingPermission")
    private fun stream(m: Mode, first: Session.Result.Ok, both: Boolean) {
        val camThread = HandlerThread("phonecam-camera").also { it.start() }
        val camHandler = Handler(camThread.looper)
        // An encoder that refuses this mode is handled like a camera without picture: the next try steps down.
        val codec = try { createEncoder(m) } catch (e: Exception) { camThread.quitSafely(); throw NoPictureException(m).apply { initCause(e) } }
        // Links run first: their keepalives flow while the camera starts, which can take a few seconds.
        val bond = Bond(onKey = { requestKeyFrame(codec) }, onBitrate = { kbps -> setBitrate(codec, kbps) })
        bond.add(first.link)
        Status.publish(Snap(Snap.State.CONNECTING, mode = m, pcKey = first.link.pcKey))
        val joiner = if (both) Thread({ joinLoop(bond, first) }, "phonecam-join").also { it.isDaemon = true; it.start() } else null
        var camera: CameraDevice? = null
        var session: CameraCaptureSession? = null
        // Set by the camera callbacks: -1 disconnected (another app took the camera), > 0 CameraDevice error.
        val cameraError = AtomicInteger(0)
        try {
            val surface = try {
                codec.createInputSurface().also { codec.start() }
            } catch (e: IllegalStateException) {
                throw NoPictureException(m).apply { initCause(e) }
            }
            Log.i(TAG, "encoder started")

            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val opened = CountDownLatch(1)
            cm.openCamera(m.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) { camera = c; opened.countDown() }
                override fun onDisconnected(c: CameraDevice) { c.close(); cameraError.set(-1); opened.countDown() }
                override fun onError(c: CameraDevice, error: Int) { c.close(); cameraError.set(error); opened.countDown() }
            }, camHandler)
            if (!opened.await(5, TimeUnit.SECONDS) || camera == null) throw IOException("camera open failed ${cameraError.get().takeIf { it != 0 } ?: "timeout"}")

            Log.i(TAG, "camera opened")
            val configured = CountDownLatch(1)
            @Suppress("DEPRECATION")
            camera!!.createCaptureSession(listOf<Surface>(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { session = s; configured.countDown() }
                override fun onConfigureFailed(s: CameraCaptureSession) { configured.countDown() }
            }, camHandler)
            // A mode the camera cannot combine with the encoder fails here: step down like with no picture.
            if (!configured.await(5, TimeUnit.SECONDS) || session == null) throw NoPictureException(m)

            Log.i(TAG, "session configured")
            val ch = cm.getCameraCharacteristics(m.cameraId)
            val stabilize = Prefs(this).stabilize
            val req = camera!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, m.fpsRange)
                if (ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) == true) {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                }
                // Electronic stabilisation crops the picture and holds frames back (Samsung and Xiaomi turn it on
                // in TEMPLATE_RECORD). A webcam usually stands still, so it is off unless the user wants it.
                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    if (stabilize) CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON else CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                // Optical stabilisation costs no delay: on where the lens has it.
                if (ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON) == true) {
                    set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
                }
                // No flicker under 50 Hz / 60 Hz room lights.
                if (ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES)?.contains(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO) == true) {
                    set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO)
                }
            }.build()
            session!!.setRepeatingRequest(req, null, camHandler)
            drain(codec, bond, m, cameraError)
        } finally {
            joiner?.interrupt()
            bond.closeAll()
            try { session?.close() } catch (_: Exception) {}
            try { camera?.close() } catch (_: Exception) {}
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            camThread.quitSafely()
        }
    }

    /**
     * Configures the encoder picked in [Caps] by name, tuned for its chip ([Device.tuning]). Encoders differ in
     * what they accept, so optional keys are dropped step by step: level 0 has everything, level 2 the bare minimum.
     */
    private fun createEncoder(m: Mode): MediaCodec {
        val tune = Device.tuning(m.encoder)
        var last: Exception? = null
        for (level in 0..2) {
            val format = MediaFormat.createVideoFormat(m.mime, m.width, m.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, m.kbps * 1000)
                setInteger(MediaFormat.KEY_FRAME_RATE, m.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, tune.keyFrameSec)
                if (level < 2) {
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                    // B-frames reorder pictures: the PC decodes with low delay and would show them out of order.
                    // Some Snapdragon and Exynos HEVC encoders turn them on by default.
                    if (Build.VERSION.SDK_INT >= 29) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                }
                if (level < 1) {
                    setInteger("prepend-sps-pps-to-idr-frames", 1)
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                    // Clock the encoder for the full frame rate right away (avoids dropped frames at 60 fps).
                    setInteger(MediaFormat.KEY_OPERATING_RATE, m.fps)
                    if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LATENCY, 1)
                    // Video-standard colours, so video apps on the PC show the same colours as the phone.
                    setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                    setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                    setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                    tune.vendorKeys.forEach { (k, v) -> setInteger(k, v) }
                }
            }
            val codec = try { MediaCodec.createByCodecName(m.encoder) } catch (_: Exception) { MediaCodec.createEncoderByType(m.mime) }
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                Log.i(TAG, "encoder ${codec.name} configured at level $level (chip ${Device.chip})")
                return codec
            } catch (e: Exception) {
                last = e
                Log.w(TAG, "configure level $level failed", e)
                codec.release()
            }
        }
        throw last ?: IOException("encoder")
    }

    @Volatile private var lastKeyRequest = 0L

    /** Asks the encoder for a key frame, at most every 300 ms: each one costs a burst of bandwidth. */
    private fun requestKeyFrame(codec: MediaCodec) {
        val now = System.currentTimeMillis()
        if (now - lastKeyRequest < 300) return
        lastKeyRequest = now
        try { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) } catch (_: Exception) {}
    }

    private fun setBitrate(codec: MediaCodec, kbps: Int) {
        try { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, kbps.coerceIn(1000, 100_000) * 1000) }) } catch (_: Exception) {}
    }

    /** Keeps a second link in the session: Wi-Fi next to USB, or USB next to Wi-Fi. */
    private fun joinLoop(bond: Bond, first: Session.Result.Ok) {
        val prefs = Prefs(this)
        try {
            while (running && !Thread.currentThread().isInterrupted) {
                val have = bond.vias()
                if (first.link.via == "usb" && "wifi" !in have) {
                    val net = wifiNetwork()
                    val hosts = (first.pcIps + prefs.host).filter { it.isNotBlank() && it != "127.0.0.1" }.distinct()
                    if (net != null) hosts.firstNotNullOfOrNull { Session.join(it, net, first.id, first.link.pcKey) }?.let { bond.add(it) }
                } else if (first.link.via == "wifi" && "usb" !in have) {
                    Session.join("127.0.0.1", null, first.id, first.link.pcKey)?.let { bond.add(it) }
                }
                Thread.sleep(3000)
            }
        } catch (_: InterruptedException) {
        }
    }

    /**
     * Pulls encoded frames and spreads them over the links. Congestion drops delta frames until the next key frame
     * and lowers the bitrate; it climbs back to the target once the links keep up again.
     */
    private fun drain(codec: MediaCodec, bond: Bond, m: Mode, cameraError: AtomicInteger) {
        val info = MediaCodec.BufferInfo()
        var config = ByteArray(0)
        var skipping = false
        var seq = 0
        var frames = 0
        var pictures = 0L
        val started = System.currentTimeMillis()
        var lastPicture = started
        var mark = started
        var lastNotify = -1
        // At most ~300 ms of video may wait in a link queue; the preferred link (USB) takes frames below ~2 frames.
        val maxQueued = maxOf(512 * 1024L, m.kbps * 1000L / 8 * 3 / 10)
        val lowWater = maxOf(96 * 1024L, m.kbps * 1000L / 8 / m.fps * 2)
        var target = thermalCap(m.kbps)
        var kbps = target
        var lastCut = 0L
        var lastChange = started
        while (running) {
            if (bond.isEmpty()) throw IOException("all links closed")
            cameraError.get().let { if (it != 0) throw IOException("camera error $it") }
            val i = codec.dequeueOutputBuffer(info, 100_000)
            val now = System.currentTimeMillis()
            if (i >= 0) {
                val buf = codec.getOutputBuffer(i)
                if (buf != null && info.size > 0) {
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    val data = ByteArray(info.size).also { buf.get(it) }
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    if (isConfig) config = data else { pictures++; lastPicture = now }
                    if (isConfig || !skipping || isKey) {
                        val type = if (isConfig) 1 else if (isKey) 2 else 3
                        val payload = if (isKey && !startsWith(data, config)) config + data else data
                        val rec = ByteBuffer.allocate(13 + payload.size).put(type.toByte()).putInt(seq).putLong(info.presentationTimeUs).put(payload).array()
                        if (bond.send(rec, maxQueued, lowWater)) {
                            seq++
                            if (!isConfig) frames++
                            skipping = false
                        } else if (!isConfig) {
                            skipping = true
                            requestKeyFrame(codec)
                            // The network does not keep up: lower the bitrate (at most once a second).
                            if (now - lastCut > 1000 && kbps > MIN_KBPS) {
                                kbps = maxOf(MIN_KBPS, maxOf(target / 4, kbps * 7 / 10))
                                setBitrate(codec, kbps)
                                lastCut = now; lastChange = now
                                Log.i(TAG, "congestion, bitrate $kbps kbit/s")
                            }
                        }
                    }
                }
                codec.releaseOutputBuffer(i, false)
            }
            // Watchdogs: a mode that never delivers, or a camera that stops.
            if (pictures == 0L && now - started > FIRST_PICTURE_MS) throw NoPictureException(m)
            if (pictures > 0 && now - lastPicture > STALL_MS) throw IOException("camera stalled")
            // Heat changes the target; a lower one applies at once.
            val capped = thermalCap(m.kbps)
            if (capped != target) {
                target = capped
                if (kbps > target) { kbps = target; setBitrate(codec, kbps); lastChange = now }
            }
            // Calm for a while: climb back towards the target bitrate.
            if (kbps < target && now - lastCut > 4000 && now - lastChange > 2000) {
                kbps = minOf(target, kbps * 115 / 100 + 250)
                setBitrate(codec, kbps)
                lastChange = now
            }
            if (now - mark >= 1000) {
                val fps = (frames * 1000L / (now - mark)).toInt()
                val links = bond.rates(now - mark)
                val sent = links.sumOf { it.second }
                val state = if (pictures > 0) Snap.State.LIVE else Snap.State.CONNECTING
                Status.publish(Snap(state, mode = m, fps = fps, kbps = sent, links = links, pcKey = bond.pcKey(), hot = target < m.kbps))
                if (pictures > 0 && kotlin.math.abs(fps - lastNotify) > 1) {
                    notify(getString(R.string.notif_live, fps, "%.1f".format(sent / 1000.0))); lastNotify = fps
                }
                frames = 0; mark = now
            }
        }
    }

    private fun startsWith(data: ByteArray, prefix: ByteArray): Boolean =
        prefix.isNotEmpty() && data.size >= prefix.size && (prefix.indices).all { data[it] == prefix[it] }

    /* ---------- Notification and locks ---------- */
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, StreamService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.notif_stop), stop).build())
            .build()
    }

    private fun startInForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW))
        val n = notification(text)
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA) else startForeground(1, n)
    }

    private fun notify(text: String) = getSystemService(NotificationManager::class.java).notify(1, notification(text))

    /* ---------- Heat: long 4K or 60 fps streams warm the phone; lower the bitrate before Android throttles ---------- */
    @Volatile private var thermal = 0
    private var thermalListener: Any? = null

    private fun watchThermal() {
        if (Build.VERSION.SDK_INT < 29) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        val l = PowerManager.OnThermalStatusChangedListener { thermal = it }
        thermal = pm.currentThermalStatus
        pm.addThermalStatusListener(mainExecutor, l)
        thermalListener = l
    }

    /** Bitrate the phone can keep up without overheating: full, 75 % when warm, 50 % when hot. */
    private fun thermalCap(kbps: Int): Int = when {
        thermal >= PowerManager.THERMAL_STATUS_SEVERE -> kbps / 2
        thermal >= PowerManager.THERMAL_STATUS_MODERATE -> kbps * 3 / 4
        else -> kbps
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phonecam:stream").also { it.acquire() }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifi = wm.createWifiLock(if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF, "phonecam:wifi").also { it.acquire() }
    }

    private fun releaseLocks() {
        try { wake?.release() } catch (_: Exception) {}
        try { wifi?.release() } catch (_: Exception) {}
    }

    companion object {
        const val ACTION_STOP = "com.phonecam.STOP"
        private const val CHANNEL = "stream"
        private const val TAG = "PhoneCam"
        /** A new camera mode must deliver its first picture within this time, else the next smaller one is tried. */
        private const val FIRST_PICTURE_MS = 4000L
        /** No picture for this long while streaming: restart camera and encoder. */
        private const val STALL_MS = 4000L
        private const val MIN_KBPS = 1000

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, StreamService::class.java))
        fun stop(ctx: Context) = ctx.startService(Intent(ctx, StreamService::class.java).setAction(ACTION_STOP))
    }
}
