// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.ElectricalServices
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import ch.amslersolar.solardash.R
import ch.amslersolar.solardash.model.PlantBattery
import ch.amslersolar.solardash.ui.theme.LocalSeriesColors
import kotlin.math.abs
import kotlin.math.min

/** Below this, a branch counts as idle: grey line, no moving dots. */
private const val IDLE_W = 20

private const val DOTS_PER_LINE = 3
private val MIN_NODE = 90.dp
private val MAX_NODE = 132.dp
/**
 * Centre-to-centre distance from the middle point to each circle, as a multiple of the circle size.
 * Neighbouring circles are √2 · this apart diagonally; 0.9 keeps the flow lines long enough to read.
 */
private const val SPACING = 0.9f
private val SPACING_EXTRA = 8.dp
private val CAPTION_HEIGHT = 20.dp
/** Caption plus its spacing, above Solar and below Battery. */
private val CAPTION_BLOCK = CAPTION_HEIGHT + 4.dp
private val EDGE = 8.dp

/**
 * Energy flow between solar, grid, home and battery through a central point. Dots travel in the direction
 * energy flows, faster at higher power; the figures in the circles are magnitudes. Sign conventions as in
 * [ch.amslersolar.solardash.model.DeviceReading]. With [gridFailure] the grid's line is broken, a bold cross in the gap.
 */
@Composable
fun EnergyFlowDiagram(
    pvW: Int?,
    loadW: Int?,
    battery: PlantBattery?,
    gridW: Int?,
    modifier: Modifier = Modifier,
    /** Height available on screen, so the whole diagram fits without scrolling. */
    maxHeight: Dp = Dp.Infinity,
    /** Largest circle; above the dashboard's [MAX_NODE], text, icons and lines grow with the circles (kiosk). */
    maxNode: Dp = MAX_NODE,
    gridFailure: Boolean = false,
) {
    val colors = LocalSeriesColors.current
    val cut = MaterialTheme.colorScheme.onSurface
    val idle = MaterialTheme.colorScheme.outlineVariant

    // Power flowing *into* the centre on each branch; negative flows out to the node.
    val solarIn = pvW ?: 0
    val gridIn = -(gridW ?: 0) // importing → into the centre
    val batteryIn = battery?.powerW ?: 0 // discharging → into the centre
    val homeIn = -(loadW ?: 0)

    // Read only inside the Canvas, so each frame redraws the dots without recomposing the nodes.
    val clock = animationClock()

    val description = flowDescription(pvW, loadW, battery, gridW, gridFailure)

    BoxWithConstraints(
        modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        // Across: two spacings plus one circle; down: the same plus the captions. As large as fits, within bounds.
        // Captions grow with the circles, so they count as a share of a circle in the height.
        val factor = 2 * SPACING + 1
        val captionShare = CAPTION_BLOCK * 2 / MAX_NODE
        val byWidth = (maxWidth - EDGE * 2 - SPACING_EXTRA * 2) / factor
        // Up to the dashboard's size captions are fixed; beyond it they scale (both meet at MAX_NODE).
        val byHeightFixed = (maxHeight - CAPTION_BLOCK * 2 - SPACING_EXTRA * 2) / factor
        val byHeight = if (byHeightFixed <= MAX_NODE) byHeightFixed else (maxHeight - SPACING_EXTRA * 2) / (factor + captionShare)
        val node = minOf(byWidth, byHeight).coerceIn(MIN_NODE, maxNode.coerceAtLeast(MIN_NODE))
        // 1 on the dashboard; larger circles scale text, icons, lines and dots alike.
        val scale = (node / MAX_NODE).coerceAtLeast(1f)
        val captionBlock = CAPTION_BLOCK * scale
        val spacing = node * SPACING + SPACING_EXTRA
        val width = spacing * 2 + node + EDGE * 2
        val height = spacing * 2 + node + captionBlock * 2

        // Solar's caption sits above it, the others' below.
        val hub = DpOffset(width / 2, captionBlock + node / 2 + spacing)
        val solar = DpOffset(hub.x, hub.y - spacing)
        val batteryPos = DpOffset(hub.x, hub.y + spacing)
        val grid = DpOffset(hub.x - spacing, hub.y)
        val home = DpOffset(hub.x + spacing, hub.y)

        val base = if (node >= 110.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium
        val value = base.copy(fontWeight = FontWeight.SemiBold, fontSize = base.fontSize * scale)

        Box(Modifier.width(width).height(height)) {
            Canvas(Modifier.fillMaxSize()) {
                val seconds = clock.value
                val centre = Offset(hub.x.toPx(), hub.y.toPx())
                fun branch(at: DpOffset, powerIn: Int, color: Color) {
                    val end = Offset(at.x.toPx(), at.y.toPx())
                    val active = abs(powerIn) >= IDLE_W
                    drawLine(if (active) color.copy(alpha = 0.35f) else idle, end, centre, strokeWidth = 3.dp.toPx() * scale, cap = StrokeCap.Round)
                    if (!active) return
                    // Cycles per second: 0.25 when barely flowing, up to 1 at 15 kW and above.
                    val speed = 0.25f + 0.75f * min(1f, abs(powerIn) / 15_000f)
                    val (from, to) = if (powerIn > 0) end to centre else centre to end
                    repeat(DOTS_PER_LINE) { i ->
                        val t = (seconds * speed + i.toFloat() / DOTS_PER_LINE) % 1f
                        drawCircle(color, radius = 4.dp.toPx() * scale, center = from + (to - from) * t)
                    }
                }
                branch(solar, solarIn, colors.solar)
                if (gridFailure) {
                    // The line broken in the middle, with a cross in the gap: the connection is cut.
                    val end = Offset(grid.x.toPx(), grid.y.toPx())
                    val dir = (centre - end) / (centre - end).getDistance()
                    val across = Offset(-dir.y, dir.x)
                    // Halfway along the visible part, between the grid circle's edge and the centre.
                    val radius = node.toPx() / 2
                    val mid = end + dir * ((radius + (centre - end).getDistance()) / 2)
                    val gap = 18.dp.toPx() * scale
                    val arm = 13.dp.toPx() * scale
                    val bold = 5.dp.toPx() * scale
                    val line = 3.dp.toPx() * scale
                    drawLine(idle, end, mid - dir * gap, strokeWidth = line, cap = StrokeCap.Round)
                    drawLine(idle, mid + dir * gap, centre, strokeWidth = line, cap = StrokeCap.Round)
                    drawLine(cut, mid - (dir + across) * arm, mid + (dir + across) * arm, strokeWidth = bold, cap = StrokeCap.Round)
                    drawLine(cut, mid - (dir - across) * arm, mid + (dir - across) * arm, strokeWidth = bold, cap = StrokeCap.Round)
                } else {
                    branch(grid, gridIn, colors.grid)
                }
                branch(home, homeIn, colors.consumption)
                if (battery != null) branch(batteryPos, batteryIn, colors.battery)
                drawCircle(idle, radius = 5.dp.toPx() * scale, center = centre)
            }

            FlowNode(solar, node, Icons.Outlined.WbSunny, colors.solar, pvW?.let(::formatPower), stringResource(R.string.screens_solar), value, scale, captionAbove = true)
            FlowNode(grid, node, Icons.Outlined.ElectricalServices, colors.grid, gridW?.let { formatPower(abs(it)) }, stringResource(R.string.screens_grid), value, scale)
            FlowNode(home, node, Icons.Outlined.Home, colors.consumption, loadW?.let(::formatPower), stringResource(R.string.screens_home), value, scale)
            if (battery != null) {
                FlowNode(
                    batteryPos, node, Icons.Outlined.BatteryChargingFull, colors.battery, formatPower(abs(battery.powerW)),
                    stringResource(R.string.screens_battery), value, scale, socPercent = battery.socPercent,
                )
            }
        }
    }
}

/** What the diagram shows, read out by screen readers in place of the drawing. */
@Composable
private fun flowDescription(pvW: Int?, loadW: Int?, battery: PlantBattery?, gridW: Int?, gridFailure: Boolean): String {
    val unknown = stringResource(R.string.screens_flow_desc_unknown)
    val idle = stringResource(R.string.screens_flow_desc_idle)
    val parts = mutableListOf(
        stringResource(R.string.screens_flow_desc_solar, pvW?.let(::formatPower) ?: unknown),
        stringResource(R.string.screens_flow_desc_home, loadW?.let(::formatPower) ?: unknown),
    )
    if (battery != null) {
        val power = formatPower(abs(battery.powerW))
        val state = when {
            battery.powerW > 0 -> stringResource(R.string.screens_flow_desc_discharging, power)
            battery.powerW < 0 -> stringResource(R.string.screens_flow_desc_charging, power)
            else -> idle
        }
        parts += battery.socPercent?.let { stringResource(R.string.screens_flow_desc_battery_soc, it, state) }
            ?: stringResource(R.string.screens_flow_desc_battery, state)
    }
    val grid = when {
        gridFailure -> stringResource(R.string.screens_flow_desc_grid_failed)
        gridW == null -> unknown
        gridW > 0 -> stringResource(R.string.screens_flow_desc_exporting, formatPower(gridW))
        gridW < 0 -> stringResource(R.string.screens_flow_desc_importing, formatPower(-gridW))
        else -> idle
    }
    parts += stringResource(R.string.screens_flow_desc_grid, grid)
    return parts.joinToString(" ")
}

/** Seconds since first composition, advancing every frame; drives the moving dots. */
@Composable
private fun animationClock(): State<Float> {
    val state = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now -> state.floatValue = (now - start) / 1_000_000_000f }
        }
    }
    return state
}

/**
 * A circle with the icon near its top and the power figure in its centre, captioned with its name. With
 * [socPercent] (batteries) the charge level is drawn as a ring around the circle and printed under the power.
 */
@Composable
private fun FlowNode(
    center: DpOffset,
    size: Dp,
    icon: ImageVector,
    color: Color,
    power: String?,
    caption: String,
    valueStyle: TextStyle,
    scale: Float,
    captionAbove: Boolean = false,
    socPercent: Int? = null,
) {
    val surface = CardDefaults.cardColors().containerColor
    val track = MaterialTheme.colorScheme.outlineVariant
    val captionWidth = size + 40.dp * scale
    val captionHeight = CAPTION_HEIGHT * scale
    val gap = 4.dp * scale
    val captionStyle = MaterialTheme.typography.bodyMedium.let { it.copy(fontSize = it.fontSize * scale, lineHeight = it.lineHeight * scale) }
    val captionText: @Composable () -> Unit = {
        Text(caption, style = captionStyle, textAlign = TextAlign.Center, modifier = Modifier.height(captionHeight))
    }
    Column(
        Modifier
            .offset(center.x - captionWidth / 2, center.y - size / 2 - if (captionAbove) captionHeight + gap else 0.dp)
            .width(captionWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        if (captionAbove) captionText()
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            if (socPercent == null) {
                Box(Modifier.fillMaxSize().background(surface, CircleShape).border(2.dp * scale, color, CircleShape))
            } else {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 4.dp.toPx() * scale
                    val inset = stroke / 2
                    drawCircle(surface)
                    drawCircle(track, radius = this.size.minDimension / 2 - inset, style = Stroke(stroke))
                    drawArc(
                        color, startAngle = -90f, sweepAngle = 360f * socPercent.coerceIn(0, 100) / 100f, useCenter = false,
                        topLeft = Offset(inset, inset), size = Size(this.size.width - stroke, this.size.height - stroke),
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
            }
            val inset = size * 0.12f
            Icon(
                icon, contentDescription = null, tint = color,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = inset).size((size * 0.22f).coerceIn(22.dp, 30.dp * scale)),
            )
            Text(power ?: "–", style = valueStyle, textAlign = TextAlign.Center, maxLines = 1)
            if (socPercent != null) {
                Text(
                    "$socPercent %", style = valueStyle, maxLines = 1,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = inset),
                )
            }
        }
        if (!captionAbove) captionText()
    }
}
