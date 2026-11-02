// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.driver.fronius

import ch.amslersolar.solardash.driver.modbus.ModbusException
import ch.amslersolar.solardash.driver.modbus.RegisterBlock
import ch.amslersolar.solardash.driver.sunspec.RegisterReader
import ch.amslersolar.solardash.model.DeviceProblem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FroniusGen24DriverTest {
    /** SunSpec register images per unit id, built model by model from 40000 like a GEN24. */
    private class FakeGen24 : RegisterReader {
        val units = HashMap<Int, HashMap<Int, Int>>()
        var longestRead = 0

        inner class Map(val unit: Int) {
            private val regs = units.getOrPut(unit) { HashMap() }
            private var next = 40002
            init { regs[40000] = 0x5375; regs[40001] = 0x6E53 }

            /** Adds a model; [fill] sets registers by offset from its ID register. */
            fun model(id: Int, length: Int, fill: Model.() -> Unit) = apply {
                val m = Model(next)
                regs[next] = id; regs[next + 1] = length
                for (o in 2 until length + 2) regs[next + o] = 0
                m.fill()
                next += length + 2
            }

            fun end() = apply { regs[next] = 0xFFFF; regs[next + 1] = 0 }

            inner class Model(private val base: Int) {
                fun u16(o: Int, v: Int) { regs[base + o] = v and 0xFFFF }
                fun u32(o: Int, v: Long) { u16(o, (v shr 16).toInt()); u16(o + 1, v.toInt()) }
                fun f32(o: Int, v: Double) = u32(o, v.toFloat().toRawBits().toLong() and 0xFFFFFFFFL)
                fun text(o: Int, n: Int, s: String) {
                    val p = s.padEnd(n * 2, '\u0000')
                    for (i in 0 until n) u16(o + i, (p[2 * i].code shl 8) or p[2 * i + 1].code)
                }
            }
        }

        override suspend fun read(unitId: Int, register: Int, count: Int): RegisterBlock {
            if (count > 125) throw ModbusException(3)
            longestRead = maxOf(longestRead, count)
            val regs = units[unitId] ?: throw ModbusException(0x0B) // gateway: target device failed to respond
            val data = ByteArray(count * 2)
            for (i in 0 until count) {
                val v = regs[register + i] ?: throw ModbusException(2)
                data[2 * i] = (v shr 8).toByte(); data[2 * i + 1] = v.toByte()
            }
            return RegisterBlock(register, data)
        }
    }

    private fun common(m: FakeGen24.Map.Model, model: String, serial: String) {
        m.text(2, 16, "Fronius"); m.text(18, 16, model); m.text(42, 8, "1.36.6-1"); m.text(50, 16, serial)
    }

    /**
     * GEN24 Plus 10.0 with BYD in "float" mode: PV 4.2 kW on two inputs, charging the battery with 1.5 kW,
     * exporting 1.9 kW (meter −1900 W); AC output 2.6 kW, so the home uses 700 W.
     */
    private fun float(withMeter: Boolean = true) = FakeGen24().apply {
        Map(1)
            .model(1, 66) { common(this, "Primo GEN24 10.0 Plus", "34567890") }
            .model(113, 60) {
                f32(22, 2_600.0); f32(24, 50.02); f32(16, 231.5); f32(18, 232.0); f32(20, 229.8)
                f32(4, 3.8); f32(40, 41.0); u16(48, 4); f32(32, 12_345_000.0)
            }
            .model(120, 26) { u16(3, 10_000); u16(4, 0) }
            .model(160, 8 + 20 * 4) {
                u16(2, 0xFFFE); u16(3, 0xFFFF); u16(4, 0); u16(5, 0); u16(8, 4) // DCA ×10^-2, DCV ×10^-1
                listOf("PV1" to 2_500, "PV2" to 1_700, "StCha 3" to 1_500, "StDisCha 4" to 0).forEachIndexed { k, (name, w) ->
                    val o = 10 + 20 * k
                    u16(o, k + 1); text(o + 1, 8, name); u16(o + 9, 600); u16(o + 10, 4_200); u16(o + 11, w); u16(o + 17, 4)
                }
            }
            .model(124, 24) { u16(8, 6_150); u16(22, 0xFFFE); u16(11, 4); u16(10, 5_120); u16(24, 0xFFFF) } // 61.50 %, charging, 512 V
            .end()
        if (withMeter) {
            Map(200)
                .model(1, 66) { common(this, "Smart Meter TS 65A-3", "M123") }
                .model(213, 124) { f32(28, -1_900.0); f32(12, 233.0); f32(60, 5_000_000.0); f32(68, 1_200_000.0); f32(26, 50.0) }
                .end()
        }
    }

    /** The same plant in "int + SF" mode. */
    private fun intSf() = FakeGen24().apply {
        Map(1)
            .model(1, 66) { common(this, "Symo GEN24 10.0 Plus", "34567890") }
            .model(103, 50) {
                u16(14, 260); u16(15, 1) // 2600 W
                u16(16, 5_002); u16(17, 0xFFFE) // 50.02 Hz
                u16(10, 2_315); u16(11, 2_320); u16(12, 2_298); u16(13, 0xFFFF)
                u16(38, 4)
            }
            .model(160, 8 + 20 * 4) {
                u16(2, 0xFFFE); u16(3, 0xFFFF); u16(4, 1); u16(5, 0); u16(8, 4) // DCW ×10
                listOf("PV1" to 250, "PV2" to 170, "StCha 3" to 150, "StDisCha 4" to 0).forEachIndexed { k, (name, w) ->
                    val o = 10 + 20 * k
                    u16(o, k + 1); text(o + 1, 8, name); u16(o + 10, 4_200); u16(o + 11, w)
                }
            }
            .model(124, 24) { u16(8, 62); u16(22, 0); u16(11, 4) }
            .end()
        Map(200)
            .model(1, 66) { common(this, "Smart Meter", "M123") }
            .model(203, 105) { u16(18, (-190).toShort().toInt()); u16(22, 1) } // −1900 W
            .end()
    }

    @Test
    fun floatModelsWithBatteryAndMeter() = runBlocking {
        val fake = float()
        val driver = FroniusGen24Driver(fake, 1)
        assertEquals("Primo GEN24 10.0 Plus (firmware 1.36.6-1)", driver.identify().model)
        val r = driver.read()
        assertEquals(4_200, r.pvPowerW)
        assertEquals(-1_500, r.battery?.powerW)
        assertEquals("Charging", r.battery?.mode)
        assertEquals(62, r.battery?.socPercent) // 61.5 % rounds up
        assertEquals(512.0, r.battery?.voltageV!!, 1e-9)
        assertEquals(1_900, r.gridPowerW)
        assertEquals(700, r.loadPowerW)
        assertEquals(listOf("L1", "L2", "L3"), r.phases.map { it.name })
        assertEquals(listOf(1, 2), r.pvStrings.map { it.index })
        assertEquals(420.0, r.pvStrings[0].voltageV, 1e-9)
        assertEquals("Producing", r.status)
        assertEquals(12_345.0, r.lifetimePvKwh!!, 1e-6)
        assertTrue("meter model 213 needs two reads", fake.longestRead <= 125)
        val titles = r.details.map { it.title }
        assertTrue(titles.containsAll(listOf("Inverter", "Inverter (AC)", "DC inputs (MPPT)", "Battery", "Smart Meter (grid)", "Nameplate")))
    }

    @Test
    fun intAndScaleFactorModelsReadTheSame() = runBlocking {
        val r = FroniusGen24Driver(intSf(), 1).read()
        assertEquals(4_200, r.pvPowerW)
        assertEquals(-1_500, r.battery?.powerW)
        assertEquals(62, r.battery?.socPercent)
        assertEquals(1_900, r.gridPowerW)
        assertEquals(700, r.loadPowerW)
        assertEquals(50.02, r.phases[0].frequencyHz, 1e-9)
    }

    @Test
    fun withoutSmartMeterThereIsNoGridOrHome() = runBlocking {
        val r = FroniusGen24Driver(float(withMeter = false), 1).read()
        assertNull(r.gridPowerW)
        assertNull(r.loadPowerW)
        assertEquals(4_200, r.pvPowerW)
    }

    @Test
    fun otherMakesAreRejectedByIdentify() = runBlocking {
        val fake = FakeGen24().apply { Map(1).model(1, 66) { text(2, 16, "LG Electronics"); text(18, 16, "ELEC") }.end() }
        val error = runCatching { FroniusGen24Driver(fake, 1).identify() }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("Not a Fronius"))
    }

    @Test
    fun noProblemsInNormalOperation() = runBlocking {
        assertEquals(emptyList<DeviceProblem>(), FroniusGen24Driver(float(), 1).read().problems)
        assertEquals(emptyList<DeviceProblem>(), FroniusGen24Driver(intSf(), 1).read().problems)
    }

    @Test
    fun gridDisconnectEventIsMainsLost() = runBlocking {
        val fake = FakeGen24().apply {
            Map(1)
                .model(1, 66) { common(this, "Primo GEN24 10.0 Plus", "34567890") }
                .model(113, 60) { u16(48, 7); u16(49, 0); u32(50, 1L shl 4) } // FAULT, Evt1 GRID_DISCONNECT
                .end()
        }
        assertEquals(
            listOf(
                DeviceProblem(DeviceProblem.Kind.FAULT, "Inverter fault (St 7)"),
                DeviceProblem(DeviceProblem.Kind.MAINS_LOST, "Grid disconnected (Evt1 bit 4)"),
            ),
            FroniusGen24Driver(fake, 1).read().problems,
        )
    }
}
