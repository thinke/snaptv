package io.github.thinke.snaptv.desktop

import io.github.thinke.snaptv.core.session.Prefs
import io.github.thinke.snaptv.core.update.Release
import io.github.thinke.snaptv.core.update.Releases
import io.github.thinke.snaptv.core.update.Version
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val latest: String?) : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val fraction: Float) : UpdateState
    data class Failed(val message: String, val release: Release? = null) : UpdateState
    /** Not an AppImage (e.g. run from Gradle), so there is no file to replace. */
    data object NotAppImage : UpdateState
}

/**
 * SnapTV Desktop's update check, like the TV's: GitHub releases are checked when the app opens
 * (at most every 10 minutes) and daily. Installing downloads the new AppImage next to the running
 * one, checks it against the release's SHA-256, replaces the file and restarts.
 */
class DesktopUpdater(
    private val prefs: Prefs,
    private val args: Array<String>,
    /** Quit, and call relaunch on the way out to start the updated copy. */
    private val onRestart: (relaunch: () -> Unit) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<UpdateState>(if (appImage == null) UpdateState.NotAppImage else UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val installed: Version = Version.parse(VERSION) ?: Version.parse("0")!!

    /** The AppImage file we run from, if any. */
    val appImage: File? get() = System.getenv("APPIMAGE")?.let(::File)?.takeIf { it.isFile }

    fun startChecking() {
        if (appImage == null || VERSION.endsWith("-dev")) return
        scope.launch {
            checkIfOlderThan(START_MIN_INTERVAL_MS)
            while (true) {
                delay(6 * 60 * 60 * 1000L)
                checkIfOlderThan(CHECK_INTERVAL_MS)
            }
        }
    }

    private suspend fun checkIfOlderThan(ms: Long) {
        if (!prefs.settings.value.updateCheck) return
        if (System.currentTimeMillis() - prefs.lastUpdateCheck < ms) return
        check()
    }

    fun checkNow() {
        scope.launch { check() }
    }

    private fun check() {
        if (appImage == null) { _state.value = UpdateState.NotAppImage; return }
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Checking
        _state.value = try {
            val releases = Releases.parse(get(Releases.API_URL))
            prefs.lastUpdateCheck = System.currentTimeMillis()
            val newest = Releases.newest(releases, installed, prefs.settings.value.updatePrerelease) { it.download(SUFFIX) != null }
            if (newest != null) UpdateState.Available(newest)
            else UpdateState.UpToDate(releases.firstOrNull { !it.prerelease }?.version?.toString())
        } catch (e: Exception) {
            UpdateState.Failed("Couldn't check for updates: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun install(release: Release) {
        val target = appImage ?: return
        scope.launch {
            try {
                val (file, sum) = release.download(SUFFIX) ?: error("the release has no AppImage")
                val expected = Releases.parseSha256(get(sum.url)) ?: error("the release has no valid checksum")
                val tmp = File(target.parentFile, ".${target.name}.download")
                val digest = MessageDigest.getInstance("SHA-256")
                open(file.url).let { c ->
                    try {
                        val total = c.contentLengthLong.takeIf { it > 0 } ?: file.size
                        c.inputStream.use { input ->
                            tmp.outputStream().use { out ->
                                val buf = ByteArray(256 * 1024)
                                var done = 0L
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    digest.update(buf, 0, n)
                                    done += n
                                    if (total > 0) _state.value = UpdateState.Downloading(release, (done.toFloat() / total).coerceAtMost(1f))
                                }
                            }
                        }
                    } finally {
                        c.disconnect()
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (actual != expected) {
                    tmp.delete()
                    error("the download is corrupt (checksum mismatch)")
                }
                tmp.setExecutable(true)
                // Same directory, so the replace is atomic; the running copy stays open until exit.
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                onRestart {
                    val pb = ProcessBuilder(listOf(target.absolutePath) + args.filter { it != "--windowed" }).inheritIO()
                    // Set by the Java launcher we run in. Inherited, it makes the new launcher skip
                    // its setup and read our options as JVM options ("Unrecognized option: --tray").
                    pb.environment().remove("_JPACKAGE_LAUNCHER")
                    pb.start()
                }
            } catch (e: Exception) {
                _state.value = UpdateState.Failed("Update failed: ${e.message ?: e.javaClass.simpleName}", release)
            }
        }
    }

    fun skip(release: Release) {
        prefs.skippedVersion = release.version.toString()
    }

    fun isSkipped(release: Release) = prefs.skippedVersion == release.version.toString()

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "SnapTV-Desktop/$VERSION")
            setRequestProperty("Accept", "application/vnd.github+json")
            instanceFollowRedirects = true
        }

    private fun get(url: String): String {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode} from GitHub")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private companion object {
        const val SUFFIX = "x86_64.AppImage"
        const val CHECK_INTERVAL_MS = 20 * 60 * 60 * 1000L
        const val START_MIN_INTERVAL_MS = 10 * 60 * 1000L
    }
}
