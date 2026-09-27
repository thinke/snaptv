package io.github.thinke.snaptv

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thinke.snaptv.core.codec.Decoder
import io.github.thinke.snaptv.core.codec.OggDemuxer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Runs the MediaCodec decoders on a real device or emulator, fed the way snapserver chunks
 * each codec. The inputs are 0.5 s of a 440 Hz stereo tone at about -24 dBFS.
 */
@RunWith(AndroidJUnit4::class)
class MediaCodecDecodersTest {
    private fun asset(name: String) =
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes() }

    @Test
    fun opusPacketsDecodeToTheTone() {
        val packets = OggDemuxer().push(asset("sine_opus.opus")).map { it.data }
        // packets[0] is OpusHead, [1] OpusTags; snapserver sends its own 12-byte header instead.
        val header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x4F505553).putInt(48000).putShort(16).putShort(2).array()
        val out = decodeAll(OpusDecoder(), header, packets.drop(2))
        check(out, minFrames = 23_000)
    }

    @Test
    fun vorbisPagesDecodeToTheTone() {
        val file = asset("sine_vorbis.ogg")
        // snapserver's codec header is the pages holding the three Vorbis headers; every
        // later chunk is one page.
        val pages = splitPages(file)
        var split = 0
        var packets = 0
        val probe = OggDemuxer()
        while (packets < 3) { packets += probe.push(pages[split]).size; split++ }
        val header = pages.take(split).reduce { a, b -> a + b }
        val out = decodeAll(PlatformDecoders.create("ogg"), header, pages.drop(split))
        check(out, minFrames = 20_000)
    }

    private fun decodeAll(d: Decoder, header: ByteArray, chunks: List<ByteArray>): ShortArray {
        val f = d.setHeader(header)
        assertEquals(48000, f.rate)
        assertEquals(2, f.channels)
        val parts = ArrayList<ShortArray>()
        for (c in chunks) {
            val pcm = d.decode(c)
            assertTrue("carried frames within output", d.carriedFrames * 2 <= pcm.size)
            parts += pcm
        }
        d.close()
        val out = ShortArray(parts.sumOf { it.size })
        var o = 0
        for (p in parts) { p.copyInto(out, o); o += p.size }
        return out
    }

    private fun check(pcm: ShortArray, minFrames: Int) {
        val frames = pcm.size / 2
        assertTrue("decoded $frames frames", frames in minFrames..25_000)
        // Skip the start (codec warm-up) and measure level and pitch on the left channel.
        val from = 2400
        val to = frames - 2400
        var sum = 0.0
        var crossings = 0
        for (i in from until to) {
            val v = pcm[i * 2].toDouble()
            sum += v * v
            if (i > from && (pcm[(i - 1) * 2] < 0) != (pcm[i * 2] < 0)) crossings++
        }
        val rms = sqrt(sum / (to - from))
        val hz = crossings / 2.0 / ((to - from) / 48000.0)
        assertTrue("rms $rms", rms in 1500.0..2600.0)
        assertEquals("pitch", 440.0, hz, 5.0)
        // Left and right carry the same tone.
        assertEquals(pcm[10_000 * 2].toInt().toDouble(), pcm[10_000 * 2 + 1].toInt().toDouble(), 64.0)
    }

    private fun splitPages(file: ByteArray): List<ByteArray> {
        val pages = ArrayList<ByteArray>()
        var pos = 0
        while (pos + 27 <= file.size) {
            val segs = file[pos + 26].toInt() and 0xff
            var len = 27 + segs
            for (i in 0 until segs) len += file[pos + 27 + i].toInt() and 0xff
            pages += file.copyOfRange(pos, pos + len)
            pos += len
        }
        return pages
    }
}
