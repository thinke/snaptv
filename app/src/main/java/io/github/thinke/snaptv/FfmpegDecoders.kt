package io.github.thinke.snaptv

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.ffmpeg.FfmpegDecoderException
import androidx.media3.decoder.ffmpeg.SnapTvFfmpeg
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import io.github.thinke.snaptv.core.codec.Decoder
import io.github.thinke.snaptv.core.codec.FlacDecoder
import io.github.thinke.snaptv.core.codec.LaggedOutput
import io.github.thinke.snaptv.core.codec.OggDemuxer
import io.github.thinke.snaptv.core.codec.Opus
import io.github.thinke.snaptv.core.codec.SampleFormat
import io.github.thinke.snaptv.core.codec.UnsupportedCodecException
import io.github.thinke.snaptv.core.codec.VorbisHeaders
import java.nio.ByteOrder

/**
 * snapserver's flac, opus and ogg (Vorbis) through FFmpeg (Jellyfin's build of Media3's FFmpeg
 * extension). The extension builds FFmpeg's extradata from Media3's initialization data, which
 * we fill the way Media3's own extractors do.
 *
 * Media3's decoder runs on its own thread, so output can come back during a later chunk; the
 * same [LaggedOutput] bookkeeping as the MediaCodec decoders keeps chunk timestamps exact.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class FfmpegDecoder(private val codec: String) : Decoder {
    private lateinit var decoder: SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, FfmpegDecoderException>
    private lateinit var output: LaggedOutput
    private val demuxer = OggDemuxer()
    private var late = 0

    override val carriedFrames: Int get() = if (::output.isInitialized) output.carriedFrames else 0

    override fun setHeader(payload: ByteArray): SampleFormat {
        val (format, mediaFormat) = when (codec) {
            "flac" -> {
                val f = FlacDecoder().setHeader(payload)
                SampleFormat(f.rate, 16, f.channels) to build(MimeTypes.AUDIO_FLAC, f, listOf(payload))
            }
            "opus" -> {
                val f = Opus.parseHeader(payload)
                // FFmpeg's Opus decoder always outputs 48 kHz, like snapserver's encoder input.
                val out = SampleFormat(48000, 16, f.channels)
                out to build(MimeTypes.AUDIO_OPUS, out, listOf(Opus.head(f.channels, f.rate)))
            }
            "ogg" -> {
                val h = VorbisHeaders.parse(payload, demuxer)
                SampleFormat(h.id.rate, 16, h.id.channels) to build(MimeTypes.AUDIO_VORBIS, h.format, listOf(h.identification, h.setup))
            }
            else -> throw UnsupportedCodecException(codec)
        }
        output = LaggedOutput(format.channels)
        decoder = SnapTvFfmpeg.audioDecoder(mediaFormat, BUFFERS, INPUT_SIZE)
        return if (codec == "ogg") VorbisHeaders.parse(payload, OggDemuxer()).format else format
    }

    private fun build(mime: String, f: SampleFormat, init: List<ByteArray>): Format =
        Format.Builder()
            .setSampleMimeType(mime)
            .setChannelCount(f.channels)
            .setSampleRate(f.rate)
            .setInitializationData(init)
            .build()

    override fun decode(payload: ByteArray): ShortArray {
        val packets = if (codec == "ogg") demuxer.push(payload).map { it.data } else listOf(payload)
        val pts = output.beginChunk()
        for (p in packets) queue(p, pts, endOfStream = false)
        collect()
        if (packets.isNotEmpty() && late < MAX_LATE) {
            val deadline = System.nanoTime() + OWN_OUTPUT_WAIT_US * 1000
            while (!output.hasOwnOutput && System.nanoTime() < deadline) {
                Thread.sleep(0, 200_000)
                collect()
            }
            late = if (output.hasOwnOutput) 0 else late + 1
        }
        return output.finish()
    }

    override fun flush(): ShortArray {
        output.beginChunk()
        queue(ByteArray(0), 0, endOfStream = true)
        val deadline = System.nanoTime() + 2_000_000_000L
        while (!ended && System.nanoTime() < deadline) {
            Thread.sleep(1)
            collect()
        }
        return output.finish()
    }

    private var ended = false

    private fun queue(packet: ByteArray, pts: Long, endOfStream: Boolean) {
        var buf: DecoderInputBuffer? = null
        val deadline = System.nanoTime() + 100_000_000L
        while (buf == null) {
            buf = decoder.dequeueInputBuffer()
            if (buf == null) {
                collect() // all input buffers busy: make room
                if (System.nanoTime() > deadline) throw IllegalStateException("FFmpeg $codec decoder accepts no input")
                Thread.sleep(1)
            }
        }
        if (endOfStream) {
            buf.setFlags(C.BUFFER_FLAG_END_OF_STREAM)
        } else {
            buf.ensureSpaceForWrite(packet.size)
            buf.data!!.put(packet)
            buf.timeUs = pts
            buf.flip()
        }
        decoder.queueInputBuffer(buf)
    }

    private fun collect() {
        while (true) {
            val out: SimpleDecoderOutputBuffer = decoder.dequeueOutputBuffer() ?: return
            if (out.isEndOfStream) {
                ended = true
                out.release()
                return
            }
            val data = out.data
            if (data != null && data.remaining() > 0) {
                val shorts = data.order(ByteOrder.nativeOrder()).asShortBuffer()
                val pcm = ShortArray(shorts.remaining()).also { shorts.get(it) }
                output.add(out.timeUs, pcm)
            }
            out.release()
        }
    }

    override fun close() {
        if (::decoder.isInitialized) decoder.release()
    }

    companion object {
        private const val BUFFERS = 16
        private const val INPUT_SIZE = 64 * 1024
        private const val OWN_OUTPUT_WAIT_US = 5_000L
        private const val MAX_LATE = 3

        fun supports(codec: String): Boolean {
            val mime = when (codec) {
                "flac" -> MimeTypes.AUDIO_FLAC
                "opus" -> MimeTypes.AUDIO_OPUS
                "ogg" -> MimeTypes.AUDIO_VORBIS
                else -> return false
            }
            return runCatching { FfmpegLibrary.isAvailable() && FfmpegLibrary.supportsFormat(mime) }.getOrDefault(false)
        }

        fun version(): String? = runCatching { FfmpegLibrary.getVersion() }.getOrNull()
    }
}
