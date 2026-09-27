package io.github.thinke.snaptv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.thinke.snaptv.Player
import io.github.thinke.snaptv.Prefs
import kotlin.math.abs
import kotlin.math.max

/**
 * Manual sync test: a dot sweeps across the screen and crosses the centre line, where the
 * screen flashes, exactly on each beat; a beep is scheduled to be heard on the same beat.
 * Adjust until beep and flash coincide.
 */
@Composable
fun SyncTestScreen(player: Player, prefs: Prefs, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val focus = remember { FocusRequester() }
    var frameNanos by remember { mutableLongStateOf(0L) }
    var frameDurationNanos by remember { mutableLongStateOf(16_666_667L) }

    DisposableEffect(Unit) {
        player.setSyncTestTone(true)
        onDispose { player.setSyncTestTone(false) }
    }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { t ->
                if (last != 0L) frameDurationNanos = (frameDurationNanos * 7 + (t - last)) / 8
                last = t
                frameNanos = t
            }
        }
    }

    val delay by player.audioDelayMs.collectAsStateWithLifecycle()
    fun adjust(step: Int) = player.adjustAudioDelay(step)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionRight -> adjust(10)
                    Key.DirectionLeft -> adjust(-10)
                    Key.DirectionUp -> adjust(50)
                    Key.DirectionDown -> adjust(-50)
                    else -> return@onKeyEvent false
                }
                true
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // A frame drawn now reaches the screen about two frames later.
            val shownUs = (frameNanos + 2 * frameDurationNanos) / 1000
            val period = Player.SYNC_TEST_PERIOD_US
            // Signed distance to the nearest beat, -period/2 .. period/2.
            var phase = Math.floorMod(shownUs, period)
            if (phase > period / 2) phase -= period
            val cx = size.width / 2
            val cy = size.height * 0.55f
            val flash = max(0f, 1f - abs(phase) / FLASH_US.toFloat())

            if (flash > 0f) drawCircle(Color.White.copy(alpha = 0.9f * flash), radius = size.height * 0.16f, center = Offset(cx, cy))
            drawLine(Color.White.copy(alpha = 0.25f), Offset(size.width * 0.05f, cy), Offset(size.width * 0.95f, cy), strokeWidth = 3f)
            drawLine(Color(0xFF6EE7D8), Offset(cx, cy - 90f), Offset(cx, cy + 90f), strokeWidth = 6f)
            for (tick in -4..4) {
                val x = cx + tick * size.width * 0.1f
                drawLine(Color.White.copy(alpha = 0.35f), Offset(x, cy - 16f), Offset(x, cy + 16f), strokeWidth = 2f)
            }
            val x = cx + phase.toFloat() / period * size.width * 0.9f
            drawCircle(Color(0xFFC084FC), radius = 26f, center = Offset(x, cy))
            drawCircle(Color.White, radius = 26f, center = Offset(x, cy), style = Stroke(3f))
        }
        Column(Modifier.align(Alignment.TopCenter).padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Sync test", style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Text(
                "${if (delay > 0) "+" else ""}$delay ms",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 48.sp,
            )
            Text(
                "Adjust until the beep and the flash happen together.\n◀ ▶ 10 ms · ▲ ▼ 50 ms · Back when done\nBeep before the flash: lower it. Beep after the flash: raise it.",
                color = Color.White.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
            )
        }
        Text(
            "This lines up the sound with this TV's picture. TVs delay the picture a little too; for the most accurate result turn on the TV's game mode during the test.",
            color = Color.White.copy(alpha = 0.5f),
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 120.dp, vertical = 40.dp),
        )
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

private const val FLASH_US = 90_000L
