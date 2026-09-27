package io.github.thinke.snaptv.core.codec

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class SampleFormat(val rate: Int, val bits: Int, val channels: Int) {
    override fun toString() = "$rate:$bits:$channels"
}

/**
 * Decodes one snapcast wire chunk at a time into interleaved 16-bit PCM.
 * Output is always 16-bit: Android TV outputs are 16-bit in practice, and it keeps the
 * sync buffer and visualizer simple. Higher bit depths are shifted down.
 */
interface Decoder {
    /** Initialise from the codec header payload; returns the stream's format. */
    fun setHeader(payload: ByteArray): SampleFormat

    /** Decode one chunk; the result length is a multiple of the channel count. */
    fun decode(payload: ByteArray): ShortArray

    companion object {
        fun forCodec(codec: String): Decoder = when (codec) {
            "flac" -> FlacDecoder()
            "pcm" -> PcmDecoder()
            else -> throw UnsupportedCodecException(codec)
        }
    }
}

class UnsupportedCodecException(val codec: String) :
    Exception("codec '$codec' is not supported (use flac or pcm for this stream)")

/** `pcm` codec: header is a RIFF WAVE header, chunks are raw little-endian samples. */
class PcmDecoder : Decoder {
    private var format = SampleFormat(48000, 16, 2)

    override fun setHeader(payload: ByteArray): SampleFormat {
        val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        if (payload.size < 12 || String(payload, 0, 4, Charsets.US_ASCII) != "RIFF") throw IllegalArgumentException("not a RIFF header")
        var pos = 12
        while (pos + 8 <= payload.size) {
            val id = String(payload, pos, 4, Charsets.US_ASCII)
            val len = b.getInt(pos + 4)
            if (id == "fmt ") {
                val channels = b.getShort(pos + 10).toInt()
                val rate = b.getInt(pos + 12)
                val bits = b.getShort(pos + 22).toInt()
                format = SampleFormat(rate, bits, channels)
                return format
            }
            pos += 8 + len
        }
        throw IllegalArgumentException("RIFF header without fmt chunk")
    }

    override fun decode(payload: ByteArray): ShortArray {
        val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        return when (format.bits) {
            16 -> ShortArray(payload.size / 2) { b.getShort(it * 2) }
            // snapcast carries 24-bit samples in 32-bit containers
            24 -> ShortArray(payload.size / 4) { (b.getInt(it * 4) shr 8).toShort() }
            32 -> ShortArray(payload.size / 4) { (b.getInt(it * 4) shr 16).toShort() }
            8 -> ShortArray(payload.size) { (payload[it].toInt() shl 8).toShort() }
            else -> throw IllegalStateException("unsupported pcm bit depth ${format.bits}")
        }
    }
}
