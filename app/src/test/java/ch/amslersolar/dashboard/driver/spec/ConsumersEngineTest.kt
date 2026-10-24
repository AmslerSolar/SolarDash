// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.spec

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Engine rules found while moving the consumer drivers: NaN floats are missing values; consumers have no grid / PV / battery. */
class ConsumersEngineTest {
    private val vocabulary = Vocabulary.parse(File("src/main/assets/modbusspec/vocabulary.json").readText())

    private fun spec(kind: String) = Spec.parse(
        """
        {"id": "t", "manufacturer": "T", "name": "Test", "kind": "$kind",
         "points": [
          {"key": "consumer_power", "label": "P", "table": "holding", "address": 0, "type": "f32", "unit": "W", "positive": "consumption"},
          {"key": "grid_power", "label": "Grid", "table": "holding", "address": 2, "type": "s16", "unit": "W", "positive": "import"},
          {"key": "pv_power", "label": "PV", "table": "holding", "address": 3, "type": "u16", "unit": "W"},
          {"key": "battery_soc", "label": "SoC", "table": "holding", "address": 4, "type": "u16", "unit": "%"},
          {"key": "l1_voltage", "label": "U1", "table": "holding", "address": 5, "type": "f32", "unit": "V"}
         ]}
        """.trimIndent(),
    )

    private fun read(kind: String, power: Double) = runBlocking {
        val f = FakeRegisters().on(1).f32(0, power).u16(2, -1500).u16(3, 800).u16(4, 55).f32(5, Double.NaN)
        val d = SpecDriver(spec(kind), vocabulary, SpecTables(holding = f, input = f), 1, requestGapMs = 0)
        d.read()
    }

    @Test
    fun nanFloatIsNotAValue() {
        val r = read("charger", Double.NaN)
        assertNull(r.loadPowerW)
        assertEquals(emptyList<Any>(), r.phases) // NaN voltage: no phase
        assertEquals(1_000, read("charger", 1_000.0).loadPowerW)
    }

    @Test
    fun consumerHasNoGridPvOrBattery() {
        val r = read("heating_rod", 2_000.0)
        assertEquals(2_000, r.loadPowerW)
        assertNull(r.gridPowerW)
        assertNull(r.pvPowerW)
        assertNull(r.battery)
        val m = read("inverter", 2_000.0)
        assertEquals(1_500, m.gridPowerW) // import-positive raw −1500 → export +1500
        assertEquals(800, m.pvPowerW)
        assertEquals(55, m.battery?.socPercent)
    }

    private val gaps = Spec.parse(
        """
        {"id": "g", "manufacturer": "T", "name": "Gaps", "kind": "heat_pump", "connection": {"limits": {"max_gap": 4}},
         "points": [
          {"key": "consumer_power", "label": "P", "table": "holding", "address": 10, "type": "u16", "unit": "W"},
          {"key": "x.a", "label": "A", "table": "holding", "address": 12, "type": "u16"},
          {"key": "x.b", "label": "B", "table": "holding", "address": 14, "type": "u16"}
         ]}
        """.trimIndent(),
    )

    @Test
    fun rejectedRegistersAreNotAskedEveryPoll() = runBlocking {
        val f = FakeRegisters().on(1).u16(10, 500).u16(14, 7) // 12 isn't on this device
        val d = SpecDriver(gaps, vocabulary, SpecTables(holding = f, input = f), 1, requestGapMs = 0)
        assertEquals(500, d.read().loadPowerW)
        assertEquals(4, f.requests.size) // block 10–14, then 10, 12, 14 one by one
        f.requests.clear()
        assertEquals(500, d.read().loadPowerW)
        assertEquals(listOf(Triple(1, 10, 1), Triple(1, 14, 1)), f.requests) // straight to single reads, 12 left out
    }

    @Test
    fun passingFailuresAreAskedAgain() = runBlocking {
        val f = FakeRegisters().on(1).u16(10, 500).u16(11, 0).u16(12, 1).u16(13, 0).u16(14, 7)
        f.failWith = 11 // gateway: target failed to respond
        val d = SpecDriver(gaps, vocabulary, SpecTables(holding = f, input = f), 1, requestGapMs = 0)
        try {
            d.read()
        } catch (_: java.io.IOException) {
        }
        f.failWith = null
        f.requests.clear()
        assertEquals(500, d.read().loadPowerW)
        assertEquals(listOf(Triple(1, 10, 5)), f.requests) // one block again
    }
}
