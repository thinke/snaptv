package io.github.thinke.snaptv

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.util.Log
import io.github.thinke.snaptv.core.calibrate.ChirpDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.math.sqrt

sealed interface CalibrationOutcome {
    /** [delayMs] is the delay after the DAC; it becomes the Audio delay setting. */
    data class Measured(val delayMs: Int, val heard: Int, val total: Int, val spreadMs: Double) : CalibrationOutcome
    data class Failed(val reason: String) : CalibrationOutcome
}

/**
 * Measures what happens after the DAC (soundbar over HDMI ARC, TV processing, air) with the
 * TV's microphone: chirps are played at known DAC times and found in a recording whose frames
 * are timestamped on the same monotonic clock.
 */
class Calibrator(private val context: Context, private val output: AudioOutput) {

    @SuppressLint("MissingPermission") // the UI asks for RECORD_AUDIO before calling us
    suspend fun run(onProgress: (String) -> Unit): CalibrationOutcome = withContext(Dispatchers.Default) {
        if (output.nextDacUs == 0L) return@withContext CalibrationOutcome.Failed("Nothing is playing yet. Wait until SnapTV is connected, then try again.")

        val rate = RATE
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return@withContext CalibrationOutcome.Failed("This TV has no usable microphone.")
        val record = try {
            AudioRecord(source(), rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 4, rate / 5 * 2))
        } catch (e: Exception) {
            return@withContext CalibrationOutcome.Failed("Could not open the microphone: ${e.message}")
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return@withContext CalibrationOutcome.Failed("This TV has no usable microphone.")
        }
        // Google TV routes voice input to the remote's Bluetooth mic, whose buffering adds a
        // large and variable delay. Only a microphone wired into the TV gives a usable time.
        val builtIn = context.getSystemService(AudioManager::class.java)
            .getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        if (builtIn == null) {
            record.release()
            return@withContext CalibrationOutcome.Failed("This TV has no built-in microphone (a remote's microphone is too imprecise for this). Adjust by ear instead.")
        }
        record.preferredDevice = builtIn

        val total = rate * RECORD_SECONDS
        val pcm = ShortArray(total)
        val ts = AudioTimestamp()
        var tsFrame = -1L
        var tsUs = 0L
        var firstTsFrame = -1L
        var firstTsUs = 0L
        try {
            record.startRecording()
            onProgress("Listening…")
            val chirp = ChirpDetector.chirp(rate, CHIRP_AMPLITUDE)
            var planned: List<Long>? = null
            var got = 0
            while (got < total) {
                val n = record.read(pcm, got, minOf(rate / 10, total - got))
                if (n < 0) return@withContext CalibrationOutcome.Failed("The microphone stopped (error $n).")
                got += n
                // Keep the latest capture timestamp: frame tsFrame was captured at tsUs.
                if (record.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                    tsFrame = ts.framePosition
                    tsUs = ts.nanoTime / 1000
                    if (firstTsFrame < 0) { firstTsFrame = tsFrame; firstTsUs = tsUs }
                }
                if (planned == null && got >= rate / 2) {
                    // Schedule well past what is already queued in the output.
                    val first = maxOf(output.nextDacUs, System.nanoTime() / 1000) + LEAD_US
                    planned = List(CHIRPS) { first + it * SPACING_US }
                    output.calibration = ChirpPlan(chirp, planned)
                    onProgress("Playing test sounds…")
                    val routed = record.routedDevice
                    Log.i(TAG, "recording from ${routed?.productName} type ${routed?.type} (wanted built-in ${builtIn.id}, got ${routed?.id})")
                    if (routed != null && routed.type != AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                        return@withContext CalibrationOutcome.Failed("Android recorded from \"${routed.productName}\" instead of the TV's built-in microphone, which can't be timed accurately. Adjust by ear instead.")
                    }
                }
                if (planned != null && System.nanoTime() / 1000 > planned.last() + MAX_DELAY_US + 200_000) break
            }
            output.calibration = null
            val recorded = got
            val plan = planned ?: return@withContext CalibrationOutcome.Failed("Recording ended too early.")
            if (tsFrame < 0) return@withContext CalibrationOutcome.Failed("The microphone doesn't report timing on this TV, so the delay can't be measured. Adjust by ear instead.")
            // The capture clock must run at the nominal rate, or frame -> time mapping drifts.
            if (tsFrame > firstTsFrame + rate) {
                val measuredRate = (tsFrame - firstTsFrame) * 1_000_000.0 / (tsUs - firstTsUs)
                Log.i(TAG, "capture rate from timestamps ${"%.1f".format(measuredRate)} Hz")
                if (kotlin.math.abs(measuredRate - rate) > rate * 0.002) {
                    return@withContext CalibrationOutcome.Failed("The microphone's timing is unreliable on this TV (${"%.0f".format(measuredRate)} Hz instead of $rate). Adjust by ear instead.")
                }
            }

            val x = FloatArray(recorded) { pcm[it] / 32768f }
            val rms = sqrt(x.fold(0.0) { a, v -> a + v * v } / recorded)
            Log.i(TAG, "recorded $recorded frames, rms ${"%.5f".format(rms)}, ts frame $tsFrame at $tsUs")
            // A working mic in a quiet room still picks up around -70 dBFS of noise; far below
            // that it is switched off (TCL and others have a physical microphone switch).
            if (rms < 1e-4) return@withContext CalibrationOutcome.Failed("The microphone recorded only silence. Check that the TV's microphone switch is on (often on the bottom edge of the TV), then try again.")

            val expected = plan.map { (tsFrame + (it - tsUs) * rate / 1_000_000L).toInt() }
            val arrivals = ChirpDetector.findArrivals(x, chirp, expected, (MAX_DELAY_US * rate / 1_000_000L).toInt())
            val delays = arrivals.mapIndexed { i, a -> a?.let { (it.frame - expected[i]) * 1000.0 / rate } }
            Log.i(TAG, "delays ms: ${delays.joinToString { it?.let { d -> "%.1f".format(d) } ?: "-" }}; snr ${arrivals.joinToString { it?.snr?.roundToInt()?.toString() ?: "-" }}")
            val r = ChirpDetector.summarize(delays)
            when {
                r.heard == 0 -> CalibrationOutcome.Failed("The microphone didn't hear the test sounds. Turn the volume up, or adjust by ear.")
                !r.ok -> CalibrationOutcome.Failed("The measurements disagreed (heard ${r.heard} of ${r.total}, spread ${"%.0f".format(r.spreadMs)} ms). Try again in a quieter room.")
                else -> CalibrationOutcome.Measured(r.delayMs.roundToInt(), r.heard, r.total, r.spreadMs)
            }
        } finally {
            output.calibration = null
            runCatching { record.stop() }
            record.release()
        }
    }

    /** Prefer a source without noise suppression or AGC, which would smear the chirps. */
    private fun source(): Int {
        val am = context.getSystemService(AudioManager::class.java)
        val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        return if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
    }

    private companion object {
        const val TAG = "SnapTV.Calibrate"
        const val RATE = 48000
        const val RECORD_SECONDS = 10
        const val CHIRPS = 8
        const val SPACING_US = 700_000L
        const val LEAD_US = 500_000L
        /** Longest delay after the DAC we look for; soundbars stay well under this. */
        const val MAX_DELAY_US = 600_000L
        const val CHIRP_AMPLITUDE = 0.6f
    }
}
