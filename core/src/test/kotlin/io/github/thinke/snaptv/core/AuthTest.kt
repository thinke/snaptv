package io.github.thinke.snaptv.core

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

    private fun header() = BaseHeader(MessageType.ERROR, 0, 0, 0, 0, 0)

    /** msg::Error::doserialize: uint32 code, then sized error and message strings. */
    private fun errorPayload(code: Int, error: String, message: String): ByteArray {
        val e = error.toByteArray(Charsets.UTF_8)
        val m = message.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(12 + e.size + m.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(code).putInt(e.size).put(e).putInt(m.size).put(m).array()
    }
}
