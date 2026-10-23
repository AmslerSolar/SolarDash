// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.discovery

import ch.amslersolar.dashboard.driver.modbus.ModbusException
import ch.amslersolar.dashboard.model.DeviceInfo
import ch.amslersolar.dashboard.model.DeviceType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress

class DeviceScannerTest {
    private fun ip(s: String) = InetAddress.getByName(s) as Inet4Address

    @Test
    fun hostsOfTheSubnetWithoutOwnNetworkAndBroadcast() {
        val hosts = DeviceScanner.hosts(ip("192.168.0.22"), 24)
        assertEquals(253, hosts.size)
        assertEquals("192.168.0.1", hosts.first())
        assertEquals("192.168.0.254", hosts.last())
        assertFalse("192.168.0.22" in hosts)
        // Larger networks are limited to the phone's own /24.
        assertEquals(253, DeviceScanner.hosts(ip("10.1.2.3"), 16).size)
        assertEquals(listOf("10.0.0.1", "10.0.0.2"), DeviceScanner.hosts(ip("10.0.0.3"), 29).take(2))
    }

    @Test
    fun recognisesEachMakeOnItsPort() = runBlocking {
        val scanner = DeviceScanner(
            portOpen = { _, _ -> true },
            identify = { type, _, _, unit ->
                when (type) {
                    DeviceType.GOODWE_ET -> throw ModbusException(2) // not a GoodWe
                    DeviceType.HUAWEI -> if (unit == 1) DeviceInfo("SUN2000-10KTL-M1", "HV123") else throw IOException("timeout")
                    DeviceType.KOSTAL_PLENTICORE -> DeviceInfo("PLENTICORE plus 10", "9012")
                    DeviceType.LG_ESS_SUNSPEC -> DeviceInfo("LG Electronics ELEC (firmware 10.05)", "DE1234")
                    else -> error("unexpected")
                }
            },
        )
        val huawei = scanner.probe("192.168.0.5", 502)
        assertEquals(DeviceType.HUAWEI, huawei.type)
        assertEquals(1, huawei.unitId)
        assertEquals(DeviceType.KOSTAL_PLENTICORE, scanner.probe("192.168.0.6", 1502).type)
        assertEquals(DeviceType.LG_ESS_SUNSPEC, scanner.probe("192.168.0.33", 6502).type)
    }

    @Test
    fun otherSunSpecMakesAreOfferedAsGenericSunSpecAndNoiseIsNot() = runBlocking {
        val scanner = DeviceScanner(
            portOpen = { _, _ -> true },
            identify = { type, _, port, _ ->
                // SunSpec on 6502 from another make; register noise on 502.
                if (port == 6502 && type in listOf(DeviceType.LG_ESS_SUNSPEC, DeviceType.SUNSPEC)) DeviceInfo("Fronius Symo", "1")
                else DeviceInfo("\u0001\u0002", "")
            },
        )
        val sunspec = scanner.probe("192.168.0.7", 6502)
        assertEquals(DeviceType.SUNSPEC, sunspec.type)
        assertTrue(sunspec.model.contains("Fronius"))
        assertNull(scanner.probe("192.168.0.8", 502).type) // register noise isn't a model name
    }

    @Test
    fun configuredDevicesAreListedButNotProbed() = runBlocking {
        val probed = mutableListOf<String>()
        val scanner = DeviceScanner(
            portOpen = { h, p -> h == "192.168.0.33" && p == 6502 || h == "192.168.0.40" && p == 1502 },
            identify = { type, h, _, _ ->
                probed += h
                // Like the real drivers: the PIKO CI driver rejects a PLENTICORE's product name.
                if (type == DeviceType.KOSTAL_PIKO_CI) throw java.io.IOException("Not a KOSTAL PIKO CI")
                DeviceInfo("PLENTICORE plus 10", "1")
            },
        )
        val events = scanner.scan(listOf("192.168.0.33", "192.168.0.40"), mapOf("192.168.0.33:6502" to "Home · LG")).toList()
        val found = events.filterIsInstance<ScanEvent.Found>().map { it.device }
        assertEquals(setOf("192.168.0.40"), probed.toSet())
        assertEquals("Home · LG", found.single { it.host == "192.168.0.33" }.alreadyIn)
        assertEquals(DeviceType.KOSTAL_PLENTICORE, found.single { it.host == "192.168.0.40" }.type)
        val last = events.filterIsInstance<ScanEvent.Progress>().maxBy { it.done }
        val ports = DeviceScanner.CANDIDATES.size
        assertEquals(2 * ports, last.total)
        assertEquals(2 * ports, last.done)
    }

    @Test
    fun findsAMovedDeviceBySerialWithoutTouchingConfiguredOnes() = runBlocking {
        val asked = mutableListOf<String>()
        val serials = mapOf("192.168.0.50" to "OTHER", "192.168.0.61" to "9012ABC", "192.168.0.70" to "9012ABC")
        val scanner = DeviceScanner(
            portOpen = { h, p -> p == 1502 && h in serials },
            identify = { _, h, _, _ -> asked += h; DeviceInfo("PLENTICORE plus 10", serials.getValue(h)) },
        )
        val hosts = DeviceScanner.hosts(ip("192.168.0.22"), 24)
        // .70 is configured (another inverter of the plant): it must not be contacted.
        val found = scanner.find(hosts, setOf("192.168.0.70:1502"), DeviceType.KOSTAL_PLENTICORE, 1502, 71, "9012ABC")
        assertEquals("192.168.0.61", found)
        assertFalse("192.168.0.70" in asked)
        assertNull(scanner.find(hosts, emptySet(), DeviceType.KOSTAL_PLENTICORE, 1502, 71, "NOPE"))
    }

    /** As announced by the real LG ESS Home 10 on this network. */
    private val lgAnnouncement = MdnsService(
        "LGE_ESS-DE2002BKRE0138F7", "_sunspec._tcp", "192.168.0.33", 6502, mapOf("Device" to "LGEESS"),
    )

    @Test
    fun announcementsMentionTheirSerial() {
        assertTrue(lgAnnouncement.mentions("de2002bkre0138f7"))
        assertFalse(lgAnnouncement.mentions("DE2002BKRE0138F8"))
        assertFalse(lgAnnouncement.mentions("LG")) // too short to mean anything
    }

    @Test
    fun announcedDevicesAreReportedOnceAndMarked() = runBlocking {
        val scanner = DeviceScanner(
            portOpen = { h, p -> delay(100); h == "192.168.0.33" && p == 6502 },
            identify = { _, _, _, _ -> DeviceInfo("LG Electronics ELEC (firmware 10.05.7437)", "DE2002BKRE0138F7") },
            mdns = { flowOf(lgAnnouncement) },
        )
        val found = scanner.scan(listOf("192.168.0.33"), emptyMap()).toList().filterIsInstance<ScanEvent.Found>().map { it.device }
        val lg = found.single()
        assertEquals(DeviceType.LG_ESS_SUNSPEC, lg.type)
        assertTrue(lg.viaMdns)
    }

    @Test
    fun movedDeviceIsTakenFromItsAnnouncementWithoutConnecting() = runBlocking {
        var connects = 0
        val scanner = DeviceScanner(
            portOpen = { _, _ -> connects++; false },
            identify = { _, _, _, _ -> connects++; error("must not connect") },
        )
        val moved = lgAnnouncement.copy(host = "192.168.0.44")
        val found = scanner.find(listOf("192.168.0.44"), emptySet(), DeviceType.LG_ESS_SUNSPEC, 6502, 1, "DE2002BKRE0138F7", listOf(moved))
        assertEquals("192.168.0.44", found)
        assertEquals(0, connects)
    }

    @Test
    fun announcedDevicesWithoutSerialAreAskedBeforeTheSubnet() = runBlocking {
        val asked = mutableListOf<String>()
        val scanner = DeviceScanner(
            portOpen = { _, _ -> error("subnet search not needed") },
            identify = { _, h, _, _ -> asked += h; DeviceInfo("PLENTICORE plus 10", if (h == "192.168.0.61") "9012ABC" else "X") },
        )
        val announced = listOf(
            MdnsService("plenticore", "_sunspec._tcp", "192.168.0.60", 1502),
            MdnsService("plenticore-2", "_sunspec._tcp", "192.168.0.61", 1502),
            MdnsService("configured", "_sunspec._tcp", "192.168.0.70", 1502),
        )
        val found = scanner.find(emptyList(), setOf("192.168.0.70:1502"), DeviceType.KOSTAL_PLENTICORE, 1502, 71, "9012ABC", announced)
        assertEquals("192.168.0.61", found)
        assertFalse("192.168.0.70" in asked)
    }
}
