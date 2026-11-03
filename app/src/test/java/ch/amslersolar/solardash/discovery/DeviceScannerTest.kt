// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.discovery

import ch.amslersolar.solardash.driver.abb.AbbCddFixtures
import ch.amslersolar.solardash.driver.abb.AuroraProtocol
import ch.amslersolar.solardash.driver.modbus.ModbusException
import ch.amslersolar.solardash.model.DeviceInfo
import ch.amslersolar.solardash.model.DeviceType
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
import java.net.ServerSocket
import kotlin.concurrent.thread

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
        val ports = DeviceScanner.EXTENDED_PORTS.size + DeviceScanner.WEB_PORTS.size
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
    fun normalSearchTries502AndTheWebPortsAndListsEveryOpenModbusPort() = runBlocking {
        val tried = mutableListOf<Int>()
        val scanner = DeviceScanner(
            portOpen = { h, p -> synchronized(tried) { tried += p }; h != "192.168.0.9" },
            identify = { _, h, _, _ -> if (h == "192.168.0.1") DeviceInfo("GoodWe GW10K-ET", "9010KETU000W0000") else throw java.io.IOException("no") },
            specIdentify = { h, p, _, _ -> if (h == "192.168.0.2") FoundDevice(h, p, DeviceType.MODBUS_SPEC_METER, 1, "Carlo Gavazzi EM24", specId = "carlo-gavazzi/em24") else null },
        )
        val events = scanner.scan(listOf("192.168.0.1", "192.168.0.2", "192.168.0.3", "192.168.0.9"), emptyMap()).toList()
        assertEquals(setOf(502, 80, 443), tried.toSet()) // 80, 443: only listed when recognised, see below
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

    /** An EMMA at 192.168.0.30 with an inverter on unit 2 and a wallbox on unit 4, as on a real one. */
    private val behindEmma: suspend (String, Int, String, Set<Int>) -> List<FoundDevice> = { h, p, hub, skip ->
        assertEquals("huawei-emma", hub)
        listOf(
            FoundDevice(h, p, DeviceType.HUAWEI, 2, "Huawei SUN2000-15K-MB0", "HV1"),
            FoundDevice(h, p, DeviceType.MODBUS_SPEC_CONSUMER, 4, "Huawei SCharger", "NS2", specId = "huawei-scharger"),
        ).filter { it.unitId !in skip }
    }

    @Test
    fun anEnergyManagerListsTheDevicesBehindIt() = runBlocking {
        val skipped = mutableListOf<Set<Int>>()
        val scanner = DeviceScanner(
            portOpen = { h, p -> h == "192.168.0.30" && p == 502 },
            identify = { _, _, _, _ -> throw java.io.IOException("no") },
            specIdentify = { h, p, _, _ -> FoundDevice(h, p, DeviceType.MODBUS_SPEC_INVERTER, 0, "Huawei EMMA", "NS0", specId = "huawei-emma") },
            behind = { h, p, hub, skip -> skipped += skip; behindEmma(h, p, hub, skip) },
        )
        val found = latest(scanner.scan(listOf("192.168.0.30"), emptyMap()).toList())
        assertEquals(listOf(setOf(0)), skipped) // the EMMA's own unit isn't asked again
        assertEquals(listOf(0, 2, 4), found.map { it.unitId }.sorted()) // three entries on one host:port
        assertEquals(3, found.map { it.key }.distinct().size)
        assertEquals("huawei-scharger", found.single { it.unitId == 4 }.specId)
        assertTrue(found.filter { it.unitId != 0 }.all { it.behindHub })
    }

    @Test
    fun aConfiguredEnergyManagerIsNotProbedButItsOtherUnitsAreListed() = runBlocking {
        val probed = mutableListOf<String>()
        val scanner = DeviceScanner(
            portOpen = { _, _ -> true },
            identify = { _, h, _, _ -> probed += h; throw java.io.IOException("no") },
            specIdentify = { _, _, _, _ -> error("a configured device must not be identified") },
            behind = behindEmma,
        )
        val known = mapOf("192.168.0.30:502" to "Home · EMMA")
        val events = scanner.scan(listOf("192.168.0.30"), known, hubs = mapOf("192.168.0.30:502" to ("huawei-emma" to setOf(0, 2)))).toList()
        val found = latest(events)
        assertTrue(probed.isEmpty())
        assertEquals("Home · EMMA", found.single { !it.behindHub }.alreadyIn)
        assertEquals(listOf(4), found.filter { it.behindHub }.map { it.unitId }) // the configured inverter isn't offered again
        val last = events.filterIsInstance<ScanEvent.Progress>().last()
        assertEquals(ScanPhase.IDENTIFY, last.phase)
        assertEquals(last.total, last.done)
    }

    @Test
    fun aGatewayWithAuroraInvertersListsEachAndSkipsModbus() = runBlocking {
        val scanner = DeviceScanner(
            portOpen = { _, p -> p == 4196 },
            identify = { _, _, _, _ -> error("the bus speaks Aurora: Modbus drivers aren't asked") },
            rollCall = { _, _, _, _ -> error("no Modbus roll call on an Aurora bus") },
            aurora = { h, p, onTry ->
                onTry()
                listOf(
                    FoundDevice(h, p, DeviceType.AURORA, 2, "ABB TRIO-27.6-TL-OUTD-400, firmware C.3.5.7", "301160"),
                    FoundDevice(h, p, DeviceType.AURORA, 3, "ABB Aurora inverter (model code 'n'), firmware C.0.2.6", "721318"),
                )
            },
        )
        val found = latest(scanner.scan(listOf("192.168.178.66"), emptyMap(), extended = true).toList())
        assertEquals(listOf(2, 3), found.map { it.unitId }.sorted())
        assertTrue(found.all { it.type == DeviceType.AURORA && it.port == 4196 && !it.identifying })
        assertFalse(found.single { it.unitId == 2 }.behindHub) // the first takes the gateway's host:port
        assertTrue(found.single { it.unitId == 3 }.behindHub)
    }

    @Test
    fun withoutAuroraAnswersTheGatewayIsAskedForModbus() = runBlocking {
        var rollCalls = 0
        val scanner = DeviceScanner(
            portOpen = { _, p -> p == 4196 },
            identify = { _, _, _, _ -> throw IOException("no") },
            rollCall = { _, _, _, _ -> rollCalls++; emptyMap() },
            aurora = { _, _, _ -> emptyList() },
        )
        scanner.scan(listOf("192.168.178.66"), emptyMap(), extended = true).toList()
        assertEquals(1, rollCalls)
    }

    /**
     * A transparent RS485 gateway with Aurora inverters at [addresses]: answers the commands identify() sends,
     * stays silent for other addresses. Returns the port and the addresses asked, in order.
     */
    private fun fakeAuroraGateway(addresses: Set<Int>): Pair<ServerSocket, MutableList<Int>> {
        val server = ServerSocket(0)
        val asked = java.util.Collections.synchronizedList(mutableListOf<Int>())
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (_: IOException) { break }
                thread(isDaemon = true) {
                    s.use {
                        val input = it.getInputStream()
                        val req = ByteArray(10)
                        while (true) {
                            var n = 0
                            while (n < 10) { val r = input.read(req, n, 10 - n); if (r < 0) return@use; n += r }
                            val address = req[0].toInt() and 0xFF
                            val command = req[1].toInt() and 0xFF
                            if (command == AuroraProtocol.SERIAL) asked += address
                            if (address !in addresses) continue
                            val data = when (command) {
                                AuroraProtocol.SERIAL -> "70000$address".toByteArray()
                                AuroraProtocol.VERSION -> byteArrayOf(0, 6, 'Y'.code.toByte(), 'E'.code.toByte(), 'N'.code.toByte(), 'N'.code.toByte())
                                else -> byteArrayOf(51, 6, 0, 0, 0, 0) // not implemented
                            }
                            val crc = AuroraProtocol.crc(data)
                            it.getOutputStream().write(data + byteArrayOf(crc.toByte(), (crc ushr 8).toByte()))
                        }
                    }
                }
            }
        }
        return server to asked
    }

    @Test
    fun auroraRollCallFindsEachInverterAndStopsAfterSilentAddresses() = runBlocking {
        val (server, asked) = fakeAuroraGateway(setOf(2, 3, 5))
        server.use {
            var tries = 0
            val found = DeviceScanner.auroraRollCall("127.0.0.1", it.localPort) { tries++ }
            assertEquals(listOf(2, 3, 5), found.map { d -> d.unitId })
            assertEquals(listOf("700002", "700003", "700005"), found.map { d -> d.serial })
            assertTrue(found.all { d -> d.type == DeviceType.AURORA && d.model == "ABB TRIO-27.6-TL-OUTD-400" })
            assertEquals((2..5 + DeviceScanner.AURORA_MAX_SILENT).toList(), asked) // stops 8 past the last one
            assertEquals(DeviceScanner.AURORA_ADDRESSES.count(), tries) // the counter still reaches its total
        }
    }

    @Test
    fun auroraRollCallOnASilentBusFindsNothingQuickly() = runBlocking {
        val (server, asked) = fakeAuroraGateway(emptySet())
        server.use {
            val t0 = System.currentTimeMillis()
            assertTrue(DeviceScanner.auroraRollCall("127.0.0.1", it.localPort).isEmpty())
            assertEquals(DeviceScanner.AURORA_MAX_SILENT, asked.size)
            assertTrue(System.currentTimeMillis() - t0 < 5_000)
        }
    }

    @Test
    fun aCddOnTheWebPortIsListedAndOtherWebServersAreNot() = runBlocking {
        val asked = mutableListOf<String>()
        val scanner = DeviceScanner(
            portOpen = { _, p -> p == 80 },
            identify = { _, _, _, _ -> error("no Modbus port is open") },
            web = { h, p -> synchronized(asked) { asked += h }; if (h == "192.168.178.8") FoundDevice(h, p, DeviceType.ABB_CDD, 1, "ABB CDD, 16 microinverters", "328100") else null },
        )
        val events = scanner.scan(listOf("192.168.178.1", "192.168.178.8", "192.168.178.36"), emptyMap()).toList()
        assertEquals(setOf("192.168.178.1", "192.168.178.8", "192.168.178.36"), asked.toSet())
        val found = latest(events).single() // the router and the other web page aren't listed
        assertEquals(DeviceType.ABB_CDD, found.type)
        assertEquals("192.168.178.8:80", found.key)
        assertFalse(found.identifying)
        val last = events.filterIsInstance<ScanEvent.Progress>().last()
        assertEquals(ScanPhase.IDENTIFY, last.phase)
        assertEquals(3, last.total)
        assertEquals(last.total, last.done)
    }

    @Test
    fun aConfiguredCddIsNotAskedAgain() = runBlocking {
        val scanner = DeviceScanner(
            portOpen = { _, p -> p == 80 },
            web = { _, _ -> error("a configured device must not be asked") },
        )
        val events = scanner.scan(listOf("192.168.178.8"), mapOf("192.168.178.8:80" to "ud60 · CDD")).toList()
        assertTrue(latest(events).isEmpty())
    }

    /** A minimal web server answering [paths] (path → body) and 404 for anything else, one request per connection. */
    private fun webServer(paths: Map<String, String>): ServerSocket = ServerSocket(0).also { server ->
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (_: IOException) { break }
                s.use {
                    val path = it.getInputStream().bufferedReader().readLine().orEmpty().split(' ').getOrElse(1) { "/" }
                    val body = paths[path]
                    val head = if (body != null) "HTTP/1.0 200 OK" else "HTTP/1.0 404 Not Found"
                    val bytes = (body ?: "").toByteArray()
                    it.getOutputStream().write("$head\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray() + bytes)
                }
            }
        }
    }

    @Test
    fun identifyWebRecognisesACddByItsSummary() = runBlocking {
        val cdd = webServer(mapOf("/summary.xml" to AbbCddFixtures.SUMMARY))
        val router = webServer(mapOf("/" to "<html>FRITZ!Box</html>"))
        val other = webServer(mapOf("/summary.xml" to "<html><body>Summary</body></html>"))
        try {
            val found = DeviceScanner.identifyWeb("127.0.0.1", cdd.localPort)
            assertEquals(DeviceType.ABB_CDD, found?.type)
            assertEquals("300001", found?.serial)
            assertEquals("ABB CDD, 5 microinverters", found?.model)
            assertNull(DeviceScanner.identifyWeb("127.0.0.1", router.localPort))
            assertNull(DeviceScanner.identifyWeb("127.0.0.1", other.localPort))
        } finally {
            listOf(cdd, router, other).forEach { it.close() }
        }
    }

    @Test
    fun lgEssIsRecognisedByItsSharedCertificate() {
        // The subject of the certificate on a real Home 10 (192.168.0.33:443), as Java writes it.
        val lg = "1.2.840.113549.1.9.1=#16126e657276752e657373406c67652e636f6d,CN=localhost,OU=ESS,O=LGE,L=Incheon,ST=Incheon,C=KR"
        assertTrue(DeviceScanner.isLgEssSubject(lg))
        assertFalse(DeviceScanner.isLgEssSubject("CN=fritz.box,O=AVM"))
        assertFalse(DeviceScanner.isLgEssSubject("CN=localhost,O=LGE"))
        assertFalse(DeviceScanner.isLgEssCertificate(null))
    }

    @Test
    fun theMacIsReadFromAnEui64Address() {
        // The IPv6 address a real LG ESS announces; its MAC (from the PC's ARP table) is d8:4f:b8:02:0d:6b.
        val lg = java.net.InetAddress.getByName("fdaa:bbcc:ddee:0:da4f:b8ff:fe02:d6b").address
        assertEquals("d8:4f:b8:02:0d:6b", MdnsBrowser.macFromEui64(lg))
        assertNull(MdnsBrowser.macFromEui64(java.net.InetAddress.getByName("fdaa:bbcc:ddee:0:1c3a:9e21:5b7f:42d0").address))
        assertNull(MdnsBrowser.macFromEui64(byteArrayOf(192.toByte(), 168.toByte(), 0, 33)))
    }

    @Test
    fun anLgEssFoundOnItsWebPortGetsItsMacAsPassword() = runBlocking {
        val scanner = DeviceScanner(
            portOpen = { _, p -> p == 443 },
            identify = { _, _, _, _ -> throw java.io.IOException("no") },
            mdns = { flowOf(lgAnnouncement.copy(mac = "d8:4f:b8:02:0d:6b")) },
            web = { h, p -> if (h == "192.168.0.33") FoundDevice(h, p, DeviceType.LG_ESS, 1, "LG ESS") else null },
        )
        val found = latest(scanner.scan(listOf("192.168.0.33", "192.168.0.40"), emptyMap()).toList()).single { it.type == DeviceType.LG_ESS }
        assertEquals("d84fb8020d6b", found.password)
    }
}
