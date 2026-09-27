package io.github.thinke.snaptv.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import io.github.thinke.snaptv.core.visual.VisualBuffer
import io.github.thinke.snaptv.ui.VisualStyle
import io.github.thinke.snaptv.ui.Visualizer
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Dev tool: renders every visualizer style to PNGs, driven by a made-up song (kick, bass,
 * chords, hi-hat), so styles can be looked at without a server or a screen.
 *
 *   ./gradlew :desktop:renderVisuals -Pout=/some/dir
 */
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/visuals").apply { mkdirs() }
    val rate = 48000
    for (style in VisualStyle.entries) {
        val visual = VisualBuffer()
        val song = Song(rate)
        val scene = ImageComposeScene(1280, 720, Density(1f)) {
            Box(Modifier.fillMaxSize().background(Color(0xFF05060C))) { Visualizer(visual, style, Modifier.fillMaxSize()) }
        }
        val start = System.nanoTime()
        var fed = 0L
        // Real time, since the visualizer reads the audio "heard now" by System.nanoTime.
        for (frame in 0 until 60 * 4) {
            val nowUs = (System.nanoTime() - start) / 1000
            while (fed * 1_000_000L / rate < nowUs + 100_000) { // stay 100 ms ahead
                val block = song.next(480)
                visual.onPlayed(block, 480, 1, rate, start / 1000 + fed * 1_000_000L / rate)
                fed += 480
            }
            val image = scene.render(System.nanoTime() - start)
            if (frame % 80 == 79) {
                val f = File(out, "${style.name.lowercase()}-${frame / 80}.png")
                f.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                println(f)
            }
            Thread.sleep(16)
        }
        scene.close()
    }
}

/** 124 bpm: kick on the beat, hi-hat between, a bass line and a slow pad. */
private class Song(private val rate: Int) {
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
}
