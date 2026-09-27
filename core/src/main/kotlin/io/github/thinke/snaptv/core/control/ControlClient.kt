package io.github.thinke.snaptv.core.control

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

data class TrackInfo(val title: String?, val artist: String?, val album: String?)

data class StreamInfo(val id: String, val status: String, val track: TrackInfo?)

/** Where this client sits in the server's world: its name, its group and what that group plays. */
data class RoomInfo(
    val clientName: String,
    val groupId: String,
    val groupName: String,
    val stream: StreamInfo?,
    /** Every stream on the server, for choosing a source. */
    val streams: List<StreamInfo>,
)

/**
 * Snapserver JSON-RPC control connection (newline-delimited JSON over TCP, port 1705).
 *
 * We keep it simple: fetch Server.GetStatus, and refetch whenever a notification arrives.
 * Notifications are rare (name, volume, stream changes, track metadata), so this costs
 * nothing and avoids mirroring the server's state machine.
 */
class ControlClient(
    private val clientId: String,
    private val onRoom: (RoomInfo?) -> Unit,
) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var socket: Socket? = null
    private var out: OutputStream? = null
    private val writeLock = Any()
    private var nextId = 1
    private var statusRequestId = -1

    fun start(host: String, port: Int = 1705) {
        stop()
        running = true
        thread = Thread({ loop(host, port) }, "snap-control").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        thread?.let { if (it !== Thread.currentThread()) it.join(2000) }
        thread = null
    }

    fun setStream(groupId: String, streamId: String) = request("Group.SetStream") {
        put("id", groupId)
        put("stream_id", streamId)
    }

    fun setName(name: String) = request("Client.SetName") {
        put("id", clientId)
        put("name", name)
    }

    private fun loop(host: String, port: Int) {
        var backoffMs = 1000L
        while (running) {
            try {
                val s = Socket()
                socket = s
                s.connect(InetSocketAddress(host, port), 5000)
                out = s.getOutputStream()
                backoffMs = 1000L
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                refresh()
                while (running) {
                    val line = reader.readLine() ?: break
                    handle(line)
                }
            } catch (_: Exception) {
                // server gone or restarting; retry below
            } finally {
                runCatching { socket?.close() }
                out = null
            }
            if (!running) break
            onRoom(null)
            try {
                Thread.sleep(backoffMs)
            } catch (_: InterruptedException) {
                break
            }
            backoffMs = (backoffMs * 2).coerceAtMost(10_000L)
        }
    }

    private fun handle(line: String) {
        val msg = runCatching { Json.parseToJsonElement(line) }.getOrNull() ?: return
        // A batch reply or notification may arrive as an array.
        val items = if (msg is JsonArray) msg else listOf(msg)
        for (item in items) {
            val o = item as? JsonObject ?: continue
            val id = (o["id"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            when {
                id != null && id == statusRequestId -> o["result"]?.let { onRoom(parseStatus(it, clientId)) }
                id == null && o["method"] != null -> refresh() // notification: something changed
            }
        }
    }

    private fun refresh() {
        statusRequestId = request("Server.GetStatus") {}
    }

    private fun request(method: String, params: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): Int {
        synchronized(writeLock) {
            val id = nextId++
            val o = out ?: return id
            val body = buildJsonObject {
                put("id", id)
                put("jsonrpc", "2.0")
                put("method", method)
                put("params", buildJsonObject(params))
            }
            runCatching {
                o.write((body.toString() + "\r\n").toByteArray(Charsets.UTF_8))
                o.flush()
            }
            return id
        }
    }

    companion object {
        /** Extracts our room from a Server.GetStatus result; null if the server doesn't know us yet. */
        fun parseStatus(result: JsonElement, clientId: String): RoomInfo? {
            val server = result.jsonObject["server"]?.jsonObject ?: return null
            val streams = server["streams"]?.jsonArray.orEmpty().mapNotNull { parseStream(it) }
            for (g in server["groups"]?.jsonArray.orEmpty()) {
                val group = g.jsonObject
                val client = group["clients"]?.jsonArray.orEmpty()
                    .map { it.jsonObject }
                    .firstOrNull { it.str("id") == clientId } ?: continue
                val configName = client["config"]?.jsonObject?.str("name").orEmpty()
                val hostName = client["host"]?.jsonObject?.str("name").orEmpty()
                val streamId = group.str("stream_id")
                return RoomInfo(
                    clientName = configName.ifBlank { hostName },
                    groupId = group.str("id").orEmpty(),
                    groupName = group.str("name").orEmpty(),
                    stream = streams.firstOrNull { it.id == streamId },
                    streams = streams,
                )
            }
            return null
        }

        private fun parseStream(e: JsonElement): StreamInfo? {
            val o = e as? JsonObject ?: return null
            val id = o.str("id") ?: return null
            val meta = o["properties"]?.jsonObject?.get("metadata") as? JsonObject
            val track = meta?.let {
                TrackInfo(
                    title = it.str("title"),
                    artist = it.strOrList("artist"),
                    album = it.str("album"),
                ).takeIf { t -> t.title != null || t.artist != null }
            }
            return StreamInfo(id, o.str("status").orEmpty(), track)
        }

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        /** MPRIS-style metadata sends artist as a list. */
        private fun JsonObject.strOrList(key: String): String? = when (val v = this[key]) {
            is JsonPrimitive -> v.contentOrNull
            is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString(", ").ifBlank { null }
            else -> null
        }
    }
}
