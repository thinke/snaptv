package io.github.thinke.snaptv

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class SnapTvApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var player: Player
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        player = Player(this, prefs)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(PlaybackService.CHANNEL_ID, getString(R.string.channel_playback), NotificationManager.IMPORTANCE_LOW)
        )
    }
}

val android.content.Context.app: SnapTvApp get() = applicationContext as SnapTvApp
