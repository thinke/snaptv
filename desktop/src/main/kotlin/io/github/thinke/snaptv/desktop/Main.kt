package io.github.thinke.snaptv.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
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
import io.github.thinke.snaptv.core.ServerSettings
import io.github.thinke.snaptv.core.SnapEngine
import io.github.thinke.snaptv.core.SnapListener
import io.github.thinke.snaptv.core.codec.SampleFormat
import io.github.thinke.snaptv.core.control.ControlClient
import io.github.thinke.snaptv.core.control.RoomInfo
import io.github.thinke.snaptv.core.visual.VisualBuffer
import io.github.thinke.snaptv.ui.VisualStyle
import io.github.thinke.snaptv.ui.Visualizer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.awt.GraphicsEnvironment
import java.net.Inet4Address
import java.net.InetAddress
import java.util.prefs.Preferences
import javax.jmdns.JmDNS

data class DesktopState(
    val connection: ConnectionState = ConnectionState.Stopped,
    val format: SampleFormat? = null,
    val server: ServerSettings = ServerSettings(),
    val room: RoomInfo? = null,
)

private val prefs: Preferences = Preferences.userRoot().node("io/github/thinke/snaptv-desktop")

/**
 * SnapTV Desktop: a Snapcast room with SnapTV's visualizer, full screen on the monitor you
 * choose. ← → style · ↑ ↓ volume · M next monitor · F11 full screen · Esc leave full screen.
 *
 *   snaptv-desktop [--server host] [--monitor N] [--windowed]
 */
fun main(args: Array<String>) {
    fun opt(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    opt("--server")?.let { prefs.put("server", it) }
    opt("--monitor")?.toIntOrNull()?.let { prefs.putInt("monitor", it) }

    val host = InetAddress.getLocalHost().hostName.substringBefore('.')
    val state = MutableStateFlow(DesktopState())
    val visual = VisualBuffer()
    val engine = SnapEngine(
        ClientIdentity(
            id = "snaptv-desktop-$host",
            hostName = host,
            os = "${System.getProperty("os.name")} ${System.getProperty("os.version")}",
            arch = System.getProperty("os.arch"),
            clientName = "SnapTV Desktop",
        ),
        object : SnapListener {
            override fun onState(s: ConnectionState) = state.update { it.copy(connection = s) }
            override fun onFormat(format: SampleFormat, codec: String) = state.update { it.copy(format = format) }
            override fun onSettings(settings: ServerSettings) = state.update { it.copy(server = settings) }
        },
    ).also { it.tap = visual }
    val output = PulseOutput(engine, "SnapTV Desktop")
    val control = ControlClient("snaptv-desktop-$host") { room -> state.update { it.copy(room = room) } }

    Thread({
        val server = prefs.get("server", null)?.takeIf { it.isNotBlank() } ?: discover()
        if (server == null) {
            state.update { it.copy(connection = ConnectionState.Failed("", 0, "no snapserver found (use --server host)")) }
            return@Thread
        }
        engine.start(server, 1704)
        control.start(server)
        output.start()
    }, "snaptv-start").apply { isDaemon = true; start() }

    application {
        val s by state.collectAsState()
        // Perceptual volume, as on the TV.
        output.gain = if (s.server.muted) 0f else (s.server.volume / 100f).let { it * it }

        val screens = remember { GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration.bounds } }
        var monitor by remember { mutableIntStateOf(prefs.getInt("monitor", if (screens.size > 1) 1 else 0).coerceIn(0, screens.size - 1)) }
        var fullscreen by remember { mutableStateOf(if ("--windowed" in args) false else prefs.getBoolean("fullscreen", true)) }
        var style by remember { mutableIntStateOf(prefs.getInt("style", 0)) }
        var pokes by remember { mutableIntStateOf(0) }

        val b = screens[monitor]
        val window = rememberWindowState(size = DpSize(1280.dp, 720.dp))
        // Move to the chosen monitor first, then go full screen there.
        LaunchedEffect(monitor, fullscreen) {
            window.placement = WindowPlacement.Floating
            window.position = WindowPosition(b.x.dp + 40.dp, b.y.dp + 40.dp)
            delay(150)
            if (fullscreen) window.placement = WindowPlacement.Fullscreen
            prefs.putInt("monitor", monitor)
            prefs.putBoolean("fullscreen", fullscreen)
        }

        Window(
            onCloseRequest = { engine.stop(); control.stop(); output.stop(); exitApplication() },
            state = window,
            title = "SnapTV",
            onPreviewKeyEvent = { e ->
                if (e.type != KeyEventType.KeyDown) return@Window false
                when (e.key) {
                    Key.DirectionRight -> { style++; prefs.putInt("style", style) }
                    Key.DirectionLeft -> { style--; prefs.putInt("style", style) }
                    Key.DirectionUp, Key.DirectionDown -> {
                        // Applied at once and reported, so Snapweb shows it (as on the TV).
                        val v = (s.server.volume + if (e.key == Key.DirectionUp) 5 else -5).coerceIn(0, 100)
                        state.update { it.copy(server = it.server.copy(volume = v, muted = false)) }
                        Thread { runCatching { engine.sendClientInfo(v, false) } }.start()
                    }
                    Key.M -> monitor = (monitor + 1) % screens.size
                    Key.F11 -> fullscreen = !fullscreen
                    Key.Escape -> fullscreen = false
                    else -> return@Window false
                }
                pokes++
                true
            },
        ) {
            NowPlaying(s, visual, VisualStyle.of(style), pokes, monitor, screens.size, output.latencyMs)
        }
    }
}

@Composable
private fun NowPlaying(s: DesktopState, visual: VisualBuffer, style: VisualStyle, pokes: Int, monitor: Int, monitors: Int, latencyMs: Int) {
    var overlay by remember { mutableStateOf(true) }
    LaunchedEffect(pokes) {
        overlay = true
        delay(5000)
        overlay = false
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Visualizer(visual, style, Modifier.fillMaxSize())
        AnimatedVisibility(overlay, enter = fadeIn(), exit = fadeOut()) {
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
                    Hint("↑ ↓  volume ${if (s.server.muted) "muted" else "${s.server.volume}%"}")
                    Hint("M  monitor ${monitor + 1}/$monitors  ·  F11  full screen")
                    Hint("output ${latencyMs} ms")
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) = Text(text, color = Color.White.copy(alpha = 0.55f), fontSize = 14.sp)

private fun status(s: DesktopState): String = when (val c = s.connection) {
    ConnectionState.Stopped -> "Starting…"
    is ConnectionState.Connecting -> "Connecting to ${c.host}…"
    is ConnectionState.Failed -> if (c.host.isEmpty()) c.reason else "Can't reach ${c.host}: ${c.reason}. Retrying…"
    is ConnectionState.Connected -> "Connected" + (s.format?.let { " · ${it.rate / 1000.0} kHz" } ?: "")
}

/** First snapserver announced over mDNS, by its IPv4 address. */
private fun discover(): String? {
    val addresses = java.net.NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
    for (a in addresses) {
        val found = runCatching {
            JmDNS.create(a).use { dns -> dns.list("_snapcast._tcp.local.", 4000).firstNotNullOfOrNull { it.inet4Addresses.firstOrNull()?.hostAddress } }
        }.getOrNull()
        if (found != null) return found
    }
    return null
}
