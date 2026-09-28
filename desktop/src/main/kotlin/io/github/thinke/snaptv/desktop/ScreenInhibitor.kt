@file:Suppress("FunctionName")

package io.github.thinke.snaptv.desktop

import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.types.UInt32

/** freedesktop's screensaver API, which KDE, GNOME and others implement. */
@DBusInterfaceName("org.freedesktop.ScreenSaver")
interface FreedesktopScreenSaver : DBusInterface {
    fun Inhibit(applicationName: String, reason: String): UInt32
    fun UnInhibit(cookie: UInt32)
}

/**
 * Keeps the screensaver, automatic lock and screen blanking away while [set] is true, like
 * Android's keep-screen-on. The desktop drops the request by itself if we exit or crash, since
 * it is tied to our D-Bus connection.
 */
class ScreenInhibitor {
    private var connection: DBusConnection? = null
    private var cookie: UInt32? = null

    @Synchronized
    fun set(on: Boolean) {
        try {
            if (on && cookie == null) {
                val c = connection ?: DBusConnectionBuilder.forSessionBus().build().also { connection = it }
                cookie = saver(c).Inhibit("SnapTV", "Showing the music visualizer")
            } else if (!on && cookie != null) {
                connection?.let { saver(it).UnInhibit(cookie!!) }
                cookie = null
            }
        } catch (e: Throwable) { // no session bus or no screensaver service: nothing to keep away
            System.err.println("SnapTV: can't ${if (on) "hold off" else "release"} the screensaver (${e.message})")
            cookie = null
        }
    }

    private fun saver(c: DBusConnection) =
        c.getRemoteObject("org.freedesktop.ScreenSaver", "/org/freedesktop/ScreenSaver", FreedesktopScreenSaver::class.java)
}
