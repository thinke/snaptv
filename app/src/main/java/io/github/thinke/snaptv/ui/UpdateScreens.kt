package io.github.thinke.snaptv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import io.github.thinke.snaptv.Prefs
import io.github.thinke.snaptv.UpdateState
import io.github.thinke.snaptv.Updater
import kotlinx.coroutines.launch

/** Shown over the visualizer when a new version is found. */
@Composable
fun UpdatePrompt(updater: Updater, onClose: () -> Unit) {
    val state by updater.state.collectAsStateWithLifecycle()
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(760.dp)
                .background(Color(0xFF151827), RoundedCornerShape(16.dp))
                .padding(36.dp)
        ) {
            UpdatePanel(state, updater, onClose)
        }
    }
}

/** Settings → Updates. */
@Composable
fun UpdatesScreen(updater: Updater, prefs: Prefs, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val state by updater.state.collectAsStateWithLifecycle()
    val settings by prefs.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    Row(Modifier.fillMaxSize().background(Color(0xFF0D0F1A)).padding(horizontal = 56.dp, vertical = 36.dp)) {
        Column(Modifier.width(340.dp).padding(end = 32.dp)) {
            Text("Updates", style = MaterialTheme.typography.headlineLarge, color = Color.White)
            Text("Installed: SnapTV ${updater.installed}", color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(top = 12.dp))
            Text(
                "New versions come from github.com/thinke/snaptv releases. Each download is checked against its published checksum and must be signed like this app; Android asks before installing.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                ListItem(
                    selected = false,
                    enabled = state !is UpdateState.Checking && state !is UpdateState.Downloading,
                    onClick = { scope.launch { updater.check() } },
                    headlineContent = { Text("Check now") },
                    supportingContent = { Text(statusText(state)) },
                    modifier = Modifier.focusRequester(first),
                )
            }
            val available = when (val s = state) {
                is UpdateState.Available -> s.release
                is UpdateState.Failed -> s.release
                is UpdateState.NeedsPermission -> s.release
                else -> null
            }
            if (available != null) {
                item {
                    ListItem(
                        selected = false,
                        onClick = { updater.install(available) },
                        headlineContent = { Text("Install SnapTV ${available.version}") },
                        supportingContent = { Text(available.notes.lines().firstOrNull { it.isNotBlank() }?.take(120) ?: available.name) },
                    )
                }
            }
            if (state is UpdateState.NeedsPermission) {
                item {
                    ListItem(
                        selected = false,
                        onClick = { updater.openInstallPermissionSettings() },
                        headlineContent = { Text("Allow SnapTV to install updates") },
                        supportingContent = { Text("Turn on the switch for SnapTV, then come back; the update continues by itself") },
                    )
                }
            }
            item {
                ListItem(
                    selected = false,
                    onClick = { prefs.update { it.copy(updateCheck = !it.updateCheck) } },
                    headlineContent = { Text("Check for updates automatically") },
                    supportingContent = { Text("Whenever SnapTV is opened, and once a day while it runs") },
                    trailingContent = { Switch(checked = settings.updateCheck, onCheckedChange = null) },
                )
            }
            item {
                ListItem(
                    selected = false,
                    onClick = { prefs.update { it.copy(updatePrerelease = !it.updatePrerelease) } },
                    headlineContent = { Text("Include pre-releases") },
                    supportingContent = { Text("Test versions before they are released") },
                    trailingContent = { Switch(checked = settings.updatePrerelease, onCheckedChange = null) },
                )
            }
        }
    }
    LaunchedEffect(Unit) { first.requestFocus() }
}

@Composable
private fun UpdatePanel(state: UpdateState, updater: Updater, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    when (state) {
        is UpdateState.Available -> {
            val r = state.release
            Text("SnapTV ${r.version} is available", style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Text("You have ${updater.installed}", color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            Text(r.notes.lines().filter { it.isNotBlank() }.take(8).joinToString("\n").ifBlank { r.name }, color = Color.White.copy(alpha = 0.8f))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 24.dp)) {
                Button(onClick = { updater.install(r) }, modifier = Modifier.focusRequester(first)) { Text("Update") }
                OutlinedButton(onClick = onClose) { Text("Later") }
                OutlinedButton(onClick = { updater.skip(r); onClose() }) { Text("Skip this version") }
            }
        }
        is UpdateState.Downloading -> {
            Text("Downloading SnapTV ${state.release.version}…", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Box(Modifier.fillMaxWidth().padding(top = 20.dp).background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(4.dp))) {
                Box(Modifier.fillMaxWidth(state.fraction).padding(vertical = 4.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)))
            }
            Text("${(state.fraction * 100).toInt()} %", color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(top = 8.dp))
        }
        is UpdateState.Installing -> {
            Text("Installing SnapTV ${state.release.version}", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Text("Confirm on Android's install screen. SnapTV restarts with the new version.", color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(top = 12.dp))
        }
        is UpdateState.NeedsPermission -> {
            Text("One more step", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Text(
                "Android needs your permission for SnapTV to install updates. Turn on the switch for SnapTV on the next screen, then press Back; the update continues by itself.",
                color = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.padding(top = 12.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 24.dp)) {
                Button(onClick = { updater.openInstallPermissionSettings() }, modifier = Modifier.focusRequester(first)) { Text("Open settings") }
                OutlinedButton(onClick = onClose) { Text("Later") }
            }
        }
        is UpdateState.Failed -> {
            Text("Update failed", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Text(state.message, color = Color(0xFFFF8A80), modifier = Modifier.padding(top = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 24.dp)) {
                state.release?.let { r -> Button(onClick = { updater.install(r) }, modifier = Modifier.focusRequester(first)) { Text("Try again") } }
                OutlinedButton(onClick = onClose, modifier = if (state.release == null) Modifier.focusRequester(first) else Modifier) { Text("Close") }
            }
        }
        else -> {
            Text(statusText(state), color = Color.White)
            OutlinedButton(onClick = onClose, modifier = Modifier.padding(top = 20.dp).focusRequester(first)) { Text("Close") }
        }
    }
    LaunchedEffect(state::class) { runCatching { first.requestFocus() } }
}

private fun statusText(s: UpdateState): String = when (s) {
    UpdateState.Idle -> "Not checked yet"
    UpdateState.Checking -> "Checking GitHub…"
    is UpdateState.UpToDate -> "SnapTV is up to date"
    is UpdateState.Available -> "SnapTV ${s.release.version} is available"
    is UpdateState.Downloading -> "Downloading ${s.release.version}: ${(s.fraction * 100).toInt()} %"
    is UpdateState.Installing -> "Waiting for Android's installer"
    is UpdateState.NeedsPermission -> "Needs permission to install apps"
    is UpdateState.Failed -> s.message
}
