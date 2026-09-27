package io.github.thinke.snaptv

import android.content.Context
import android.os.Build
import android.provider.Settings
import io.github.thinke.snaptv.core.ClientIdentity
import io.github.thinke.snaptv.core.ServerSettings
import io.github.thinke.snaptv.core.session.SnapSession
import io.github.thinke.snaptv.core.source.ClickTrackSource
import io.github.thinke.snaptv.core.transport.Scheme
import io.github.thinke.snaptv.core.transport.ServerAddress
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

typealias PlayerState = io.github.thinke.snaptv.core.session.PlayerState

/**
 * App-wide playback, independent of any activity so the TV keeps playing with the screen on
 * something else. The shared logic is [SnapSession] in core; this adds Android's audio output,
 * discovery and decoders, and the Android-only delay tools. Started and stopped by [PlaybackService].
 */
class Player(context: Context, private val prefs: Prefs) {
    private val discovery = Discovery(context)
    private lateinit var session: SnapSession

    init {
        session = SnapSession(
            identity = identity(context, prefs.clientId),
            prefs = prefs,
            decoders = SelectableDecoders(prefs) { codec, label -> session.setDecoderLabel("$label ($codec)") },
            sink = { AudioOutput(it) },
            discover = {
                withTimeoutOrNull(10_000) { discovery.servers().first { it.isNotEmpty() } }
                    ?.first()?.let { ServerAddress(Scheme.TCP, it.host, it.port) }
            },
            log = { android.util.Log.i("SnapTV.Sync", it) },
        )
        // A room sync test that didn't end cleanly (crash, reboot) still owes rooms their music.
        session.launch {
            state.map { it.room != null }.distinctUntilChanged().collect { online ->
                if (online && clickSource == null) restoreAfterSyncTest()
            }
        }
    }

    private val output: AudioOutput get() = session.output as AudioOutput

    val state: StateFlow<PlayerState> get() = session.state
    val visual get() = session.visual
    val audioDelayMs: StateFlow<Int> get() = session.audioDelayMs
    val clientId: String get() = session.clientId

    fun start() = session.start()
    fun stop() = session.stop()
    fun changeVolume(delta: Int) = session.changeVolume(delta)
    fun setStream(streamId: String) = session.setStream(streamId)
    fun setName(name: String) = session.setName(name)
    fun setAudioDelay(ms: Int) = session.setAudioDelay(ms)
    fun adjustAudioDelay(stepMs: Int) = session.adjustAudioDelay(stepMs)

    /**
     * Beep once a second, heard on the beat when the delay settings are right: the sound half of
     * the manual sync test. Replaces the music while on.
     */
    fun setSyncTestTone(on: Boolean) {
        val engine = session.engine
        output.metronome = if (on) {
            Metronome(beep(), SYNC_TEST_PERIOD_US) { (engine.settings.latencyMs + engine.outputLatencyMs) * 1000L }
        } else {
            null
        }
    }

    private fun beep(): FloatArray {
        val n = 48000 * 30 / 1000
        return FloatArray(n) { i ->
            // 30 ms of 1 kHz with a fast fade in and out, so the onset is sharp but click-free.
            val env = minOf(1f, i / 48f, (n - i) / 240f)
            (0.5 * env * kotlin.math.sin(2 * Math.PI * 1000 * i / 48000)).toFloat()
        }
    }

    /** Measures this TV's delay after the DAC; see [Calibrator]. */
    fun calibrator(context: Context) = Calibrator(context, output)

    private var clickSource: ClickTrackSource? = null

    /** Whether snapserver has the click-track input the room sync test feeds. */
    fun roomSyncTestAvailable(): Boolean = state.value.room?.streams?.any { it.id == SYNC_TEST_STREAM } == true

    /**
     * Plays a click track through snapcast in this TV's group: every room in the group clicks
     * together by snapcast's timing, so the TV's delay can be tuned against them. A SnapTV only
     * ever switches its own group; rooms join the test by being grouped with the TV.
     */
    fun startRoomSyncTest() {
        val room = state.value.room ?: return
        val host = session.serverHost ?: return
        val own = room.groups.firstOrNull { it.id == room.groupId } ?: return
        // Don't overwrite a pending restore with the click track itself.
        if (prefs.syncTestRestore == null && own.streamId != SYNC_TEST_STREAM) {
            prefs.syncTestRestore = "${own.id}=${own.streamId}"
        }
        session.launch {
            clickSource = ClickTrackSource(ipv4For(host, room.serverHostName), SYNC_TEST_PORT).also { it.start() }
            session.control.setStream(own.id, SYNC_TEST_STREAM)
        }
    }

    /**
     * snapserver's tcp inputs listen on IPv4 only (its source URI can't carry an IPv6 bind
     * address), but discovery may have found the server by IPv6. Look for an IPv4 address by the
     * server's own hostname; without one, try the address we have.
     */
    private fun ipv4For(host: String, serverHostName: String): String {
        runCatching { java.net.InetAddress.getByName(host) }.getOrNull()?.let { if (it is java.net.Inet4Address) return host }
        for (name in listOf(serverHostName, "$serverHostName.local").filter { serverHostName.isNotBlank() }) {
            val v4 = runCatching { java.net.InetAddress.getAllByName(name) }.getOrNull()?.firstOrNull { it is java.net.Inet4Address }
            if (v4 != null) return v4.hostAddress ?: continue
        }
        return host
    }

    fun stopRoomSyncTest() {
        clickSource?.stop()
        clickSource = null
        restoreAfterSyncTest()
    }

    /** Error from the click source, e.g. snapserver has no SyncTest input. */
    fun roomSyncTestError(): String? = clickSource?.error

    private fun restoreAfterSyncTest() {
        val saved = prefs.syncTestRestore ?: return
        val pairs = saved.split(';').mapNotNull { e -> e.split('=', limit = 2).takeIf { it.size == 2 } }
        session.launch {
            pairs.forEach { (group, stream) -> session.control.setStream(group, stream) }
            prefs.syncTestRestore = null
        }
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

    companion object {
        const val SYNC_TEST_PERIOD_US = 1_000_000L
        const val MAX_DELAY_MS = SnapSession.MAX_DELAY_MS
        /** The snapserver input the room sync test feeds: `tcp://0.0.0.0:4954?name=SyncTest&mode=server`. */
        const val SYNC_TEST_STREAM = "SyncTest"
        const val SYNC_TEST_PORT = 4954

        fun volumeLabel(s: ServerSettings) = SnapSession.volumeLabel(s)
        fun ms(us: Long) = SnapSession.ms(us)
    }
}
