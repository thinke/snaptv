@file:Suppress("FunctionName", "unused")

package io.github.thinke.snaptv.desktop

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.Tuple
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import java.io.File
import java.nio.file.Files

// The freedesktop/KDE tray protocol: https://www.freedesktop.org/wiki/Specifications/StatusNotifierItem/
// and the menu it points to (libdbusmenu's com.canonical.dbusmenu).

@JvmSuppressWildcards
@DBusInterfaceName("org.kde.StatusNotifierItem")
interface StatusNotifierItem : DBusInterface {
    fun Activate(x: Int, y: Int)
    fun SecondaryActivate(x: Int, y: Int)
    fun ContextMenu(x: Int, y: Int)
    fun Scroll(delta: Int, orientation: String)

    class NewToolTip(path: String) : DBusSignal(path)
}

@DBusInterfaceName("org.kde.StatusNotifierWatcher")
interface StatusNotifierWatcher : DBusInterface {
    fun RegisterStatusNotifierItem(service: String)
}

class Pixmap(
    @field:Position(0) @JvmField val width: Int,
    @field:Position(1) @JvmField val height: Int,
    @field:Position(2) @JvmField val data: ByteArray,
) : Struct()

class ToolTip(
    @field:Position(0) @JvmField val iconName: String,
    @field:Position(1) @JvmField val pixmaps: List<Pixmap>,
    @field:Position(2) @JvmField val title: String,
    @field:Position(3) @JvmField val text: String,
) : Struct()

class MenuLayout(
    @field:Position(0) @JvmField val id: Int,
    @field:Position(1) @JvmField val properties: Map<String, Variant<*>>,
    @field:Position(2) @JvmField val children: List<Variant<*>>,
) : Struct()

/** A two-value D-Bus reply. dbus-java reads the element types from the type arguments, so it must be generic. */
class Reply2<A, B>(
    @field:Position(0) @JvmField val first: A,
    @field:Position(1) @JvmField val second: B,
) : Tuple()

class ItemProperties(
    @field:Position(0) @JvmField val id: Int,
    @field:Position(1) @JvmField val properties: Map<String, Variant<*>>,
) : Struct()

class MenuEvent(
    @field:Position(0) @JvmField val id: Int,
    @field:Position(1) @JvmField val eventId: String,
    @field:Position(2) @JvmField val data: Variant<*>,
    @field:Position(3) @JvmField val timestamp: UInt32,
) : Struct()


// Kotlin would compile List<...> parameters as Java wildcards, which dbus-java can't map.
@JvmSuppressWildcards
@DBusInterfaceName("com.canonical.dbusmenu")
interface DBusMenu : DBusInterface {
    /** (revision, layout) */
    fun GetLayout(parentId: Int, recursionDepth: Int, propertyNames: List<String>): Reply2<UInt32, MenuLayout>
    fun GetGroupProperties(ids: List<Int>, propertyNames: List<String>): List<ItemProperties>
    fun GetProperty(id: Int, name: String): Variant<*>
    fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32)
    fun EventGroup(events: List<MenuEvent>): List<Int>
    fun AboutToShow(id: Int): Boolean
    /** (ids needing an update, ids not found) */
    fun AboutToShowGroup(ids: List<Int>): Reply2<List<Int>, List<Int>>

    class LayoutUpdated(path: String, val revision: UInt32, val parent: Int) : DBusSignal(path, revision, parent)
}

/**
 * SnapTV's tray icon in KDE's own protocol, so it is drawn like the icons around it: a
 * monochrome symbolic SVG that Plasma recolours for the panel, a tooltip, and a native menu that
 * opens on left and right click (Show/Hide window, Settings, Quit). [start] returns false where no tray host is running, and
 * the caller then falls back to Java's (XEmbed) tray.
 */
class StatusNotifier(
    private val onToggle: () -> Unit,
    private val onSettings: () -> Unit,
    private val onQuit: () -> Unit,
) {
    private var connection: DBusConnection? = null
    @Volatile private var windowVisible = true
    @Volatile private var status = "Starting…"
    @Volatile private var revision = 1L
    private val iconDir: File by lazy { installIcon() }

    private val item = object : StatusNotifierItem, Properties {
        override fun getObjectPath() = ITEM_PATH
        override fun Activate(x: Int, y: Int) = onToggle()
        override fun SecondaryActivate(x: Int, y: Int) = onToggle()
        override fun ContextMenu(x: Int, y: Int) = Unit // the host shows our dbusmenu
        override fun Scroll(delta: Int, orientation: String) = Unit

        @Suppress("UNCHECKED_CAST")
        override fun <A : Any?> Get(iface: String, name: String): A = itemProperties()[name] as A
        override fun <A : Any?> Set(iface: String, name: String, value: A) = Unit
        override fun GetAll(iface: String): Map<String, Variant<*>> = itemProperties()
    }

    private val menu = object : DBusMenu, Properties {
        override fun getObjectPath() = MENU_PATH

        override fun GetLayout(parentId: Int, recursionDepth: Int, propertyNames: List<String>) =
            Reply2(UInt32(revision), layout())

        override fun GetGroupProperties(ids: List<Int>, propertyNames: List<String>): List<ItemProperties> =
            entries().filter { ids.isEmpty() || it.first in ids }.map { ItemProperties(it.first, it.second) }

        override fun GetProperty(id: Int, name: String): Variant<*> = entries().first { it.first == id }.second[name] ?: Variant("")

        override fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32) {
            if (eventId != "clicked") return
            when (id) {
                ID_TOGGLE -> onToggle()
                ID_SETTINGS -> onSettings()
                ID_QUIT -> onQuit()
            }
        }

        override fun EventGroup(events: List<MenuEvent>): List<Int> {
            events.forEach { Event(it.id, it.eventId, it.data, it.timestamp) }
            return emptyList()
        }

        override fun AboutToShow(id: Int) = false
        override fun AboutToShowGroup(ids: List<Int>) = Reply2<List<Int>, List<Int>>(emptyList(), emptyList())

        @Suppress("UNCHECKED_CAST")
        override fun <A : Any?> Get(iface: String, name: String): A = menuProperties()[name] as A
        override fun <A : Any?> Set(iface: String, name: String, value: A) = Unit
        override fun GetAll(iface: String): Map<String, Variant<*>> = menuProperties()
    }

    fun start(): Boolean = try {
        val c = DBusConnectionBuilder.forSessionBus().build()
        val name = "org.kde.StatusNotifierItem-${ProcessHandle.current().pid()}-1"
        c.requestBusName(name)
        c.exportObject(ITEM_PATH, item)
        c.exportObject(MENU_PATH, menu)
        c.getRemoteObject("org.kde.StatusNotifierWatcher", "/StatusNotifierWatcher", StatusNotifierWatcher::class.java, true)
            .RegisterStatusNotifierItem(name)
        connection = c
        true
    } catch (e: Throwable) { // incl. a missing class in a trimmed runtime: fall back, don't crash
        System.err.println("SnapTV: no StatusNotifier tray (${e.message}); using the basic tray")
        if (System.getenv("SNAPTV_DEBUG") != null) e.printStackTrace()
        runCatching { connection?.close() }
        connection = null
        false
    }

    fun update(windowVisible: Boolean, status: String) {
        val c = connection ?: return
        if (windowVisible != this.windowVisible) {
            this.windowVisible = windowVisible
            revision++
            runCatching { c.sendMessage(DBusMenu.LayoutUpdated(MENU_PATH, UInt32(revision), 0)) }
        }
        if (status != this.status) {
            this.status = status
            runCatching { c.sendMessage(StatusNotifierItem.NewToolTip(ITEM_PATH)) }
        }
    }

    fun stop() {
        runCatching { connection?.close() }
        connection = null
    }

    private fun itemProperties(): Map<String, Variant<*>> = mapOf(
        "Category" to Variant("ApplicationStatus"),
        "Id" to Variant("snaptv-desktop"),
        "Title" to Variant("SnapTV"),
        "Status" to Variant("Active"),
        "WindowId" to Variant(0),
        "IconName" to Variant("snaptv-symbolic"),
        "IconThemePath" to Variant(iconDir.absolutePath),
        "IconPixmap" to Variant(whitePixmaps, "a(iiay)"),
        "OverlayIconName" to Variant(""),
        "AttentionIconName" to Variant(""),
        "ToolTip" to Variant(ToolTip("snaptv-symbolic", emptyList(), "SnapTV", status), "(sa(iiay)ss)"),
        // Left click opens the menu too, so quitting is always one click away there.
        "ItemIsMenu" to Variant(true),
        "Menu" to Variant(DBusPath(MENU_PATH)),
    )

    private fun menuProperties(): Map<String, Variant<*>> = mapOf(
        "Version" to Variant(UInt32(3)),
        "TextDirection" to Variant("ltr"),
        "Status" to Variant("normal"),
        "IconThemePath" to Variant(listOf<String>(), "as"),
    )

    private fun entries(): List<Pair<Int, Map<String, Variant<*>>>> = listOf(
        ID_TOGGLE to mapOf("label" to Variant(if (windowVisible) "Hide window" else "Show window")),
        ID_SETTINGS to mapOf("label" to Variant("Settings")),
        ID_SEPARATOR to mapOf("type" to Variant("separator")),
        ID_QUIT to mapOf("label" to Variant("Quit SnapTV"), "icon-name" to Variant("application-exit")),
    )

    private fun layout(): MenuLayout = MenuLayout(
        0,
        mapOf("children-display" to Variant("submenu")),
        entries().map { (id, props) -> Variant(MenuLayout(id, props, emptyList()), "(ia{sv}av)") },
    )

    /** Plasma finds IconName through IconThemePath, so the symbolic SVG goes into a tiny theme there. */
    private fun installIcon(): File {
        val base = File(System.getProperty("user.home"), ".cache/snaptv-desktop/icons")
        val dir = File(base, "hicolor/scalable/status").apply { mkdirs() }
        val svg = StatusNotifier::class.java.getResourceAsStream("/snaptv-symbolic.svg")!!.use { it.readBytes() }
        Files.write(File(dir, "snaptv-symbolic.svg").toPath(), svg)
        Files.write(File(base, "snaptv-symbolic.svg").toPath(), svg)
        return base
    }

    /** For hosts that ignore IconName: white bars as ARGB32 pixmaps. */
    private val whitePixmaps: List<Pixmap> by lazy {
        val img = javax.imageio.ImageIO.read(StatusNotifier::class.java.getResourceAsStream("/snaptv-tray-white.png"))
        listOf(22, 32, 48, 64).map { size ->
            val scaled = java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            scaled.createGraphics().apply {
                setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                drawImage(img, 0, 0, size, size, null)
                dispose()
            }
            val bytes = java.nio.ByteBuffer.allocate(size * size * 4) // ARGB32, network byte order
            for (y in 0 until size) for (x in 0 until size) bytes.putInt(scaled.getRGB(x, y))
            Pixmap(size, size, bytes.array())
        }
    }

    private companion object {
        const val ITEM_PATH = "/StatusNotifierItem"
        const val MENU_PATH = "/MenuBar"
        const val ID_TOGGLE = 1
        const val ID_SETTINGS = 2
        const val ID_SEPARATOR = 3
        const val ID_QUIT = 4
    }
}
