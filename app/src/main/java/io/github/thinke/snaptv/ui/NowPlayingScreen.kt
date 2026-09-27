package io.github.thinke.snaptv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.thinke.snaptv.Player
import io.github.thinke.snaptv.PlayerState
import io.github.thinke.snaptv.Prefs
import io.github.thinke.snaptv.core.ConnectionState
import kotlinx.coroutines.delay

@Composable
fun NowPlayingScreen(player: Player, prefs: Prefs, onOpenSettings: () -> Unit) {
    val state by player.state.collectAsStateWithLifecycle()
    val settings by prefs.settings.collectAsStateWithLifecycle()
    val style = VisualStyle.of(settings.visualStyle)
    val focus = remember { FocusRequester() }

    // Any key press shows the overlay for a few seconds; it also stays up while nothing plays.
    var pokes by remember { mutableIntStateOf(0) }
    var overlay by remember { mutableStateOf(true) }
    LaunchedEffect(pokes) {
        overlay = true
        delay(5000)
        overlay = false
    }
    var volumeToast by remember { mutableIntStateOf(0) }
    var showVolume by remember { mutableStateOf(false) }
    LaunchedEffect(volumeToast) {
        if (volumeToast == 0) return@LaunchedEffect
        showVolume = true
        delay(1500)
        showVolume = false
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> prefs.update { it.copy(visualStyle = it.visualStyle - 1) }
                    Key.DirectionRight -> prefs.update { it.copy(visualStyle = it.visualStyle + 1) }
                    Key.DirectionUp -> { player.changeVolume(+5); volumeToast++ }
                    Key.DirectionDown -> { player.changeVolume(-5); volumeToast++ }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Menu -> onOpenSettings()
                    else -> return@onKeyEvent false
                }
                pokes++
                true
            }
    ) {
        Visualizer(player.visual, style, Modifier.fillMaxSize())

        AnimatedVisibility(overlay || !state.audible, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp)) {
                Column(Modifier.align(Alignment.TopStart)) {
                    val room = state.room
                    val track = room?.stream?.track
                    Text(
                        track?.title ?: room?.clientName?.takeIf { it.isNotBlank() } ?: "SnapTV",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                    )
                    val detail = when {
                        track != null -> listOfNotNull(track.artist, track.album).joinToString(" · ")
                        room != null -> listOfNotNull(room.groupName.takeIf { it.isNotBlank() }, room.stream?.id?.let { "source $it" }).joinToString(" · ")
                        else -> ""
                    }
                    if (detail.isNotBlank()) {
                        Text(detail, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.85f))
                    }
                    Text(statusLine(state), style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.6f))
                }
                Row(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Hint("◀ ▶  ${style.label}")
                    Hint("▲ ▼  volume ${Player.volumeLabel(state.server)}")
                    Hint("OK  settings")
                }
            }
        }

        AnimatedVisibility(showVolume, Modifier.align(Alignment.TopCenter).padding(top = 40.dp), enter = fadeIn(), exit = fadeOut()) {
            Text(
                "Volume ${Player.volumeLabel(state.server)}",
                color = Color.White,
                fontSize = 28.sp,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp)).padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }

        if (settings.showStats) {
            Text(
                statsText(state, settings.latencyMs),
                color = Color.White.copy(alpha = 0.8f),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(24.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                    .padding(12.dp),
            )
        }
    }

    LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.6f))
}

private fun statusLine(s: PlayerState): String {
    if (s.discovering) return "Looking for a snapserver on the network…"
    s.serverError?.let { return "Server error: $it" }
    return when (val c = s.connection) {
        ConnectionState.Stopped -> "Stopped"
        is ConnectionState.Connecting -> "Connecting to ${c.host}…"
        is ConnectionState.Failed -> if (c.host.isEmpty()) "${c.reason}. Still looking…" else "Can't reach ${c.host}: ${c.reason}. Retrying…"
        is ConnectionState.Connected -> {
            val fmt = s.format?.let { " · ${it.rate / 1000.0} kHz ${s.codec?.uppercase()}" } ?: ""
            when {
                s.audible -> "Playing from ${c.host}$fmt"
                s.sync?.playing == true -> "Connected to ${c.host} · waiting for sound"
                else -> "Connected to ${c.host} · buffering"
            }
        }
    }
}

private fun statsText(s: PlayerState, latencyMs: Int): String {
    val y = s.sync
    return buildString {
        appendLine("clock offset  %.1f s".format(s.clockOffsetUs / 1e6))
        appendLine("round trip    ${Player.ms(s.rttUs)} ms")
        appendLine("buffer        ${s.server.bufferMs} ms (+${s.server.latencyMs} server, +$latencyMs local)")
        appendLine("output queue  ${s.outputBufferMs} ms")
        if (y != null) {
            appendLine("sync error    ${Player.ms(y.medianErrorUs)} ms (last ${Player.ms(y.lastErrorUs)})")
            appendLine("correction    ${y.correctionPpm} ppm")
            appendLine("queued        ${y.queuedMs} ms")
            append("resyncs ${y.hardSyncs}  underruns ${y.underruns}")
        }
    }
}
