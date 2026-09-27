package io.github.thinke.snaptv

import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class AppSettings(
    /**
     * Empty means "discover automatically". Otherwise a host name/IP (tcp to [serverPort]) or a
     * snapclient style URL such as ws://host:1780 or wss://host:1788, whose own or default port wins.
     */
    val serverHost: String = "",
    val serverPort: Int = 1704,
    /** wss only: accept any server certificate (snapclient's behaviour without --server-cert). */
    val tlsTrustAll: Boolean = false,
    /** Extra output delay of this TV/soundbar that we compensate for. */
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
) {
    fun decoderFor(codec: String): String? =
        decoders.split(';').firstOrNull { it.startsWith("$codec=") }?.substringAfter('=')?.takeIf { it.isNotBlank() }

    fun withDecoder(codec: String, id: String): AppSettings =
        copy(decoders = (decoders.split(';').filter { it.isNotBlank() && !it.startsWith("$codec=") } + "$codec=$id").joinToString(";"))
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("snaptv", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /** Stable per-install client id; snapserver keys name, volume and group on it. */
    val clientId: String = sp.getString(KEY_CLIENT_ID, null) ?: run {
        @Suppress("HardwareIds")
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        val id = "snaptv-" + (androidId ?: UUID.randomUUID().toString().take(16))
        sp.edit().putString(KEY_CLIENT_ID, id).apply()
        id
    }

    var lastUpdateCheck: Long
        get() = sp.getLong("lastUpdateCheck", 0)
        set(v) = sp.edit().putLong("lastUpdateCheck", v).apply()

    /**
     * Streams to give back to groups after the room sync test ("group=stream;…"). Kept here
     * so a crash mid-test can't leave rooms on the click track.
     */
    var syncTestRestore: String?
        get() = sp.getString("syncTestRestore", null)
        set(v) = sp.edit().putString("syncTestRestore", v).apply()

    /** A version the user chose to skip; not offered again (a newer one still is). */
    var skippedVersion: String?
        get() = sp.getString("skippedVersion", null)
        set(v) = sp.edit().putString("skippedVersion", v).apply()

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        sp.edit()
            .putString("serverHost", next.serverHost)
            .putInt("serverPort", next.serverPort)
            .putBoolean("tlsTrustAll", next.tlsTrustAll)
            .putInt("latencyMs", next.latencyMs)
            .putInt("visualStyle", next.visualStyle)
            .putBoolean("startOnBoot", next.startOnBoot)
            .putBoolean("keepScreenOn", next.keepScreenOn)
            .putBoolean("showStats", next.showStats)
            .putString("authUser", next.authUser)
            // Plain text in app-private prefs (allowBackup is off), so readable only with root.
            // EncryptedSharedPreferences would add a dependency for little gain: snapcast sends
            // it as base64 over unencrypted TCP anyway, so it is a LAN password, not a secret.
            .putString("authPassword", next.authPassword)
            .putBoolean("updateCheck", next.updateCheck)
            .putBoolean("updatePrerelease", next.updatePrerelease)
            .putString("decoders", next.decoders)
            .apply()
    }

    private fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            serverHost = sp.getString("serverHost", d.serverHost) ?: "",
            serverPort = sp.getInt("serverPort", d.serverPort),
            tlsTrustAll = sp.getBoolean("tlsTrustAll", d.tlsTrustAll),
            latencyMs = sp.getInt("latencyMs", d.latencyMs),
            visualStyle = sp.getInt("visualStyle", d.visualStyle),
            startOnBoot = sp.getBoolean("startOnBoot", d.startOnBoot),
            keepScreenOn = sp.getBoolean("keepScreenOn", d.keepScreenOn),
            showStats = sp.getBoolean("showStats", d.showStats),
            authUser = sp.getString("authUser", d.authUser) ?: "",
            authPassword = sp.getString("authPassword", d.authPassword) ?: "",
            updateCheck = sp.getBoolean("updateCheck", d.updateCheck),
            updatePrerelease = sp.getBoolean("updatePrerelease", d.updatePrerelease),
            decoders = sp.getString("decoders", d.decoders) ?: "",
        )
    }

    private companion object {
        const val KEY_CLIENT_ID = "clientId"
    }
}
