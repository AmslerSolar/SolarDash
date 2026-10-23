// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.kostal

import ch.amslersolar.dashboard.driver.modbus.ModbusException
import ch.amslersolar.dashboard.driver.modbus.RegisterBlock
import ch.amslersolar.dashboard.driver.sunspec.RegisterReader
import ch.amslersolar.dashboard.model.DeviceConfig
import ch.amslersolar.dashboard.model.DeviceStatus
import ch.amslersolar.dashboard.model.DeviceType
import ch.amslersolar.dashboard.model.PlantAggregator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KostalPlenticoreDriverTest {
    /** A register image that, like a strict inverter, rejects any read touching an undefined register. */
    private class FakeKostal(private val wordSwapped: Boolean) : RegisterReader {
        val regs = HashMap<Int, Int>()
        var reads = 0

        init {
            regs[5] = if (wordSwapped) 0 else 1
        }

        fun u16(a: Int, v: Int) = apply { regs[a] = v and 0xFFFF }
        fun u32(a: Int, v: Long) = apply {
            val hi = ((v shr 16) and 0xFFFF).toInt()
            val lo = (v and 0xFFFF).toInt()
            if (wordSwapped) { regs[a] = lo; regs[a + 1] = hi } else { regs[a] = hi; regs[a + 1] = lo }
        }
        fun f32(a: Int, v: Double) = u32(a, v.toFloat().toRawBits().toLong() and 0xFFFFFFFFL)
        fun text(a: Int, n: Int, s: String) = apply {
            val padded = s.padEnd(n * 2, '\u0000')
            for (i in 0 until n) regs[a + i] = (padded[2 * i].code shl 8) or padded[2 * i + 1].code
        }

        override suspend fun read(unitId: Int, register: Int, count: Int): RegisterBlock {
            reads++
            assertEquals(71, unitId)
            val data = ByteArray(count * 2)
            for (i in 0 until count) {
                val v = regs[register + i] ?: throw ModbusException(2)
                data[2 * i] = (v shr 8).toByte()
                data[2 * i + 1] = v.toByte()
            }
            return RegisterBlock(register, data)
        }
    }

    /** Master: BYD battery on DC3, KSEM at the grid connection; charging 3 kW from 5 kW PV, exporting 1.2 kW. */
    private fun master(wordSwapped: Boolean = true) = FakeKostal(wordSwapped)
        .text(768, 32, "PLENTICORE plus").text(800, 32, "10").text(14, 8, "90123ABC")
        .u16(34, 2).u16(588, 4).u16(1082, 3).u16(1545, 2)
        .u32(56, 6)
        .f32(1066, 5_000.0)
        .f32(258, 6.0).f32(260, 3_000.0).f32(266, 500.0)
        .f32(268, 4.0).f32(270, 2_000.0).f32(276, 500.0)
        .u16(582, 3_000).f32(200, -6.5).f32(216, 460.0).u16(514, 56).f32(210, 56.4)
        .f32(252, -1_200.0).f32(220, 50.0)
        .f32(116, 800.0).f32(106, 0.0).f32(108, 0.0)
        .f32(152, 50.01).f32(154, 1.5).f32(156, 330.0).f32(158, 231.0)
        .f32(160, 1.4).f32(162, 320.0).f32(164, 232.0).f32(166, 1.6).f32(168, 350.0).f32(170, 230.0)
        .f32(98, 41.5).f32(322, 18_400.0).f32(320, 23_500_000.0)

    /** Slave: PV only, no battery, no meter, big-endian, no 1066 on this firmware. */
    private fun slave() = FakeKostal(wordSwapped = false)
        .text(768, 32, "PLENTICORE plus").text(800, 32, "10")
        .u16(34, 3).u16(588, 0).u16(1082, 0xFF).u16(1545, 1)
        .f32(258, 3.0).f32(260, 1_500.0).f32(266, 500.0)
        .f32(268, 2.0).f32(270, 1_000.0).f32(276, 500.0)
        .f32(278, 1.0).f32(280, 500.0).f32(286, 500.0)
        .f32(252, 0.0).f32(322, 9_000.0)

    @Test
    fun masterWithBatteryAndMeter() = runBlocking {
        val fake = master()
        val driver = KostalPlenticoreDriver(fake, 71)
        val info = driver.identify()
        assertEquals("PLENTICORE plus 10", info.model)
        assertEquals("90123ABC", info.serial)

        val r = driver.read()
        assertEquals(5_000, r.pvPowerW)
        assertEquals(-3_000, r.battery?.powerW) // current −6.5 A: charging
        assertEquals("Charging", r.battery?.mode)
        assertEquals(56, r.battery?.socPercent)
        assertEquals(1_200, r.gridPowerW) // meter −1200 W (feed-in) → +1200 export
        assertEquals(800, r.loadPowerW)
        assertEquals(listOf("L1", "L2", "L3"), r.phases.map { it.name })
        assertEquals(231.0, r.phases[0].voltageV, 1e-3)
        assertEquals(18.4, r.today.pvKwh!!, 1e-6)
        assertEquals("Feed-in", r.status)
        assertEquals(2, r.pvStrings.size) // DC3 is the battery
        val titles = r.details.map { it.title }
        assertTrue(titles.containsAll(listOf("Device", "Inverter (AC)", "PV", "Battery", "Energy meter", "Home consumption")))
        assertTrue(r.details.first { it.title == "Battery" }.rows.contains("Type" to "BYD (4)"))
    }

    @Test
    fun bigEndianMasterReadsTheSame() = runBlocking {
        val r = KostalPlenticoreDriver(master(wordSwapped = false), 71).read()
        assertEquals(5_000, r.pvPowerW)
        assertEquals(1_200, r.gridPowerW)
        assertEquals(-3_000, r.battery?.powerW)
    }

    @Test
    fun slaveHasNoBatteryGridOrLoad() = runBlocking {
        val r = KostalPlenticoreDriver(slave(), 71).read()
        assertNull(r.battery)
        assertNull(r.gridPowerW)
        assertNull(r.loadPowerW)
        assertEquals(3_000, r.pvPowerW) // no 1066: sum of DC1–DC3
        assertTrue(r.details.none { it.title == "Battery" })
    }

    @Test
    fun rejectedBlocksAreReadFieldByFieldOnlyOnce() = runBlocking {
        val fake = master()
        val driver = KostalPlenticoreDriver(fake, 71)
        driver.read()
        val first = fake.reads
        driver.read()
        val second = fake.reads - first
        assertTrue("second poll should skip known-missing fields ($second vs $first reads)", second < first)
        assertEquals(5_000, driver.read().pvPowerW)
    }

    @Test
    fun masterAndSlaveMakeOnePlantWithoutDoubleCounting() = runBlocking {
        val m = DeviceConfig(id = "m", name = "Master", type = DeviceType.KOSTAL_PLENTICORE)
        val s = DeviceConfig(id = "s", name = "Slave", type = DeviceType.KOSTAL_PLENTICORE)
        val plant = PlantAggregator.aggregate(
            listOf(
                DeviceStatus(m, KostalPlenticoreDriver(master(), 71).read(), null),
                DeviceStatus(s, KostalPlenticoreDriver(slave(), 71).read(), null),
            ),
        )
        assertEquals(8_000, plant.pvPowerW)
        assertEquals(1_200, plant.gridPowerW) // the master's meter only
        assertEquals(-3_000, plant.battery?.powerW)
        // The slave has no own load figure, so home is the balance: 8000 − 3000 − 1200.
        assertEquals(3_800, plant.loadPowerW)
        assertEquals(27.4, plant.today.pvKwh!!, 1e-6)
    }

    @Test
    fun blocksStayWithinModbusLimits() {
        val blocks = KostalPlenticoreDriver.blocks(KostalPlenticoreDriver.STATIC_FIELDS + KostalPlenticoreDriver.LIVE_FIELDS)
        for (b in blocks) assertTrue(b.last().first + b.last().second - b.first().first <= 125)
    }
}
