package io.github.thinke.snaptv.desktop

import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path

/**
 * One SnapTV Desktop per user: two would both send to snapserver, or play the room twice. The
 * running copy listens on a Unix socket in the runtime dir; a second launch passes its arguments
 * there (the running copy shows its window, or Settings for `--settings`) and exits.
 */
class SingleInstance {
    private val path: Path = Path.of(System.getenv("XDG_RUNTIME_DIR") ?: System.getProperty("java.io.tmpdir"), "snaptv-desktop.sock")
    private var server: ServerSocketChannel? = null

    /** Called with a second launch's arguments. */
    @Volatile var onLaunch: (List<String>) -> Unit = {}

    /** True if we are the only copy. False if another is running; it has been handed [args]. */
    fun claim(args: Array<String>): Boolean {
        repeat(2) {
            if (handOver(args)) return false
            // Nobody answered: a socket left by a copy that was killed, or none at all.
            runCatching { Files.deleteIfExists(path) }
            try {
                val s = ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(UnixDomainSocketAddress.of(path))
                server = s
                Thread({ serve(s) }, "single-instance").apply { isDaemon = true; start() }
                return true
            } catch (_: IOException) {
                // Another copy bound it just now; hand over to that one.
            }
        }
        return true // can't tell; better two copies than none
    }

    /** Frees the socket, so a copy started now (after an update) becomes the one. */
    fun release() {
        runCatching { server?.close() }
        server = null
        runCatching { Files.deleteIfExists(path) }
    }

    private fun handOver(args: Array<String>): Boolean = try {
        SocketChannel.open(UnixDomainSocketAddress.of(path)).use { c ->
            c.write(ByteBuffer.wrap((args.joinToString("\u0000") + "\n").toByteArray()))
        }
        true
    } catch (_: IOException) {
        false
    }

    private fun serve(s: ServerSocketChannel) {
        while (s.isOpen) {
            try {
                s.accept().use { c ->
                    val text = java.nio.channels.Channels.newInputStream(c).bufferedReader().readLine().orEmpty()
                    onLaunch(text.split('\u0000').filter { it.isNotEmpty() })
                }
            } catch (_: IOException) {
                if (!s.isOpen) return
            }
        }
    }
}
