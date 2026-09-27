package io.github.thinke.snaptv

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import io.github.thinke.snaptv.core.SnapEngine
import io.github.thinke.snaptv.core.codec.SampleFormat

/**
 * Pulls audio from the engine into an AudioTrack.
 *
 * Timing: for every block we work out when its first frame will reach the DAC, from
 * AudioTrack's (framePosition, nanoTime) timestamp pair extrapolated to the frame index we are
 * about to write. That estimate only depends on frame counts, not on when write() happens to
 * return, so a blocking write never skews it. The engine then picks samples for that moment.
 */
class AudioOutput(private val engine: SnapEngine) {
    @Volatile private var running = false
    @Volatile var gain: Float = 1f
    private var thread: Thread? = null

    /** How far behind "written" the DAC is, as last measured (ms); for the stats overlay. */
    @Volatile var bufferedMs: Int = 0
        private set

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "snap-audio").apply { start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread?.join(1000)
        thread = null
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        while (running) {
            val format = engine.buffer?.format
            if (format == null) {
                try { Thread.sleep(50) } catch (_: InterruptedException) { return }
                continue
            }
            val track = try {
                createTrack(format)
            } catch (e: Exception) {
                Log.e(TAG, "cannot open audio output for $format", e)
                try { Thread.sleep(1000) } catch (_: InterruptedException) { return }
                continue
            }
            try {
                play(track, format)
            } catch (e: Exception) {
                Log.e(TAG, "audio output failed; reopening", e)
            } finally {
                runCatching { track.stop() }
                track.release()
            }
        }
    }

    private fun createTrack(f: SampleFormat): AudioTrack {
        val mask = if (f.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val min = AudioTrack.getMinBufferSize(f.rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        // Enough headroom to ride out a GC pause, small enough that routing changes settle fast.
        val bytes = maxOf(min * 2, f.rate * f.channels * 2 / 10)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(f.rate)
                    .setChannelMask(mask)
                    .build()
            )
            .setBufferSizeInBytes(bytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun play(track: AudioTrack, f: SampleFormat) {
        val blockFrames = f.rate / 100 // 10 ms
        val buf = ShortArray(blockFrames * f.channels)
        val ts = AudioTimestamp()
        var written = 0L
        var tsFrame = 0L
        var tsUs = 0L
        var haveTs = false
        var lastPollUs = 0L
        var appliedGain = -1f
        track.play()

        while (running && engine.buffer?.format == f) {
            val g = gain
            if (g != appliedGain) { track.setVolume(g); appliedGain = g }

            val nowUs = System.nanoTime() / 1000
            if (!haveTs || nowUs - lastPollUs > TIMESTAMP_POLL_US) {
                lastPollUs = nowUs
                if (track.getTimestamp(ts) && ts.framePosition > 0) {
                    tsFrame = ts.framePosition
                    tsUs = ts.nanoTime / 1000
                    haveTs = true
                }
            }

            if (haveTs) {
                val dacUs = tsUs + (written - tsFrame) * 1_000_000L / f.rate
                bufferedMs = ((dacUs - nowUs) / 1000).toInt()
                engine.render(buf, blockFrames, dacUs)
            } else {
                // Until the device reports a real timestamp we don't know its latency, and a
                // guess would only cause a hard resync a moment later. Prime it with silence.
                buf.fill(0)
            }
            val n = track.write(buf, 0, buf.size)
            if (n < 0) throw IllegalStateException("AudioTrack.write returned $n")
            written += n / f.channels
        }
    }

    private companion object {
        const val TAG = "SnapTV.Audio"
        const val TIMESTAMP_POLL_US = 250_000L
    }
}
