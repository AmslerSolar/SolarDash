// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.meter

import ch.amslersolar.dashboard.model.EnergyTotals
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class IneproPro380DriverTest {
    /** PRO380-Mod with the whole documented map; [kw] per phase, + = forward (import). */
    private fun pro380(kw: List<Double>): FakeMeterImage {
        val f = FakeMeterImage(unitId = 3, maxCount = 125)
        for (a in 0x4000..0x401F) f.u16(a, 0)
        for (a in 0x5000..0x5031) f.u16(a, 0)
        for (a in 0x6000..0x604A) f.u16(a, 0)
        f.u32(0x4000, 0x19123456L).u16(0x4002, 0x0102).u16(0x4003, 3).u16(0x4004, 9600).u16(0x4011, 1)
        f.f32(0x4007, 1.18).u16(0x400F, 5).u16(0x4012, 'F'.code).u16(0x4013, 'F'.code).u16(0x4014, 'R'.code)
        listOf(230.0, 231.0, 232.0).forEachIndexed { i, v -> f.f32(0x5002 + 2 * i, v) }
        f.f32(0x5008, 50.0)
        listOf(4.0, 3.0, 2.0).forEachIndexed { i, v -> f.f32(0x500C + 2 * i, v) }
        f.f32(0x5012, kw.sum())
        kw.forEachIndexed { i, v -> f.f32(0x5014 + 2 * i, v) }
        f.f32(0x502A, 0.99)
        f.f32(0x6000, 900.0).f32(0x600C, 1000.0).f32(0x6018, 100.0).f32(0x6049, 3.5)
        return f
    }

    @Test
    fun identifiesBySerialAndFrequency() = runBlocking {
        val info = IneproPro380Driver(pro380(listOf(0.0, 0.0, 0.0)), 3).identify()
        assertEquals("inepro PRO380", info.model)
        assertEquals("19123456", info.serial)
    }

    @Test
    fun threePhaseImport() = runBlocking {
        val f = pro380(listOf(1.2, 0.8, 0.5))
        val r = IneproPro380Driver(f, 3).read()
        assertEquals(-2500, r.gridPowerW)
        assertEquals(listOf(-1200, -800, -500), r.phases.map { it.powerW })
        assertEquals(231.0, r.phases[1].voltageV, 1e-4)
        assertEquals(50.0, r.phases[0].frequencyHz, 1e-6)
        assertEquals(EnergyTotals(), r.today) // the resettable day counter is not a daily counter
        assertEquals("3.50 kWh", r.detailRow("Energy (lifetime)", "Resettable day counter"))
        assertEquals("1000.00 kWh", r.detailRow("Energy (lifetime)", "Imported (forward)"))
        assertEquals("F / F / R", r.detailRow("Meter", "Current direction L1 / L2 / L3"))
        assertEquals("forward + reverse (5)", r.detailRow("Meter", "Combination code"))
        assertEquals("even (1)", r.detailRow("Modbus RTU", "Parity"))
        assertTrue(f.requests.all { it.second <= 50 })
    }

    @Test
    fun threePhaseExport() = runBlocking {
        val r = IneproPro380Driver(pro380(listOf(-1.0, -1.0, -0.5)), 3).read()
        assertEquals(2500, r.gridPowerW)
        assertEquals(listOf(1000, 1000, 500), r.phases.map { it.powerW })
    }

    @Test
    fun missingValuesAreLeftOut() = runBlocking {
        val f = pro380(listOf(0.1, 0.0, 0.0))
        for (a in 0x6000..0x604A) f.regs.remove(a) // no energy registers at all
        f.f32(0x5016, Double.NaN)
        val r = IneproPro380Driver(f, 3).read()
        assertNull(r.phases[1].powerW)
        assertEquals(-100, r.gridPowerW)
        assertNull(r.detailRow("Energy (lifetime)", "Imported (forward)"))
    }

    @Test
    fun nonMatchingDeviceThrows() {
        val noInfo = pro380(listOf(0.0, 0.0, 0.0)).also { f -> (0x4000..0x401F).forEach { f.regs.remove(it) } }
        assertThrows(IOException::class.java) { runBlocking { IneproPro380Driver(noInfo, 3).identify() } }
        val wrong = pro380(listOf(0.0, 0.0, 0.0)).also { it.u16(0x5008, 0).u16(0x5009, 0) }
        assertThrows(IOException::class.java) { runBlocking { IneproPro380Driver(wrong, 3).identify() } }
    }
}
