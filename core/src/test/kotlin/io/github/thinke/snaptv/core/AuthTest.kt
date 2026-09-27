package io.github.thinke.snaptv.core

import io.github.thinke.snaptv.core.codec.SampleFormat
import io.github.thinke.snaptv.core.protocol.BaseHeader
import io.github.thinke.snaptv.core.protocol.ErrorMessage
import io.github.thinke.snaptv.core.protocol.MessageReader
import io.github.thinke.snaptv.core.protocol.MessageType
import io.github.thinke.snaptv.core.protocol.MessageWriter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AuthTest {
    private val identity = ClientIdentity(id = "test-id", hostName = "box", os = "Linux", arch = "x86_64")

    private fun hello(credentials: Credentials?): JsonObject = Json.parseToJsonElement(helloJson(identity, credentials)).jsonObject

    @Test
    fun helloWithoutAuthHasNoAuthKey() {
        val o = hello(null)
        assertFalse("Auth" in o)
        assertEquals(
            setOf("Arch", "ClientName", "HostName", "ID", "Instance", "MAC", "OS", "SnapStreamProtocolVersion", "Version"),
            o.keys,
        )
        assertEquals("test-id", o["ID"]!!.jsonPrimitive.content)
    }

    @Test
    fun helloWithAuthCarriesBasicCredentials() {
        val auth = hello(Credentials("badaix", "secret"))["Auth"]!!.jsonObject
        assertEquals("Basic", auth["scheme"]!!.jsonPrimitive.content)
        // The example from snapserver's Server.Authenticate docs: base64("badaix:secret").
        assertEquals("YmFkYWl4OnNlY3JldA==", auth["param"]!!.jsonPrimitive.content)
    }

    @Test
    fun basicParamIsUtf8AndKeepsColonsInPassword() {
        val decoded = String(java.util.Base64.getDecoder().decode(Credentials("jürgen", "a:b").basicParam()), Charsets.UTF_8)
        assertEquals("jürgen:a:b", decoded) // server splits on the first ':' only
    }

    @Test
    fun credentialsOfBlankIsNone() {
        assertNull(Credentials.of("", ""))
        assertEquals(Credentials("u", ""), Credentials.of("u", ""))
        assertEquals(Credentials("", "p"), Credentials.of("", "p"))
        assertFalse(Credentials("u", "hunter2").toString().contains("hunter2"))
    }

    @Test
    fun parsesErrorMessage() {
        val bytes = ByteArrayOutputStream().also {
            MessageWriter.write(it, MessageType.ERROR, 3, 0, errorPayload(401, "Unauthorized", "Wrong password"))
        }.toByteArray()
        val msg = MessageReader.read(DataInputStream(ByteArrayInputStream(bytes))) { 0 }
        msg as ErrorMessage
        assertEquals(MessageType.ERROR, msg.header.type)
        assertEquals(401, msg.code)
        assertEquals("Unauthorized", msg.error)
        assertEquals("Wrong password", msg.message)
        assertTrue(msg.isAuthError)
    }

    @Test
    fun forbiddenIsAnAuthErrorButOthersAreNot() {
        assertTrue(ErrorMessage(header(), 403, "Forbidden", "Permission 'Streaming' missing").isAuthError)
        assertFalse(ErrorMessage(header(), 500, "Internal", "x").isAuthError)
        assertEquals("Not allowed to stream: nope", AuthFailure(403, "Forbidden", "nope").describe())
        assertEquals("Authentication failed: Unknown user", AuthFailure(401, "Unauthorized", "Unknown user").describe())
    }

    /** Plays snapserver with auth enabled: read Hello, answer Error 401, close - as server.cpp does. */
    @Test
    fun engineReportsAuthFailureAndBacksOff() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val connections = AtomicInteger()
        val helloAuth = CopyOnWriteArrayList<String>()
        val acceptor = Thread {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                connections.incrementAndGet()
                s.use {
                    val input = DataInputStream(it.getInputStream())
                    val header = ByteArray(BaseHeader.SIZE).also { h -> input.readFully(h) }
                    val size = ByteBuffer.wrap(header, 22, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()
                    val payload = ByteArray(size).also { p -> input.readFully(p) }
                    val json = String(payload, 4, size - 4, Charsets.UTF_8)
                    helloAuth += Json.parseToJsonElement(json).jsonObject["Auth"].toString()
                    MessageWriter.write(it.getOutputStream(), MessageType.ERROR, 0, 0, errorPayload(401, "Unauthorized", "Wrong password"))
                }
            }
        }.apply { isDaemon = true; start() }

        val failed = CountDownLatch(1)
        val states = CopyOnWriteArrayList<ConnectionState>()
        val failures = CopyOnWriteArrayList<AuthFailure>()
        val engine = SnapEngine(identity, object : SnapListener {
            override fun onState(state: ConnectionState) {
                states += state
                if (state is ConnectionState.Failed) failed.countDown()
            }
            override fun onAuthFailed(failure: AuthFailure) { failures += failure }
        })
        try {
            engine.start("127.0.0.1", server.localPort, Credentials("u", "p"))
            assertTrue(failed.await(5, TimeUnit.SECONDS))
            val f = states.filterIsInstance<ConnectionState.Failed>().first()
            assertNotNull(f.auth)
            assertEquals(401, f.auth!!.code)
            assertEquals("Authentication failed: Wrong password", f.reason)
            assertEquals(listOf(AuthFailure(401, "Unauthorized", "Wrong password")), failures)
            assertEquals("""{"param":"dTpw","scheme":"Basic"}""", helloAuth.single())

            // A normal failure would retry within 500 ms; an auth failure must not.
            Thread.sleep(1500)
            assertEquals(1, connections.get())
        } finally {
            // stop() has to cut the long backoff short rather than leave the worker sleeping.
            val t0 = System.nanoTime()
            engine.stop()
            assertTrue(System.nanoTime() - t0 < 1_000_000_000L)
            server.close()
            acceptor.join(1000)
        }
    }

    /** Accepts connections on loopback; [reply] answers each one after its Hello has been read. */
    private class FakeServer(private val reply: (OutputStream) -> Unit) : AutoCloseable {
        val socket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val connections = AtomicInteger()
        private val acceptor = Thread {
            while (!socket.isClosed) {
                val s = runCatching { socket.accept() }.getOrNull() ?: break
                connections.incrementAndGet()
                s.use {
                    val input = DataInputStream(it.getInputStream())
                    val header = ByteArray(BaseHeader.SIZE).also { h -> input.readFully(h) }
                    val size = ByteBuffer.wrap(header, 22, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()
                    input.readFully(ByteArray(size))
                    runCatching { reply(it.getOutputStream()) }
                }
            }
        }.apply { isDaemon = true; start() }

        override fun close() {
            socket.close()
            acceptor.join(1000)
        }
    }

    private class Recorder(private val done: (Recorder) -> Boolean) : SnapListener {
        val events = CopyOnWriteArrayList<Any>()
        val latch = CountDownLatch(1)
        private fun add(e: Any) { events += e; if (done(this)) latch.countDown() }
        override fun onState(state: ConnectionState) = add(state)
        override fun onFormat(format: SampleFormat, codec: String) = add(format)
        override fun onServerError(message: String) = add("error:$message")
        override fun onAuthFailed(failure: AuthFailure) = add(failure)
    }

    @Test
    fun engineReportsForbidden() {
        FakeServer { MessageWriter.write(it, MessageType.ERROR, 0, 0, errorPayload(403, "Forbidden", "Permission 'Streaming' missing")) }.use { server ->
            val rec = Recorder { r -> r.events.any { it is ConnectionState.Failed } }
            val engine = SnapEngine(identity, rec)
            try {
                engine.start("127.0.0.1", server.socket.localPort, Credentials("u", "p"))
                assertTrue(rec.latch.await(5, TimeUnit.SECONDS))
                val f = rec.events.filterIsInstance<ConnectionState.Failed>().first()
                assertTrue(f.auth!!.forbidden)
                assertEquals("Not allowed to stream: Permission 'Streaming' missing", f.reason)
                assertEquals(listOf(AuthFailure(403, "Forbidden", "Permission 'Streaming' missing")), rec.events.filterIsInstance<AuthFailure>())
                // Refused at Hello: never claimed to be Connected.
                assertFalse(rec.events.any { it is ConnectionState.Connected })
            } finally {
                engine.stop()
            }
        }
    }

    @Test
    fun otherServerErrorsDoNotEndTheSession() {
        val done = CountDownLatch(1)
        FakeServer { out ->
            MessageWriter.write(out, MessageType.ERROR, 0, 0, errorPayload(500, "Internal", "oops"))
            MessageWriter.write(out, MessageType.SERVER_SETTINGS, 1, 0, MessageWriter.jsonPayload("""{"bufferMs":1000,"latency":0,"volume":50,"muted":false}"""))
            MessageWriter.write(out, MessageType.CODEC_HEADER, 2, 0, codecHeaderPayload("pcm", wavHeader(48000, 16, 2)))
            done.await(5, TimeUnit.SECONDS) // hold the connection open until the test is done
        }.use { server ->
            val rec = Recorder { r -> r.events.any { it is SampleFormat } }
            val engine = SnapEngine(identity, rec)
            try {
                engine.start("127.0.0.1", server.socket.localPort)
                assertTrue(rec.latch.await(5, TimeUnit.SECONDS))
                val e = rec.events.toList()
                assertEquals("error:Internal: oops", e.single { it is String })
                assertEquals(SampleFormat(48000, 16, 2), e.single { it is SampleFormat })
                assertTrue(e.indexOf("error:Internal: oops") < e.indexOfFirst { it is SampleFormat })
                assertFalse(e.any { it is ConnectionState.Failed || it is AuthFailure })
                // Connected only once the server answered Hello, i.e. after the error and before the format.
                val connected = e.indexOfFirst { it is ConnectionState.Connected }
                assertTrue(connected > e.indexOf("error:Internal: oops"))
                assertTrue(connected < e.indexOfFirst { it is SampleFormat })
            } finally {
                done.countDown()
                engine.stop()
            }
        }
    }

    @Test
    fun authBackoffDoublesUpToFiveMinutes() {
        val seq = generateSequence(SnapEngine.AUTH_BACKOFF_MIN_MS) { SnapEngine.nextAuthBackoffMs(it) }.take(7).toList()
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L, 300_000L), seq)
    }

    /** Two auth failures in a row: the second wait is twice the first. */
    @Test
    fun engineDoublesAuthBackoff() {
        FakeServer { MessageWriter.write(it, MessageType.ERROR, 0, 0, errorPayload(401, "Unauthorized", "Wrong password")) }.use { server ->
            val times = CopyOnWriteArrayList<Long>()
            val rec = Recorder { r -> r.events.count { it is ConnectionState.Connecting } >= 3 }
            val engine = SnapEngine(identity, object : SnapListener by rec {
                override fun onState(state: ConnectionState) {
                    if (state is ConnectionState.Connecting) times += System.nanoTime()
                    rec.onState(state)
                }
            })
            engine.authBackoffMinMs = 200
            try {
                engine.start("127.0.0.1", server.socket.localPort, Credentials("u", "p"))
                assertTrue(rec.latch.await(5, TimeUnit.SECONDS))
                val first = (times[1] - times[0]) / 1_000_000
                val second = (times[2] - times[1]) / 1_000_000
                assertTrue("first wait $first ms", first in 200..399)
                assertTrue("second wait $second ms", second in 400..799)
            } finally {
                engine.stop()
            }
        }
    }

    private fun codecHeaderPayload(codec: String, header: ByteArray): ByteArray {
        val c = codec.toByteArray(Charsets.US_ASCII)
        return ByteBuffer.allocate(8 + c.size + header.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(c.size).put(c).putInt(header.size).put(header).array()
    }

    /** The 44-byte RIFF/WAVE header snapserver's pcm encoder sends as its codec header. */
    private fun wavHeader(rate: Int, bits: Int, channels: Int): ByteArray =
        ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(channels.toShort()); putInt(rate)
            putInt(rate * channels * bits / 8); putShort((channels * bits / 8).toShort()); putShort(bits.toShort())
            put("data".toByteArray()); putInt(0)
        }.array()

    private fun header() = BaseHeader(MessageType.ERROR, 0, 0, 0, 0, 0)

    /** msg::Error::doserialize: uint32 code, then sized error and message strings. */
    private fun errorPayload(code: Int, error: String, message: String): ByteArray {
        val e = error.toByteArray(Charsets.UTF_8)
        val m = message.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(12 + e.size + m.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(code).putInt(e.size).put(e).putInt(m.size).put(m).array()
    }
}
