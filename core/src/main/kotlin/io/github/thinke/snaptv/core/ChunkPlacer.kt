package io.github.thinke.snaptv.core

import io.github.thinke.snaptv.core.codec.Decoder
import io.github.thinke.snaptv.core.codec.SampleFormat
import io.github.thinke.snaptv.core.sync.PcmChunk
import kotlin.math.abs

class DecoderFailedException(message: String, cause: Throwable) : Exception(message, cause)

/**
 * Decodes wire chunks and stamps the result with the server time of its first frame.
 *
 * A single chunk that fails to decode is skipped (the sync buffer pads the gap), but a decoder
 * that keeps failing is reported with [DecoderFailedException] rather than left to play silence.
 *
 * Audio a decoder carried over from earlier chunks ([Decoder.carriedFrames]) is placed right
 * before the current chunk, which is only right while the timeline is contiguous. snapserver
 * restarts it when a source (re)starts or resumes after idle, so if the carried audio would not
 * line up with the end of what was placed before, it belongs to the old timeline and is dropped.
 */
class ChunkPlacer(private val decoder: Decoder, private val format: SampleFormat) {
    private var failures = 0
    /** Server time right after the last placed audio, or [NONE]. */
    private var expectedStartUs = NONE

    /** Returns the chunk to buffer, or null if there is nothing to play from this one. */
    fun place(timestampUs: Long, payload: ByteArray): PcmChunk? {
        val pcm = try {
            decoder.decode(payload)
        } catch (e: Exception) {
            if (++failures >= MAX_FAILURES) {
                throw DecoderFailedException("decoder failed on $failures chunks in a row: ${e.message ?: e.javaClass.simpleName}", e)
            }
            expectedStartUs = NONE // a gap: nothing to line carried audio up with
            return null
        }
        failures = 0
        val channels = format.channels
        val carried = decoder.carriedFrames.coerceIn(0, pcm.size / channels)
        var samples = pcm
        var startUs = Decoder.chunkStartUs(timestampUs, carried, format.rate)
        if (carried > 0 && expectedStartUs != NONE && abs(startUs - expectedStartUs) > CONTIGUOUS_TOLERANCE_US) {
            samples = pcm.copyOfRange(carried * channels, pcm.size)
            startUs = timestampUs
        }
        val chunk = PcmChunk(startUs, samples, channels)
        // Even with no output, audio the decoder still holds for this chunk starts at startUs.
        expectedStartUs = startUs + chunk.frames * 1_000_000L / format.rate
        return if (chunk.frames > 0) chunk else null
    }

    companion object {
        private const val NONE = Long.MIN_VALUE
        /** Consecutive failed chunks before the decoder is given up on (about 0.2 s of Opus). */
        const val MAX_FAILURES = 10
        /** Timestamps are rounded to microseconds per chunk; real timeline jumps are far larger. */
        const val CONTIGUOUS_TOLERANCE_US = 2_000L
    }
}
