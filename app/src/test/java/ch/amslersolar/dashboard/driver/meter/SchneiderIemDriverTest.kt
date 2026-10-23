// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.meter

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SchneiderIemDriverTest {
    /** iEM3155 at Modbus address 5; constants are frame addresses (documented register − 1). [kw]: + = import. */
    private fun iem3155(kw: List<Double>, model: String = "iEM3155", lenient: Boolean = true) =
        MeterRegisterFake(unitId = 5, lenient = lenient).apply {
            text(29, 20, model).text(49, 20, model).text(69, 20, "Schneider Electric")
            u32(129, 1_234_567)
            u16(131, 18); u16(132, (3 shl 8) or 14); u16(133, (9 shl 8) or 30); u16(134, 0) // 2018-03-14 09:30
            text(135, 5, "V1.0")
            u16(1636, 0x2103)
            u32(2003, 7_200); u16(2013, 3); u16(2014, 4); u16(2015, 11); u16(2016, 50)
            kw.forEachIndexed { i, p ->
                f32(2999 + 2 * i, kotlin.math.abs(p) * 1000 / 230)
                f32(3027 + 2 * i, 230.0 + i)
                f32(3019 + 2 * i, 400.0 + i)
                f32(3053 + 2 * i, p)
            }
            f32(3005, 0.1); f32(3009, 3.0); f32(3025, 400.0); f32(3035, 230.0)
            f32(3059, kw.sum()); f32(3067, 0.4); f32(3075, 2.6); f32(3083, 1.2); f32(3109, 49.98)
            u64(3203, 0x0000_0001_0000_0002L) // import Wh: high word first
            u64(3207, 2_500_000); u64(3219, 10_000); u64(3223, 20_000)
        }

    @Test
    fun identifiesAndReadsImport() = runBlocking {
        val fake = iem3155(listOf(1.0, 0.8, 0.7))
        val driver = SchneiderIemDriver(fake, 5)
        val info = driver.identify()
        assertEquals("iEM3155", info.model)
        assertEquals("1234567", info.serial)

        val r = driver.read()
        assertEquals(-2_500, r.gridPowerW) // 2.5 kW imported
        assertEquals(listOf("L1", "L2", "L3"), r.phases.map { it.name })
        assertEquals(230.0, r.phases[0].voltageV, 1e-3)
        assertEquals(-1_000, r.phases[0].powerW)
        assertEquals(49.98, r.phases[1].frequencyHz, 1e-3)
        assertNull(r.today.importKwh)
        val energy = rows(r, "Energy (lifetime)")
        assertEquals("4294967.3 kWh", energy["Active import"]) // 0x1_0000_0002 Wh, high word first
        assertEquals("2500.0 kWh", energy["Active export"])
        val grid = rows(r, "Grid")
        assertEquals("0.800", grid["Power factor"]) // 4-quadrant 1.2 → Q4, PF 0.8
        assertEquals("Q4 (P +, capacitive)", grid["Quadrant"])
        val device = rows(r, "Device")
        assertEquals("2018-03-14 09:30", device["Manufactured"])
        assertEquals("3PH4W (11)", device["Power system"])
    }

    @Test
    fun exportIsPositive() = runBlocking {
        val r = SchneiderIemDriver(iem3155(listOf(-0.5, -0.5, -0.2)), 5).read()
        assertEquals(1_200, r.gridPowerW)
        assertEquals(500, r.phases[0].powerW)
    }

    @Test
    fun sendsAddressNotRegisterNumber() = runBlocking {
        val fake = iem3155(listOf(0.1, 0.1, 0.1))
        val driver = SchneiderIemDriver(fake, 5)
        driver.read()
        fake.reads.clear()
        driver.read()
        // Register 3000 (I1) is address 2999; register 3204 (import) is address 3203. Two requests per poll.
        assertEquals(listOf(2999 to 112, 3203 to 24), fake.reads)
    }

    @Test
    fun nonMatchingDeviceThrows() {
        assertThrows(IOException::class.java) { runBlocking { SchneiderIemDriver(silentDevice, 5).identify() } }
        val other = MeterRegisterFake(unitId = 5, lenient = true).text(49, 20, "PM5560").text(69, 20, "Other Corp")
        assertThrows(IOException::class.java) { runBlocking { SchneiderIemDriver(other, 5).identify() } }
    }

    @Test
    fun iem3150WithoutReactiveAndExportRegisters() = runBlocking {
        // Strict device: undefined addresses rejected; the iEM3150 lacks Q, S and export energy.
        val fake = iem3155(listOf(0.3, 0.3, 0.3), model = "iEM3150", lenient = false)
        fake.remove(3067).remove(3075).remove(3207, 4).remove(3219, 4).remove(3223, 4)
        val driver = SchneiderIemDriver(fake, 5)
        assertEquals("iEM3150", driver.identify().model)
        val r = driver.read()
        assertEquals(-900, r.gridPowerW)
        val grid = rows(r, "Grid")
        assertNull(grid["Reactive power"])
        assertNull(grid["Apparent power"])
        val energy = rows(r, "Energy (lifetime)")
        assertNull(energy["Active export"])
        assertTrue(energy.containsKey("Active import"))
        // Afterwards only the documented runs are read, in a handful of requests.
        fake.reads.clear()
        driver.read()
        assertTrue("${fake.reads}", fake.reads.size <= 12)
    }
}
