// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.amslersolar.solardash.R
import ch.amslersolar.solardash.model.EnergyTotals
import ch.amslersolar.solardash.model.HistoryMath
import ch.amslersolar.solardash.model.Sample
import ch.amslersolar.solardash.ui.theme.LocalSeriesColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

enum class Metric(val label: String) {
    SOLAR("Solar"),
    CONSUMPTION("Consumption"),
    BATTERY("Battery"),
    GRID("Grid"),
    EXPORT("Exported"),
    IMPORT("Imported"),
    ;

    fun power(s: Sample): Int? = when (this) {
        SOLAR -> s.pvW
        CONSUMPTION -> s.loadW
        BATTERY -> s.batteryW
        GRID -> s.gridW
        EXPORT, IMPORT -> null
    }

    fun energy(e: EnergyTotals): Double? = when (this) {
        SOLAR -> e.pvKwh
        CONSUMPTION -> e.loadKwh
        EXPORT -> e.exportKwh
        IMPORT -> e.importKwh
        BATTERY, GRID -> null
    }
}

/** The metric's name in the phone's language ([Metric.label] stays English for logs and tests). */
@Composable
fun metricName(m: Metric): String = stringResource(
    when (m) {
        Metric.SOLAR -> R.string.history_metric_solar
        Metric.CONSUMPTION -> R.string.history_metric_consumption
        Metric.BATTERY -> R.string.history_metric_battery
        Metric.GRID -> R.string.history_metric_grid
        Metric.EXPORT -> R.string.history_metric_exported
        Metric.IMPORT -> R.string.history_metric_imported
    },
)

@Composable
fun Metric.color(): Color {
    val c = LocalSeriesColors.current
    return when (this) {
        Metric.SOLAR -> c.solar
        Metric.CONSUMPTION -> c.consumption
        Metric.BATTERY -> c.battery
        Metric.GRID, Metric.EXPORT -> c.grid
        Metric.IMPORT -> c.import
    }
}

/** Visible window of the x domain, as fractions 0..1. Pinch zooms around the fingers, drag pans. */
@Stable
class ChartViewport(private val minSpan: Float) {
    var start by mutableFloatStateOf(0f)
        private set
    var end by mutableFloatStateOf(1f)
        private set
    val span: Float get() = end - start
    val isZoomed: Boolean get() = span < 0.999f

    fun reset() {
        start = 0f
        end = 1f
    }

    /** [anchor] is the gesture centre as a fraction of the plot width; [pan] is a fraction of it too. */
    fun transform(anchor: Float, zoom: Float, pan: Float) {
        val newSpan = (span / zoom).coerceIn(minSpan, 1f)
        val pivot = start + anchor * span
        val newStart = (pivot - anchor * newSpan - pan * newSpan).coerceIn(0f, 1f - newSpan)
        start = newStart
        end = newStart + newSpan
    }
}

private class YScale(val min: Float, val max: Float, val step: Float, val ticks: List<Float>)

/** Axis from 0 (always included) to a rounded maximum, with 1/2/2.5/5 × 10ⁿ steps. */
private fun niceScale(lo: Float, hi: Float, target: Int = 4): YScale {
    val a = min(lo, 0f)
    var b = max(hi, 0f)
    if (b - a < 1e-6f) b = a + 1f
    val raw = (b - a) / target
    val magnitude = 10f.pow(floor(log10(raw)))
    val step = listOf(1f, 2f, 2.5f, 5f, 10f).map { it * magnitude }.first { it >= raw * 0.999f }
    val start = floor(a / step) * step
    val end = ceil(b / step) * step
    val ticks = generateSequence(start) { it + step }.takeWhile { it <= end + step / 2 }.toList()
    return YScale(start, end, step, ticks)
}

private class Plot(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val viewport: ChartViewport,
    val scale: YScale,
) {
    val width get() = right - left
    fun x(fraction: Float) = left + (fraction - viewport.start) / viewport.span * width
    fun y(value: Float) = bottom - (value - scale.min) / (scale.max - scale.min) * (bottom - top)
}

private val PLOT_LEFT = 52.dp
private val PLOT_BOTTOM = 24.dp
private val PLOT_TOP = 8.dp

/**
 * Two-finger pinch zooms and pans; one-finger horizontal drag pans once zoomed (vertical drags still
 * scroll the page); tap selects; double-tap resets the zoom.
 */
private fun Modifier.chartGestures(viewport: ChartViewport, plotLeftPx: Float, onTap: (Float) -> Unit, plotRightPx: Float = 0f) = this
    .pointerInput(viewport, plotLeftPx, plotRightPx) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            do {
                val event = awaitPointerEvent()
                val pressed = event.changes.count { it.pressed }
                val plotWidth = size.width - plotLeftPx - plotRightPx
                if (pressed >= 2) {
                    val centroid = event.calculateCentroid(useCurrent = true)
                    viewport.transform(
                        anchor = ((centroid.x - plotLeftPx) / plotWidth).coerceIn(0f, 1f),
                        zoom = event.calculateZoom(),
                        pan = event.calculatePan().x / plotWidth,
                    )
                    event.changes.forEach { it.consume() }
                } else if (pressed == 1 && viewport.isZoomed) {
                    val pan = event.calculatePan()
                    if (abs(pan.x) > abs(pan.y)) {
                        viewport.transform(anchor = 0.5f, zoom = 1f, pan = pan.x / plotWidth)
                        event.changes.forEach { it.consume() }
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }
    .pointerInput(viewport, plotLeftPx, plotRightPx) {
        detectTapGestures(
            onDoubleTap = { viewport.reset() },
            onTap = { offset ->
                val fraction = viewport.start + (offset.x - plotLeftPx) / (size.width - plotLeftPx - plotRightPx) * viewport.span
                onTap(fraction.coerceIn(0f, 1f))
            },
        )
    }

private class ChartStyle(val grid: Color, val axis: Color, val text: TextStyle, val surface: Color)

@Composable
private fun chartStyle(surface: Color) = ChartStyle(
    grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
    axis = MaterialTheme.colorScheme.outline,
    text = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
    surface = surface,
)

private fun DrawScope.drawAxes(
    plot: Plot,
    style: ChartStyle,
    measurer: TextMeasurer,
    yLabel: (Float) -> String,
    xLabels: List<Pair<Float, String>>,
) {
    plot.scale.ticks.forEach { v ->
        val y = plot.y(v)
        drawLine(if (v == 0f) style.axis else style.grid, Offset(plot.left, y), Offset(plot.right, y), strokeWidth = 1.dp.toPx())
        val label = measurer.measure(yLabel(v), style.text)
        drawText(label, topLeft = Offset(plot.left - label.size.width - 6.dp.toPx(), y - label.size.height / 2f))
    }
    var lastRight = Float.NEGATIVE_INFINITY
    xLabels.forEach { (fraction, text) ->
        val x = plot.x(fraction)
        if (x < plot.left - 1 || x > plot.right + 1) return@forEach
        val label = measurer.measure(text, style.text)
        val left = (x - label.size.width / 2f).coerceIn(plot.left - 4.dp.toPx(), size.width - label.size.width.toFloat())
        // Labels pushed inward at the edges could otherwise collide with their neighbour.
        if (left < lastRight + 6.dp.toPx()) return@forEach
        drawText(label, topLeft = Offset(left, plot.bottom + 4.dp.toPx()))
        lastRight = left + label.size.width
    }
}

private fun formatAxisPower(watts: Float, step: Float): String = when {
    step < 100f -> "${watts.roundToInt()} W"
    step < 1000f -> String.format(Locale.ROOT, "%.1f kW", watts / 1000f)
    else -> String.format(Locale.ROOT, "%.0f kW", watts / 1000f)
}

private fun formatAxisEnergy(kwh: Float, step: Float): String = when {
    step >= 1000f -> String.format(Locale.ROOT, "%.0f MWh", kwh / 1000f)
    step < 1f -> String.format(Locale.ROOT, "%.1f kWh", kwh)
    else -> String.format(Locale.ROOT, "%.0f kWh", kwh)
}

private val HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm")
private val WEEKDAY = DateTimeFormatter.ofPattern("EEE")
private val TICK_MINUTES = listOf(15, 30, 60, 120, 180, 240, 360, 720, 1440)

/** One curve on the day / week chart, in its display unit. */
data class ChartLine(
    val key: String,
    val label: String, // as shown on the chart (shortened)
    val full: String, // full name, for the tooltip
    val unit: String,
    val color: Color,
    val dash: Dash,
    val points: List<Pair<Long, Float>>,
)

/** Line patterns: plant power solid, battery charge dashed, added channels each with their own pattern. */
enum class Dash(val pattern: FloatArray?) {
    SOLID(null), DASHED(floatArrayOf(8f, 6f)), DASH_DOT(floatArrayOf(10f, 4f, 2f, 4f)),
    DOTTED(floatArrayOf(1f, 5f)), DASH_DOT_DOT(floatArrayOf(10f, 4f, 2f, 4f, 2f, 4f)),
}

/** A % axis always shows 0–100 %. */
val PERCENT_SCALE: Pair<Float, Float> = 0f to 100f

private val AXIS_WIDTH = 40.dp
private val AXIS_TOP = 20.dp // room for the unit above each axis

private fun axisTick(v: Float, step: Float): String = when {
    step >= 10f || v == 0f -> v.roundToInt().toString()
    step >= 1f -> String.format(Locale.ROOT, "%.0f", v)
    step >= 0.1f -> String.format(Locale.ROOT, "%.1f", v)
    else -> String.format(Locale.ROOT, "%.2f", v)
}

/** Fits a nice scale to [lo]..[hi] without forcing zero in (voltages, temperatures), unless the data is near it. */
private fun fitScale(lo: Float, hi: Float, includeZero: Boolean, target: Int = 4): YScale {
    if (includeZero) return niceScale(lo, hi, target)
    var a = lo
    var b = hi
    if (b - a < 1e-6f) { a -= 1f; b += 1f }
    val raw = (b - a) / target
    val magnitude = 10f.pow(floor(log10(raw)))
    val step = listOf(1f, 2f, 2.5f, 5f, 10f).map { it * magnitude }.first { it >= raw * 0.999f }
    val start = floor(a / step) * step
    val end = ceil(b / step) * step
    return YScale(start, end, step, generateSequence(start) { it + step }.takeWhile { it <= end + step / 2 }.toList())
}

/**
 * Curves over a day or a week with one y-axis per unit (up to four): first unit on the left, second on the
 * right, then a second left and a second right axis. Each axis fits its own visible data; % is fixed to 0–100.
 * Gaps longer than the recording gap limit break the line; a sample alone between two gaps is a dot.
 */
@Composable
fun PowerChart(
    lines: List<ChartLine>,
    startEpoch: Long,
    endEpoch: Long,
    selectedEpoch: Long?,
    onSelect: (Long) -> Unit,
    viewport: ChartViewport,
    surface: Color,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val style = chartStyle(surface)
    val density = LocalDensity.current
    val duration = (endEpoch - startEpoch).toFloat()
    fun fraction(ts: Long) = (ts - startEpoch) / duration

    val units = lines.filter { it.points.isNotEmpty() }.map { it.unit }.distinct().take(4)
    val leftAxes = units.indices.filter { it % 2 == 0 }.size
    val rightAxes = units.indices.filter { it % 2 == 1 }.size
    val plotLeftPx = with(density) { AXIS_WIDTH.toPx() * maxOf(1, leftAxes) }
    val plotRightPx = with(density) { if (rightAxes == 0) 8.dp.toPx() else AXIS_WIDTH.toPx() * rightAxes }

    // Each axis fits what's visible, so zooming into a quiet hour shows its detail.
    val visibleFrom = startEpoch + (viewport.start * duration).toLong()
    val visibleTo = startEpoch + (viewport.end * duration).toLong()
    val scales = units.associateWith { unit ->
        if (unit == "%") {
            YScale(PERCENT_SCALE.first, PERCENT_SCALE.second, 25f, listOf(0f, 25f, 50f, 75f, 100f))
        } else {
            val values = lines.filter { it.unit == unit }.flatMap { l -> l.points.filter { it.first in visibleFrom..visibleTo }.map { it.second } }
            val lo = values.minOrNull() ?: 0f
            val hi = values.maxOrNull() ?: 1f
            // Power and energy keep zero in view; other quantities (V, °C, Hz) fit their own range.
            fitScale(lo, hi, includeZero = unit.startsWith("k") || unit == "W" || lo <= 0f)
        }
    }
    val dashes = Dash.entries.associateWith { d -> d.pattern?.let { p -> PathEffect.dashPathEffect(p.map { with(density) { it.dp.toPx() } }.toFloatArray()) } }
    val zone = ZoneId.systemDefault()

    Canvas(modifier.chartGestures(viewport, plotLeftPx, { f -> onSelect(startEpoch + (f * duration).toLong()) }, plotRightPx)) {
        val top = AXIS_TOP.toPx()
        val bottom = size.height - PLOT_BOTTOM.toPx()
        val left = plotLeftPx
        val right = size.width - plotRightPx
        fun x(f: Float) = left + (f - viewport.start) / viewport.span * (right - left)
        fun y(scale: YScale, v: Float) = bottom - (v - scale.min) / (scale.max - scale.min) * (bottom - top)

        // Axes: index 0 left (gridlines), 1 right, 2 outer left, 3 outer right.
        units.forEachIndexed { i, unit ->
            val scale = scales.getValue(unit)
            val onLeft = i % 2 == 0
            val outer = i >= 2
            val edge = if (onLeft) left - (if (outer) AXIS_WIDTH.toPx() else 0f) else right + (if (outer) AXIS_WIDTH.toPx() else 0f)
            scale.ticks.forEach { v ->
                val yy = y(scale, v)
                if (i == 0) drawLine(if (v == 0f) style.axis else style.grid, Offset(left, yy), Offset(right, yy), strokeWidth = 1.dp.toPx())
                val label = measurer.measure(axisTick(v, scale.step), style.text)
                val lx = if (onLeft) edge - label.size.width - 4.dp.toPx() else edge + 4.dp.toPx()
                drawText(label, topLeft = Offset(lx, yy - label.size.height / 2f))
            }
            val header = measurer.measure(unit, style.text.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
            val hx = if (onLeft) edge - header.size.width - 4.dp.toPx() else edge + 4.dp.toPx()
            drawText(header, topLeft = Offset(hx, 0f))
        }

        val spanMinutes = viewport.span * duration / 60f
        val tick = TICK_MINUTES.firstOrNull { spanMinutes / it <= 7 } ?: TICK_MINUTES.last()
        val ticks = if (tick >= 1440) {
            // Whole days: step by calendar date (DST-safe) and label each day under its own middle.
            val first = Instant.ofEpochSecond(startEpoch).atZone(zone).toLocalDate()
            generateSequence(first) { it.plusDays(1) }
                .takeWhile { it.atStartOfDay(zone).toEpochSecond() < endEpoch }
                .map { day ->
                    val from = day.atStartOfDay(zone).toEpochSecond()
                    val to = day.plusDays(1).atStartOfDay(zone).toEpochSecond()
                    fraction((from + to) / 2) to WEEKDAY.format(day)
                }
                .toList()
        } else {
            generateSequence(0L) { it + tick * 60L }.takeWhile { it <= endEpoch - startEpoch }
                .map { offset -> fraction(startEpoch + offset) to HOUR_MINUTE.format(Instant.ofEpochSecond(startEpoch + offset).atZone(zone)) }
                .toList()
        }
        var lastRight = Float.NEGATIVE_INFINITY
        ticks.forEach { (f, text) ->
            val xx = x(f)
            if (xx < left - 1 || xx > right + 1) return@forEach
            val label = measurer.measure(text, style.text)
            val lx = (xx - label.size.width / 2f).coerceIn(left - 4.dp.toPx(), size.width - label.size.width.toFloat())
            if (lx < lastRight + 6.dp.toPx()) return@forEach
            drawText(label, topLeft = Offset(lx, bottom + 4.dp.toPx()))
            lastRight = lx + label.size.width
        }

        clipRect(left, 0f, right, size.height) {
            for (line in lines) {
                val scale = scales[line.unit] ?: continue
                val path = Path()
                var prev: Long? = null
                val pts = line.points
                for ((i, p) in pts.withIndex()) {
                    val (t, v) = p
                    val px = x(fraction(t))
                    val py = y(scale, v)
                    if (prev == null || t - prev > HistoryMath.MAX_GAP_SECONDS) path.moveTo(px, py) else path.lineTo(px, py)
                    // A sample with nothing near it on either side (e.g. from the background job) makes no line: a dot.
                    val before = pts.getOrNull(i - 1)?.let { t - it.first <= HistoryMath.MAX_GAP_SECONDS } == true
                    val after = pts.getOrNull(i + 1)?.let { it.first - t <= HistoryMath.MAX_GAP_SECONDS } == true
                    if (!before && !after) drawCircle(line.color, radius = 2.5.dp.toPx(), center = Offset(px, py))
                    prev = t
                }
                drawPath(
                    path, line.color,
                    style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = dashes[line.dash]),
                )
            }
            if (selectedEpoch != null) {
                val xx = x(fraction(selectedEpoch))
                drawLine(style.axis, Offset(xx, top), Offset(xx, bottom), strokeWidth = 1.dp.toPx())
                for (line in lines) {
                    val scale = scales[line.unit] ?: continue
                    val p = line.points.minByOrNull { abs(it.first - selectedEpoch) } ?: continue
                    if (abs(p.first - selectedEpoch) > HistoryMath.MAX_GAP_SECONDS) continue
                    val center = Offset(x(fraction(p.first)), y(scale, p.second))
                    drawCircle(style.surface, radius = 6.dp.toPx(), center = center)
                    drawCircle(line.color, radius = 4.dp.toPx(), center = center)
                }
            }
        }
    }
}

data class EnergySlot(val label: String, val shortLabel: String, val energy: EnergyTotals)

/** Energy per day / month / year as grouped bars, one bar per metric. */
@Composable
fun EnergyChart(
    slots: List<EnergySlot>,
    metrics: List<Metric>,
    selectedIndex: Int?,
    onSelect: (Int) -> Unit,
    viewport: ChartViewport,
    surface: Color,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val style = chartStyle(surface)
    val colors = metrics.associateWith { it.color() }
    val plotLeftPx = with(LocalDensity.current) { PLOT_LEFT.toPx() }
    val n = slots.size.coerceAtLeast(1)

    val first = floor(viewport.start * n).toInt().coerceIn(0, n - 1)
    val last = ceil(viewport.end * n).toInt().coerceIn(first + 1, n)
    val visibleMax = slots.subList(first, min(last, slots.size)).maxOfOrNull { s ->
        metrics.maxOfOrNull { it.energy(s.energy) ?: 0.0 } ?: 0.0
    } ?: 0.0
    val scale = niceScale(0f, visibleMax.toFloat())

    Canvas(modifier.chartGestures(viewport, plotLeftPx, { f -> onSelect((f * n).toInt().coerceIn(0, n - 1)) })) {
        val plot = Plot(PLOT_LEFT.toPx(), PLOT_TOP.toPx(), size.width, size.height - PLOT_BOTTOM.toPx(), viewport, scale)
        val slotWidth = plot.width / (viewport.span * n)

        // Thin out labels so they never collide.
        val widest = slots.maxOfOrNull { measurer.measure(it.shortLabel, style.text).size.width } ?: 0
        val every = max(1, ceil((widest + 8.dp.toPx()) / slotWidth).toInt())
        val labels = slots.mapIndexedNotNull { i, s -> if (i % every == 0) (i + 0.5f) / n to s.shortLabel else null }
        drawAxes(plot, style, measurer, { formatAxisEnergy(it, scale.step) }, labels)

        clipRect(plot.left, 0f, plot.right, size.height) {
            if (selectedIndex != null) {
                drawRect(
                    style.grid.copy(alpha = 0.35f),
                    topLeft = Offset(plot.x(selectedIndex.toFloat() / n), plot.top),
                    size = Size(slotWidth, plot.bottom - plot.top),
                )
            }
            val gap = 2.dp.toPx()
            val groupWidth = slotWidth * 0.72f
            val barWidth = ((groupWidth - gap * (metrics.size - 1)) / metrics.size).coerceAtLeast(1f)
            val radius = min(4.dp.toPx(), barWidth / 2)
            for (i in first until min(last, slots.size)) {
                val groupLeft = plot.x(i.toFloat() / n) + (slotWidth - groupWidth) / 2
                metrics.forEachIndexed { k, metric ->
                    val v = metric.energy(slots[i].energy) ?: return@forEachIndexed
                    if (v <= 0) return@forEachIndexed
                    val left = groupLeft + k * (barWidth + gap)
                    val top = plot.y(v.toFloat())
                    // Rounded data end, square on the baseline.
                    val path = Path().apply {
                        addRoundRect(
                            RoundRect(
                                left, top, left + barWidth, plot.y(0f),
                                topLeftCornerRadius = CornerRadius(radius),
                                topRightCornerRadius = CornerRadius(radius),
                            ),
                        )
                    }
                    drawPath(path, colors.getValue(metric))
                }
            }
        }
    }
}
