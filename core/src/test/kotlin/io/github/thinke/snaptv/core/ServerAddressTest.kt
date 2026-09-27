package io.github.thinke.snaptv.core

import io.github.thinke.snaptv.core.transport.Scheme
import io.github.thinke.snaptv.core.transport.ServerAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerAddressTest {
    private fun p(s: String, tcpPort: Int = 1704) = ServerAddress.parse(s, tcpPort)

    @Test
    fun bareHostIsTcp() {
        assertEquals(ServerAddress(Scheme.TCP, "10.0.0.1", 1704), p("10.0.0.1"))
        assertEquals(ServerAddress(Scheme.TCP, "snap.local", 1234), p(" snap.local:1234 "))
        assertEquals(ServerAddress(Scheme.TCP, "snap.local", 4953), p("snap.local", tcpPort = 4953))
    }

    @Test
    fun schemesUseSnapclientDefaultPorts() {
        assertEquals(ServerAddress(Scheme.TCP, "h", 1704), p("tcp://h"))
        assertEquals(ServerAddress(Scheme.WS, "h", 1780), p("ws://h"))
        assertEquals(ServerAddress(Scheme.WSS, "h", 1788), p("wss://h"))
        // The tcp fallback port only applies to bare hosts.
        assertEquals(ServerAddress(Scheme.WS, "h", 1780), p("ws://h", tcpPort = 9999))
        assertEquals(ServerAddress(Scheme.TCP, "h", 1704), p("tcp://h", tcpPort = 9999))
    }

    @Test
    fun explicitPortPathAndCase() {
        assertEquals(ServerAddress(Scheme.WS, "h", 8080), p("WS://h:8080/stream?x=1#f"))
        assertEquals(ServerAddress(Scheme.WSS, "h", 443), p("wss://h:443/"))
    }

    @Test
    fun ipv6() {
        assertEquals(ServerAddress(Scheme.WS, "fe80::1", 1780), p("ws://[fe80::1]"))
        assertEquals(ServerAddress(Scheme.TCP, "::1", 1705), p("[::1]:1705"))
        assertEquals(ServerAddress(Scheme.TCP, "fe80::1", 1704), p("fe80::1"))
        assertEquals("ws://[fe80::1]:1780", p("ws://[fe80::1]").toString())
        assertEquals("[fe80::1]:1780", p("ws://[fe80::1]").authority)
    }

    @Test
    fun userInfo() {
        val a = p("ws://me:p%40ss@h:1780")
        assertEquals("h", a.host)
        assertEquals("me", a.user)
        assertEquals("p@ss", a.password)
        assertNull(p("ws://h").user)
    }

    @Test
    fun rejectsGarbage() {
        for (bad in listOf("http://h", "ws://", "ws://h:0", "ws://h:70000", "ws://h:abc", "[::1", "", "tcp://:1704")) {
            assertThrows(bad, IllegalArgumentException::class.java) { p(bad) }
        }
    }

    @Test
    fun toStringRoundTrips() {
        for (s in listOf("tcp://h:1704", "ws://10.0.0.2:1780", "wss://snap.example:1788")) assertEquals(s, p(s).toString())
    }
}
