package io.github.thinke.snaptv.core.visual

import io.github.thinke.snaptv.core.PlaybackTap

/**
 * Keeps the last second or so of played audio (mono, -1..1) together with when it will be
 * heard, so the UI can pull the window that is audible *right now*. Audio is written well
 * ahead of time (output buffer + TV latency), so reading "the newest samples" would put the
 * visuals visibly ahead of the sound.
 */
class VisualBuffer(capacityPow2: Int = 16) : PlaybackTap {
    private val size = 1 shl capacityPow2
    private val mask = size - 1
    private val ring = FloatArray(size)
    private var written = 0L // total frames ever written
    private var anchorFrame = 0L // frame index whose heard time is anchorUs
    private var anchorUs = 0L
    private var rate = 48000

    @Synchronized
    override fun onPlayed(samples: ShortArray, frames: Int, channels: Int, rate: Int, heardAtUs: Long) {
        this.rate = rate
        anchorFrame = written
        anchorUs = heardAtUs
        var i = 0
        for (f in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) sum += samples[i++]
            ring[((written + f) and mask.toLong()).toInt()] = sum / (channels * 32768f)
        }
        written += frames
    }

    /**
     * Copies the [out].size samples ending at local time [nowUs] into [out].
     * Returns false if that audio is not in the buffer (nothing playing yet).
     */
    @Synchronized
    fun window(nowUs: Long, out: FloatArray): Boolean {
        if (written == 0L) return false
        val end = anchorFrame + (nowUs - anchorUs) * rate / 1_000_000L
        val start = end - out.size
        if (start < written - size || end > written || start < 0) {
            out.fill(0f)
            return false
        }
        for (i in out.indices) out[i] = ring[((start + i) and mask.toLong()).toInt()]
        return true
    }

    val sampleRate: Int @Synchronized get() = rate
}
