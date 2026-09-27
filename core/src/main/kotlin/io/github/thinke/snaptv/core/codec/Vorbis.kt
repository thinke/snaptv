package io.github.thinke.snaptv.core.codec

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The Vorbis identification header (packet type 1), Vorbis I spec section 4.2.2. */
data class VorbisIdHeader(val channels: Int, val rate: Int, val blocksize0: Int, val blocksize1: Int) {
    companion object {
        fun parse(p: ByteArray): VorbisIdHeader {
            if (!isHeader(p, 1) || p.size < 30) throw IllegalArgumentException("not a Vorbis identification header")
            val b = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
            if (b.getInt(7) != 0) throw IllegalArgumentException("unsupported Vorbis version ${b.getInt(7)}")
            val channels = p[11].toInt() and 0xff
            val rate = b.getInt(12)
            val sizes = p[28].toInt() and 0xff
            val bs0 = 1 shl (sizes and 0x0f)
            val bs1 = 1 shl (sizes shr 4)
            if (channels == 0 || rate <= 0 || bs0 > bs1 || p[29].toInt() and 1 == 0) throw IllegalArgumentException("invalid Vorbis identification header")
            return VorbisIdHeader(channels, rate, bs0, bs1)
        }
    }
}

/** The Vorbis comment header (packet type 3). */
data class VorbisComments(val vendor: String, val comments: List<String>) {
    /** Value of the first `KEY=value` comment, key compared case-insensitively as the spec says. */
    operator fun get(key: String): String? = comments.firstOrNull { it.length > key.length && it[key.length] == '=' && it.startsWith(key, ignoreCase = true) }
        ?.substring(key.length + 1)

    companion object {
        fun parse(p: ByteArray): VorbisComments {
            if (!isHeader(p, 3)) throw IllegalArgumentException("not a Vorbis comment header")
            val b = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
            b.position(7)
            fun string(): String {
                val n = b.getInt()
                if (n < 0 || n > b.remaining()) throw IllegalArgumentException("truncated Vorbis comment header")
                val s = String(p, b.position(), n, Charsets.UTF_8)
                b.position(b.position() + n)
                return s
            }
            val vendor = string()
            val count = b.getInt()
            if (count < 0 || count > b.remaining() / 4) throw IllegalArgumentException("truncated Vorbis comment header")
            return VorbisComments(vendor, List(count) { string() })
        }
    }
}

/**
 * The three Vorbis header packets from snapserver's `ogg` codec header (Ogg pages).
 * [setup] is the codebook packet MediaCodec wants as csd-1; comments are only read for the
 * `SAMPLE_FORMAT=rate:bits:channels` tag that snapserver adds.
 */
class VorbisHeaders(val id: VorbisIdHeader, val identification: ByteArray, val comments: VorbisComments, val setup: ByteArray) {
    /** The stream's format as snapclient reports it: SAMPLE_FORMAT if present, else 16-bit at the header's rate. */
    val format: SampleFormat
        get() {
            val tagged = comments["SAMPLE_FORMAT"]?.split(':')?.mapNotNull { it.trim().toIntOrNull() }
            return if (tagged != null && tagged.size == 3 && tagged[0] == id.rate && tagged[2] == id.channels) {
                SampleFormat(tagged[0], tagged[1], tagged[2])
            } else {
                SampleFormat(id.rate, 16, id.channels)
            }
        }

    companion object {
        /** Parses the codec header; [demuxer] is left positioned for the audio pages that follow. */
        fun parse(payload: ByteArray, demuxer: OggDemuxer = OggDemuxer()): VorbisHeaders {
            val packets = demuxer.push(payload)
            if (packets.size < 3) throw IllegalArgumentException("Ogg codec header holds ${packets.size} of 3 Vorbis header packets")
            if (!isHeader(packets[2].data, 5)) throw IllegalArgumentException("not a Vorbis setup header")
            return VorbisHeaders(VorbisIdHeader.parse(packets[0].data), packets[0].data, VorbisComments.parse(packets[1].data), packets[2].data)
        }
    }
}

private fun isHeader(p: ByteArray, type: Int) =
    p.size >= 7 && p[0].toInt() == type && String(p, 1, 6, Charsets.US_ASCII) == "vorbis"
