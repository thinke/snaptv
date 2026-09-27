package io.github.thinke.snaptv.core.codec

/**
 * Collects the output of a decoder that returns audio late (MediaCodec runs on its own thread
 * and may hold a buffer or two), so a chunk-at-a-time [Decoder] can still be built on it.
 *
 * Every input of a chunk is queued with [beginChunk]'s presentation time; output buffers come
 * back carrying the presentation time of the chunk they were decoded from. Whatever has come
 * out when the chunk is finished is returned by [finish], and [carriedFrames] counts the frames
 * at its start that came from earlier chunks. While snapserver's chunk timestamps are
 * contiguous (each one is the previous plus its duration), moving the current chunk's
 * timestamp back by that many frames gives the time of the first returned frame. They are not
 * contiguous across a source restart; [io.github.thinke.snaptv.core.ChunkPlacer] then drops the
 * carried audio.
 *
 * One inexactness remains for Vorbis, shared with snapclient: a fresh decoder outputs nothing
 * for its first packet, so the first chunk after a (re)start is short by that packet (a quarter
 * of the previous plus a quarter of its own block size, 3 to 21 ms at 48 kHz) and is played that
 * much early. The sync buffer absorbs the jump at the next chunk. Correcting it would need the
 * previous page's last block size, which a client joining mid-stream never sees.
 *
 * Presentation times are spaced [SPACING_US] apart so a codec that interpolates timestamps
 * within a buffer still maps back to the right chunk.
 */
class LaggedOutput(private val channels: Int) {
    private var next = 0L
    private var current = -1L
    private val parts = ArrayList<ShortArray>()
    private var carriedSamples = 0
    private var ownSamples = 0

    var carriedFrames = 0
        private set

    /** Starts a chunk; queue all of its input with the returned presentation time. */
    fun beginChunk(): Long {
        current = next++
        return current * SPACING_US
    }

    /** Output that came back from the codec, with the presentation time it carried. */
    fun add(presentationUs: Long, pcm: ShortArray) {
        if (pcm.isEmpty()) return
        parts += pcm
        if (Math.floorDiv(presentationUs, SPACING_US) < current) carriedSamples += pcm.size else ownSamples += pcm.size
    }

    /** True once output of the current chunk itself has arrived. */
    val hasOwnOutput: Boolean get() = ownSamples > 0

    /** Everything collected since the last call, in codec order. */
    fun finish(): ShortArray {
        carriedFrames = carriedSamples / channels
        val out = when (parts.size) {
            0 -> ShortArray(0)
            1 -> parts[0]
            else -> ShortArray(parts.sumOf { it.size }).also { o ->
                var p = 0
                for (part in parts) {
                    part.copyInto(o, p)
                    p += part.size
                }
            }
        }
        parts.clear()
        carriedSamples = 0
        ownSamples = 0
        return out
    }

    companion object {
        const val SPACING_US = 1_000_000L
    }
}
