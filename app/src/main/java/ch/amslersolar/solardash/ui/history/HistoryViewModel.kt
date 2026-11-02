// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.ui.history

import ch.amslersolar.solardash.i18n.channelName
import ch.amslersolar.solardash.i18n.DetailTexts
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.amslersolar.solardash.data.HistoryRepository
import ch.amslersolar.solardash.data.PlantRepository
import ch.amslersolar.solardash.model.ChartChannel
import ch.amslersolar.solardash.model.Channels
import ch.amslersolar.solardash.model.Plant
import kotlinx.coroutines.flow.first
import ch.amslersolar.solardash.driver.demo.DemoHistory
import ch.amslersolar.solardash.model.EnergyTotals
import ch.amslersolar.solardash.model.HistoryMath
import ch.amslersolar.solardash.model.HistoryPeriod
import ch.amslersolar.solardash.model.HistoryRange
import ch.amslersolar.solardash.model.Sample
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

sealed interface HistoryChart {
    /** Power curve; [days] holds per-day energy for the table view when the range spans several days. */
    data class Power(
        val startEpoch: Long,
        val endEpoch: Long,
        val samples: List<Sample>,
        val days: List<EnergySlot> = emptyList(),
        val channels: List<ChannelSeries> = emptyList(),
    ) : HistoryChart
    data class Energy(val slots: List<EnergySlot>) : HistoryChart
}

/** An added device channel's data for the chart, already in its display unit. */
data class ChannelSeries(
    val deviceId: String,
    val key: String,
    val slot: Int,
    val name: String, // the channel's own name (shortened on the chart)
    val full: String, // with device and section, for the tooltip
    val unit: String,
    val points: List<Pair<Long, Double>>,
)

/** Why channels could not be added; the UI turns it into a translated message. */
sealed interface ChannelRefusal {
    data class TooManyChannels(val max: Int) : ChannelRefusal
    data class TooManyUnits(val max: Int, val units: List<String>) : ChannelRefusal
}

data class HistoryUiState(
    val range: HistoryRange,
    val today: LocalDate,
    val totals: EnergyTotals,
    val chart: HistoryChart,
    val hasAnyData: Boolean,
)

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(
    private val plantId: String,
    private val plants: PlantRepository,
    private val history: HistoryRepository,
) : ViewModel() {
    private val range = MutableStateFlow(HistoryRange(HistoryPeriod.DAY, LocalDate.now()))
    /** The device shown on its own; null: the whole plant. */
    private val device = MutableStateFlow<String?>(null)
    val selectedDevice: StateFlow<String?> = device
    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating

    /** Null until the first load. Reloads when the range changes or new samples are recorded. */
    val state: StateFlow<HistoryUiState?> = combine(range, device, history.version, plants.plant(plantId)) { r, d, _, plant -> Triple(r, d, plant) }
        .mapLatest { (r, d, plant) -> load(r, plant, d?.takeIf { id -> plant?.devices?.any { it.id == id } == true }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setDevice(deviceId: String?) {
        device.value = deviceId
    }

    fun setPeriod(period: HistoryPeriod) {
        range.value = HistoryRange(period, range.value.anchor)
    }

    fun previous() {
        range.value = range.value.previous()
    }

    fun next() {
        if (range.value.hasNext(LocalDate.now())) range.value = range.value.next()
    }

    fun fillDemoHistory() {
        if (_generating.value) return
        _generating.value = true
        viewModelScope.launch {
            try {
                DemoHistory.fill(plantId, history)
            } finally {
                _generating.value = false
            }
        }
    }

    /** (key, label, unit) of every recorded channel of a device, for the picker. */
    suspend fun catalog(deviceId: String) = history.channelCatalog(deviceId)

    /** Adds channels in free colour slots, or calls [onRefused] when the chart would get too many series or units. */
    fun addChannels(selection: List<Pair<String, String>>, onRefused: (ChannelRefusal) -> Unit) = viewModelScope.launch {
        val plant = plants.plant(plantId).first() ?: return@launch
        val current = plant.chartChannels
        if (current.size + selection.size > MAX_CHANNELS) {
            onRefused(ChannelRefusal.TooManyChannels(MAX_CHANNELS))
            return@launch
        }
        val used = current.map { it.slot }.toMutableSet()
        val added = current + selection.map { (deviceId, key) ->
            ChartChannel(deviceId, key, (0 until MAX_CHANNELS).first { it !in used }.also { used += it })
        }
        val units = PLANT_UNITS.toMutableList()
        for (c in added) {
            val unit = Channels.displayUnit(history.channelCatalog(c.deviceId).firstOrNull { it.first == c.key }?.third ?: "").first
            if (unit !in units) units += unit
        }
        if (units.size > MAX_AXES) {
            onRefused(ChannelRefusal.TooManyUnits(MAX_AXES, units.toList()))
            return@launch
        }
        plants.setChartChannels(plantId, added)
    }

    fun removeChannel(deviceId: String, key: String) = viewModelScope.launch {
        val plant = plants.plant(plantId).first() ?: return@launch
        plants.setChartChannels(plantId, plant.chartChannels.filterNot { it.deviceId == deviceId && it.key == key })
    }

    private suspend fun channelSeries(plant: Plant?, from: Long, to: Long): List<ChannelSeries> {
        plant ?: return emptyList()
        val names = plant.devices.associate { it.id to it.name }
        return plant.chartChannels.filter { it.deviceId in names }.groupBy { it.deviceId }.flatMap { (deviceId, entries) ->
            val catalog = history.channelCatalog(deviceId).associate { it.first to (it.second to it.third) }
            val data = history.channelSeries(deviceId, entries.map { it.key }, from, to)
            entries.map { c ->
                val (label, unit) = catalog[c.key] ?: (c.key to "")
                val (shown, factor) = Channels.displayUnit(unit)
                val name = channelName(c.key, label)
                val full = "$name — ${names[deviceId]}" + if (c.key.startsWith("d:")) " (${DetailTexts.tr(Channels.section(c.key))})" else ""
                ChannelSeries(deviceId, c.key, c.slot, name, full, shown, data[c.key].orEmpty().map { (t, v) -> t to v * factor })
            }
        }
    }

    /** The plant's figures, or [deviceId]'s alone (worked out from its recorded values). */
    private suspend fun load(r: HistoryRange, plant: Plant?, deviceId: String?): HistoryUiState {
        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()
        val hasAnyData = history.firstDay(plantId) != null
        suspend fun samples(from: Long, to: Long) =
            if (deviceId == null) history.samples(plantId, from, to) else history.deviceSamples(deviceId, from, to)
        suspend fun days(from: LocalDate, to: LocalDate) =
            if (deviceId == null) history.days(plantId, from, to) else history.deviceDays(deviceId, from, to)
        suspend fun months(year: Int): Map<YearMonth, EnergyTotals> =
            if (deviceId == null) history.months(plantId, year)
            else days(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31)).groupBy { YearMonth.from(it.day) }
                .mapValues { (_, d) -> HistoryMath.sum(d.map { it.energy }) }
        suspend fun years(): Map<Int, EnergyTotals> =
            if (deviceId == null) history.years(plantId)
            else days(LocalDate.of(2000, 1, 1), today).groupBy { it.day.year }.mapValues { (_, d) -> HistoryMath.sum(d.map { it.energy }) }
        return when (r.period) {
            HistoryPeriod.DAY -> {
                val start = r.anchor.atStartOfDay(zone).toEpochSecond()
                val end = r.anchor.plusDays(1).atStartOfDay(zone).toEpochSecond()
                val totals = days(r.anchor, r.anchor).firstOrNull()?.energy ?: EnergyTotals()
                val chart = HistoryChart.Power(start, end, samples(start, end), channels = channelSeries(plant, start, end))
                HistoryUiState(r, today, totals, chart, hasAnyData)
            }
            HistoryPeriod.WEEK, HistoryPeriod.MONTH -> {
                val start = r.start!!
                val end = r.end!!
                val byDay = days(start, end).associate { it.day to it.energy }
                val pattern = if (r.period == HistoryPeriod.WEEK) "EEE" else "d"
                val slots = generateSequence(start) { it.plusDays(1) }.takeWhile { it <= end }.map { day ->
                    EnergySlot(
                        label = day.format(DateTimeFormatter.ofPattern("EEE, d MMM")),
                        shortLabel = day.format(DateTimeFormatter.ofPattern(pattern)),
                        energy = byDay[day] ?: EnergyTotals(),
                    )
                }.toList()
                val totals = HistoryMath.sum(byDay.values.toList())
                val chart = if (r.period == HistoryPeriod.WEEK) {
                    val from = start.atStartOfDay(zone).toEpochSecond()
                    val to = end.plusDays(1).atStartOfDay(zone).toEpochSecond()
                    HistoryChart.Power(from, to, samples(from, to), days = slots, channels = channelSeries(plant, from, to))
                } else {
                    HistoryChart.Energy(slots)
                }
                HistoryUiState(r, today, totals, chart, hasAnyData)
            }
            HistoryPeriod.YEAR -> {
                val months = months(r.anchor.year)
                val slots = Month.entries.map { m ->
                    EnergySlot(
                        label = "${m.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${r.anchor.year}",
                        shortLabel = m.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                        energy = months[YearMonth.of(r.anchor.year, m)] ?: EnergyTotals(),
                    )
                }
                HistoryUiState(r, today, HistoryMath.sum(months.values.toList()), HistoryChart.Energy(slots), hasAnyData)
            }
            HistoryPeriod.LIFETIME -> {
                val years = years()
                val firstYear = years.keys.minOrNull() ?: today.year
                val slots = (firstYear..today.year).map { y ->
                    EnergySlot(label = y.toString(), shortLabel = y.toString(), energy = years[y] ?: EnergyTotals())
                }
                HistoryUiState(r, today, HistoryMath.sum(years.values.toList()), HistoryChart.Energy(slots), hasAnyData)
            }
        }
    }
}

/** Added channels at most; with the plant's two units (kW, %) that keeps the chart within [MAX_AXES] scales. */
const val MAX_CHANNELS = 3
const val MAX_AXES = 4
val PLANT_UNITS = listOf("kW", "%")
