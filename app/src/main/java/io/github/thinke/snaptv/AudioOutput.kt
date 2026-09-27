package io.github.thinke.snaptv

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Build
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
/**
 * Test sound for measuring the delay after the DAC: [chirp] starts reaching the DAC exactly at
 * each of [atUs] (monotonic, the clock AudioTrack timestamps use). While a plan is set, the
 * stream is muted and only the chirps play.
 */
class ChirpPlan(val chirp: FloatArray, val atUs: List<Long>)

/**
 * A beep on every beat of the monotonic clock ([periodUs]), for the manual sync test. It is
 * scheduled [delayUs] early, exactly like the music, so with the right Audio delay it is
 * *heard* on the beat. [delayUs] is read live, so adjustments apply to the next beep.
 */
class Metronome(val sound: FloatArray, val periodUs: Long, val delayUs: () -> Long)

class AudioOutput(private val engine: SnapEngine) {
    @Volatile private var running = false
    @Volatile var gain: Float = 1f
    @Volatile var calibration: ChirpPlan? = null
    @Volatile var metronome: Metronome? = null

    /** Monotonic time (us) the next written frame reaches the DAC; 0 until the track reports it. */
    @Volatile var nextDacUs: Long = 0
        private set
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
        // Capacity for a GC pause or a slow HAL; how much of it we actually fill is set by
        // TARGET_QUEUE_MS in play().
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
        var lastUnderruns = 0
        // Keep only this much unplayed audio in the track. Keeping it full would add its whole
        // capacity (hundreds of ms on some TVs) to the output delay, which has to fit inside
        // the server's buffer together with the network: with snapserver's buffer at 400 ms a
        // TCL TV then ran its queue dry every second.
        val targetFrames = maxOf(blockFrames * 4, f.rate * TARGET_QUEUE_MS / 1000).toLong()
        var headWraps = 0L
        var lastHead = 0L
        fun playedFrames(): Long {
            // playbackHeadPosition is an unsigned 32-bit counter that wraps.
            val h = track.playbackHeadPosition.toLong() and 0xffffffffL
            if (h < lastHead) headWraps += 1L shl 32
            lastHead = h
            return headWraps + h
        }
        // A stream track only starts once its start threshold (by default the whole buffer) is
        // filled; with pacing it would never get there. Lower it where we can, and elsewhere
        // don't pace until the track is actually playing.
        if (Build.VERSION.SDK_INT >= 31) {
            runCatching { track.startThresholdInFrames = targetFrames.toInt() }
        }
        track.play()
        Log.i(TAG, "opened $f: capacity ${track.bufferSizeInFrames} frames, queue target $targetFrames, start threshold ${if (Build.VERSION.SDK_INT >= 31) track.startThresholdInFrames else -1}")

        while (running && engine.buffer?.format == f) {
            val played = playedFrames()
            val pending = written - played
            if (played > 0 && pending > targetFrames) {
                try {
                    Thread.sleep(maxOf(1L, (pending - targetFrames) * 1000 / f.rate))
                } catch (_: InterruptedException) {
                    return
                }
                continue
            }
            val plan = calibration
            val beat = metronome
            // Test sounds must be audible even if the room is turned down or muted.
            val g = if (plan != null || beat != null) maxOf(gain, CALIBRATION_MIN_GAIN) else gain
            if (g != appliedGain) { track.setVolume(g); appliedGain = g }

            val nowUs = System.nanoTime() / 1000
            if (!haveTs || nowUs - lastPollUs > TIMESTAMP_POLL_US) {
                lastPollUs = nowUs
                if (track.getTimestamp(ts) && ts.framePosition > 0) {
                    val newUs = ts.nanoTime / 1000
                    if (haveTs) {
                        // Where the old timestamp says this frame should have played.
                        val predictedUs = tsUs + (ts.framePosition - tsFrame) * 1_000_000L / f.rate
                        val jumpUs = newUs - predictedUs
                        if (kotlin.math.abs(jumpUs) > TIMESTAMP_JUMP_LOG_US) {
                            Log.w(TAG, "timestamp jump ${jumpUs}us (frame ${ts.framePosition})")
                        }
                    }
                    tsFrame = ts.framePosition
                    tsUs = newUs
                    haveTs = true
                }
                val u = track.underrunCount
                if (u != lastUnderruns) {
                    Log.w(TAG, "AudioTrack underruns: $u (+${u - lastUnderruns})")
                    lastUnderruns = u
                }
            }

            if (haveTs) {
                val dacUs = tsUs + (written - tsFrame) * 1_000_000L / f.rate
                bufferedMs = ((dacUs - nowUs) / 1000).toInt()
                nextDacUs = dacUs
                when {
                    plan != null -> renderChirps(plan, buf, blockFrames, f, dacUs)
                    beat != null -> renderBeats(beat, buf, blockFrames, f, dacUs)
                    else -> engine.render(buf, blockFrames, dacUs)
                }
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

    private fun renderBeats(m: Metronome, buf: ShortArray, frames: Int, f: SampleFormat, dacUs: Long) {
        val delay = m.delayUs()
        val blockUs = frames * 1_000_000L / f.rate
        val soundUs = m.sound.size * 1_000_000L / f.rate
        // Beats whose sound (sent `delay` early) overlaps this block.
        val firstBeat = Math.floorDiv(dacUs + delay - soundUs, m.periodUs) + 1
        val lastBeat = Math.floorDiv(dacUs + delay + blockUs, m.periodUs)
        renderChirps(ChirpPlan(m.sound, (firstBeat..lastBeat).map { it * m.periodUs - delay }), buf, frames, f, dacUs)
    }

    private fun renderChirps(plan: ChirpPlan, buf: ShortArray, frames: Int, f: SampleFormat, dacUs: Long) {
        buf.fill(0)
        for (at in plan.atUs) {
            // Frame of this block where the chirp's first sample belongs (may be negative).
            val start = ((at - dacUs) * f.rate / 1_000_000L).toInt()
            if (start >= frames || start + plan.chirp.size <= 0) continue
            for (i in maxOf(0, -start) until minOf(plan.chirp.size, frames - start)) {
                val v = (plan.chirp[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                for (c in 0 until f.channels) buf[(start + i) * f.channels + c] = v
            }
        }
    }

    private companion object {
        const val CALIBRATION_MIN_GAIN = 0.5f
        const val TAG = "SnapTV.Audio"
        const val TIMESTAMP_POLL_US = 250_000L
        const val TIMESTAMP_JUMP_LOG_US = 1_000L
        const val TARGET_QUEUE_MS = 80
    }
}
