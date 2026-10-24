// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.spec

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A float register holding NaN or ±∞ gives no value (the point is left out), like an "invalid" raw value. */
class SpecReaderNonFiniteTest {
    private val spec = Spec.parse(
        """
        {"id": "t", "manufacturer": "T", "name": "T", "kind": "meter",
         "points": [
           {"key": "l1_voltage", "table": "holding", "address": 0, "type": "f32", "unit": "V"},
           {"key": "l2_voltage", "table": "holding", "address": 2, "type": "f32", "unit": "V"},
           {"key": "l3_voltage", "table": "holding", "address": 4, "type": "f32", "unit": "V"},
           {"key": "grid_power", "table": "holding", "address": 6, "type": "f32", "unit": "W", "positive": "import"}
         ]}
        """.trimIndent(),
    )

    @Test
    fun nonFiniteFloatsAreLeftOut() = runBlocking {
        val f = FakeMeterImage(1, 125).f32(0, 230.0).f32(2, Double.NaN).f32(4, Double.POSITIVE_INFINITY).f32(6, Double.NaN)
        val tables = SpecTables(holding = f, input = f)
        val values = SpecReader(spec, Vocabulary(emptyMap()), tables, 1, minIntervalMs = 0).read()
        assertEquals(setOf("l1_voltage"), values.keys)
        val r = SpecDriver(spec, Vocabulary(emptyMap()), tables, 1, requestGapMs = 0).read()
        assertNull(r.gridPowerW)
        assertEquals(listOf("L1"), r.phases.map { it.name })
    }
}
