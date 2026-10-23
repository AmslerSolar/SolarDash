// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.riello

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

class RielloCentralDriverTest {
    /**
     * Registers by number as in the document, answered at register + [offset] (−1: 1-based numbering).
     * [swapped]: words sent LSByte first. Registers 1–112 exist; [newFirmware] = false drops 42–53.
     */
    private class FakeRiello(private val offset: Int = -1, private val swapped: Boolean = false, newFirmware: Boolean = true) : RegisterReader {
        val regs = HashMap<Int, Int>()
        val requests = mutableListOf<Pair<Int, Int>>()
        init {
            for (r in 1..112) regs[r] = 0
            if (!newFirmware) for (r in 42..53) regs.remove(r)
        }
        fun u16(r: Int, v: Int) = apply { regs[r] = v and 0xFFFF }
        fun u32(r: Int, v: Long) = apply { u16(r, (v shr 16).toInt()); u16(r + 1, v.toInt()) }
        fun text(r: Int, n: Int, s: String) = apply {
            val p = s.padEnd(n * 2, ' ')
            for (i in 0 until n) u16(r + i, (p[2 * i].code shl 8) or p[2 * i + 1].code)
        }

        override suspend fun read(unitId: Int, register: Int, count: Int): RegisterBlock {
            assertEquals(1, unitId)
            requests += register to count
            val data = ByteArray(count * 2)
            for (i in 0 until count) {
                val v = regs[register + i - offset] ?: throw ModbusException(2)
                val (first, second) = if (swapped) v to (v shr 8) else (v shr 8) to v
                data[2 * i] = first.toByte(); data[2 * i + 1] = second.toByte()
            }
            return RegisterBlock(register, data)
        }
    }

    /** Three-phase 100 kVA central inverter producing 60 kW. */
    private fun central(offset: Int = -1, swapped: Boolean = false, newFirmware: Boolean = true) = FakeRiello(offset, swapped, newFirmware).apply {
        u16(80, 0x0033); text(81, 3, "100000"); text(84, 2, "4500"); text(86, 3, "01.30")
        text(89, 8, "RS1234567890"); text(97, 8, "SIRIO K100"); text(105, 8, "RIELLO")
        u16(11, 452); u16(12, 6100); u16(13, 0xFFFF); u16(14, 0xFFFF)
        u16(15, 1000); u16(16, 0xFFFF); u16(17, 0xFFFF)
        u16(18, 870); u16(19, 865); u16(20, 868); u16(21, 2301); u16(22, 2298); u16(23, 2305)
        u16(24, 5002); u16(25, 2000); u16(26, 1990); u16(27, 2010)
        u32(31, 12_345_678); u32(33, 23_456); u16(35, 3)
        if (newFirmware) { u16(42, -5); u32(49, 3_456); u16(52, 1_200) }
    }

    @Test
    fun threePhaseCentralInverter() = runBlocking {
        val d = RielloCentralDriver(central(), 1)
        val info = d.identify()
        assertEquals("RIELLO SIRIO K100", info.model)
        assertEquals("RS1234567890", info.serial)
        val r = d.read()
        assertEquals(61_000, r.pvPowerW) // 610.0 V × 100.0 A
        assertEquals(1, r.pvStrings.size) // PV2/PV3 0xFFFF: not available
        assertNull(r.pvStrings[0].powerW)
        assertEquals(listOf("L1", "L2", "L3"), r.phases.map { it.name })
        assertEquals(230.1, r.phases[0].voltageV, 1e-9)
        assertEquals(87.0, r.phases[0].currentA!!, 1e-9)
        assertEquals(50.02, r.phases[0].frequencyHz, 1e-9)
        assertEquals(20_000, r.phases[0].powerW) // 10 W units
        assertEquals(45.2, r.temperatureC!!, 1e-9)
        assertEquals(345.6, r.today.pvKwh!!, 1e-9)
        assertEquals(1_234_567.8, r.lifetimePvKwh!!, 1e-6)
        assertEquals("Mode 3", r.status)
        assertNull(r.gridPowerW)
        val inv = r.details.first { it.title == "Inverter" }.rows.toMap()
        assertEquals("100.0 kVA", inv["Rated power"])
        assertEquals("450.0 V", inv["Nominal PV voltage"])
        assertEquals("01.30", inv["Firmware"])
        assertEquals("None", inv["Alarms"])
        val ac = r.details.first { it.title == "AC output" }.rows.toMap()
        assertEquals("60000 W", ac["Active power"])
        assertEquals("-50 var", ac["Reactive power R"])
        assertEquals(listOf(79 to 1, 79 to 33, 6 to 47), requestsOf(central())) // detect, identification, measures
    }

    private fun requestsOf(fake: FakeRiello): List<Pair<Int, Int>> = runBlocking {
        RielloCentralDriver(fake, 1).read()
        fake.requests
    }

    @Test
    fun zeroBasedAndByteSwappedAreDetected() = runBlocking {
        val r = RielloCentralDriver(central(offset = 0, swapped = true), 1).read()
        assertEquals(61_000, r.pvPowerW)
        assertEquals(230.1, r.phases[0].voltageV, 1e-9)
        assertEquals(1_234_567.8, r.lifetimePvKwh!!, 1e-6)
        assertEquals("RIELLO SIRIO K100", RielloCentralDriver(central(offset = 0, swapped = true), 1).identify().model)
    }

    @Test
    fun olderFirmwareWithoutRegisters42To53() = runBlocking {
        val fake = central(newFirmware = false)
        val d = RielloCentralDriver(fake, 1)
        val r = d.read()
        assertEquals(61_000, r.pvPowerW)
        assertNull(r.today.pvKwh) // daily energy only since FW 1.2.5
        assertEquals(1_234_567.8, r.lifetimePvKwh!!, 1e-6)
        fake.requests.clear()
        d.read()
        assertEquals(listOf(6 to 3, 10 to 31), fake.requests) // 42–53 not asked again
    }

    @Test
    fun alarmsAndSinglePhase() = runBlocking {
        val fake = central().apply {
            u16(80, 0x0031)
            u16(8, (1 shl 9) or (1 shl 13)) // bit 10 No grid, bit 14 Over-temperature
            u16(12, 0); u16(25, 0)
        }
        val r = RielloCentralDriver(fake, 1).read()
        assertEquals("Over-temperature, No grid", r.status)
        assertEquals(listOf("L1"), r.phases.map { it.name })
        assertTrue(r.pvStrings.isEmpty())
        assertEquals(0, r.pvPowerW) // no PV input with voltage: falls back to the AC power, 0 W
        assertTrue(r.details.first().rows.contains("Phases" to "Single-phase"))
    }

    @Test
    fun otherDevicesAreRejected() {
        val other = FakeRiello().apply { u16(80, 0x1234); u16(79, 7) }
        assertThrows(IOException::class.java) { runBlocking { RielloCentralDriver(other, 1).identify() } }
        val empty = object : RegisterReader {
            override suspend fun read(unitId: Int, register: Int, count: Int): RegisterBlock = throw ModbusException(2)
        }
        assertThrows(IOException::class.java) { runBlocking { RielloCentralDriver(empty, 1).identify() } }
        // Right marker but no identification text.
        val blank = FakeRiello().apply { u16(80, 0x0033) }
        assertThrows(IOException::class.java) { runBlocking { RielloCentralDriver(blank, 1).identify() } }
    }
}
