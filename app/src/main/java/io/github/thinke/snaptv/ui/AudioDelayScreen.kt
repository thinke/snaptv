package io.github.thinke.snaptv.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.thinke.snaptv.CalibrationOutcome
import io.github.thinke.snaptv.Player
import io.github.thinke.snaptv.Prefs
import kotlinx.coroutines.launch

/**
 * Setting the delay the TV can't see (soundbar, TV processing): by ear while music plays, or
 * measured with the TV's microphone.
 */
@Composable
fun AudioDelayScreen(player: Player, prefs: Prefs, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var measuring by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    var syncTest by remember { mutableStateOf(false) }
    var roomTest by remember { mutableStateOf(false) }
    if (roomTest) {
        RoomSyncTestScreen(player, onBack = { roomTest = false })
        return
    }
    if (syncTest) {
        SyncTestScreen(player, prefs, onBack = { syncTest = false })
        return
    }

    fun measure() {
        if (measuring) return
        measuring = true
        status = "Starting…"
        scope.launch {
            val outcome = player.calibrator(context).run { status = it }
            status = when (outcome) {
                is CalibrationOutcome.Measured -> {
                    player.setAudioDelay(outcome.delayMs)
                    "Measured ${outcome.delayMs} ms (${outcome.heard} of ${outcome.total} test sounds, within ${"%.1f".format(outcome.spreadMs)} ms) and applied. Fine-tune by ear if needed."
                }
                is CalibrationOutcome.Failed -> outcome.reason
            }
            measuring = false
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) measure() else status = "Microphone permission was denied. You can still adjust by ear."
    }

    val delay by player.audioDelayMs.collectAsStateWithLifecycle()
    val room = player.state.collectAsStateWithLifecycle().value.room
    fun adjust(step: Int) = player.adjustAudioDelay(step)

    Row(Modifier.fillMaxSize().background(Color(0xFF0D0F1A)).padding(horizontal = 56.dp, vertical = 36.dp)) {
        Column(Modifier.width(340.dp).padding(end = 32.dp)) {
            Text("Audio delay", style = MaterialTheme.typography.headlineLarge, color = Color.White)
            Text(
                "${if (delay > 0) "+" else ""}$delay ms",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 56.sp,
                modifier = Modifier.padding(vertical = 16.dp),
            )
            Text(
                "Soundbars and TV sound processing delay the sound after the TV sends it. SnapTV plays that much earlier to stay in step with the other rooms.\n\n" +
                    "By ear: stand where you hear this TV and another room at the same time, and adjust until they sound like one. Raise the delay if the TV is behind.\n\n" +
                    (if (room != null) "Stored on the server as this TV's latency, so Snapweb shows and changes the same value." else "The server's control connection isn't available, so this is stored on the TV for now."),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.65f),
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { Stepper("Fine", "◀ ▶ changes by 10 ms", 10, ::adjust, Modifier.focusRequester(first)) }
            item { Stepper("Coarse", "◀ ▶ changes by 50 ms", 50, ::adjust) }
            item {
                ListItem(
                    selected = false,
                    onClick = { roomTest = true },
                    headlineContent = { Text("Sync test through snapcast") },
                    supportingContent = { Text("A click through the whole snapcast path and a flash when it should be heard; adjust until they coincide") },
                )
            }
            item {
                ListItem(
                    selected = false,
                    onClick = { syncTest = true },
                    headlineContent = { Text("Sync test with picture") },
                    supportingContent = { Text("A beep and a flash once a second; adjust until they happen together") },
                )
            }
            item {
                ListItem(
                    selected = false,
                    enabled = !measuring,
                    onClick = {
                        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        if (granted) measure() else permission.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    headlineContent = { Text(if (measuring) "Measuring…" else "Measure with the TV's microphone") },
                    supportingContent = {
                        Text(status ?: "Pause the music first: the other rooms keep playing and drown out the test. SnapTV then plays eight short test sounds and listens with the TV's built-in microphone.")
                    },
                )
            }
            item {
                ListItem(
                    selected = false,
                    onClick = { player.setAudioDelay(0); status = null },
                    headlineContent = { Text("Reset to 0 ms") },
                )
            }
        }
    }
    LaunchedEffect(Unit) { first.requestFocus() }
}

@Composable
private fun Stepper(title: String, hint: String, step: Int, adjust: (Int) -> Unit, modifier: Modifier = Modifier) {
    ListItem(
        selected = false,
        onClick = {},
        headlineContent = { Text(title) },
        supportingContent = { Text(hint) },
        trailingContent = { Text("◀  ▶") },
        modifier = modifier.onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key) {
                Key.DirectionRight -> { adjust(step); true }
                Key.DirectionLeft -> { adjust(-step); true }
                else -> false
            }
        },
    )
}

