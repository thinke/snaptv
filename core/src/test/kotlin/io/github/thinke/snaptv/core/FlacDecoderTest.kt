package io.github.thinke.snaptv.core

import io.github.thinke.snaptv.core.codec.FlacDecoder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class FlacDecoderTest {
    /** Decodes a whole .flac file and checks the result against STREAMINFO's MD5 of the source audio. */
    private fun check(name: String, expectedRate: Int, expectedChannels: Int) {
        val bytes = javaClass.getResourceAsStream("/flac/$name")!!.readBytes()
        val audioStart = audioOffset(bytes)
        val decoder = FlacDecoder()
        val format = decoder.setHeader(bytes.copyOfRange(0, audioStart))
        assertEquals(expectedRate, format.rate)
        assertEquals(expectedChannels, format.channels)
        assertEquals(16, format.bits)

        val pcm = decoder.decode(bytes.copyOfRange(audioStart, bytes.size))
        val le = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { le.putShort(it) }
        val actual = MessageDigest.getInstance("MD5").digest(le.array())
        assertEquals(hex(streamInfoMd5(bytes)), hex(actual))
    }

    private fun audioOffset(b: ByteArray): Int {
        var pos = 4
        while (true) {
            val last = b[pos].toInt() and 0x80 != 0
            val len = ((b[pos + 1].toInt() and 0xff) shl 16) or ((b[pos + 2].toInt() and 0xff) shl 8) or (b[pos + 3].toInt() and 0xff)
            pos += 4 + len
            if (last) return pos
        }
    }

    // STREAMINFO is always the first block; its MD5 is the last 16 of its 34 bytes.
    private fun streamInfoMd5(b: ByteArray) = b.copyOfRange(8 + 18, 8 + 34)

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun fixedPredictors() = check("stereo_l0.flac", 48000, 2)
    @Test fun lpcDefault() = check("stereo_l5.flac", 48000, 2)
    @Test fun lpcBest() = check("stereo_l8.flac", 48000, 2)
    @Test fun lpcHighOrderLargeBlocks() = check("stereo_lax32.flac", 48000, 2)
    @Test fun mono() = check("mono_l5.flac", 44100, 1)
    @Test fun quietConstantAndWastedBits() = check("quiet_l5.flac", 48000, 2)

    @Test
    fun splitsIntoFramesThatDecodeTheSame() {
        val bytes = javaClass.getResourceAsStream("/flac/stereo_l5.flac")!!.readBytes()
        val start = audioOffset(bytes)
        val whole = FlacDecoder().also { it.setHeader(bytes.copyOfRange(0, start)) }.decode(bytes.copyOfRange(start, bytes.size))
        val d = FlacDecoder().also { it.setHeader(bytes.copyOfRange(0, start)) }
        val frames = d.splitFrames(bytes.copyOfRange(start, bytes.size))
        assertEquals(25, frames.size) // 0.6 s of 48 kHz = 28800 samples = 25 blocks of 1152
        val joined = frames.flatMap { d.decode(it).toList() }
        assertEquals(whole.toList(), joined)
    }
}

