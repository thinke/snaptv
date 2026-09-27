package io.github.thinke.snaptv

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import io.github.thinke.snaptv.core.update.Release
import io.github.thinke.snaptv.core.update.Releases
import io.github.thinke.snaptv.core.update.Version
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val latest: String?) : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val fraction: Float) : UpdateState
    /** Android's installer is showing its confirmation. */
    data class Installing(val release: Release) : UpdateState
    /** Android must first allow SnapTV to install apps (a one-time switch). */
    data class NeedsPermission(val release: Release) : UpdateState
    data class Failed(val message: String, val release: Release? = null) : UpdateState
}

/**
 * Checks GitHub releases for a newer SnapTV and installs it through Android's installer, after
 * checking the published SHA-256 and that the new APK is signed like the installed one.
 */
class Updater(private val context: Context, private val prefs: Prefs, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val installed: Version = Version.parse(BuildConfig.VERSION_NAME) ?: Version.parse("0")!!

    init {
        instance = this
        // A long-running TV should notice releases without being reopened.
        scope.launch {
            while (true) {
                checkIfDue()
                delay(6 * 60 * 60 * 1000L)
            }
        }
    }

    fun checkIfDue() {
        val s = prefs.settings.value
        if (!s.updateCheck) return
        if (System.currentTimeMillis() - prefs.lastUpdateCheck < CHECK_INTERVAL_MS) return
        scope.launch { check() }
    }

    suspend fun check() {
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Checking
        _state.value = withContext(Dispatchers.IO) {
            try {
                val releases = Releases.parse(get(Releases.API_URL))
                prefs.lastUpdateCheck = System.currentTimeMillis()
                val newest = Releases.newest(releases, installed, prefs.settings.value.updatePrerelease)
                if (newest != null) UpdateState.Available(newest)
                else UpdateState.UpToDate(releases.firstOrNull { !it.prerelease }?.version?.toString())
            } catch (e: Exception) {
                Log.w(TAG, "update check failed", e)
                UpdateState.Failed("Couldn't check for updates: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun install(release: Release) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission(release)
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val apk = download(release)
                verify(apk)
                _state.value = UpdateState.Installing(release)
                commit(apk)
            } catch (e: Exception) {
                Log.w(TAG, "update failed", e)
                _state.value = UpdateState.Failed(e.message ?: e.javaClass.simpleName, release)
            }
        }
    }

    /** Opens the "install unknown apps" switch for SnapTV (or the closest settings screen). */
    fun openInstallPermissionSettings() {
        val perApp = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        val fallback = Intent(Settings.ACTION_SECURITY_SETTINGS)
        for (i in listOf(perApp, fallback)) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (i.resolveActivity(context.packageManager) != null) {
                context.startActivity(i)
                return
            }
        }
    }

    fun skip(release: Release) {
        prefs.skippedVersion = release.version.toString()
    }

    fun isSkipped(release: Release) = prefs.skippedVersion == release.version.toString()

    private fun download(release: Release): File {
        val expected = Releases.parseSha256(get(release.sha256Url)) ?: throw IllegalStateException("The release has no valid checksum file")
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "snaptv-${release.version}.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        val conn = open(release.apkUrl)
        try {
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
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
            conn.disconnect()
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            file.delete()
            throw IllegalStateException("The download is corrupt (checksum mismatch). Try again.")
        }
        return file
    }

    /** Same package, same signing certificate, not a downgrade. */
    private fun verify(apk: File) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: throw IllegalStateException("The download is not a valid app")
        val current = pm.getPackageInfo(context.packageName, flags)
        if (archive.packageName != context.packageName) throw IllegalStateException("The download is a different app (${archive.packageName})")
        if (certs(archive) != certs(current)) throw IllegalStateException("The download is not signed by the same key as this SnapTV, so it was not installed")
        @Suppress("DEPRECATION")
        val archiveCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        @Suppress("DEPRECATION")
        val currentCode = if (Build.VERSION.SDK_INT >= 28) current.longVersionCode else current.versionCode.toLong()
        if (archiveCode < currentCode) throw IllegalStateException("The download is older than the installed version")
    }

    private fun certs(p: PackageInfo): Set<String> {
        @Suppress("DEPRECATION")
        val sigs = if (Build.VERSION.SDK_INT >= 28) p.signingInfo?.apkContentsSigners else p.signatures
        return sigs.orEmpty().map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    private fun commit(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("snaptv.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val intent = PendingIntent.getBroadcast(context, id, Intent(context, InstallResultReceiver::class.java), flags)
            session.commit(intent.intentSender)
        }
    }

    internal fun onInstallResult(status: Int, message: String?) {
        val release = (_state.value as? UpdateState.Installing)?.release
        _state.value = when (status) {
            PackageInstaller.STATUS_SUCCESS -> UpdateState.Idle // we are about to be restarted anyway
            PackageInstaller.STATUS_FAILURE_ABORTED -> release?.let { UpdateState.Available(it) } ?: UpdateState.Idle
            else -> UpdateState.Failed("Android didn't install the update: ${message ?: "error $status"}", release)
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            // GitHub's API rejects requests without a User-Agent.
            setRequestProperty("User-Agent", "SnapTV/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Accept", "application/vnd.github+json")
            instanceFollowRedirects = true
        }

    private fun get(url: String): String {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode} from GitHub")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    companion object {
        private const val TAG = "SnapTV.Update"
        private const val CHECK_INTERVAL_MS = 20 * 60 * 60 * 1000L
        @Volatile internal var instance: Updater? = null
    }
}

/** Receives PackageInstaller's result; when it needs the user's OK, shows Android's confirmation. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(confirm)
            return
        }
        Updater.instance?.onInstallResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
