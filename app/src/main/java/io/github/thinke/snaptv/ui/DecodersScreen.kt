package io.github.thinke.snaptv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.thinke.snaptv.DecoderCatalog
import io.github.thinke.snaptv.DecoderSelfTest
import io.github.thinke.snaptv.Prefs
import io.github.thinke.snaptv.SelfTestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings → Decoders: which decoder each codec uses, and a test of all of them on this device. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
fun DecodersScreen(prefs: Prefs, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val settings by prefs.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val options = remember { DecoderCatalog.codecs.associateWith { DecoderCatalog.options(it) } }
    var results by remember { mutableStateOf<List<SelfTestResult>?>(null) }
    var testing by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }

    Row(Modifier.fillMaxSize().background(Color(0xFF0D0F1A)).padding(horizontal = 56.dp, vertical = 36.dp)) {
        Column(Modifier.width(340.dp).padding(end = 32.dp)) {
            Text("Decoders", style = MaterialTheme.typography.headlineLarge, color = Color.White)
            Text(
                "Choose how each codec is decoded: SnapTV's own decoder, FFmpeg, or one of this device's decoders. OK cycles through them; playback reconnects with the new one.\n\n" +
                    "The test decodes sample files with every decoder and checks the result, so you can see which work on this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.65f),
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(DecoderCatalog.codecs, key = { it }) { codec ->
                val list = options[codec].orEmpty()
                val current = settings.decoderFor(codec) ?: DecoderCatalog.default(codec)
                ListItem(
                    selected = false,
                    enabled = list.size > 1,
                    onClick = {
                        val i = list.indexOfFirst { it.id == current }
                        val next = list[(i + 1).mod(list.size)]
                        prefs.update { it.withDecoder(codec, next.id) }
                    },
                    headlineContent = { Text(DecoderCatalog.title(codec)) },
                    supportingContent = {
                        Text((list.firstOrNull { it.id == current }?.label ?: current) + if (current == DecoderCatalog.default(codec)) " · default" else "")
                    },
                    trailingContent = { Text("${list.size} available") },
                    modifier = if (codec == DecoderCatalog.codecs.first()) Modifier.focusRequester(first) else Modifier,
                )
            }
            item {
                ListItem(
                    selected = false,
                    enabled = !testing,
                    onClick = {
                        testing = true
                        scope.launch {
                            results = withContext(Dispatchers.Default) { DecoderSelfTest.runAll(context) }.also { rs ->
                                rs.forEach { r ->
                                    android.util.Log.i("SnapTV.DecoderTest", "${if (r.ok) "OK  " else "FAIL"} ${r.codec} | ${r.option.label} | ${r.detail} | speed ${r.speed?.let { "%.0fx".format(it) }} | delay ${r.delayMs?.let { "%.1fms".format(it) }}")
                                }
                            }
                            testing = false
                        }
                    },
                    headlineContent = { Text(if (testing) "Testing…" else "Test decoders on this device") },
                    supportingContent = { Text("Decodes sample FLAC, Opus and Vorbis with every decoder") },
                )
            }
            item {
                ListItem(
                    selected = false,
                    onClick = { prefs.update { it.copy(decoders = "") } },
                    headlineContent = { Text("Reset to defaults") },
                )
            }
            results?.let { rs ->
                // Focusable rows, so the D-pad can scroll through all of them.
                items(rs, key = { "${it.codec}/${it.option.id}" }) { r ->
                    val speed = r.speed?.let { " · %.0f× real time".format(it) } ?: ""
                    val delay = r.delayMs?.let { " · delay %.0f ms".format(it) } ?: ""
                    ListItem(
                        selected = false,
                        onClick = {},
                        headlineContent = {
                            Text(
                                "${if (r.ok) "✓" else "✗"}  ${DecoderCatalog.title(r.codec)} · ${r.option.label}",
                                color = if (r.ok) Color.Unspecified else Color(0xFFFF8A80),
                            )
                        },
                        supportingContent = { Text("${r.detail}$speed$delay") },
                    )
                }
            }
        }
    }
    LaunchedEffect(Unit) { first.requestFocus() }
}
