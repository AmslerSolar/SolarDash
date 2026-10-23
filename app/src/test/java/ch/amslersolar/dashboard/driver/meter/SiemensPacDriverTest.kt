// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.meter

import ch.amslersolar.dashboard.model.DeviceProblem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SiemensPacDriverTest {
    /** Strict image of the documented map; [w] are the meter's phase powers (+ = import). */
    private fun pac2200(w: List<Double>, manufacturer: Int = 42) = MeterRegisterFake(unitId = 1).apply {
        u16(64001, manufacturer)
        text(64002, 10, "7KM2200-2EA40-1JB1")
        text(64012, 8, "LQN/12345678")
        u16(64020, 2)
        u16(64021, ('V'.code shl 8) or 3); u16(64022, (2 shl 8) or 0)
        for (a in 64023..64027) u16(a, 0)
        w.forEachIndexed { i, p ->
            f32(1 + 2 * i, 230.0 + i) // U L-N
            f32(7 + 2 * i, 400.0)
            f32(13 + 2 * i, kotlin.math.abs(p) / 230.0)
            f32(19 + 2 * i, kotlin.math.abs(p))
            f32(25 + 2 * i, p)
            f32(31 + 2 * i, 10.0)
            f32(37 + 2 * i, 0.99)
        }
        f32(55, 50.02); f32(57, 231.0); f32(59, 400.0); f32(61, 2.0)
        f32(63, w.sumOf { kotlin.math.abs(it) }); f32(65, w.sum()); f32(67, 30.0); f32(69, 0.99); f32(71, 0.1)
        u32(205, 0); u32(207, 0); u32(209, 1); u32(211, 0)
        for (a in 801 until 833 step 4) f64(a, 1_000.0)
        for (p in 0 until 3) for (o in 0 until 16 step 4) f64(841 + 40 * p + o, 500.0)
        f64(961, 0.0); f64(965, 0.0)
        f64(969, 8_956_000.5) // Wh, big-endian double
        f64(973, 1_234_000.0)
    }

    @Test
    fun identifiesAndReadsImport() = runBlocking {
        val driver = SiemensPacDriver(pac2200(listOf(1_000.0, 500.0, 500.0)), 1)
        val info = driver.identify()
        assertEquals("SENTRON PAC2200 (7KM2200-2EA40-1JB1)", info.model)
        assertEquals("LQN/12345678", info.serial)

        val r = driver.read()
        assertEquals(-2_000, r.gridPowerW)
        assertEquals(230.0, r.phases[0].voltageV, 1e-3)
        assertEquals(-1_000, r.phases[0].powerW)
        assertEquals(50.02, r.phases[0].frequencyHz, 1e-3)
        val energy = rows(r, "Energy (lifetime)")
        assertEquals("8956.0 kWh", energy["Active import (MID)"])
        assertEquals("1234.0 kWh", energy["Active export (MID)"])
        assertEquals("V3.2.0", rows(r, "Device")["Firmware"])
        assertEquals("T1", rows(r, "Device")["Active tariff"])
        assertNull(r.today.exportKwh)
    }

    @Test
    fun exportIsPositive() = runBlocking {
        val r = SiemensPacDriver(pac2200(listOf(-800.0, -800.0, -400.0)), 1).read()
        assertEquals(2_000, r.gridPowerW)
        assertEquals(400, r.phases[2].powerW)
    }

    @Test
    fun readsOnlyDocumentedBlocks() = runBlocking {
        // The strict fake rejects anything outside the documented offsets; three live requests per poll.
        val fake = pac2200(listOf(1.0, 1.0, 1.0))
        val driver = SiemensPacDriver(fake, 1)
        driver.read()
        fake.reads.clear()
        driver.read()
        assertEquals(listOf(1 to 42, 55 to 18, 961 to 16), fake.reads)
    }

    @Test
    fun nonMatchingDeviceThrows() {
        assertThrows(IOException::class.java) { runBlocking { SiemensPacDriver(silentDevice, 1).identify() } }
        assertThrows(IOException::class.java) { runBlocking { SiemensPacDriver(pac2200(listOf(0.0, 0.0, 0.0), manufacturer = 7), 1).identify() } }
    }

    @Test
    fun missingValuesAreNull() = runBlocking {
        val fake = pac2200(listOf(100.0, 100.0, 100.0))
        fake.remove(29) // L3 active power rejected
        fake.remove(973, 4) // no export counter
        val r = SiemensPacDriver(fake, 1).read()
        assertEquals(-300, r.gridPowerW)
        assertNull(r.phases[2].powerW)
        assertEquals(-100, r.phases[1].powerW)
        assertNull(rows(r, "Energy (lifetime)")["Active export (MID)"])
        assertTrue(rows(r, "Energy (lifetime)").containsKey("Active import (MID)"))
    }

    @Test
    fun deviceStatusRangeBitsAreWarnings() = runBlocking {
        assertEquals(emptyList<DeviceProblem>(), SiemensPacDriver(pac2200(listOf(100.0, 0.0, 0.0)), 1).read().problems)
        // Configuration menu (0x02000000) and restart (stored, 0x00000800) are not problems.
        val fake = pac2200(listOf(100.0, 0.0, 0.0)).apply { u32(205, 0x0A000800L) }
        assertEquals(
            listOf(DeviceProblem(DeviceProblem.Kind.WARNING, "Current out of range (status 0x08000000)")),
            SiemensPacDriver(fake, 1).read().problems,
        )
    }
}
