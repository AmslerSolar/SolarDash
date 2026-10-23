// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.consumer

import ch.amslersolar.dashboard.driver.modbus.ModbusException
import ch.amslersolar.dashboard.driver.modbus.RegisterBlock
import ch.amslersolar.dashboard.driver.sunspec.RegisterReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class NibeSDriverTest {
    /** Rejects reads touching undefined registers and queries over 20 registers, like the S-series. */
    private class FakeRegs : RegisterReader {
        val regs = HashMap<Int, Int>()
        val requests = mutableListOf<Pair<Int, Int>>()

        override suspend fun read(unitId: Int, register: Int, count: Int): RegisterBlock {
            assertEquals(1, unitId)
            assertTrue("query of $count registers", count <= 20)
            requests += register to count
            val data = ByteArray(count * 2)
            for (i in 0 until count) {
                val v = regs[register + i] ?: throw ModbusException(2)
                data[2 * i] = (v shr 8).toByte()
                data[2 * i + 1] = v.toByte()
            }
            return RegisterBlock(register, data)
        }
    }

    private class Nibe {
        val input = FakeRegs()
        val holding = FakeRegs()
        fun inp(a: Int, v: Int) = apply { input.regs[a] = v and 0xFFFF }
        fun temp(a: Int, c: Double) = inp(a, Math.round(c * 10).toInt())
        /** 32-bit, low word first. */
        fun inp32(a: Int, v: Long) = apply { input.regs[a] = (v and 0xFFFF).toInt(); input.regs[a + 1] = ((v shr 16) and 0xFFFF).toInt() }
        fun hold32(a: Int, v: Long) = apply { holding.regs[a] = (v and 0xFFFF).toInt(); holding.regs[a + 1] = ((v shr 16) and 0xFFFF).toInt() }
        fun hold(a: Int, v: Int) = apply { holding.regs[a] = v and 0xFFFF }

        fun driver() = NibeSDriver(input, holding, 1)
    }

    /** S1255 ground source heat pump, heating, 1.85 kW used; no outdoor-unit registers. */
    private fun s1255(priority: Int = 30) = Nibe().apply {
        for (a in 1..20) inp(a, 0x8000) // sensors present but not connected
        temp(1, -3.4).temp(5, 38.5).temp(7, 32.0).temp(8, 51.2).temp(9, 49.0).temp(10, 4.1).temp(11, 1.2)
        temp(26, 21.5).temp(39, 0.0).temp(40, 0.0)
        inp(1017, 390).inp32(1025, 1234).inp(1028, priority).inp(1029, 0)
        inp(1046, 523) // 52.3 Hz
        inp32(1083, 4321).inp32(1087, 9876)
        inp(1100, 1).inp(1101, 0).inp(1102, 64)
        inp32(1575, 123_456).inp32(1577, 987_654).inp32(1581, 0).inp32(1583, 100_000).inp32(1585, 800_000)
        inp(1975, 0).inp(2014, 450)
        inp32(2166, 18_500) // 1 850.0 W
        inp(2195, 0).inp(2196, 0)
        hold32(11, -2_500L and 0xFFFFFFFFL) // −250.0 degree minutes
        hold(237, 0)
    }

    @Test
    fun identifiesByPlausibility() = runBlocking {
        assertEquals("NIBE S-series heat pump", s1255().driver().identify().model)
    }

    @Test
    fun heating() = runBlocking {
        val r = s1255().driver().read()
        assertEquals("Heating", r.status)
        assertEquals(1_850, r.loadPowerW)
        assertEquals(-3.4, r.temperatureC!!, 1e-9)
        assertNull(r.today.loadKwh)
        assertNull(r.pvPowerW)
        assertNull(r.gridPowerW)
        assertNull(r.battery)
        val rows = r.details.flatMap { it.rows }.toMap()
        assertEquals("38.5 °C", rows["Supply (BT2)"])
        assertEquals("32.0 °C", rows["Return (BT3)"])
        assertEquals("51.2 °C", rows["Hot water top (BT7)"])
        assertEquals("4.1 °C", rows["Brine in (BT10)"])
        assertEquals("52.3 Hz", rows["Compressor frequency"])
        assertEquals("On (1)", rows["Compressor"])
        assertEquals("98765.4 kWh", rows["Heat produced, heating"]) // low word first, factor 10
        assertEquals("12345.6 kWh", rows["Heat produced, hot water"])
        assertEquals("4321", rows["Compressor starts"])
        assertEquals("9876 h", rows["Compressor operating time"])
        assertEquals("-250.0", rows["Degree minutes"])
        assertEquals("No alarm", rows["Alarm"])
        // Not connected (0x8000) and not on this model (rejected): left out.
        assertNull(rows["Exhaust air (BT20)"])
        assertNull(rows["Outdoor unit (BT28)"])
        assertNull(rows["Defrost"])
    }

    @Test
    fun hotWaterStandbyDefrostFault() = runBlocking {
        assertEquals("Hot water", s1255(priority = 20).driver().read().status)
        assertEquals("Standby", s1255(priority = 10).driver().read().status)
        assertEquals("Cooling", s1255(priority = 60).driver().read().status)
        // SMO S40 with S2125: defrost from the outdoor unit.
        val smo = s1255().inp(1803, 450).inp(1805, 1).temp(1621, -5.0)
        val r = smo.driver().read()
        assertEquals("Defrost", r.status)
        assertEquals("Active (1)", r.details.flatMap { it.rows }.toMap()["Defrost"])
        val alarm = s1255().inp(2195, 1).inp(1975, 183).driver().read()
        assertEquals("Fault", alarm.status)
        assertEquals("Active (alarm 183)", alarm.details.flatMap { it.rows }.toMap()["Alarm"])
    }

    @Test
    fun outdoorFallsBackToOutdoorUnit() = runBlocking {
        val r = s1255().inp(1, 0x8000).temp(1621, 7.5).driver().read()
        assertEquals(7.5, r.temperatureC!!, 1e-9)
    }

    @Test
    fun respectsQueryAndRateLimits() = runBlocking {
        val nibe = s1255()
        nibe.driver().read()
        val total = nibe.input.requests.sumOf { it.second } + nibe.holding.requests.sumOf { it.second }
        assertTrue("$total registers in one poll", total <= 100)
        assertTrue(nibe.input.requests.all { it.second <= 20 })
    }

    @Test
    fun throttlesAbove100RegistersPerSecond() = runBlocking {
        var now = 0L
        var slept = 0L
        val nibe = s1255()
        val log = mutableListOf<Pair<Long, Int>>() // (time, registers)
        val input = RegisterReader { u, r, c -> nibe.input.read(u, r, c).also { log += now to c } }
        val holding = RegisterReader { u, r, c -> nibe.holding.read(u, r, c).also { log += now to c } }
        val driver = NibeSDriver(input, holding, 1, clock = { now }, sleep = { slept += it; now += it })
        repeat(NibeSDriver.REFRESH_POLLS + 1) { driver.read() } // two full reads with no time passing in between
        assertTrue("should have paused", slept > 0)
        for ((t, _) in log) {
            val inWindow = log.filter { it.first in t until t + 1_000 }.sumOf { it.second }
            assertTrue("$inWindow registers within one second", inWindow <= 100)
        }
    }

    @Test
    fun cachesBetweenFullReads() = runBlocking {
        val nibe = s1255()
        val driver = nibe.driver()
        driver.read()
        val n = nibe.input.requests.size
        nibe.inp(1028, 20)
        assertEquals("Heating", driver.read().status)
        assertEquals(n, nibe.input.requests.size)
        assertEquals("Hot water", driver.read().status)
    }

    @Test
    fun rejectsOtherDevices() = runBlocking {
        assertThrows { Nibe().driver().identify() }
        // Answers FC04 but with values a NIBE can't have.
        val other = Nibe()
        for (a in 1..20) other.inp(a, 0)
        other.inp(1028, 7).inp(2195, 0)
        assertThrows { other.driver().identify() }
        val hot = s1255().temp(1, 120.0)
        assertThrows { hot.driver().identify() }
    }

    private fun assertThrows(block: suspend () -> Unit) = runBlocking {
        try {
            block()
            fail("Expected IOException")
        } catch (_: IOException) {
        }
    }
}
