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

class CarloGavazziMeterDriverTest {
    /**
     * New map (EM24 E1, EM300, EM210): V L-N 230.1/231.2/229.8 V, currents 2.5/1.5/0.5 A, W per phase given,
     * PF 0.95, Hz 50.0, kWh(+) 12345.6, kWh(−) 6543.2. [lswFirst] = documented LSW→MSW word order.
     */
    private fun newMap(
        id: Int, maxCount: Int, watts: List<Int>, lswFirst: Boolean = true, fill: IntRange = 0x00..0x51,
        hzRaw: Int = 500,
    ): FakeMeterImage {
        val f = FakeMeterImage(unitId = 1, maxCount = maxCount)
        for (a in fill) f.u16(a, 0)
        f.exact[0x000B to 1] = intArrayOf(id)
        listOf(2301, 2312, 2298).forEachIndexed { i, v -> f.s32(2 * i, v, lswFirst) }
        listOf(4000, 3995, 3990).forEachIndexed { i, v -> f.s32(0x06 + 2 * i, v, lswFirst) }
        listOf(2500, 1500, 500).forEachIndexed { i, v -> f.s32(0x0C + 2 * i, v, lswFirst) }
        watts.forEachIndexed { i, v -> f.s32(0x12 + 2 * i, v * 10, lswFirst) }
        f.s32(0x24, 2304, lswFirst).s32(0x26, 3995, lswFirst).s32(0x28, watts.sum() * 10, lswFirst)
        for (i in 0 until 4) f.u16(0x2E + i, 950)
        f.u16(0x33, hzRaw)
        f.s32(0x34, 123456, lswFirst)
        f.s32(0x4E, 65432, lswFirst)
        return f
    }

    @Test
    fun em24EthernetIdentifiesByIdCodeAndSerial() = runBlocking {
        val f = newMap(1648, 125, listOf(0, 0, 0)).ascii(0x5000, 7, "BP12345678901")
        val info = CarloGavazziMeterDriver(f, 1).identify()
        assertEquals("Carlo Gavazzi EM24 Ethernet (EM24DINAV23XE1X)", info.model)
        assertEquals("BP12345678901", info.serial)
        assertEquals(0x000B to 1, f.requests.first()) // the ID code only answers a 1-word request
    }

    @Test
    fun threePhaseImportIsNegative() = runBlocking {
        val f = newMap(341, 50, listOf(500, 600, 400)) // EM340, importing 1.5 kW
        f.u16(0x1002, 0).u16(0x1103, 1)
        val r = CarloGavazziMeterDriver(f, 1).read()
        assertEquals(-1500, r.gridPowerW)
        assertEquals(listOf("L1", "L2", "L3"), r.phases.map { it.name })
        assertEquals(230.1, r.phases[0].voltageV, 1e-9)
        assertEquals(2.5, r.phases[0].currentA!!, 1e-9)
        assertEquals(50.0, r.phases[0].frequencyHz, 1e-9)
        assertEquals(listOf(-500, -600, -400), r.phases.map { it.powerW })
        assertEquals(EnergyTotals(), r.today)
        assertEquals("12345.6 kWh", r.detailRow("Energy (lifetime)", "Imported kWh(+)"))
        assertEquals("6543.2 kWh", r.detailRow("Energy (lifetime)", "Exported kWh(−)"))
        assertEquals("0.950", r.detailRow("L1", "Power factor"))
        assertNull(r.status)
        assertTrue(f.requests.all { it.second <= 50 })
    }

    @Test
    fun threePhaseExportIsPositive() = runBlocking {
        val f = newMap(1651, 125, listOf(-700, -800, -500)) // EM24 E1 exporting 2 kW
        val r = CarloGavazziMeterDriver(f, 1).read()
        assertEquals(2000, r.gridPowerW)
        assertEquals(listOf(700, 800, 500), r.phases.map { it.powerW })
        assertTrue((0x0000 to 0x52) in f.requests) // one request for all measurements on the E1
        assertEquals(1, f.requests.count { it.first < 0x52 && it != (0x000B to 1) })
    }

    @Test
    fun wordOrderLswFirstAndEngineeringSampleMswFirst() = runBlocking {
        // −2000.0 W in LSW→MSW order: words 0xB1E0 (low), 0xFFFF (high).
        val f = newMap(341, 50, listOf(0, 0, 0))
        f.words(0x28, 0xB1E0, 0xFFFF)
        assertEquals(2000, CarloGavazziMeterDriver(f, 1).read().gridPowerW)

        val sample = newMap(340, 50, listOf(-100, -100, -100), lswFirst = false)
        val r = CarloGavazziMeterDriver(sample, 1).read()
        assertEquals(300, r.gridPowerW)
        assertEquals(230.1, r.phases[0].voltageV, 1e-9)
    }

    @Test
    fun em210SinglePhaseReadsOnlyDocumentedRegisters() = runBlocking {
        // Only 0000h–0037h and 004Eh exist (strict fake): the undocumented gap must not be read.
        val f = newMap(210, 61, listOf(-1200, 0, 0), fill = 0x00..0x37, hzRaw = 50)
        f.u16(0x1002, 3) // 1P
        val r = CarloGavazziMeterDriver(f, 1).read()
        assertEquals(1200, r.gridPowerW)
        assertEquals(listOf("L1"), r.phases.map { it.name })
        assertEquals(50.0, r.phases[0].frequencyHz, 1e-9) // EM210: "Value weight: Hz"
        assertEquals("1P (3)", r.detailRow("Meter", "Measuring system"))
        assertTrue(f.requests.none { (a, c) -> a < 0x4E && a + c > 0x38 })
    }

    @Test
    fun oldEm24DinUsesItsOwnMapAndElevenRegisterRequests() = runBlocking {
        val f = FakeMeterImage(unitId = 5, maxCount = 11)
        for (a in 0x00..0x67) f.u16(a, 0)
        f.exact[0x000B to 1] = intArrayOf(72)
        listOf(2300, 2310, 2320).forEachIndexed { i, v -> f.s32(2 * i, v, lswFirst = true) }
        listOf(1000, 2000, 3000).forEachIndexed { i, v -> f.s32(0x0C + 2 * i, v, lswFirst = true) }
        listOf(2300, 4600, -900).forEachIndexed { i, v -> f.s32(0x12 + 2 * i, v, lswFirst = true) }
        f.s32(0x28, 6000, lswFirst = true) // 600 W import
        f.u16(0x37, 499) // Hz ×10 at 0037h (new map: 0033h)
        f.s32(0x3E, 1000, lswFirst = true).s32(0x5C, 20, lswFirst = true)
        f.u16(0x0302, 128 + 4).ascii(0x1300, 7, "ABC1234567890").u16(0x1102, 0)
        val d = CarloGavazziMeterDriver(f, 5)
        assertEquals("Carlo Gavazzi EM24-DIN (AV5)", d.identify().model)
        val r = d.read()
        assertEquals(-600, r.gridPowerW)
        assertEquals(listOf(-230, -460, 90), r.phases.map { it.powerW })
        assertEquals(49.9, r.phases[0].frequencyHz, 1e-9)
        assertEquals("100.0 kWh", r.detailRow("Energy (lifetime)", "Imported kWh(+)"))
        assertEquals("2.0 kWh", r.detailRow("Energy (lifetime)", "Exported kWh(−)"))
        assertEquals("EM24DINAV23XO2PFB", r.detailRow("Meter", "Item number"))
        assertEquals("ABC1234567890", r.detailRow("Meter", "Serial number"))
    }

    @Test
    fun nonMatchingDeviceThrows() {
        val unknown = FakeMeterImage(1, 125).also { it.exact[0x000B to 1] = intArrayOf(999) }
        assertThrows(IOException::class.java) { runBlocking { CarloGavazziMeterDriver(unknown, 1).identify() } }
        val silent = FakeMeterImage(1, 125) // answers every read with Modbus exception 2
        assertThrows(IOException::class.java) { runBlocking { CarloGavazziMeterDriver(silent, 1).identify() } }
    }

    @Test
    fun overflowAndMissingStaticValuesAreLeftOut() = runBlocking {
        val f = newMap(332, 50, listOf(100, 100, 100))
        f.words(0x14, 0xFFFF, 0x7FFF) // W L2 overflow "EEEE": MSW 7FFFh
        // No serial, firmware or setup registers at all: the static reads fail, the live data still comes.
        val r = CarloGavazziMeterDriver(f, 1).read()
        assertNull(r.phases[1].powerW)
        assertEquals(-100, r.phases[0].powerW)
        assertNull(r.detailRow("Meter", "Serial number"))
        assertNull(r.detailRow("Meter", "Measuring system"))
        assertEquals(3, r.phases.size)
    }

    @Test
    fun easyConnectionModeAndWiringErrorsShowInStatus() = runBlocking {
        val f = newMap(341, 50, listOf(100, 0, 0)).u16(0x1103, 0)
        assertTrue(CarloGavazziMeterDriver(f, 1).read().status!!.startsWith("Easy connection"))
        val g = newMap(341, 50, listOf(100, 0, 0)).u16(0x1103, 1).u16(0x1105, 0b100)
        assertEquals("Wiring: phase 2 inverted", CarloGavazziMeterDriver(g, 1).read().status)
    }
}
