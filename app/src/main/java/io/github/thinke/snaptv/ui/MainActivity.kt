package io.github.thinke.snaptv.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
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
import io.github.thinke.snaptv.UpdateState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.thinke.snaptv.app
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        val updater = app.updater
        // Back from the "install unknown apps" screen with the switch on: carry on installing.
        val pending = updater.state.value
        if (pending is UpdateState.NeedsPermission && packageManager.canRequestPackageInstalls()) updater.install(pending.release)
        updater.checkOnStart()
    }

    private val shaderEffects = GlesShaderEffects()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Opening the app always (re)starts playback; the service outlives the activity.
        PlaybackService.start(this)

        val player = app.player
        val prefs = app.prefs
        setContent {
            // GPU visualizer styles (OpenGL ES) next to the Canvas ones.
            CompositionLocalProvider(LocalShaderEffects provides shaderEffects) {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF6EE7D8), secondary = Color(0xFFC084FC))) {
                var settingsOpen by rememberSaveable { mutableStateOf(false) }
                if (settingsOpen) SettingsScreen(player, prefs, onBack = { settingsOpen = false })
                else NowPlayingScreen(player, prefs, onOpenSettings = { settingsOpen = true })

                // Offer a new release once per app start (unless skipped); Settings → Updates always works.
                val update by app.updater.state.collectAsStateWithLifecycle()
                var promptDismissed by rememberSaveable { mutableStateOf(false) }
                val offered = (update as? UpdateState.Available)?.release
                val busy = update is UpdateState.Downloading || update is UpdateState.Installing ||
                    update is UpdateState.NeedsPermission || (update is UpdateState.Failed && (update as UpdateState.Failed).release != null)
                if (!settingsOpen && !promptDismissed && ((offered != null && !app.updater.isSkipped(offered)) || busy)) {
                    UpdatePrompt(app.updater, onClose = { promptDismissed = true })
                }
            }
            }
        }

        // Keep the screen on while SnapTV is open, music or not (as on the desktop). The flag
        // only holds while this window is visible, so leaving the app lets the TV rest.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                prefs.settings.map { it.keepScreenOn }
                    .distinctUntilChanged()
                    .collect { on ->
                        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
            }
        }
    }
}
