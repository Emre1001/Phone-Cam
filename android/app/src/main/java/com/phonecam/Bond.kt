package com.phonecam

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Several links to the same PC session. USB is preferred: it has the lowest delay, and keeping frames on one
 * link means the PC rarely has to wait to put them back in order. Wi-Fi takes over when USB is busy or gone.
 */
class Bond(private val onKey: () -> Unit, private val onBitrate: (Int) -> Unit) {
    private inner class Lane(val link: SecureLink) {
        val queue = LinkedBlockingQueue<ByteArray>()
        val queued = AtomicLong()
        val sent = AtomicLong()
        var lastSent = 0L
        @Volatile var alive = true
        private val writer = Thread({
            var lastWrite = System.currentTimeMillis()
            try {
                while (alive) {
                    val rec = queue.poll(KEEPALIVE_MS, TimeUnit.MILLISECONDS)
                    if (rec == null) {
                        // An idle link (Wi-Fi next to USB) stays open and ready to take over.
                        if (link.pcPings && System.currentTimeMillis() - lastWrite >= KEEPALIVE_MS) {
                            link.send(KEEPALIVE)
                            lastWrite = System.currentTimeMillis()
                        }
                        continue
                    }
                    link.send(rec)
                    lastWrite = System.currentTimeMillis()
                    queued.addAndGet(-rec.size.toLong())
                    sent.addAndGet(rec.size.toLong())
                }
            } catch (_: Exception) {
            }
            die()
        }, "phonecam-${link.via}-tx")
        private val reader = Thread({ link.readControl({ keyRequested() }, onBitrate); die() }, "phonecam-${link.via}-rx")

        fun start() { writer.isDaemon = true; reader.isDaemon = true; writer.start(); reader.start() }

        fun die() {
            if (!alive) return
            alive = false
            link.close()
            writer.interrupt()
            lanes.remove(this)
            queue.clear()
        }
    }

    private val lanes = CopyOnWriteArrayList<Lane>()
    @Volatile private var lastKey = 0L

    /** The PC asks for a key frame after a loss; several links may ask for the same loss at once. */
    private fun keyRequested() {
        val now = System.currentTimeMillis()
        if (now - lastKey < 300) return
        lastKey = now
        onKey()
    }

    fun add(link: SecureLink) { Lane(link).also { lanes.add(it); it.start() } }

    fun isEmpty() = lanes.isEmpty()
    fun vias() = lanes.map { it.link.via }.toSet()
    fun pcKey(): ByteArray? = lanes.firstOrNull()?.link?.pcKey

    /**
     * Queues a record. Picks the preferred link (USB first) while it has less than `lowWater` bytes waiting,
     * else the link with the least waiting. False when every link already holds more than `maxQueued` bytes:
     * the network is the bottleneck.
     */
    fun send(rec: ByteArray, maxQueued: Long, lowWater: Long): Boolean {
        val alive = lanes.filter { it.alive }
        if (alive.isEmpty()) throw IOException("all links closed")
        val ordered = alive.sortedBy { if (it.link.via == "usb") 0 else 1 }
        val lane = ordered.firstOrNull { it.queued.get() <= lowWater } ?: ordered.minByOrNull { it.queued.get() }!!
        if (lane.queued.get() > maxQueued) return false
        lane.queued.addAndGet(rec.size.toLong())
        lane.queue.put(rec)
        return true
    }

    /** kbit/s per link since the last call. */
    fun rates(ms: Long): List<Pair<String, Int>> = lanes.map { l ->
        val s = l.sent.get()
        val kbps = ((s - l.lastSent) * 8 / maxOf(ms, 1)).toInt()
        l.lastSent = s
        l.link.via to kbps
    }.sortedBy { it.first }

    fun closeAll() = lanes.toList().forEach { it.die() }

    companion object {
        private const val KEEPALIVE_MS = 1000L
        /** Record type 0: keeps a link alive, carries nothing (docs/PROTOCOL.md). */
        private val KEEPALIVE = byteArrayOf(0)
    }
}
