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
        val events = scanner.scan(listOf("192.168.0.33", "192.168.0.40"), mapOf("192.168.0.33:6502" to "Home · LG"), extended = true).toList()
        val found = latest(events)
        assertEquals(setOf("192.168.0.40"), probed.toSet())
        assertEquals("Home · LG", found.single { it.host == "192.168.0.33" }.alreadyIn)
        assertEquals(DeviceType.KOSTAL_PLENTICORE, found.single { it.host == "192.168.0.40" }.type)
        val last = events.filterIsInstance<ScanEvent.Progress>().filter { it.phase == ScanPhase.PORTS }.maxBy { it.done }
        val ports = DeviceScanner.EXTENDED_PORTS.size
        assertEquals(2 * ports, last.total)
        assertEquals(2 * ports, last.done)
    }

    /** The last event per host:port: what the list finally shows. */
    private fun latest(events: List<ScanEvent>) =
        events.filterIsInstance<ScanEvent.Found>().map { it.device }.associateBy { it.key }.values.toList()

    @Test
    fun identifyingCountsTheDescriptionsTried() = runBlocking {
        val scanner = DeviceScanner(
            portOpen = { _, _ -> true },
            identify = { _, _, _, _ -> throw java.io.IOException("no") },
            specIdentify = { h, p, _, onTry -> repeat(5) { onTry() }; FoundDevice(h, p, DeviceType.MODBUS_SPEC_METER, 11, "Huawei DTSU666-H") },
            specCount = { _, _ -> 5 },
        )
        val events = scanner.scan(listOf("192.168.0.1"), emptyMap()).toList()
        val counts = events.filterIsInstance<ScanEvent.Found>().map { it.device }.filter { it.identifying && it.toTry > 0 }
        val total = DeviceScanner.CANDIDATES.getValue(502).size + 5
        assertEquals((1..total).toList(), counts.map { it.tried })
        assertTrue(counts.all { it.toTry == total })
        assertEquals("Huawei DTSU666-H", latest(events).single().model) // the result replaces the counter
    }

    @Test
    fun behindAGatewayOnlyTheUnitsThatAnswerAreIdentified() = runBlocking {
        val driversTried = mutableListOf<Int>()
        var seenLive: Map<Int, Set<Boolean>>? = null
        val scanner = DeviceScanner(
            portOpen = { _, p -> p == 4196 },
            identify = { _, _, _, unit -> synchronized(driversTried) { driversTried += unit }; throw java.io.IOException("no") },
            specIdentify = { h, p, live, _ -> seenLive = live; FoundDevice(h, p, DeviceType.MODBUS_SPEC_METER, 11, "Huawei DTSU666-H", rtuOverTcp = true) },
            specCount = { _, live -> if (live == null) 214 else 2 },
            specUnits = { listOf(1, 11, 247) },
            // only unit 11 answers, and only to RTU frames (a gateway in transparent mode)
            rollCall = { _, _, units, onTry -> units.forEach { _ -> onTry(); onTry() }; mapOf(11 to setOf(true)) },
        )
        val events = scanner.scan(listOf("192.168.0.200"), emptyMap(), extended = true).toList()
        val result = latest(events).single()
        assertEquals("Huawei DTSU666-H", result.model)
        assertTrue(result.rtuOverTcp)
        assertEquals(mapOf(11 to setOf(true)), seenLive)
        assertTrue(driversTried.isEmpty()) // no unit answered Modbus TCP frames: the drivers aren't asked
        val counts = events.filterIsInstance<ScanEvent.Found>().map { it.device }.filter { it.identifying && it.toTry > 0 }
        assertTrue(counts.isNotEmpty() && counts.last().tried <= counts.last().toTry)
    }

    @Test
    fun normalSearchTriesOnly502AndListsEveryOpenPort() = runBlocking {
        val tried = mutableListOf<Int>()
        val scanner = DeviceScanner(
            portOpen = { h, p -> synchronized(tried) { tried += p }; h != "192.168.0.9" },
            identify = { _, h, _, _ -> if (h == "192.168.0.1") DeviceInfo("GoodWe GW10K-ET", "9010KETU000W0000") else throw java.io.IOException("no") },
            specIdentify = { h, p, _, _ -> if (h == "192.168.0.2") FoundDevice(h, p, DeviceType.MODBUS_SPEC_METER, 1, "Carlo Gavazzi EM24", specId = "carlo-gavazzi/em24") else null },
        )
        val events = scanner.scan(listOf("192.168.0.1", "192.168.0.2", "192.168.0.3", "192.168.0.9"), emptyMap()).toList()
        assertEquals(setOf(502), tried.toSet())
        // Each open port is listed at once, then identified.
        val first = events.filterIsInstance<ScanEvent.Found>().take(3).map { it.device }
        assertTrue(first.all { it.identifying })
        val found = latest(events).associateBy { it.host }
        assertEquals(3, found.size)
        assertEquals(DeviceType.GOODWE_ET, found.getValue("192.168.0.1").type)
        assertEquals("carlo-gavazzi/em24", found.getValue("192.168.0.2").specId)
        assertNull(found.getValue("192.168.0.3").type) // open, not recognised, still listed
        assertFalse(found.values.any { it.identifying })
    }

    @Test
    fun aSunSpecDeviceIsNamedByItsModbusSpecFileWhenOneKnowsIt() = runBlocking {
        val scanner = DeviceScanner(
            portOpen = { _, p -> p == 502 },
            identify = { type, _, _, _ -> if (type == DeviceType.SUNSPEC) DeviceInfo("SCHNEIDER Conext CL", "ZX1") else throw java.io.IOException("no") },
            specIdentify = { h, p, _, _ -> FoundDevice(h, p, DeviceType.MODBUS_SPEC_INVERTER, 10, "Schneider Conext CL", "ZX1", specId = "schneider-conext-cl") },
        )
        val found = latest(scanner.scan(listOf("192.168.0.5"), emptyMap()).toList()).single()
        assertEquals("schneider-conext-cl", found.specId)
    }

    @Test
    fun aSilentUnitBehindAGatewayIsAskedOnce() = runBlocking {
        val asked = mutableListOf<Pair<DeviceType, Int>>()
        val scanner = DeviceScanner(
            portOpen = { _, _ -> true },
            identify = { type, _, _, unit -> asked += type to unit; throw java.net.SocketTimeoutException("no answer") },
        )
        scanner.probe("192.168.0.200", 4196, DeviceScanner.CANDIDATES.values.flatten())
        assertEquals(asked.map { it.second }.distinct(), asked.map { it.second }) // one try per unit id
        asked.clear()
        scanner.probe("192.168.0.201", 502) // a known port: every driver still gets its try
        assertEquals(DeviceScanner.CANDIDATES.getValue(502).size, asked.size)
    }

    @Test
    fun aTypedNetworkGivesItsAddresses() {
        assertEquals(254, DeviceScanner.parseRange("192.168.1")!!.size)
        assertEquals("192.168.1.1", DeviceScanner.parseRange("192.168.1.0/24")!!.first())
        assertEquals("192.168.1.254", DeviceScanner.parseRange(" 192.168.1.77/24 ")!!.last())
        assertEquals(listOf("192.168.1.200"), DeviceScanner.parseRange("192.168.1.200"))
        assertEquals(listOf("192.168.1.201", "192.168.1.202"), DeviceScanner.parseRange("192.168.1.200/30"))
        for (bad in listOf("", "192.168", "192.168.1.0/16", "192.168.1.300", "gateway", "192.168.1.0/x")) {
            assertNull(bad, DeviceScanner.parseRange(bad))
        }
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
        val found = latest(scanner.scan(listOf("192.168.0.33"), emptyMap()).toList())
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
