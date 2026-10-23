// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.schneider

import ch.amslersolar.dashboard.driver.sunspec.FakeRegisterImage
import ch.amslersolar.dashboard.model.DeviceProblem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ConextCl60DriverTest {
    /**
     * CL 60E at slave address 1, input registers as in map rev 3.0: 32-bit values low word first; reads touching an
     * undocumented register (0x1390, 0x1394, …) are rejected, so the driver must fall back to single fields.
     */
    private fun cl60(modelCode: Int = 0x1062, wiring: Int = 1) = FakeRegisterImage(unitId = 1, lowWordFirst = true).apply {
        text(0x0A15, 15, "LCD_CL-60E_V11_V03_Q_M"); text(0x0A24, 15, "MDSP_CL-60E_V11_V1_B")
        u32(0x1355, 3); u32(0x1357, 30)
        text(0x137D, 10, "A1612160390")
        u16(0x1387, modelCode); u16(0x1388, 600); u16(0x1389, wiring); u16(0x13B8, 300)
        u16(0x138A, 2_345); u32(0x138B, 123_456); u32(0x138D, 5_000); s16(0x138F, 452)
        u16(0x1392, 6_500); u16(0x1393, 1_085); u16(0x1395, 0)
        u32(0x1398, 70_500) // > 65 535: checks the word order
        u16(0x139A, 2_301); u16(0x139B, 2_305); u16(0x139C, 2_299)
        u16(0x139D, 1_002); u16(0x139E, 1_001); u16(0x139F, 1_003)
        u32(0x13A4, 69_800); u32(0x13A6, 68_912); u32(0x13A8, -1_200L and 0xFFFFFFFFL); s16(0x13AA, -998); u16(0x13AB, 500)
        u16(0x13AD, 0x0000); u16(0x13B4, 0); u16(0x13CE, 1_500); u32(0x13D8, 1L shl 17); u16(0x13F8, 600)
        u32(0x1407, 45_678); s16(0x1419, -12); u16(0x141A, 7_100)
        for (i in 0 until 14) u16(0x1B64 + i, 850 + i)
    }

    @Test
    fun readsTheVendorRegistersAtOneWattResolution() = runBlocking {
        val image = cl60()
        val driver = ConextCl60Driver(image, 1)
        val info = driver.identify()
        assertEquals("Conext CL 60E", info.model)
        assertEquals("A1612160390", info.serial)
        val r = driver.read()
        assertEquals(70_500, r.pvPowerW)
        assertEquals(1, r.pvStrings.size)
        assertEquals(650.0, r.pvStrings[0].voltageV, 1e-9)
        assertEquals(108.5, r.pvStrings[0].currentA, 1e-9)
        assertEquals(234.5, r.today.pvKwh!!, 1e-9)
        assertEquals(123_456.0, r.lifetimePvKwh!!, 1e-9)
        assertEquals(45.2, r.temperatureC!!, 1e-9)
        assertEquals("Run", r.status)
        assertEquals(listOf("L1", "L2", "L3"), r.phases.map { it.name })
        assertEquals(230.1, r.phases[0].voltageV, 1e-9)
        assertEquals(100.2, r.phases[0].currentA!!, 1e-9)
        assertEquals(50.0, r.phases[0].frequencyHz, 1e-9)
        assertNull(r.gridPowerW)
        val ac = r.details.first { it.title == "Inverter (AC)" }.rows.toMap()
        assertEquals("68912 W", ac["Active power"])
        assertEquals("-1200 var", ac["Reactive power"])
        assertEquals("-0.998", ac["Power factor (+ leading)"])
        assertTrue(image.requests.all { it.second <= 125 })
    }

    @Test
    fun threeWireWiringGivesNoPerPhaseReadings() = runBlocking {
        val r = ConextCl60Driver(cl60(wiring = 2), 1).read()
        assertTrue(r.phases.isEmpty())
        assertTrue(r.details.first { it.title == "Inverter (AC)" }.rows.any { it.first == "L1-L2 voltage" })
    }

    @Test
    fun rejectsOtherDevices() = runBlocking {
        val wrongCode = runCatching { ConextCl60Driver(cl60(modelCode = 0x2001), 1).identify() }.exceptionOrNull()
        assertTrue(wrongCode is IOException)
        val nothing = runCatching { ConextCl60Driver(FakeRegisterImage(unitId = 1), 1).identify() }.exceptionOrNull()
        assertTrue(nothing is IOException)
    }

    @Test
    fun noProblemsWhileRunning() = runBlocking {
        assertEquals(emptyList<DeviceProblem>(), ConextCl60Driver(cl60(), 1).read().problems)
    }

    @Test
    fun faultAndAlarmCodesBecomeProblems() = runBlocking {
        suspend fun problems(state: Int, code: Int) = ConextCl60Driver(cl60().apply { u16(0x13AD, state); u16(0x13B4, code) }, 1).read().problems
        assertEquals(listOf(DeviceProblem(DeviceProblem.Kind.MAINS_LOST, "Islanding (grid outage) (10)")), problems(0x5500, 10))
        assertEquals(listOf(DeviceProblem(DeviceProblem.Kind.FAULT, "Insulation fault (39)")), problems(0x5500, 39))
        assertEquals(listOf(DeviceProblem(DeviceProblem.Kind.FAULT, "Fault code 999")), problems(0x5500, 999))
        assertEquals(listOf(DeviceProblem(DeviceProblem.Kind.WARNING, "Fan failure (70)")), problems(0x9100, 70))
        assertEquals(listOf(DeviceProblem(DeviceProblem.Kind.WARNING, "Derating (state 0x8100)")), problems(0x8100, 0))
        assertEquals(emptyList<DeviceProblem>(), problems(0x5500, 75)) // low irradiance
        assertEquals(emptyList<DeviceProblem>(), problems(0x1400, 0)) // standby
    }
}
