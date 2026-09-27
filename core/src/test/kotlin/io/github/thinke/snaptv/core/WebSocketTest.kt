package io.github.thinke.snaptv.core

import io.github.thinke.snaptv.core.protocol.CodecHeader
import io.github.thinke.snaptv.core.protocol.MessageReader
import io.github.thinke.snaptv.core.protocol.MessageType
import io.github.thinke.snaptv.core.protocol.MessageWriter
import io.github.thinke.snaptv.core.protocol.ProtocolException
import io.github.thinke.snaptv.core.protocol.WireChunk
import io.github.thinke.snaptv.core.transport.Scheme
import io.github.thinke.snaptv.core.transport.ServerAddress
import io.github.thinke.snaptv.core.transport.TlsOptions
import io.github.thinke.snaptv.core.transport.Transport
import io.github.thinke.snaptv.core.transport.WsFrames
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.KeyStore
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException

class WebSocketTest {
    private val servers = mutableListOf<ServerSocket>()
    private val scripts = mutableListOf<Thread>()
    private val scriptFailures = ConcurrentLinkedQueue<Throwable>()

    /** Server scripts run on their own threads; their assertion failures fail the test here. */
    @After
    fun tearDown() {
        synchronized(scripts) { scripts.toList() }.forEach { it.join(5000) }
        servers.forEach { runCatching { it.close() } }
        scriptFailures.peek()?.let { throw AssertionError("server script failed: $it", it) }
    }

    // ---- framing ----

    @Test
    fun encodesRfcExamples() {
        // RFC 6455 section 5.7.
        val hello = "Hello".toByteArray()
        assertArrayEquals(bytes(0x81, 0x05, 0x48, 0x65, 0x6c, 0x6c, 0x6f), WsFrames.encode(WsFrames.TEXT, hello, null))
        assertArrayEquals(
            bytes(0x81, 0x85, 0x37, 0xfa, 0x21, 0x3d, 0x7f, 0x9f, 0x4d, 0x51, 0x58),
            WsFrames.encode(WsFrames.TEXT, hello, bytes(0x37, 0xfa, 0x21, 0x3d)),
        )
        assertArrayEquals(bytes(0x01, 0x03, 0x48, 0x65, 0x6c), WsFrames.encode(WsFrames.TEXT, "Hel".toByteArray(), null, fin = false))
        val f256 = WsFrames.encode(WsFrames.BINARY, ByteArray(256), null)
        assertArrayEquals(bytes(0x82, 0x7e, 0x01, 0x00), f256.copyOf(4))
        assertEquals(4 + 256, f256.size)
        val f64k = WsFrames.encode(WsFrames.BINARY, ByteArray(65536), bytes(1, 2, 3, 4))
        assertArrayEquals(bytes(0x82, 0xff, 0, 0, 0, 0, 0, 1, 0, 0, 1, 2, 3, 4), f64k.copyOf(14))
        assertEquals(14 + 65536, f64k.size)
        // Boundary: 125 still fits the 7 bit length, 126 does not; 65535 fits 16 bits.
        assertEquals(125, WsFrames.encode(WsFrames.BINARY, ByteArray(125), null)[1].toInt())
        assertEquals(126, WsFrames.encode(WsFrames.BINARY, ByteArray(126), null)[1].toInt())
        assertEquals(126, WsFrames.encode(WsFrames.BINARY, ByteArray(65535), null)[1].toInt())
    }

    @Test
    fun decodesWhatItEncodes() {
        for (n in listOf(0, 1, 125, 126, 127, 65535, 65536, 200_000)) {
            val payload = ByteArray(n) { (it * 31).toByte() }
            for (mask in listOf(null, bytes(0xde, 0xad, 0xbe, 0xef))) {
                val input = ByteArrayInputStream(WsFrames.encode(WsFrames.BINARY, payload, mask))
                val h = WsFrames.readHeader(input)!!
                assertTrue(h.fin)
                assertEquals(WsFrames.BINARY, h.opcode)
                assertEquals(n.toLong(), h.length)
                assertArrayEquals(payload, WsFrames.readPayload(input, h))
                assertNull(WsFrames.readHeader(input))
            }
        }
    }

    @Test
    fun acceptKeyMatchesRfc() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WsFrames.acceptKey("dGhlIHNhbXBsZSBub25jZQ=="))
    }

    @Test
    fun rejectsBadControlFrames() {
        assertThrows(ProtocolException::class.java) { WsFrames.readHeader(ByteArrayInputStream(bytes(0x09, 0x00))) } // fragmented ping
        assertThrows(ProtocolException::class.java) { WsFrames.readHeader(ByteArrayInputStream(bytes(0x89, 0x7e, 0, 200)))} // long ping
        assertThrows(ProtocolException::class.java) { WsFrames.readHeader(ByteArrayInputStream(bytes(0xc2, 0x00))) } // RSV1
    }

    // ---- transport against an in-process server ----

    @Test
    fun streamsSnapcastMessagesOverWebSocket() {
        val header = MessageWriter.encode(MessageType.CODEC_HEADER, 1, 0, sized("pcm") + sized(ByteArray(44) { it.toByte() }))
        val medium = chunk(2, ByteArray(300) { it.toByte() }) // 16 bit extended length
        val big = chunk(3, ByteArray(70_000) { (it % 251).toByte() }) // 64 bit extended length
        val fragmented = chunk(4, ByteArray(5000) { (it % 7).toByte() })
        val clientMsg = MessageWriter.encode(MessageType.HELLO, 9, 0, MessageWriter.jsonPayload("{}"))

        val pongs = LinkedBlockingQueue<ByteArray>()
        val received = LinkedBlockingQueue<ByteArray>()
        val port = serve { c ->
            val req = c.acceptHandshake()
            assertTrue(req, req.startsWith("GET /stream HTTP/1.1\r\n"))
            assertTrue(req, req.contains("\r\nHost: 127.0.0.1:${c.port}\r\n"))
            c.send(WsFrames.encode(WsFrames.BINARY, header, null))
            c.send(WsFrames.encode(WsFrames.TEXT, "ignored".toByteArray(), null, fin = false))
            c.send(WsFrames.encode(WsFrames.CONTINUATION, " text".toByteArray(), null))
            c.send(WsFrames.encode(WsFrames.BINARY, medium, null))
            c.send(WsFrames.encode(WsFrames.BINARY, big, null))
            // One message in three frames, with a ping and a pong in the middle.
            c.send(WsFrames.encode(WsFrames.BINARY, fragmented.copyOfRange(0, 10), null, fin = false))
            c.send(WsFrames.encode(WsFrames.PING, "hi".toByteArray(), null))
            c.send(WsFrames.encode(WsFrames.CONTINUATION, fragmented.copyOfRange(10, 10), null, fin = false))
            c.send(WsFrames.encode(WsFrames.PONG, ByteArray(0), null))
            c.send(WsFrames.encode(WsFrames.CONTINUATION, fragmented.copyOfRange(10, fragmented.size), null))
            // Read what the client sends until it closes.
            while (true) {
                val h = WsFrames.readHeader(c.input) ?: break
                assertNotNull("client frames must be masked", h.mask)
                val p = WsFrames.readPayload(c.input, h)
                when (h.opcode) {
                    WsFrames.PONG -> pongs.add(p)
                    WsFrames.BINARY -> {
                        received.add(p)
                        c.send(WsFrames.encode(WsFrames.CLOSE, bytes(0x03, 0xe8), null))
                    }
                    WsFrames.CLOSE -> { received.add(p); break }
                }
            }
        }

        val t = Transport.connect(ServerAddress(Scheme.WS, "127.0.0.1", port))
        val input = DataInputStream(t.input)
        val codec = MessageReader.read(input) { 0 } as CodecHeader
        assertEquals("pcm", codec.codec)
        assertEquals(44, codec.payload.size)
        assertArrayEquals(ByteArray(300) { it.toByte() }, (MessageReader.read(input) { 0 } as WireChunk).payload)
        assertArrayEquals(ByteArray(70_000) { (it % 251).toByte() }, (MessageReader.read(input) { 0 } as WireChunk).payload)
        val f = MessageReader.read(input) { 0 } as WireChunk
        assertEquals(4, f.header.id)
        assertArrayEquals(ByteArray(5000) { (it % 7).toByte() }, f.payload)
        assertArrayEquals("hi".toByteArray(), pongs.poll(2, TimeUnit.SECONDS))

        t.send(clientMsg)
        assertArrayEquals(clientMsg, received.poll(2, TimeUnit.SECONDS))
        assertEquals(-1, t.input.read()) // server's close frame ends the stream
        assertArrayEquals(bytes(0x03, 0xe8), received.poll(2, TimeUnit.SECONDS)) // close echoed
        t.close()
    }

    @Test
    fun refusedUpgradeFails() {
        val port = serve { c ->
            c.readRequest()
            c.send("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
        }
        val e = assertThrows(ProtocolException::class.java) { Transport.connect(ServerAddress(Scheme.WS, "127.0.0.1", port)) }
        assertTrue(e.message!!, e.message!!.contains("404"))
    }

    @Test
    fun wrongAcceptKeyFails() {
        val port = serve { c ->
            c.readRequest()
            c.send("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: nope\r\n\r\n".toByteArray())
        }
        assertThrows(ProtocolException::class.java) { Transport.connect(ServerAddress(Scheme.WS, "127.0.0.1", port)) }
    }

    @Test
    fun maskedServerFrameIsAnError() {
        val port = serve { c ->
            c.acceptHandshake()
            c.send(WsFrames.encode(WsFrames.BINARY, chunk(1, ByteArray(4)), bytes(1, 2, 3, 4)))
            c.input.read() // hold the connection until the client gives up
        }
        val t = Transport.connect(ServerAddress(Scheme.WS, "127.0.0.1", port))
        assertThrows(ProtocolException::class.java) { t.input.read() }
        t.close()
    }

    /** The whole engine over ws: Hello arrives as one binary message, settings come back. */
    @Test
    fun engineSpeaksWebSocket() {
        val hello = CompletableFuture<String>()
        val port = serve { c ->
            c.acceptHandshake()
            while (true) {
                val h = WsFrames.readHeader(c.input) ?: break
                val p = WsFrames.readPayload(c.input, h)
                if (h.opcode != WsFrames.BINARY) break
                val type = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN).getShort().toInt()
                if (type == MessageType.HELLO) {
                    hello.complete(String(p, 26 + 4, p.size - 30))
                    val settings = MessageWriter.jsonPayload("""{"bufferMs":750,"latency":0,"muted":false,"volume":42}""")
                    c.send(WsFrames.encode(WsFrames.BINARY, MessageWriter.encode(MessageType.SERVER_SETTINGS, 1, 0, settings), null))
                }
            }
        }
        val got = CompletableFuture<ServerSettings>()
        val engine = SnapEngine(ClientIdentity("snaptv-test", "h", "os", "arch"), object : SnapListener {
            override fun onSettings(settings: ServerSettings) { got.complete(settings) }
        })
        engine.start(ServerAddress.parse("ws://127.0.0.1:$port"))
        try {
            assertTrue(hello.get(5, TimeUnit.SECONDS).contains("\"ID\":\"snaptv-test\""))
            val s = got.get(5, TimeUnit.SECONDS)
            assertEquals(750, s.bufferMs)
            assertEquals(42, s.volume)
        } finally {
            engine.stop()
        }
    }

    /** The same over plain tcp://, i.e. TcpTransport: messages back to back on the socket. */
    @Test
    fun engineSpeaksTcp() {
        val hello = CompletableFuture<String>()
        val port = serve { c ->
            val input = DataInputStream(c.input)
            while (true) {
                val base = ByteArray(26)
                input.readFully(base)
                val b = ByteBuffer.wrap(base).order(ByteOrder.LITTLE_ENDIAN)
                val p = ByteArray(b.getInt(22))
                input.readFully(p)
                if (b.getShort(0).toInt() == MessageType.HELLO) {
                    hello.complete(String(p, 4, p.size - 4))
                    val settings = MessageWriter.jsonPayload("""{"bufferMs":900,"latency":0,"muted":false,"volume":17}""")
                    c.send(MessageWriter.encode(MessageType.SERVER_SETTINGS, 1, 0, settings))
                }
            }
        }
        val got = CompletableFuture<ServerSettings>()
        val engine = SnapEngine(ClientIdentity("snaptv-test", "h", "os", "arch"), object : SnapListener {
            override fun onSettings(settings: ServerSettings) { got.complete(settings) }
        })
        engine.start(ServerAddress.parse("tcp://127.0.0.1:$port"))
        try {
            assertTrue(hello.get(5, TimeUnit.SECONDS).contains("\"ID\":\"snaptv-test\""))
            val s = got.get(5, TimeUnit.SECONDS)
            assertEquals(900, s.bufferMs)
            assertEquals(17, s.volume)
        } finally {
            engine.stop()
        }
    }

    // ---- wss ----

    @Test
    fun wssWithPinnedSelfSignedCertificate() {
        val msg = chunk(7, ByteArray(1000) { it.toByte() })
        val (port, pem) = serveTls { c ->
            c.acceptHandshake()
            c.send(WsFrames.encode(WsFrames.BINARY, msg, null))
            c.input.read()
        }
        val address = ServerAddress.parse("wss://127.0.0.1:$port")

        // Not in the system trust store (and the name doesn't match): refused by default.
        assertThrows(SSLException::class.java) { Transport.connect(address) }

        for (tls in listOf(pem.inputStream().use(TlsOptions::fromPem), TlsOptions(trustAll = true))) {
            val t = Transport.connect(address, tls)
            val w = MessageReader.read(DataInputStream(t.input)) { 0 } as WireChunk
            assertArrayEquals(ByteArray(1000) { it.toByte() }, w.payload)
            t.close()
        }
    }

    /**
     * close() must not hang behind a send() stuck on a peer that stopped reading. SSLSocket.close()
     * waits for such a writer without a timeout, so over wss only closing the TCP socket helps.
     */
    @Test
    fun wssCloseWithStuckWriter() {
        val release = CountDownLatch(1)
        val (port, _) = serveTls { c ->
            c.acceptHandshake()
            release.await(10, TimeUnit.SECONDS) // never read what the client sends
        }
        val t = Transport.connect(ServerAddress.parse("wss://127.0.0.1:$port"), TlsOptions(trustAll = true))
        val writer = Thread {
            val big = ByteArray(256 * 1024)
            runCatching { while (true) t.send(big) }
        }.apply { isDaemon = true; start() }
        // Wait until the writer is blocked: nothing more has gone out for a while.
        Thread.sleep(1000)
        assertTrue(writer.isAlive)
        val closed = CompletableFuture.runAsync { t.close() }
        try {
            closed.get(3, TimeUnit.SECONDS)
            writer.join(3000)
            assertTrue("writer still blocked after close", !writer.isAlive)
        } finally {
            release.countDown()
        }
    }

    // ---- helpers ----

    private class Conn(socket: Socket, val port: Int) {
        val input: InputStream = BufferedInputStream(socket.getInputStream())
        private val out: OutputStream = socket.getOutputStream()

        fun readRequest(): String {
            val sb = StringBuilder()
            while (!sb.endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b < 0) break
                sb.append(b.toChar())
            }
            return sb.toString()
        }

        fun acceptHandshake(): String {
            val req = readRequest()
            val key = req.lines().first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }.substringAfter(':').trim()
            send(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: ${WsFrames.acceptKey(key)}\r\n\r\n").toByteArray())
            return req
        }

        fun send(b: ByteArray) {
            out.write(b)
            out.flush()
        }
    }

    /** Runs [script] for the first connection on a loopback port; returns the port. */
    private fun serve(script: (Conn) -> Unit): Int {
        val ss = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        servers += ss
        runScript { ss.accept().use { script(Conn(it, ss.localPort)) } }
        return ss.localPort
    }

    /**
     * Runs [script] for every connection to a TLS loopback server with a fresh self-signed
     * certificate; returns the port and that certificate as PEM.
     */
    private fun serveTls(script: (Conn) -> Unit): Pair<Int, File> {
        val keytool = File(System.getProperty("java.home"), "bin/keytool")
        assumeTrue("keytool not available", keytool.canExecute())
        val dir = Files.createTempDirectory("snaptv-wss").toFile().apply { deleteOnExit() }
        val ks = File(dir, "server.p12").apply { deleteOnExit() }
        val pem = File(dir, "server.pem").apply { deleteOnExit() }
        fun run(vararg a: String) {
            val p = ProcessBuilder(keytool.path, *a).redirectErrorStream(true).start()
            val out = p.inputStream.readBytes().decodeToString()
            assertEquals(out, 0, p.waitFor())
        }
        run("-genkeypair", "-keystore", ks.path, "-storetype", "PKCS12", "-storepass", "secret", "-alias", "s",
            "-keyalg", "EC", "-dname", "CN=snapserver.invalid", "-validity", "2")
        run("-exportcert", "-rfc", "-keystore", ks.path, "-storetype", "PKCS12", "-storepass", "secret", "-alias", "s", "-file", pem.path)

        val keyStore = KeyStore.getInstance("PKCS12").apply { ks.inputStream().use { load(it, "secret".toCharArray()) } }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, "secret".toCharArray()) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val ss = ctx.serverSocketFactory.createServerSocket(0, 5, InetAddress.getLoopbackAddress())
        servers += ss
        Thread {
            while (!ss.isClosed) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                runScript { s.use { script(Conn(it, ss.localPort)) } }
            }
        }.apply { isDaemon = true }.start()
        return ss.localPort to pem
    }

    /**
     * Runs [body] on a daemon thread, keeping any failure for [tearDown]. I/O errors are the
     * client going away (refused handshakes, closes) and don't count.
     */
    private fun runScript(body: () -> Unit) {
        val t = Thread {
            try {
                body()
            } catch (_: IOException) {
            } catch (e: Throwable) {
                scriptFailures += e
            }
        }.apply { isDaemon = true }
        synchronized(scripts) { scripts += t }
        t.start()
    }

    private fun chunk(id: Int, pcm: ByteArray): ByteArray {
        val p = ByteBuffer.allocate(12 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).putInt(1).putInt(2).putInt(pcm.size).put(pcm).array()
        return MessageWriter.encode(MessageType.WIRE_CHUNK, id, 0, p)
    }

    private fun sized(s: String) = sized(s.toByteArray())

    private fun sized(b: ByteArray) = ByteArrayOutputStream().apply {
        write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(b.size).array())
        write(b)
    }.toByteArray()

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
}
