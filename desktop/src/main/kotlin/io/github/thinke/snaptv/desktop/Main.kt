package io.github.thinke.snaptv.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.darkColors
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.Tray
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.thinke.snaptv.core.ClientIdentity
import io.github.thinke.snaptv.core.ConnectionState
import io.github.thinke.snaptv.core.codec.DecoderFactory
import io.github.thinke.snaptv.core.session.PlayerState
import io.github.thinke.snaptv.core.session.SnapSession
import io.github.thinke.snaptv.core.transport.Scheme
import io.github.thinke.snaptv.core.transport.ServerAddress
import io.github.thinke.snaptv.ui.LocalShaderEffects
import io.github.thinke.snaptv.ui.VisualStyle
import io.github.thinke.snaptv.ui.visualStyleOf
import io.github.thinke.snaptv.ui.Visualizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.awt.GraphicsEnvironment
import java.net.Inet4Address
import java.net.InetAddress
import javax.jmdns.JmDNS

/**
 * SnapTV Desktop: a Snapcast room with SnapTV's visualizer, full screen on the monitor you
 * choose. The playback and control logic is core's [SnapSession], shared with the TV app.
 *
 *   snaptv-desktop [--server host] [--monitor N] [--windowed] [--tray] [--settings]
 *
 * Keys: ← → style · ↑ ↓ volume · S or Enter settings · M next monitor (then all screens) · F11
 * full screen · Esc back.
 * Closing the window only hides it: SnapTV keeps playing from the system tray, and quits from the
 * tray icon's menu (left or right click).
 */
fun main(args: Array<String>) {
    fun opt(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val instance = SingleInstance()
    if (!instance.claim(args)) {
        println("SnapTV Desktop is already running; showing its window.")
        return
    }
    val prefs = desktopPrefs()
    opt("--server")?.let { s -> prefs.update { it.copy(serverHost = s) } }
    opt("--monitor")?.toIntOrNull()?.let { m -> prefs.update { it.copy(monitor = m) } }
    if ("--windowed" in args) prefs.update { it.copy(fullscreen = false) }

    val host = InetAddress.getLocalHost().hostName.substringBefore('.')
    val session = SnapSession(
        identity = ClientIdentity(
            id = prefs.clientId,
            hostName = host,
            os = "${System.getProperty("os.name")} ${System.getProperty("os.version")}",
            arch = System.getProperty("os.arch"),
            clientName = "SnapTV Desktop",
            version = VERSION,
        ),
        prefs = prefs,
        decoders = DecoderFactory.Default,
        sink = { PulseOutput(it, "SnapTV Desktop") },
        discover = { discover() },
    )
    session.setDecoderLabel("SnapTV (built-in)")
    // Play (a room) or Send (this computer is the source); one at a time, switched in Settings.
    val source = SourceMode(session.visual)
    // Command-line overrides for this run only (not saved): --mode room|source --source-port N --sink NAME
    val modeOverride = opt("--mode")
    val portOverride = opt("--source-port")?.toIntOrNull()
    val sinkOverride = opt("--sink")
    // Bumped on every mode change, so a source still looking for its server gives up.
    val modeGen = java.util.concurrent.atomic.AtomicInteger()
    fun applyMode(mode: String) {
        val gen = modeGen.incrementAndGet()
        if (mode == "source") {
            session.stop()
            Thread({
                val s = prefs.settings.value
                var host: String? = null
                while (host == null && gen == modeGen.get()) {
                    host = s.serverHost.takeIf { it.isNotBlank() }?.let { runCatching { ServerAddress.fromStored(it, s.serverPort).host }.getOrNull() } ?: discover()?.host
                    if (host == null) Thread.sleep(DISCOVERY_RETRY_MS)
                }
                if (host != null && gen == modeGen.get()) source.start(host, portOverride ?: s.sourcePort, sinkOverride ?: s.sourceSink)
            }, "source-start").apply { isDaemon = true; start() }
        } else {
            source.stop()
            session.start()
        }
    }
    applyMode(modeOverride ?: prefs.settings.value.desktopMode)
    lateinit var restart: (relaunch: () -> Unit) -> Unit
    val updater = DesktopUpdater(prefs, args) { relaunch -> restart(relaunch) }
    updater.startChecking()

    // GPU visualizer styles (Skia runtime shaders).
    val shaderEffects = SkiaShaderEffects()
    val inhibitor = ScreenInhibitor()

    application {
        val state by session.state.collectAsState()
        val settings by prefs.settings.collectAsState()
        val screens = remember { GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration.bounds } }
        // The chosen monitor, or MONITOR_ALL: the same picture full screen on every monitor.
        val monitor = effectiveMonitor(settings.monitor, screens.size)
        val targets = if (monitor == MONITOR_ALL) screens.indices.toList() else listOf(monitor)
        var settingsOpen by remember { mutableStateOf("--settings" in args) }
        var pokes by remember { mutableIntStateOf(0) }
        // Shown unless started with --tray; closing hides to the tray and keeps playing.
        var visible by remember { mutableStateOf("--tray" !in args || "--settings" in args) }
        val icon = remember { BitmapPainter(useResource("snaptv-icon.png") { loadImageBitmap(it) }) }
        // Java's Linux tray (XEmbed) has no transparency, so the tray icon is opaque edge to edge.
        val trayIcon = remember { BitmapPainter(useResource("snaptv-tray.png") { loadImageBitmap(it) }) }
        lateinit var notifierRef: StatusNotifier
        val quit = { notifierRef.stop(); source.stop(); session.stop(); exitApplication() }
        val sourceState by source.state.collectAsState()
        // Re-apply when the mode changes, or the source port/output while sending.
        val modeKey = Triple(settings.desktopMode, settings.sourcePort, settings.sourceSink)
        var applied by remember { mutableStateOf(modeKey) }
        LaunchedEffect(modeKey) {
            if (modeKey != applied) { applied = modeKey; applyMode(modeKey.first) }
        }
        val sending = (modeOverride ?: settings.desktopMode) == "source"
        // After an update: stop, give up the single-instance socket, then start the new copy.
        restart = { relaunch -> java.awt.EventQueue.invokeLater { notifierRef.stop(); source.stop(); session.stop(); instance.release(); relaunch(); exitApplication() } }
        val update by updater.state.collectAsState()
        var updateDismissed by remember { mutableStateOf(false) }

        // KDE's own tray protocol where available (transparent symbolic icon, native menu);
        // Java's XEmbed tray otherwise.
        val notifier = remember {
            StatusNotifier(
                onToggle = { java.awt.EventQueue.invokeLater { visible = !visible } },
                onSettings = { java.awt.EventQueue.invokeLater { visible = true; settingsOpen = true } },
                onQuit = { java.awt.EventQueue.invokeLater { quit() } },
            )
        }
        notifierRef = notifier
        // Started again from the menu (or a terminal): show this copy instead.
        instance.onLaunch = { a -> java.awt.EventQueue.invokeLater { visible = true; if ("--settings" in a) settingsOpen = true } }
        val nativeTray = remember { notifier.start() }
        val statusLine = if (sending) sourceStatus(sourceState) else status(state)
        LaunchedEffect(visible, statusLine) { notifier.update(visible, statusLine) }
        // Keep the screensaver and screen lock away while SnapTV has a window (minimized too),
        // music or not; hidden to the tray, let go.
        val holdScreen = visible && settings.keepScreenOn
        LaunchedEffect(holdScreen) { withContext(Dispatchers.IO) { inhibitor.set(holdScreen) } }
        // After hiding, give the freed window memory back instead of waiting for the next GC.
        LaunchedEffect(visible) { if (!visible) { delay(2000); releaseMemory() } }
        if (!nativeTray) {
            Tray(
                icon = trayIcon,
                tooltip = "SnapTV · $statusLine",
                onAction = { visible = !visible },
                menu = {
                    Item(if (visible) "Hide window" else "Show window", onClick = { visible = !visible })
                    Item("Settings", onClick = { visible = true; settingsOpen = true })
                    Separator()
                    Item("Quit SnapTV", onClick = quit)
                },
            )
        }

        // Hidden to the tray means gone: disposing a window frees its Skia surfaces and GPU
        // context (most of the idle memory). One window per target monitor; settings and the
        // update prompt open on the first, the visualizer shows on all.
        if (visible) for (screen in targets) key(screen) {
            val primary = screen == targets.first()
            val windowState = rememberWindowState(size = DpSize(1280.dp, 720.dp))
            // Move to its monitor first, then go full screen there.
            LaunchedEffect(screen, settings.fullscreen) {
                val b = screens[screen]
                windowState.placement = WindowPlacement.Floating
                windowState.position = WindowPosition(b.x.dp + 40.dp, b.y.dp + 40.dp)
                delay(150)
                if (settings.fullscreen) windowState.placement = WindowPlacement.Fullscreen
            }
            Window(
                onCloseRequest = { visible = false },
                state = windowState,
                title = "SnapTV",
                icon = icon,
                onPreviewKeyEvent = { e ->
                    if (e.type != KeyEventType.KeyDown) return@Window false
                    if (settingsOpen) {
                        if (e.key == Key.Escape) { settingsOpen = false; true } else false
                    } else {
                        when (e.key) {
                            Key.DirectionRight -> prefs.update { it.copy(visualStyle = it.visualStyle + 1) }
                            Key.DirectionLeft -> prefs.update { it.copy(visualStyle = it.visualStyle - 1) }
                            Key.DirectionUp -> session.changeVolume(5)
                            Key.DirectionDown -> session.changeVolume(-5)
                            Key.S, Key.Enter -> settingsOpen = true
                            // From the setting as it is now: this handler outlives changes of it.
                            Key.M -> prefs.update { it.copy(monitor = nextMonitor(effectiveMonitor(it.monitor, screens.size), screens.size)) }
                            Key.F11 -> prefs.update { it.copy(fullscreen = !it.fullscreen) }
                            Key.Escape -> prefs.update { it.copy(fullscreen = false) }
                            else -> return@Window false
                        }
                        pokes++
                        true
                    }
                },
            ) {
                // When windows come and go (M: one screen, the other, all), the one that had the
                // keyboard may have closed: the first takes focus, or keys would go nowhere.
                LaunchedEffect(targets) {
                    if (primary) {
                        delay(300) // after the window is placed
                        window.toFront()
                        window.requestFocus()
                    }
                }
                MaterialTheme(colors = darkColors(primary = Color(0xFF6EE7D8), secondary = Color(0xFFC084FC))) {
                  CompositionLocalProvider(LocalShaderEffects provides shaderEffects) {
                    if (settingsOpen && primary) {
                        SettingsPanel(session, updater, screens.size, monitor, onClose = { settingsOpen = false })
                    } else {
                        Box {
                            NowPlaying(session, state, visualStyleOf(settings.visualStyle), settings.showStats, pokes, monitor, screens.size, if (sending) sourceState else null) { settingsOpen = true }
                            val offered = (update as? UpdateState.Available)?.release
                            val busy = update is UpdateState.Downloading || (update is UpdateState.Failed && (update as UpdateState.Failed).release != null)
                            if (primary && !updateDismissed && ((offered != null && !updater.isSkipped(offered)) || busy)) {
                                UpdatePrompt(updater, update, onClose = { updateDismissed = true })
                            }
                        }
                    }
                  }
                }
            }
        }
    }
}

/** Saved monitor value for "all screens": the same visualizer full screen on each. */
internal const val MONITOR_ALL = -2

/** The monitor a saved value means: -1 is automatic (the second screen if there is one). */
internal fun effectiveMonitor(saved: Int, count: Int): Int =
    if (saved == MONITOR_ALL && count > 1) MONITOR_ALL
    else (if (saved < 0) (if (count > 1) 1 else 0) else saved).coerceIn(0, count - 1)

/** M and Settings → Next monitor: 1, 2, …, then all screens (with more than one), then 1 again. */
internal fun nextMonitor(current: Int, count: Int): Int = when {
    current == MONITOR_ALL -> 0
    current + 1 < count -> current + 1
    count > 1 -> MONITOR_ALL
    else -> 0
}

/** "monitor 2/2", or "all screens". */
internal fun monitorLabel(monitor: Int, count: Int) = if (monitor == MONITOR_ALL) "all $count screens" else "monitor ${monitor + 1}/$count"

@Composable
private fun NowPlaying(session: SnapSession, s: PlayerState, style: VisualStyle, stats: Boolean, pokes: Int, monitor: Int, monitors: Int, sending: SourceState?, openSettings: () -> Unit) {
    var overlay by remember { mutableStateOf(true) }
    LaunchedEffect(pokes) {
        overlay = true
        delay(5000)
        overlay = false
    }
    Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClick = openSettings)) {
        Visualizer(session.visual, style, Modifier.fillMaxSize())
        AnimatedVisibility(overlay || (sending == null && !s.audible), enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp)) {
                Column(Modifier.align(Alignment.TopStart)) {
                    if (sending != null) {
                        Text("Snapcast source", color = Color.White, fontSize = 34.sp)
                        Text("Apps playing to \"Snapcast (multiroom)\" go to every room", color = Color.White.copy(alpha = 0.85f), fontSize = 18.sp)
                        Text(sourceStatus(sending), color = Color.White.copy(alpha = 0.6f), fontSize = 16.sp)
                    } else {
                        val track = s.room?.stream?.track
                        Text(track?.title ?: s.room?.clientName?.takeIf { it.isNotBlank() } ?: "SnapTV Desktop", color = Color.White, fontSize = 34.sp)
                        val detail = track?.let { listOfNotNull(it.artist, it.album).joinToString(" · ") } ?: s.room?.stream?.id?.let { "source $it" }
                        detail?.let { Text(it, color = Color.White.copy(alpha = 0.85f), fontSize = 18.sp) }
                        Text(status(s), color = Color.White.copy(alpha = 0.6f), fontSize = 16.sp)
                    }
                }
                Row(Modifier.align(Alignment.BottomStart).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Hint("← →  ${style.label}")
                    Hint("↑ ↓  volume ${SnapSession.volumeLabel(s.server)}")
                    Hint("M  ${monitorLabel(monitor, monitors)}  ·  F11  full screen")
                    Hint("S / click  settings")
                }
            }
        }
        if (stats) {
            Text(
                if (sending != null) sourceStatsText(sending) else statsText(s),
                color = Color.White.copy(alpha = 0.8f),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(24.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp)).padding(12.dp),
            )
        }
    }
}

@Composable
private fun Hint(text: String) = Text(text, color = Color.White.copy(alpha = 0.55f), fontSize = 14.sp)

private fun status(s: PlayerState): String {
    if (s.discovering) return "Looking for a snapserver on the network…"
    s.serverError?.let { return "Server error: $it" }
    return when (val c = s.connection) {
        ConnectionState.Stopped -> "Starting…"
        is ConnectionState.Connecting -> "Connecting to ${s.serverLabel(c.host)}…"
        is ConnectionState.Failed -> if (c.host.isEmpty()) "${c.reason}. Still looking…" else "Can't reach ${s.serverLabel(c.host)}: ${c.reason}. Retrying…"
        is ConnectionState.Connected -> {
            val fmt = s.format?.let { " · ${it.rate / 1000.0} kHz ${s.codec?.uppercase()}" } ?: ""
            if (s.audible) "Playing$fmt" else "Connected to ${s.serverLabel(c.host)} · waiting for sound"
        }
    }
}

fun statsText(s: PlayerState): String = buildString {
    appendLine("clock offset  %.1f s".format(s.clockOffsetUs / 1e6))
    appendLine("round trip    ${SnapSession.ms(s.rttUs)} ms")
    appendLine("buffer        ${s.server.bufferMs} ms (+${s.server.latencyMs} delay)")
    appendLine("output queue  ${s.outputBufferMs} ms")
    s.decoder?.let { appendLine("decoder       $it") }
    s.sync?.let { y ->
        appendLine("sync error    ${SnapSession.ms(y.medianErrorUs)} ms (last ${SnapSession.ms(y.lastErrorUs)})")
        appendLine("correction    ${y.correctionPpm} ppm")
        appendLine("queued        ${y.queuedMs} ms")
        append("resyncs ${y.hardSyncs}  underruns ${y.underruns}")
    }
}

/**
 * Hands memory freed by the closed window back to the system: a GC for the Java heap, then
 * glibc's malloc_trim for native memory (Skia, AWT), which glibc otherwise keeps for reuse.
 */
private fun releaseMemory() {
    System.gc()
    runCatching { com.sun.jna.NativeLibrary.getInstance("c").getFunction("malloc_trim").invokeInt(arrayOf(0)) }
}

fun sourceStatus(s: SourceState): String = s.error?.let { "${s.status} ($it)" } ?: s.status

fun sourceStatsText(s: SourceState): String = buildString {
    appendLine("output        ${s.sinkName} (\"Snapcast (multiroom)\")")
    appendLine("buffered      ${s.bufferedMs} ms")
    appendLine("sent          ${s.sentSeconds} s")
    append("drift fixes   ${s.dropped} dropped, ${s.padded} padded")
}

/** First snapserver announced over mDNS, by its IPv4 address. */
private const val DISCOVERY_RETRY_MS = 10_000L

/** One mDNS search: each IPv4 interface is asked for `_snapcast._tcp` for up to 4 s. */
internal fun discover(): ServerAddress? {
    val addresses = java.net.NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
    for (a in addresses) {
        val found = runCatching {
            JmDNS.create(a).use { dns ->
                dns.list("_snapcast._tcp.local.", 4000).firstNotNullOfOrNull { i -> i.inet4Addresses.firstOrNull()?.let { it.hostAddress to i.port } }
            }
        }.getOrNull()
        if (found != null) return ServerAddress(Scheme.TCP, found.first, found.second)
    }
    return null
}
