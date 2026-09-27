package io.github.thinke.snaptv.core.sync

import io.github.thinke.snaptv.core.codec.SampleFormat
import kotlin.math.abs

/** Decoded audio; [startUs] is the server-clock time of its first frame. */
class PcmChunk(val startUs: Long, val samples: ShortArray, channels: Int) {
    val frames = samples.size / channels
}

data class SyncStats(
    val playing: Boolean,
    /** Positive: we are ahead of where the server wants us (playing too early). */
    val lastErrorUs: Long,
    val medianErrorUs: Long,
    val correctionPpm: Int,
    val queuedMs: Int,
    val hardSyncs: Int,
    val underruns: Int,
)

/**
 * Holds decoded chunks and hands out audio aligned to the server timeline.
 *
 * The audio output calls [read] with the stream time at which the first frame it asks for
 * should be heard. We compare that with where our read cursor actually is:
 * - not playing yet: skip or pad with silence so the first frame lands sample-exactly;
 * - large error (discontinuity, route change): hard resync the same way;
 * - small error (clock drift): drop or duplicate single frames, at most [maxPpm].
 *
 * The error is measured on the frame about to be written, and corrections apply to that same
 * frame, so the loop has no delay built in regardless of how deep the output buffer is.
 */
class SyncBuffer(
    val format: SampleFormat,
    private val maxQueueUs: Long = 10_000_000L,
    private val maxPpm: Int = 500,
) {
    private val rate = format.rate
    private val channels = format.channels
    private val queue = ArrayDeque<PcmChunk>()
    private var current: PcmChunk? = null
    private var offset = 0
    private var playing = false

    private val errors = LongArray(ERROR_WINDOW)
    private var errorCount = 0
    private var errorNext = 0
    private var medianErrorUs = 0L
    private var lastErrorUs = 0L
    private var ppm = 0
    private var correctionAcc = 0.0
    private var hardSyncs = 0

    /** Diagnostic hook: called (under the buffer's lock) for starts, resyncs and underruns. */
    @Volatile var onEvent: ((String) -> Unit)? = null
    private var underruns = 0

    @Synchronized
    fun add(chunk: PcmChunk) {
        if (chunk.frames == 0) return
        queue.addLast(chunk)
        while (queue.size > 1 && queue.last().startUs - queue.first().startUs > maxQueueUs) queue.removeFirst()
    }

    @Synchronized
    fun clear() {
        queue.clear()
        current = null
        playing = false
        resetErrors()
    }

    @Synchronized
    fun stats(): SyncStats {
        val queued = queue.sumOf { it.frames.toLong() } + ((current?.frames ?: 0) - offset)
        return SyncStats(playing, lastErrorUs, medianErrorUs, ppm, (queued * 1000 / rate).toInt(), hardSyncs, underruns)
    }

    /**
     * Fills [out] with [frames] interleaved frames. [playAtUs] is the stream (server) time at
     * which out[0] will be heard. Returns true if any real audio was written.
     */
    @Synchronized
    fun read(out: ShortArray, frames: Int, playAtUs: Long): Boolean {
        var filled: Int
        if (!playing) {
            filled = start(out, frames, playAtUs)
            if (!playing) return false
        } else {
            val err = cursorUs() - playAtUs
            trackError(err)
            if (abs(err) > HARD_ERROR_US || (errorCount >= SHORT_WINDOW && abs(medianErrorUs) > MEDIAN_HARD_ERROR_US)) {
                hardSyncs++
                onEvent?.invoke("resync #$hardSyncs: error ${err}us median ${medianErrorUs}us (${if (abs(err) > HARD_ERROR_US) "jump" else "drift"})")
                playing = false
                current = null
                resetErrors()
                filled = start(out, frames, playAtUs)
                if (!playing) return false
            } else {
                filled = 0
                updateCorrection(frames)
            }
        }
        copy(out, filled, frames)
        return true
    }

    /** Positions the cursor for [playAtUs]; returns how many leading frames were padded with silence. */
    private fun start(out: ShortArray, frames: Int, playAtUs: Long): Int {
        current?.let { queue.addFirst(it); current = null }
        offset = 0
        // Drop everything that should already have finished playing.
        while (queue.isNotEmpty() && endUs(queue.first()) <= playAtUs) queue.removeFirst()
        val first = queue.firstOrNull()
        if (first == null) {
            out.fill(0, 0, frames * channels)
            return frames
        }
        val lead = first.startUs - playAtUs
        return if (lead > 0) {
            val silence = (lead * rate / 1_000_000L).toInt()
            if (silence >= frames) {
                out.fill(0, 0, frames * channels)
                return frames
            }
            out.fill(0, 0, silence * channels)
            current = queue.removeFirst()
            playing = true
            onEvent?.invoke("start: ${lead}us early, $silence frames of silence first")
            silence
        } else {
            current = queue.removeFirst()
            offset = (-lead * rate / 1_000_000L).toInt()
            playing = true
            onEvent?.invoke("start: ${-lead}us late, skipping $offset frames")
            0
        }
    }

    private fun copy(out: ShortArray, from: Int, frames: Int) {
        // Apply at most one drop/duplicate per call, in the middle of the block.
        var op = 0
        if (correctionAcc >= 1.0) { op = 1; correctionAcc -= 1.0 }
        else if (correctionAcc <= -1.0) { op = -1; correctionAcc += 1.0 }
        val opAt = from + (frames - from) / 2

        var o = from
        while (o < frames) {
            var c = current
            if (c == null || offset >= c.frames) {
                c = queue.removeFirstOrNull()
                current = c
                offset = 0
                if (c == null) {
                    underruns++
                    onEvent?.invoke("underrun #$underruns: queue empty at frame $o of $frames")
                    playing = false
                    resetErrors()
                    out.fill(0, o * channels, frames * channels)
                    return
                }
            }
            if (o == opAt && op == -1) { // drop one frame to catch up
                offset++
                op = 0
                continue
            }
            val src = offset * channels
            System.arraycopy(c.samples, src, out, o * channels, channels)
            if (o == opAt && op == 1) { // duplicate one frame to fall back
                op = 0
                o++
                if (o < frames) System.arraycopy(c.samples, src, out, o * channels, channels)
                else offset-- // no room; replay it next time instead
            }
            offset++
            o++
        }
    }

    private fun cursorUs(): Long {
        val c = current
        if (c != null && offset < c.frames) return c.startUs + offset * 1_000_000L / rate
        return queue.firstOrNull()?.startUs ?: Long.MAX_VALUE
    }

    private fun endUs(c: PcmChunk) = c.startUs + c.frames * 1_000_000L / rate

    private fun trackError(err: Long) {
        lastErrorUs = err
        errors[errorNext] = err
        errorNext = (errorNext + 1) % ERROR_WINDOW
        if (errorCount < ERROR_WINDOW) errorCount++
        // Median of the most recent SHORT_WINDOW samples: robust against timestamp jitter.
        val n = minOf(errorCount, SHORT_WINDOW)
        val recent = LongArray(n) { errors[Math.floorMod(errorNext - 1 - it, ERROR_WINDOW)] }
        recent.sort()
        medianErrorUs = recent[n / 2]
    }

    private fun updateCorrection(frames: Int) {
        if (errorCount < SHORT_WINDOW || abs(medianErrorUs) < CORRECTION_DEADBAND_US) {
            ppm = 0
            return
        }
        // 1 ms off -> 500 ppm (0.5 ms/s); proportional, so we settle without overshoot.
        ppm = (medianErrorUs / 2).coerceIn(-maxPpm.toLong(), maxPpm.toLong()).toInt()
        correctionAcc += frames * ppm / 1_000_000.0
    }

    private fun resetErrors() {
        errorCount = 0
        errorNext = 0
        medianErrorUs = 0
        ppm = 0
        correctionAcc = 0.0
    }

    companion object {
        private const val ERROR_WINDOW = 64
        private const val SHORT_WINDOW = 25
        private const val HARD_ERROR_US = 100_000L
        private const val MEDIAN_HARD_ERROR_US = 8_000L
        private const val CORRECTION_DEADBAND_US = 150L
    }
}
