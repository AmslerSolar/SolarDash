// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import kotlin.math.max

/** One plant-level power sample. Same sign conventions as [DeviceReading]. */
data class Sample(
    val epochSecond: Long,
    val pvW: Int?,
    val loadW: Int?,
    val batteryW: Int?,
    val gridW: Int?,
    val socPercent: Int?,
)

data class DailyEnergy(val day: LocalDate, val energy: EnergyTotals)

object HistoryMath {
    /** Longer gaps between samples (app closed, background work deferred) aren't integrated across. */
    const val MAX_GAP_SECONDS = 20 * 60L

    /** Devices' "today" counters reset at midnight on their own clock; ignore them shortly after ours. */
    private val COUNTERS_FROM = LocalTime.of(0, 15)

    fun countersUsable(time: LocalTime): Boolean = time >= COUNTERS_FROM

    /**
     * Whether a device's "today" solar counter of [value] can be trusted. Inverters that sleep at night can keep
     * yesterday's total until a few minutes after they wake up (an ABB TRIO reported 26.5 kWh at 07:25 and 0.1 at
     * 07:35); since a day keeps its highest counter, that gave yesterday's yield again. A counter is trusted when
     * it is 0, differs from the device's [first] value of the day, while the device produces, or from noon on.
     */
    fun pvCounterFresh(value: Double, first: Double?, pvW: Int?, time: LocalTime): Boolean =
        value == 0.0 || first != null && value != first || (pvW ?: 0) > 0 || time >= LocalTime.NOON

    /** Energy between two consecutive samples (trapezoid rule). A metric is null when it can't be computed. */
    fun integrate(prev: Sample?, cur: Sample): EnergyTotals {
        if (prev == null) return EnergyTotals()
        val seconds = cur.epochSecond - prev.epochSecond
        if (seconds <= 0 || seconds > MAX_GAP_SECONDS) return EnergyTotals()
        val hours = seconds / 3600.0
        fun kwh(a: Int?, b: Int?, part: (Int) -> Int): Double? =
            if (a == null || b == null) null else (part(a) + part(b)) / 2.0 * hours / 1000.0
        return EnergyTotals(
            pvKwh = kwh(prev.pvW, cur.pvW) { max(it, 0) },
            loadKwh = kwh(prev.loadW, cur.loadW) { max(it, 0) },
            exportKwh = kwh(prev.gridW, cur.gridW) { max(it, 0) },
            importKwh = kwh(prev.gridW, cur.gridW) { max(-it, 0) },
        )
    }

    /**
     * Updates a day's totals with a new sample. A device counter wins when present (max, since counters
     * only grow during a day and a device that misses one poll would otherwise pull the total down);
     * otherwise the integrated [delta] is added.
     */
    fun merge(existing: EnergyTotals?, counters: EnergyTotals?, delta: EnergyTotals): EnergyTotals {
        fun pick(old: Double?, counter: Double?, d: Double?): Double? = when {
            counter != null -> max(old ?: 0.0, counter)
            d != null -> (old ?: 0.0) + d
            else -> old
        }
        return EnergyTotals(
            pvKwh = pick(existing?.pvKwh, counters?.pvKwh, delta.pvKwh),
            exportKwh = pick(existing?.exportKwh, counters?.exportKwh, delta.exportKwh),
            importKwh = pick(existing?.importKwh, counters?.importKwh, delta.importKwh),
            loadKwh = pick(existing?.loadKwh, counters?.loadKwh, delta.loadKwh),
        )
    }

    fun sum(values: List<EnergyTotals>): EnergyTotals {
        fun total(pick: (EnergyTotals) -> Double?) = values.mapNotNull(pick).takeIf { it.isNotEmpty() }?.sum()
        return EnergyTotals(
            pvKwh = total { it.pvKwh },
            exportKwh = total { it.exportKwh },
            importKwh = total { it.importKwh },
            loadKwh = total { it.loadKwh },
        )
    }

    /** Share of consumption not bought from the grid. */
    fun selfSufficiency(e: EnergyTotals): Double? {
        val load = e.loadKwh ?: return null
        val import = e.importKwh ?: return null
        return if (load > 0) (1 - import / load).coerceIn(0.0, 1.0) else null
    }

    /** Share of solar production used on site rather than exported. */
    fun selfConsumption(e: EnergyTotals): Double? {
        val pv = e.pvKwh ?: return null
        val export = e.exportKwh ?: return null
        return if (pv > 0) (1 - export / pv).coerceIn(0.0, 1.0) else null
    }
}

enum class HistoryPeriod(val label: String) {
    DAY("Day"),
    WEEK("Week"),
    MONTH("Month"),
    YEAR("Year"),
    LIFETIME("Lifetime"),
}

/** The calendar period shown in the history, anchored on any date inside it. Weeks start on Monday. */
data class HistoryRange(val period: HistoryPeriod, val anchor: LocalDate) {
    /** First day of the period; null for [HistoryPeriod.LIFETIME], whose bounds come from the data. */
    val start: LocalDate?
        get() = when (period) {
            HistoryPeriod.DAY -> anchor
            HistoryPeriod.WEEK -> anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            HistoryPeriod.MONTH -> anchor.withDayOfMonth(1)
            HistoryPeriod.YEAR -> anchor.withDayOfYear(1)
            HistoryPeriod.LIFETIME -> null
        }

    /** Last day of the period, inclusive. */
    val end: LocalDate?
        get() = when (period) {
            HistoryPeriod.DAY -> anchor
            HistoryPeriod.WEEK -> start!!.plusDays(6)
            HistoryPeriod.MONTH -> start!!.plusMonths(1).minusDays(1)
            HistoryPeriod.YEAR -> start!!.plusYears(1).minusDays(1)
            HistoryPeriod.LIFETIME -> null
        }

    fun previous(): HistoryRange = shift(-1)

    fun next(): HistoryRange = shift(1)

    fun hasNext(today: LocalDate): Boolean = end?.let { it < today } ?: false

    private fun shift(by: Long) = copy(
        anchor = when (period) {
            HistoryPeriod.DAY -> anchor.plusDays(by)
            HistoryPeriod.WEEK -> anchor.plusWeeks(by)
            HistoryPeriod.MONTH -> anchor.plusMonths(by)
            HistoryPeriod.YEAR -> anchor.plusYears(by)
            HistoryPeriod.LIFETIME -> anchor
        },
    )

    fun label(): String = when (period) {
        HistoryPeriod.DAY -> anchor.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy"))
        HistoryPeriod.WEEK -> {
            val s = start!!
            val e = end!!
            val startPattern = if (s.year == e.year) "d MMM" else "d MMM yyyy"
            "${s.format(DateTimeFormatter.ofPattern(startPattern))} – ${e.format(DateTimeFormatter.ofPattern("d MMM yyyy"))}"
        }
        HistoryPeriod.MONTH -> anchor.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
        HistoryPeriod.YEAR -> anchor.year.toString()
        HistoryPeriod.LIFETIME -> "All time"
    }
}

/**
 * What history accepts: no plant makes more than [MAX_POWER_W] or [MAX_DAY_KWH] a day, and a 32-bit register's
 * "not available" pattern (all ones, or its signed extremes, at any decimal scale) is never a reading. Applied
 * when recording, and once to what earlier versions stored (history database version 7).
 */
object Plausible {
    const val MAX_POWER_W = 10_000_000
    const val MAX_DAY_KWH = 1_000_000.0
    private val PATTERNS = listOf(4294967295.0, 2147483647.0, -2147483648.0)

    fun power(w: Int?): Int? = w?.takeIf { kotlin.math.abs(it) <= MAX_POWER_W }

    fun dayEnergy(kwh: Double?): Double? = kwh?.takeIf { it.isFinite() && kotlin.math.abs(it) <= MAX_DAY_KWH }

    fun day(e: EnergyTotals) = EnergyTotals(dayEnergy(e.pvKwh), dayEnergy(e.exportKwh), dayEnergy(e.importKwh), dayEnergy(e.loadKwh))

    /** A register's "not available" pattern after scaling (4294967295, 429496729.5, 42949672.95 …). */
    fun notAvailable(v: Double): Boolean = PATTERNS.any { p ->
        (0..3).any { k -> kotlin.math.abs(v - p / Math.pow(10.0, k.toDouble())) < 1e-9 * kotlin.math.abs(p) / Math.pow(10.0, k.toDouble()) }
    }
}
