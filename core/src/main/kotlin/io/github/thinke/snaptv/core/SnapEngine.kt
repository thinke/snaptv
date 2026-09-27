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
import io.github.thinke.snaptv.core.sync.SyncBuffer
import io.github.thinke.snaptv.core.sync.SyncStats
import io.github.thinke.snaptv.core.sync.TimeSync
import io.github.thinke.snaptv.core.transport.Scheme
import io.github.thinke.snaptv.core.transport.ServerAddress
import io.github.thinke.snaptv.core.transport.TlsOptions
import io.github.thinke.snaptv.core.transport.Transport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.DataInputStream
import java.net.Socket
import java.util.Base64

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

/**
 * Login for a snapserver with `[authorization]` enabled (`enabled`, `role`, `user` keys). Sent in
 * Hello as the "Basic" scheme, i.e. base64("user:password") - encoded, not encrypted, so anyone on
 * the LAN can read it.
 *
 * snapserver 0.34.0 forces authorization off whatever its config says (snapserver.cpp "TODO: auth"),
 * so it accepts and ignores this; the 401/403 path is only exercised by tests, not a real server.
 */
data class Credentials(val user: String, val password: String) {
    /** The Hello "Auth" param, built exactly like snapclient does from tcp://user:password@host. */
    fun basicParam(): String = Base64.getEncoder().encodeToString("$user:$password".toByteArray(Charsets.UTF_8))

    override fun toString() = "Credentials(user=$user, password=***)"

    companion object {
        /** Like snapclient: send auth when either field is set, otherwise none. */
        fun of(user: String, password: String): Credentials? =
            if (user.isEmpty() && password.isEmpty()) null else Credentials(user, password)
    }
}

/** The server refused our Hello: [code] 401 = bad/missing credentials, 403 = no Streaming permission. */
data class AuthFailure(val code: Int, val error: String, val message: String) {
    val forbidden: Boolean get() = code == ErrorMessage.FORBIDDEN

    fun describe(): String = if (forbidden) "Not allowed to stream: $message" else "Authentication failed: $message"
}

data class ServerSettings(val bufferMs: Int = 1000, val latencyMs: Int = 0, val volume: Int = 100, val muted: Boolean = false)

sealed interface ConnectionState {
    data object Stopped : ConnectionState
    data class Connecting(val host: String, val port: Int) : ConnectionState
    /** The server accepted our Hello (first ServerSettings or CodecHeader), not merely TCP connected. */
    data class Connected(val host: String, val port: Int) : ConnectionState
    /** [auth] is set when the server rejected our credentials; we then retry only slowly. */
    data class Failed(val host: String, val port: Int, val reason: String, val auth: AuthFailure? = null) : ConnectionState
}

interface SnapListener {
    fun onState(state: ConnectionState) {}
    fun onFormat(format: SampleFormat, codec: String) {}
    fun onSettings(settings: ServerSettings) {}
    fun onServerError(message: String) {}
    /** The server rejected our Hello; followed by a [ConnectionState.Failed] with [ConnectionState.Failed.auth] set. */
    fun onAuthFailed(failure: AuthFailure) {}
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
    @Volatile private var socket: Socket? = null
    @Volatile private var transport: Transport? = null
    private val writeLock = Any()
    private var nextMessageId = 0

    fun start(host: String, port: Int = 1704, credentials: Credentials? = null) =
        start(ServerAddress(Scheme.TCP, host, port), credentials = credentials)

    /**
     * Connects over plain TCP, WebSocket or WebSocket over TLS, per [address]. Like snapclient,
     * a user:password in the URL is used as the login when no [credentials] are given.
     */
    fun start(address: ServerAddress, tls: TlsOptions = TlsOptions.Default, credentials: Credentials? = null) {
        stop()
        running = true
        val login = credentials ?: Credentials.of(address.user.orEmpty(), address.password.orEmpty())
        worker = Thread({ connectLoop(address, tls, login) }, "snap-connection").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        runCatching { transport?.close() }
        runCatching { socket?.close() }
        // Wake it from a reconnect backoff, which can be longer than the join below.
        worker?.let { if (it !== Thread.currentThread()) it.interrupt() }
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

    private fun connectLoop(address: ServerAddress, tls: TlsOptions, credentials: Credentials?) {
        val host = address.host
        val port = address.port
        var backoffMs = 500L
        var authBackoffMs = authBackoffMinMs
        while (running) {
            var delayMs = backoffMs
            listener.onState(ConnectionState.Connecting(host, port))
            try {
                val t = Transport.connect(address, tls) { socket = it }
                transport = t
                if (!running) break // stopped while connecting
                backoffMs = 500L
                session(t, credentials) {
                    // Like snapclient, the Hello reply (ServerSettings) is what means we're in.
                    authBackoffMs = authBackoffMinMs
                    listener.onState(ConnectionState.Connected(host, port))
                }
            } catch (e: AuthFailedException) {
                if (!running) break
                // Wrong credentials won't fix themselves and a settings change restarts us anyway;
                // keep retrying, slowly, in case the server's user list is being edited.
                delayMs = authBackoffMs
                authBackoffMs = nextAuthBackoffMs(authBackoffMs)
                listener.onAuthFailed(e.failure)
                listener.onState(ConnectionState.Failed(host, port, e.failure.describe(), e.failure))
            } catch (e: Exception) {
                if (!running) break
                listener.onState(ConnectionState.Failed(host, port, e.message ?: e.javaClass.simpleName))
            } finally {
                runCatching { transport?.close() }
                runCatching { socket?.close() }
                transport = null
                buffer = null
            }
            if (!running) break
            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) {
                break
            }
            backoffMs = (backoffMs * 2).coerceAtMost(5000L)
        }
    }

    private fun session(s: Transport, credentials: Credentials?, onAccepted: () -> Unit) {
        send(MessageType.HELLO, MessageWriter.jsonPayload(helloJson(identity, credentials)))
        val timeThread = Thread({ timeLoop(s) }, "snap-time").apply { isDaemon = true; start() }
        var decoder: Decoder? = null
        var placer: ChunkPlacer? = null
        try {
            val input = DataInputStream(s.input)
            var accepted = false
            fun accept() {
                if (!accepted) { accepted = true; onAccepted() }
            }
            while (running && !s.isClosed) {
                when (val msg = MessageReader.read(input, clock::nowUs)) {
                    is CodecHeader -> {
                        accept()
                        decoder?.close()
                        decoder = null
                        placer = null
                        val d = decoders.create(msg.codec)
                        val format = try {
                            d.setHeader(msg.payload)
                        } catch (e: Exception) {
                            d.close()
                            throw e
                        }
                        decoder = d
                        placer = ChunkPlacer(d, format)
                        buffer = SyncBuffer(format)
                        listener.onFormat(format, msg.codec)
                    }
                    is WireChunk -> {
                        val p = placer ?: continue
                        val b = buffer ?: continue
                        // A decoder that keeps failing ends the session, so it shows as Failed.
                        p.place(msg.timestampUs, msg.payload)?.let(b::add)
                    }
                    is ServerSettingsMessage -> {
                        accept()
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
                    is ErrorMessage -> {
                        if (msg.isAuthError) throw AuthFailedException(AuthFailure(msg.code, msg.error, msg.message))
                        listener.onServerError("${msg.error}: ${msg.message}")
                    }
                    is UnknownMessage -> Unit
                }
            }
        } finally {
            timeThread.interrupt()
            decoder?.close()
        }
    }

    private fun timeLoop(s: Transport) {
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
            val t = transport ?: return
            t.send(MessageWriter.encode(type, nextMessageId++ and 0xffff, clock.nowUs(), payload))
        }
    }

    private class AuthFailedException(val failure: AuthFailure) : Exception(failure.describe())

    /** First retry delay after an auth failure; tests shorten it. */
    @Volatile internal var authBackoffMinMs = AUTH_BACKOFF_MIN_MS

    companion object {
        private const val MIN_TIME_SAMPLES = 5
        internal const val AUTH_BACKOFF_MIN_MS = 30_000L
        internal const val AUTH_BACKOFF_MAX_MS = 300_000L

        /** Auth retries double from 30 s up to 5 min; an accepted Hello starts over. */
        internal fun nextAuthBackoffMs(currentMs: Long): Long = (currentMs * 2).coerceAtMost(AUTH_BACKOFF_MAX_MS)
    }
}

/** The Hello payload, the same keys snapclient's msg::Hello sends. */
internal fun helloJson(identity: ClientIdentity, credentials: Credentials?): String = buildJsonObject {
    put("Arch", identity.arch)
    if (credentials != null) {
        put("Auth", buildJsonObject {
            put("param", credentials.basicParam())
            put("scheme", "Basic")
        })
    }
    put("ClientName", identity.clientName)
    put("HostName", identity.hostName)
    put("ID", identity.id)
    put("Instance", identity.instance)
    put("MAC", identity.mac)
    put("OS", identity.os)
    put("SnapStreamProtocolVersion", JsonPrimitive(2))
    put("Version", identity.version)
}.toString()
