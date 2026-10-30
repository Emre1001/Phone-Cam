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
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.view.OrientationEventListener
import android.view.Surface
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Foreground service: connects to the PC, then runs camera -> hardware encoder -> network. */
class StreamService : Service() {
    @Volatile private var running = false
    private var worker: Thread? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var orientation = 0
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
    private fun wifiNetwork(): Network? {
        val cm = getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        return cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }

    private fun wifiHost(prefs: Prefs): String = prefs.host.ifBlank { Discovery.find().firstOrNull()?.host.orEmpty() }

    private fun loop() {
        val prefs = Prefs(this)
        try {
            while (running) {
                val mode = Caps.resolve(this, prefs)
                Log.i(TAG, "mode=$mode")
                if (mode == null) { Status.publish(Snap(error = Err.NO_CAMERA)); break }
                Status.publish(Snap(Snap.State.SEARCHING, mode = mode))
                val both = prefs.mode == "both"
                // USB first when it may be used: it needs no PIN and pins the PC key safely.
                val r = if (prefs.mode == "wifi") null else Session.start("127.0.0.1", null, prefs, prefs.pin, Build.MODEL, mode, rotationFor(mode))
                val result = if (r is Session.Result.Ok || prefs.mode == "usb") r else {
                    val host = wifiHost(prefs)
                    if (host.isEmpty()) null
                    else { Status.publish(Snap(Snap.State.CONNECTING, mode = mode)); Session.start(host, wifiNetwork(), prefs, prefs.pin, Build.MODEL, mode, rotationFor(mode)) }
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
                        if (running) { Status.publish(Snap(Snap.State.WAITING, mode = mode)); sleep(800) }
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

    /** Degrees clockwise the PC must turn the picture so it appears upright. */
    private fun rotationFor(m: Mode) =
        if (m.front) (m.sensorOrientation + orientation) % 360 else (m.sensorOrientation - orientation + 360) % 360

    /* ---------- One streaming session ---------- */
    @SuppressLint("MissingPermission")
    private fun stream(m: Mode, first: Session.Result.Ok, both: Boolean) {
        val camThread = HandlerThread("phonecam-camera").also { it.start() }
        val camHandler = Handler(camThread.looper)
        val codec = createEncoder(m)
        var camera: CameraDevice? = null
        var session: CameraCaptureSession? = null
        try {
            Log.i(TAG, "createInputSurface")
            val surface = codec.createInputSurface()
            Log.i(TAG, "codec started")
            codec.start()

            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val opened = CountDownLatch(1)
            var openError: Int? = null
            cm.openCamera(m.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) { camera = c; opened.countDown() }
                override fun onDisconnected(c: CameraDevice) { c.close(); openError = -1; opened.countDown() }
                override fun onError(c: CameraDevice, error: Int) { c.close(); openError = error; opened.countDown() }
            }, camHandler)
            if (!opened.await(5, TimeUnit.SECONDS) || camera == null) throw IOException("camera open failed ${openError ?: "timeout"}")

            Log.i(TAG, "camera opened")
            val configured = CountDownLatch(1)
            @Suppress("DEPRECATION")
            camera!!.createCaptureSession(listOf<Surface>(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { session = s; configured.countDown() }
                override fun onConfigureFailed(s: CameraCaptureSession) { configured.countDown() }
            }, camHandler)
            if (!configured.await(5, TimeUnit.SECONDS) || session == null) throw IOException("camera session failed")

            Log.i(TAG, "session configured")
            val req = camera!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, m.fpsRange)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }.build()
            session!!.setRepeatingRequest(req, null, camHandler)

            val bond = Bond(onKey = { requestKeyFrame(codec) }, onBitrate = { kbps -> setBitrate(codec, kbps) })
            bond.add(first.link)
            val joiner = if (both) Thread({ joinLoop(bond, first) }, "phonecam-join").also { it.isDaemon = true; it.start() } else null
            try {
                drain(codec, bond, m)
            } finally {
                joiner?.interrupt()
                bond.closeAll()
            }
        } finally {
            try { session?.close() } catch (_: Exception) {}
            try { camera?.close() } catch (_: Exception) {}
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            camThread.quitSafely()
        }
    }

    /** Configures an encoder; encoders differ in what they accept, so optional keys are dropped step by step. */
    private fun createEncoder(m: Mode): MediaCodec {
        var last: Exception? = null
        for (level in 0..2) {
            val format = MediaFormat.createVideoFormat(m.mime, m.width, m.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, m.kbps * 1000)
                setInteger(MediaFormat.KEY_FRAME_RATE, m.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                if (level < 2) setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                if (level < 1) {
                    setInteger("prepend-sps-pps-to-idr-frames", 1)
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                    if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LATENCY, 1)
                }
            }
            val codec = MediaCodec.createEncoderByType(m.mime)
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                Log.i(TAG, "encoder ${codec.name} configured at level $level")
                return codec
            } catch (e: Exception) {
                last = e
                Log.w(TAG, "configure level $level failed", e)
                codec.release()
            }
        }
        throw last ?: IOException("encoder")
    }

    private fun requestKeyFrame(codec: MediaCodec) {
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

    /** Pulls encoded frames and spreads them over the links. Congestion drops delta frames until the next key frame. */
    private fun drain(codec: MediaCodec, bond: Bond, m: Mode) {
        val info = MediaCodec.BufferInfo()
        var config = ByteArray(0)
        var skipping = false
        var seq = 0
        var frames = 0
        var mark = System.currentTimeMillis()
        var lastNotify = 0
        // At most ~300 ms of video may wait in a link queue.
        val maxQueued = maxOf(512 * 1024L, m.kbps * 1000L / 8 * 3 / 10)
        while (running) {
            if (bond.isEmpty()) throw IOException("all links closed")
            val i = codec.dequeueOutputBuffer(info, 200_000)
            if (i < 0) continue
            val buf = codec.getOutputBuffer(i)
            if (buf != null && info.size > 0) {
                buf.position(info.offset); buf.limit(info.offset + info.size)
                val data = ByteArray(info.size).also { buf.get(it) }
                val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                if (isConfig) config = data
                if (isConfig || !skipping || isKey) {
                    val type = if (isConfig) 1 else if (isKey) 2 else 3
                    val payload = if (isKey && !startsWith(data, config)) config + data else data
                    val rec = ByteBuffer.allocate(13 + payload.size).put(type.toByte()).putInt(seq).putLong(info.presentationTimeUs).put(payload).array()
                    if (bond.send(rec, maxQueued)) {
                        seq++
                        if (!isConfig) frames++
                        skipping = false
                    } else if (!isConfig) {
                        skipping = true
                        requestKeyFrame(codec)
                    }
                }
            }
            codec.releaseOutputBuffer(i, false)
            val now = System.currentTimeMillis()
            if (now - mark >= 1000) {
                val fps = (frames * 1000L / (now - mark)).toInt()
                val links = bond.rates(now - mark)
                val kbps = links.sumOf { it.second }
                Status.publish(Snap(Snap.State.LIVE, mode = m, fps = fps, kbps = kbps, links = links, pcKey = bond.pcKey()))
                if (kotlin.math.abs(fps - lastNotify) > 1) { notify(getString(R.string.notif_live, fps, "%.1f".format(kbps / 1000.0))); lastNotify = fps }
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

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, StreamService::class.java))
        fun stop(ctx: Context) = ctx.startService(Intent(ctx, StreamService::class.java).setAction(ACTION_STOP))
    }
}
