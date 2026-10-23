// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.ingeteam

import ch.amslersolar.dashboard.driver.modbus.ModbusException
import ch.amslersolar.dashboard.driver.modbus.RegisterBlock
import ch.amslersolar.dashboard.driver.sunspec.RegisterReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class IngeteamDriverTest {
    /** One register table (holding or input); reads touching an undefined register fail like a strict device. */
    private class Regs : RegisterReader {
        val regs = HashMap<Int, Int>()
        val requests = mutableListOf<Pair<Int, Int>>()
        fun u16(a: Int, v: Int) = apply { regs[a] = v and 0xFFFF }
        fun u32(a: Int, v: Long) = apply { u16(a, (v shr 16).toInt()); u16(a + 1, v.toInt()) }
        fun fill(range: IntRange, v: Int = 0) = apply { range.forEach { regs.putIfAbsent(it, v) } }

        override suspend fun read(unitId: Int, register: Int, count: Int): RegisterBlock {
            assertEquals(1, unitId)
            assertTrue(count in 1..124)
            requests += register to count
            val data = ByteArray(count * 2)
            for (i in 0 until count) {
                val v = regs[register + i] ?: throw ModbusException(2)
                data[2 * i] = (v shr 8).toByte(); data[2 * i + 1] = v.toByte()
            }
            return RegisterBlock(register, data)
        }
    }

    private fun driver(holding: Regs, input: Regs = Regs()) = IngeteamDriver(holding, input, 1, requestGapMs = 0)

    /** Current generation 3Play: 9.8 kW DC on two MPPTs, 3 × 230 V, 50.00 Hz. [reserved]: reserved registers readable. */
    private fun play3Current(reserved: Boolean = true) = Regs().apply {
        if (reserved) { fill(0x1001..0x1041); fill(0x1050..0x1093) } else {
            fill(0x1001..0x1024); fill(0x1027..0x1028); fill(0x1036..0x1041); fill(0x1050..0x1073); fill(0x1080..0x1093)
        }
        for (i in 0..2) {
            val b = 0x1001 + 5 * i
            u16(b, 2300 + 10 * i); u16(b + 1, 1400); u32(b + 2, 32_000); u16(b + 4, 5000)
        }
        u16(0x1010, 6205); u16(0x1011, 850); u32(0x1012, 52_700) // 620.5 V, 8.50 A, 5270.0 W
        u16(0x1014, 5980); u16(0x1015, 760); u32(0x1016, 45_300)
        u16(0x101C, 41); u16(0x101D, 3); u16(0x101E, 0x0004)
        u32(0x1021, 123_456); u32(0x1023, 9_876); u32(0x1027, 31_250)
        u16(0x1036, 0b1001) // over-temperature + initial ramp
        u32(0x1037, 96_000); u32(0x1039, (-1_500).toLong()); u16(0x103D, 998)
        u16(0x1050, 6200); u16(0x1051, 425)
    }

    @Test
    fun currentGenerationThreePhase() = runBlocking {
        val d = driver(play3Current())
        val info = d.identify()
        assertEquals("INGECON SUN 3Play", info.model)
        assertEquals("", info.serial)
        val r = d.read()
        assertEquals(9_800, r.pvPowerW) // 5270 + 4530 W DC
        assertEquals(listOf(1, 2), r.pvStrings.map { it.index })
        assertEquals(620.5, r.pvStrings[0].voltageV, 1e-9)
        assertEquals(8.5, r.pvStrings[0].currentA, 1e-9)
        assertEquals(5_270, r.pvStrings[0].powerW)
        assertEquals(listOf("L1", "L2", "L3"), r.phases.map { it.name })
        assertEquals(231.0, r.phases[1].voltageV, 1e-9)
        assertEquals(14.0, r.phases[0].currentA!!, 1e-9)
        assertEquals(50.0, r.phases[0].frequencyHz, 1e-9)
        assertEquals(3_200, r.phases[2].powerW) // [W x 10]: 32000 → 3200.0 W
        assertEquals("Connected to grid", r.status)
        assertEquals(41.0, r.temperatureC!!, 1e-9)
        assertEquals(31.25, r.today.pvKwh!!, 1e-9)
        assertEquals(123_456.0, r.lifetimePvKwh!!, 1e-9)
        assertNull(r.gridPowerW)
        assertNull(r.battery)
        val inv = r.details.first { it.title == "Inverter" }.rows.toMap()
        assertEquals("Over-temperature, Initial ramp", inv["Active power reduction"])
        val ac = r.details.first { it.title == "AC output" }.rows.toMap()
        assertEquals("9600 W", ac["Active power"])
        assertEquals("-150 var", ac["Reactive power"])
        assertTrue(r.details.first { it.title == "Strings" }.rows.contains("String 1 current" to "4.25 A"))
    }

    @Test
    fun wideReadsUseTwoRequestsAndStrictDonglesFallBack() = runBlocking {
        val wide = play3Current()
        driver(wide).read()
        assertEquals(listOf(0x1001 to 36, 0x1001 to 65, 0x1050 to 68), wide.requests)

        val strict = play3Current(reserved = false)
        val d = driver(strict)
        assertEquals(9_800, d.read().pvPowerW)
        strict.requests.clear()
        d.read()
        assertEquals(IngeteamDriver.STRICT_BLOCKS, strict.requests) // remembered: no second wide attempt
    }

    @Test
    fun onePlayMissingPhasesAndUnusedInputs() = runBlocking {
        val h = play3Current().apply {
            for (i in 1..2) { val b = 0x1001 + 5 * i; u16(b, 0); u16(b + 1, 0); u32(b + 2, 0); u16(b + 4, 0) }
            u16(0x1014, 0); u16(0x1015, 0); u32(0x1016, 0)
            u16(0x101C, 0xFFFF) // not available
            u32(0x1027, 0xFFFFFFFFL)
        }
        val d = driver(h)
        assertEquals("INGECON SUN 1Play", d.identify().model)
        val r = d.read()
        assertEquals(listOf("L1"), r.phases.map { it.name })
        assertEquals(1, r.pvStrings.size)
        assertEquals(5_270, r.pvPowerW)
        assertNull(r.temperatureC)
        assertNull(r.today.pvKwh)
    }

    /** Older 3Play (ABI1000), input registers: 14.5 kW AC, PV 15.0 kW on two inputs. */
    private fun legacy3Play() = Regs().apply {
        fill(0..66, 0xFFFF)
        listOf(2026, 10, 21, 13, 5, 30).forEachIndexed { i, v -> u16(i, v) }
        u32(6, 54_321); u16(26, 2)
        u16(29, 2100); u16(30, 2110); u16(31, 2090)
        u16(32, 2301); u16(33, 2310); u16(34, 2295); u16(35, 5001)
        u16(36, 1460); u16(37, 1450); u16(38, 20); u16(39, 995)
        u16(40, 1210); u16(41, 1190); u16(42, 640); u16(43, 630)
        u16(44, 1500); u16(45, 770); u16(46, 730)
        u16(47, 1500); u16(49, 100); u16(50, 0); u16(51, 0)
        for (i in 52..61) u16(i, 0)
        u16(52, 85)
        u32(63, 4_210) // 42.10 kWh
        u16(66, 0)
    }

    @Test
    fun legacyThreePlayFromInputRegisters() = runBlocking {
        val input = legacy3Play()
        val d = driver(Regs(), input) // no holding registers at all: exception 2 → older map
        assertEquals("INGECON SUN 3Play", d.identify().model)
        val r = d.read()
        assertEquals(15_000, r.pvPowerW) // "Wats/10": 1500 → 15 000 W
        assertEquals(listOf(640.0, 630.0), r.pvStrings.map { it.voltageV })
        assertEquals(12.1, r.pvStrings[0].currentA, 1e-9)
        assertEquals(7_700, r.pvStrings[0].powerW)
        assertEquals(230.1, r.phases[0].voltageV, 1e-9)
        assertEquals(21.0, r.phases[0].currentA!!, 1e-9)
        assertEquals(50.01, r.phases[2].frequencyHz, 1e-9)
        assertEquals("Connected to the grid", r.status)
        assertEquals(42.1, r.today.pvKwh!!, 1e-9)
        assertEquals(54_321.0, r.lifetimePvKwh!!, 1e-9)
        assertNull(r.gridPowerW)
        val more = r.details.first { it.title == "More" }.rows.toMap()
        assertEquals("14500 W", more["Active power (AC)"])
        assertEquals("8.5 A", more["String 1 current"])
        assertTrue(r.details.first { it.title == "Inverter" }.rows.contains("Clock" to "2026-10-21 13:05:30"))
    }

    @Test
    fun legacyOnePlayHfAndTlmAreTold() = runBlocking {
        fun onePlay(hf: Boolean) = Regs().apply {
            fill(0..66, 0xFFFF)
            listOf(2025, 6, 1, 12, 0, 0).forEachIndexed { i, v -> u16(i, v) }
            u32(6, 9_999); u16(26, 2); u16(29, 2_000); u16(30, 2_302); u16(32, 4_998); u16(34, 4_500)
            u16(37, 1_250); u16(39, 380); u16(41, 4_700); u32(49, 2_345)
            if (!hf) { u16(38, 1_100); u16(40, 360); u16(42, 2_480); u16(43, 2_220) }
        }
        val hf = driver(Regs(), onePlay(true))
        assertEquals("INGECON SUN 1Play HF", hf.identify().model)
        val r = hf.read()
        assertEquals(4_700, r.pvPowerW)
        assertEquals(1, r.pvStrings.size)
        assertEquals(4_700, r.pvStrings[0].powerW)
        assertEquals(4_500, r.phases.single().powerW)
        assertEquals(49.98, r.phases.single().frequencyHz, 1e-9)
        assertEquals(23.45, r.today.pvKwh!!, 1e-9)

        val tlm = driver(Regs(), onePlay(false))
        assertEquals("INGECON SUN 1Play TLM", tlm.identify().model)
        val t = tlm.read()
        assertEquals(listOf(2_480, 2_220), t.pvStrings.map { it.powerW })
        assertEquals(11.0, t.pvStrings[1].currentA, 1e-9)
    }

    @Test
    fun legacy100TlLayout() = runBlocking {
        val input = Regs().apply {
            fill(0..66, 0xFFFF)
            listOf(2024, 3, 3, 9, 30, 0).forEachIndexed { i, v -> u16(i, v) }
            u32(12, 15_000); u16(20, 2)
            u16(21, 14_000); u16(22, 14_100); u16(23, 13_900); u16(24, 2_310); u16(25, 2_305); u16(26, 2_300); u16(27, 5_000)
            u16(29, 9_800); u16(32, 16_000); u16(33, 700); u16(34, 10_100)
            u16(38, (52 shl 8) or 51) // string 2 = 5.2 A, string 1 = 5.1 A
            u32(57, 1_000_000)
        }
        val d = driver(Regs(), input)
        assertEquals("INGECON SUN 3Play 100TL/160TL", d.identify().model)
        val r = d.read()
        assertEquals(101_000, r.pvPowerW)
        assertEquals(700.0, r.pvStrings.single().voltageV, 1e-9)
        assertEquals(160.0, r.pvStrings.single().currentA, 1e-9)
        assertEquals(140.0, r.phases[0].currentA!!, 1e-9)
        assertEquals(150.0, r.today.pvKwh!!, 1e-9)
        assertEquals(1_000_000.0, r.lifetimePvKwh!!, 1e-9)
        val more = r.details.first { it.title == "More" }.rows.toMap()
        assertEquals("5.1 A", more["String 1 current"])
        assertEquals("5.2 A", more["String 2 current"])
    }

    @Test
    fun otherDevicesAreRejected() {
        // Holding registers with implausible content, no input registers.
        val foreign = Regs().apply { fill(0x1001..0x1024, 0x1234) }
        assertThrows(IOException::class.java) { runBlocking { driver(foreign).identify() } }
        // Nothing at either address range.
        assertThrows(IOException::class.java) { runBlocking { driver(Regs(), Regs()).identify() } }
        // Input registers 0–66 exist but hold no date.
        assertThrows(IOException::class.java) { runBlocking { driver(Regs(), Regs().fill(0..66, 7)).identify() } }
        // Older map at night: no frequency yet, layout can't be told.
        val night = legacy3Play().apply { u16(35, 0) }
        assertThrows(IOException::class.java) { runBlocking { driver(Regs(), night).identify() } }
    }

    @Test
    fun requestsArePacedAtLeastOneSecondApart() = runBlocking {
        val h = play3Current()
        val d = IngeteamDriver(h, Regs(), 1, requestGapMs = 300)
        val t0 = System.nanoTime()
        d.read() // three requests: detection + two blocks
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("took $ms ms", ms >= 600)
    }
}
