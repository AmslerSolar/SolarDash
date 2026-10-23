// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.enphase

import ch.amslersolar.dashboard.driver.enphase.EnphaseFixtures.INFO_XML
import ch.amslersolar.dashboard.driver.enphase.EnphaseFixtures.INVERTERS_BRIEF
import ch.amslersolar.dashboard.driver.enphase.EnphaseFixtures.LIVEDATA_BRIEF
import ch.amslersolar.dashboard.driver.enphase.EnphaseFixtures.METERS_BRIEF
import ch.amslersolar.dashboard.driver.enphase.EnphaseFixtures.METERS_NONE
import ch.amslersolar.dashboard.driver.enphase.EnphaseFixtures.METERS_PRODUCTION_ONLY
import ch.amslersolar.dashboard.driver.enphase.EnphaseFixtures.READINGS_BRIEF
import ch.amslersolar.dashboard.driver.enphase.EnphaseFixtures.READINGS_EXPORTING
import ch.amslersolar.dashboard.driver.enphase.EnphaseFixtures.READINGS_PRODUCTION_ONLY
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class EnphaseGatewayDriverTest {
    /** A gateway answering from [responses]; missing paths are 404, as the real HTTP fetch maps them. */
    private class FakeGateway(val responses: Map<String, String>, val status: Int = 200) : HttpFetch {
        val calls = mutableListOf<String>()
        override suspend fun get(path: String): String {
            calls += path
            EnphaseGatewayDriver.checkStatus(path, if (status != 200) status else if (path in responses) 200 else 404)
            return responses.getValue(path)
        }
    }

    private fun gateway(vararg responses: Pair<String, String>) = FakeGateway(responses.toMap())

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw e
        }
        fail("expected ${T::class.simpleName}")
        error("unreachable")
    }

    @Test
    fun identifyFromMeterListWithSerialAndFirmware() = runBlocking {
        val info = EnphaseGatewayDriver(gateway("/ivp/meters" to METERS_BRIEF, "/info" to INFO_XML)).identify()
        assertEquals("Enphase IQ Gateway (firmware D7.6.175)", info.model)
        assertEquals("122212345678", info.serial)
    }

    @Test
    fun identifyWithoutInfoEndpoint() = runBlocking {
        val info = EnphaseGatewayDriver(gateway("/ivp/meters" to METERS_BRIEF)).identify()
        assertEquals("Enphase IQ Gateway", info.model)
        assertEquals("?", info.serial)
    }

    @Test
    fun identifyWithoutCtsFallsBackToInverterList() = runBlocking {
        val info = EnphaseGatewayDriver(gateway("/ivp/meters" to METERS_NONE, "/api/v1/production/inverters" to INVERTERS_BRIEF)).identify()
        assertEquals("Enphase IQ Gateway", info.model)
    }

    @Test
    fun identifyRejectsOtherDevices() {
        // Some other web server: HTML page, foreign JSON, or nothing at that path.
        for (body in listOf("<html><body>Router login</body></html>", """{"status":"ok"}""", """[{"id":1}]""")) {
            val e = assertThrows<IOException> { runBlocking { EnphaseGatewayDriver(gateway("/ivp/meters" to body)).identify() } }
            assertTrue(e.message, e.message!!.contains("Not an Enphase IQ Gateway"))
        }
        assertThrows<IOException> { runBlocking { EnphaseGatewayDriver(gateway()).identify() } }
        // An empty meter list with no inverter list isn't enough either.
        assertThrows<IOException> { runBlocking { EnphaseGatewayDriver(gateway("/ivp/meters" to "[]")).identify() } }
    }

    @Test
    fun productionAndNetConsumptionFromBriefSample() = runBlocking {
        val r = EnphaseGatewayDriver(
            gateway("/ivp/meters" to METERS_BRIEF, "/ivp/meters/readings" to READINGS_BRIEF, "/api/v1/production/inverters" to INVERTERS_BRIEF),
        ).read()
        assertEquals(132, r.pvPowerW) // production CT activePower, not the inverters' 5-minute sum (20 W)
        assertEquals(0, r.gridPowerW)
        assertEquals(132, r.loadPowerW)
        assertEquals(1608.426912, r.lifetimePvKwh!!, 1e-9)
        // phaseCount 2: the third, empty channel is dropped
        assertEquals(listOf("L1", "L2"), r.phases.map { it.name })
        assertEquals(123.152, r.phases[0].voltageV, 1e-9)
        assertEquals(0.137, r.phases[0].currentA!!, 1e-9)
        assertEquals(59.188, r.phases[0].frequencyHz, 1e-9)
        assertEquals(EnergyTotalsEmpty, r.today)
        assertEquals("Producing", r.status)
        val titles = r.details.map { it.title }
        assertTrue(titles.toString(), titles.containsAll(listOf("Meter: production", "Meter: net-consumption", "Microinverters (2)")))
        val inv = r.details.first { it.title.startsWith("Microinverters") }.rows.toMap()
        assertEquals("15 W, max 38 W, 2022-06-02T12:10:36Z", inv["121935144671"])
    }

    @Test
    fun exportingSignsAndBalance() = runBlocking {
        val r = EnphaseGatewayDriver(gateway("/ivp/meters" to METERS_BRIEF, "/ivp/meters/readings" to READINGS_EXPORTING)).read()
        assertEquals(2500, r.pvPowerW)
        assertEquals(1800, r.gridPowerW) // net-consumption -1800 W = exporting = positive in our convention
        assertEquals(700, r.loadPowerW)
        assertEquals(r.loadPowerW, r.pvPowerW!! - r.gridPowerW!!)
        assertEquals(listOf(1000, 800), r.phases.map { it.powerW })
        assertNull(r.battery)
    }

    @Test
    fun productionOnlySystem() = runBlocking {
        val r = EnphaseGatewayDriver(
            gateway("/ivp/meters" to METERS_PRODUCTION_ONLY, "/ivp/meters/readings" to READINGS_PRODUCTION_ONLY, "/api/v1/production/inverters" to INVERTERS_BRIEF),
        ).read()
        assertEquals(132, r.pvPowerW)
        assertNull(r.gridPowerW)
        assertNull(r.loadPowerW)
        assertEquals(1608.426912, r.lifetimePvKwh!!, 1e-9)
        assertEquals(listOf("L1", "L2"), r.phases.map { it.name })
        assertEquals(listOf<Int?>(null, null), r.phases.map { it.powerW }) // production CT: not grid power
    }

    @Test
    fun microinvertersOnlyWithoutCts() = runBlocking {
        val gw = gateway("/ivp/meters" to METERS_NONE, "/api/v1/production/inverters" to INVERTERS_BRIEF)
        var now = 0L
        val driver = EnphaseGatewayDriver(gw) { now }
        val r = driver.read()
        assertEquals(20, r.pvPowerW) // 15 W + 5 W
        assertNull(r.gridPowerW)
        assertNull(r.lifetimePvKwh)
        assertTrue("/ivp/meters/readings" !in gw.calls)
        // Inverter data only changes every 5 minutes (brief), so it's cached.
        now = 5_000
        driver.read()
        assertEquals(1, gw.calls.count { it == "/api/v1/production/inverters" })
        now = 5 * 60_000L
        driver.read()
        assertEquals(2, gw.calls.count { it == "/api/v1/production/inverters" })
    }

    @Test
    fun batteryFromLiveData() = runBlocking {
        val r = EnphaseGatewayDriver(
            gateway("/ivp/meters" to METERS_BRIEF, "/ivp/meters/readings" to READINGS_BRIEF, "/ivp/livedata/status" to LIVEDATA_BRIEF),
        ).read()
        assertEquals(-221, r.battery?.powerW) // -220.8 W: charging
        assertEquals(100, r.battery?.socPercent)
        assertEquals("Charging", r.battery?.mode)
        assertTrue(r.details.any { it.title == "Live data" })
    }

    @Test
    fun unauthorizedTokenGivesClearMessage() {
        for (status in listOf(401, 403, 302)) {
            val gw = FakeGateway(mapOf("/ivp/meters" to METERS_BRIEF), status)
            val e = assertThrows<EnphaseAuthException> { runBlocking { EnphaseGatewayDriver(gw).read() } }
            assertTrue(e.message, e.message!!.contains("token expired or missing"))
            val e2 = assertThrows<EnphaseAuthException> { runBlocking { EnphaseGatewayDriver(gw).identify() } }
            assertTrue(e2.message, e2.message!!.contains("token expired or missing"))
        }
    }

    @Test
    fun missingTokenFailsBeforeAnyConnection() {
        // 192.0.2.1 is TEST-NET-1; the empty token must be rejected before anything is sent.
        val e = assertThrows<EnphaseAuthException> { runBlocking { EnphaseGatewayDriver("192.0.2.1", token = "  ").identify() } }
        assertTrue(e.message, e.message!!.contains("token expired or missing"))
    }

    private companion object {
        val EnergyTotalsEmpty = ch.amslersolar.dashboard.model.EnergyTotals()
    }
}
