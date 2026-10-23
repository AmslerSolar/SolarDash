// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.powerone

import ch.amslersolar.dashboard.driver.modbus.ModbusException
import ch.amslersolar.dashboard.driver.modbus.RegisterBlock
import ch.amslersolar.dashboard.driver.sunspec.RegisterReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class PowerOneTrioDriverTest {
    /**
     * Input registers by register number (as in the specs), answered at register + [offset] like a gateway would
     * (−1: the specs' 1-based numbering). [swapped]: float word order.
     */
    private class FakeTrio(private val offset: Int = -1, private val swapped: Boolean = false) : RegisterReader {
        val regs = HashMap<Int, Int>()
        var asleep = false

        fun u16(r: Int, v: Int) = apply { regs[r] = v and 0xFFFF }
        fun u32(r: Int, v: Long) = apply { u16(r, (v shr 16).toInt()); u16(r + 1, v.toInt()) }
        fun f32(r: Int, v: Double, swap: Boolean = swapped) = apply {
            val bits = v.toFloat().toRawBits()
            val hi = bits ushr 16; val lo = bits and 0xFFFF
            if (swap) { u16(r, lo); u16(r + 1, hi) } else { u16(r, hi); u16(r + 1, lo) }
        }
        fun chars(r: Int, s: String) = apply { s.forEachIndexed { i, c -> u16(r + i, c.code) } }

        override suspend fun read(unitId: Int, register: Int, count: Int): RegisterBlock {
            assertEquals(2, unitId)
            if (asleep) throw SocketTimeoutException("Read timed out")
            val data = ByteArray(count * 2)
            for (i in 0 until count) {
                val v = regs[register + i - offset] ?: if (regs.containsKey(register - offset)) 0 else throw ModbusException(2)
                data[2 * i] = (v shr 8).toByte(); data[2 * i + 1] = v.toByte()
            }
            return RegisterBlock(register, data)
        }
    }

    /** TRIO-20.0 in the GT map: 14.2 kW AC from two inputs. */
    private fun trio20(offset: Int = -1, swapped: Boolean = false) = FakeTrio(offset, swapped).apply {
        u16(100, 4370); u16(101, 72); u16(102, 2); u16(103, 2)
        chars(104, "123456"); chars(110, "-3G97-"); u16(116, 'y'.code)
        for (r in 117..131) regs.putIfAbsent(r, 0)
        u16(124, 6); u16(125, 2); u16(126, 2); u16(127, 2); u16(128, 0)
        f32(132, 31_400.0); f32(134, 98_765.4); f32(136, 0.0); f32(138, 140.0); f32(140, 600.0); f32(142, 7_000.0)
        f32(144, 231.0); f32(146, 20.5); f32(148, 14_200.0); f32(150, 50.01)
        f32(152, 7_400.0); f32(154, 620.0); f32(156, 11.9); f32(158, 7_200.0); f32(160, 610.0); f32(162, 11.8)
        f32(164, 48.0); f32(166, 52.0); f32(168, 12.0); f32(170, 0.0); f32(172, 1.0); u16(174, 1); u16(175, 0)
    }

    @Test
    fun gtMapWithOneBasedAddresses() = runBlocking {
        val driver = PowerOneTrioDriver(trio20(), 2)
        val info = driver.identify()
        assertEquals("TRIO-20-TL-OUTD-400", info.model)
        assertEquals("123456", info.serial)
        val r = driver.read()
        assertEquals(14_200, r.pvPowerW)
        assertEquals(31.4, r.today.pvKwh!!, 1e-6)
        assertEquals(98_765.4, r.lifetimePvKwh!!, 0.01)
        assertEquals("Run", r.status)
        assertEquals(listOf(620.0, 610.0), r.pvStrings.map { it.voltageV })
        assertEquals(231.0, r.phases.single().voltageV, 1e-9)
        assertNull(r.gridPowerW)
        assertNull(r.loadPowerW)
        assertTrue(r.details.any { it.title == "DC inputs" })
    }

    @Test
    fun zeroBasedGatewayAndSwappedFloatsAreDetected() = runBlocking {
        val r = PowerOneTrioDriver(trio20(offset = 0, swapped = true), 2).read()
        assertEquals(14_200, r.pvPowerW)
        assertEquals(50.01, r.phases.single().frequencyHz, 1e-3)
        assertTrue(r.details.first().rows.contains("Register map" to "GT (0-based addresses)"))
    }

    @Test
    fun gt03MapOfTrio60() = runBlocking {
        val fake = FakeTrio().apply {
            u16(1000, 261); u16(1001, 132); u16(1002, 1); chars(1003, "-3N63-"); chars(1009, "654321")
            for (r in 1015..1022) regs.putIfAbsent(r, 0)
            u16(1022, 0x2F)
            for (r in 1050..1135) regs.putIfAbsent(r, 0)
            u16(1050, 6)
            u32(1070, 210_500); u32(1072, 1_530_000_000)
            f32(1094, 48_300.0, swap = true); f32(1106, 50.0, swap = true)
            f32(1108, 48_900.0, swap = true); f32(1110, 700.0, swap = true); f32(1112, 69.9, swap = true)
            f32(1122, 55.0, swap = true)
        }
        val driver = PowerOneTrioDriver(fake, 2)
        assertEquals("TRIO-60-TL-OUTD", driver.identify().model)
        val r = driver.read()
        assertEquals(48_300, r.pvPowerW)
        assertEquals(210.5, r.today.pvKwh!!, 1e-9)
        assertEquals(1_530_000.0, r.lifetimePvKwh!!, 1e-6)
        assertEquals("Connected to grid", r.status)
        assertEquals(1, r.pvStrings.size)
    }

    @Test
    fun goesToSleepAfterSunsetInsteadOfFailing() = runBlocking {
        val fake = trio20().apply { f32(148, 120.0) } // evening: 120 W
        val driver = PowerOneTrioDriver(fake, 2)
        assertEquals(120, driver.read().pvPowerW)
        fake.asleep = true
        val night = driver.read()
        assertEquals(0, night.pvPowerW)
        assertEquals("Sleeping (no sun)", night.status)
    }

    @Test
    fun noAnswerAtAllIsAnError() {
        val fake = trio20().apply { asleep = true }
        val e = runCatching { runBlocking { PowerOneTrioDriver(fake, 2).read() } }.exceptionOrNull()
        assertTrue(e is IOException)
    }

    @Test
    fun noAnswerDuringTheDayIsAnError() {
        val fake = trio20()
        val driver = PowerOneTrioDriver(fake, 2)
        runBlocking { driver.read() } // 14.2 kW
        fake.asleep = true
        assertTrue(runCatching { runBlocking { driver.read() } }.exceptionOrNull() is IOException)
    }

    @Test
    fun otherDevicesAreRejected() {
        val fake = FakeTrio().apply { u16(100, 1); u16(1000, 7) }
        val e = runCatching { runBlocking { PowerOneTrioDriver(fake, 2).identify() } }.exceptionOrNull()
        assertTrue(e?.message.orEmpty().contains("No Power-One"))
    }
}
