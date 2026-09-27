package io.github.thinke.snaptv.core

import io.github.thinke.snaptv.core.calibrate.ChirpDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ChirpDetectorTest {
    private val rate = 48000
    private val chirp = ChirpDetector.chirp(rate)

    /** A noisy "room" recording with the chirp arriving [delayFrames] after each scheduled start, plus an echo. */
    private fun room(starts: List<Int>, delayFrames: Int, gain: Float, noise: Float, seed: Int = 1): FloatArray {
        val r = Random(seed)
        val x = FloatArray(rate * 6) { (r.nextFloat() - 0.5f) * 2 * noise }
        for (s in starts) {
            for (i in chirp.indices) {
                val a = s + delayFrames + i
                if (a < x.size) x[a] += chirp[i] * gain
                val echo = a + rate / 50 // a wall 3.4 m away
                if (echo < x.size) x[echo] += chirp[i] * gain * 0.4f
            }
        }
        return x
    }

    private val starts = (0 until 6).map { rate / 2 + it * rate * 7 / 10 }

    @Test
    fun findsDelayThroughNoiseAndEchoes() {
        val delay = rate * 268 / 1000 // a soundbar's worth
        val rec = room(starts, delay, gain = 0.05f, noise = 0.02f)
        val arrivals = ChirpDetector.findArrivals(rec, chirp, starts, searchFrames = rate / 2)
        val delays = arrivals.mapIndexed { i, a -> a?.let { (it.frame - starts[i]) * 1000.0 / rate } }
        val result = ChirpDetector.summarize(delays)
        assertTrue(result.ok)
        assertEquals(6, result.heard)
        assertEquals(268.0, result.delayMs, 0.1)
    }

    @Test
    fun silenceIsNotAChirp() {
        val rec = FloatArray(rate * 6) { (Random(it).nextFloat() - 0.5f) * 0.02f }
        val arrivals = ChirpDetector.findArrivals(rec, chirp, starts, searchFrames = rate / 2)
        assertTrue(arrivals.all { it == null })
        assertFalse(ChirpDetector.summarize(arrivals.map { null }).ok)
    }

    @Test
    fun disagreeingChirpsAreNotTrusted() {
        val r = ChirpDetector.summarize(listOf(100.0, 112.0, 124.0, 136.0, 148.0))
        assertFalse(r.ok)
    }

    @Test
    fun oneStragglerIsIgnored() {
        val r = ChirpDetector.summarize(listOf(250.0, 251.0, 250.5, 290.0, 249.8, 250.2))
        assertTrue(r.ok)
        assertEquals(5, r.heard)
        assertEquals(250.2, r.delayMs, 0.5)
    }

    @Test
    fun windowTooShortIsNull() {
        val rec = FloatArray(100)
        assertNull(ChirpDetector.findArrivals(rec, chirp, listOf(0), searchFrames = 50)[0])
    }
}
