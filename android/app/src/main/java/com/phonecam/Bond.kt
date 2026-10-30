package com.phonecam

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/** Several links to the same PC session. Each record goes to the link with the least data waiting. */
class Bond(private val onKey: () -> Unit, private val onBitrate: (Int) -> Unit) {
    private inner class Lane(val link: SecureLink) {
        val queue = LinkedBlockingQueue<ByteArray>()
        val queued = AtomicLong()
        val sent = AtomicLong()
        var lastSent = 0L
        @Volatile var alive = true
        private val writer = Thread({
            try {
                while (alive) {
                    val rec = queue.take()
                    link.send(rec)
                    queued.addAndGet(-rec.size.toLong())
                    sent.addAndGet(rec.size.toLong())
                }
            } catch (_: Exception) {
            }
            die()
        }, "phonecam-${link.via}-tx")
        private val reader = Thread({ link.readControl(onKey, onBitrate); die() }, "phonecam-${link.via}-rx")

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
    private var rr = 0

    fun add(link: SecureLink) { Lane(link).also { lanes.add(it); it.start() } }

    fun isEmpty() = lanes.isEmpty()
    fun vias() = lanes.map { it.link.via }.toSet()
    fun pcKey(): ByteArray? = lanes.firstOrNull()?.link?.pcKey

    /** False when every link already holds more than `maxQueued` bytes (the network is the bottleneck). */
    fun send(rec: ByteArray, maxQueued: Long): Boolean {
        val alive = lanes.filter { it.alive }
        if (alive.isEmpty()) throw IOException("all links closed")
        // Least data waiting wins; ties rotate, so every link carries traffic and stays alive.
        val start = (rr++ and Int.MAX_VALUE) % alive.size
        val lane = (alive.indices).map { alive[(start + it) % alive.size] }.minByOrNull { it.queued.get() }!!
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
}
