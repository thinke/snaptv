package io.github.thinke.snaptv.core

import io.github.thinke.snaptv.core.visual.Spectrum
import io.github.thinke.snaptv.core.visual.VisualBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class VisualTest {
    @Test
    fun spectrumPeaksAtToneFrequency() {
        val s = Spectrum()
        val tone = FloatArray(2048) { 0.5f * sin(2 * PI * 1000.0 * it / 48000).toFloat() }
        repeat(5) { s.update(tone, 48000, 1 / 60f) }
        val loudest = s.levels.indices.maxBy { s.levels[it] }
        assertEquals(s.bandOf(1000f, 48000).toFloat(), loudest.toFloat(), 1f)
        assertTrue(s.levels[loudest] > 0.8f)
        assertTrue("far bands stay quiet", s.levels[s.bandOf(8000f, 48000)] < 0.3f)
    }

    @Test
    fun windowFollowsHeardTimeNotWriteTime() {
        val v = VisualBuffer()
        // 100 ms written, heard starting 500 ms from now: nothing audible yet.
        val block = ShortArray(4800 * 2) { (it / 2).toShort() }
        v.onPlayed(block, 4800, 2, 48000, heardAtUs = 500_000)
        val out = FloatArray(480)
        assertFalse(v.window(100_000, out))
        // At 510 ms we hear frames 0..480 of the block.
        assertTrue(v.window(510_000, out))
        assertEquals(479 / 32768f, out.last(), 1e-6f)
    }

    @Test
    fun findsOnsetAheadOfNow() {
        val v = VisualBuffer()
        val block = ShortArray(9600 * 2) // 200 ms, heard from t = 1 s
        for (i in 4800 until 4810) { block[i * 2] = 20000; block[i * 2 + 1] = 20000 } // click 100 ms in
        v.onPlayed(block, 9600, 2, 48000, heardAtUs = 1_000_000)
        assertEquals(1_100_000L, v.onsetBetween(1_000_000, 1_200_000, 0.3f))
        assertEquals(null, v.onsetBetween(1_000_000, 1_090_000, 0.3f))
    }
}
