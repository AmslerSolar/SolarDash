// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.lg

import ch.amslersolar.dashboard.driver.modbus.ModbusException
import ch.amslersolar.dashboard.driver.modbus.RegisterBlock
import ch.amslersolar.dashboard.driver.sunspec.RegisterReader
import ch.amslersolar.dashboard.model.DeviceProblem
import ch.amslersolar.dashboard.model.DeviceProblem.Kind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Against a register image laid out like LG's SunSpec map ver. 1.3, with the scale-factor convention and values
 * read from a real Home 10 (LG divides by 10^SF).
 */
class LgEssSunSpecDriverTest {
    class FakeLg(batteryW: Int = 848, chaSt: Int = 4, actPower: Int = -55000, socX10: Int = 529, acW: Float = 2140f) : RegisterReader {
        val regs = HashMap<Int, Int>()
        val addr = HashMap<Int, Int>()

        init {
            put(0, 0x5375, 0x6E53)
            var a = 2
            for ((id, length) in listOf(1 to 66, 103 to 50, 113 to 60, 120 to 26, 123 to 24, 802 to 62, 64256 to 20)) {
                addr[id] = a
                put(a, id, length, *IntArray(length) { 0xFFFF })
                a += 2 + length
            }
            put(a, 0xFFFF, 0)
            at(1, 2, *text("LG Electronics", 16)); at(1, 18, *text("D010KE1N211", 16))
            at(1, 42, *text("10.05.7230", 8)); at(1, 50, *text("SN12345", 16))
            at(113, 22, *f32(acW)); at(113, 40, *f32(41.5f)); at(113, 48, 4)
            // 802 as on the real unit: SoC with SoC_SF 1 (÷10), V reads 2, W in watts, offset 63 = 1 (not W's SF)
            at(802, 11, socX10); at(802, 16, chaSt); at(802, 34, 2); at(802, 47, batteryW and 0xFFFF)
            at(802, 56, 1); at(802, 59, 1); at(802, 63, 1)
            // 64256 as on the real unit: ActPower with SF 2 (÷100), voltages and frequency with SF 1 (÷10)
            at(64256, 2, actPower ushr 16 and 0xFFFF, actPower and 0xFFFF, 2)
            at(64256, 5, 2342, 2376, 2385, 1, 500, 1)
            at(64256, 19, 0, 25_938 ushr 16, 25_938 and 0xFFFF) // today's PV, Wh
        }

        private fun put(a: Int, vararg v: Int) = v.forEachIndexed { i, x -> regs[a + i] = x }
        private fun at(model: Int, offset: Int, vararg v: Int) = put(addr.getValue(model) + offset, *v)
        private fun text(s: String, size: Int): IntArray {
            val b = s.toByteArray().copyOf(size * 2)
            return IntArray(size) { ((b[2 * it].toInt() and 0xFF) shl 8) or (b[2 * it + 1].toInt() and 0xFF) }
        }
        private fun f32(v: Float): IntArray = v.toRawBits().let { intArrayOf(it ushr 16, it and 0xFFFF) }

        override suspend fun read(unitId: Int, register: Int, count: Int): RegisterBlock {
            if (register !in regs) throw ModbusException(2)
            val buf = ByteBuffer.allocate(count * 2)
            repeat(count) { buf.putShort((regs[register + it] ?: 0).toShort()) }
            return RegisterBlock(register, buf.array())
        }
    }

    @Test
    fun identifies() = runBlocking {
        val info = LgEssSunSpecDriver(FakeLg(), 1).identify()
        assertEquals("LG Electronics D010KE1N211 (firmware 10.05.7230)", info.model)
        assertEquals("SN12345", info.serial)
    }

    @Test
    fun chargingAndExporting() = runBlocking {
        val r = LgEssSunSpecDriver(FakeLg(), 1).read()
        assertEquals(-848, r.battery?.powerW)
        assertEquals(53, r.battery?.socPercent)
        assertNull(r.battery?.voltageV)
        assertEquals(550, r.gridPowerW) // −55000 / 10^2 feed-in → export
        assertEquals(2140 + 848, r.pvPowerW)
        assertEquals(2140 - 550, r.loadPowerW)
        assertEquals(listOf("L1" to 234.2, "L2" to 237.6, "L3" to 238.5), r.phases.map { it.name to it.voltageV })
        assertTrue(r.phases.all { it.currentA == null && it.powerW == null }) // the meter has only a total
        assertEquals(25.938, r.today.pvKwh!!, 1e-9)
        assertNull(r.lifetimePvKwh)
        assertEquals("Producing", r.status)
    }

    @Test
    fun valuesReadFromTheRealUnit() = runBlocking {
        // Night snapshot: 317 W AC from the battery (327 W), 6.06 W purchase, SoC 23.3 %.
        val r = LgEssSunSpecDriver(FakeLg(batteryW = 327, chaSt = 3, actPower = 606, socX10 = 233, acW = 317f), 1).read()
        assertEquals(23, r.battery?.socPercent)
        assertEquals(327, r.battery?.powerW)
        assertEquals(-6, r.gridPowerW)
        assertEquals(0, r.pvPowerW)
        assertEquals(323, r.loadPowerW)
    }

    @Test
    fun detailsListEveryModelWithL1L2L3Names() = runBlocking {
        val r = LgEssSunSpecDriver(FakeLg(batteryW = 327, chaSt = 3, actPower = 606, socX10 = 233), 1).read()
        val titles = r.details.map { it.title }
        assertTrue(titles.first().startsWith("Identity"))
        val battery = r.details.first { it.title.startsWith("Battery") }.rows.toMap()
        assertEquals("23.3 %", battery["State of charge"])
        assertEquals("Discharging (3)", battery["Charge status"])
        val meter = r.details.first { it.title.startsWith("Power meter") }.rows.toMap()
        assertEquals("6.1 W", meter["Active power (+ purchase, − feed-in)"])
        assertEquals("234.2 V", meter["L1 voltage"])
        assertEquals("25.9 kWh", meter["PV generation (today)"])
    }

    @Test
    fun problemsFromEventsAndStates() = runBlocking {
        assertEquals(emptyList<DeviceProblem>(), LgEssSunSpecDriver(FakeLg(), 1).read().problems)
        val fake = FakeLg().apply {
            val inv = addr.getValue(113); val bat = addr.getValue(802)
            regs[inv + 48] = 7; regs[inv + 50] = 0; regs[inv + 51] = (1 shl 4) or (1 shl 7) // grid disconnect, over-temperature
            regs[bat + 22] = 99; regs[bat + 26] = 0; regs[bat + 27] = 1 shl 2 // over-temperature warning
        }
        assertEquals(
            listOf(
                DeviceProblem(Kind.MAINS_LOST, "Grid disconnect (event bit 4)"),
                DeviceProblem(Kind.WARNING, "Over-temperature (event bit 7)"),
                DeviceProblem(Kind.FAULT, "Fault (state 7)"),
                DeviceProblem(Kind.WARNING, "Battery over-temperature warning (event bit 2)"),
                DeviceProblem(Kind.FAULT, "Battery fault (bank state 99)"),
            ),
            LgEssSunSpecDriver(fake, 1).read().problems,
        )
    }
}
