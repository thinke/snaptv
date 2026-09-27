package io.github.thinke.snaptv.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import io.github.thinke.snaptv.core.visual.DemoSong
import io.github.thinke.snaptv.core.visual.VisualBuffer
import io.github.thinke.snaptv.ui.VisualStyle
import io.github.thinke.snaptv.ui.Visualizer
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Dev tool: renders every visualizer style to PNGs, driven by [DemoSong], so styles can be looked at without a server or a screen.
 *
 *   ./gradlew :desktop:renderVisuals -Pout=/some/dir
 */
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/visuals").apply { mkdirs() }
    val rate = 48000
    for (style in VisualStyle.entries) {
        val visual = VisualBuffer()
        val song = DemoSong(rate)
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
