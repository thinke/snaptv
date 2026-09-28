package io.github.thinke.snaptv.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Switch
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material.LocalContentColor
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.thinke.snaptv.core.session.SnapSession
import io.github.thinke.snaptv.core.transport.ServerAddress
import io.github.thinke.snaptv.ui.VisualStyle
import io.github.thinke.snaptv.ui.visualStyleOf

/**
 * The desktop's settings, the same as the TV's where they apply (see CLAUDE.md, feature parity):
 * all of them go through core's [SnapSession] and settings model.
 */
@Composable
fun SettingsPanel(session: SnapSession, updater: DesktopUpdater, monitors: Int, monitor: Int, onClose: () -> Unit) {
    val prefs = session.prefs
    val settings by prefs.settings.collectAsState()
    val state by session.state.collectAsState()
    val delay by session.audioDelayMs.collectAsState()
    val room = state.room

    // Text fields take their text, label and cursor colour from LocalContentColor, which is black
    // unless set: on this dark panel it has to be white.
    CompositionLocalProvider(LocalContentColor provides Color.White) {
    Column(
        Modifier.fillMaxSize().background(Color(0xFF0D0F1A)).verticalScroll(rememberScrollState()).padding(horizontal = 56.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Settings", style = MaterialTheme.typography.h4, color = Color.White)
            Spacer(Modifier.weight(1f))
            Button(onClick = onClose) { Text("Done (Esc)") }
        }

        Section("Mode") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((mode, label) in listOf("room" to "Play this room", "source" to "Send this computer's audio")) {
                    if (settings.desktopMode == mode) Button(onClick = {}) { Text(label) }
                    else OutlinedButton(onClick = { prefs.update { it.copy(desktopMode = mode) } }) { Text(label) }
                }
            }
            Text(
                if (settings.desktopMode == "source")
                    "Send: apps playing to the \"Snapcast (multiroom)\" output go to every room. SnapTV creates that output while it runs (or uses one that exists), and sends in exact real time so no delay builds up."
                else "Play: this computer is a room and plays the house audio.",
                color = Color.White.copy(alpha = 0.5f),
            )
            if (settings.desktopMode == "source") {
                var port by remember { mutableStateOf(settings.sourcePort.toString()) }
                var sink by remember { mutableStateOf(settings.sourceSink) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = port, onValueChange = { port = it.filter(Char::isDigit) }, singleLine = true, label = { Text("snapserver input port") }, modifier = Modifier.width(200.dp))
                    OutlinedTextField(value = sink, onValueChange = { sink = it.trim() }, singleLine = true, label = { Text("output name") }, modifier = Modifier.width(240.dp))
                    OutlinedButton(onClick = {
                        prefs.update { it.copy(sourcePort = port.toIntOrNull() ?: 4953, sourceSink = sink.ifBlank { "snapcast" }) }
                    }) { Text("Apply") }
                }
            }
        }

        Section("Server") {
            var address by remember { mutableStateOf(settings.serverHost) }
            var error by remember { mutableStateOf<String?>(null) }
            fun connect() {
                if (address.isBlank()) { prefs.update { it.copy(serverHost = "", serverPort = 1704) }; error = null; return }
                error = try {
                    val (h, p) = ServerAddress.toStored(address)
                    prefs.update { it.copy(serverHost = h, serverPort = p) }
                    null
                } catch (e: IllegalArgumentException) { e.message ?: "not a valid address" }
            }
            Text(
                if (settings.serverHost.isBlank()) "Automatic (mDNS)" + (session.serverHost?.let { " · found " + nameAndAddress(session.serverLabel(it), it) } ?: "")
                else settings.serverHost + if ("://" !in settings.serverHost) ":${settings.serverPort}" else "",
                color = Color.White.copy(alpha = 0.8f),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = address, onValueChange = { address = it.trim() }, singleLine = true,
                    label = { Text("host, host:port, ws:// or wss:// URL; empty = automatic") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { connect() }),
                    modifier = Modifier.width(520.dp),
                )
                Button(onClick = { connect() }) { Text("Connect") }
                OutlinedButton(onClick = { address = ""; connect() }) { Text("Automatic") }
            }
            error?.let { Text(it, color = Color(0xFFFF8A80)) }
            Toggle("Accept any TLS certificate (wss:// with a self-signed certificate)", settings.tlsTrustAll) { v -> prefs.update { it.copy(tlsTrustAll = v) } }
            var user by remember { mutableStateOf(settings.authUser) }
            var pass by remember { mutableStateOf(settings.authPassword) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = user, onValueChange = { user = it }, singleLine = true, label = { Text("Server login: user") })
                OutlinedTextField(value = pass, onValueChange = { pass = it }, singleLine = true, label = { Text("password") }, visualTransformation = PasswordVisualTransformation())
                OutlinedButton(onClick = { prefs.update { it.copy(authUser = user.trim(), authPassword = pass) } }) { Text("Save login") }
            }
            Text("Only if your snapserver requires a login.", color = Color.White.copy(alpha = 0.5f))
        }

        if (settings.desktopMode == "room") Section("This room") {
            var name by remember(room?.clientName) { mutableStateOf(room?.clientName.orEmpty()) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, enabled = room != null, label = { Text("Name in Snapcast") })
                OutlinedButton(onClick = { session.setName(name.trim()) }, enabled = room != null) { Text("Rename") }
            }
            val streams = room?.streams.orEmpty()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Source: ${room?.stream?.id ?: "–"}", color = Color.White)
                OutlinedButton(
                    enabled = streams.size > 1,
                    onClick = {
                        val r = room ?: return@OutlinedButton
                        val i = r.streams.indexOfFirst { it.id == r.stream?.id }
                        session.setStream(r.streams[(i + 1) % r.streams.size].id)
                    },
                ) { Text(if (streams.size > 1) "Next source (whole group)" else "Only one source") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Volume: ${SnapSession.volumeLabel(state.server)}", color = Color.White, modifier = Modifier.width(160.dp))
                for (step in listOf(-10, -5, 5, 10)) OutlinedButton(onClick = { session.changeVolume(step) }) { Text(if (step > 0) "+$step" else "$step") }
            }
        }

        if (settings.desktopMode == "room") Section("Audio delay") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${if (delay > 0) "+" else ""}$delay ms", color = MaterialTheme.colors.primary, style = MaterialTheme.typography.h5, modifier = Modifier.width(140.dp))
                for (step in listOf(-50, -10, 10, 50)) OutlinedButton(onClick = { session.adjustAudioDelay(step) }) { Text(if (step > 0) "+$step" else "$step") }
                OutlinedButton(onClick = { session.setAudioDelay(0) }) { Text("Reset") }
            }
            Text(
                "Delay after this computer's audio output (e.g. a receiver or Bluetooth speaker). Stored on the server as this room's latency, so Snapweb shows and changes the same value." +
                    if (room == null) " The control connection isn't available, so it is kept locally for now." else "",
                color = Color.White.copy(alpha = 0.5f),
            )
        }

        Section("Screen") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Visualizer: ${visualStyleOf(settings.visualStyle).label}", color = Color.White, modifier = Modifier.width(240.dp))
                OutlinedButton(onClick = { prefs.update { it.copy(visualStyle = it.visualStyle + 1) } }) { Text("Next style") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (monitor == MONITOR_ALL) "Monitor: all $monitors screens" else "Monitor: ${monitor + 1} of $monitors", color = Color.White, modifier = Modifier.width(240.dp))
                OutlinedButton(enabled = monitors > 1, onClick = { prefs.update { it.copy(monitor = nextMonitor(monitor, monitors)) } }) { Text("Next monitor") }
            }
            Toggle("Full screen", settings.fullscreen) { v -> prefs.update { it.copy(fullscreen = v) } }
            Toggle("Keep the screensaver and screen lock away while SnapTV is on screen", settings.keepScreenOn) { v -> prefs.update { it.copy(keepScreenOn = v) } }
            Toggle("Show sync statistics", settings.showStats) { v -> prefs.update { it.copy(showStats = v) } }
        }

        Section("Updates") {
            val update by updater.state.collectAsState()
            Text("Installed: SnapTV Desktop $VERSION · ${updateStatus(update)}", color = Color.White.copy(alpha = 0.8f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { updater.checkNow() }, enabled = update !is UpdateState.NotAppImage && update !is UpdateState.Checking && update !is UpdateState.Downloading) { Text("Check now") }
                val available = (update as? UpdateState.Available)?.release ?: (update as? UpdateState.Failed)?.release
                if (available != null) Button(onClick = { updater.install(available) }) { Text("Install ${available.version}") }
            }
            Toggle("Check for updates automatically (when opened and daily)", settings.updateCheck) { v -> prefs.update { it.copy(updateCheck = v) } }
            Toggle("Include pre-releases", settings.updatePrerelease) { v -> prefs.update { it.copy(updatePrerelease = v) } }
            Text("New versions come from github.com/thinke/snaptv releases and are checked against their published SHA-256 before replacing this AppImage.", color = Color.White.copy(alpha = 0.5f))
        }

        Section("About") {
            Text("SnapTV Desktop $VERSION · client id ${session.clientId}", color = Color.White.copy(alpha = 0.7f))
            Text(statsText(state), color = Color.White.copy(alpha = 0.5f))
        }
    }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Divider(color = Color.White.copy(alpha = 0.1f))
    Text(title, style = MaterialTheme.typography.h6, color = Color.White)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) { content() }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked, onCheckedChange = onChange)
        Text(label, color = Color.White, modifier = Modifier.padding(start = 8.dp))
    }
}

fun updateStatus(s: UpdateState): String = when (s) {
    UpdateState.Idle -> "not checked yet"
    UpdateState.Checking -> "checking GitHub…"
    is UpdateState.UpToDate -> "up to date"
    is UpdateState.Available -> "${s.release.version} is available"
    is UpdateState.Downloading -> "downloading ${s.release.version}: ${(s.fraction * 100).toInt()} %"
    is UpdateState.Failed -> s.message
    UpdateState.NotAppImage -> "updates work when run as the AppImage"
}

/** Shown over the visualizer when a new version is found. */
@Composable
fun UpdatePrompt(updater: DesktopUpdater, state: UpdateState, onClose: () -> Unit) {
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(620.dp).background(Color(0xFF151827), androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state) {
                is UpdateState.Available -> {
                    val r = state.release
                    Text("SnapTV Desktop ${r.version} is available", style = MaterialTheme.typography.h5, color = Color.White)
                    Text("You have $VERSION", color = Color.White.copy(alpha = 0.6f))
                    ChangeList(r)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { updater.install(r) }) { Text("Update and restart") }
                        OutlinedButton(onClick = onClose) { Text("Later") }
                        OutlinedButton(onClick = { updater.skip(r); onClose() }) { Text("Skip this version") }
                    }
                }
                is UpdateState.Downloading -> {
                    Text("Downloading SnapTV Desktop ${state.release.version}…", style = MaterialTheme.typography.h6, color = Color.White)
                    androidx.compose.material.LinearProgressIndicator(progress = state.fraction, modifier = Modifier.fillMaxWidth())
                }
                is UpdateState.Failed -> {
                    Text(state.message, color = Color(0xFFFF8A80))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        state.release?.let { r -> Button(onClick = { updater.install(r) }) { Text("Try again") } }
                        OutlinedButton(onClick = onClose) { Text("Close") }
                    }
                }
                else -> OutlinedButton(onClick = onClose) { Text("Close") }
            }
        }
    }
}


/** "steambox (fd3a::1)", or just the address while the name is unknown. */
internal fun nameAndAddress(label: String, host: String) = if (label == host) host else "$label ($host)"

/** What's new since the installed version, per release; scrolls with the mouse wheel when long. */
@Composable
private fun ChangeList(r: io.github.thinke.snaptv.core.update.Release) {
    if (r.changes.isEmpty()) {
        Text(r.name, color = Color.White.copy(alpha = 0.8f))
        return
    }
    Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        r.changes.forEach { c ->
            Text(c.version.toString(), color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
            c.lines.forEach { Text("• $it", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp) }
        }
    }
}
