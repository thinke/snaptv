package io.github.thinke.snaptv

import android.content.Context
import androidx.media3.common.util.UnstableApi
import io.github.thinke.snaptv.core.codec.FlacDecoder
import io.github.thinke.snaptv.core.codec.OggDemuxer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.sqrt

data class SelfTestResult(
    val codec: String,
    val option: DecoderOption,
    val ok: Boolean,
    /** What was checked and how it went, e.g. "bit-exact" or the error. */
    val detail: String,
    /** Decoding speed as a multiple of real time. */
    val speed: Double? = null,
    /** Average audio held back by the decoder per chunk (its output delay). */
    val delayMs: Double? = null,
)

/**
 * Runs every decoder option on bundled test files, fed exactly as snapserver chunks them: FLAC
 * frame by frame, Opus packet by packet, Vorbis page by page. FLAC must be bit-exact (checked
 * against the file's own MD5); Opus and Vorbis must give the 440 Hz test tone at the right level.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object DecoderSelfTest {
    fun runAll(context: Context): List<SelfTestResult> =
        DecoderCatalog.codecs.flatMap { codec -> DecoderCatalog.options(codec).map { run(context, codec, it) } }

    fun run(context: Context, codec: String, option: DecoderOption): SelfTestResult {
        val input = try {
            input(context, codec)
        } catch (e: Exception) {
            return SelfTestResult(codec, option, false, "test file: ${e.message}")
        }
        val decoder = try {
            DecoderCatalog.create(codec, option.id)
        } catch (e: Exception) {
            return SelfTestResult(codec, option, false, "can't create: ${e.message ?: e.javaClass.simpleName}")
        }
        return try {
            val format = decoder.setHeader(input.header)
            val parts = ArrayList<ShortArray>()
            var carried = 0L
            val t0 = System.nanoTime()
            for (c in input.chunks) {
                parts += decoder.decode(c)
                carried += decoder.carriedFrames
            }
            parts += decoder.flush()
            val seconds = (System.nanoTime() - t0) / 1e9
            val pcm = concat(parts)
            val frames = pcm.size / format.channels
            val speed = if (seconds > 0) frames.toDouble() / format.rate / seconds else null
            val delay = carried.toDouble() / input.chunks.size / format.rate * 1000
            val (ok, detail) = input.check(pcm, format.channels)
            SelfTestResult(codec, option, ok, detail, speed, delay)
        } catch (e: Exception) {
            SelfTestResult(codec, option, false, "failed: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            decoder.close()
        }
    }

    private class Input(val header: ByteArray, val chunks: List<ByteArray>, val check: (ShortArray, Int) -> Pair<Boolean, String>)

    private fun input(context: Context, codec: String): Input {
        fun asset(name: String) = context.assets.open("selftest/$name").use { it.readBytes() }
        return when (codec) {
            "flac" -> {
                val file = asset("stereo_l5.flac")
                val start = flacAudioOffset(file)
                val header = file.copyOfRange(0, start)
                val splitter = FlacDecoder().also { it.setHeader(header) }
                val expectedMd5 = file.copyOfRange(8 + 18, 8 + 34)
                Input(header, splitter.splitFrames(file.copyOfRange(start, file.size))) { pcm, _ ->
                    val le = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                    pcm.forEach { le.putShort(it) }
                    val md5 = MessageDigest.getInstance("MD5").digest(le.array())
                    if (md5.contentEquals(expectedMd5)) true to "bit-exact"
                    else false to "output differs from the original (${pcm.size / 2} of 28800 frames)"
                }
            }
            "opus" -> {
                val packets = OggDemuxer().push(asset("sine_opus.opus")).map { it.data }
                // snapserver's 12-byte pseudo header instead of OpusHead/OpusTags.
                val header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(0x4F505553).putInt(48000).putShort(16).putShort(2).array()
                Input(header, packets.drop(2), ::toneCheck)
            }
            "ogg" -> {
                val pages = oggPages(asset("sine_vorbis.ogg"))
                var split = 0
                var packets = 0
                val probe = OggDemuxer()
                while (packets < 3) { packets += probe.push(pages[split]).size; split++ }
                Input(pages.take(split).reduce { a, b -> a + b }, pages.drop(split), ::toneCheck)
            }
            else -> throw IllegalArgumentException("no test file for $codec")
        }
    }

    /** 0.5 s of 440 Hz at about -24 dBFS, both channels. */
    private fun toneCheck(pcm: ShortArray, channels: Int): Pair<Boolean, String> {
        val frames = pcm.size / channels
        if (frames < 20_000) return false to "only $frames of ~24000 frames"
        val from = 2400
        val to = frames - 2400
        var sum = 0.0
        var crossings = 0
        for (i in from until to) {
            val v = pcm[i * channels].toDouble()
            sum += v * v
            if (i > from && (pcm[(i - 1) * channels] < 0) != (pcm[i * channels] < 0)) crossings++
        }
        val rms = sqrt(sum / (to - from))
        val hz = crossings / 2.0 / ((to - from) / 48000.0)
        val ok = rms in 1500.0..2600.0 && kotlin.math.abs(hz - 440) < 5
        return ok to "tone %.0f Hz, level %.0f".format(hz, rms) + if (ok) "" else " (expected 440 Hz, ~2050)"
    }

    private fun flacAudioOffset(b: ByteArray): Int {
        var pos = 4
        while (true) {
            val last = b[pos].toInt() and 0x80 != 0
            val len = ((b[pos + 1].toInt() and 0xff) shl 16) or ((b[pos + 2].toInt() and 0xff) shl 8) or (b[pos + 3].toInt() and 0xff)
            pos += 4 + len
            if (last) return pos
        }
    }

    private fun oggPages(file: ByteArray): List<ByteArray> {
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

    private fun concat(parts: List<ShortArray>): ShortArray {
        val out = ShortArray(parts.sumOf { it.size })
        var o = 0
        for (p in parts) { p.copyInto(out, o); o += p.size }
        return out
    }
}
