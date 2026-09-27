package io.github.thinke.snaptv.core.visual

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns a window of samples into [bands] log-spaced levels in 0..1, with fast attack and
 * slow release so the display looks alive rather than flickery. Not thread-safe: use one
 * instance per render loop.
 */
class Spectrum(
    val fftSize: Int = 2048,
    val bands: Int = 64,
    private val minHz: Float = 40f,
    private val maxHz: Float = 16000f,
    private val floorDb: Float = -70f,
) {
    init {
        require(fftSize and (fftSize - 1) == 0) { "fftSize must be a power of two" }
    }

    private val window = FloatArray(fftSize) { (0.5 - 0.5 * cos(2 * PI * it / (fftSize - 1))).toFloat() }
    private val re = FloatArray(fftSize)
    private val im = FloatArray(fftSize)
    private val bitrev = IntArray(fftSize).also { r ->
        val bits = Integer.numberOfTrailingZeros(fftSize)
        for (i in 0 until fftSize) r[i] = Integer.reverse(i) ushr (32 - bits)
    }
    private val cosT = FloatArray(fftSize / 2) { cos(2 * PI * it / fftSize).toFloat() }
    private val sinT = FloatArray(fftSize / 2) { sin(2 * PI * it / fftSize).toFloat() }

    /** Smoothed band levels, 0..1. */
    val levels = FloatArray(bands)
    /** Slowly falling peak markers, 0..1. */
    val peaks = FloatArray(bands)
    /** Overall loudness 0..1 and a bass-only energy 0..1 (handy for pulses). */
    var loudness = 0f
        private set
    var bass = 0f
        private set

    private var edges = IntArray(0)
    private var edgesRate = 0

    fun update(samples: FloatArray, sampleRate: Int, dtSeconds: Float) {
        if (edgesRate != sampleRate) computeEdges(sampleRate)
        val n = fftSize
        val offset = max(0, samples.size - n)
        var rms = 0f
        for (i in 0 until n) {
            val s = if (offset + i < samples.size) samples[offset + i] else 0f
            rms += s * s
            val j = bitrev[i]
            re[j] = s * window[i]
            im[j] = 0f
        }
        fft()

        val attack = 1f - exp(-dtSeconds / ATTACK_S)
        val release = 1f - exp(-dtSeconds / RELEASE_S)
        for (b in 0 until bands) {
            var power = 0f
            for (k in edges[b] until edges[b + 1]) power = max(power, re[k] * re[k] + im[k] * im[k])
            // Hann window halves amplitude; normalise so a full-scale sine is ~0 dB.
            val mag = sqrt(power) * 4f / n
            val db = 20f * log10(max(mag, 1e-9f))
            val target = ((db - floorDb) / -floorDb).coerceIn(0f, 1f)
            val k = if (target > levels[b]) attack else release
            levels[b] += (target - levels[b]) * k
            peaks[b] = if (levels[b] > peaks[b]) levels[b] else max(0f, peaks[b] - 0.35f * dtSeconds)
        }
        val targetLoud = (sqrt(rms / n) * 3f).coerceIn(0f, 1f)
        loudness += (targetLoud - loudness) * (if (targetLoud > loudness) attack else release)
        val bassBands = max(1, bands / 8)
        val targetBass = levels.take(bassBands).average().toFloat()
        bass += (targetBass - bass) * (if (targetBass > bass) attack else release)
    }

    /** Lets a quiet display decay to rest when there is no audio. */
    fun decay(dtSeconds: Float) {
        val release = 1f - exp(-dtSeconds / RELEASE_S)
        for (b in 0 until bands) {
            levels[b] -= levels[b] * release
            peaks[b] = max(0f, peaks[b] - 0.35f * dtSeconds)
        }
        loudness -= loudness * release
        bass -= bass * release
    }

    private fun computeEdges(sampleRate: Int) {
        edgesRate = sampleRate
        val binHz = sampleRate.toFloat() / fftSize
        val top = minOf(maxHz, sampleRate / 2f)
        val e = IntArray(bands + 1)
        for (b in 0..bands) {
            val hz = minHz * (top / minHz).toDouble().pow(b.toDouble() / bands)
            e[b] = (hz / binHz).toInt().coerceIn(1, fftSize / 2)
        }
        // Every band gets at least one bin, so low bands don't read as empty.
        for (b in 1..bands) if (e[b] <= e[b - 1]) e[b] = minOf(e[b - 1] + 1, fftSize / 2)
        edges = e
    }

    private fun fft() {
        val n = fftSize
        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            var i = 0
            while (i < n) {
                for (j in 0 until half) {
                    val wr = cosT[j * step]
                    val wi = -sinT[j * step]
                    val a = i + j
                    val b = a + half
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Band index containing [hz], for tests and labels. */
    fun bandOf(hz: Float, sampleRate: Int): Int {
        if (edgesRate != sampleRate) computeEdges(sampleRate)
        val bin = (hz / (sampleRate.toFloat() / fftSize)).toInt()
        for (b in 0 until bands) if (bin < edges[b + 1]) return b
        return bands - 1
    }

    private companion object {
        const val ATTACK_S = 0.025f
        const val RELEASE_S = 0.3f
    }
}
