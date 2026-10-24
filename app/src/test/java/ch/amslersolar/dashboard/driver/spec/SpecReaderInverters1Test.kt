// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.spec

import ch.amslersolar.dashboard.driver.modbus.ModbusException
import ch.amslersolar.dashboard.driver.modbus.RegisterBlock
import ch.amslersolar.dashboard.driver.sunspec.RegisterReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Engine changes made while moving the inverters (group 1) onto ModbusSpec. */
class SpecReaderInverters1Test {
    private val spec = Spec.parse(
        """
        {"id": "test", "manufacturer": "T", "name": "T", "kind": "inverter", "connection": {"limits": {"max_registers": 125}},
         "points": [
           {"key": "pv_power", "address": 0, "type": "f32"},
           {"key": "x.missing", "address": 2, "type": "u16"},
           {"key": "x.there", "address": 3, "type": "u16"}
         ]}
        """.trimIndent(),
    )

    private class Fake(private val missing: Set<Int>, private val words: Map<Int, Int>) : RegisterReader {
        val requests = mutableListOf<Pair<Int, Int>>()

        override suspend fun read(unitId: Int, register: Int, count: Int): RegisterBlock {
            requests += register to count
            if ((register until register + count).any { it in missing }) throw ModbusException(2)
            val data = ByteArray(2 * count)
            for (i in 0 until count) {
                val v = words[register + i] ?: 0
                data[2 * i] = (v shr 8).toByte()
                data[2 * i + 1] = v.toByte()
            }
            return RegisterBlock(register, data)
        }
    }

    private fun reader(f: Fake, slowEvery: Int = 12) =
        SpecReader(spec, Vocabulary.parse("{}"), SpecTables(holding = f, input = f), 1, slowEvery = slowEvery, minIntervalMs = 0)

    @Test
    fun floatNanIsNoValue() = runBlocking {
        val nan = java.lang.Float.floatToRawIntBits(Float.NaN)
        val v = reader(Fake(emptySet(), mapOf(0 to (nan ushr 16), 1 to (nan and 0xFFFF), 3 to 7))).read()
        assertNull(v["pv_power"])
        assertEquals(7.0, v["x.there"]!!.number!!, 0.0)
    }
}
