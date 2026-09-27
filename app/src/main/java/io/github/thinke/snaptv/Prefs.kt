package io.github.thinke.snaptv

import android.content.Context
import android.provider.Settings
import io.github.thinke.snaptv.core.session.KeyValueStore
import java.util.UUID

// The settings model and its logic live in core, shared with the desktop app.
typealias AppSettings = io.github.thinke.snaptv.core.session.AppSettings
typealias Prefs = io.github.thinke.snaptv.core.session.Prefs

fun Prefs(context: Context): Prefs {
    val sp = context.getSharedPreferences("snaptv", Context.MODE_PRIVATE)
    val store = object : KeyValueStore {
        override fun getString(key: String) = sp.getString(key, null)
        override fun putString(key: String, value: String?) = sp.edit().putString(key, value).apply()
        override fun getInt(key: String, default: Int) = sp.getInt(key, default)
        override fun putInt(key: String, value: Int) = sp.edit().putInt(key, value).apply()
        override fun getBoolean(key: String, default: Boolean) = sp.getBoolean(key, default)
        override fun putBoolean(key: String, value: Boolean) = sp.edit().putBoolean(key, value).apply()
        override fun getLong(key: String, default: Long) = sp.getLong(key, default)
        override fun putLong(key: String, value: Long) = sp.edit().putLong(key, value).apply()
    }
    return Prefs(store) {
        @Suppress("HardwareIds")
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        "snaptv-" + (androidId ?: UUID.randomUUID().toString().take(16))
    }
}
