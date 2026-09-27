package io.github.thinke.snaptv

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import io.github.thinke.snaptv.core.codec.Decoder
import io.github.thinke.snaptv.core.codec.DecoderFactory
import io.github.thinke.snaptv.core.codec.LaggedOutput
import io.github.thinke.snaptv.core.codec.OggDemuxer
import io.github.thinke.snaptv.core.codec.Opus
import io.github.thinke.snaptv.core.codec.SampleFormat
import io.github.thinke.snaptv.core.codec.UnsupportedCodecException
import io.github.thinke.snaptv.core.codec.VorbisHeaders
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** core's own flac/pcm decoders, plus opus and ogg (Vorbis) through the platform's MediaCodec. */
val PlatformDecoders = DecoderFactory { codec ->
    when (codec) {
        "opus" -> {
            requireDecoder(codec, MediaFormat.MIMETYPE_AUDIO_OPUS)
            OpusDecoder()
        }
        "ogg" -> {
            requireDecoder(codec, MediaFormat.MIMETYPE_AUDIO_VORBIS)
            VorbisDecoder()
        }
        else -> Decoder.forCodec(codec)
    }
}

private fun requireDecoder(codec: String, mime: String) {
    val found = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
        !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
    }
    if (!found) throw UnsupportedCodecException(codec)
}

/**
 * snapserver `opus`: a 12-byte pseudo header, then one raw Opus packet per chunk. MediaCodec
 * gets a synthesised OpusHead with no pre-skip, so every decoded sample is played, as
 * snapclient does (see [Opus.csd]).
 */
class OpusDecoder(codecName: String? = null) : MediaCodecDecoder(MediaFormat.MIMETYPE_AUDIO_OPUS, codecName) {
    override fun setHeader(payload: ByteArray): SampleFormat {
        val f = Opus.parseHeader(payload)
        // snapserver always resamples to 48 kHz for Opus, and MediaCodec only outputs 48 kHz.
        if (f.rate != 48000) throw IllegalArgumentException("Opus at ${f.rate} Hz is not supported")
        open(f, Opus.csd(f))
        return f
    }

    override fun decode(payload: ByteArray): ShortArray = decodePackets(listOf(payload))
}

/**
 * snapserver `ogg`: the codec header is the three Vorbis header packets in Ogg pages, chunks
 * are Ogg pages of audio packets. We demux in core and feed MediaCodec packet by packet.
 */
class VorbisDecoder(codecName: String? = null) : MediaCodecDecoder(MediaFormat.MIMETYPE_AUDIO_VORBIS, codecName) {
    private val demuxer = OggDemuxer()

    override fun setHeader(payload: ByteArray): SampleFormat {
        val h = VorbisHeaders.parse(payload, demuxer)
        open(SampleFormat(h.id.rate, 16, h.id.channels), listOf(h.identification, h.setup))
        return h.format
    }

    override fun decode(payload: ByteArray): ShortArray = decodePackets(demuxer.push(payload).map { withPageSamples(it.data) })

    /**
     * Android's Vorbis decoders expect each packet followed by a 4-byte "samples left on this
     * page" count (MediaExtractor appends it). -1 means unknown: no end-of-page trimming, which
     * is right for a live stream.
     */
    private fun withPageSamples(packet: ByteArray): ByteArray =
        ByteBuffer.allocate(packet.size + 4).order(ByteOrder.LITTLE_ENDIAN).put(packet).putInt(-1).array()
}

/**
 * snapserver `flac` through the device's FLAC decoder: the codec header ("fLaC" + metadata
 * blocks) is what Android's decoder takes as csd-0, and each chunk holds whole frames.
 */
class FlacMediaCodecDecoder(codecName: String? = null) : MediaCodecDecoder(MediaFormat.MIMETYPE_AUDIO_FLAC, codecName) {
    override fun setHeader(payload: ByteArray): SampleFormat {
        val f = io.github.thinke.snaptv.core.codec.FlacDecoder().setHeader(payload)
        // Our pipeline is 16-bit, and so is Android's FLAC decoder output by default.
        open(SampleFormat(f.rate, 16, f.channels), listOf(payload))
        return f
    }

    override fun decode(payload: ByteArray): ShortArray = decodePackets(listOf(payload))
}

/**
 * A chunk-at-a-time [Decoder] on top of a synchronously driven MediaCodec.
 *
 * MediaCodec decodes on its own thread and may return a chunk's audio during a later call.
 * [LaggedOutput] tags each chunk's input with a presentation time and reports how much of the
 * returned audio belongs to earlier chunks, which the engine uses to move the timestamp back
 * (see [Decoder.carriedFrames]). So we only wait briefly for a chunk's own output, and not at
 * all once the codec has shown it runs behind.
 */
abstract class MediaCodecDecoder(private val mime: String, private val codecName: String? = null) : Decoder {
    private var codec: MediaCodec? = null
    private var mediaFormat: MediaFormat? = null
    private lateinit var pcmFormat: SampleFormat
    private lateinit var output: LaggedOutput
    private val info = MediaCodec.BufferInfo()
    private var late = 0

    override val carriedFrames: Int get() = if (::output.isInitialized) output.carriedFrames else 0

    protected fun open(format: SampleFormat, csd: List<ByteArray>) {
        pcmFormat = format
        output = LaggedOutput(format.channels)
        mediaFormat = MediaFormat.createAudioFormat(mime, format.rate, format.channels).apply {
            csd.forEachIndexed { i, b -> setByteBuffer("csd-$i", ByteBuffer.wrap(b)) }
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT)
        }
        start()
    }

    private fun start(): MediaCodec {
        val c = if (codecName != null) MediaCodec.createByCodecName(codecName) else MediaCodec.createDecoderByType(mime)
        try {
            c.configure(mediaFormat, null, null, 0)
            c.start()
        } catch (e: Exception) {
            c.release()
            throw e
        }
        codec = c
        late = 0
        return c
    }

    /** Queues [packets] as one chunk and returns the PCM the codec has released so far. */
    protected fun decodePackets(packets: List<ByteArray>): ShortArray {
        val c = codec ?: start() // re-created after an error; ChunkPlacer gives up if that keeps failing
        val pts = output.beginChunk()
        try {
            for (p in packets) queue(c, p, pts)
            drain(c, 0)
            if (packets.isNotEmpty() && late < MAX_LATE) {
                val deadline = System.nanoTime() + OWN_OUTPUT_WAIT_US * 1000
                while (!output.hasOwnOutput && System.nanoTime() < deadline) drain(c, 1000)
                late = if (output.hasOwnOutput) 0 else late + 1
            }
        } catch (e: Exception) {
            close()
            output.finish()
            throw e
        }
        return output.finish()
    }

    private fun queue(c: MediaCodec, packet: ByteArray, pts: Long) {
        repeat(INPUT_ATTEMPTS) {
            val index = c.dequeueInputBuffer(2000)
            if (index >= 0) {
                val buf = c.getInputBuffer(index)!!
                buf.clear()
                buf.put(packet)
                c.queueInputBuffer(index, 0, packet.size, pts, 0)
                return
            }
            drain(c, 0) // all input buffers busy: make room
        }
        throw IllegalStateException("$mime decoder accepts no input")
    }

    private fun drain(c: MediaCodec, firstTimeoutUs: Long) {
        var timeoutUs = firstTimeoutUs
        while (true) {
            val index = c.dequeueOutputBuffer(info, timeoutUs)
            timeoutUs = 0
            when {
                index >= 0 -> {
                    val buf = c.getOutputBuffer(index)
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset).limit(info.offset + info.size)
                        val shorts = buf.slice().order(ByteOrder.nativeOrder()).asShortBuffer()
                        val pcm = ShortArray(shorts.remaining()).also { shorts.get(it) }
                        output.add(info.presentationTimeUs, pcm)
                    }
                    c.releaseOutputBuffer(index, false)
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> checkOutputFormat(c.outputFormat)
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                else -> Unit // INFO_OUTPUT_BUFFERS_CHANGED: getOutputBuffer handles it
            }
        }
    }

    private fun checkOutputFormat(f: MediaFormat) {
        val channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val encoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
        if (channels != pcmFormat.channels || rate != pcmFormat.rate || encoding != AudioFormat.ENCODING_PCM_16BIT) {
            throw IllegalStateException("$mime decoder outputs $rate Hz, $channels ch, encoding $encoding; expected ${pcmFormat.rate} Hz, ${pcmFormat.channels} ch, 16-bit")
        }
    }

    override fun flush(): ShortArray {
        val c = codec ?: return ShortArray(0)
        output.beginChunk()
        repeat(INPUT_ATTEMPTS) {
            val index = c.dequeueInputBuffer(2000)
            if (index >= 0) {
                c.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                val deadline = System.nanoTime() + 2_000_000_000L
                while (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == 0 && System.nanoTime() < deadline) drain(c, 10_000)
                return output.finish()
            }
            drain(c, 0)
        }
        return output.finish()
    }

    override fun close() {
        codec?.let { c ->
            runCatching { c.stop() }
            c.release()
        }
        codec = null
    }

    private companion object {
        const val MAX_INPUT = 64 * 1024
        const val INPUT_ATTEMPTS = 50
        /** How long to wait for a chunk's own output before handing it on as carried audio. */
        const val OWN_OUTPUT_WAIT_US = 5_000L
        /** After this many chunks in a row whose output came late, stop waiting at all. */
        const val MAX_LATE = 3
    }
}
