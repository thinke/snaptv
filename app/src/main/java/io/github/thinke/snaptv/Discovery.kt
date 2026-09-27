package io.github.thinke.snaptv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.Inet4Address
import java.util.concurrent.Executors

data class DiscoveredServer(val name: String, val host: String, val port: Int)

/** Browses mDNS for snapservers (`_snapcast._tcp`, advertised by snapserver via avahi). */
class Discovery(private val context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    fun servers(): Flow<List<DiscoveredServer>> = callbackFlow {
        val found = LinkedHashMap<String, DiscoveredServer>()
        // Some TV Wi-Fi drivers drop multicast unless someone holds this lock.
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("snaptv-discovery").apply { setReferenceCounted(false); acquire() }
        val resolveExecutor = Executors.newSingleThreadExecutor()

        // NSD callbacks and our resolver run on different threads.
        fun publish() {
            trySend(synchronized(found) { found.values.toList() })
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onServiceFound(info: NsdServiceInfo) {
                resolveExecutor.execute { resolve(info) { s -> synchronized(found) { found[info.serviceName] = s }; publish() } }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                if (synchronized(found) { found.remove(info.serviceName) } != null) publish()
            }
        }

        publish()
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose {
            runCatching { nsd.stopServiceDiscovery(listener) }
            resolveExecutor.shutdownNow()
            lock.release()
        }
    }

    @Suppress("DEPRECATION")
    private fun resolve(info: NsdServiceInfo, onResolved: (DiscoveredServer) -> Unit) {
        val done = java.util.concurrent.CountDownLatch(1)
        // resolveService only allows one resolve at a time on older releases; we serialise.
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                done.countDown()
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val address = if (Build.VERSION.SDK_INT >= 34) {
                    serviceInfo.hostAddresses.firstOrNull { it is Inet4Address } ?: serviceInfo.hostAddresses.firstOrNull()
                } else {
                    serviceInfo.host
                }
                address?.hostAddress?.let { onResolved(DiscoveredServer(serviceInfo.serviceName, it, serviceInfo.port)) }
                done.countDown()
            }
        })
        try {
            done.await(5, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            // Discovery was cancelled (e.g. we already found a server); just stop.
        }
    }

    private companion object {
        const val TAG = "SnapTV.Discovery"
        const val SERVICE_TYPE = "_snapcast._tcp."
    }
}
