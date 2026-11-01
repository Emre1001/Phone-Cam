package com.phonecam

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.ByteBuffer
import java.security.Signature
import java.security.interfaces.ECPublicKey
import javax.crypto.KeyAgreement

const val CAM_PORT = 47800
const val DISCOVERY_PORT = 47801
private const val MAX_RECORD = (16 shl 20) + 16
/** The PC pings every second; a link that stays silent this long is dead (Wi-Fi gone, PC asleep). */
private const val DEAD_AFTER_MS = 5000

/** The Wi-Fi network, also when the phone routes its traffic over mobile data (Wi-Fi without internet). */
fun findWifiNetwork(ctx: Context): Network? {
    val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
    @Suppress("DEPRECATION")
    return cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
}

/** One encrypted TCP link to the PC app. Protocol: docs/PROTOCOL.md. */
class SecureLink private constructor(
    private val sock: Socket,
    private val sealer: Crypto.Sealer,
    private val opener: Crypto.Opener,
    val pcKey: ByteArray,
    val via: String,
) : Closeable {
    private val out = DataOutputStream(sock.getOutputStream().buffered(1 shl 16))
    private val input = DataInputStream(sock.getInputStream().buffered(1 shl 14))

    fun send(plain: ByteArray) {
        val ct = synchronized(sealer) { sealer.seal(plain) }
        synchronized(out) {
            out.writeInt(ct.size)
            out.write(ct)
            out.flush()
        }
    }

    fun recv(): ByteArray {
        val n = input.readInt()
        if (n < 16 || n > MAX_RECORD) throw IOException("bad record")
        val ct = ByteArray(n).also { input.readFully(it) }
        return try { opener.open(ct) } catch (e: Exception) { throw IOException("record authentication failed") }
    }

    /**
     * True once the PC sent a keepalive ('P', PC app 0.0.2 and newer). Only then the phone sends its own
     * keepalives (older PC apps would reject them) and treats a silent link as dead.
     */
    @Volatile var pcPings = false
        private set

    /** Reads PC to phone control messages until the link ends. */
    fun readControl(onKey: () -> Unit, onBitrate: (Int) -> Unit) {
        try {
            while (true) {
                val m = recv()
                when (m.firstOrNull()?.toInt()?.toChar()) {
                    'K' -> onKey()
                    'B' -> if (m.size >= 5) onBitrate(ByteBuffer.wrap(m, 1, 4).int)
                    'P' -> if (!pcPings) { pcPings = true; sock.soTimeout = DEAD_AFTER_MS }
                    else -> {}
                }
            }
        } catch (_: Exception) {
        }
    }

    override fun close() {
        try { sock.close() } catch (_: Exception) {}
    }

    /** After the session handshake: no read timeout until the PC proves it sends keepalives (see [pcPings]). */
    fun established() { sock.soTimeout = 0 }

    companion object {
        /** TCP connect + handshake. `network` pins the socket to Wi-Fi when the phone has several networks. */
        fun open(host: String, network: Network?): SecureLink {
            val s = Socket()
            try {
                network?.bindSocket(s)
                s.tcpNoDelay = true
                s.sendBufferSize = 1 shl 20
                s.connect(InetSocketAddress(host, CAM_PORT), 2500)
                s.soTimeout = 6000
                val o = s.getOutputStream()
                val i = DataInputStream(s.getInputStream())
                val kp = Crypto.newKeyPair()
                val me = Crypto.encode(kp.public as ECPublicKey)
                o.write("PCAM".toByteArray() + byteArrayOf(2) + me)
                o.flush()
                val pcEph = ByteArray(65).also { i.readFully(it) }
                val pcId = ByteArray(65).also { i.readFully(it) }
                val sig = ByteArray(i.readUnsignedByte()).also { i.readFully(it) }
                val transcript = "PCAM2".toByteArray() + me + pcEph + pcId
                val params = (kp.public as ECPublicKey).params
                val ok = Signature.getInstance("SHA256withECDSA").run {
                    initVerify(Crypto.decode(pcId, params)); update(transcript); verify(sig)
                }
                if (!ok) throw IOException("PC signature invalid")
                val shared = KeyAgreement.getInstance("ECDH").run { init(kp.private); doPhase(Crypto.decode(pcEph, params), true); generateSecret() }
                val salt = Crypto.sha256(transcript)
                val link = SecureLink(
                    s,
                    Crypto.Sealer(Crypto.hkdf(salt, shared, "phonecam v2 phone->pc")),
                    Crypto.Opener(Crypto.hkdf(salt, shared, "phonecam v2 pc->phone")),
                    pcId,
                    if (host == "127.0.0.1") "usb" else "wifi",
                )
                return link
            } catch (e: Exception) {
                try { s.close() } catch (_: Exception) {}
                throw if (e is IOException) e else IOException(e.message ?: "handshake", e)
            }
        }
    }
}

/** Starting or joining a session on top of a SecureLink. */
object Session {
    sealed interface Result {
        class Ok(val link: SecureLink, val id: ByteArray, val pcIps: List<String>) : Result
        class Refused(val code: Int) : Result
        class Untrusted(val key: ByteArray) : Result
        class Failed(val reason: String) : Result
    }

    private fun str(b: ByteArray) = byteArrayOf(b.size.toByte()) + b

    /** New session. Unknown PC keys are only accepted over USB (adb is a trusted channel); Wi-Fi needs a confirmed key. */
    fun start(host: String, network: Network?, trust: Prefs, pin: String, name: String, m: Mode, rotation: Int): Result {
        val link = try { SecureLink.open(host, network) } catch (e: Exception) { return Result.Failed(e.message ?: "connect") }
        try {
            if (!trust.isTrusted(link.pcKey)) {
                if (link.via == "usb") trust.trust(link.pcKey) else { link.close(); return Result.Untrusted(link.pcKey) }
            }
            val hello = ByteBuffer.allocate(256)
            hello.put(0)
            hello.put(str(pin.toByteArray().take(32).toByteArray()))
            hello.put(str(name.toByteArray().take(80).toByteArray()))
            hello.put(if (m.hevc) 1 else 0)
            hello.putShort(m.width.toShort()); hello.putShort(m.height.toShort())
            hello.put(m.fps.toByte())
            hello.putShort(rotation.toShort())
            hello.put(if (m.front) 1 else 0)
            link.send(hello.array().copyOf(hello.position()))
            val r = link.recv()
            if (r.isEmpty() || r[0].toInt() != 0) { link.close(); return Result.Refused(r.firstOrNull()?.toInt() ?: 3) }
            val id = r.copyOfRange(1, 17)
            val n = r[17].toInt() and 0xFF
            val ips = (0 until n).map { k -> (0 until 4).joinToString(".") { j -> (r[18 + 4 * k + j].toInt() and 0xFF).toString() } }
            link.established()
            return Result.Ok(link, id, ips)
        } catch (e: Exception) {
            link.close()
            return Result.Failed(e.message ?: "session")
        }
    }

    /** Adds a link to a running session. The PC must present the same key as on the first link. */
    fun join(host: String, network: Network?, id: ByteArray, pcKey: ByteArray): SecureLink? {
        val link = try { SecureLink.open(host, network) } catch (_: Exception) { return null }
        try {
            if (!link.pcKey.contentEquals(pcKey)) { link.close(); return null }
            link.send(byteArrayOf(1) + id)
            if (link.recv().firstOrNull()?.toInt() == 0) { link.established(); return link }
        } catch (_: Exception) {
        }
        link.close()
        return null
    }
}

data class Pc(val name: String, val host: String)

object Discovery {
    /**
     * Broadcasts "PCAM?" and collects answers for `waitMs`. With `network` the socket is bound to Wi-Fi, so the
     * search works when the phone sends its traffic over mobile data (Samsung and Xiaomi do that on Wi-Fi without internet).
     */
    fun find(waitMs: Long = 1500, network: Network? = null): List<Pc> {
        val found = LinkedHashMap<String, Pc>()
        try {
            DatagramSocket().use { sock ->
                try { network?.bindSocket(sock) } catch (_: Exception) {}
                sock.broadcast = true
                sock.soTimeout = 250
                val msg = "PCAM?".toByteArray()
                val targets = mutableSetOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
                NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.forEach { ni ->
                    ni.interfaceAddresses.mapNotNull { it.broadcast }.forEach { targets.add(it) }
                }
                val end = System.currentTimeMillis() + waitMs
                var lastSend = 0L
                val buf = ByteArray(128)
                while (System.currentTimeMillis() < end) {
                    if (System.currentTimeMillis() - lastSend > 500) {
                        targets.forEach { t -> try { sock.send(DatagramPacket(msg, msg.size, t, DISCOVERY_PORT)) } catch (_: Exception) {} }
                        lastSend = System.currentTimeMillis()
                    }
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        sock.receive(p)
                        val lines = String(p.data, 0, p.length).split('\n')
                        if (lines.size >= 3 && lines[0] == "PCAM!" && p.address is Inet4Address) {
                            found[p.address.hostAddress!!] = Pc(lines[1], p.address.hostAddress!!)
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                    }
                }
            }
        } catch (_: Exception) {
        }
        return found.values.toList()
    }
}
