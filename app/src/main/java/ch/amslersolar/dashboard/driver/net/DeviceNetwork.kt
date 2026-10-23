// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.net

import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.net.URLConnection

/**
 * Opens connections to devices. Drivers never create sockets themselves, so the app can choose the network a
 * device is on: on Android the plant's Wi-Fi or Ethernet, also when it has no internet and the phone's default
 * network is mobile data (see ch.amslersolar.dashboard.net.LocalNetworks). The default uses the system's routing.
 */
interface DeviceNetwork {
    /** An unconnected socket for talking to [host]. */
    fun socket(host: String): Socket = Socket()

    fun openConnection(url: URL): URLConnection = url.openConnection()

    companion object {
        @Volatile var current: DeviceNetwork = object : DeviceNetwork {}
    }
}

/** An IPv4/IPv6 subnet of a local network: [address] with [prefixLength] network bits. */
data class Subnet(val address: InetAddress, val prefixLength: Int) {
    operator fun contains(target: InetAddress): Boolean {
        val a = address.address
        val b = target.address
        if (a.size != b.size || prefixLength !in 0..a.size * 8) return false
        val full = prefixLength / 8
        for (i in 0 until full) if (a[i] != b[i]) return false
        val rest = prefixLength % 8
        if (rest == 0) return true
        val mask = (0xFF shl (8 - rest)) and 0xFF
        return (a[full].toInt() and mask) == (b[full].toInt() and mask)
    }
}

/** The network among [networks] whose subnets contain [target]; the most specific subnet wins. */
fun <N> networkFor(target: InetAddress, networks: Map<N, List<Subnet>>): N? =
    networks.entries
        .mapNotNull { (n, subnets) -> subnets.filter { target in it }.maxOfOrNull { it.prefixLength }?.let { n to it } }
        .maxByOrNull { it.second }?.first

/** [host] as an address when it's written as one (no DNS lookup), else null. */
fun ipLiteral(host: String): InetAddress? {
    val h = host.trim().removePrefix("[").removeSuffix("]")
    val ipv4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""").matchEntire(h)
    if (ipv4 != null) {
        val bytes = ipv4.groupValues.drop(1).map { it.toInt() }
        if (bytes.any { it > 255 }) return null
        return InetAddress.getByAddress(bytes.map { it.toByte() }.toByteArray())
    }
    return if (':' in h && h.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' }) runCatching { InetAddress.getByName(h) }.getOrNull() else null
}
