package io.github.thinke.snaptv.core.visual

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * A made-up song for looking at and timing visualizers without a server: 124 bpm, kick on the
 * beat, hi-hat between, a bass line and a slow pad. Mono 16-bit.
 */
class DemoSong(private val rate: Int) {
    private var n = 0L
    private val random = Random(3)
    private val beat = rate * 60 / 124
    private val bass = floatArrayOf(55f, 55f, 65.4f, 49f)
    private val chords = arrayOf(floatArrayOf(220f, 261.6f, 329.6f), floatArrayOf(196f, 246.9f, 293.7f))

    fun next(frames: Int): ShortArray = ShortArray(frames) {
        val t = n.toFloat() / rate
        val inBeat = (n % beat).toFloat() / rate
        val bar = (n / (beat * 4)).toInt()
        var v = 0f
        v += 0.7f * sin(2 * PI.toFloat() * (50f + 90f * exp(-inBeat * 30f)) * inBeat) * exp(-inBeat * 7f) // kick
        v += 0.25f * sin(2 * PI.toFloat() * bass[(n / beat % 4).toInt()] * t)
        for (f in chords[bar % 2]) v += 0.06f * sin(2 * PI.toFloat() * f * t) * (0.6f + 0.4f * sin(t * 0.7f))
        val offBeat = ((n + beat / 2) % beat).toFloat() / rate
        v += 0.12f * (random.nextFloat() * 2 - 1) * exp(-offBeat * 60f) // hi-hat
        n++
        (v.coerceIn(-1f, 1f) * 30000).toInt().toShort()
    }

    /** Feeds [visual] in real time from a background thread until [stop] is called. */
    fun play(visual: VisualBuffer): () -> Unit {
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val t = Thread({
            val startNs = System.nanoTime()
            var fed = 0L
            while (running.get()) {
                val nowUs = (System.nanoTime() - startNs) / 1000
                while (fed * 1_000_000L / rate < nowUs + 100_000) { // stay 100 ms ahead
                    visual.onPlayed(next(480), 480, 1, rate, startNs / 1000 + fed * 1_000_000L / rate)
                    fed += 480
                }
                Thread.sleep(10)
            }
        }, "demo-song").apply { isDaemon = true; start() }
        return { running.set(false); t.join(500) }
    }
}
