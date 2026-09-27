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
            Text("Sync test through snapcast", style = MaterialTheme.typography.headlineLarge, color = Color.White)
            Text(
                "Sends a click every second through snapcast and shows on screen when snapcast says it should be heard, so the whole path is tested, soundbar included. Only this TV's group is switched; it gets its music back when you leave.",
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
                    Text(
                        if (others.any { it.connected }) "These rooms click too, so you can also compare them with the TV by ear."
                        else "Works with this TV alone: the screen shows when each click should be heard.",
                        color = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 12.dp),
                    )
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
    var frameNanos by remember { mutableLongStateOf(0L) }
    var frameDurationNanos by remember { mutableLongStateOf(16_666_667L) }
    // Heard times (local clock) of the last click reached and of the next one due.
    var lastClickUs by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }

    // Make sure the group gets its music back however this screen goes away.
    DisposableEffect(Unit) { onDispose { player.stopRoomSyncTest() } }
    LaunchedEffect(Unit) {
        var last = 0L
        var nextClickUs: Long? = null
        while (true) {
            withFrameNanos { t ->
                if (last != 0L) frameDurationNanos = (frameDurationNanos * 7 + (t - last)) / 8
                last = t
                frameNanos = t
                // What this frame shows reaches the screen about two frames from now.
                val shownUs = (t + 2 * frameDurationNanos) / 1000
                val next = nextClickUs ?: player.visual.onsetBetween(shownUs - 20_000, shownUs + LOOKAHEAD_US, CLICK_LEVEL)
                    ?.takeIf { it - lastClickUs > 500_000 }
                nextClickUs = next
                if (next != null && shownUs >= next) {
                    if (io.github.thinke.snaptv.BuildConfig.DEBUG) android.util.Log.d("SnapTV.RoomTest", "flash for click heard at $next us (delta ${next - lastClickUs})")
                    lastClickUs = next
                    nextClickUs = null
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
            val shownUs = (frameNanos + 2 * frameDurationNanos) / 1000
            val cx = size.width / 2
            val cy = size.height * 0.55f
            if (lastClickUs != 0L) {
                // Clicks come once a second on snapcast's timeline; sweep towards the next one.
                var phase = Math.floorMod(shownUs - lastClickUs, PERIOD_US)
                if (phase > PERIOD_US / 2) phase -= PERIOD_US
                val flash = max(0f, 1f - abs(phase) / FLASH_US.toFloat())
                if (flash > 0f) drawCircle(Color.White.copy(alpha = 0.9f * flash), radius = size.height * 0.16f, center = Offset(cx, cy))
                val x = cx + phase.toFloat() / PERIOD_US * size.width * 0.9f
                drawCircle(Color(0xFFC084FC), radius = 26f, center = Offset(x, cy))
                drawCircle(Color.White, radius = 26f, center = Offset(x, cy), style = Stroke(3f))
            }
            drawLine(Color.White.copy(alpha = 0.25f), Offset(size.width * 0.05f, cy), Offset(size.width * 0.95f, cy), strokeWidth = 3f)
            drawLine(Color(0xFF6EE7D8), Offset(cx, cy - 90f), Offset(cx, cy + 90f), strokeWidth = 6f)
        }
        Column(Modifier.align(Alignment.TopCenter).padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Sync test through snapcast", style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Text("${if (delayMs > 0) "+" else ""}$delayMs ms", color = MaterialTheme.colorScheme.primary, fontSize = 48.sp)
            Text(
                "The click travels the whole snapcast path. The flash marks when snapcast says it should be heard.\n" +
                    "Adjust until click and flash happen together: ◀ ▶ 10 ms · ▲ ▼ 50 ms · Back to finish\n" +
                    "Click after the flash: raise it. Before: lower it.",
                color = Color.White.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
            )
        }
        Text(
            error?.let { "Can't send the click track: $it" }
                ?: (if (lastClickUs == 0L) "Waiting for the first click…" else "Rooms grouped with this TV click too, so you can also compare them by ear."),
            color = if (error != null) Color(0xFFFF8A80) else Color.White.copy(alpha = 0.5f),
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(40.dp),
        )
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

private const val PERIOD_US = 1_000_000L
private const val FLASH_US = 90_000L
private const val LOOKAHEAD_US = 250_000L

/** The click track peaks around 0.6; music between clicks is muted, so anything this loud is a click. */
private const val CLICK_LEVEL = 0.2f
