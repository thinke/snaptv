package io.github.thinke.snaptv.core

import io.github.thinke.snaptv.core.codec.Decoder
import io.github.thinke.snaptv.core.codec.DecoderFactory
import io.github.thinke.snaptv.core.codec.SampleFormat
import io.github.thinke.snaptv.core.protocol.CodecHeader
import io.github.thinke.snaptv.core.protocol.ErrorMessage
import io.github.thinke.snaptv.core.protocol.MessageReader
import io.github.thinke.snaptv.core.protocol.MessageType
import io.github.thinke.snaptv.core.protocol.MessageWriter
import io.github.thinke.snaptv.core.protocol.ServerSettingsMessage
import io.github.thinke.snaptv.core.protocol.TimeMessage
import io.github.thinke.snaptv.core.protocol.UnknownMessage
import io.github.thinke.snaptv.core.protocol.WireChunk
import io.github.thinke.snaptv.core.sync.PcmChunk
import io.github.thinke.snaptv.core.sync.SyncBuffer
import io.github.thinke.snaptv.core.sync.SyncStats
import io.github.thinke.snaptv.core.sync.TimeSync
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/** Monotonic microseconds. On Android this must match AudioTimestamp.nanoTime's clock. */
fun interface MonotonicClock {
    fun nowUs(): Long

    companion object {
        val System = MonotonicClock { java.lang.System.nanoTime() / 1000 }
    }
}

data class ClientIdentity(
    /** Stable unique id; snapserver keys client config (name, volume, latency) on it. */
    val id: String,
    val hostName: String,
    val os: String,
    val arch: String,
    val mac: String = "00:00:00:00:00:00",
    val clientName: String = "SnapTV",
    val version: String = "0.1.0",
    val instance: Int = 1,
)

data class ServerSettings(val bufferMs: Int = 1000, val latencyMs: Int = 0, val volume: Int = 100, val muted: Boolean = false)

sealed interface ConnectionState {
    data object Stopped : ConnectionState
    data class Connecting(val host: String, val port: Int) : ConnectionState
    data class Connected(val host: String, val port: Int) : ConnectionState
    data class Failed(val host: String, val port: Int, val reason: String) : ConnectionState
}

interface SnapListener {
    fun onState(state: ConnectionState) {}
    fun onFormat(format: SampleFormat, codec: String) {}
    fun onSettings(settings: ServerSettings) {}
    fun onServerError(message: String) {}
}

/** Receives exactly what is being played, stamped with when it will be heard (local clock). */
fun interface PlaybackTap {
    fun onPlayed(samples: ShortArray, frames: Int, channels: Int, rate: Int, heardAtUs: Long)
}

/**
 * A snapcast client without an audio device: it connects, keeps the clock in sync and decodes
 * audio into a [SyncBuffer]. The platform's audio output pulls from [render].
 */
class SnapEngine(
    private val identity: ClientIdentity,
    private val listener: SnapListener,
    private val clock: MonotonicClock = MonotonicClock.System,
    private val decoders: DecoderFactory = DecoderFactory.Default,
) {
    val timeSync = TimeSync()

    @Volatile var buffer: SyncBuffer? = null
        private set

    @Volatile var settings = ServerSettings()
        private set

    /** Extra delay after the DAC (HDMI, soundbar, TV processing) that we compensate for. */
    @Volatile var outputLatencyMs: Int = 0

    @Volatile var tap: PlaybackTap? = null

    @Volatile private var running = false
    private var worker: Thread? = null
    private var socket: Socket? = null
    private var out: OutputStream? = null
    private val writeLock = Any()
    private var nextMessageId = 0

    fun start(host: String, port: Int = 1704) {
        stop()
        running = true
        worker = Thread({ connectLoop(host, port) }, "snap-connection").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        worker?.let { if (it !== Thread.currentThread()) it.join(2000) }
        worker = null
        buffer = null
        listener.onState(ConnectionState.Stopped)
    }

    /**
     * Fills [out] with [frames] frames that should reach the DAC at local time [dacUs].
     * Returns false (and writes silence) when there is nothing to play yet.
     */
    fun render(out: ShortArray, frames: Int, dacUs: Long): Boolean {
        val b = buffer
        if (b == null || timeSync.sampleCount < MIN_TIME_SAMPLES) {
            out.fill(0, 0, frames * (b?.format?.channels ?: 2))
            return false
        }
        val s = settings
        val compensationUs = (s.latencyMs + outputLatencyMs) * 1000L
        val playAt = timeSync.toServer(dacUs) - s.bufferMs * 1000L + compensationUs
        val played = b.read(out, frames, playAt)
        tap?.onPlayed(out, frames, b.format.channels, b.format.rate, dacUs + compensationUs)
        return played
    }

    fun stats(): SyncStats? = buffer?.stats()

    /** Tell the server our volume changed locally (e.g. from the TV remote). */
    fun sendClientInfo(volume: Int, muted: Boolean) {
        val json = buildJsonObject {
            put("volume", volume)
            put("muted", muted)
        }.toString()
        send(MessageType.CLIENT_INFO, MessageWriter.jsonPayload(json))
    }

    private fun connectLoop(host: String, port: Int) {
        var backoffMs = 500L
        while (running) {
            listener.onState(ConnectionState.Connecting(host, port))
            try {
                val s = Socket()
                socket = s
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, port), 5000)
                s.soTimeout = 15_000 // server sends time replies every second; silence means dead
                out = s.getOutputStream()
                backoffMs = 500L
                listener.onState(ConnectionState.Connected(host, port))
                session(s)
            } catch (e: Exception) {
                if (!running) break
                listener.onState(ConnectionState.Failed(host, port, e.message ?: e.javaClass.simpleName))
            } finally {
                runCatching { socket?.close() }
                out = null
                buffer = null
            }
            if (!running) break
            try {
                Thread.sleep(backoffMs)
            } catch (_: InterruptedException) {
                break
            }
            backoffMs = (backoffMs * 2).coerceAtMost(5000L)
        }
    }

    private fun session(s: Socket) {
        send(MessageType.HELLO, MessageWriter.jsonPayload(helloJson()))
        val timeThread = Thread({ timeLoop(s) }, "snap-time").apply { isDaemon = true; start() }
        var decoder: Decoder? = null
        try {
            val input = DataInputStream(BufferedInputStream(s.getInputStream(), 64 * 1024))
            while (running && !s.isClosed) {
                when (val msg = MessageReader.read(input, clock::nowUs)) {
                    is CodecHeader -> {
                        decoder?.close()
                        decoder = null
                        val d = decoders.create(msg.codec)
                        val format = try {
                            d.setHeader(msg.payload)
                        } catch (e: Exception) {
                            d.close()
                            throw e
                        }
                        decoder = d
                        buffer = SyncBuffer(format)
                        listener.onFormat(format, msg.codec)
                    }
                    is WireChunk -> {
                        val d = decoder ?: continue
                        val b = buffer ?: continue
                        val pcm = try {
                            d.decode(msg.payload)
                        } catch (e: Exception) {
                            continue // one corrupt chunk: skip it, the sync buffer pads the gap
                        }
                        val startUs = Decoder.chunkStartUs(msg.timestampUs, d.carriedFrames, b.format.rate)
                        b.add(PcmChunk(startUs, pcm, b.format.channels))
                    }
                    is ServerSettingsMessage -> {
                        val o = Json.parseToJsonElement(msg.json).jsonObject
                        val ns = ServerSettings(
                            bufferMs = o["bufferMs"]?.jsonPrimitive?.int ?: settings.bufferMs,
                            latencyMs = o["latency"]?.jsonPrimitive?.int ?: settings.latencyMs,
                            volume = o["volume"]?.jsonPrimitive?.int ?: settings.volume,
                            muted = o["muted"]?.jsonPrimitive?.boolean ?: settings.muted,
                        )
                        settings = ns
                        listener.onSettings(ns)
                    }
                    is TimeMessage -> timeSync.add(
                        c2sUs = msg.latencyUs,
                        s2cUs = msg.header.receivedUs - msg.header.sentUs,
                        nowUs = msg.header.receivedUs,
                    )
                    is ErrorMessage -> listener.onServerError("${msg.error}: ${msg.message}")
                    is UnknownMessage -> Unit
                }
            }
        } finally {
            timeThread.interrupt()
            decoder?.close()
        }
    }

    private fun timeLoop(s: Socket) {
        try {
            // A quick burst gives a usable median before the first audio has to play.
            var sent = 0
            while (running && !s.isClosed) {
                send(MessageType.TIME, MessageWriter.timePayload())
                sent++
                Thread.sleep(if (sent < 20) 50L else 1000L)
            }
        } catch (_: InterruptedException) {
        } catch (_: Exception) {
            runCatching { s.close() } // write failed: let the reader notice and reconnect
        }
    }

    private fun send(type: Int, payload: ByteArray) {
        synchronized(writeLock) {
            val o = out ?: return
            MessageWriter.write(o, type, nextMessageId++ and 0xffff, clock.nowUs(), payload)
        }
    }

    private fun helloJson(): String = buildJsonObject {
        put("Arch", identity.arch)
        put("ClientName", identity.clientName)
        put("HostName", identity.hostName)
        put("ID", identity.id)
        put("Instance", identity.instance)
        put("MAC", identity.mac)
        put("OS", identity.os)
        put("SnapStreamProtocolVersion", JsonPrimitive(2))
        put("Version", identity.version)
    }.toString()

    companion object {
        private const val MIN_TIME_SAMPLES = 5
    }
}
