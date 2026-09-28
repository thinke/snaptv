package io.github.thinke.snaptv.bench

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.thinke.snaptv.core.visual.DemoSong
import io.github.thinke.snaptv.core.visual.VisualBuffer
import io.github.thinke.snaptv.ui.GlesShaderEffects
import io.github.thinke.snaptv.ui.LocalShaderEffects
import io.github.thinke.snaptv.ui.visualStyleOf
import io.github.thinke.snaptv.ui.Visualizer

/**
 * Times one visualizer style on a real device, fed by [DemoSong] (no server, no sound):
 *
 *   adb shell am start -n io.github.thinke.snaptv.bench/io.github.thinke.snaptv.bench.VisualBenchActivity --ei style 3
 *   adb shell dumpsys gfxinfo io.github.thinke.snaptv.bench
 *
 * GPU styles draw on their own GL surface, which gfxinfo doesn't see; they log their frame
 * rate instead: adb logcat -s SnapTV.GL reset; sleep 15
 *   adb shell dumpsys gfxinfo io.github.thinke.snaptv.bench
 *
 * GPU styles draw on their own GL surface, which gfxinfo doesn't see; they log their frame
 * rate instead: adb logcat -s SnapTV.GL
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
            CompositionLocalProvider(LocalShaderEffects provides GlesShaderEffects(logFps = true)) {
                Box(Modifier.fillMaxSize().background(Color(0xFF05060C))) {
                    Visualizer(visual, visualStyleOf(style.intValue), Modifier.fillMaxSize())
                    // Like the app's overlays: GPU styles draw on a GL surface under Compose.
                    androidx.compose.foundation.text.BasicText(
                        "Benchmark · ${visualStyleOf(style.intValue).label}",
                        style = androidx.compose.ui.text.TextStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 20.sp),
                        modifier = Modifier.padding(24.dp),
                    )
                }
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
