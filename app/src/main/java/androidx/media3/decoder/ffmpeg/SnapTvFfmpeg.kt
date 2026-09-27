package androidx.media3.decoder.ffmpeg

import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.SimpleDecoderOutputBuffer

/**
 * SnapTV's bridge to Media3's FFmpeg audio decoder, which the library keeps package-private
 * (only its ExoPlayer renderer is public). Living in the same package lets us create one.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object SnapTvFfmpeg {
    fun audioDecoder(format: Format, buffers: Int, inputSize: Int): SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, FfmpegDecoderException> =
        FfmpegAudioDecoder(format, buffers, buffers, inputSize, false)
}
