// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import ch.amslersolar.dashboard.driver.net.DeviceNetwork
import ch.amslersolar.dashboard.driver.net.Subnet
import ch.amslersolar.dashboard.driver.net.ipLiteral
import ch.amslersolar.dashboard.driver.net.networkFor
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.net.URLConnection
import java.util.concurrent.ConcurrentHashMap

/**
 * The phone's local networks (Wi-Fi, Ethernet), including ones without internet access.
 *
 * Off-grid plants often have Wi-Fi without internet, sometimes only the gateway's own access point. Android then
 * keeps such a network but makes mobile data the default, and connections that aren't bound to the Wi-Fi go out
 * over mobile data and never reach the device. So:
 * - the app requests Wi-Fi and Ethernet without the "internet" capability, which also keeps Android from dropping
 *   a Wi-Fi that has no internet while the app runs;
 * - each device connection is bound to the local network whose subnet contains the device's address; other
 *   addresses (e.g. a device at another site, reached through a VPN) use the normal routing.
 */
class LocalNetworks(context: Context) : DeviceNetwork {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val links = ConcurrentHashMap<Network, LinkProperties>()

    init {
        for (transport in listOf(NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_ETHERNET)) {
            val request = NetworkRequest.Builder()
                .addTransportType(transport)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            runCatching { cm.requestNetwork(request, Tracker()) }
        }
    }

    private inner class Tracker : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            cm.getLinkProperties(network)?.let { links[network] = it }
        }
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            links[network] = linkProperties
        }
        override fun onLost(network: Network) {
            links.remove(network)
        }
    }

    private fun subnets(): Map<Network, List<Subnet>> =
        links.mapValues { (_, p) -> p.linkAddresses.map { Subnet(it.address, it.prefixLength) } }

    /** The local network [host] is on, if any. Names are looked up on the local network first (e.g. "envoy.local"). */
    private fun networkFor(host: String): Network? {
        val nets = subnets()
        if (nets.isEmpty()) return null
        ipLiteral(host)?.let { return networkFor(it, nets) }
        for (n in nets.keys) {
            val address = runCatching { n.getAllByName(host).toList() }.getOrNull().orEmpty()
            if (address.any { networkFor(it, nets) == n }) return n
        }
        return null
    }

    override fun socket(host: String): Socket = Socket().also { s -> networkFor(host)?.bindSocket(s) }

    override fun openConnection(url: URL): URLConnection = networkFor(url.host)?.openConnection(url) ?: url.openConnection()

    /** This phone's IPv4 address and prefix on its local network, for the device search (not the mobile network). */
    fun ownIpv4(): Pair<Inet4Address, Int>? {
        val candidates = links.values.ifEmpty { listOfNotNull(cm.getLinkProperties(cm.activeNetwork)) }
        return candidates.firstNotNullOfOrNull { p ->
            p.linkAddresses.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
                ?.let { it.address as Inet4Address to it.prefixLength }
        }
    }

    /** True when [address] is on one of the local networks. */
    fun isLocal(address: InetAddress): Boolean = networkFor(address, subnets()) != null

    companion object {
        @Volatile private var instance: LocalNetworks? = null

        fun get(context: Context): LocalNetworks =
            instance ?: synchronized(this) { instance ?: LocalNetworks(context.applicationContext).also { instance = it } }
    }
}
