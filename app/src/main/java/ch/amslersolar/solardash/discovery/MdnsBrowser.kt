// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.Inet6Address

/** A device announcing itself over mDNS / DNS-SD. */
data class MdnsService(
    val name: String,
    val type: String,
    val host: String,
    val port: Int,
    val txt: Map<String, String> = emptyMap(),
    /** The device's MAC, read from an announced IPv6 address made from it (EUI-64; Android 14 and newer only, see [MdnsBrowser.watch]). */
    val mac: String? = null,
) {
    /** Whether the announcement carries [serial] (in its name or a TXT value), e.g. LG's "LGE_ESS-<serial>". */
    fun mentions(serial: String): Boolean {
        val s = serial.trim()
        if (s.length < 4) return false
        return name.contains(s, ignoreCase = true) || txt.values.any { it.contains(s, ignoreCase = true) }
    }
}

/**
 * Listens for inverters that announce themselves. Seen on a real LG ESS Home 10: `_sunspec._tcp` on port 6502
 * named "LGE_ESS-<serial>", TXT "Device=LGEESS". An LG ESS with SunSpec switched off announces only
 * `_pmsctrl._tcp` (port 80, same name; seen 2026-11-07): listened for to learn its MAC, its device password.
 * `_modbus._tcp` is listened for in case other makes use it.
 * Holds a Wi-Fi multicast lock while browsing, since many phones drop multicast traffic otherwise.
 */
object MdnsBrowser {
    /** The LG ESS's own control service (not Modbus). */
    const val LG_CONTROL = "_pmsctrl._tcp"
    val SERVICE_TYPES = listOf("_sunspec._tcp.", "_modbus._tcp.", "$LG_CONTROL.")

    /** Resolved services as they're found, until the collector stops. */
    fun browse(context: Context, types: List<String> = SERVICE_TYPES): Flow<MdnsService> = callbackFlow {
        val nsd = context.getSystemService(NsdManager::class.java)
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("SolarDash:mdns").apply { setReferenceCounted(false); acquire() }
        // Older Android resolves one service at a time ("already active" otherwise).
        val resolving = Mutex()
        val watching = java.util.concurrent.CopyOnWriteArrayList<Any>()
        val listeners = types.map { type ->
            object : NsdManager.DiscoveryListener {
                override fun onServiceFound(info: NsdServiceInfo) {
                    if (Build.VERSION.SDK_INT >= 34) watching += watch(nsd, info, this@callbackFlow) { trySend(it) }
                    else launch { resolving.withLock { resolve(nsd, info)?.let { trySend(it) } } }
                }
                override fun onServiceLost(info: NsdServiceInfo) {}
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }.also { runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, it) } }
        }
        awaitClose {
            listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
            if (Build.VERSION.SDK_INT >= 34) watching.forEach { runCatching { nsd.unregisterServiceInfoCallback(it as NsdManager.ServiceInfoCallback) } }
            lock.release()
        }
    }

    /** Everything announced within [durationMs]. */
    suspend fun collect(context: Context, durationMs: Long): List<MdnsService> {
        val out = mutableListOf<MdnsService>()
        withTimeoutOrNull(durationMs) { browse(context).collect { s -> if (out.none { it.host == s.host && it.port == s.port }) out += s } }
        return out
    }

    @Suppress("DEPRECATION") // resolveService / host: Android before 14, see [watch]
    private suspend fun resolve(nsd: NsdManager, info: NsdServiceInfo): MdnsService? {
        val result = CompletableDeferred<NsdServiceInfo?>()
        runCatching {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onServiceResolved(resolved: NsdServiceInfo) { result.complete(resolved) }
                override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) { result.complete(null) }
            })
        }.onFailure { return null }
        val r = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { result.await() } ?: return null
        val host = r.host?.hostAddress ?: return null
        val txt = r.attributes.mapValues { (_, v) -> v?.toString(Charsets.UTF_8).orEmpty() }
        return MdnsService(r.serviceName, r.serviceType.trim('.'), host, r.port, txt)
    }

    /**
     * Android 14 and newer: follows the service instead of resolving it once. A device answering on IPv4 and IPv6
     * sends its IPv6 addresses separately (an LG ESS does), and a single resolve often returns only the IPv4 one, so
     * the MAC would be missing. Sends the service when its address is known, and again when a MAC turns up.
     *
     * The device is listed by its IPv4 address, as the port scan finds it. A device announcing only IPv6 addresses
     * is listed by one of those, once [IPV4_GRACE_MS] has passed without an IPv4 one (so a dual-stack device whose
     * IPv6 answer came first isn't listed twice).
     */
    @RequiresApi(34)
    private fun watch(nsd: NsdManager, info: NsdServiceInfo, scope: CoroutineScope, send: (MdnsService) -> Unit): NsdManager.ServiceInfoCallback {
        val lock = Any()
        var sent: MdnsService? = null
        var mac: String? = null
        var waiting: Job? = null
        fun offer(s: MdnsService) = synchronized(lock) {
            val last = sent
            if (last == null || last.host != s.host || last.port != s.port || (last.mac == null && s.mac != null)) { sent = s; send(s) }
        }
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceUpdated(r: NsdServiceInfo) {
                val v6 = r.hostAddresses.filterIsInstance<Inet6Address>()
                val txt = r.attributes.mapValues { (_, v) -> v?.toString(Charsets.UTF_8).orEmpty() }
                synchronized(lock) {
                    mac = v6.firstNotNullOfOrNull { macFromEui64(it.address) } ?: mac
                    val v4 = r.hostAddresses.firstOrNull { it is Inet4Address }?.hostAddress
                    val host = v4 ?: sent?.host
                    if (host != null) {
                        waiting?.cancel()
                        offer(MdnsService(r.serviceName, r.serviceType.trim('.'), host, r.port, txt, mac))
                    } else if (v6.isNotEmpty() && waiting == null) {
                        // Link-local addresses only as a last resort: they work on this network segment only.
                        val ip = (v6.firstOrNull { !it.isLinkLocalAddress } ?: v6.first()).hostAddress ?: return
                        waiting = scope.launch {
                            delay(IPV4_GRACE_MS)
                            synchronized(lock) { if (sent == null) offer(MdnsService(r.serviceName, r.serviceType.trim('.'), ip, r.port, txt, mac)) }
                        }
                    }
                }
            }
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {}
            override fun onServiceLost() {}
            override fun onServiceInfoCallbackUnregistered() {}
        }
        runCatching { nsd.registerServiceInfoCallback(info, { it.run() }, callback) }
        return callback
    }

    /**
     * The MAC inside an IPv6 address made from it (EUI-64): the last 8 bytes are the MAC with ff:fe in the middle
     * and the universal/local bit flipped. Seen on an LG ESS: …:da4f:b8ff:fe02:d6b is d8:4f:b8:02:0d:6b.
     * Null for addresses made otherwise (random or privacy addresses).
     */
    fun macFromEui64(address: ByteArray): String? {
        if (address.size != 16 || address[11] != 0xff.toByte() || address[12] != 0xfe.toByte()) return null
        val mac = byteArrayOf((address[8].toInt() xor 0x02).toByte(), address[9], address[10], address[13], address[14], address[15])
        return mac.joinToString(":") { "%02x".format(it.toInt() and 0xff) }
    }

    private const val RESOLVE_TIMEOUT_MS = 3_000L
    private const val IPV4_GRACE_MS = 2_000L
}
