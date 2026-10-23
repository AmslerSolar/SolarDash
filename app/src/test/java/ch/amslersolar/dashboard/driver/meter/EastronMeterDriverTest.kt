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

class EastronMeterDriverTest {
    private class Meter(val input: FakeMeterImage, val holding: FakeMeterImage) {
        fun driver() = EastronMeterDriver(input, holding, 7)
    }

    /** SDM630, 3p4w; [watts] per phase (+ import); undocumented gaps readable unless [strict]. */
    private fun sdm630(watts: List<Double>, swapped: Boolean = false, strict: Boolean = false): Meter {
        val i = FakeMeterImage(7, 80, evenOnly = true)
        val h = FakeMeterImage(7, 80, evenOnly = true)
        if (!strict) {
            for (a in 0x00..0x4F) i.u16(a, 0)
            for (a in 0xC8..0xE1) i.u16(a, 0)
        }
        listOf(230.5, 231.0, 229.5).forEachIndexed { n, v -> i.f32(2 * n, v, swapped) }
        listOf(3.0, 2.0, 1.0).forEachIndexed { n, v -> i.f32(0x06 + 2 * n, v, swapped) }
        watts.forEachIndexed { n, v -> i.f32(0x0C + 2 * n, v, swapped) }
        for (n in 0 until 3) {
            i.f32(0x12 + 2 * n, 700.0, swapped).f32(0x18 + 2 * n, 50.0, swapped).f32(0x1E + 2 * n, 0.98, swapped)
            i.f32(0x24 + 2 * n, 1.5, swapped)
        }
        i.f32(0x2A, 230.3, swapped).f32(0x2E, 2.0, swapped).f32(0x30, 6.0, swapped).f32(0x34, watts.sum(), swapped)
        i.f32(0x38, 2100.0, swapped).f32(0x3C, 150.0, swapped).f32(0x3E, 0.97, swapped).f32(0x42, 2.0, swapped)
        i.f32(0x46, 49.98, swapped).f32(0x48, 4321.5, swapped).f32(0x4A, 1234.25, swapped)
        i.f32(0x4C, 10.0, swapped).f32(0x4E, 20.0, swapped)
        i.f32(0xC8, 399.0, swapped).f32(0xCA, 400.0, swapped).f32(0xCC, 401.0, swapped).f32(0xCE, 400.0, swapped)
        i.f32(0xE0, 0.4, swapped)
        h.u32(0xFC00, 21_123_456L, swapped).f32(0x0A, 3.0, swapped).f32(0x14, 7.0, swapped).f32(0x1C, 2.0, swapped)
        h.f32(0x12, 0.0, swapped)
        return Meter(i, h)
    }

    private fun sdm120(watts: Double): Meter {
        val i = FakeMeterImage(7, 80, evenOnly = true)
        val h = FakeMeterImage(7, 80, evenOnly = true)
        for (a in 0x00..0x1F) i.u16(a, 0)
        i.f32(0x00, 232.0).f32(0x06, 4.0).f32(0x0C, watts).f32(0x12, 930.0).f32(0x18, 40.0).f32(0x1E, -0.99)
        i.f32(0x46, 50.02).f32(0x48, 100.0).f32(0x4A, 250.5).f32(0x4C, 1.0).f32(0x4E, 2.0)
        h.u32(0xFC00, 1_000_123L).words(0xFC02, 0x0020, 0x0102).f32(0x14, 7.0).f32(0x1C, 0.0)
        return Meter(i, h)
    }

    @Test
    fun identifiesSdm630BySystemTypeAndSerial() = runBlocking {
        val info = sdm630(listOf(0.0, 0.0, 0.0)).driver().identify()
        assertEquals("Eastron SDM630", info.model)
        assertEquals("21123456", info.serial)
    }

    @Test
    fun threePhaseImport() = runBlocking {
        val m = sdm630(listOf(1000.0, 500.0, 250.0))
        val r = m.driver().read()
        assertEquals(-1750, r.gridPowerW)
        assertEquals(listOf(-1000, -500, -250), r.phases.map { it.powerW })
        assertEquals(230.5, r.phases[0].voltageV, 1e-4)
        assertEquals(3.0, r.phases[0].currentA!!, 1e-6)
        assertEquals(49.98, r.phases[2].frequencyHz, 1e-4)
        assertEquals(EnergyTotals(), r.today)
        assertEquals("4321.50 kWh", r.detailRow("Energy (lifetime)", "Imported"))
        assertEquals("1234.25 kWh", r.detailRow("Energy (lifetime)", "Exported"))
        assertEquals("399.0 V", r.detailRow("L1", "Voltage L-L"))
        assertEquals("3 phase 4 wire (3)", r.detailRow("Meter", "System type"))
        assertEquals("7", r.detailRow("Modbus RTU", "Address"))
        // Measurements in two requests: 0000h–004Fh (80 registers, the documented maximum) and the L-L block.
        assertTrue((0x00 to 80) in m.input.requests)
        assertTrue((0xC8 to 26) in m.input.requests)
    }

    @Test
    fun threePhaseExport() = runBlocking {
        val r = sdm630(listOf(-2000.0, -2000.0, -1000.0)).driver().read()
        assertEquals(5000, r.gridPowerW)
        assertEquals(listOf(2000, 2000, 1000), r.phases.map { it.powerW })
    }

    @Test
    fun reversedRegisterOrderIsDetected() = runBlocking {
        val d = sdm630(listOf(-100.0, -200.0, -300.0), swapped = true).driver()
        assertEquals("21123456", d.identify().serial)
        val r = d.read()
        assertEquals(600, r.gridPowerW)
        assertEquals(230.5, r.phases[0].voltageV, 1e-4)
        assertEquals("reversed (least significant first)", r.detailRow("Meter", "Register order"))
    }

    @Test
    fun sdm120SinglePhase() = runBlocking {
        val m = sdm120(-900.0)
        val d = m.driver()
        assertEquals("Eastron SDM120", d.identify().model)
        val r = d.read()
        assertEquals(900, r.gridPowerW)
        assertEquals(listOf("L1"), r.phases.map { it.name })
        assertEquals(4.0, r.phases[0].currentA!!, 1e-6)
        assertEquals("250.50 kWh", r.detailRow("Energy (lifetime)", "Exported"))
        assertEquals("0020", r.detailRow("Meter", "Meter code"))
        assertTrue(m.input.requests.all { it.second <= 80 && it.second % 2 == 0 })
    }

    @Test
    fun strictMeterRejectingUndocumentedGapsStillReads() = runBlocking {
        val m = sdm630(listOf(100.0, 100.0, 100.0), strict = true)
        val d = m.driver()
        val r = d.read()
        assertEquals(-300, r.gridPowerW)
        assertEquals("400.0 V", r.detailRow("System", "Average voltage L-L"))
        val first = m.input.requests.size
        d.read() // the rejected blocks are remembered: the second poll asks only gap-free runs
        assertTrue(m.input.requests.size - first < first)
    }

    @Test
    fun missingValuesAreLeftOut() = runBlocking {
        val m = sdm630(listOf(100.0, 100.0, 100.0))
        m.input.f32(0x4A, Double.NaN)
        val r = m.driver().read()
        assertNull(r.detailRow("Energy (lifetime)", "Exported"))
        assertNull(r.detailRow("L1", "Imported")) // per-phase energies (static block) not in this image
    }

    @Test
    fun nonMatchingDeviceThrows() {
        val noFrequency = sdm630(listOf(0.0, 0.0, 0.0)).also { it.input.f32(0x46, 0.0) }
        assertThrows(IOException::class.java) { runBlocking { noFrequency.driver().identify() } }
        val noSerial = sdm630(listOf(0.0, 0.0, 0.0)).also { it.holding.regs.clear() }
        assertThrows(IOException::class.java) { runBlocking { noSerial.driver().identify() } }
        val nothing = Meter(FakeMeterImage(7, 80, true), FakeMeterImage(7, 80, true))
        assertThrows(IOException::class.java) { runBlocking { nothing.driver().identify() } }
    }
}
