package io.github.thinke.snaptv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.thinke.snaptv.core.visual.Spectrum
import io.github.thinke.snaptv.core.visual.VisualBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class VisualStyle(val label: String) {
    Bars("Spectrum"),
    Halo("Halo"),
    Scope("Oscilloscope"),
    Ridges("Ridges"),
    Starfield("Starfield"),
    Pulse("Pulse"),
    Liquid("Liquid"),
    ;

    companion object {
        fun of(index: Int) = entries[Math.floorMod(index, entries.size)]
    }
}

/**
 * Full-screen visualizer. Each frame pulls the audio that is audible *now* (not the audio
 * just written), so it stays in step with the sound including the TV's own delay.
 */
@Composable
fun Visualizer(visual: VisualBuffer, style: VisualStyle, modifier: Modifier = Modifier) {
    val spectrum = remember { Spectrum(fftSize = 2048, bands = 48) }
    val samples = remember { FloatArray(2048) }
    val path = remember { Path() }
    val scene = remember { Scene(spectrum.bands) }
    var frameNanos by remember { mutableLongStateOf(0L) }

    LaunchedEffect(visual) {
        var last = 0L
        while (true) {
            withFrameNanos { t ->
                val dt = if (last == 0L) 1 / 60f else ((t - last) / 1e9f).coerceIn(0.001f, 0.1f)
                last = t
                // System.nanoTime is the clock AudioTrack timestamps use.
                if (visual.window(System.nanoTime() / 1000, samples)) spectrum.update(samples, visual.sampleRate, dt)
                else spectrum.decay(dt)
                scene.step(spectrum, samples, visual.sampleRate, dt)
                frameNanos = t
            }
        }
    }

    Canvas(modifier) {
        val time = frameNanos / 1e9f // reading this state redraws every frame
        drawBackdrop(spectrum, time)
        when (style) {
            VisualStyle.Bars -> drawBars(spectrum, time)
            VisualStyle.Halo -> drawHalo(spectrum, time)
            VisualStyle.Scope -> drawScope(scene, samples, spectrum, path, time)
            VisualStyle.Ridges -> drawRidges(scene, spectrum, time)
            VisualStyle.Starfield -> drawStarfield(scene, spectrum, time)
            VisualStyle.Pulse -> drawPulse(scene, spectrum, time)
            VisualStyle.Liquid -> drawLiquid(scene, spectrum, path, time)
        }
    }
}

/** Slowly drifting hue, so a long listening session doesn't look static. */
internal fun palette(t: Float, time: Float, saturation: Float = 0.75f, value: Float = 1f): Color {
    val hue = ((170f + t * 150f + time * 4f) % 360f + 360f) % 360f
    return Color.hsv(hue, saturation, value)
}

private fun DrawScope.drawBackdrop(s: Spectrum, time: Float) {
    val glow = palette(0.3f, time, 0.6f, 0.5f)
    drawRect(
        Brush.radialGradient(
            colors = listOf(glow.copy(alpha = 0.10f + 0.35f * s.bass), Color.Transparent),
            center = Offset(size.width / 2, size.height * 0.62f),
            radius = max(size.width, size.height) * (0.45f + 0.2f * s.bass),
        )
    )
}

private fun DrawScope.drawBars(s: Spectrum, time: Float) {
    val n = s.bands
    val slot = size.width * 0.9f / n
    val barW = slot * 0.72f
    val left = size.width * 0.05f
    val baseline = size.height * 0.70f
    val maxH = size.height * 0.55f
    for (i in 0 until n) {
        val color = palette(i / (n - 1f), time)
        val h = max(2f, s.levels[i] * maxH)
        val x = left + i * slot + (slot - barW) / 2
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(color, color.copy(alpha = 0.55f)), startY = baseline - h, endY = baseline),
            topLeft = Offset(x, baseline - h),
            size = Size(barW, h),
            cornerRadius = CornerRadius(barW / 3),
        )
        // reflection on the "floor"
        drawRect(
            brush = Brush.verticalGradient(listOf(color.copy(alpha = 0.22f), Color.Transparent), startY = baseline + 6, endY = baseline + 6 + h * 0.4f),
            topLeft = Offset(x, baseline + 6),
            size = Size(barW, h * 0.4f),
        )
        val peakY = baseline - max(2f, s.peaks[i] * maxH) - 8f
        drawRect(color.copy(alpha = 0.9f), Offset(x, peakY), Size(barW, 4f))
    }
}

private fun DrawScope.drawHalo(s: Spectrum, time: Float) {
    val center = Offset(size.width / 2, size.height / 2)
    val unit = min(size.width, size.height)
    val baseR = unit * 0.2f * (1f + 0.18f * s.bass)
    val spokes = s.bands * 2
    val rotation = time * 0.08f
    val stroke = (2 * PI.toFloat() * baseR / spokes) * 0.55f
    drawCircle(palette(0.1f, time, 0.5f, 0.9f).copy(alpha = 0.08f + 0.25f * s.loudness), baseR * 0.92f, center)
    for (i in 0 until spokes) {
        // mirror the spectrum so low frequencies sit at the top and bottom of the ring
        val band = if (i < s.bands) i else spokes - 1 - i
        val level = s.levels[band]
        val a = rotation + i * 2 * PI.toFloat() / spokes
        val dir = Offset(cos(a), sin(a))
        val color = palette(band / (s.bands - 1f), time)
        drawLine(
            color = color.copy(alpha = 0.35f + 0.65f * level),
            start = center + dir * baseR,
            end = center + dir * (baseR + 6f + level * unit * 0.26f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}
