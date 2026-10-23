// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.consumer

import ch.amslersolar.dashboard.driver.modbus.ModbusException
import ch.amslersolar.dashboard.driver.modbus.RegisterBlock
import ch.amslersolar.dashboard.driver.sunspec.RegisterReader
import ch.amslersolar.dashboard.model.DeviceProblem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class HovalTopTronicDriverTest {
    /** Gateway image: undefined registers are rejected, [timeouts] don't answer (sensor not configured). */
    private class FakeGateway : RegisterReader {
        val regs = HashMap<Int, Int>()
        val timeouts = mutableSetOf<Int>()
        var requests = 0

        fun u16(a: Int, v: Int) = apply { regs[a] = v and 0xFFFF }
        fun tenths(a: Int, c: Double) = u16(a, Math.round(c * 10).toInt())
        fun u32(a: Int, v: Long) = apply { regs[a] = ((v shr 16) and 0xFFFF).toInt(); regs[a + 1] = (v and 0xFFFF).toInt() }

        override suspend fun read(unitId: Int, register: Int, count: Int): RegisterBlock {
            assertEquals(1, unitId)
            requests++
            if ((register until register + count).any { it in timeouts }) throw SocketTimeoutException("Read timed out")
            val data = ByteArray(count * 2)
            for (i in 0 until count) {
                val v = regs[register + i] ?: throw ModbusException(2)
                data[2 * i] = (v shr 8).toByte()
                data[2 * i + 1] = v.toByte()
            }
            return RegisterBlock(register, data)
        }
    }

    /** UltraSource heating: 1.85 kW electrical, 7 kW heat, COP 3.8. */
    private fun ultraSource(wfa: Int = 1, generator: Int = 1) = FakeGateway().apply {
        tenths(1477, -2.5)
        for (a in 1499..1504) u16(a, 0)
        tenths(1499, 50.0).tenths(1500, 47.5).u16(1501, 1).u16(1504, 1)
        for (a in 1513..1519) u16(a, 0)
        tenths(1513, 34.0).u32(1516, 12_345).u32(1518, 6_789)
        u16(1534, 255).tenths(1535, 29.5).u16(1561, 1)
        for (a in 18722..18737) u16(a, 0)
        u16(18722, generator).u16(18723, wfa).tenths(18724, 35.0).tenths(18725, 34.6).u16(18726, 55).u16(18737, 1)
        u32(18731, 70) // 7.0 kW current output heating
        u16(25611, 185) // 1.85 kW, 2 decimals
        u16(25612, 7) // 7 kW
        u32(25613, 4_200) // 4.200 MWh = 4 200 kWh
        u32(27484, 12_600).u32(27486, 0).u32(27488, 2_100).u16(27490, 38)
        u16(27467, 41)
        tenths(27494, 36.0)
        tenths(31708, 18.5)
        timeouts += 27483 // SF2 not configured
    }

    @Test
    fun identifiesByPlausibility() = runBlocking {
        assertEquals("Hoval heat pump (TopTronic E, WFA)", HovalTopTronicDriver(ultraSource(), 1).identify().model)
    }

    @Test
    fun heating() = runBlocking {
        val r = HovalTopTronicDriver(ultraSource(), 1).read()
        assertEquals("Heating", r.status)
        assertEquals(1_850, r.loadPowerW)
        assertEquals(-2.5, r.temperatureC!!, 1e-9)
        assertNull(r.today.loadKwh)
        assertNull(r.pvPowerW)
        assertNull(r.gridPowerW)
        assertNull(r.battery)
        val rows = r.details.flatMap { it.rows }.toMap()
        assertEquals("34.6 °C", rows["Flow"])
        assertEquals("29.5 °C", rows["Return"])
        assertEquals("47.5 °C", rows["Hot water top (SF)"])
        assertEquals("36.0 °C", rows["Buffer (WPF)"])
        assertEquals("3.8", rows["COP"])
        assertEquals("1.85 kW", rows["Electrical power"])
        assertEquals("7 kW", rows["Heating power"])
        assertEquals("4200 kWh", rows["Electrical energy total"])
        assertEquals("12600 kWh", rows["Heat heating total"])
        assertEquals("3.50", rows["COP total (calculated)"]) // (12 600 + 2 100) / 4 200
        assertEquals("12345 h", rows["Operating hours"])
        assertEquals("No error", rows["Heat pump error"])
        assertEquals("18.5 l/min", rows["Volume flow"])
        assertNull(rows["Hot water bottom (SF2)"]) // timed out: not installed
    }

    @Test
    fun hotWaterDefrostStandbyFault() = runBlocking {
        assertEquals("Hot water", HovalTopTronicDriver(ultraSource(wfa = 4), 1).read().status)
        assertEquals("Defrost", HovalTopTronicDriver(ultraSource(wfa = 8), 1).read().status)
        assertEquals("Standby", HovalTopTronicDriver(ultraSource(wfa = 0, generator = 0), 1).read().status)
        assertEquals("Fault", HovalTopTronicDriver(ultraSource(wfa = 11, generator = 16), 1).read().status)
        assertEquals("Fault", HovalTopTronicDriver(ultraSource(wfa = 0, generator = 16), 1).read().status)
        val err = HovalTopTronicDriver(ultraSource().u16(1534, 80), 1).read()
        assertEquals("W17 (80)", err.details.flatMap { it.rows }.toMap()["Heat pump error"])
    }

    @Test
    fun notAvailableValuesAreLeftOut() = runBlocking {
        val gw = ultraSource().u16(1477, 0x8000)
        gw.regs.remove(27490) // no COP on this firmware: the 27484 block falls back to single registers
        val r = HovalTopTronicDriver(gw, 1).read()
        assertNull(r.temperatureC)
        val rows = r.details.flatMap { it.rows }.toMap()
        assertNull(rows["COP"])
        assertEquals("12600 kWh", rows["Heat heating total"])
    }

    @Test
    fun optionalTimeoutIsNotRetriedEveryPoll() = runBlocking {
        val gw = ultraSource()
        val driver = HovalTopTronicDriver(gw, 1)
        driver.read()
        val first = gw.requests
        repeat(HovalTopTronicDriver.REFRESH_POLLS - 1) { driver.read() }
        assertEquals(first, gw.requests) // cached in between
        driver.read()
        assertEquals(first - 1, gw.requests - first) // full read again, without the timed-out sensor
    }

    @Test
    fun rejectsOtherDevices() = runBlocking {
        assertThrows { HovalTopTronicDriver(FakeGateway(), 1).identify() }
        val other = FakeGateway()
        for (a in 18722..18737) other.u16(a, 0x4242)
        assertThrows { HovalTopTronicDriver(other, 1).identify() }
        assertThrows { HovalTopTronicDriver(ultraSource().u16(18726, 250), 1).identify() }
    }

    private fun assertThrows(block: suspend () -> Unit) = runBlocking {
        try {
            block()
            fail("Expected IOException")
        } catch (_: IOException) {
        }
    }

    @Test
    fun problemsFromStatusAndErrorCode() = runBlocking {
        assertEquals(emptyList<DeviceProblem>(), HovalTopTronicDriver(ultraSource(), 1).read().problems)
        assertEquals(emptyList<DeviceProblem>(), HovalTopTronicDriver(ultraSource(wfa = 8), 1).read().problems) // defrost
        val lowPressure = ultraSource(wfa = 12, generator = 16).u16(1534, 203).u16(1504, 7)
        assertEquals(
            listOf(
                DeviceProblem(DeviceProblem.Kind.FAULT, "Low-pressure fault (WFA status 12)"),
                DeviceProblem(DeviceProblem.Kind.WARNING, "Hot water warning (status 7)"),
                DeviceProblem(DeviceProblem.Kind.FAULT, "Heat pump error E12 (code 203)"),
            ),
            HovalTopTronicDriver(lowPressure, 1).read().problems,
        )
        val warning = ultraSource().u16(1534, 74)
        assertEquals(
            listOf(DeviceProblem(DeviceProblem.Kind.WARNING, "Heat pump error W11 (code 74)")),
            HovalTopTronicDriver(warning, 1).read().problems,
        )
        val generatorFault = ultraSource(wfa = 0, generator = 16).u16(1534, 77)
        assertEquals(
            listOf(
                DeviceProblem(DeviceProblem.Kind.FAULT, "Heat generator fault (status 16)"),
                DeviceProblem(DeviceProblem.Kind.FAULT, "Heat pump error code 77"),
            ),
            HovalTopTronicDriver(generatorFault, 1).read().problems,
        )
    }
}
