// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.fenecon

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

class FeneconFemsDriverTest {
    /**
     * A `_sum` register image. [strict]: rejects any read touching a register that isn't set (like a FEMS that
     * doesn't serve the gaps of the table); otherwise gaps read as 0.
     */
    private class FakeFems(private val strict: Boolean = false) : RegisterReader {
        val regs = HashMap<Int, Int>()
        val requests = mutableListOf<Pair<Int, Int>>()

        fun u16(a: Int, v: Int) = apply { regs[a] = v and 0xFFFF }
        fun f32(a: Int, v: Double) = apply {
            val bits = v.toFloat().toRawBits()
            regs[a] = (bits ushr 16) and 0xFFFF
            regs[a + 1] = bits and 0xFFFF
        }
        fun f64(a: Int, v: Double) = apply {
            val bits = v.toRawBits()
            for (i in 0 until 4) regs[a + i] = ((bits ushr (48 - 16 * i)) and 0xFFFF).toInt()
        }
        fun text(a: Int, n: Int, s: String) = apply {
            val padded = s.padEnd(n * 2, '\u0000')
            for (i in 0 until n) regs[a + i] = (padded[2 * i].code shl 8) or padded[2 * i + 1].code
        }

        override suspend fun read(unitId: Int, register: Int, count: Int): RegisterBlock {
            assertEquals(1, unitId)
            assertTrue("at most 125 registers per request", count <= 125)
            requests += register to count
            val data = ByteArray(count * 2)
            for (i in 0 until count) {
                val v = regs[register + i] ?: if (strict) throw ModbusException(2) else 0
                data[2 * i] = (v shr 8).toByte()
                data[2 * i + 1] = v.toByte()
            }
            return RegisterBlock(register, data)
        }
    }

    private fun fems(strict: Boolean = false) = FakeFems(strict).text(200, 16, "_sum").u16(222, 0).u16(417, 1)
        .f64(351, 1_200_000.0).f64(355, 1_000_000.0).f64(359, 3_000_000.0).f64(363, 4_500_000.0)
        .f64(367, 9_876_500.0).f64(371, 0.0).f64(375, 9_876_500.0).f64(379, 8_000_000.0)
        .f64(383, 1_250_000.0).f64(387, 1_050_000.0)

    /** Hybrid: 6 kW DC PV, battery charging 2 kW, house 1.5 kW, exporting 2.5 kW. */
    private fun chargingExport(strict: Boolean = false) = fems(strict)
        .u16(302, 64)
        .f32(303, 4_000.0) // AC side: 6000 PV − 2000 into the battery
        .f32(415, -2_000.0)
        .f32(315, -2_500.0) // − = sell to grid
        .f32(327, 6_000.0).f32(339, 6_000.0).f32(331, 0.0)
        .f32(343, 1_500.0)

    @Test
    fun identify() = runBlocking {
        val info = FeneconFemsDriver(fems(), 1).identify()
        assertEquals("FENECON FEMS", info.model)
    }

    @Test
    fun identifyRejectsOtherDevices() = runBlocking {
        for (fake in listOf(
            FakeFems().text(200, 16, "GoodWe ET"),
            FakeFems(strict = true), // nothing at 200
            FakeFems().text(200, 16, "_sum").u16(222, 42),
        )) {
            try {
                FeneconFemsDriver(fake, 1).identify()
                fail("identify() should have thrown")
            } catch (_: IOException) {
            }
        }
    }

    @Test
    fun chargingAndExporting() = runBlocking {
        val fake = chargingExport()
        val r = FeneconFemsDriver(fake, 1).read()
        assertEquals(6_000, r.pvPowerW)
        assertEquals(-2_000, r.battery?.powerW)
        assertEquals("Charging", r.battery?.mode)
        assertEquals(64, r.battery?.socPercent)
        assertEquals(2_500, r.gridPowerW)
        assertEquals(1_500, r.loadPowerW)
        assertEquals("Ok, On-grid", r.status)
        assertEquals(9_876.5, r.lifetimePvKwh!!, 1e-9)
        assertNull(r.today.pvKwh)
        val totals = r.details.first { it.title == "Energy totals" }.rows.toMap()
        assertEquals("9876.5 kWh", totals["Production"])
        assertEquals("4500.0 kWh", totals["Grid sell"])
        assertTrue(r.details.map { it.title }.containsAll(listOf("System", "Battery (ESS)", "Grid", "Production", "Consumption")))
        assertEquals(listOf(222 to 1, 302 to 116), fake.requests) // one live block
    }

    @Test
    fun dischargingAndImporting() = runBlocking {
        val fake = fems()
            .u16(302, 35)
            .f32(303, 1_800.0).f32(415, 1_800.0)
            .f32(315, 700.0) // + = buy from grid
            .f32(327, 0.0).f32(339, 0.0)
            .f32(343, 2_500.0)
            .u16(222, 2)
        val r = FeneconFemsDriver(fake, 1).read()
        assertEquals(0, r.pvPowerW)
        assertEquals(1_800, r.battery?.powerW)
        assertEquals("Discharging", r.battery?.mode)
        assertEquals(-700, r.gridPowerW)
        assertEquals(2_500, r.loadPowerW)
        assertEquals("Warning, On-grid", r.status)
    }

    @Test
    fun batteryFallsBackToAcSideMinusDcPv() = runBlocking {
        val fake = chargingExport().f32(415, Double.NaN)
        assertEquals(-2_000, FeneconFemsDriver(fake, 1).read().battery?.powerW)
    }

    @Test
    fun offGrid() = runBlocking {
        val fake = fems().u16(417, 2)
            .u16(302, 80).f32(415, 900.0).f32(315, 0.0).f32(327, 300.0).f32(343, 1_200.0)
        val r = FeneconFemsDriver(fake, 1).read()
        assertEquals("Ok, Off-grid", r.status)
        assertEquals(0, r.gridPowerW)
        assertEquals(900, r.battery?.powerW)
        assertTrue(r.details.first { it.title == "System" }.rows.contains("Grid mode" to "Off-grid (2)"))
    }

    @Test
    fun missingValuesAreNull() = runBlocking {
        // No ESS, no meter: OpenEMS leaves those channels undefined (NaN floats, 0xFFFF integers).
        val fake = FakeFems().text(200, 16, "_sum").u16(222, 0).u16(417, 0xFFFF)
            .u16(302, 0xFFFF).f32(303, Double.NaN).f32(309, Double.NaN).f32(415, Double.NaN).f32(339, Double.NaN)
            .f32(315, Double.NaN).f32(327, 4_200.0).f32(343, Double.NaN)
            .f64(367, Double.NaN)
        val r = FeneconFemsDriver(fake, 1).read()
        assertEquals(4_200, r.pvPowerW)
        assertNull(r.battery)
        assertNull(r.gridPowerW)
        assertNull(r.loadPowerW)
        assertNull(r.lifetimePvKwh)
        assertEquals("Ok", r.status)
        assertTrue(r.details.none { it.title == "Battery (ESS)" })
    }

    @Test
    fun rejectedBlockIsReadFieldByField() = runBlocking {
        val fake = chargingExport(strict = true)
        val driver = FeneconFemsDriver(fake, 1)
        val r = driver.read()
        assertEquals(6_000, r.pvPowerW)
        assertEquals(-2_000, r.battery?.powerW)
        assertEquals(2_500, r.gridPowerW)
        assertEquals(9_876.5, r.lifetimePvKwh!!, 1e-9)
        // Second poll: no new block attempt, and fields the FEMS rejected (309, 317, …) aren't asked again.
        fake.requests.clear()
        driver.read()
        assertTrue(fake.requests.none { it.second == 116 })
        assertTrue(fake.requests.none { it.first == 309 })
    }
}
