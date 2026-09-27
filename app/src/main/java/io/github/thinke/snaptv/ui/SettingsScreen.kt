package io.github.thinke.snaptv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import io.github.thinke.snaptv.BuildConfig
import io.github.thinke.snaptv.Discovery
import io.github.thinke.snaptv.PlaybackService
import io.github.thinke.snaptv.Player
import io.github.thinke.snaptv.Prefs
import io.github.thinke.snaptv.app
import io.github.thinke.snaptv.core.transport.ServerAddress
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation

@Composable
fun SettingsScreen(player: Player, prefs: Prefs, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings by prefs.settings.collectAsStateWithLifecycle()
    val state by player.state.collectAsStateWithLifecycle()
    val delay by player.audioDelayMs.collectAsStateWithLifecycle()
    val servers by remember { Discovery(context).servers() }.collectAsState(emptyList())
    var picker by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var login by remember { mutableStateOf(false) }
    var delayScreen by remember { mutableStateOf(false) }
    var updates by remember { mutableStateOf(false) }

    if (updates) {
        UpdatesScreen(context.app.updater, prefs, onBack = { updates = false })
        return
    }

    if (delayScreen) {
        AudioDelayScreen(player, prefs, onBack = { delayScreen = false })
        return
    }

    if (login) {
        LoginEditor(
            user = settings.authUser,
            password = settings.authPassword,
            onSave = { u, p -> prefs.update { it.copy(authUser = u, authPassword = p) }; login = false },
            onBack = { login = false },
        )
        return
    }

    if (renaming) {
        NameEditor(
            current = state.room?.clientName.orEmpty(),
            onSave = { player.setName(it); renaming = false },
            onBack = { renaming = false },
        )
        return
    }
    if (picker) {
        ServerPicker(
            servers = servers.map { "${it.name} (${it.host})" to it },
            current = settings.serverHost,
            onAuto = { prefs.update { s -> s.copy(serverHost = "", serverPort = 1704) }; picker = false },
            onPick = { host, port -> prefs.update { s -> s.copy(serverHost = host, serverPort = port) }; picker = false },
            onBack = { picker = false },
        )
        return
    }
    BackHandler(onBack = onBack)
    val first = remember { FocusRequester() }

    Row(Modifier.fillMaxSize().background(Color(0xFF0D0F1A)).padding(horizontal = 56.dp, vertical = 36.dp)) {
        Column(Modifier.width(260.dp).padding(end = 32.dp)) {
            Text("Settings", style = MaterialTheme.typography.headlineLarge, color = Color.White)
            Text(
                "Changes apply immediately. Back returns to the visualizer.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
            item {
                val auto = settings.serverHost.isBlank()
                val found = servers.firstOrNull()
                ListItem(
                    selected = false,
                    onClick = { picker = true },
                    headlineContent = { Text("Server") },
                    supportingContent = {
                        Text(
                            if (auto) "Automatic" + (found?.let { " · found ${it.host}" } ?: " · searching…")
                            else if ("://" in settings.serverHost) settings.serverHost
                            else "${settings.serverHost}:${settings.serverPort}"
                        )
                    },
                    modifier = Modifier.focusRequester(first),
                )
            }
            item {
                val room = state.room
                ListItem(
                    selected = false,
                    enabled = room != null && room.streams.size > 1,
                    onClick = {
                        val r = room ?: return@ListItem
                        val i = r.streams.indexOfFirst { it.id == r.stream?.id }
                        player.setStream(r.streams[(i + 1) % r.streams.size].id)
                    },
                    headlineContent = { Text("Source") },
                    supportingContent = {
                        Text(
                            when {
                                room == null -> "Not available (no control connection)"
                                room.streams.size > 1 -> "Press OK to switch · applies to this TV's whole group"
                                else -> "The server has one source"
                            }
                        )
                    },
                    trailingContent = { Text(room?.stream?.id ?: "–") },
                )
            }
            item {
                ListItem(
                    selected = false,
                    enabled = state.room != null,
                    onClick = { renaming = true },
                    headlineContent = { Text("Name in Snapcast") },
                    supportingContent = { Text("How this TV appears in Snapweb and other controllers") },
                    trailingContent = { Text(state.room?.clientName ?: "–") },
                )
            }
            item {
                ListItem(
                    selected = false,
                    onClick = { login = true },
                    headlineContent = { Text("Server login") },
                    supportingContent = { Text("Only if your snapserver requires a username and password") },
                    trailingContent = { Text(settings.authUser.ifBlank { "none" }) },
                )
            }
            item {
                Toggle(
                    "Accept any TLS certificate",
                    "For wss:// servers with a self-signed certificate. Leave off otherwise.",
                    settings.tlsTrustAll,
                ) { prefs.update { s -> s.copy(tlsTrustAll = it) } }
            }
            item {
                ListItem(
                    selected = false,
                    onClick = { delayScreen = true },
                    headlineContent = { Text("Audio delay") },
                    supportingContent = { Text("Raise it if this TV sounds later than the other rooms. OK to measure or tune by ear; ◀ ▶ adjusts here in 10 ms steps.") },
                    trailingContent = { Text("${if (delay > 0) "+" else ""}$delay ms") },
                    modifier = Modifier.onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val step = when (e.key) {
                            Key.DirectionRight -> 10
                            Key.DirectionLeft -> -10
                            else -> return@onPreviewKeyEvent false
                        }
                        player.adjustAudioDelay(step)
                        true
                    },
                )
            }
            item {
                ListItem(
                    selected = false,
                    onClick = { prefs.update { it.copy(visualStyle = it.visualStyle + 1) } },
                    headlineContent = { Text("Visualizer") },
                    trailingContent = { Text(VisualStyle.of(settings.visualStyle).label) },
                )
            }
            item {
                Toggle("Start when the TV boots", "Play in the background like any other room speaker. Not possible on Android 15 and newer.", settings.startOnBoot) {
                    prefs.update { s -> s.copy(startOnBoot = it) }
                }
            }
            item {
                Toggle("Keep screen on while music plays", "The screensaver still starts when nothing is playing.", settings.keepScreenOn) {
                    prefs.update { s -> s.copy(keepScreenOn = it) }
                }
            }
            item {
                Toggle("Show sync statistics", "Clock offset, sync error and buffer levels on the visualizer screen.", settings.showStats) {
                    prefs.update { s -> s.copy(showStats = it) }
                }
            }
            item {
                ListItem(
                    selected = false,
                    onClick = { if (state.active) PlaybackService.stop(context) else PlaybackService.start(context) },
                    headlineContent = { Text(if (state.active) "Stop playback" else "Start playback") },
                )
            }
            item {
                ListItem(
                    selected = false,
                    onClick = { updates = true },
                    headlineContent = { Text("Updates") },
                    supportingContent = { Text("Check GitHub for a newer SnapTV") },
                )
            }
            item {
                ListItem(
                    selected = false,
                    onClick = {},
                    headlineContent = { Text("About") },
                    supportingContent = { Text("SnapTV ${BuildConfig.VERSION_NAME} · client id ${prefs.clientId}") },
                )
            }
        }
    }
    LaunchedEffect(Unit) { first.requestFocus() }
}

@Composable
private fun Toggle(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        selected = false,
        onClick = { onChange(!checked) },
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    )
}

@Composable
private fun <T : io.github.thinke.snaptv.DiscoveredServer> ServerPicker(
    servers: List<Pair<String, T>>,
    current: String,
    onAuto: () -> Unit,
    onPick: (String, Int) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var address by remember { mutableStateOf(current) }
    var error by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }

    Column(Modifier.fillMaxSize().background(Color(0xFF0D0F1A)).padding(horizontal = 56.dp, vertical = 36.dp)) {
        Text("Choose server", style = MaterialTheme.typography.headlineLarge, color = Color.White)
        Spacer16()
        ListItem(
            selected = current.isBlank(),
            onClick = onAuto,
            headlineContent = { Text("Automatic") },
            supportingContent = { Text("Use the first snapserver announced on the network") },
            modifier = Modifier.focusRequester(first),
        )
        servers.forEach { (label, server) ->
            ListItem(
                selected = current == server.host,
                onClick = { onPick(server.host, server.port) },
                headlineContent = { Text(label) },
                supportingContent = { Text("port ${server.port}") },
            )
        }
        Spacer16()
        Text("Or enter an address", color = Color.White.copy(alpha = 0.7f))
        // Field above the button, so ▼ from the server list lands in the field first.
        Field(null, address, { address = it.trim() }, onDone = { error = submit(address, onPick) }, keyboardType = KeyboardType.Uri)
        Button(onClick = { error = submit(address, onPick) }, modifier = Modifier.padding(top = 12.dp)) { Text("Connect") }
        Text(
            error ?: "host, host:port, or a URL such as ws://host:1780 or wss://host:1788",
            color = if (error != null) Color(0xFFFF8A80) else Color.White.copy(alpha = 0.5f),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    LaunchedEffect(Unit) { first.requestFocus() }
}

@Composable
private fun NameEditor(current: String, onSave: (String) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var name by remember { mutableStateOf(current) }
    val field = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().background(Color(0xFF0D0F1A)).padding(horizontal = 56.dp, vertical = 36.dp)) {
        Text("Name in Snapcast", style = MaterialTheme.typography.headlineLarge, color = Color.White)
        Field(null, name, { name = it }, Modifier.focusRequester(field), onDone = { onSave(name.trim()) })
        Button(onClick = { onSave(name.trim()) }, modifier = Modifier.padding(top = 12.dp)) { Text("Save") }
    }
    LaunchedEffect(Unit) { field.requestFocus() }
}

/** Accepts host, host:port or a tcp/ws/wss URL; returns an error message if it doesn't parse. */
private fun submit(address: String, onPick: (String, Int) -> Unit): String? {
    if (address.isBlank()) return null
    return try {
        val (host, port) = ServerAddress.toStored(address)
        onPick(host, port)
        null
    } catch (e: IllegalArgumentException) {
        e.message ?: "That is not a valid server address"
    }
}

@Composable
private fun LoginEditor(user: String, password: String, onSave: (String, String) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var u by remember { mutableStateOf(user) }
    var p by remember { mutableStateOf(password) }
    val first = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().background(Color(0xFF0D0F1A)).padding(horizontal = 56.dp, vertical = 36.dp)) {
        Text("Server login", style = MaterialTheme.typography.headlineLarge, color = Color.White)
        Text(
            "Leave both empty if the server has no users configured. The password is sent the way snapclient sends it: unencrypted unless you use wss://.",
            color = Color.White.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )
        Field("Username", u, { u = it }, Modifier.focusRequester(first))
        Field("Password", p, { p = it }, visual = PasswordVisualTransformation())
        Button(onClick = { onSave(u.trim(), p) }, modifier = Modifier.padding(top = 16.dp)) { Text("Save") }
    }
    LaunchedEffect(Unit) { first.requestFocus() }
}

@Composable
private fun Field(
    label: String?,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    visual: VisualTransformation = VisualTransformation.None,
    keyboardType: KeyboardType = KeyboardType.Text,
    onDone: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    if (label != null) Text(label, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(top = 8.dp))
    Box(
        Modifier
            .width(420.dp)
            .padding(top = 4.dp)
            .border(
                if (focused) 2.dp else 1.dp,
                if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.4f),
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 20.sp),
            cursorBrush = SolidColor(Color.White),
            visualTransformation = visual,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = if (onDone != null) ImeAction.Done else ImeAction.Next),
            keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
            modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        )
    }
}

@Composable
private fun Spacer16() = Box(Modifier.padding(top = 16.dp))
