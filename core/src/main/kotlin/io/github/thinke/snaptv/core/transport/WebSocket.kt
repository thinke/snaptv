package io.github.thinke.snaptv.core.transport

import io.github.thinke.snaptv.core.protocol.ProtocolException
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** RFC 6455 framing, both directions, so tests can play the server side. */
object WsFrames {
    const val CONTINUATION = 0x0
    const val TEXT = 0x1
    const val BINARY = 0x2
    const val CLOSE = 0x8
    const val PING = 0x9
    const val PONG = 0xA

    /** Frames larger than this can't be a snapcast message (see BaseHeader.MAX_PAYLOAD). */
    const val MAX_FRAME = 32L * 1024 * 1024

    class Header(val fin: Boolean, val opcode: Int, val length: Long, val mask: ByteArray?)

    /** A whole frame; clients must pass a 4 byte [mask], servers null. */
    fun encode(opcode: Int, payload: ByteArray, mask: ByteArray?, fin: Boolean = true): ByteArray {
        val n = payload.size
        val lenBytes = if (n < 126) 0 else if (n <= 0xffff) 2 else 8
        val out = ByteArray(2 + lenBytes + (if (mask != null) 4 else 0) + n)
        out[0] = ((if (fin) 0x80 else 0) or opcode).toByte()
        val maskBit = if (mask != null) 0x80 else 0
        var p = 2
        when (lenBytes) {
            0 -> out[1] = (maskBit or n).toByte()
            2 -> {
                out[1] = (maskBit or 126).toByte()
                out[p++] = (n ushr 8).toByte(); out[p++] = n.toByte()
            }
            else -> {
                out[1] = (maskBit or 127).toByte()
                for (shift in 56 downTo 0 step 8) out[p++] = (n.toLong() ushr shift).toByte()
            }
        }
        if (mask != null) {
            require(mask.size == 4)
            mask.copyInto(out, p)
            p += 4
            for (i in 0 until n) out[p + i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
        } else {
            payload.copyInto(out, p)
        }
        return out
    }

    /** Next frame header, or null on a clean end of stream before it. */
    fun readHeader(input: InputStream): Header? {
        val b0 = input.read()
        if (b0 < 0) return null
        val b1 = readByte(input)
        if (b0 and 0x70 != 0) throw ProtocolException("websocket: reserved bits set (no extensions negotiated)")
        var len = (b1 and 0x7f).toLong()
        if (len == 126L) {
            len = (readByte(input).toLong() shl 8) or readByte(input).toLong()
        } else if (len == 127L) {
            len = 0
            repeat(8) { len = (len shl 8) or readByte(input).toLong() }
            if (len < 0) throw ProtocolException("websocket: bad frame length")
        }
        val mask = if (b1 and 0x80 != 0) ByteArray(4).also { readFully(input, it) } else null
        val opcode = b0 and 0x0f
        if (opcode >= 0x8 && (b0 and 0x80 == 0 || len > 125)) throw ProtocolException("websocket: bad control frame")
        return Header(b0 and 0x80 != 0, opcode, len, mask)
    }

    /** Reads a (small) frame payload whole, unmasking it if needed. */
    fun readPayload(input: InputStream, h: Header): ByteArray {
        if (h.length > MAX_FRAME) throw ProtocolException("websocket: frame too large (${h.length})")
        val p = ByteArray(h.length.toInt())
        readFully(input, p)
        h.mask?.let { m -> for (i in p.indices) p[i] = (p[i].toInt() xor m[i and 3].toInt()).toByte() }
        return p
    }

    private fun readByte(input: InputStream): Int {
        val b = input.read()
        if (b < 0) throw EOFException("websocket: truncated frame")
        return b
    }

    internal fun readFully(input: InputStream, b: ByteArray) {
        var off = 0
        while (off < b.size) {
            val n = input.read(b, off, b.size - off)
            if (n < 0) throw EOFException("websocket: truncated frame")
            off += n
        }
    }

    fun acceptKey(key: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.US_ASCII)))

    private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
}

/**
 * Snapcast over WebSocket, as snapclient's ClientConnectionWs/Wss: `GET /stream` on the HTTP
 * server (no subprotocol, no extensions), every snapcast message is one binary message in each
 * direction. Server messages may be fragmented (beast auto-fragments); pings are answered.
 */
class WebSocketTransport private constructor(private val socket: Socket) : Transport {
    private val raw = BufferedInputStream(socket.getInputStream(), 64 * 1024)
    private val out: OutputStream = socket.getOutputStream()
    private val writeLock = ReentrantLock()
    private val random = SecureRandom()
    @Volatile private var closeSent = false

    override val input: InputStream = MessageStream()

    override fun send(message: ByteArray) = sendFrame(WsFrames.BINARY, message)

    override val isClosed get() = socket.isClosed

    /** Says goodbye if the socket is writable within a moment, then drops it. */
    override fun close() {
        try {
            if (!socket.isClosed && writeLock.tryLock(200, TimeUnit.MILLISECONDS)) {
                try {
                    if (!closeSent) {
                        closeSent = true
                        out.write(WsFrames.encode(WsFrames.CLOSE, byteArrayOf(0x03, 0xE8.toByte()), newMask())) // 1000 normal
                        out.flush()
                    }
                } finally {
                    writeLock.unlock()
                }
            }
        } catch (_: Exception) {
        } finally {
            socket.close()
        }
    }

    private fun sendFrame(opcode: Int, payload: ByteArray) {
        val frame = WsFrames.encode(opcode, payload, newMask())
        writeLock.lock()
        try {
            if (closeSent) throw java.io.IOException("websocket closed")
            if (opcode == WsFrames.CLOSE) closeSent = true
            out.write(frame)
            out.flush()
        } finally {
            writeLock.unlock()
        }
    }

    private fun newMask() = ByteArray(4).also { random.nextBytes(it) }

    private fun handshake(address: ServerAddress) {
        val key = Base64.getEncoder().encodeToString(ByteArray(16).also { random.nextBytes(it) })
        val request = "GET /stream HTTP/1.1\r\n" +
            "Host: ${address.authority}\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $key\r\n" +
            "Sec-WebSocket-Version: 13\r\n" +
            "User-Agent: SnapTV\r\n" +
            "\r\n"
        out.write(request.toByteArray(Charsets.US_ASCII))
        out.flush()

        val status = readLine()
        if (status.split(' ').getOrNull(1) != "101") throw ProtocolException("websocket handshake refused: $status")
        val headers = HashMap<String, String>()
        var total = 0
        while (true) {
            val line = readLine()
            if (line.isEmpty()) break
            total += line.length
            if (total > 16 * 1024) throw ProtocolException("websocket handshake: headers too long")
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        if (!headers["upgrade"].equals("websocket", ignoreCase = true)) throw ProtocolException("websocket handshake: no upgrade")
        if (headers["sec-websocket-accept"] != WsFrames.acceptKey(key)) throw ProtocolException("websocket handshake: bad accept key")
        if (headers["sec-websocket-extensions"] != null) throw ProtocolException("websocket handshake: unrequested extension")
    }

    private fun readLine(): String {
        val sb = ByteArrayOutputStream()
        while (true) {
            val b = raw.read()
            if (b < 0) throw EOFException("websocket handshake: connection closed")
            if (b == '\n'.code) break
            if (sb.size() > 8192) throw ProtocolException("websocket handshake: line too long")
            sb.write(b)
        }
        return sb.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r")
    }

    /** Binary message payloads back to back; control frames are handled in between. */
    private inner class MessageStream : InputStream() {
        private var remaining = 0L // payload bytes left in the current data frame
        private var inMessage = false // a fragmented message is still going
        private var skipText = false // that message is text, which snapcast never sends: drop it
        private var ended = false

        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (remaining == 0L) if (!nextDataFrame()) return -1
            val n = raw.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n < 0) throw EOFException("websocket: truncated frame")
            remaining -= n
            return n
        }

        override fun available() = minOf(remaining, raw.available().toLong()).toInt()

        /** Advances to the next frame with binary payload; false once the server closed. */
        private fun nextDataFrame(): Boolean {
            while (!ended) {
                val h = WsFrames.readHeader(raw) ?: throw EOFException("websocket: connection closed without close frame")
                if (h.mask != null) throw ProtocolException("websocket: masked frame from server")
                when (h.opcode) {
                    WsFrames.PING -> {
                        val p = WsFrames.readPayload(raw, h)
                        runCatching { sendFrame(WsFrames.PONG, p) }
                    }
                    WsFrames.PONG -> WsFrames.readPayload(raw, h)
                    WsFrames.CLOSE -> {
                        val p = WsFrames.readPayload(raw, h)
                        ended = true
                        runCatching { sendFrame(WsFrames.CLOSE, p.copyOf(minOf(p.size, 2))) } // echo the code
                    }
                    WsFrames.TEXT, WsFrames.BINARY -> {
                        if (inMessage) throw ProtocolException("websocket: new message inside a fragmented one")
                        inMessage = !h.fin
                        skipText = h.opcode == WsFrames.TEXT
                        if (take(h)) return true
                    }
                    WsFrames.CONTINUATION -> {
                        if (!inMessage) throw ProtocolException("websocket: continuation without a message")
                        inMessage = !h.fin
                        if (take(h)) return true
                    }
                    else -> throw ProtocolException("websocket: unknown opcode ${h.opcode}")
                }
            }
            return false
        }

        private fun take(h: WsFrames.Header): Boolean {
            if (skipText) {
                var left = h.length
                while (left > 0) {
                    val s = raw.skip(left)
                    if (s <= 0) { if (raw.read() < 0) throw EOFException("websocket: truncated frame") else left-- } else left -= s
                }
                return false
            }
            remaining = h.length
            return remaining > 0
        }
    }

    companion object {
        /** Runs the upgrade on an already connected (and, for wss, TLS) socket. */
        fun open(socket: Socket, address: ServerAddress): WebSocketTransport {
            val t = WebSocketTransport(socket)
            t.handshake(address)
            return t
        }
    }
}
