package io.github.thinke.snaptv

import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import androidx.media3.common.util.UnstableApi
import io.github.thinke.snaptv.core.codec.Decoder
import io.github.thinke.snaptv.core.codec.DecoderFactory
import io.github.thinke.snaptv.core.codec.UnsupportedCodecException

/** One way to decode a codec on this device. [id] is what Settings stores. */
data class DecoderOption(val id: String, val label: String)

/**
 * The decoders available per codec: SnapTV's own (Kotlin), FFmpeg, and each MediaCodec decoder
 * the device lists. Defaults are the ones SnapTV has always used.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object DecoderCatalog {
    /** snapserver codec names with a choice of decoder (pcm needs none). */
    val codecs = listOf("flac", "opus", "ogg")

    fun title(codec: String) = when (codec) {
        "flac" -> "FLAC"
        "opus" -> "Opus"
        "ogg" -> "Ogg Vorbis"
        else -> codec
    }

    const val KOTLIN = "kotlin"
    const val FFMPEG = "ffmpeg"
    private const val MEDIACODEC = "mc:"

    fun default(codec: String): String = when (codec) {
        "flac", "pcm" -> KOTLIN
        else -> mediaCodecs(codec).firstOrNull()?.id ?: FFMPEG
    }

    fun options(codec: String): List<DecoderOption> = buildList {
        if (codec == "flac" || codec == "pcm") add(DecoderOption(KOTLIN, "SnapTV (built-in)"))
        if (FfmpegDecoder.supports(codec)) add(DecoderOption(FFMPEG, "FFmpeg ${FfmpegDecoder.version().orEmpty()}".trim()))
        addAll(mediaCodecs(codec))
    }

    fun label(codec: String, id: String): String = options(codec).firstOrNull { it.id == id }?.label ?: id

    fun create(codec: String, id: String): Decoder = when {
        id == KOTLIN -> Decoder.forCodec(codec)
        id == FFMPEG -> FfmpegDecoder(codec)
        id.startsWith(MEDIACODEC) -> {
            val name = id.removePrefix(MEDIACODEC)
            when (codec) {
                "flac" -> FlacMediaCodecDecoder(name)
                "opus" -> OpusDecoder(name)
                "ogg" -> VorbisDecoder(name)
                else -> throw UnsupportedCodecException(codec)
            }
        }
        else -> throw UnsupportedCodecException(codec)
    }

    private fun mime(codec: String) = when (codec) {
        "flac" -> MediaFormat.MIMETYPE_AUDIO_FLAC
        "opus" -> MediaFormat.MIMETYPE_AUDIO_OPUS
        "ogg" -> MediaFormat.MIMETYPE_AUDIO_VORBIS
        else -> null
    }

    private fun mediaCodecs(codec: String): List<DecoderOption> {
        val mime = mime(codec) ?: return emptyList()
        return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }
            .map { info ->
                val kind = if (Build.VERSION.SDK_INT >= 29) {
                    when {
                        info.isHardwareAccelerated -> " (hardware)"
                        info.isVendor -> " (vendor)"
                        else -> ""
                    }
                } else ""
                DecoderOption(MEDIACODEC + info.name, "Device: ${info.name}$kind")
            }
    }
}

/**
 * Creates the decoder chosen in Settings for each codec, falling back to the default if the
 * choice is gone (e.g. settings copied from another device) or fails to start.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class SelectableDecoders(private val prefs: Prefs, private val onChosen: (codec: String, label: String) -> Unit) : DecoderFactory {
    override fun create(codec: String): Decoder {
        val wanted = prefs.settings.value.decoderFor(codec) ?: DecoderCatalog.default(codec)
        val decoder = try {
            DecoderCatalog.create(codec, wanted).also { onChosen(codec, DecoderCatalog.label(codec, wanted)) }
        } catch (e: Exception) {
            Log.w("SnapTV.Decoders", "decoder $wanted for $codec unavailable, using default", e)
            val d = DecoderCatalog.default(codec)
            DecoderCatalog.create(codec, d).also { onChosen(codec, DecoderCatalog.label(codec, d) + " (fallback)") }
        }
        return decoder
    }
}
