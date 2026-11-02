// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class HistoryTest {
    private fun sample(t: Long, pv: Int? = null, load: Int? = null, grid: Int? = null) = Sample(t, pv, load, null, grid, null)

    @Test
    fun integratesWithTrapezoidRule() {
        // 2 kW → 4 kW over 30 min = 1.5 kWh
        val e = HistoryMath.integrate(sample(0, pv = 2000), sample(900, pv = 4000))
        assertEquals(0.75, e.pvKwh!!, 1e-9) // 15 min
        val half = HistoryMath.integrate(sample(0, pv = 2000, load = 1000), sample(1200, pv = 4000, load = 1000))
        assertEquals(1.0, half.pvKwh!!, 1e-9)
        assertEquals(1000 / 3000.0, half.loadKwh!!, 1e-9)
    }

    @Test
    fun splitsGridIntoExportAndImport() {
        val e = HistoryMath.integrate(sample(0, grid = 3000), sample(600, grid = 3000))
        assertEquals(0.5, e.exportKwh!!, 1e-9)
        assertEquals(0.0, e.importKwh!!, 1e-9)
        val i = HistoryMath.integrate(sample(0, grid = -1200), sample(600, grid = -1200))
        assertEquals(0.2, i.importKwh!!, 1e-9)
    }

    @Test
    fun doesNotIntegrateAcrossLongGapsOrMissingValues() {
        assertNull(HistoryMath.integrate(sample(0, pv = 1000), sample(HistoryMath.MAX_GAP_SECONDS + 1, pv = 1000)).pvKwh)
        assertNull(HistoryMath.integrate(sample(0, pv = null), sample(60, pv = 1000)).pvKwh)
        assertNull(HistoryMath.integrate(null, sample(60, pv = 1000)).pvKwh)
    }

    @Test
    fun counterWinsAndNeverDecreases() {
        val day = HistoryMath.merge(null, EnergyTotals(pvKwh = 10.0), EnergyTotals(pvKwh = 0.2, loadKwh = 0.1))
        assertEquals(10.0, day.pvKwh!!, 1e-9)
        assertEquals(0.1, day.loadKwh!!, 1e-9) // no load counter → integrated
        val afterDrop = HistoryMath.merge(day, EnergyTotals(pvKwh = 6.0), EnergyTotals())
        assertEquals(10.0, afterDrop.pvKwh!!, 1e-9)
        val integrated = HistoryMath.merge(afterDrop, null, EnergyTotals(pvKwh = 0.5))
        assertEquals(10.5, integrated.pvKwh!!, 1e-9)
    }

    @Test
    fun ignoresCountersJustAfterMidnight() {
        assertFalse(HistoryMath.countersUsable(LocalTime.of(0, 5)))
        assertTrue(HistoryMath.countersUsable(LocalTime.of(0, 15)))
    }

    @Test
    fun selfSufficiencyAndSelfConsumption() {
        val e = EnergyTotals(pvKwh = 40.0, exportKwh = 30.0, importKwh = 5.0, loadKwh = 20.0)
        assertEquals(0.75, HistoryMath.selfSufficiency(e)!!, 1e-9)
        assertEquals(0.25, HistoryMath.selfConsumption(e)!!, 1e-9)
        assertNull(HistoryMath.selfConsumption(EnergyTotals(pvKwh = 0.0, exportKwh = 0.0)))
    }

    @Test
    fun weekStartsOnMondayAndNavigates() {
        val r = HistoryRange(HistoryPeriod.WEEK, LocalDate.of(2026, 10, 22)) // Thursday
        assertEquals(LocalDate.of(2026, 10, 19), r.start)
        assertEquals(LocalDate.of(2026, 10, 25), r.end)
        assertEquals(LocalDate.of(2026, 10, 12), r.previous().start)
        assertFalse(r.hasNext(LocalDate.of(2026, 10, 22)))
        assertTrue(r.previous().hasNext(LocalDate.of(2026, 10, 22)))
    }

    @Test
    fun monthAndYearBounds() {
        val feb = HistoryRange(HistoryPeriod.MONTH, LocalDate.of(2028, 2, 14))
        assertEquals(LocalDate.of(2028, 2, 29), feb.end)
        val year = HistoryRange(HistoryPeriod.YEAR, LocalDate.of(2026, 10, 20))
        assertEquals(LocalDate.of(2026, 1, 1), year.start)
        assertEquals(LocalDate.of(2026, 12, 31), year.end)
        assertNull(HistoryRange(HistoryPeriod.LIFETIME, LocalDate.of(2026, 10, 20)).start)
    }
}
