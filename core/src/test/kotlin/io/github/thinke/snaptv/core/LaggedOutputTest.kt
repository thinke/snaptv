package io.github.thinke.snaptv.core

import io.github.thinke.snaptv.core.codec.Decoder
import io.github.thinke.snaptv.core.codec.LaggedOutput
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaggedOutputTest {
    private fun frames(n: Int, v: Int) = ShortArray(n * 2) { v.toShort() }

    @Test
    fun immediateOutputIsNotCarried() {
        val l = LaggedOutput(2)
        val pts = l.beginChunk()
        assertFalse(l.hasOwnOutput)
        l.add(pts, frames(960, 1))
        assertTrue(l.hasOwnOutput)
        assertEquals(1920, l.finish().size)
        assertEquals(0, l.carriedFrames)
    }

    @Test
    fun outputOneChunkLateIsCarried() {
        val l = LaggedOutput(2)
        val p0 = l.beginChunk()
        assertEquals(0, l.finish().size) // nothing out yet
        val p1 = l.beginChunk()
        l.add(p0, frames(480, 1))
        l.add(p1, frames(960, 2))
        val out = l.finish()
        assertEquals(1440 * 2, out.size)
        assertEquals(480, l.carriedFrames)
        assertEquals(1, out[0].toInt())
        assertEquals(2, out[480 * 2].toInt())
    }

    @Test
    fun interpolatedTimestampsStillMapToTheirChunk() {
        val l = LaggedOutput(1)
        val p0 = l.beginChunk()
        l.finish()
        val p1 = l.beginChunk()
        l.add(p0 + 20_000, frames(10, 1)) // a codec that advanced the timestamp within chunk 0
        l.add(p1 + 20_000, frames(10, 2))
        l.finish()
        assertEquals(20, l.carriedFrames)
    }

    /**
     * The whole point: with a codec that answers a chunk late, the audio still lands on the
     * server timeline where snapserver put it. Chunks are 20 ms at 48 kHz, timestamps contiguous.
     */
    @Test
    fun timestampsStayOnTheServerTimeline() {
        val rate = 48000
        val chunkFrames = 960
        val l = LaggedOutput(1)
        val pending = ArrayDeque<Pair<Long, Int>>() // what the "codec" still holds
        val placed = ArrayList<Pair<Long, Short>>() // (start time, first sample value) of each result
        for (k in 0 until 6) {
            val tsUs = 1_000_000L + k * 20_000L
            pending.addLast(l.beginChunk() to k)
            // the codec releases buffers with a varying lag of 0..2 chunks
            val release = if (k % 3 == 2) pending.size else pending.size - 1
            repeat(release) {
                val (pts, idx) = pending.removeFirst()
                l.add(pts, ShortArray(chunkFrames) { (idx * 1000 + it).toShort() })
            }
            val pcm = l.finish()
            if (pcm.isNotEmpty()) placed += Decoder.chunkStartUs(tsUs, l.carriedFrames, rate) to pcm[0]
        }
        for ((startUs, first) in placed) {
            val idx = first / 1000
            assertEquals("chunk $idx", 1_000_000L + idx * 20_000L, startUs)
        }
        assertTrue(placed.size >= 3)
    }

    @Test
    fun chunkStartMovesBackByCarriedDuration() {
        assertEquals(1_000_000L, Decoder.chunkStartUs(1_000_000L, 0, 48000))
        assertEquals(990_000L, Decoder.chunkStartUs(1_000_000L, 480, 48000))
        assertEquals(1_000_000L - 2_500L, Decoder.chunkStartUs(1_000_000L, 120, 48000))
    }

    @Test
    fun concatenatesInCodecOrder() {
        val l = LaggedOutput(1)
        val p = l.beginChunk()
        l.add(p, shortArrayOf(1, 2))
        l.add(p, ShortArray(0))
        l.add(p, shortArrayOf(3))
        assertArrayEquals(shortArrayOf(1, 2, 3), l.finish())
    }
}
