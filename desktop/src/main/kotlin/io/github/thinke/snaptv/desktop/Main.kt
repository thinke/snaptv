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
import io.github.thinke.snaptv.ui.VisualStyle
import io.github.thinke.snaptv.ui.Visualizer
import kotlinx.coroutines.delay
import java.awt.GraphicsEnvironment
import java.net.Inet4Address
import java.net.InetAddress
import javax.jmdns.JmDNS

const val VERSION = "0.1.0"

/**
 * SnapTV Desktop: a Snapcast room with SnapTV's visualizer, full screen on the monitor you
 * choose. The playback and control logic is core's [SnapSession], shared with the TV app.
 *
 *   snaptv-desktop [--server host] [--monitor N] [--windowed]
 *
 * Keys: ← → style · ↑ ↓ volume · S or Enter settings · M next monitor · F11 full screen · Esc back
 */
fun main(args: Array<String>) {
    fun opt(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
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
    session.start()

    application {
        val state by session.state.collectAsState()
        val settings by prefs.settings.collectAsState()
        val screens = remember { GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration.bounds } }
        val monitor = (if (settings.monitor < 0) (if (screens.size > 1) 1 else 0) else settings.monitor).coerceIn(0, screens.size - 1)
        var settingsOpen by remember { mutableStateOf(false) }
        var pokes by remember { mutableIntStateOf(0) }

        val window = rememberWindowState(size = DpSize(1280.dp, 720.dp))
        // Move to the chosen monitor first, then go full screen there.
        LaunchedEffect(monitor, settings.fullscreen) {
            val b = screens[monitor]
            window.placement = WindowPlacement.Floating
            window.position = WindowPosition(b.x.dp + 40.dp, b.y.dp + 40.dp)
            delay(150)
            if (settings.fullscreen) window.placement = WindowPlacement.Fullscreen
        }

        Window(
            onCloseRequest = { session.stop(); exitApplication() },
            state = window,
            title = "SnapTV",
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
                        Key.M -> prefs.update { it.copy(monitor = (monitor + 1) % screens.size) }
                        Key.F11 -> prefs.update { it.copy(fullscreen = !it.fullscreen) }
                        Key.Escape -> prefs.update { it.copy(fullscreen = false) }
                        else -> return@Window false
                    }
                    pokes++
                    true
                }
            },
        ) {
            MaterialTheme(colors = darkColors(primary = Color(0xFF6EE7D8), secondary = Color(0xFFC084FC))) {
                if (settingsOpen) {
                    SettingsPanel(session, screens.size, monitor, onClose = { settingsOpen = false })
                } else {
                    NowPlaying(session, state, VisualStyle.of(settings.visualStyle), settings.showStats, pokes, monitor, screens.size) { settingsOpen = true }
                }
            }
        }
    }
}

@Composable
private fun NowPlaying(session: SnapSession, s: PlayerState, style: VisualStyle, stats: Boolean, pokes: Int, monitor: Int, monitors: Int, openSettings: () -> Unit) {
    var overlay by remember { mutableStateOf(true) }
    LaunchedEffect(pokes) {
        overlay = true
        delay(5000)
        overlay = false
    }
    Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClick = openSettings)) {
        Visualizer(session.visual, style, Modifier.fillMaxSize())
        AnimatedVisibility(overlay || !s.audible, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp)) {
                Column(Modifier.align(Alignment.TopStart)) {
                    val track = s.room?.stream?.track
                    Text(track?.title ?: s.room?.clientName?.takeIf { it.isNotBlank() } ?: "SnapTV Desktop", color = Color.White, fontSize = 34.sp)
                    val detail = track?.let { listOfNotNull(it.artist, it.album).joinToString(" · ") } ?: s.room?.stream?.id?.let { "source $it" }
                    detail?.let { Text(it, color = Color.White.copy(alpha = 0.85f), fontSize = 18.sp) }
                    Text(status(s), color = Color.White.copy(alpha = 0.6f), fontSize = 16.sp)
                }
                Row(Modifier.align(Alignment.BottomStart).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Hint("← →  ${style.label}")
                    Hint("↑ ↓  volume ${SnapSession.volumeLabel(s.server)}")
                    Hint("M  monitor ${monitor + 1}/$monitors  ·  F11  full screen")
                    Hint("S / click  settings")
                }
            }
        }
        if (stats) {
            Text(
                statsText(s),
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
        is ConnectionState.Connecting -> "Connecting to ${c.host}…"
        is ConnectionState.Failed -> if (c.host.isEmpty()) "${c.reason}. Still looking…" else "Can't reach ${c.host}: ${c.reason}. Retrying…"
        is ConnectionState.Connected -> {
            val fmt = s.format?.let { " · ${it.rate / 1000.0} kHz ${s.codec?.uppercase()}" } ?: ""
            if (s.audible) "Playing$fmt" else "Connected · waiting for sound"
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

/** First snapserver announced over mDNS, by its IPv4 address. */
private fun discover(): ServerAddress? {
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
