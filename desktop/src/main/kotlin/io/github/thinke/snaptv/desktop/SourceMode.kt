package io.github.thinke.snaptv.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import io.github.thinke.snaptv.core.visual.VisualBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

data class SourceState(
    val running: Boolean = false,
    val status: String = "Stopped",
    val sinkName: String = "",
    /** Audio waiting between capture and network, ms. Stays small if pacing works. */
    val bufferedMs: Int = 0,
    /** Frames dropped (capture ran fast) or padded (capture ran slow) to hold real time. */
    val dropped: Long = 0,
    val padded: Long = 0,
    val sentSeconds: Long = 0,
    val error: String? = null,
)

/**
 * Send mode (Linux): this computer becomes the snapcast source. Apps play into a PipeWire output
 * ("Snapcast (multiroom)"); we record its monitor and push raw PCM to snapserver's tcp input
 * (`tcp://0.0.0.0:4953?mode=server&sampleformat=48000:16:2`).
 *
 * snapserver drains a pushed input at exactly real time by its clock, so anything sent faster
 * piles up in its socket as permanent delay (the laptop's old script gained ~3 s over a day).
 * So capture and sending are decoupled: a sender thread sends exactly real time by our
 * monotonic clock, from a small buffer that drops a frame when capture runs fast and pads one
 * when it runs slow. We also connect before capturing, so no start-up burst queues up.
 */
class SourceMode(private val visual: VisualBuffer) {
    private val _state = MutableStateFlow(SourceState())
    val state: StateFlow<SourceState> = _state.asStateFlow()

    @Volatile private var running = false
    private var capture: Thread? = null
    private var sender: Thread? = null
    private var socket: Socket? = null
    @Volatile private var loadedModule: String? = null

    // Captured frames waiting to be sent, interleaved stereo 16-bit. Guarded by `lock`.
    private val lock = Object()
    private val fifo = ShortArray(RATE * CHANNELS * 2) // 2 s
    private var head = 0L // total frames written
    private var tail = 0L // total frames read

    init {
        // Killed or logged out: still remove the output we created, or it outlives us.
        Runtime.getRuntime().addShutdownHook(Thread { loadedModule?.let { runCatching { pactl("unload-module", it) } } })
    }

    fun start(host: String, port: Int, sinkName: String) {
        stop()
        running = true
        _state.value = SourceState(running = true, status = "Preparing…", sinkName = sinkName)
        sender = Thread({ senderLoop(host, port, sinkName) }, "source-sender").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        capture?.join(1000)
        sender?.join(1000)
        capture = null
        sender = null
        loadedModule?.let { runCatching { pactl("unload-module", it) } }
        loadedModule = null
        _state.update { it.copy(running = false, status = "Stopped") }
    }

    private fun senderLoop(host: String, port: Int, sinkName: String) {
        var backoff = 1000L
        while (running) {
            try {
                ensureSink(sinkName)
                _state.update { it.copy(status = "Connecting to $host:$port…", error = null) }
                val s = Socket()
                socket = s
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, port), 5000)
                backoff = 1000L
                synchronized(lock) { head = 0; tail = 0 }
                // Connected first, then capture: nothing queues up while connecting.
                capture = Thread({ captureLoop(sinkName) }, "source-capture").apply { isDaemon = true; start() }
                _state.update { it.copy(status = "Sending to $host:$port") }
                sendPaced(s.getOutputStream())
            } catch (e: Exception) {
                if (!running) break
                _state.update { it.copy(status = "Can't send to $host:$port, retrying…", error = e.message ?: e.javaClass.simpleName) }
            } finally {
                runCatching { socket?.close() }
                capture?.let { c -> c.interrupt(); c.join(1000) }
                capture = null
            }
            if (!running) break
            try { Thread.sleep(backoff) } catch (_: InterruptedException) { break }
            backoff = (backoff * 2).coerceAtMost(10_000L)
        }
    }

    /** Sends exactly real time by the monotonic clock, whatever rate capture delivers. */
    private fun sendPaced(out: OutputStream) {
        val block = RATE / 100 // 10 ms
        val pcm = ShortArray(block * CHANNELS)
        val bytes = ByteArray(pcm.size * 2)
        val startNs = System.nanoTime()
        var sent = 0L
        var dropped = 0L
        var padded = 0L
        var lastReport = startNs
        var checks = 0L
        while (running) {
            val due = (System.nanoTime() - startNs) * RATE / 1_000_000_000L
            if (sent + block > due) {
                Thread.sleep(2)
                continue
            }
            synchronized(lock) {
                var available = head - tail
                // Capture ran fast: drop single frames until we're back at the target. Spread
                // out like this, a frame now and then is inaudible.
                if (available > TARGET_FRAMES + MARGIN_FRAMES) {
                    tail++
                    dropped++
                    available--
                }
                for (i in 0 until block) {
                    if (available > 0) {
                        val src = ((tail % FIFO_FRAMES) * CHANNELS).toInt()
                        pcm[i * 2] = fifo[src]
                        pcm[i * 2 + 1] = fifo[src + 1]
                        tail++
                        available--
                    } else {
                        // Capture ran slow (or silence at start): repeat the last frame.
                        if (i > 0) { pcm[i * 2] = pcm[i * 2 - 2]; pcm[i * 2 + 1] = pcm[i * 2 - 1] }
                        padded++
                    }
                }
            }
            for (i in pcm.indices) {
                val v = pcm[i].toInt()
                bytes[i * 2] = v.toByte()
                bytes[i * 2 + 1] = (v shr 8).toByte()
            }
            out.write(bytes)
            sent += block
            val now = System.nanoTime()
            if (now - lastReport > 1_000_000_000L) {
                lastReport = now
                if (++checks % ROUTE_CHECK_S == 0L) checkRoute()
                val buffered = synchronized(lock) { head - tail }
                _state.update { it.copy(bufferedMs = (buffered * 1000 / RATE).toInt(), dropped = dropped, padded = padded, sentSeconds = sent / RATE) }
            }
        }
    }

    private fun captureLoop(sinkName: String) {
        val pulse = Native.load("pulse-simple", PulseSimple::class.java)
        val err = IntByReference()
        val ss = SampleSpec().apply { rate = RATE; channels = CHANNELS.toByte() }
        // Small fragments: audio reaches the sender (and the visualizer) quickly.
        val attr = BufferAttr().apply { fragsize = RATE * CHANNELS * 2 * 10 / 1000 }
        val stream: Pointer = pulse.pa_simple_new(null, "SnapTV Desktop", PA_STREAM_RECORD, "$sinkName.monitor", STREAM_NAME, ss, null, attr, err)
            ?: run {
                _state.update { it.copy(error = "can't record from $sinkName.monitor (error ${err.value})") }
                runCatching { socket?.close() }
                return
            }
        try {
            val frames = RATE / 100
            val buf = ByteArray(frames * CHANNELS * 2)
            val shorts = ShortArray(frames * CHANNELS)
            while (running && !Thread.currentThread().isInterrupted) {
                if (pulse.pa_simple_read(stream, buf, buf.size.toLong(), err) < 0) error("pa_simple_read error ${err.value}")
                for (i in shorts.indices) shorts[i] = ((buf[i * 2].toInt() and 0xff) or (buf[i * 2 + 1].toInt() shl 8)).toShort()
                synchronized(lock) {
                    for (f in 0 until frames) {
                        if (head - tail >= FIFO_FRAMES) tail++ // overflow: keep the newest
                        val dst = ((head % FIFO_FRAMES) * CHANNELS).toInt()
                        fifo[dst] = shorts[f * 2]
                        fifo[dst + 1] = shorts[f * 2 + 1]
                        head++
                    }
                }
                // What is being sent, drawn now (the rooms hear it a buffer later).
                visual.onPlayed(shorts, frames, CHANNELS, RATE, System.nanoTime() / 1000)
            }
        } catch (e: Exception) {
            if (running) _state.update { it.copy(error = "capture stopped: ${e.message}") }
            runCatching { socket?.close() } // the sender reconnects and restarts capture
        } finally {
            pulse.pa_simple_free(stream)
        }
    }

    /**
     * The old script's silent failure: the output disappears (or PipeWire restarts) and our
     * capture is moved to another source, e.g. the speakers' monitor. We'd keep streaming, but
     * the wrong audio. So check that we still record [sinkName]'s monitor, and start over if not.
     * (Sending at most a moment later is fine: pacing catches up.)
     */
    private fun checkRoute() {
        val name = _state.value.sinkName
        val ok = runCatching {
            val monitor = Json.parseToJsonElement(pactl("-f", "json", "list", "short", "sources")).jsonArray
                .map { it.jsonObject }.firstOrNull { it.str("name") == "$name.monitor" }?.str("index")
            val pid = ProcessHandle.current().pid().toString()
            val ours = Json.parseToJsonElement(pactl("-f", "json", "list", "source-outputs")).jsonArray.map { it.jsonObject }
                .filter { o -> o["properties"]?.jsonObject?.let { it.str("application.process.id") == pid && it.str("media.name") == STREAM_NAME } == true }
            monitor != null && ours.isNotEmpty() && ours.all { it.str("source") == monitor }
        }.getOrDefault(true) // pactl itself failing is no reason to drop the stream
        if (!ok) error("capture is no longer on $name.monitor")
    }

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.content

    /** Uses an existing PipeWire output named [name], or creates one for as long as we run. */
    private fun ensureSink(name: String) {
        val sinks = pactl("list", "short", "sinks")
        if (sinks.lines().any { it.split('\t').getOrNull(1) == name }) {
            // One we made in an earlier run that couldn't clean up (killed: the AppImage unmounts
            // before shutdown hooks run) is ours again, so quitting removes it.
            loadedModule = pactl("list", "short", "modules").lines().map { it.split('\t') }
                .firstOrNull { it.getOrNull(1) == "module-null-sink" && it.getOrNull(2)?.let { a -> "sink_name=$name " in "$a " && OWNER_MARK in a } == true }
                ?.first()
            return
        }
        val id = pactl(
            "load-module", "module-null-sink", "sink_name=$name",
            "sink_properties=device.description=\"Snapcast (multiroom)\" $OWNER_MARK",
            "rate=$RATE", "channels=$CHANNELS", "format=s16le",
        ).trim()
        loadedModule = id.takeIf { it.isNotBlank() }
    }

    private fun pactl(vararg args: String): String {
        val p = ProcessBuilder(listOf("pactl") + args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(5, TimeUnit.SECONDS) || p.exitValue() != 0) error("pactl ${args.first()} failed: ${out.trim()}")
        return out
    }

    private companion object {
        const val STREAM_NAME = "Snapcast source"
        const val OWNER_MARK = "snaptv.owner=snaptv-desktop"
        const val ROUTE_CHECK_S = 5L
        const val RATE = 48000
        const val CHANNELS = 2
        val FIFO_FRAMES = (RATE * 2).toLong()
        /** Audio kept between capture and sending: absorbs capture jitter. */
        const val TARGET_FRAMES = RATE * 40 / 1000
        const val MARGIN_FRAMES = RATE * 20 / 1000
    }
}
