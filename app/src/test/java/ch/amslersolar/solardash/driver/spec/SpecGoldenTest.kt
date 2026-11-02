// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.driver.spec

import ch.amslersolar.solardash.driver.DeviceDriver
import ch.amslersolar.solardash.model.DeviceReading
import java.io.File
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName

/**
 * The ModbusSpec drivers must read what the hand-written drivers read, on the same register images: the values
 * the energy flow and the plant status use (power, phases, battery, energy, faults). Detail rows may differ.
 * The hand-written drivers are gone: what they read is recorded in golden/SpecGoldenTest/old-drivers.txt.
 */
class SpecGoldenTest {
    private val library = SpecLibrary { File("src/main/assets/modbusspec/$it").readText() }

    private fun spec(id: String, tables: SpecTables, unit: Int) =
        SpecDriver(library.spec(id), library.vocabulary, tables, unit, SpecDevices.hooks(id), requestGapMs = 0)

    /** What has to match, with powers to the watt and voltages/currents to 0.01. */
    private fun essentials(r: DeviceReading) = listOf(
        "pv" to r.pvPowerW, "grid" to r.gridPowerW, "load" to r.loadPowerW,
        "battery" to r.battery?.let { listOf(it.powerW, it.socPercent) },
        "phases" to r.phases.map { listOf(it.name, "%.2f".format(Locale.ROOT, it.voltageV), it.currentA?.let { a -> "%.2f".format(Locale.ROOT, a) }, it.powerW) },
        "problems" to r.problems.map { it.kind to it.text },
    )

    @get:Rule val name = TestName()

    private val old = OldDriverRecords("SpecGoldenTest")

    /** [new] must read what the hand-written driver read on the same image (recorded as `<test method>/<case>`). */
    private fun same(case: String, new: DeviceDriver) = runBlocking {
        val key = "${name.methodName}/$case"
        val b = essentials(new.read())
        assertEquals(key, old[key], b.joinToString("\n"))
    }

    // ---------------------------------------------------------------- Eastron SDM630 / SDM120

    private fun sdm630(watts: List<Double>, swapped: Boolean): Pair<FakeMeterImage, FakeMeterImage> {
        val i = FakeMeterImage(7, 80, evenOnly = true)
        val h = FakeMeterImage(7, 80, evenOnly = true)
        for (a in 0x00..0x4F) i.u16(a, 0)
        for (a in 0xC8..0xE1) i.u16(a, 0)
        listOf(230.5, 231.0, 229.5).forEachIndexed { n, v -> i.f32(2 * n, v, swapped) }
        listOf(3.0, 2.0, 1.0).forEachIndexed { n, v -> i.f32(0x06 + 2 * n, v, swapped) }
        watts.forEachIndexed { n, v -> i.f32(0x0C + 2 * n, v, swapped) }
        i.f32(0x34, watts.sum(), swapped).f32(0x46, 49.98, swapped).f32(0x48, 4321.5, swapped).f32(0x4A, 1234.25, swapped)
        h.u32(0xFC00, 21_123_456L, swapped).f32(0x0A, 3.0, swapped)
        return i to h
    }

    @Test
    fun eastronSdm630BothWordOrders() {
        for (swapped in listOf(false, true)) {
            val (i2, h2) = sdm630(listOf(1000.0, 500.0, -250.0), swapped)
            same("swapped=$swapped", spec("eastron-sdm", SpecTables(holding = h2, input = i2), 7))
        }
    }

    @Test
    fun eastronIdentify() = runBlocking {
        val (i, h) = sdm630(listOf(0.0, 0.0, 0.0), false)
        val info = spec("eastron-sdm", SpecTables(holding = h, input = i), 7).identify()
        assertEquals("21123456", info.serial)
    }

    // ---------------------------------------------------------------- KEBA P30

    private fun keba(state: Long, error: Long) = FakeRegisters().on(255)
        .u32(1000, state).u32(1004, 7).u32(1006, error)
        .u32(1008, 16_000).u32(1010, 15_500).u32(1012, 0)
        .u32(1014, 18_416_854).u32(1016, 304_111).u32(1018, 0x030A0D00)
        .u32(1020, 11_040_000).u32(1036, 38_101).u32(1040, 230).u32(1042, 231).u32(1044, 229)
        .u32(1046, 928).u32(1100, 16_000).u32(1110, 32_000).u32(1500, 0).u32(1502, 165)
        .u32(1550, 3).u32(1552, 3).u32(1600, 6_000).u32(1602, 11)

    @Test
    fun kebaChargingAndFault() {
        for ((state, error) in listOf(3L to 0L, 4L to 0x40000L)) {
            val f = keba(state, error)
            same("state=$state error=$error", spec("keba-kecontact-p30", SpecTables(holding = f, input = f), 255))
        }
    }

    @Test
    fun kebaIdentify() = runBlocking {
        val f = keba(3, 0)
        assertEquals("18416854", spec("keba-kecontact-p30", SpecTables(holding = f, input = f), 255).identify().serial)
    }
}
