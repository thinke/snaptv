package io.github.thinke.snaptv.core.calibrate

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Finds when known chirps arrive in a microphone recording, for measuring the delay the TV
 * cannot see itself (soundbar over HDMI ARC, TV sound processing, air).
 *
 * The chirp is a short up-sweep; matched filtering (cross-correlation with the chirp) turns
 * each arrival into a sharp peak even in a noisy room, and a sweep has no periodic side peaks
 * that a tone would have.
 */
object ChirpDetector {
    const val CHIRP_MS = 20

    /** 800 Hz to 4 kHz in [CHIRP_MS], Hann-windowed, peak [amplitude] (0..1). */
    fun chirp(rate: Int, amplitude: Float = 0.5f): FloatArray {
        val n = rate * CHIRP_MS / 1000
        val t = CHIRP_MS / 1000.0
        val f0 = 800.0
        val f1 = 4000.0
        return FloatArray(n) { i ->
            val x = i.toDouble() / rate
            val window = 0.5 - 0.5 * cos(2 * PI * i / (n - 1))
            (amplitude * window * sin(2 * PI * (f0 * x + (f1 - f0) / (2 * t) * x * x))).toFloat()
        }
    }

    data class Arrival(
        /** Index in the recording where the chirp starts. */
        val frame: Int,
        /** Peak height over the median correlation level; below ~20 is probably not the chirp. */
        val snr: Float,
    )

    /**
     * For each expected chirp start (frame index in the recording, from the scheduled DAC time),
     * finds the strongest arrival in the following [searchFrames]. Returns null for a window
     * without a clear peak.
     */
    fun findArrivals(recording: FloatArray, chirp: FloatArray, expected: List<Int>, searchFrames: Int): List<Arrival?> {
        val corr = correlate(recording, chirp)
        val sorted = corr.copyOf().also { it.sort() }
        val floor = sorted[sorted.size / 2].coerceAtLeast(1e-9f)
        return expected.map { start ->
            val from = start.coerceIn(0, corr.size)
            val to = (start + searchFrames).coerceIn(0, corr.size)
            if (to - from < chirp.size) return@map null
            var best = from
            for (i in from until to) if (corr[i] > corr[best]) best = i
            val snr = corr[best] / floor
            if (snr < MIN_SNR) null else Arrival(best, snr)
        }
    }

    /**
     * Median of per-chirp delays with an agreement check: a result is trusted only when enough
     * chirps were heard and they agree within [maxSpreadMs].
     */
    data class Result(val delayMs: Double, val heard: Int, val total: Int, val spreadMs: Double) {
        val ok: Boolean get() = heard >= (total + 1) / 2 && spreadMs <= MAX_SPREAD_MS
    }

    fun summarize(delaysMs: List<Double?>): Result {
        val got = delaysMs.filterNotNull().sorted()
        if (got.isEmpty()) return Result(0.0, 0, delaysMs.size, Double.POSITIVE_INFINITY)
        val median = got[got.size / 2]
        // Ignore stragglers (a reflection winning one window) when judging agreement.
        val close = got.filter { abs(it - median) <= OUTLIER_MS }
        val spread = if (close.size > 1) close.last() - close.first() else 0.0
        return Result(close[close.size / 2], close.size, delaysMs.size, spread)
    }

    /** |cross-correlation| of [x] with [t], normalised by the local energy, via FFT. */
    internal fun correlate(x: FloatArray, t: FloatArray): FloatArray {
        var n = 1
        while (n < x.size + t.size) n = n shl 1
        val xr = DoubleArray(n); val xi = DoubleArray(n)
        val tr = DoubleArray(n); val ti = DoubleArray(n)
        for (i in x.indices) xr[i] = x[i].toDouble()
        for (i in t.indices) tr[i] = t[i].toDouble()
        fft(xr, xi, false)
        fft(tr, ti, false)
        for (i in 0 until n) {
            // X * conj(T)
            val r = xr[i] * tr[i] + xi[i] * ti[i]
            val im = xi[i] * tr[i] - xr[i] * ti[i]
            xr[i] = r; xi[i] = im
        }
        fft(xr, xi, true)
        val out = FloatArray(x.size)
        for (i in out.indices) out[i] = abs(xr[i] / n).toFloat()
        // Normalise by the recording's energy over the chirp length, so a loud noise burst
        // doesn't beat a quieter but well-matched chirp.
        var e = 0.0
        for (i in 0 until minOf(t.size, x.size)) e += x[i] * x[i]
        for (i in out.indices) {
            out[i] = (out[i] / sqrt(e + 1e-3)).toFloat()
            val leave = i
            val enter = i + t.size
            if (enter < x.size) e += x[enter] * x[enter] - x[leave] * x[leave]
            if (e < 0) e = 0.0
        }
        return out
    }

    private fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val a = re[i]; re[i] = re[j]; re[j] = a
                val b = im[i]; im[i] = im[j]; im[j] = b
            }
        }
        var len = 2
        while (len <= n) {
            val ang = 2 * PI / len * (if (inverse) 1 else -1)
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val tr = re[b] * cr - im[b] * ci
                    val tim = re[b] * ci + im[b] * cr
                    re[b] = re[a] - tr; im[b] = im[a] - tim
                    re[a] += tr; im[a] += tim
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    private const val MIN_SNR = 8f
    private const val MAX_SPREAD_MS = 6.0
    private const val OUTLIER_MS = 15.0
}
