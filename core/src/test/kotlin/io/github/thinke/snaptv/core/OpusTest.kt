package io.github.thinke.snaptv.core

import io.github.thinke.snaptv.core.codec.OggDemuxer
import io.github.thinke.snaptv.core.codec.Opus
import io.github.thinke.snaptv.core.codec.SampleFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OpusTest {
    /** Byte for byte what opus_encoder.cpp writes on a little endian server. */
    private fun pseudoHeader(rate: Int = 48000, bits: Int = 16, channels: Int = 2, id: Int = 0x4F505553) =
        ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(id).putInt(rate).putShort(bits.toShort()).putShort(channels.toShort()).array()

    @Test
    fun parsesSnapserverPseudoHeader() {
        val h = pseudoHeader()
        assertEquals("SUPO", String(h, 0, 4, Charsets.US_ASCII)) // the magic lands byte-swapped
        assertEquals(SampleFormat(48000, 16, 2), Opus.parseHeader(h))
    }

    @Test
    fun rejectsBadPseudoHeaders() {
        assertThrows(IllegalArgumentException::class.java) { Opus.parseHeader(ByteArray(11)) }
        assertThrows(IllegalArgumentException::class.java) { Opus.parseHeader(pseudoHeader(id = 0x5355504F)) }
        assertThrows(IllegalArgumentException::class.java) { Opus.parseHeader(pseudoHeader(rate = 44100)) }
        assertThrows(IllegalArgumentException::class.java) { Opus.parseHeader(pseudoHeader(channels = 6)) }
    }

    @Test
    fun opusHeadMatchesLibopusOutput() {
        // ffmpeg/libopus wrote this file: 48 kHz stereo, pre-skip 312, gain 0, family 0.
        val file = javaClass.getResourceAsStream("/ogg/sine_opus.opus")!!.readBytes()
        val head = OggDemuxer().push(file)[0].data
        assertArrayEquals(head, Opus.head(channels = 2, inputRate = 48000, preSkip = 312))
    }

    @Test
    fun mediaCodecCsdHasNoPreSkip() {
        val csd = Opus.csd(SampleFormat(48000, 16, 2))
        assertEquals(3, csd.size)
        val head = ByteBuffer.wrap(csd[0]).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("OpusHead", String(csd[0], 0, 8, Charsets.US_ASCII))
        assertEquals(19, csd[0].size)
        assertEquals(2, csd[0][9].toInt())
        assertEquals(0, head.getShort(10).toInt())
        assertEquals(48000, head.getInt(12))
        assertArrayEquals(ByteArray(8), csd[1])
        assertArrayEquals(ByteArray(8), csd[2])
    }

    @Test
    fun nanosecondCsdIsLittleEndianLong() {
        assertArrayEquals(byteArrayOf(0x00, 0xb4.toByte(), 0xc4.toByte(), 0x04, 0, 0, 0, 0), Opus.nanosCsd(80_000_000L))
    }
}
