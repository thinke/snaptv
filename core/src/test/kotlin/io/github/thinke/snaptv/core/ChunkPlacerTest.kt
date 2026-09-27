package io.github.thinke.snaptv.core

import io.github.thinke.snaptv.core.codec.Decoder
import io.github.thinke.snaptv.core.codec.SampleFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ChunkPlacerTest {
    private val format = SampleFormat(48000, 16, 1)

    /** Each chunk is 480 frames (10 ms) valued by its payload byte, returned one call late. */
    private class LateDecoder : Decoder {
        var held: ShortArray? = null
        var fail = false
        override var carriedFrames = 0
        override fun setHeader(payload: ByteArray) = SampleFormat(48000, 16, 1)
        override fun decode(payload: ByteArray): ShortArray {
            if (fail) throw IllegalStateException("boom")
            val out = held ?: ShortArray(0)
            held = ShortArray(480) { payload[0].toShort() }
            carriedFrames = out.size
            return out
        }
    }

    @Test
    fun contiguousCarriedAudioKeepsItsOwnTime() {
        val p = ChunkPlacer(LateDecoder(), format)
        assertNull(p.place(1_000_000, byteArrayOf(1)))
        val c = p.place(1_010_000, byteArrayOf(2))!!
        assertEquals(1_000_000, c.startUs)
        assertEquals(1, c.samples[0].toInt())
        assertEquals(990_000 + 20_000, p.place(1_020_000, byteArrayOf(3))!!.startUs)
    }

    @Test
    fun carriedAudioIsDroppedWhenTheTimelineJumps() {
        val p = ChunkPlacer(LateDecoder(), format)
        p.place(1_000_000, byteArrayOf(1))
        p.place(1_010_000, byteArrayOf(2))
        // the source restarted: the next chunk is a minute later
        val c = p.place(61_000_000, byteArrayOf(3))
        assertNull("the stale chunk 2 is not played before the new audio", c)
        // chunk 3 came out late and is contiguous with chunk 4, so it is placed normally
        val d = p.place(61_010_000, byteArrayOf(4))!!
        assertEquals(61_000_000, d.startUs)
        assertEquals(3, d.samples[0].toInt())
    }

    @Test
    fun partlyCarriedChunkKeepsOnlyItsOwnAudioAfterAJump() {
        val d = object : Decoder {
            var n = 0
            override var carriedFrames = 0
            override fun setHeader(payload: ByteArray) = SampleFormat(48000, 16, 1)
            override fun decode(payload: ByteArray): ShortArray {
                n++
                carriedFrames = if (n == 1) 0 else 240
                return ShortArray(480) { if (it < carriedFrames) -1 else payload[0].toShort() }
            }
        }
        val p = ChunkPlacer(d, format)
        assertEquals(1_000_000, p.place(1_000_000, byteArrayOf(1))!!.startUs)
        val c = p.place(9_000_000, byteArrayOf(2))!!
        assertEquals(9_000_000, c.startUs)
        assertEquals(240, c.frames)
        assertEquals(2, c.samples[0].toInt())
    }

    @Test
    fun singleFailuresAreSkippedButARunOfThemIsReported() {
        val d = LateDecoder()
        val p = ChunkPlacer(d, format)
        d.fail = true
        repeat(ChunkPlacer.MAX_FAILURES - 1) { assertNull(p.place(it * 10_000L, byteArrayOf(1))) }
        d.fail = false
        p.place(0, byteArrayOf(1)) // a success resets the count
        d.fail = true
        repeat(ChunkPlacer.MAX_FAILURES - 1) { assertNull(p.place(it * 10_000L, byteArrayOf(1))) }
        val e = assertThrows(DecoderFailedException::class.java) { p.place(0, byteArrayOf(1)) }
        assertEquals("boom", e.cause!!.message)
    }
}
