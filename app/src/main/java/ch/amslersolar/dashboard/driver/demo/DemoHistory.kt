// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.demo

import ch.amslersolar.dashboard.data.HistoryRepository
import ch.amslersolar.dashboard.model.DailyEnergy
import ch.amslersolar.dashboard.model.EnergyTotals
import ch.amslersolar.dashboard.model.HistoryMath
import ch.amslersolar.dashboard.model.Sample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Generates two years of plausible 15-minute history for a 29.9 kW plant with a 20 kWh battery:
 * seasons, cloudy days, morning/evening load peaks and winter heating. For trying the history screens.
 */
object DemoHistory {
    private const val STEP_S = 900L
    private const val BATTERY_WH = 20_000.0

    suspend fun fill(plantId: String, history: HistoryRepository, days: Long = 730) {
        val (samples, daily) = withContext(Dispatchers.Default) { generate(plantId.hashCode(), days) }
        history.insert(plantId, samples, daily)
    }

    private fun generate(seed: Int, days: Long): Pair<List<Sample>, List<DailyEnergy>> {
        val zone = ZoneId.systemDefault()
        val random = Random(seed)
        val end = Instant.now().epochSecond / STEP_S * STEP_S
        val start = end - days * 86_400
        val samples = ArrayList<Sample>(((end - start) / STEP_S + 1).toInt())
        val daily = LinkedHashMap<LocalDate, EnergyTotals>()
        var soc = 50.0
        var previous: Sample? = null
        var day: LocalDate? = null
        var clouds = 1.0

        for (t in start..end step STEP_S) {
            val time = Instant.ofEpochSecond(t).atZone(zone)
            if (time.toLocalDate() != day) {
                day = time.toLocalDate()
                clouds = 0.2 + 0.8 * sqrt(random.nextDouble())
            }
            val season = 0.5 + 0.5 * cos(2 * PI * (time.dayOfYear - 172) / 365.0) // 1 in June, 0 in December
            val hour = time.toLocalTime().toSecondOfDay() / 3600.0
            val halfDaylight = 4.5 + 3.5 * season
            val sun = max(0.0, cos((hour - 13) / halfDaylight * PI / 2))
            val pv = (sun * (9_000 + 17_000 * season) * clouds * (0.85 + 0.3 * random.nextDouble())).roundToInt()

            val peaks = 1_500 * exp(-((hour - 7.5) / 1.0).let { it * it }) + 2_500 * exp(-((hour - 19) / 1.5).let { it * it })
            val heating = 1_800 * (1 - season) * (if (hour in 5.0..22.0) 1.0 else 0.4)
            val load = (450 + peaks + heating + random.nextInt(400)).roundToInt()

            val surplus = pv - load
            val battery = when {
                surplus > 0 && soc < 95 -> -min(surplus, 8_000)
                surplus < 0 && soc > 10 -> min(-surplus, 8_000)
                else -> 0
            }
            soc = (soc - battery * (STEP_S / 3600.0) / BATTERY_WH * 100).coerceIn(0.0, 100.0)

            val sample = Sample(t, pv, load, battery, pv + battery - load, soc.roundToInt())
            daily[day!!] = HistoryMath.merge(daily[day], null, HistoryMath.integrate(previous, sample))
            samples += sample
            previous = sample
        }
        return samples to daily.map { (d, e) -> DailyEnergy(d, e) }
    }
}
