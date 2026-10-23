// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.sunspec

import ch.amslersolar.dashboard.driver.modbus.ModbusException
import ch.amslersolar.dashboard.driver.modbus.RegisterBlock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SunSpecDeviceTest {
    /** A map at base 0 with the given (model id, length) chain; ids can be changed later to simulate a reboot. */
    private class Map(var chain: List<Pair<Int, Int>>, val end: Pair<Int, Int> = 0xFFFF to 0) : RegisterReader {
        override suspend fun read(unitId: Int, register: Int, count: Int): RegisterBlock {
            val regs = HashMap<Int, Int>()
            regs[0] = 0x5375; regs[1] = 0x6E53
            var a = 2
            for ((id, len) in chain) {
                regs[a] = id; regs[a + 1] = len
                for (o in 2 until len + 2) regs[a + o] = 0
                a += 2 + len
            }
            regs[a] = end.first; regs[a + 1] = end.second
            val data = ByteArray(count * 2)
            for (i in 0 until count) {
                val v = regs[register + i] ?: throw ModbusException(2)
                data[2 * i] = (v shr 8).toByte(); data[2 * i + 1] = v.toByte()
            }
            return RegisterBlock(register, data)
        }
    }

    @Test
    fun aMapWithoutTheCommonModelFirstIsRejectedAndNotKept() = runBlocking {
        // As read from an LG ESS Home 10 after a restart: garbled ids, lengths intact.
        val map = Map(listOf(203 to 66, 103 to 50, 802 to 60, 120 to 26, 123 to 24, 802 to 62, 64256 to 20), end = 2 to 0)
        val device = SunSpecDevice(map, 1)
        val e = runCatching { device.model(1) }.exceptionOrNull()
        assertTrue(e is IOException && e.message.orEmpty().contains("not valid"))
        // Once the device is back to normal, the next poll finds the proper map.
        map.chain = listOf(1 to 66, 113 to 60, 120 to 26, 123 to 24, 802 to 62, 64256 to 20)
        assertNotNull(device.model(1))
        assertEquals(setOf(1, 113, 120, 123, 802, 64256), device.discover().keys)
    }

    @Test
    fun aChangedMapIsDiscoveredAgain() = runBlocking {
        val map = Map(listOf(1 to 66, 103 to 50, 802 to 62))
        val device = SunSpecDevice(map, 1)
        assertNotNull(device.model(103))
        // Firmware update: 103 replaced by the float model 113 (60 registers), 802 moves.
        map.chain = listOf(1 to 66, 113 to 60, 802 to 62)
        val e = runCatching { device.model(802) }.exceptionOrNull()
        assertTrue(e is IOException && e.message.orEmpty().contains("changed"))
        assertNotNull(device.model(113))
        assertNotNull(device.model(802))
    }
}
