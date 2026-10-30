package com.phonecam

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

enum class Err { NONE, WRONG_PIN, PIN_LOCKED, NO_PC, NO_CAMERA, CAMERA, NO_FFMPEG, UNSUPPORTED, PERMISSION, UNTRUSTED }

data class Snap(
    val state: State = State.IDLE,
    val error: Err = Err.NONE,
    val detail: String = "",
    val mode: Mode? = null,
    val fps: Int = 0,
    val kbps: Int = 0,
    /** ("usb"|"wifi", kbit/s) per active link while live. */
    val links: List<Pair<String, Int>> = emptyList(),
    /** PC identity key: the one in use, or the one waiting for the user's trust (Err.UNTRUSTED). */
    val pcKey: ByteArray? = null,
) {
    enum class State { IDLE, SEARCHING, CONNECTING, WAITING, LIVE }
}

/** Service to UI bridge: the service publishes, the activity listens. */
object Status {
    @Volatile var current = Snap()
        private set
    private val listeners = CopyOnWriteArrayList<(Snap) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun publish(s: Snap) {
        current = s
        main.post { listeners.forEach { it(s) } }
    }

    fun listen(fn: (Snap) -> Unit): () -> Unit {
        listeners.add(fn)
        fn(current)
        return { listeners.remove(fn) }
    }
}
