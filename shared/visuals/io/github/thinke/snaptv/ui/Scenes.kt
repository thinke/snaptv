package io.github.thinke.snaptv.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import io.github.thinke.snaptv.core.visual.Spectrum
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * What the newer styles remember between frames: a history of spectra (Ridges), a beat
 * detector and the rings it sets off (Pulse), and the stars (Starfield). Fixed-size arrays, so
 * nothing is allocated per frame.
 */
internal class Scene(private val bands: Int) {
    /** Spectrum snapshots, [RIDGE_ROWS] of them, oldest at [head] + 1. */
    val history = Array(RIDGE_ROWS) { FloatArray(bands) }
    var head = 0
        private set
    private var sinceRow = 0f

    /** 1 on a beat, falling towards 0 after it. */
    var beat = 0f
        private set
    private var energyAverage = 0f
    private var sinceBeat = 1f

    /** Ridges' per-row scratch: the ground outline, the ridge line and their paints. */
    val ridgeFill = Path()
    val ridgeLine = Path()
    val ridgePaint = Paint().apply { style = PaintingStyle.Stroke; strokeWidth = 0f; isAntiAlias = true } // hairline
    val ridgeGround = Paint().apply { isAntiAlias = false }
    val liquidFill = Paint().apply { isAntiAlias = false }

    val ringRadius = FloatArray(MAX_RINGS)
    val ringHue = FloatArray(MAX_RINGS)
    val ringStrength = FloatArray(MAX_RINGS)
    var rings = 0
        private set

    private val random = Random(7)
    val starX = FloatArray(STARS)
    val starY = FloatArray(STARS)
    val starZ = FloatArray(STARS) { random.nextFloat() }
    val starHue = FloatArray(STARS)
    /** How far the stars moved this frame, for their streaks. */
    var starStep = 0f
        private set

    init {
        for (i in 0 until STARS) placeStar(i, starZ[i])
    }

    /**
     * [samples] is the audio heard now, newest last. Beats come from its bass energy directly:
     * the smoothed spectrum barely dips between kicks, so it can't show them.
     */
    fun step(s: Spectrum, samples: FloatArray, rate: Int, dt: Float) {
        sinceRow += dt
        if (sinceRow >= ROW_SECONDS) {
            sinceRow = 0f
            head = (head + 1) % RIDGE_ROWS
            s.levels.copyInto(history[head])
        }

        // A beat: the last 20 ms of bass (below ~150 Hz) well above its recent average, and not
        // twice within 180 ms.
        val recent = min(samples.size, max(1, rate / 50))
        val k = 1f - exp(-2f * PI.toFloat() * 150f / max(rate, 1))
        var low = 0f
        var energy = 0f
        for (i in samples.size - recent * 3 until samples.size) {
            if (i < 0) continue
            low += (samples[i] - low) * k
            if (i >= samples.size - recent) energy += low * low
        }
        energy /= recent
        sinceBeat += dt
        val isBeat = energy > energyAverage * 2.2f + 1e-5f && sinceBeat > 0.18f
        energyAverage += (energy - energyAverage) * (1f - exp(-dt / 0.5f))
        if (isBeat) {
            sinceBeat = 0f
            beat = 1f
            addRing(s)
        } else {
            beat *= exp(-dt / 0.22f)
        }

        var kept = 0
        for (i in 0 until rings) {
            val r = ringRadius[i] + dt * (0.35f + 0.4f * ringStrength[i])
            if (r < 1.3f) {
                ringRadius[kept] = r; ringHue[kept] = ringHue[i]; ringStrength[kept] = ringStrength[i]
                kept++
            }
        }
        rings = kept

        starStep = dt * (0.08f + 0.9f * s.loudness + 1.6f * beat)
        for (i in 0 until STARS) {
            starZ[i] -= starStep
            if (starZ[i] <= 0.03f) placeStar(i, 1f)
        }
    }

    private var travel = 0f

    /** The numbers a GPU style needs this frame. */
    fun fillShaderInputs(inputs: ShaderInputs, s: Spectrum, time: Float, dt: Float) {
        val per = bands / ShaderInputs.BANDS
        for (b in 0 until ShaderInputs.BANDS) {
            var sum = 0f
            for (i in 0 until per) sum += s.levels[b * per + i]
            inputs.bands[b] = sum / per
        }
        // Flying speed follows the music; integrated, so a change of speed never jumps.
        travel += dt * (0.25f + 0.9f * s.loudness + 1.4f * beat)
        // Everything repeats every 20 units, so wrapping there is seamless and keeps it exact.
        if (travel >= TRAVEL_WRAP) travel -= TRAVEL_WRAP
        inputs.ringPhase = (travel * 3f) % 1f
        inputs.ribPhase = (travel * 0.15f) % 1f
        inputs.huePhase = (travel * 0.05f) % 1f
        inputs.time = time
        inputs.bass = s.bass
        inputs.loudness = s.loudness
        inputs.beat = beat
        inputs.hue = ((170f + time * 4f) % 360f) / 360f
        inputs.swayX = 0.06f * sin(time * 0.31f)
        inputs.swayY = 0.06f * cos(time * 0.23f)
        // The flash: the opposite hue, pale, as strong as the beat.
        val flash = Color.hsv(((inputs.hue + 0.5f) % 1f) * 360f, 0.4f, 1f)
        inputs.flash[0] = flash.red * beat * 0.8f
        inputs.flash[1] = flash.green * beat * 0.8f
        inputs.flash[2] = flash.blue * beat * 0.8f
    }

    private fun addRing(s: Spectrum) {
        if (rings == MAX_RINGS) return
        ringRadius[rings] = 0f
        // Colour from where the energy is: bright, trebly music gives warmer rings.
        var weighted = 0f
        var total = 0f
        for (b in 0 until bands) { weighted += b * s.levels[b]; total += s.levels[b] }
        ringHue[rings] = if (total > 0f) weighted / total / (bands - 1) else 0.3f
        ringStrength[rings] = max(s.bass, 0.3f)
        rings++
    }

    private fun placeStar(i: Int, z: Float) {
        starX[i] = random.nextFloat() * 2f - 1f
        starY[i] = random.nextFloat() * 2f - 1f
        starZ[i] = z
        starHue[i] = random.nextFloat()
    }

    companion object {
        const val RIDGE_ROWS = 28
        const val ROW_SECONDS = 1f / 24
        const val MAX_RINGS = 16
        const val STARS = 220
        const val TRAVEL_WRAP = 20f
    }
}

/**
 * A scrolling landscape of the last second of spectra across the whole screen, lows in the
 * middle (Unknown Pleasures style).
 *
 * Drawn to suit slow TV CPUs, where Skia cuts anti-aliased fills and any stroke wider than a
 * pixel into triangles on the CPU every frame (measured on a TCL TV: ~23 fps drawn that way).
 * Here each row's ground is one fill with anti-aliasing off and each line a 1-pixel hairline
 * path, both of which the GPU draws directly; the line hides the fill's rough edge. Front
 * rows get a second hairline a pixel lower to look bolder.
 */
internal fun DrawScope.drawRidges(scene: Scene, s: Spectrum, time: Float) {
    val n = s.bands
    val rows = Scene.RIDGE_ROWS
    val points = 2 * n
    val top = size.height * 0.16f
    val spacing = (size.height * 0.97f - top) / (rows - 1)
    val amp = size.height * 0.24f
    val step = size.width / (points - 1)
    val fill = scene.ridgeFill
    val line = scene.ridgeLine
    // One ground colour for the whole landscape, from the back row to the bottom edge, so no
    // stripes of the backdrop show between rows; the glow stays in the sky above.
    val ground = scene.ridgeGround
    ground.color = palette(0.5f, time, 0.5f, 0.075f)
    drawIntoCanvas { canvas ->
        canvas.drawRect(0f, top, size.width, size.height, ground)
        for (k in 0 until rows) {
            // Oldest first, at the back; each row hides most of what's behind it.
            val levels = scene.history[(scene.head + 1 + k) % rows]
            val depth = k / (rows - 1f)
            val base = top + k * spacing
            fill.reset()
            line.reset()
            for (j in 0 until points) {
                val band = if (j < n) n - 1 - j else j - n
                // Taper to the edges, so each ridge rises out of flat ground.
                val edge = sin(PI.toFloat() * j / (points - 1))
                val x = j * step
                val y = base - levels[band] * amp * edge * edge * (0.55f + 0.45f * depth)
                if (j == 0) { fill.moveTo(x, y); line.moveTo(x, y) } else { fill.lineTo(x, y); line.lineTo(x, y) }
            }
            // Down to this row's own ground line only: every line behind lies above it, and less
            // area keeps the GPU inside the frame.
            fill.lineTo(size.width, base + 1f)
            fill.lineTo(0f, base + 1f)
            fill.close()
            canvas.drawPath(fill, ground)
            val hue = 0.15f + 0.7f * depth
            val paint = scene.ridgePaint
            paint.color = palette(hue, time, 0.55f, 0.6f + 0.4f * depth).copy(alpha = 0.4f + 0.6f * depth)
            canvas.drawPath(line, paint)
            if (depth > 0.45f) {
                canvas.translate(0f, 1.2f)
                canvas.drawPath(line, paint)
                canvas.translate(0f, -1.2f)
            }
        }
    }
}

/** Stars flying at you: faster when the music is loud, a jolt on every beat. */
internal fun DrawScope.drawStarfield(scene: Scene, s: Spectrum, time: Float) {
    val center = Offset(size.width / 2, size.height / 2)
    val unit = max(size.width, size.height) * 0.5f
    val trail = max(scene.starStep * 4f, 0.01f)
    for (i in 0 until Scene.STARS) {
        val z = scene.starZ[i]
        val head = center + Offset(scene.starX[i] / z, scene.starY[i] / z) * unit * 0.35f
        if (head.x < 0 || head.y < 0 || head.x > size.width || head.y > size.height) continue
        val zTail = min(1f, z + trail)
        val tail = center + Offset(scene.starX[i] / zTail, scene.starY[i] / zTail) * unit * 0.35f
        val near = 1f - z
        val color = palette(scene.starHue[i] * 0.5f + 0.4f * s.loudness, time, 0.35f + 0.4f * scene.beat, 1f)
        drawLine(color.copy(alpha = (0.2f + 0.8f * near).coerceIn(0f, 1f)), tail, head, strokeWidth = 1f + 4f * near * near, cap = StrokeCap.Round)
    }
}

/** Rings rippling out from the centre on every beat, around a core that breathes with the bass. */
internal fun DrawScope.drawPulse(scene: Scene, s: Spectrum, time: Float) {
    val center = Offset(size.width / 2, size.height / 2)
    val unit = min(size.width, size.height)
    val maxR = max(size.width, size.height) * 0.62f
    for (i in 0 until scene.rings) {
        val r = scene.ringRadius[i]
        val fade = (1f - r / 1.3f).coerceIn(0f, 1f)
        val color = palette(scene.ringHue[i], time)
        drawCircle(color.copy(alpha = 0.12f * fade), r * maxR, center, style = Stroke(width = 28f * fade + 4f))
        drawCircle(color.copy(alpha = 0.85f * fade), r * maxR, center, style = Stroke(width = 2f + 5f * scene.ringStrength[i] * fade))
    }
    val coreR = unit * (0.06f + 0.07f * s.bass + 0.03f * scene.beat)
    val core = palette(0.25f + 0.5f * s.loudness, time, 0.6f, 1f)
    drawCircle(
        Brush.radialGradient(listOf(core.copy(alpha = 0.9f), core.copy(alpha = 0.25f), Color.Transparent), center, coreR * 2.2f),
        coreR * 2.2f,
        center,
    )
    // Treble as a sparkle of dots on a slow orbit.
    val n = s.bands
    for (b in n / 2 until n) {
        val level = s.levels[b]
        if (level < 0.08f) continue
        val a = time * 0.25f + b * 2.39996f // golden angle: evenly scattered
        val d = unit * (0.16f + 0.2f * ((b - n / 2f) / (n / 2f)))
        drawCircle(palette(b / (n - 1f), time).copy(alpha = level), 2f + 5f * level, center + Offset(cos(a), sin(a)) * d)
    }
}

/** Layered soft blobs, pushed out of shape by the spectrum. */
internal fun DrawScope.drawLiquid(scene: Scene, s: Spectrum, path: Path, time: Float) {
    val fill = scene.liquidFill
    val edge = scene.ridgePaint
    val center = Offset(size.width / 2, size.height / 2)
    val unit = min(size.width, size.height)
    val n = s.bands
    val points = 120
    for (layer in 2 downTo 0) {
        val base = unit * (0.13f + 0.07f * layer) * (1f + 0.25f * s.bass)
        val spin = time * (0.05f + 0.04f * layer) * (if (layer % 2 == 0) 1 else -1)
        path.reset()
        for (p in 0..points) {
            val a = spin + p * 2 * PI.toFloat() / points
            // Mirror the bands around the circle, lows at the top and bottom; interpolate for a smooth edge.
            val t = (if (p <= points / 2) p else points - p) / (points / 2f) * (n - 1) * 0.8f
            val i = t.toInt().coerceAtMost(n - 2)
            val level = s.levels[i] + (s.levels[i + 1] - s.levels[i]) * (t - i)
            val wobble = 0.04f * sin(a * 3 + time * 1.3f + layer) + 0.03f * sin(a * 5 - time * 0.9f)
            val r = base * (1f + wobble + level * (0.45f + 0.25f * layer))
            val pt = center + Offset(cos(a), sin(a)) * r
            if (p == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
        }
        path.close()
        val color = palette(0.15f + 0.3f * layer + 0.2f * s.loudness, time, 0.7f, 1f)
        // As in Ridges: fill without anti-aliasing and a hairline edge, which the GPU draws
        // directly (smooth fills and wider strokes are cut up on the CPU, too slow on TVs).
        drawIntoCanvas { canvas ->
            // Solid rather than a gradient: three overlapping gradient blobs overran the GPU.
            fill.color = color.copy(alpha = 0.32f + 0.08f * layer)
            canvas.drawPath(path, fill)
            edge.color = color.copy(alpha = 0.8f)
            canvas.drawPath(path, edge)
        }
    }
}

/**
 * The waveform. Its glow is a few fainter hairlines offset up and down rather than wide
 * strokes: those are cut into triangles on the CPU every frame, too slow on TVs.
 */
internal fun DrawScope.drawScope(scene: Scene, samples: FloatArray, s: Spectrum, path: Path, time: Float) {
    // Normalise gently so quiet music still draws a visible line, without blowing up hiss.
    var peak = 0.05f
    for (v in samples) peak = max(peak, kotlin.math.abs(v))
    val gain = min(1f / peak, 8f) * 0.8f
    val points = min(samples.size, (size.width / 5).toInt())
    val step = samples.size.toFloat() / points
    val mid = size.height / 2
    val amp = size.height * 0.32f
    path.reset()
    for (p in 0 until points) {
        val v = samples[(p * step).toInt()] * gain
        val x = p * size.width / (points - 1)
        val y = mid - v * amp
        if (p == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    val color = palette(0.2f + 0.6f * s.loudness, time)
    val paint = scene.ridgePaint
    drawIntoCanvas { canvas ->
        for ((offset, alpha) in SCOPE_GLOW) {
            paint.color = color.copy(alpha = alpha)
            canvas.translate(0f, offset)
            canvas.drawPath(path, paint)
            canvas.translate(0f, -offset)
        }
    }
}

private val SCOPE_GLOW = listOf(-6f to 0.05f, 6f to 0.05f, -3f to 0.12f, 3f to 0.12f, -1.2f to 0.5f, 1.2f to 0.5f, 0f to 1f)
