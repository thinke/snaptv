package io.github.thinke.snaptv.core

import io.github.thinke.snaptv.core.codec.OggDemuxer
import io.github.thinke.snaptv.core.codec.SampleFormat
import io.github.thinke.snaptv.core.codec.VorbisComments
import io.github.thinke.snaptv.core.codec.VorbisHeaders
import io.github.thinke.snaptv.core.codec.VorbisIdHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VorbisTest {
    private val file = javaClass.getResourceAsStream("/ogg/sine_vorbis.ogg")!!.readBytes()

    /** snapserver sends the header pages as the codec header: split the file where audio pages begin. */
    private fun headerPages(): Pair<ByteArray, ByteArray> {
        var pos = 0
        var packets = 0
        val d = OggDemuxer()
        while (packets < 3) {
            val segs = file[pos + 26].toInt() and 0xff
            var len = 27 + segs
            for (i in 0 until segs) len += file[pos + 27 + i].toInt() and 0xff
            packets += d.push(file, pos, len).size
            pos += len
        }
        return file.copyOfRange(0, pos) to file.copyOfRange(pos, file.size)
    }

    private fun comments(vararg c: String): ByteArray {
        val vendor = "test".toByteArray()
        val body = c.map { it.toByteArray() }
        val b = ByteBuffer.allocate(7 + 4 + vendor.size + 4 + body.sumOf { 4 + it.size } + 1).order(ByteOrder.LITTLE_ENDIAN)
        b.put(3).put("vorbis".toByteArray()).putInt(vendor.size).put(vendor).putInt(body.size)
        body.forEach { b.putInt(it.size).put(it) }
        b.put(1)
        return b.array()
    }

    @Test
    fun parsesHeadersFromCodecHeaderPages() {
        val (header, audio) = headerPages()
        val d = OggDemuxer()
        val h = VorbisHeaders.parse(header, d)
        assertEquals(VorbisIdHeader(2, 48000, 256, 2048), h.id)
        assertEquals(SampleFormat(48000, 16, 2), h.format)
        assertEquals("48000:16:2", h.comments["sample_format"])
        assertTrue(h.comments.vendor.startsWith("Lavf") || h.comments.vendor.contains("Xiph"))
        assertEquals(5, h.setup[0].toInt())
        // the same demuxer carries on with the audio pages
        val packets = d.push(audio)
        assertTrue(packets.isNotEmpty())
        assertTrue(packets.all { it.data[0].toInt() and 1 == 0 }) // audio packets have the low bit clear
    }

    @Test
    fun sampleFormatTagOverridesBitDepth() {
        val (header, _) = headerPages()
        val h = VorbisHeaders.parse(header)
        val tagged = VorbisHeaders(h.id, h.identification, VorbisComments.parse(comments("TITLE=SnapStream", "SAMPLE_FORMAT=48000:24:2")), h.setup)
        assertEquals(SampleFormat(48000, 24, 2), tagged.format)
        // a tag contradicting the identification header is ignored: the decoder's PCM follows the header
        val wrong = VorbisHeaders(h.id, h.identification, VorbisComments.parse(comments("SAMPLE_FORMAT=44100:16:2")), h.setup)
        assertEquals(SampleFormat(48000, 16, 2), wrong.format)
        val none = VorbisHeaders(h.id, h.identification, VorbisComments.parse(comments()), h.setup)
        assertNull(none.comments["SAMPLE_FORMAT"])
        assertEquals(SampleFormat(48000, 16, 2), none.format)
    }

    @Test
    fun rejectsNonVorbis() {
        assertThrows(IllegalArgumentException::class.java) { VorbisIdHeader.parse(comments()) }
        assertThrows(IllegalArgumentException::class.java) { VorbisHeaders.parse(ByteArray(64)) }
        val opus = javaClass.getResourceAsStream("/ogg/sine_opus.opus")!!.readBytes()
        assertThrows(IllegalArgumentException::class.java) { VorbisHeaders.parse(opus) }
    }
}
