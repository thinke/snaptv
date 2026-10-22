package io.github.thinke.snaptv.core.transport

import java.net.Inet4Address
import java.net.InterfaceAddress
import java.net.NetworkInterface

/**
 * A snapserver announced over mDNS (`_snapcast._tcp`). Both apps list these in Settings to pick
 * from; picking one stores its address, like typing it in. [name] is what mDNS calls it.
 */
data class DiscoveredServer(val name: String, val host: String, val port: Int) {
    val address get() = ServerAddress(Scheme.TCP, host, port)
}

/** Docker/Podman/libvirt bridges: a server on this machine answers there with an address that works only here. */
private val VIRTUAL_BRIDGES = listOf("docker", "br-", "veth", "virbr", "podman", "cni")

/** This machine's IPv4 networks, without loopback and virtual bridges. */
fun localNetworks(): List<InterfaceAddress> = NetworkInterface.getNetworkInterfaces().toList()
    .filter { it.isUp && !it.isLoopback && VIRTUAL_BRIDGES.none { p -> it.name.startsWith(p) } }
    .flatMap { it.interfaceAddresses }
    .filter { it.address is Inet4Address }

/**
 * Of the IPv4 addresses a server announces, one on one of [networks]: mDNS hosts announce all
 * their addresses on every network, Docker bridges too, and those can't be reached from here.
 */
fun reachableAddress(announced: List<Inet4Address>, networks: List<InterfaceAddress>): Inet4Address? =
    announced.firstOrNull { a -> networks.any { onNetwork(a, it.address, it.networkPrefixLength.toInt()) } }

/** Whether [a] is on the network of [address]/[prefix]. */
fun onNetwork(a: Inet4Address, address: java.net.InetAddress, prefix: Int): Boolean {
    fun bits(x: java.net.InetAddress) = x.address.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }
    val mask = if (prefix == 0) 0L else (0xffffffffL shl (32 - prefix)) and 0xffffffffL
    return bits(a) and mask == bits(address) and mask
}
