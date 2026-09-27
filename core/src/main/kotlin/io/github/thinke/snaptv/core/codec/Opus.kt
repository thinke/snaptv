package io.github.thinke.snaptv.core.codec

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * snapserver's `opus` codec: the codec header is a 12-byte pseudo header ("OPUS" magic, rate,
 * bits, channels, little endian) and every wire chunk is one raw Opus packet of 10 to 60 ms.
 * There is no OpusHead on the wire, so for MediaCodec we build one ourselves.
 */
object Opus {
    private const val ID_OPUS = 0x4F505553

    /** Parses the pseudo header, as snapclient's OpusDecoder::setHeader does. */
    fun parseHeader(payload: ByteArray): SampleFormat {
        if (payload.size < 12) throw IllegalArgumentException("Opus header too small")
        val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        if (b.getInt(0) != ID_OPUS) throw IllegalArgumentException("not an Opus pseudo header")
        val rate = b.getInt(4)
        val bits = b.getShort(8).toInt() and 0xffff
        val channels = b.getShort(10).toInt() and 0xffff
        if (rate !in VALID_RATES || channels !in 1..2) throw IllegalArgumentException("unsupported Opus format $rate:$bits:$channels")
        return SampleFormat(rate, bits, channels)
    }

    /**
     * OpusHead (RFC 7845 section 5.1) for MediaCodec's csd-0, mapping family 0 (mono/stereo).
     * [preSkip] is in 48 kHz samples; [inputRate] is informational only.
     */
    fun head(channels: Int, inputRate: Int, preSkip: Int = 0): ByteArray =
        ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
            .put("OpusHead".toByteArray(Charsets.US_ASCII))
            .put(1) // version
            .put(channels.toByte())
            .putShort(preSkip.toShort())
            .putInt(inputRate)
            .putShort(0) // output gain
            .put(0) // channel mapping family
            .array()

    /** csd-1 (codec delay) and csd-2 (seek pre-roll): nanoseconds as a native-order (little endian) long. */
    fun nanosCsd(ns: Long): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(ns).array()

    /**
     * csd-0, csd-1 and csd-2 for MediaCodec's audio/opus decoder.
     *
     * Pre-skip and codec delay are 0 on purpose: snapclient calls opus_decode on each packet and
     * plays every sample it returns, so the encoder's look-ahead is part of the timeline every
     * other room hears. Skipping it here would shift this client against them. The seek
     * pre-roll only applies after a flush, which we never do; 0 keeps any decoder that applies
     * it at start from dropping audio.
     */
    fun csd(format: SampleFormat): List<ByteArray> = listOf(
        head(format.channels, format.rate, preSkip = 0),
        nanosCsd(0),
        nanosCsd(0),
    )

    private val VALID_RATES = setOf(8000, 12000, 16000, 24000, 48000)
}
