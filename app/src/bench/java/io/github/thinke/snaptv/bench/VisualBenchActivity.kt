package io.github.thinke.snaptv.bench

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.thinke.snaptv.core.visual.DemoSong
import io.github.thinke.snaptv.core.visual.VisualBuffer
import io.github.thinke.snaptv.ui.VisualStyle
import io.github.thinke.snaptv.ui.Visualizer

/**
 * Times one visualizer style on a real device, fed by [DemoSong] (no server, no sound):
 *
 *   adb shell am start -n io.github.thinke.snaptv.bench/io.github.thinke.snaptv.bench.VisualBenchActivity --ei style 3
 *   adb shell dumpsys gfxinfo io.github.thinke.snaptv.bench reset; sleep 15
 *   adb shell dumpsys gfxinfo io.github.thinke.snaptv.bench
 */
class VisualBenchActivity : ComponentActivity() {
    private val visual = VisualBuffer()
    private val style = mutableIntStateOf(0)
    private var stopSong: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No screensaver in the middle of a measurement.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        style.intValue = intent.getIntExtra("style", 0)
        setContent {
            Box(Modifier.fillMaxSize().background(Color(0xFF05060C))) {
                Visualizer(visual, VisualStyle.of(style.intValue), Modifier.fillMaxSize())
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        style.intValue = intent.getIntExtra("style", style.intValue)
    }

    override fun onStart() {
        super.onStart()
        stopSong = DemoSong(48000).play(visual)
    }

    override fun onStop() {
        stopSong?.invoke()
        stopSong = null
        super.onStop()
    }
}
