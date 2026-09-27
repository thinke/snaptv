package io.github.thinke.snaptv.core.transport

/**
 * How the binary stream protocol reaches the server. Defaults are snapclient's
 * (client/snapclient.cpp): tcp 1704 (stream server), ws 1780 and wss 1788 (the HTTP server's
 * `/stream` WebSocket endpoint, the latter only with `[http] ssl_enabled = true`).
 */
enum class Scheme(val id: String, val defaultPort: Int) {
    TCP("tcp", 1704),
    WS("ws", 1780),
    WSS("wss", 1788),
}

/**
 * A snapserver to connect to, as snapclient's `<tcp|ws|wss>://<host>[:port]` URL. User info
 * (`user:password@`) is kept because snapclient turns it into Hello auth; we don't send it yet.
 */
data class ServerAddress(
    val scheme: Scheme,
    val host: String,
    val port: Int = scheme.defaultPort,
    val user: String? = null,
    val password: String? = null,
) {
    /** `host:port` with IPv6 literals bracketed, as used in URLs and the WebSocket Host header. */
    val authority: String get() = "${if (':' in host) "[$host]" else host}:$port"

    override fun toString() = "${scheme.id}://$authority"

    companion object {
        /**
         * Parses `tcp://host:1704`, `ws://host`, `wss://host:1788` or a bare `host[:port]` (tcp,
         * [defaultTcpPort] when no port is given). A path, query or fragment is ignored: snapclient
         * always uses `/stream`. Throws [IllegalArgumentException] for anything else.
         */
        fun parse(text: String, defaultTcpPort: Int = Scheme.TCP.defaultPort): ServerAddress {
            val t = text.trim()
            val sep = t.indexOf("://")
            val scheme: Scheme
            var rest: String
            if (sep >= 0) {
                val id = t.substring(0, sep).lowercase()
                scheme = Scheme.entries.firstOrNull { it.id == id }
                    ?: throw IllegalArgumentException("unsupported scheme '$id', expected one of tcp, ws, wss")
                rest = t.substring(sep + 3)
            } else {
                scheme = Scheme.TCP
                rest = t
            }
            val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
            if (end >= 0) rest = rest.substring(0, end)

            var user: String? = null
            var password: String? = null
            val at = rest.lastIndexOf('@')
            if (at >= 0) {
                val info = rest.substring(0, at)
                rest = rest.substring(at + 1)
                user = decode(info.substringBefore(':'))
                password = if (':' in info) decode(info.substringAfter(':')) else null
            }

            val host: String
            val portText: String?
            if (rest.startsWith("[")) {
                val close = rest.indexOf(']')
                require(close > 0) { "unterminated IPv6 address in '$text'" }
                host = rest.substring(1, close)
                val tail = rest.substring(close + 1)
                require(tail.isEmpty() || tail.startsWith(":")) { "unexpected '$tail' after IPv6 address" }
                portText = tail.removePrefix(":").ifEmpty { null }
            } else if (rest.count { it == ':' } > 1) {
                host = rest // bare IPv6 literal, no port possible
                portText = null
            } else {
                host = rest.substringBefore(':')
                portText = if (':' in rest) rest.substringAfter(':') else null
            }
            require(host.isNotEmpty()) { "missing host in '$text'" }
            val port = when {
                portText != null -> portText.toIntOrNull()?.takeIf { it in 1..65535 }
                    ?: throw IllegalArgumentException("bad port '$portText'")
                sep < 0 -> defaultTcpPort
                else -> scheme.defaultPort
            }
            return ServerAddress(scheme, host, port, user, password)
        }

        /**
         * Splits a manually entered server into the (host, port) pair the app stores: a URL is
         * kept whole (its own or default port applies, the stored port is just the tcp default),
         * `host[:port]` is split. Throws [IllegalArgumentException] if [text] doesn't parse.
         */
        fun toStored(text: String): Pair<String, Int> {
            val a = parse(text)
            val t = text.trim()
            return if ("://" in t) t to Scheme.TCP.defaultPort else a.host to a.port
        }

        /**
         * The inverse of [toStored]. Also accepts what older settings screens stored for a URL,
         * split at its last ':' ("wss://h" + 8443): a URL without a port of its own then takes a
         * non-default stored [port].
         */
        fun fromStored(host: String, port: Int): ServerAddress {
            val a = parse(host, defaultTcpPort = port)
            if ("://" !in host || port == Scheme.TCP.defaultPort) return a
            // Fails (bad port) or ends up in a path if the URL already has a port of its own.
            val joined = runCatching { parse("${host.trim()}:$port") }.getOrNull()
            return if (joined?.port == port) joined else a
        }

        private fun decode(s: String) = java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    }
}
