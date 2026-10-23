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

class JanitzaUmgDriverTest {
    /** The shared float block 19000 ff.; [p] are the meter's phase powers (+ = drawn from the grid). */
    private fun measurements(fake: MeterRegisterFake, p: List<Double>) = fake.apply {
        listOf(230.1, 231.2, 229.8).forEachIndexed { i, v -> f32(19000 + 2 * i, v) }
        listOf(398.5, 399.0, 400.1).forEachIndexed { i, v -> f32(19006 + 2 * i, v) }
        p.forEachIndexed { i, w ->
            f32(19012 + 2 * i, kotlin.math.abs(w) / 230.0)
            f32(19020 + 2 * i, w)
            f32(19028 + 2 * i, kotlin.math.abs(w) * 1.02)
            f32(19036 + 2 * i, 50.0)
            f32(19044 + 2 * i, if (w >= 0) 0.98 else -0.98)
        }
        f32(19018, 0.2)
        f32(19026, p.sum()); f32(19034, p.sumOf { kotlin.math.abs(it) } * 1.02); f32(19042, 150.0)
        f32(19050, 50.01); f32(19052, 1.0)
        for (a in 19054..19108 step 2) f32(a, 0.0)
        f32(19068, 12_345_600.0) // consumed, Wh
        f32(19076, 6_543_200.0) // delivered, Wh
        for (a in 19110..19120 step 2) f32(a, 2.5)
    }

    private fun umg604(p: List<Double>) = measurements(MeterRegisterFake(unitId = 1), p)
        .text(10072, 32, "Hauptzaehler").text(10104, 64, "Grid connection").u32(10176, 7_001_234).u32(10178, 55)

    private fun umg96rme(p: List<Double>) = measurements(MeterRegisterFake(unitId = 1), p)
        .u16(750, 302).u32(754, 4_401_122).u32(756, 99).u16(761, 3)

    @Test
    fun identifiesUmg604ProAndReadsImport() = runBlocking {
        val driver = JanitzaUmgDriver(umg604(listOf(800.0, 700.0, 500.0)), 1)
        val info = driver.identify()
        assertEquals("UMG 604-PRO", info.model)
        assertEquals("7001234", info.serial)

        val r = driver.read()
        assertEquals(-2_000, r.gridPowerW) // 2 kW drawn → import is negative
        assertEquals(listOf("L1", "L2", "L3"), r.phases.map { it.name })
        assertEquals(230.1, r.phases[0].voltageV, 1e-3)
        assertEquals(-800, r.phases[0].powerW)
        assertEquals(50.01, r.phases[2].frequencyHz, 1e-3)
        assertEquals(800.0 / 230.0, r.phases[0].currentA!!, 1e-3)
        assertNull(r.today.importKwh) // lifetime only, no daily counters
        val energy = rows(r, "Energy (lifetime)")
        assertEquals("12345.6 kWh", energy["Import (consumed)"])
        assertEquals("6543.2 kWh", energy["Export (delivered)"])
        assertEquals("Hauptzaehler", rows(r, "Device")["Device name"])
        assertEquals("Right (1)", rows(r, "Grid")["Rotation field"])
    }

    @Test
    fun exportIsPositive() = runBlocking {
        val r = JanitzaUmgDriver(umg604(listOf(-1_000.0, -600.0, 100.0)), 1).read()
        assertEquals(1_500, r.gridPowerW)
        assertEquals(1_000, r.phases[0].powerW)
        assertEquals(-100, r.phases[2].powerW)
    }

    @Test
    fun identifiesUmg96rmFamilyByModuleNumber() = runBlocking {
        val driver = JanitzaUmgDriver(umg96rme(listOf(100.0, 100.0, 100.0)), 1)
        val info = driver.identify()
        assertEquals("UMG 96RM-E", info.model)
        assertEquals("4401122", info.serial)
        val device = rows(driver.read(), "Device")
        assertEquals("Ethernet (3)", device["Module"])
        assertEquals("302", device["Software release"])
    }

    @Test
    fun liveBlockIsOneRequest() = runBlocking {
        val fake = umg604(listOf(1.0, 1.0, 1.0))
        val driver = JanitzaUmgDriver(fake, 1)
        driver.read()
        fake.reads.clear()
        driver.read()
        assertEquals(listOf(19000 to 122), fake.reads)
    }

    @Test
    fun littleEndianDataIsRejected() {
        // The documented addresses deliver big-endian data; a word-swapped image must not be taken for a UMG.
        val fake = measurements(MeterRegisterFake(unitId = 1, lowWordFirst = true), listOf(1.0, 1.0, 1.0))
            .text(10072, 32, "X")
        assertThrows(IOException::class.java) { runBlocking { JanitzaUmgDriver(fake, 1).identify() } }
    }

    @Test
    fun nonMatchingDeviceThrows() {
        assertThrows(IOException::class.java) { runBlocking { JanitzaUmgDriver(silentDevice, 1).identify() } }
        // Some other device answering the addresses with unrelated data (frequency 1234 Hz).
        val other = MeterRegisterFake(unitId = 1, lenient = true).f32(19050, 1234.0)
        assertThrows(IOException::class.java) { runBlocking { JanitzaUmgDriver(other, 1).identify() } }
    }

    @Test
    fun rejectedRegistersAreLeftOut() = runBlocking {
        val fake = umg604(listOf(300.0, 300.0, 300.0))
        for (i in 0 until 3) fake.remove(19020 + 2 * i) // phase powers missing
        val driver = JanitzaUmgDriver(fake, 1)
        val r = driver.read()
        assertEquals(-900, r.gridPowerW)
        assertTrue(r.phases.all { it.powerW == null })
        assertEquals(3, r.phases.size)
        // The rejected block isn't asked again; only the single fields are.
        fake.reads.clear()
        driver.read()
        assertTrue(fake.reads.none { it == 19000 to 122 })
        assertTrue(fake.reads.none { it.first in setOf(19020, 19022, 19024) })
    }
}
