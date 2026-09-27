package io.github.thinke.snaptv.desktop

import io.github.thinke.snaptv.core.session.KeyValueStore
import io.github.thinke.snaptv.core.session.Prefs
import java.net.InetAddress
import java.util.prefs.Preferences

/** SnapTV Desktop settings in the user's Java preferences (~/.java/.userPrefs). */
fun desktopPrefs(): Prefs {
    val node = Preferences.userRoot().node("io/github/thinke/snaptv-desktop")
    val store = object : KeyValueStore {
        override fun getString(key: String): String? = node.get(key, null)
        override fun putString(key: String, value: String?) = if (value == null) node.remove(key) else node.put(key, value)
        override fun getInt(key: String, default: Int) = node.getInt(key, default)
        override fun putInt(key: String, value: Int) = node.putInt(key, value)
        override fun getBoolean(key: String, default: Boolean) = node.getBoolean(key, default)
        override fun putBoolean(key: String, value: Boolean) = node.putBoolean(key, value)
        override fun getLong(key: String, default: Long) = node.getLong(key, default)
        override fun putLong(key: String, value: Long) = node.putLong(key, value)
    }
    return Prefs(store) { "snaptv-desktop-" + InetAddress.getLocalHost().hostName.substringBefore('.') }
}
