package io.github.thinke.snaptv

import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class AppSettings(
    /** Empty means "discover automatically". */
    val serverHost: String = "",
    val serverPort: Int = 1704,
    /** Extra output delay of this TV/soundbar that we compensate for. */
    val latencyMs: Int = 0,
    val visualStyle: Int = 0,
    val startOnBoot: Boolean = true,
    val keepScreenOn: Boolean = true,
    val showStats: Boolean = false,
)

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

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        sp.edit()
            .putString("serverHost", next.serverHost)
            .putInt("serverPort", next.serverPort)
            .putInt("latencyMs", next.latencyMs)
            .putInt("visualStyle", next.visualStyle)
            .putBoolean("startOnBoot", next.startOnBoot)
            .putBoolean("keepScreenOn", next.keepScreenOn)
            .putBoolean("showStats", next.showStats)
            .apply()
    }

    private fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            serverHost = sp.getString("serverHost", d.serverHost) ?: "",
            serverPort = sp.getInt("serverPort", d.serverPort),
            latencyMs = sp.getInt("latencyMs", d.latencyMs),
            visualStyle = sp.getInt("visualStyle", d.visualStyle),
            startOnBoot = sp.getBoolean("startOnBoot", d.startOnBoot),
            keepScreenOn = sp.getBoolean("keepScreenOn", d.keepScreenOn),
            showStats = sp.getBoolean("showStats", d.showStats),
        )
    }

    private companion object {
        const val KEY_CLIENT_ID = "clientId"
    }
}
