package io.github.thinke.snaptv.core.source

import io.github.thinke.snaptv.core.MonotonicClock
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Feeds a click track into a snapserver TCP source (`tcp://…?mode=server`, 48000:16:2), so
 * every room playing that stream clicks at the same moment by snapcast's own timing.
 *
 * Audio is written in real time from our monotonic clock with a small lead, and never ahead of
 * it: snapserver drains a TCP source at exactly real time, so anything sent early just sits in
 * its socket as extra delay.
 */
class ClickTrackSource(
    private val host: String,
    private val port: Int,
    private val clock: MonotonicClock = MonotonicClock.System,
) {
    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile private var socket: Socket? = null

    /** Why the last attempt stopped, for the UI; null while running fine. */
    @Volatile var error: String? = null
        private set

    fun start() {
        if (running) return
        running = true
        error = null
        thread = Thread({ run() }, "sync-test-source").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        thread?.join(1000)
        thread = null
    }

    private fun run() {
        try {
            val s = Socket()
            socket = s
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), 5000)
            stream(s.getOutputStream())
        } catch (e: Exception) {
            if (running) error = e.message ?: e.javaClass.simpleName
        } finally {
            runCatching { socket?.close() }
        }
    }

    private fun stream(out: OutputStream) {
        val blockFrames = RATE / 100
        val buf = ByteBuffer.allocate(blockFrames * 4).order(ByteOrder.LITTLE_ENDIAN)
        val startUs = clock.nowUs()
        var sent = 0L
        while (running) {
            val dueFrames = (clock.nowUs() - startUs) * RATE / 1_000_000L + LEAD_FRAMES
            if (sent >= dueFrames) {
                Thread.sleep(5)
                continue
            }
            buf.clear()
            for (i in 0 until blockFrames) {
                val v = (sample(sent + i) * 32767).toInt().toShort()
                buf.putShort(v).putShort(v)
            }
            out.write(buf.array())
            sent += blockFrames
        }
    }

    companion object {
        const val RATE = 48000
        private const val LEAD_FRAMES = RATE / 50 // 20 ms

        /** One click per second; every fourth is higher, so you can tell where the bar starts. */
        fun sample(frame: Long): Float {
            val inSecond = (frame % RATE).toInt()
            if (inSecond >= CLICK_FRAMES) return 0f
            val accent = (frame / RATE) % 4 == 0L
            val hz = if (accent) 3000.0 else 2000.0
            val t = inSecond.toDouble() / RATE
            // ~4 ms to fade: sharp enough to time by ear, long enough to hear in the next room.
            return (0.7 * exp(-t * 250) * sin(2 * PI * hz * t)).toFloat()
        }

        private const val CLICK_FRAMES = RATE * 15 / 1000
    }
}
