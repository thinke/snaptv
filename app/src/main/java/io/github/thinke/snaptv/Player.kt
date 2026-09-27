package io.github.thinke.snaptv

import android.content.Context
import android.os.Build
import android.provider.Settings
import io.github.thinke.snaptv.core.ClientIdentity
import io.github.thinke.snaptv.core.ConnectionState
import io.github.thinke.snaptv.core.ServerSettings
import io.github.thinke.snaptv.core.SnapEngine
import io.github.thinke.snaptv.core.SnapListener
import io.github.thinke.snaptv.core.codec.SampleFormat
import io.github.thinke.snaptv.core.sync.SyncStats
import io.github.thinke.snaptv.core.visual.VisualBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
    /** Real sound in the last few seconds; the source streams digital silence when idle. */
    val audible: Boolean = false,
)

/**
 * App-wide playback: engine + audio output + visual tap, independent of any activity so the
 * TV keeps playing with the screen on something else. Started and stopped by [PlaybackService].
 */
class Player(context: Context, private val prefs: Prefs) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val discovery = Discovery(context)
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    val visual = VisualBuffer()

    private val engine = SnapEngine(identity(context, prefs.clientId), object : SnapListener {
        override fun onState(state: ConnectionState) = _state.update { it.copy(connection = state) }
        override fun onFormat(format: SampleFormat, codec: String) = _state.update { it.copy(format = format, codec = codec, serverError = null) }
        override fun onSettings(settings: ServerSettings) {
            _state.update { it.copy(server = settings) }
            applyVolume(settings)
        }
        override fun onServerError(message: String) = _state.update { it.copy(serverError = message) }
    }).also { it.tap = visual }

    private val output = AudioOutput(engine)
    private var sessionJob: Job? = null

    init {
        // Latency calibration applies live, no reconnect needed.
        scope.launch {
            prefs.settings.map { it.latencyMs }.distinctUntilChanged().collect { engine.outputLatencyMs = it }
        }
    }

    fun start() {
        if (sessionJob?.isActive == true) return
        _state.update { it.copy(active = true) }
        sessionJob = scope.launch {
            // Reconnect whenever the chosen server changes.
            prefs.settings.map { it.serverHost to it.serverPort }.distinctUntilChanged().collect { (host, port) ->
                engine.stop()
                val target = if (host.isNotBlank()) host to port else discover()
                if (target != null) {
                    engine.start(target.first, target.second)
                    output.start()
                }
            }
        }
        scope.launch {
            val probe = FloatArray(4800)
            var lastAudibleUs = Long.MIN_VALUE
            while (sessionJob?.isActive == true) {
                val nowUs = System.nanoTime() / 1000
                if (visual.window(nowUs, probe) && rms(probe) > AUDIBLE_RMS) lastAudibleUs = nowUs
                val audible = lastAudibleUs != Long.MIN_VALUE && nowUs - lastAudibleUs < AUDIBLE_HOLD_US
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
        _state.update { PlayerState() }
    }

    /** Volume from the remote: applied locally at once and reported so Snapweb stays in step. */
    fun changeVolume(delta: Int) {
        val s = engine.settings
        val v = (s.volume + delta).coerceIn(0, 100)
        val next = s.copy(volume = v, muted = false)
        applyVolume(next)
        _state.update { it.copy(server = next) }
        scope.launch(Dispatchers.IO) { runCatching { engine.sendClientInfo(v, false) } }
    }

    private suspend fun discover(): Pair<String, Int>? {
        _state.update { it.copy(discovering = true) }
        try {
            while (true) {
                val found = withTimeoutOrNull(10_000) { discovery.servers().first { it.isNotEmpty() } }
                if (found != null) return found.first().host to found.first().port
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

    private fun identity(context: Context, id: String): ClientIdentity {
        val name = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL
        return ClientIdentity(
            id = id,
            hostName = name,
            os = "Android ${Build.VERSION.RELEASE}",
            arch = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            clientName = "SnapTV",
            version = BuildConfig.VERSION_NAME,
        )
    }

    private fun rms(x: FloatArray): Float {
        var sum = 0f
        for (v in x) sum += v * v
        return kotlin.math.sqrt(sum / x.size)
    }

    companion object {
        private const val AUDIBLE_RMS = 0.001f // about -60 dBFS
        private const val AUDIBLE_HOLD_US = 8_000_000L

        fun volumeLabel(s: ServerSettings) = if (s.muted) "muted" else "${s.volume}%"
        fun ms(us: Long) = Math.round(us / 100.0) / 10.0
    }
}
