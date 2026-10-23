// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.net

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceNetworkTest {
    private fun ip(s: String) = InetAddress.getByName(s)

    @Test
    fun subnetContainsOnlyItsAddresses() {
        val home = Subnet(ip("192.168.1.37"), 24)
        assertTrue(ip("192.168.1.20") in home)
        assertFalse(ip("192.168.2.20") in home)
        val odd = Subnet(ip("10.0.0.130"), 25) // 10.0.0.128–255
        assertTrue(ip("10.0.0.200") in odd)
        assertFalse(ip("10.0.0.100") in odd)
        assertFalse(ip("fe80::1") in home)
    }

    @Test
    fun picksTheWifiForLocalDevicesAndNothingForOthers() {
        val networks = mapOf(
            "wifi" to listOf(Subnet(ip("10.10.100.150"), 24), Subnet(ip("fe80::1"), 64)), // a gateway's own access point
            "ethernet" to listOf(Subnet(ip("192.168.0.5"), 16)),
            "inner" to listOf(Subnet(ip("192.168.7.1"), 24)),
        )
        assertEquals("wifi", networkFor(ip("10.10.100.254"), networks))
        assertEquals("inner", networkFor(ip("192.168.7.9"), networks)) // the more specific subnet wins
        assertEquals("ethernet", networkFor(ip("192.168.3.9"), networks))
        assertNull(networkFor(ip("8.8.8.8"), networks)) // e.g. a remote site: normal routing
    }

    @Test
    fun ipLiteralsWithoutDnsLookups() {
        assertEquals(ip("192.168.1.20"), ipLiteral("192.168.1.20"))
        assertEquals(ip("fe80::1"), ipLiteral("[fe80::1]"))
        assertNull(ipLiteral("envoy.local"))
        assertNull(ipLiteral("300.1.1.1"))
    }
}
