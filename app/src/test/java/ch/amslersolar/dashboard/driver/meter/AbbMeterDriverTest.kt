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

class AbbMeterDriverTest {
    /**
     * B23/B24 register image. Like the meter, every register of 1000h–8EFFh answers; unused ones are "invalid"
     * (FFFFh). [watts] per phase, + = import.
     */
    private fun b2x(type: String, watts: List<Int>, elements: Int = 3): FakeMeterImage {
        val f = FakeMeterImage(unitId = 1, maxCount = 125)
        for (a in 0x5000..0x5040) f.u16(a, 0xFFFF)
        for (a in 0x5100..0x5200) f.u16(a, 0xFFFF)
        for (a in 0x5460..0x5540) f.u16(a, 0xFFFF)
        for (a in 0x5B00..0x5B41) f.u16(a, 0xFFFF)
        for (a in 0x8900..0x8970) f.u16(a, 0xFFFF)
        for (a in 0x8A00..0x8A30) f.u16(a, 0xFFFF)
        for (a in 0x8C00..0x8CE6) f.u16(a, 0xFFFF)
        f.ascii(0x8960, 6, type).u32(0x8900, 12345678L).ascii(0x8908, 8, "1.0.0").u16(0x8910, 0x0102)
        f.u16(0x8CE5, elements)
        listOf(2301, 2295, 2310).forEachIndexed { i, v -> f.u32(0x5B00 + 2 * i, v.toLong()) }
        listOf(1050, 520, 260).forEachIndexed { i, v -> f.u32(0x5B0C + 2 * i, v.toLong()) }
        f.s32(0x5B14, watts.sum() * 100)
        watts.forEachIndexed { i, v -> f.s32(0x5B16 + 2 * i, v * 100) }
        f.u16(0x5B2C, 5001).u16(0x5B3A, 990)
        f.u64(0x5000, 1_234_567L).u64(0x5004, 89_000L).u64(0x5008, 1_145_567L)
        return f
    }

    @Test
    fun identifiesByTypeDesignation() = runBlocking {
        val info = AbbMeterDriver(b2x("B23 312-100", listOf(0, 0, 0)), 1).identify()
        assertEquals("ABB B23 312-100", info.model)
        assertEquals("12345678", info.serial)
    }

    @Test
    fun threePhaseImport() = runBlocking {
        val f = b2x("B24 352-100", listOf(2300, 1200, 600))
        val r = AbbMeterDriver(f, 1).read()
        assertEquals(-4100, r.gridPowerW)
        assertEquals(listOf(-2300, -1200, -600), r.phases.map { it.powerW })
        assertEquals(230.1, r.phases[0].voltageV, 1e-9)
        assertEquals(10.5, r.phases[0].currentA!!, 1e-9)
        assertEquals(50.01, r.phases[0].frequencyHz, 1e-9)
        assertEquals(EnergyTotals(), r.today)
        assertEquals("12345.67 kWh", r.detailRow("Energy (lifetime)", "Imported"))
        assertEquals("890.00 kWh", r.detailRow("Energy (lifetime)", "Exported"))
        assertEquals("1.0.0", r.detailRow("Meter", "Firmware"))
        assertEquals("1.2", r.detailRow("Meter", "Modbus mapping version"))
        assertTrue(f.requests.all { it.second <= 125 })
    }

    @Test
    fun threePhaseExport() = runBlocking {
        val r = AbbMeterDriver(b2x("B23 312-100", listOf(-1500, -1500, -1000)), 1).read()
        assertEquals(4000, r.gridPowerW)
        assertEquals(listOf(1500, 1500, 1000), r.phases.map { it.powerW })
    }

    @Test
    fun singleElementMeteringReportsOnlyTheMeasuredPhase() = runBlocking {
        val f = b2x("B23 112-100", listOf(-800, 0, 0), elements = 1)
        f.u32(0x5B02, 0).u32(0x5B04, 0)
        val r = AbbMeterDriver(f, 1).read()
        assertEquals(800, r.gridPowerW)
        assertEquals(listOf("L1"), r.phases.map { it.name })
        assertEquals("1", r.detailRow("Meter", "Number of elements"))
    }

    @Test
    fun invalidRegistersAreLeftOut() = runBlocking {
        val f = b2x("B23 312-100", listOf(100, 100, 100))
        f.words(0x5B18, 0x7FFF, 0xFFFF) // L2 power invalid (signed: highest value)
        f.words(0x5B04, 0xFFFF, 0xFFFF) // L3 voltage invalid (unsigned: FFFFh)
        f.words(0x5004, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF) // export accumulator invalid (e.g. "Steel" meter)
        val r = AbbMeterDriver(f, 1).read()
        assertEquals(listOf("L1", "L2"), r.phases.map { it.name })
        assertNull(r.phases[1].powerW)
        assertNull(r.detailRow("Energy (lifetime)", "Exported"))
    }

    @Test
    fun nonMatchingDeviceThrows() {
        val other = b2x("A44 552-100", listOf(0, 0, 0))
        assertThrows(IOException::class.java) { runBlocking { AbbMeterDriver(other, 1).identify() } }
        val nothing = FakeMeterImage(1, 125)
        assertThrows(IOException::class.java) { runBlocking { AbbMeterDriver(nothing, 1).identify() } }
    }
}
