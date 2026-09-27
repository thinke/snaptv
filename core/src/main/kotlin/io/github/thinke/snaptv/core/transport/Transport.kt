package io.github.thinke.snaptv.core.transport

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * A connection carrying snapcast messages. [input] yields the server's messages as one byte
 * stream (over WebSocket each binary message holds exactly one snapcast message, so the
 * concatenation is the same stream plain TCP carries).
 */
interface Transport : Closeable {
    val input: InputStream

    /** Sends one complete snapcast message (base header + payload) as a unit. */
    fun send(message: ByteArray)

    val isClosed: Boolean

    companion object {
        /**
         * Connects to [address]. [onSocket] receives the raw TCP socket before it connects so a
         * caller on another thread can abort a slow connect or handshake by closing it.
         */
        fun connect(
            address: ServerAddress,
            tls: TlsOptions = TlsOptions.Default,
            connectTimeoutMs: Int = 5000,
            readTimeoutMs: Int = 15_000,
            onSocket: (Socket) -> Unit = {},
        ): Transport {
            val raw = Socket()
            onSocket(raw)
            try {
                raw.tcpNoDelay = true
                raw.connect(InetSocketAddress(address.host, address.port), connectTimeoutMs)
                raw.soTimeout = readTimeoutMs // server sends time replies every second; silence means dead
                return when (address.scheme) {
                    Scheme.TCP -> TcpTransport(raw)
                    Scheme.WS -> WebSocketTransport.open(raw, address)
                    Scheme.WSS -> WebSocketTransport.open(tls.wrap(raw, address.host, address.port), address)
                }
            } catch (e: Exception) {
                runCatching { raw.close() }
                throw e
            }
        }
    }
}

/** Snapcast's native transport: the messages back to back on a TCP socket. */
class TcpTransport(private val socket: Socket) : Transport {
    override val input: InputStream = BufferedInputStream(socket.getInputStream(), 64 * 1024)
    private val out = socket.getOutputStream()

    override fun send(message: ByteArray) {
        out.write(message)
        out.flush()
    }

    override val isClosed get() = socket.isClosed

    override fun close() = socket.close()
}

/**
 * Server certificate checks for wss.
 *
 * - Default: the platform trust store plus hostname verification, like any HTTPS client.
 * - [trustedCertificates]: trust only these (a self-signed server certificate or a private CA),
 *   without hostname checks. This is snapclient's `--server-cert <file.pem>`, whose verify
 *   callback accepts any chain OpenSSL validated against the file and never checks the name.
 * - [trustAll]: no verification. This is what snapclient does when `--server-cert` is not given
 *   (boost's default verify_none), so it may be needed to reach servers that work with it.
 *
 * snapclient's client certificate options (`--cert`, `--cert-key`) are not supported.
 */
data class TlsOptions(
    val trustedCertificates: List<X509Certificate> = emptyList(),
    val trustAll: Boolean = false,
) {
    internal fun wrap(raw: Socket, host: String, port: Int): SSLSocket {
        val s = socketFactory().createSocket(raw, host, port, true) as SSLSocket
        if (!trustAll && trustedCertificates.isEmpty()) {
            s.sslParameters = s.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        }
        s.startHandshake()
        return s
    }

    private fun socketFactory(): SSLSocketFactory {
        val trustManagers = when {
            trustAll -> arrayOf(TrustAll)
            trustedCertificates.isNotEmpty() -> {
                val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
                trustedCertificates.forEachIndexed { i, c -> ks.setCertificateEntry("snaptv-$i", c) }
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }.trustManagers
            }
            else -> return SSLSocketFactory.getDefault() as SSLSocketFactory
        }
        return SSLContext.getInstance("TLS").apply { init(null, trustManagers, null) }.socketFactory
    }

    private object TrustAll : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        val Default = TlsOptions()

        /** Certificates from PEM (one or more `BEGIN CERTIFICATE` blocks) or DER. */
        fun fromPem(input: InputStream): TlsOptions {
            val certs = CertificateFactory.getInstance("X.509").generateCertificates(input).map { it as X509Certificate }
            require(certs.isNotEmpty()) { "no certificate found" }
            return TlsOptions(trustedCertificates = certs)
        }
    }
}
