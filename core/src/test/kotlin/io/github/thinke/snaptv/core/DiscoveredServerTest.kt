package io.github.thinke.snaptv.core

import io.github.thinke.snaptv.core.transport.onNetwork
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class DiscoveredServerTest {
    private fun ip(s: String) = InetAddress.getByName(s) as Inet4Address

    @Test
    fun aServerOnOurLanIsOnOurNetwork() {
        assertTrue(onNetwork(ip("10.120.40.210"), ip("10.120.40.203"), 24))
        assertTrue(onNetwork(ip("192.168.1.250"), ip("192.168.0.7"), 23))
    }

    @Test
    fun itsDockerBridgeAddressIsNot() {
        // thinkedesktop announces 172.20.0.1 (a Docker bridge) next to its LAN address.
        assertFalse(onNetwork(ip("172.20.0.1"), ip("10.120.40.203"), 24))
        assertFalse(onNetwork(ip("10.120.41.5"), ip("10.120.40.203"), 24))
    }

    @Test
    fun edgePrefixes() {
        assertTrue(onNetwork(ip("8.8.8.8"), ip("10.0.0.1"), 0))
        assertTrue(onNetwork(ip("10.0.0.1"), ip("10.0.0.1"), 32))
        assertFalse(onNetwork(ip("10.0.0.2"), ip("10.0.0.1"), 32))
    }
}
