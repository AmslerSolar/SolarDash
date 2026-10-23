// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.sunspec

import ch.amslersolar.dashboard.driver.modbus.ModbusException
import ch.amslersolar.dashboard.driver.modbus.RegisterBlock
import ch.amslersolar.dashboard.model.DeviceProblem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SunSpecInverterDriverTest {
    /** One unit's SunSpec chain from 40000, model by model (offsets from each model's ID register). */
    private class Chain : RegisterReader {
        val regs = HashMap<Int, Int>()
        private var next = 40002
        init { regs[40000] = 0x5375; regs[40001] = 0x6E53 }

        fun model(id: Int, length: Int, fill: Chain.(Int) -> Unit) = apply {
            regs[next] = id; regs[next + 1] = length
            for (o in 2 until length + 2) regs[next + o] = 0
            fill(next)
            next += length + 2
        }
        fun end() = apply { regs[next] = 0xFFFF; regs[next + 1] = 0 }
        fun u16(a: Int, v: Int) { regs[a] = v and 0xFFFF }
        fun text(a: Int, n: Int, s: String) {
            val p = s.padEnd(n * 2, '\u0000')
            for (i in 0 until n) u16(a + i, (p[2 * i].code shl 8) or p[2 * i + 1].code)
        }

        override suspend fun read(unitId: Int, register: Int, count: Int): RegisterBlock {
            assertEquals(1, unitId)
            val data = ByteArray(count * 2)
            for (i in 0 until count) {
                val v = regs[register + i] ?: throw ModbusException(2)
                data[2 * i] = (v shr 8).toByte(); data[2 * i + 1] = v.toByte()
            }
            return RegisterBlock(register, data)
        }
    }

    /**
     * SolarEdge-style: inverter (103, int + SF) at 4.5 kW AC, then the meter's own common block and a 203 meter
     * reading +1.2 kW (feed-in under SolarEdge's convention), all on unit 1.
     */
    private fun solarEdge(maker: String = "SolarEdge") = Chain()
        .model(1, 65) { b -> text(b + 2, 16, maker); text(b + 18, 16, "SE10K"); text(b + 42, 8, "4.18.32"); text(b + 50, 16, "7E1234AB") }
        .model(103, 50) { b ->
            u16(b + 14, 4_500); u16(b + 15, 0) // W
            u16(b + 16, 5_000); u16(b + 17, 0xFFFE) // 50.00 Hz
            u16(b + 10, 2_310); u16(b + 11, 2_320); u16(b + 12, 2_300); u16(b + 13, 0xFFFF)
            u16(b + 31, 4_650); u16(b + 32, 0) // DC W
            u16(b + 38, 4)
        }
        .model(1, 65) { b -> text(b + 2, 16, maker); text(b + 18, 16, "Meter") }
        .model(203, 105) { b -> u16(b + 18, 1_200); u16(b + 22, 0) }
        .end()

    @Test
    fun solarEdgeChainWithMeterOnTheSameUnit() = runBlocking {
        val driver = SunSpecInverterDriver(solarEdge(), 1, SunSpecProfiles.SOLAREDGE)
        val info = driver.identify()
        assertEquals("SE10K (firmware 4.18.32)", info.model)
        assertEquals("7E1234AB", info.serial)
        val r = driver.read()
        assertEquals(4_650, r.pvPowerW) // no model 160: the inverter's DC power
        assertEquals(1_200, r.gridPowerW) // SolarEdge convention: + = feed-in
        assertEquals(3_300, r.loadPowerW)
        assertEquals("Producing", r.status)
        assertTrue(r.details.any { it.title == "Energy meter (grid)" })
    }

    @Test
    fun gridSignCanBeInverted() = runBlocking {
        val r = SunSpecInverterDriver(solarEdge(), 1, SunSpecProfiles.SOLAREDGE.invertedGrid(true)).read()
        assertEquals(-1_200, r.gridPowerW)
        assertEquals(5_700, r.loadPowerW)
    }

    @Test
    fun makeSpecificProfileRejectsOtherMakesGenericAcceptsThem() = runBlocking {
        val other = solarEdge(maker = "SMA")
        val error = runCatching { SunSpecInverterDriver(other, 1, SunSpecProfiles.SOLAREDGE).identify() }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("Not a SolarEdge"))
        assertEquals("SMA SE10K (firmware 4.18.32)", SunSpecInverterDriver(other, 1, SunSpecProfiles.GENERIC).identify().model)
    }

    @Test
    fun batteryBankModel802GivesDirectionFromChargeStatus() = runBlocking {
        val chain = Chain()
            .model(1, 65) { b -> text(b + 2, 16, "Acme"); text(b + 18, 16, "Hybrid 8") }
            .model(103, 50) { b -> u16(b + 14, 1_000); u16(b + 15, 0); u16(b + 31, 3_000); u16(b + 32, 0); u16(b + 38, 4) }
            .model(802, 62) { b ->
                u16(b + 11, 640); u16(b + 56, 0xFFFF) // SoC 64.0 %
                u16(b + 16, 4) // charging
                u16(b + 47, 1_900); u16(b + 63, 0) // W, no defined sign
                u16(b + 34, 5_120); u16(b + 59, 0xFFFE) // 51.20 V
            }
            .end()
        val r = SunSpecInverterDriver(chain, 1, SunSpecProfiles.GENERIC).read()
        assertEquals(-1_900, r.battery?.powerW)
        assertEquals(64, r.battery?.socPercent)
        assertEquals(51.2, r.battery?.voltageV!!, 1e-9)
        assertEquals("Charging", r.battery?.mode)
        assertNull(r.gridPowerW) // no meter model in the chain
        assertTrue(r.details.any { it.title == "Battery bank (SunSpec 802)" })
    }

    @Test
    fun noProblemsWhileProducing() = runBlocking {
        assertEquals(emptyList<DeviceProblem>(), SunSpecInverterDriver(solarEdge(), 1, SunSpecProfiles.SOLAREDGE).read().problems)
    }

    @Test
    fun faultStateAndEventBitsOfTheIntModel() = runBlocking {
        val chain = Chain()
            .model(1, 65) { b -> text(b + 2, 16, "Acme"); text(b + 18, 16, "Inv 10") }
            .model(103, 50) { b ->
                u16(b + 38, 7); u16(b + 39, 43) // St FAULT, vendor state 43
                u16(b + 40, 0); u16(b + 41, 0x0801) // Evt1: GROUND_FAULT, AC_UNDER_VOLT
            }
            .end()
        val r = SunSpecInverterDriver(chain, 1, SunSpecProfiles.GENERIC).read()
        assertEquals("Fault", r.status)
        assertEquals(
            listOf(
                DeviceProblem(DeviceProblem.Kind.FAULT, "Inverter fault (vendor state 43)"),
                DeviceProblem(DeviceProblem.Kind.FAULT, "Ground fault (Evt1 bit 0)"),
                DeviceProblem(DeviceProblem.Kind.MAINS_LOST, "Grid under-voltage (Evt1 bit 11)"),
            ),
            r.problems,
        )
    }

    @Test
    fun eventBitsOfTheFloatModelAndOfTheMpptInputs() = runBlocking {
        val chain = Chain()
            .model(1, 65) { b -> text(b + 2, 16, "Acme"); text(b + 18, 16, "Inv 10") }
            .model(113, 60) { b ->
                u16(b + 48, 4) // MPPT: running
                u16(b + 50, 0); u16(b + 51, 0x0080) // Evt1: OVER_TEMP
            }
            .model(160, 48) { b ->
                u16(b + 8, 2)
                u16(b + 10, 1); u16(b + 27, 4); u16(b + 28, 0x0000); u16(b + 29, 0x8000) // input 1: ARC_DETECTION
                u16(b + 30, 2); u16(b + 47, 7) // input 2: DCSt FAULT
            }
            .end()
        val r = SunSpecInverterDriver(chain, 1, SunSpecProfiles.GENERIC).read()
        assertEquals(
            listOf(
                DeviceProblem(DeviceProblem.Kind.WARNING, "Over-temperature (Evt1 bit 7)"),
                DeviceProblem(DeviceProblem.Kind.FAULT, "Input 1: arc detected (DCEvt bit 15)"),
                DeviceProblem(DeviceProblem.Kind.FAULT, "Input 2: fault (DCSt 7)"),
            ),
            r.problems,
        )
    }
}
