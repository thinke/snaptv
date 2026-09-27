package io.github.thinke.snaptv.core.protocol

import java.io.DataInputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Snapcast binary protocol (little endian). Every message is a 26 byte base header
 * followed by `size` bytes of typed payload. See snapcast doc/binary_protocol.md.
 */
object MessageType {
    const val CODEC_HEADER = 1
    const val WIRE_CHUNK = 2
    const val SERVER_SETTINGS = 3
    const val TIME = 4
    const val HELLO = 5
    const val CLIENT_INFO = 7
    const val ERROR = 8
}

/** Protocol timestamps are {sec, usec} pairs; we carry them as microseconds. */
internal fun ByteBuffer.getTv(): Long = getInt().toLong() * 1_000_000L + getInt().toLong()

internal fun ByteBuffer.putTv(us: Long) {
    // Floor division keeps usec in [0, 1e6) for negative values, as snapcast's tv does.
    putInt(Math.floorDiv(us, 1_000_000L).toInt())
    putInt(Math.floorMod(us, 1_000_000L).toInt())
}

data class BaseHeader(
    val type: Int,
    val id: Int,
    val refersTo: Int,
    val sentUs: Long,
    val receivedUs: Long,
    val size: Int,
) {
    companion object {
        const val SIZE = 26
        /** Guard against a corrupt stream making us allocate gigabytes. */
        const val MAX_PAYLOAD = 16 * 1024 * 1024
    }
}

sealed interface ServerMessage {
    val header: BaseHeader
}

class CodecHeader(override val header: BaseHeader, val codec: String, val payload: ByteArray) : ServerMessage

/** [timestampUs] is the server-clock time of the first sample in [payload]. */
class WireChunk(override val header: BaseHeader, val timestampUs: Long, val payload: ByteArray) : ServerMessage

class ServerSettingsMessage(override val header: BaseHeader, val json: String) : ServerMessage

/** Server's reply to our time request; [latencyUs] = server receive time - our send time. */
class TimeMessage(override val header: BaseHeader, val latencyUs: Long) : ServerMessage

/**
 * Sent by snapserver in reply to Hello when auth is enabled and fails: 401 "Unauthorized"
 * (missing or wrong credentials) or 403 "Forbidden" (user lacks the Streaming permission).
 * The server closes the connection right after it.
 */
class ErrorMessage(override val header: BaseHeader, val code: Int, val error: String, val message: String) : ServerMessage {
    val isAuthError: Boolean get() = code == UNAUTHORIZED || code == FORBIDDEN

    companion object {
        const val UNAUTHORIZED = 401
        const val FORBIDDEN = 403
    }
}

class UnknownMessage(override val header: BaseHeader) : ServerMessage

class ProtocolException(message: String) : Exception(message)

object MessageReader {
    /**
     * Blocks until one full message has been read. [nowUs] stamps the receive time as soon
     * as the header arrives, which is what time sync needs.
     */
    fun read(input: DataInputStream, nowUs: () -> Long): ServerMessage {
        val headerBytes = ByteArray(BaseHeader.SIZE)
        input.readFully(headerBytes)
        val receivedUs = nowUs()
        val hb = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
        val type = hb.getShort().toInt() and 0xffff
        val id = hb.getShort().toInt() and 0xffff
        val refersTo = hb.getShort().toInt() and 0xffff
        val sentUs = hb.getTv()
        hb.getTv() // received, filled in by us instead
        val size = hb.getInt()
        if (size < 0 || size > BaseHeader.MAX_PAYLOAD) throw ProtocolException("bad message size $size (type $type)")
        val header = BaseHeader(type, id, refersTo, sentUs, receivedUs, size)

        val payload = ByteArray(size)
        input.readFully(payload)
        val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        return when (type) {
            MessageType.CODEC_HEADER -> {
                val codec = b.getSizedString()
                CodecHeader(header, codec, b.getSizedBytes())
            }
            MessageType.WIRE_CHUNK -> {
                val ts = b.getTv()
                WireChunk(header, ts, b.getSizedBytes())
            }
            MessageType.SERVER_SETTINGS -> ServerSettingsMessage(header, b.getSizedString())
            MessageType.TIME -> TimeMessage(header, b.getTv())
            MessageType.ERROR -> {
                val code = b.getInt()
                val error = b.getSizedString()
                ErrorMessage(header, code, error, b.getSizedString())
            }
            else -> UnknownMessage(header)
        }
    }

    private fun ByteBuffer.getSizedBytes(): ByteArray {
        val n = getInt()
        if (n < 0 || n > remaining()) throw ProtocolException("bad field size $n (remaining ${remaining()})")
        return ByteArray(n).also { get(it) }
    }

    private fun ByteBuffer.getSizedString(): String = String(getSizedBytes(), Charsets.UTF_8)
}

object MessageWriter {
    fun write(out: OutputStream, type: Int, id: Int, sentUs: Long, payload: ByteArray) {
        val buf = ByteBuffer.allocate(BaseHeader.SIZE + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(type.toShort())
        buf.putShort(id.toShort())
        buf.putShort(0) // refersTo
        buf.putTv(sentUs)
        buf.putTv(0) // received
        buf.putInt(payload.size)
        buf.put(payload)
        out.write(buf.array())
        out.flush()
    }

    fun jsonPayload(json: String): ByteArray {
        val bytes = json.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(4 + bytes.size).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.size).put(bytes).array()
    }

    /** A time request carries a zero latency; the server fills in its own. */
    fun timePayload(): ByteArray = ByteArray(8)
}
