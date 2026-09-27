package io.github.thinke.snaptv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts playback at boot so the TV behaves like any other always-on snapclient.
 * Android 15 forbids starting media playback services from BOOT_COMPLETED; there the
 * attempt fails and playback begins the first time the app is opened instead.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!context.app.prefs.settings.value.startOnBoot) return
        try {
            PlaybackService.start(context)
        } catch (e: Exception) {
            Log.w("SnapTV.Boot", "cannot start playback from ${intent.action}: ${e.message}")
        }
    }
}
