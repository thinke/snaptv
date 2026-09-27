package io.github.thinke.snaptv.core

import io.github.thinke.snaptv.core.codec.SampleFormat
import io.github.thinke.snaptv.core.sync.PcmChunk
import io.github.thinke.snaptv.core.sync.SyncBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SyncBufferTest {
    private val format = SampleFormat(48000, 16, 2)

    /** Chunk of [frames] frames whose left channel counts up from [firstValue]. */
    private fun ramp(startUs: Long, frames: Int, firstValue: Int = 1): PcmChunk {
        val s = ShortArray(frames * 2)
        for (i in 0 until frames) { s[i * 2] = ((firstValue + i) % 30000).toShort(); s[i * 2 + 1] = 0 }
        return PcmChunk(startUs, s, 2)
    }

    @Test
    fun padsWithSilenceWhenAudioStartsInsideBlock() {
        val b = SyncBuffer(format)
        b.add(ramp(1_000_000, 960))
        val out = ShortArray(480 * 2)
        assertTrue(b.read(out, 480, 1_000_000 - 5_000)) // 5 ms early = 240 frames of silence
        assertEquals(0, out[239 * 2].toInt())
        assertEquals(1, out[240 * 2].toInt())
    }

    @Test
    fun skipsIntoChunkWhenLate() {
        val b = SyncBuffer(format)
        b.add(ramp(1_000_000, 960))
        val out = ShortArray(480 * 2)
        b.read(out, 480, 1_000_000 + 2_000) // 2 ms late = skip 96 frames
        assertEquals(97, out[0].toInt())
    }

    @Test
    fun waitsWhileAudioIsInTheFuture() {
        val b = SyncBuffer(format)
        b.add(ramp(1_000_000, 480))
        val out = ShortArray(480 * 2) { 7 }
        assertFalse(b.read(out, 480, 0))
        assertTrue(out.all { it.toInt() == 0 })
    }

    @Test
    fun underrunGivesSilenceAndRestarts() {
        val b = SyncBuffer(format)
        b.add(ramp(0, 480))
        val out = ShortArray(960 * 2)
        b.read(out, 960, 0)
        assertEquals(0, out[500 * 2].toInt())
        assertEquals(1, b.stats().underruns)
        assertFalse(b.stats().playing)
    }

    /** A DAC whose clock runs [ppm] off from the server; the buffer must track it without resyncing. */
    private fun driftConverges(ppm: Double) {
        val b = SyncBuffer(format)
        val chunkFrames = 960 // 20 ms, like snapserver's chunk_ms=20
        var nextChunkUs = 0L
        var chunkIndex = 0
        fun feedUntil(us: Long) {
            while (nextChunkUs < us + 500_000) {
                b.add(ramp(nextChunkUs, chunkFrames, chunkIndex * chunkFrames))
                chunkIndex++
                nextChunkUs += chunkFrames * 1_000_000L / 48000
            }
        }
        val out = ShortArray(480 * 2)
        var playAt = 0.0
        val blockUs = 480 * 1_000_000.0 / 48000 * (1 + ppm / 1e6)
        var worst = 0L
        repeat(60 * 100) { i -> // 60 s of 10 ms blocks
            feedUntil(playAt.toLong())
            b.read(out, 480, playAt.toLong())
            if (i > 30 * 100) worst = maxOf(worst, abs(b.stats().medianErrorUs))
            playAt += blockUs
        }
        assertEquals("no hard resyncs", 0, b.stats().hardSyncs)
        assertTrue("settled within 0.5 ms, was $worst us", worst < 500)
    }

    @Test fun tracksFastDac() = driftConverges(200.0)
    @Test fun tracksSlowDac() = driftConverges(-200.0)

    @Test
    fun hardResyncsOnLargeJump() {
        val b = SyncBuffer(format)
        for (i in 0 until 100) b.add(ramp(i * 20_000L, 960))
        val out = ShortArray(480 * 2)
        b.read(out, 480, 0)
        b.read(out, 480, 10_000)
        b.read(out, 480, 500_000) // e.g. the output route changed under us
        assertEquals(1, b.stats().hardSyncs)
        assertTrue(abs(b.stats().lastErrorUs) > 100_000)
    }
}
