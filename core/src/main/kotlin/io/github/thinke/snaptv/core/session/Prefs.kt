package io.github.thinke.snaptv.core.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Settings shared by the Android and desktop apps; each app ignores what doesn't apply to it. */
data class AppSettings(
    /**
     * Empty means "discover automatically". Otherwise a host name/IP (tcp to [serverPort]) or a
     * snapclient style URL such as ws://host:1780 or wss://host:1788, whose own or default port wins.
     */
    val serverHost: String = "",
    val serverPort: Int = 1704,
    /** wss only: accept any server certificate (snapclient's behaviour without --server-cert). */
    val tlsTrustAll: Boolean = false,
    /** Local fallback for the delay after the DAC; normally 0, the value lives on the server. */
    val latencyMs: Int = 0,
    val visualStyle: Int = 0,
    val startOnBoot: Boolean = true,
    val keepScreenOn: Boolean = true,
    val showStats: Boolean = false,
    /**
     * Only needed when snapserver has `[authorization] enabled`; both empty means no auth.
     * snapserver 0.34.0 forces authorization off, so this is for future servers.
     */
    val authUser: String = "",
    val authPassword: String = "",
    /** Look for new releases on GitHub (on start and daily) and offer to install them. */
    val updateCheck: Boolean = true,
    val updatePrerelease: Boolean = false,
    /** Decoder choice per codec, "flac=kotlin;opus=ffmpeg" (Settings → Decoders); unset = default. */
    val decoders: String = "",
    /** Desktop: which monitor to fill (-1 automatic, -2 all screens), and whether full screen. */
    val monitor: Int = -1,
    val fullscreen: Boolean = true,
    /** Desktop (Linux) only: "room" plays the house audio; "source" sends this computer's audio. */
    val desktopMode: String = "room",
    /** Source mode: snapserver's tcp input port, and the PipeWire output apps play into. */
    val sourcePort: Int = 4953,
    val sourceSink: String = "snapcast",
) {
    fun decoderFor(codec: String): String? =
        decoders.split(';').firstOrNull { it.startsWith("$codec=") }?.substringAfter('=')?.takeIf { it.isNotBlank() }

    fun withDecoder(codec: String, id: String): AppSettings =
        copy(decoders = (decoders.split(';').filter { it.isNotBlank() && !it.startsWith("$codec=") } + "$codec=$id").joinToString(";"))
}

/** Where each app keeps its settings: SharedPreferences on Android, java.util.prefs on desktop. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getLong(key: String, default: Long): Long
    fun putLong(key: String, value: Long)
}

class Prefs(private val store: KeyValueStore, newClientId: () -> String) {
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /** Stable per-install client id; snapserver keys name, volume and group on it. */
    val clientId: String = store.getString(KEY_CLIENT_ID) ?: newClientId().also { store.putString(KEY_CLIENT_ID, it) }

    var lastUpdateCheck: Long
        get() = store.getLong("lastUpdateCheck", 0)
        set(v) = store.putLong("lastUpdateCheck", v)

    /**
     * Streams to give back to groups after the room sync test ("group=stream;…"). Kept here
     * so a crash mid-test can't leave rooms on the click track.
     */
    var syncTestRestore: String?
        get() = store.getString("syncTestRestore")
        set(v) = store.putString("syncTestRestore", v)

    /** A version the user chose to skip; not offered again (a newer one still is). */
    var skippedVersion: String?
        get() = store.getString("skippedVersion")
        set(v) = store.putString("skippedVersion", v)

    @Synchronized
    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        store.putString("serverHost", next.serverHost)
        store.putInt("serverPort", next.serverPort)
        store.putBoolean("tlsTrustAll", next.tlsTrustAll)
        store.putInt("latencyMs", next.latencyMs)
        store.putInt("visualStyle", next.visualStyle)
        store.putBoolean("startOnBoot", next.startOnBoot)
        store.putBoolean("keepScreenOn", next.keepScreenOn)
        store.putBoolean("showStats", next.showStats)
        store.putString("authUser", next.authUser)
        // Plain text in app-private storage: snapcast sends it as base64 over unencrypted TCP
        // anyway, so it is a LAN password, not a secret worth an encryption dependency.
        store.putString("authPassword", next.authPassword)
        store.putBoolean("updateCheck", next.updateCheck)
        store.putBoolean("updatePrerelease", next.updatePrerelease)
        store.putString("decoders", next.decoders)
        store.putInt("monitor", next.monitor)
        store.putBoolean("fullscreen", next.fullscreen)
        store.putString("desktopMode", next.desktopMode)
        store.putInt("sourcePort", next.sourcePort)
        store.putString("sourceSink", next.sourceSink)
    }

    private fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            serverHost = store.getString("serverHost") ?: d.serverHost,
            serverPort = store.getInt("serverPort", d.serverPort),
            tlsTrustAll = store.getBoolean("tlsTrustAll", d.tlsTrustAll),
            latencyMs = store.getInt("latencyMs", d.latencyMs),
            visualStyle = store.getInt("visualStyle", d.visualStyle),
            startOnBoot = store.getBoolean("startOnBoot", d.startOnBoot),
            keepScreenOn = store.getBoolean("keepScreenOn", d.keepScreenOn),
            showStats = store.getBoolean("showStats", d.showStats),
            authUser = store.getString("authUser") ?: d.authUser,
            authPassword = store.getString("authPassword") ?: d.authPassword,
            updateCheck = store.getBoolean("updateCheck", d.updateCheck),
            updatePrerelease = store.getBoolean("updatePrerelease", d.updatePrerelease),
            decoders = store.getString("decoders") ?: d.decoders,
            monitor = store.getInt("monitor", d.monitor),
            fullscreen = store.getBoolean("fullscreen", d.fullscreen),
            desktopMode = store.getString("desktopMode") ?: d.desktopMode,
            sourcePort = store.getInt("sourcePort", d.sourcePort),
            sourceSink = store.getString("sourceSink") ?: d.sourceSink,
        )
    }

    private companion object {
        const val KEY_CLIENT_ID = "clientId"
    }
}
