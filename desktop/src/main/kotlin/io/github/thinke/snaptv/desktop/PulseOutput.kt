package io.github.thinke.snaptv.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.IntByReference
import io.github.thinke.snaptv.core.SnapEngine
import io.github.thinke.snaptv.core.codec.SampleFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The few calls of libpulse-simple we need (PipeWire provides it via pipewire-pulseaudio). */
@Suppress("FunctionName")
private interface PulseSimple : Library {
    fun pa_simple_new(
        server: String?, name: String, dir: Int, dev: String?, streamName: String,
        ss: SampleSpec, map: Pointer?, attr: BufferAttr?, error: IntByReference,
    ): Pointer?

    fun pa_simple_write(s: Pointer, data: ByteArray, bytes: Long, error: IntByReference): Int
    /** Microseconds until a sample written now is played (pa_usec_t). */
    fun pa_simple_get_latency(s: Pointer, error: IntByReference): Long
    fun pa_simple_free(s: Pointer)
}

@Structure.FieldOrder("format", "rate", "channels")
class SampleSpec : Structure() {
    @JvmField var format: Int = PA_SAMPLE_S16LE
    @JvmField var rate: Int = 48000
    @JvmField var channels: Byte = 2
}

@Structure.FieldOrder("maxlength", "tlength", "prebuf", "minreq", "fragsize")
class BufferAttr : Structure() {
    @JvmField var maxlength: Int = -1
    @JvmField var tlength: Int = -1
    @JvmField var prebuf: Int = -1
    @JvmField var minreq: Int = -1
    @JvmField var fragsize: Int = -1
}

private const val PA_STREAM_PLAYBACK = 1
private const val PA_SAMPLE_S16LE = 3

/**
 * Pulls audio from the engine into a PulseAudio/PipeWire stream. Timing works like the TV's
 * AudioTrack path: before each block we ask how long until newly written audio is heard
 * (pa_simple_get_latency) and render the samples due at that moment.
 */
class PulseOutput(private val engine: SnapEngine, private val appName: String) {
    @Volatile private var running = false
    @Volatile var gain: Float = 1f
    @Volatile var latencyMs: Int = 0
        private set
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "pulse-output").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        thread?.join(1000)
        thread = null
    }

    private fun loop() {
        val pulse = runCatching { Native.load("pulse-simple", PulseSimple::class.java) }.getOrElse {
            System.err.println("SnapTV: can't load libpulse-simple (${it.message}); install pipewire-pulseaudio")
            return
        }
        while (running) {
            val format = engine.buffer?.format
            if (format == null) { Thread.sleep(50); continue }
            val err = IntByReference()
            val ss = SampleSpec().apply { rate = format.rate; channels = format.channels.toByte() }
            // Ask for ~60 ms of buffering: enough against scheduling hiccups, little added delay.
            val attr = BufferAttr().apply { tlength = format.rate * format.channels * 2 * 60 / 1000 }
            val stream = pulse.pa_simple_new(null, appName, PA_STREAM_PLAYBACK, null, "Snapcast", ss, null, attr, err)
            if (stream == null) {
                System.err.println("SnapTV: can't open PulseAudio stream (error ${err.value})")
                Thread.sleep(1000)
                continue
            }
            try {
                play(pulse, stream, format)
            } catch (e: Exception) {
                System.err.println("SnapTV: audio output failed: ${e.message}")
            } finally {
                pulse.pa_simple_free(stream)
            }
        }
    }

    private fun play(pulse: PulseSimple, stream: Pointer, f: SampleFormat) {
        val blockFrames = f.rate / 100 // 10 ms
        val buf = ShortArray(blockFrames * f.channels)
        val bytes = ByteArray(buf.size * 2)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val err = IntByReference()
        while (running && engine.buffer?.format == f) {
            val lat = pulse.pa_simple_get_latency(stream, err)
            latencyMs = (lat / 1000).toInt()
            val dacUs = System.nanoTime() / 1000 + lat
            engine.render(buf, blockFrames, dacUs)
            val g = gain
            if (g != 1f) for (i in buf.indices) buf[i] = (buf[i] * g).toInt().coerceIn(-32768, 32767).toShort()
            bb.clear()
            bb.put(buf)
            // Blocks while the server's buffer is full, which paces us at real time.
            if (pulse.pa_simple_write(stream, bytes, bytes.size.toLong(), err) < 0) {
                throw IllegalStateException("pa_simple_write error ${err.value}")
            }
        }
    }
}
