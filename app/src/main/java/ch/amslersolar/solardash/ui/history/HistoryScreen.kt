// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalDensity
import ch.amslersolar.solardash.model.Channels
import ch.amslersolar.solardash.model.DeviceConfig
import ch.amslersolar.solardash.ui.theme.LocalSeriesColors
import java.util.Locale
import kotlin.math.abs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.TableRows
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.amslersolar.solardash.R
import ch.amslersolar.solardash.ui.historyPeriodName
import ch.amslersolar.solardash.ui.historyRangeLabel
import ch.amslersolar.solardash.model.EnergyTotals
import ch.amslersolar.solardash.model.HistoryMath
import ch.amslersolar.solardash.model.HistoryPeriod
import ch.amslersolar.solardash.model.Sample
import ch.amslersolar.solardash.ui.InfoCard
import ch.amslersolar.solardash.ui.InfoRow
import ch.amslersolar.solardash.ui.formatEnergy
import ch.amslersolar.solardash.ui.formatPower
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val ENERGY_METRICS = listOf(Metric.SOLAR, Metric.CONSUMPTION, Metric.EXPORT, Metric.IMPORT)
private val TIME = DateTimeFormatter.ofPattern("HH:mm")
private val DAY_TIME = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")

/** Content of a plant's History tab. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryTab(
    hasDemoDevices: Boolean,
    state: HistoryUiState?,
    generating: Boolean,
    onPeriod: (HistoryPeriod) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onFillDemo: () -> Unit,
    devices: List<DeviceConfig>,
    catalog: suspend (String) -> List<Triple<String, String, String>>,
    onAddChannels: (List<Pair<String, String>>, (ChannelRefusal) -> Unit) -> Unit,
    onRemoveChannel: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    /** The device shown on its own; null: the whole plant. */
    selectedDevice: String? = null,
    onDevice: (String?) -> Unit = {},
) {
    var showTable by rememberSaveable { mutableStateOf(false) }
    var hiddenPower by rememberSaveable { mutableStateOf(setOf<String>()) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var refusal by remember { mutableStateOf<ChannelRefusal?>(null) }
    var hiddenEnergy by rememberSaveable { mutableStateOf(setOf(Metric.EXPORT, Metric.IMPORT)) }

    if (state == null) {
        Box(modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        return
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (devices.size > 1) {
            item {
                // The whole plant, or one device's own curves and energy.
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = selectedDevice == null, onClick = { onDevice(null) }, label = { Text(stringResource(R.string.history_whole_plant)) })
                    devices.forEach { d ->
                        FilterChip(selected = selectedDevice == d.id, onClick = { onDevice(d.id) }, label = { Text(d.name) })
                    }
                }
            }
        }
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                HistoryPeriod.entries.forEachIndexed { i, period ->
                    SegmentedButton(
                        selected = state.range.period == period,
                        onClick = { onPeriod(period) },
                        shape = SegmentedButtonDefaults.itemShape(i, HistoryPeriod.entries.size),
                        icon = {},
                        label = { Text(historyPeriodName(period), maxLines = 1, style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val navigable = state.range.period != HistoryPeriod.LIFETIME
                IconButton(onClick = onPrevious, enabled = navigable) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.history_previous))
                }
                Text(
                    historyRangeLabel(state.range),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium,
                )
                IconButton(onClick = onNext, enabled = navigable && state.range.hasNext(state.today)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.history_next))
                }
                IconButton(onClick = { showTable = !showTable }) {
                    Icon(
                        if (showTable) Icons.Outlined.BarChart else Icons.Outlined.TableRows,
                        contentDescription = stringResource(if (showTable) R.string.history_show_chart else R.string.history_show_table),
                    )
                }
            }
        }

        if (!state.hasAnyData) {
            item { EmptyHistory(hasDemoDevices, generating, onFillDemo) }
            return@LazyColumn
        }

        item {
            when (val chart = state.chart) {
                is HistoryChart.Power -> {
                    if (showTable) {
                        if (chart.days.isNotEmpty()) EnergyTable(chart.days) else PowerTable(chart.samples)
                    } else {
                        PowerCard(
                            chart, hiddenPower,
                            onToggle = { k -> hiddenPower = if (k in hiddenPower) hiddenPower - k else hiddenPower + k },
                            onAddChannel = { picking = true },
                            onRemoveChannel = onRemoveChannel,
                        )
                    }
                    if (picking) {
                        ChannelPicker(
                            devices = devices,
                            added = chart.channels.map { it.deviceId to it.key }.toSet(),
                            catalog = catalog,
                            onAdd = { selection ->
                                picking = false
                                onAddChannels(selection) { refusal = it }
                            },
                            onDismiss = { picking = false },
                        )
                    }
                }
                is HistoryChart.Energy -> {
                    val metrics = ENERGY_METRICS.filterNot { it in hiddenEnergy }
                    if (showTable) EnergyTable(chart.slots)
                    else EnergyCard(chart, state.range.period, metrics, hiddenEnergy) { m -> hiddenEnergy = hiddenEnergy.toggle(m) }
                }
            }
        }
        item { TotalsCard(state.totals) }
        refusal?.let { reason ->
            item {
                AlertDialog(
                    onDismissRequest = { refusal = null },
                    title = { Text(stringResource(R.string.history_refused_title)) },
                    text = { Text(refusalMessage(reason)) },
                    confirmButton = { TextButton(onClick = { refusal = null }) { Text(stringResource(R.string.history_ok)) } },
                )
            }
        }
        if (hasDemoDevices) {
            item {
                TextButton(onClick = onFillDemo, enabled = !generating) {
                    Text(stringResource(if (generating) R.string.history_generating else R.string.history_fill_demo))
                }
            }
        }
    }
}

@Composable
private fun refusalMessage(reason: ChannelRefusal): String = when (reason) {
    is ChannelRefusal.TooManyChannels -> pluralStringResource(R.plurals.history_refused_channels, reason.max, reason.max)
    is ChannelRefusal.TooManyUnits ->
        pluralStringResource(R.plurals.history_refused_units, reason.max, reason.max, reason.units.joinToString())
}

private fun Set<Metric>.toggle(m: Metric) = if (m in this) this - m else this + m

/** Readout values count only when a sample lies within this many seconds of the tapped time. */
private const val READOUT_WINDOW_S = 20 * 60L
private val CHANNEL_DASHES = listOf(Dash.DASH_DOT, Dash.DOTTED, Dash.DASH_DOT_DOT)
private const val SOC_KEY = "plant:soc"

@Composable
private fun EmptyHistory(hasDemoDevices: Boolean, generating: Boolean, onFillDemo: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            stringResource(R.string.history_empty),
        )
        if (hasDemoDevices) {
            Button(onClick = onFillDemo, enabled = !generating) {
                Text(stringResource(if (generating) R.string.history_generating else R.string.history_fill_demo))
            }
        }
    }
}

/** Series toggles; also the legend, so every color has its name next to it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(all: List<Metric>, hidden: Set<Metric>, onToggle: (Metric) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        all.forEach { m ->
            FilterChip(
                selected = m !in hidden,
                onClick = { onToggle(m) },
                label = { Text(metricName(m)) },
                leadingIcon = { Box(Modifier.size(10.dp).background(m.color(), CircleShape)) },
            )
        }
    }
}

private data class ReadoutValue(val color: Color, val label: String, val value: String)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Readout(title: String, values: List<ReadoutValue>) {
    Column(Modifier.heightIn(min = 40.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            values.forEach { (color, label, v) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(color, CircleShape))
                    Text("$label $v", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
    }
}

/** Plant curves in kW and battery charge in %, plus the added device channels, each on its unit's axis. */
@Composable
private fun chartLines(chart: HistoryChart.Power): List<ChartLine> {
    val c = LocalSeriesColors.current
    val colors = Metric.entries.associateWith { it.color() }
    val names = Metric.entries.associateWith { metricName(it) }
    val soc = stringResource(R.string.history_soc)
    fun plant(m: Metric, pick: (Sample) -> Int?) = ChartLine(
        "plant:${m.name}", names.getValue(m), names.getValue(m), "kW", colors.getValue(m), Dash.SOLID,
        chart.samples.mapNotNull { s -> pick(s)?.let { s.epochSecond to it / 1000f } },
    )
    return listOf(
        plant(Metric.SOLAR) { it.pvW },
        plant(Metric.CONSUMPTION) { it.loadW },
        plant(Metric.BATTERY) { it.batteryW },
        plant(Metric.GRID) { it.gridW },
        ChartLine(
            SOC_KEY, soc, soc, "%", c.battery, Dash.DASHED,
            // Some devices report nonsense outside 0–100 % (seen on LG before the scale fix); leave it out.
            chart.samples.mapNotNull { s -> s.socPercent?.takeIf { it in 0..100 }?.let { s.epochSecond to it.toFloat() } },
        ),
    ) + chart.channels.map { ch ->
        ChartLine(
            "${ch.deviceId}|${ch.key}", Channels.shorten(ch.name), ch.full, ch.unit,
            c.channels[ch.slot % c.channels.size], CHANNEL_DASHES[ch.slot % CHANNEL_DASHES.size],
            ch.points.map { (t, v) -> t to v.toFloat() },
        )
    }
}

private fun formatValue(v: Float, unit: String): String {
    val decimals = when {
        unit == "%" -> 0
        abs(v) >= 100 -> 1
        else -> 2
    }
    val number = String.format(Locale.getDefault(), "%.${decimals}f", v)
    return if (unit == "value") number else "$number $unit"
}

@Composable
private fun PowerCard(
    chart: HistoryChart.Power,
    hidden: Set<String>,
    onToggle: (String) -> Unit,
    onAddChannel: () -> Unit,
    onRemoveChannel: (String, String) -> Unit,
) {
    // Zoom in to half an hour at most, whatever the range.
    val viewport = remember(chart.startEpoch, chart.endEpoch) {
        ChartViewport(minSpan = (1800f / (chart.endEpoch - chart.startEpoch)).coerceAtMost(1f))
    }
    val multiDay = chart.endEpoch - chart.startEpoch > 25 * 3600
    var selected by remember(chart.startEpoch) { mutableStateOf<Long?>(null) }
    val surface = CardDefaults.cardColors().containerColor
    val zone = ZoneId.systemDefault()
    val all = chartLines(chart)
    val lines = all.filterNot { it.key in hidden }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LineLegend(all, hidden, onToggle, onAddChannel, onRemove = { key ->
                chart.channels.firstOrNull { "${it.deviceId}|${it.key}" == key }?.let { onRemoveChannel(it.deviceId, it.key) }
            }, canAdd = chart.channels.size < MAX_CHANNELS)
            val t = selected
            if (t != null) {
                Readout(
                    (if (multiDay) DAY_TIME else TIME).format(Instant.ofEpochSecond(t).atZone(zone)),
                    lines.mapNotNull { line ->
                        val p = line.points.minByOrNull { abs(it.first - t) } ?: return@mapNotNull null
                        if (abs(p.first - t) > READOUT_WINDOW_S) null else ReadoutValue(line.color, line.label, formatValue(p.second, line.unit))
                    },
                )
            } else {
                Readout(stringResource(R.string.history_tap_chart), emptyList())
            }
            if (all.all { it.points.isEmpty() }) {
                Box(Modifier.fillMaxWidth().height(240.dp), Alignment.Center) {
                    Text(stringResource(if (multiDay) R.string.history_no_samples_week else R.string.history_no_samples_day))
                }
            } else {
                PowerChart(
                    lines, chart.startEpoch, chart.endEpoch, selected, { selected = it },
                    viewport, surface, Modifier.fillMaxWidth().height(260.dp),
                )
            }
            ChartHint()
        }
    }
}

/** A short sample of the line in its colour and pattern, so the legend matches the chart. */
@Composable
private fun LineSample(line: ChartLine) {
    val density = LocalDensity.current
    Canvas(Modifier.size(width = 20.dp, height = 10.dp)) {
        val effect = line.dash.pattern?.let { p -> PathEffect.dashPathEffect(p.map { with(density) { it.dp.toPx() } }.toFloatArray()) }
        drawLine(line.color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 2.dp.toPx(), pathEffect = effect)
    }
}

/** Series toggles; also the legend. Added channels can be removed; a long press shows a channel's full name. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun LineLegend(
    lines: List<ChartLine>,
    hidden: Set<String>,
    onToggle: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    canAdd: Boolean,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        lines.forEach { line ->
            val channel = !line.key.startsWith("plant:")
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text("${line.full} (${line.unit})") } },
                state = rememberTooltipState(),
            ) {
                FilterChip(
                    selected = line.key !in hidden,
                    onClick = { onToggle(line.key) },
                    label = { Text(if (line.unit == "kW" && !channel) line.label else "${line.label} · ${line.unit}") },
                    leadingIcon = { LineSample(line) },
                    trailingIcon = if (channel) {
                        {
                            Icon(
                                Icons.Filled.Close, contentDescription = stringResource(R.string.history_remove_channel, line.full),
                                modifier = Modifier.size(18.dp).clickable { onRemove(line.key) },
                            )
                        }
                    } else null,
                )
            }
        }
        AssistChip(
            onClick = onAdd,
            enabled = canAdd,
            label = { Text(stringResource(R.string.history_add_channel)) },
            leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
    }
}

@Composable
private fun EnergyCard(
    chart: HistoryChart.Energy,
    period: HistoryPeriod,
    metrics: List<Metric>,
    hidden: Set<Metric>,
    onToggle: (Metric) -> Unit,
) {
    val key = chart.slots.firstOrNull()?.label to period
    val viewport = remember(key) { ChartViewport(minSpan = (3f / chart.slots.size.coerceAtLeast(1)).coerceAtMost(1f)) }
    var selected by remember(key) { mutableStateOf<Int?>(null) }
    val surface = CardDefaults.cardColors().containerColor

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Legend(ENERGY_METRICS, hidden, onToggle)
            val slot = selected?.let { chart.slots.getOrNull(it) }
            if (slot != null) {
                Readout(slot.label, metrics.mapNotNull { m -> m.energy(slot.energy)?.let { ReadoutValue(m.color(), metricName(m), formatEnergy(it)) } })
            } else {
                Readout(stringResource(R.string.history_tap_bar), emptyList())
            }
            EnergyChart(chart.slots, metrics, selected, { selected = it }, viewport, surface, Modifier.fillMaxWidth().height(240.dp))
            ChartHint()
        }
    }
}

@Composable
private fun ChartHint() {
    Text(
        stringResource(R.string.history_chart_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TotalsCard(e: EnergyTotals) {
    InfoCard(stringResource(R.string.history_totals)) {
        InfoRow(stringResource(R.string.history_solar_yield), formatEnergy(e.pvKwh))
        InfoRow(metricName(Metric.CONSUMPTION), formatEnergy(e.loadKwh))
        InfoRow(metricName(Metric.EXPORT), formatEnergy(e.exportKwh))
        InfoRow(metricName(Metric.IMPORT), formatEnergy(e.importKwh))
        InfoRow(stringResource(R.string.history_self_sufficiency), formatShare(HistoryMath.selfSufficiency(e)))
        InfoRow(stringResource(R.string.history_self_consumption), formatShare(HistoryMath.selfConsumption(e)))
    }
}

private fun formatShare(v: Double?) = v?.let { "${(it * 100).roundToInt()} %" } ?: "–"

/** Hourly averages, as an accessible alternative to the day chart. */
@Composable
private fun PowerTable(samples: List<Sample>) {
    val zone = ZoneId.systemDefault()
    val byHour = samples.groupBy { Instant.ofEpochSecond(it.epochSecond).atZone(zone).hour }
    val solar = metricName(Metric.SOLAR)
    val cons = stringResource(R.string.history_consumption_short)
    InfoCard(stringResource(R.string.history_hourly_power)) {
        if (byHour.isEmpty()) Text(stringResource(R.string.history_no_samples_day))
        byHour.toSortedMap().forEach { (hour, list) ->
            fun avg(pick: (Sample) -> Int?) = list.mapNotNull(pick).takeIf { it.isNotEmpty() }?.average()?.roundToInt()
            val values = listOfNotNull(
                avg { it.pvW }?.let { "$solar ${formatPower(it)}" },
                avg { it.loadW }?.let { "$cons ${formatPower(it)}" },
            ).joinToString(" · ")
            InfoRow(String.format(java.util.Locale.ROOT, "%02d:00", hour), values)
        }
    }
}

@Composable
private fun EnergyTable(slots: List<EnergySlot>) {
    val solarConsumption = "${metricName(Metric.SOLAR)} · ${metricName(Metric.CONSUMPTION)}"
    val exportImport = "${metricName(Metric.EXPORT)} · ${metricName(Metric.IMPORT)}"
    InfoCard(stringResource(R.string.history_energy)) {
        slots.forEach { s ->
            val e = s.energy
            if (listOf(e.pvKwh, e.loadKwh, e.exportKwh, e.importKwh).all { it == null }) return@forEach
            Column(Modifier.padding(vertical = 4.dp)) {
                Text(s.label, style = MaterialTheme.typography.labelLarge)
                InfoRow(solarConsumption, "${formatEnergy(e.pvKwh)} · ${formatEnergy(e.loadKwh)}")
                InfoRow(exportImport, "${formatEnergy(e.exportKwh)} · ${formatEnergy(e.importKwh)}")
            }
        }
    }
}
