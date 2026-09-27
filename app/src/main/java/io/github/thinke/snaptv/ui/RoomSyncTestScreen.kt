package io.github.thinke.snaptv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.thinke.snaptv.Player
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max

/**
 * Room sync test: a click track goes through snapcast to this TV and the rooms you pick, so
 * they all click together by snapcast's timing. Stand where you hear both and adjust the TV's
 * delay until the clicks merge.
 */
@Composable
fun RoomSyncTestScreen(player: Player, onBack: () -> Unit) {
    val state by player.state.collectAsStateWithLifecycle()
    var running by remember { mutableStateOf(false) }
    val room = state.room
    val members = room?.groups?.firstOrNull { it.id == room.groupId }?.clients.orEmpty()
    val others = members.filter { it.id != player.clientId }

    if (running) {
        RunningTest(player, onStop = { player.stopRoomSyncTest(); running = false; onBack() })
        return
    }
    BackHandler(onBack = onBack)
    val first = remember { FocusRequester() }
    Row(Modifier.fillMaxSize().background(Color(0xFF0D0F1A)).padding(horizontal = 56.dp, vertical = 36.dp)) {
        Column(Modifier.width(380.dp).padding(end = 32.dp)) {
            Text("Room sync test", style = MaterialTheme.typography.headlineLarge, color = Color.White)
            Text(
                "Plays a click every second through snapcast in this TV's group, so every room in the group clicks together exactly as snapcast times them. Only this group is switched; it gets its music back when you leave.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.65f),
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        Column {
            when {
                room == null -> Text("Needs the server's control connection, which isn't available right now.", color = Color(0xFFFF8A80))
                !player.roomSyncTestAvailable() -> {
                    Text("The server needs a SyncTest input first", style = MaterialTheme.typography.titleLarge, color = Color.White)
                    Text(
                        "Add this line under [stream] in snapserver.conf, next to your other source, and restart snapserver:\n\n" +
                            "source = tcp://0.0.0.0:${Player.SYNC_TEST_PORT}?name=${Player.SYNC_TEST_STREAM}&mode=server&sampleformat=48000:16:2",
                        color = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                else -> {
                    Text("In this TV's group", style = MaterialTheme.typography.titleLarge, color = Color.White)
                    Text(
                        members.joinToString("\n") { "• ${it.name}${if (!it.connected) " (offline)" else ""}" },
                        color = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    if (others.none { it.connected }) {
                        Text(
                            "To compare with another room, add it to this TV's group in Snapweb first. Alone, the test still lets you hear the TV's click.",
                            color = Color(0xFFFFD27F),
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    Button(
                        onClick = { player.startRoomSyncTest(); running = true },
                        modifier = Modifier.padding(top = 20.dp).focusRequester(first),
                    ) { Text("Start test") }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun RunningTest(player: Player, onStop: () -> Unit) {
    BackHandler(onBack = onStop)
    val delayMs by player.audioDelayMs.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    var flashAtNanos by remember { mutableLongStateOf(0L) }
    var frameNanos by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }

    // Make sure the rooms get their music back however this screen goes away.
    DisposableEffect(Unit) { onDispose { player.stopRoomSyncTest() } }
    LaunchedEffect(Unit) {
        // Longer than a frame, so a click never falls between two looks.
        val window = FloatArray(1200)
        while (true) {
            withFrameNanos { t ->
                frameNanos = t
                val ok = player.visual.window(System.nanoTime() / 1000, window)
                if (ok && window.maxOf { abs(it) } > CLICK_LEVEL && t - flashAtNanos > 500_000_000L) {
                    flashAtNanos = t
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            error = player.roomSyncTestError()
            delay(500)
        }
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
                    Key.DirectionRight -> player.adjustAudioDelay(10)
                    Key.DirectionLeft -> player.adjustAudioDelay(-10)
                    Key.DirectionUp -> player.adjustAudioDelay(50)
                    Key.DirectionDown -> player.adjustAudioDelay(-50)
                    else -> return@onKeyEvent false
                }
                true
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val since = (frameNanos - flashAtNanos) / 1_000_000f
            val a = if (flashAtNanos == 0L) 0f else max(0f, 1f - since / 250f)
            if (a > 0f) drawCircle(Color.White.copy(alpha = 0.9f * a), radius = size.height * 0.18f, center = center)
        }
        Column(Modifier.align(Alignment.TopCenter).padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Room sync test", style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Text("${if (delayMs > 0) "+" else ""}$delayMs ms", color = MaterialTheme.colorScheme.primary, fontSize = 48.sp)
            Text(
                "Stand where you hear this TV and another room. The flash marks this TV's click.\n" +
                    "Adjust until the clicks merge into one: ◀ ▶ 10 ms · ▲ ▼ 50 ms · Back to finish\n" +
                    "TV clicks after the other room: raise it. Before: lower it.",
                color = Color.White.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
            )
        }
        error?.let {
            Text(
                "Can't send the click track: $it",
                color = Color(0xFFFF8A80),
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(40.dp),
            )
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** The click track peaks around 0.6; music between clicks is muted, so anything this loud is a click. */
private const val CLICK_LEVEL = 0.2f
