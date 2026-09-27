package io.github.thinke.snaptv.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import io.github.thinke.snaptv.PlaybackService
import io.github.thinke.snaptv.app
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Opening the app always (re)starts playback; the service outlives the activity.
        PlaybackService.start(this)

        val player = app.player
        val prefs = app.prefs
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF6EE7D8), secondary = Color(0xFFC084FC))) {
                var settingsOpen by rememberSaveable { mutableStateOf(false) }
                if (settingsOpen) SettingsScreen(player, prefs, onBack = { settingsOpen = false })
                else NowPlayingScreen(player, prefs, onOpenSettings = { settingsOpen = true })
            }
        }

        // Keep the screen on only while there is sound, so the screensaver (and OLED panels)
        // still get their rest when the house is quiet.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(player.state, prefs.settings) { s, p -> p.keepScreenOn && s.audible }
                    .distinctUntilChanged()
                    .collect { on ->
                        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
            }
        }
    }
}
