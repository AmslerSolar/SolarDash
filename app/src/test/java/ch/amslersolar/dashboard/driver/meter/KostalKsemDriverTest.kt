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

class KostalKsemDriverTest {
    /**
     * Strict image (like the KSEM, exception 2 for unspecified registers). [w]: signed phase powers, + = drawn
     * from the grid; written as the documented direction-split unsigned pairs.
     */
    private fun ksem(w: List<Double>, manufacturer: Int = 0x5233) = MeterRegisterFake(unitId = 1).apply {
        u16(8192, manufacturer); u16(8193, 0x4852); u16(8194, 1); u16(8195, 0x0206)
        text(8196, 16, "KOSTAL Solar Electric"); text(8212, 16, "KOSTAL Smart Energy Meter"); text(8228, 16, "30380912332211")
        u16(8244, 500); u64(8245, 1_552_323_559_000); u16(8249, 7)
        u16(8250, 0xAABB); u16(8251, 0xCC11); u16(8252, 0x2233)

        fun pair(a: Int, v: Double) {
            u32(a, if (v > 0) Math.round(v * 10) else 0)
            u32(a + 2, if (v < 0) Math.round(-v * 10) else 0)
        }
        pair(0, w.sum()); pair(4, 300.0); pair(16, w.sumOf { kotlin.math.abs(it) })
        s32(24, -950); u32(26, 50_010)
        w.forEachIndexed { i, p ->
            val b = 40 + 40 * i
            pair(b, p); pair(b + 4, 100.0); pair(b + 16, kotlin.math.abs(p))
            u32(b + 20, Math.round(kotlin.math.abs(p) / 230.0 * 1000)); u32(b + 22, 230_500L + i * 1000); s32(b + 24, 990)
        }
        u32(146, 0)
        u64(512, 123_456_789); u64(516, 98_765_432) // 0.1 Wh
        for (a in listOf(520, 524, 544, 548)) u64(a, 1_000)
        for (b in listOf(592, 672, 752)) for (o in listOf(0, 4, 8, 12, 32, 36)) u64(b + o, 10_000)
    }

    @Test
    fun identifiesAndReadsImport() = runBlocking {
        val driver = KostalKsemDriver(ksem(listOf(1_000.0, 600.0, 400.0)), 1)
        val info = driver.identify()
        assertEquals("KOSTAL Smart Energy Meter", info.model)
        assertEquals("30380912332211", info.serial)

        val r = driver.read()
        assertEquals(-2_000, r.gridPowerW)
        assertEquals(listOf(-1_000, -600, -400), r.phases.map { it.powerW })
        assertEquals(230.5, r.phases[0].voltageV, 1e-6)
        assertEquals(1.0 / 0.23, r.phases[0].currentA!!, 1e-2)
        assertEquals(50.01, r.phases[0].frequencyHz, 1e-6)
        val energy = rows(r, "Energy (lifetime)")
        assertEquals("12345.7 kWh", energy["Active energy drawn (1.8.0)"])
        assertEquals("9876.5 kWh", energy["Active energy fed in (2.8.0)"])
        val device = rows(r, "Device")
        assertEquals("2.6", device["Firmware"])
        assertEquals("HWv2 (18514)", device["Hardware type"])
        assertEquals("AA:BB:CC:11:22:33", device["MAC address"])
        assertNull(r.today.importKwh)
    }

    @Test
    fun exportIsPositiveAndPhasesMayDiffer() = runBlocking {
        val r = KostalKsemDriver(ksem(listOf(-2_000.0, 300.0, -100.0)), 1).read()
        assertEquals(1_800, r.gridPowerW)
        assertEquals(listOf(2_000, -300, 100), r.phases.map { it.powerW })
    }

    @Test
    fun gapsAreReadAsDocumentedRuns() = runBlocking {
        val fake = ksem(listOf(10.0, 10.0, 10.0))
        val driver = KostalKsemDriver(fake, 1)
        driver.read()
        fake.reads.clear()
        driver.read()
        // The bridged blocks were rejected (gaps); now each documented run is one request.
        assertTrue(fake.reads.all { (start, n) -> (start until start + n).all { it in fake.regs } })
        assertTrue(fake.reads.contains(0 to 8))
        assertTrue(fake.reads.contains(512 to 8))
        assertTrue("${fake.reads}", fake.reads.size <= 10)
    }

    @Test
    fun multiRegisterValuesAreHighWordFirst() = runBlocking {
        val fake = ksem(listOf(0.0, 0.0, 0.0))
        fake.u32(0, 0x0001_0000L) // 65536 × 0.1 W drawn: only correct if the first register is the high word
        assertEquals(-6_554, KostalKsemDriver(fake, 1).read().gridPowerW)
    }

    @Test
    fun nonMatchingDeviceThrows() {
        assertThrows(IOException::class.java) { runBlocking { KostalKsemDriver(silentDevice, 1).identify() } }
        assertThrows(IOException::class.java) { runBlocking { KostalKsemDriver(ksem(listOf(0.0, 0.0, 0.0), manufacturer = 0x1234), 1).identify() } }
    }

    @Test
    fun missingValuesAreNull() = runBlocking {
        val fake = ksem(listOf(100.0, 100.0, 100.0))
        fake.remove(122) // L3 active power− not answered
        fake.remove(142) // L3 voltage
        val r = KostalKsemDriver(fake, 1).read()
        assertEquals(-300, r.gridPowerW)
        assertEquals(2, r.phases.size) // no voltage → no L3 phase entry
        assertNull(rows(r, "L3")["Active power fed in (−)"])
    }
}
