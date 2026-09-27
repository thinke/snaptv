package io.github.thinke.snaptv.core.session

import io.github.thinke.snaptv.core.AuthFailure
import io.github.thinke.snaptv.core.ClientIdentity
import io.github.thinke.snaptv.core.ConnectionState
import io.github.thinke.snaptv.core.Credentials
import io.github.thinke.snaptv.core.ServerSettings
import io.github.thinke.snaptv.core.SnapEngine
import io.github.thinke.snaptv.core.SnapListener
import io.github.thinke.snaptv.core.codec.DecoderFactory
import io.github.thinke.snaptv.core.codec.SampleFormat
import io.github.thinke.snaptv.core.control.ControlClient
import io.github.thinke.snaptv.core.control.RoomInfo
import io.github.thinke.snaptv.core.sync.SyncStats
import io.github.thinke.snaptv.core.transport.ServerAddress
import io.github.thinke.snaptv.core.transport.TlsOptions
import io.github.thinke.snaptv.core.visual.VisualBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.sqrt

data class PlayerState(
    val active: Boolean = false,
    val connection: ConnectionState = ConnectionState.Stopped,
    val discovering: Boolean = false,
    val format: SampleFormat? = null,
    val codec: String? = null,
    val server: ServerSettings = ServerSettings(),
    val sync: SyncStats? = null,
    val clockOffsetUs: Long = 0,
    val rttUs: Long = 0,
    val outputBufferMs: Int = 0,
    val serverError: String? = null,
    /** Set while the server rejects our login; the engine then retries only every 30 s or more. */
    val authError: AuthFailure? = null,
    /** Real sound in the last few seconds; the source streams digital silence when idle. */
    val audible: Boolean = false,
    /** Our name, group and stream from the control API; null until known. */
    val room: RoomInfo? = null,
    /** The decoder actually in use, e.g. "FFmpeg 6.0 (flac)". */
    val decoder: String? = null,
)

/** The platform's audio output: AudioTrack on Android, PulseAudio/PipeWire on desktop. */
interface AudioSink {
    fun start()
    fun stop()
    /** Linear gain applied to the output (volume). */
    var gain: Float
    /** How far ahead of the speaker the output is, for the stats overlay. */
    val bufferedMs: Int
}

/**
 * One Snapcast room: engine, control API and the logic both apps share (server choice and
 * reconnects, volume, source, name, audio delay stored on the server, stats). The platform
 * brings the audio output, discovery and decoders.
 */
class SnapSession(
    identity: ClientIdentity,
    val prefs: Prefs,
    decoders: DecoderFactory,
    sink: (SnapEngine) -> AudioSink,
    /** Finds a snapserver on the network, or returns null after trying for a while. */
    private val discover: suspend () -> ServerAddress?,
    private val log: (String) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    val visual = VisualBuffer()

    val engine = SnapEngine(identity, object : SnapListener {
        override fun onState(state: ConnectionState) = _state.update {
            // Connected means the server took our Hello, so a previous auth refusal no longer applies.
            if (state is ConnectionState.Connected && it.authError != null) {
                it.copy(connection = state, authError = null, serverError = null)
            } else {
                it.copy(connection = state)
            }
        }
        override fun onFormat(format: SampleFormat, codec: String) =
            _state.update { it.copy(format = format, codec = codec, serverError = null, authError = null) }
        override fun onSettings(settings: ServerSettings) {
            if (settings.latencyMs == pendingDelayMs) pendingDelayMs = null
            _state.update { it.copy(server = settings) }
            applyVolume(settings)
        }
        override fun onServerError(message: String) = _state.update { it.copy(serverError = message) }
        // serverError is what the status line shows, so auth failures surface there too.
        override fun onAuthFailed(failure: AuthFailure) =
            _state.update { it.copy(authError = failure, serverError = failure.describe()) }
    }, decoders = decoders).also {
        it.tap = visual
        it.syncEvents = log
    }

    val output: AudioSink = sink(engine)
    val control = ControlClient(prefs.clientId) { room -> _state.update { it.copy(room = room) } }
    private var sessionJob: Job? = null

    /** The server host we are connected to (for extras that need a second connection). */
    @Volatile var serverHost: String? = null
        private set

    val clientId: String get() = prefs.clientId

    /** Total delay compensated after the DAC: the server-side latency plus any local fallback. */
    val audioDelayMs: StateFlow<Int> = combine(state, prefs.settings) { s, p -> s.server.latencyMs + p.latencyMs }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    // Last value sent to the server and not yet echoed back, so quick presses add up.
    @Volatile private var pendingDelayMs: Int? = null

    init {
        // A local delay applies live, no reconnect needed.
        scope.launch {
            prefs.settings.map { it.latencyMs }.distinctUntilChanged().collect { engine.outputLatencyMs = it }
        }
        // Audio delay lives on the server (the client's latency, as in Snapweb). A value kept
        // locally, from before, or set while the control API was unreachable, moves there once
        // we can reach it, so it is never applied twice.
        scope.launch {
            state.map { it.room != null && it.connection is ConnectionState.Connected }.distinctUntilChanged().collect { online ->
                val local = prefs.settings.value.latencyMs
                if (online && local != 0) setAudioDelay(state.value.server.latencyMs + local)
            }
        }
    }

    /** For the platform to show which decoder is in use. */
    fun setDecoderLabel(label: String) = _state.update { it.copy(decoder = label) }

    fun start() {
        if (sessionJob?.isActive == true) return
        _state.update { it.copy(active = true) }
        sessionJob = scope.launch {
            // Reconnect whenever the chosen server, the login or the TLS setting changes, and on
            // a decoder change: the new decoder takes over at the next codec header.
            prefs.settings.map { Endpoint(it.serverHost, it.serverPort, Credentials.of(it.authUser, it.authPassword), it.tlsTrustAll, it.decoders) }
                .distinctUntilChanged().collect { (host, port, credentials, trustAll) ->
                    engine.stop()
                    control.stop()
                    _state.update { it.copy(authError = null, serverError = null) }
                    val target = if (host.isNotBlank()) {
                        try {
                            ServerAddress.fromStored(host, port)
                        } catch (e: IllegalArgumentException) {
                            _state.update { it.copy(connection = ConnectionState.Failed(host, port, e.message ?: "bad server address")) }
                            null
                        }
                    } else {
                        findServer()
                    }
                    if (target != null) {
                        serverHost = target.host
                        engine.start(target, if (trustAll) TlsOptions(trustAll = true) else TlsOptions.Default, credentials)
                        // The control API is plain JSON-RPC on 1705 whatever transport the audio uses.
                        control.start(target.host)
                        output.start()
                    }
                }
        }
        scope.launch {
            val probe = FloatArray(4800)
            var ticks = 0
            var lastAudibleUs = Long.MIN_VALUE
            while (sessionJob?.isActive == true) {
                val nowUs = System.nanoTime() / 1000
                if (visual.window(nowUs, probe) && rms(probe) > AUDIBLE_RMS) lastAudibleUs = nowUs
                val audible = lastAudibleUs != Long.MIN_VALUE && nowUs - lastAudibleUs < AUDIBLE_HOLD_US
                if (++ticks % 10 == 0) engine.stats()?.let { y ->
                    log("median ${y.medianErrorUs}us ppm ${y.correctionPpm} queued ${y.queuedMs}ms resyncs ${y.hardSyncs} underruns ${y.underruns} rtt ${engine.timeSync.lastRttUs}us out ${output.bufferedMs}ms")
                }
                _state.update {
                    it.copy(
                        sync = engine.stats(),
                        clockOffsetUs = engine.timeSync.offsetUs,
                        rttUs = engine.timeSync.lastRttUs,
                        outputBufferMs = output.bufferedMs,
                        audible = audible,
                    )
                }
                delay(500)
            }
        }
    }

    fun stop() {
        sessionJob?.cancel()
        sessionJob = null
        output.stop()
        engine.stop()
        control.stop()
        _state.update { PlayerState() }
    }

    /** Volume from the remote/keyboard: applied at once and reported so Snapweb stays in step. */
    fun changeVolume(delta: Int) {
        val s = engine.settings
        val v = (s.volume + delta).coerceIn(0, 100)
        val next = s.copy(volume = v, muted = false)
        applyVolume(next)
        _state.update { it.copy(server = next) }
        scope.launch(Dispatchers.IO) { runCatching { engine.sendClientInfo(v, false) } }
    }

    /** Switch the source for this room's whole group (as Snapweb does). */
    fun setStream(streamId: String) {
        val room = _state.value.room ?: return
        scope.launch(Dispatchers.IO) { control.setStream(room.groupId, streamId) }
    }

    /** The name shown for this room in Snapweb and other controllers. */
    fun setName(name: String) {
        scope.launch(Dispatchers.IO) { control.setName(name) }
    }

    fun setAudioDelay(ms: Int) {
        val room = state.value.room
        if (room != null && state.value.connection is ConnectionState.Connected) {
            val v = ms.coerceIn(0, MAX_DELAY_MS)
            pendingDelayMs = v
            if (prefs.settings.value.latencyMs != 0) prefs.update { it.copy(latencyMs = 0) }
            scope.launch(Dispatchers.IO) { control.setLatency(v) }
        } else {
            prefs.update { it.copy(latencyMs = ms.coerceIn(-500, MAX_DELAY_MS)) }
        }
    }

    fun adjustAudioDelay(stepMs: Int) = setAudioDelay((pendingDelayMs ?: audioDelayMs.value) + stepMs)

    /** Runs [block] on the session's scope (for platform extras that belong to its lifetime). */
    fun launch(block: suspend CoroutineScope.() -> Unit) = scope.launch(Dispatchers.IO, block = block)

    private suspend fun findServer(): ServerAddress? {
        _state.update { it.copy(discovering = true) }
        try {
            while (true) {
                discover()?.let { return it }
                _state.update { it.copy(connection = ConnectionState.Failed("", 0, "no snapserver found on the network")) }
            }
        } finally {
            _state.update { it.copy(discovering = false) }
        }
    }

    private fun applyVolume(s: ServerSettings) {
        // Perceptual curve: 50% on the slider sounds about half as loud, not barely quieter.
        val linear = s.volume / 100f
        output.gain = if (s.muted) 0f else linear * linear
    }

    private fun rms(x: FloatArray): Float {
        var sum = 0f
        for (v in x) sum += v * v
        return sqrt(sum / x.size)
    }

    private data class Endpoint(val host: String, val port: Int, val credentials: Credentials?, val tlsTrustAll: Boolean, val decoders: String)

    companion object {
        const val MAX_DELAY_MS = 2000
        private const val AUDIBLE_RMS = 0.001f // about -60 dBFS
        private const val AUDIBLE_HOLD_US = 8_000_000L

        fun volumeLabel(s: ServerSettings) = if (s.muted) "muted" else "${s.volume}%"
        fun ms(us: Long) = Math.round(us / 100.0) / 10.0
    }
}
