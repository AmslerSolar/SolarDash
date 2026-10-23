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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class StiebelEltronIsgDriverTest {
    /** Register image keyed by PROTOCOL address; rejects reads touching registers it doesn't have. */
    private class FakeRegs : RegisterReader {
        val regs = HashMap<Int, Int>()
        val requests = mutableListOf<Pair<Int, Int>>()

        override suspend fun read(unitId: Int, register: Int, count: Int): RegisterBlock {
            assertEquals(1, unitId)
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

    private class Isg {
        val input = FakeRegs()
        val holding = FakeRegs()

        /** Documented (1-based) address: stored at address − 1, as the ISG expects it on the wire. */
        fun inp(doc: Int, v: Int) = apply { input.regs[doc - 1] = v and 0xFFFF }
        fun hold(doc: Int, v: Int) = apply { holding.regs[doc - 1] = v and 0xFFFF }
        fun temp(doc: Int, c: Double) = inp(doc, Math.round(c * 10).toInt())

        fun driver() = StiebelEltronIsgDriver(input, holding, 1)
    }

    /** WPM 3 heating: compressor + heating bits, outdoor 2.5 °C, 0x8000 for sensors that aren't fitted. */
    private fun wpm3(status: Int = (1 shl 0) or (1 shl 4) or (1 shl 6)) = Isg().apply {
        for (a in 501..548) inp(a, 0x8000)
        for (a in 2501..2506) inp(a, 0)
        for (a in 3501..3516) inp(a, 0)
        temp(507, 2.5).temp(515, 35.2).temp(516, 30.1).temp(522, 48.6).temp(523, 50.0).temp(518, 34.0).temp(536, -1.5)
        inp(520, 152) // 1.52 bar, data type 7
        inp(2501, status)
        inp(2570, 0).inp(2571, 12).inp(2572, 4).inp(2573, 1)
        inp(5001, 2).inp(5002, 390)
        hold(1501, 2)
        // Energy: heat 25 kWh heating + 5 kWh DHW today; electricity 6 + 2 kWh today.
        inp(3501, 25).inp(3504, 5).inp(3511, 6).inp(3514, 2)
        inp(3502, 345).inp(3503, 12) // 12 345 kWh heat heating
        inp(3505, 600).inp(3506, 3) // 3 600 kWh heat DHW
        inp(3512, 100).inp(3513, 4) // 4 100 kWh electricity heating
        inp(3515, 150).inp(3516, 1) // 1 150 kWh electricity DHW
    }

    @Test
    fun identifiesWpm3ByControllerCode() = runBlocking {
        val info = wpm3().driver().identify()
        assertEquals("STIEBEL ELTRON WPM 3 (ISG)", info.model)
        val isg = wpm3().inp(5002, 449)
        assertEquals("STIEBEL ELTRON WPMsystem (ISG)", isg.driver().identify().model)
    }

    @Test
    fun usesOneBasedAddresses() = runBlocking {
        val isg = wpm3()
        isg.driver().identify()
        // Documented 501 is sent as 500, 2501 as 2500, 5002 as 5001.
        assertTrue(isg.input.requests.contains(500 to 48))
        assertTrue(isg.input.requests.contains(2500 to 6))
        assertTrue(isg.input.requests.contains(5001 to 1))
    }

    @Test
    fun heating() = runBlocking {
        val r = wpm3().driver().read()
        assertEquals("Heating", r.status)
        assertEquals(2.5, r.temperatureC!!, 1e-9)
        assertNull(r.loadPowerW)
        assertNull(r.pvPowerW)
        assertNull(r.gridPowerW)
        assertNull(r.battery)
        assertEquals(8.0, r.today.loadKwh!!, 1e-9)
        val rows = r.details.flatMap { it.rows }.toMap()
        assertEquals("35.2 °C", rows["Flow"])
        assertEquals("30.1 °C", rows["Return"])
        assertEquals("48.6 °C", rows["Hot water"])
        assertEquals("-1.5 °C", rows["Source"])
        assertEquals("1.52 bar", rows["Heating pressure"])
        assertEquals("12345 kWh", rows["Heat heating total"])
        assertEquals("4100 kWh", rows["Electrical heating total"])
        assertEquals("3.75", rows["COP today (calculated)"]) // 30 / 8
        assertEquals("3.04", rows["COP total (calculated)"]) // 15 945 / 5 250
        assertEquals("On", rows["Compressor"])
        assertEquals("Programmed operation (2)", rows["Operating mode"])
        assertEquals("WPM 3", rows["Controller"])
        assertEquals("12.4.1 (extension 0)", rows["ISG version"])
        // Not fitted (0x8000): left out.
        assertNull(rows["Room (FE7)"])
        assertNull(rows["Hot gas"])
    }

    @Test
    fun hotWaterDefrostFaultAndStandby() = runBlocking {
        assertEquals("Hot water", wpm3(status = (1 shl 5) or (1 shl 6)).driver().read().status)
        assertEquals("Defrost", wpm3(status = (1 shl 9) or (1 shl 6)).driver().read().status)
        assertEquals("Defrost", wpm3(status = 0).inp(2506, 1).driver().read().status)
        assertEquals("Fault", wpm3().inp(2504, 1).driver().read().status)
        assertEquals("Idle", wpm3(status = 0).driver().read().status)
        assertEquals("Standby", wpm3(status = 0).hold(1501, 1).driver().read().status)
        assertEquals("Cooling", wpm3(status = (1 shl 8) or (1 shl 6)).driver().read().status)
    }

    @Test
    fun cachesBetweenFullReads() = runBlocking {
        val isg = wpm3()
        val driver = isg.driver()
        driver.read()
        val n = isg.input.requests.size
        isg.inp(2501, 1 shl 5)
        repeat(StiebelEltronIsgDriver.REFRESH_POLLS - 1) { assertEquals("Heating", driver.read().status) }
        assertEquals(n, isg.input.requests.size)
        assertEquals("Hot water", driver.read().status)
    }

    @Test
    fun wpm3iWithoutHeatPump1Registers() = runBlocking {
        // A WPM 3i that rejects 542..548: block 501..548 is then read register by register.
        val isg = wpm3().inp(5002, 391)
        for (a in 542..548) isg.input.regs.remove(a - 1)
        isg.temp(609, 33.3)
        isg.input.regs.remove(515 - 1)
        val driver = isg.driver()
        assertEquals("STIEBEL ELTRON WPM 3i (ISG)", driver.identify().model)
        val rows = driver.read().details.flatMap { it.rows }.toMap()
        assertEquals("33.3 °C", rows["Flow"])
        assertEquals("30.1 °C", rows["Return"])
    }

    @Test
    fun noDayCountersMeansNoDailyEnergy() = runBlocking {
        val isg = wpm3()
        isg.inp(3511, 0x8000).inp(3514, 0x8000)
        assertNull(isg.driver().read().today.loadKwh)
    }

    @Test
    fun plausibilityWithoutControllerCode() = runBlocking {
        val isg = wpm3()
        isg.input.regs.remove(5002 - 1)
        assertEquals("STIEBEL ELTRON WPM (ISG)", isg.driver().identify().model)
    }

    @Test
    fun rejectsOtherDevices() = runBlocking {
        // Nothing answers FC04.
        assertThrows { Isg().driver().identify() }
        // An LWZ integral ventilation unit: different register map.
        assertThrows { wpm3().inp(5002, 103).driver().identify() }
        // Some other device with random values, no controller code.
        val other = Isg()
        for (a in 501..548) other.inp(a, 0x1234)
        for (a in 2501..2506) other.inp(a, 0x7777)
        assertThrows { other.driver().identify() }
    }

    private fun assertThrows(block: suspend () -> Unit) = runBlocking {
        try {
            block()
            fail("Expected IOException")
        } catch (_: IOException) {
        }
    }

    @Test
    fun problemsFromFatalErrorAndBusStatus() = runBlocking {
        assertEquals(emptyList<DeviceProblem>(), wpm3().driver().read().problems)
        assertEquals(emptyList<DeviceProblem>(), wpm3(status = 1 shl 9).inp(2506, 1).driver().read().problems) // defrost
        val r = wpm3().inp(2504, 1).inp(2505, -3).driver().read()
        assertEquals(
            listOf(
                DeviceProblem(DeviceProblem.Kind.FAULT, "System fault (fatal error)"),
                DeviceProblem(DeviceProblem.Kind.WARNING, "CAN bus error (bus status -3)"),
            ),
            r.problems,
        )
    }
}
