package io.github.thinke.snaptv.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import io.github.thinke.snaptv.Player
import io.github.thinke.snaptv.Prefs
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/**
 * Screensaver content. The clock and track text wander slowly around the screen so nothing
 * stays lit in one place (OLED burn-in); without music it is just a dim clock.
 */
@Composable
fun DreamScreen(player: Player, prefs: Prefs) {
    val state by player.state.collectAsStateWithLifecycle()
    val settings by prefs.settings.collectAsStateWithLifecycle()
    var clock by remember { mutableStateOf(timeNow()) }
    LaunchedEffect(Unit) {
        while (true) {
            clock = timeNow()
            delay(10_000)
        }
    }

    val drift = rememberInfiniteTransition(label = "drift")
    val dx by drift.animateFloat(0f, 1f, infiniteRepeatable(tween(97_000, easing = LinearEasing), RepeatMode.Reverse), label = "dx")
    val dy by drift.animateFloat(0f, 1f, infiniteRepeatable(tween(61_000, easing = LinearEasing), RepeatMode.Reverse), label = "dy")

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        if (state.audible) {
            Visualizer(player.visual, visualStyleOf(settings.visualStyle), Modifier.fillMaxSize().alpha(0.85f))
        }
        val track = state.room?.stream?.track
        Column(
            Modifier.offset(
                x = 48.dp + (maxWidth - 520.dp).coerceAtLeast(0.dp) * dx,
                y = 40.dp + (maxHeight - 220.dp).coerceAtLeast(0.dp) * dy,
            )
        ) {
            Text(clock, color = Color.White.copy(alpha = if (state.audible) 0.8f else 0.45f), fontSize = 56.sp)
            if (state.audible && track != null) {
                track.title?.let { Text(it, color = Color.White.copy(alpha = 0.85f), fontSize = 24.sp) }
                track.artist?.let { Text(it, color = Color.White.copy(alpha = 0.6f), fontSize = 18.sp) }
            }
        }
    }
}

private fun timeNow(): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
