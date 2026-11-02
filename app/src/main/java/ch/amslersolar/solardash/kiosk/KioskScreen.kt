// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.kiosk

import ch.amslersolar.solardash.ui.status.statusHeadline
import ch.amslersolar.solardash.ui.status.KioskStatusLine
import ch.amslersolar.solardash.ui.status.IssueList
import ch.amslersolar.solardash.ui.status.GuideActivity
import ch.amslersolar.solardash.model.PlantStatus
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.amslersolar.solardash.R
import ch.amslersolar.solardash.model.PlantSnapshot
import ch.amslersolar.solardash.ui.EnergyFlowDiagram
import ch.amslersolar.solardash.ui.PlantState
import ch.amslersolar.solardash.ui.formatEnergy
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

/**
 * Full-screen energy flow with today's totals. While [dimmed] the page is black (nothing animates), and the
 * first touch only wakes it, so a tap meant to wake it never hits the × button.
 */
@Composable
fun KioskScreen(
    plantName: String,
    state: PlantState,
    status: PlantStatus?,
    dimmed: Boolean,
    onTouch: () -> Unit,
    onExit: () -> Unit,
) {
    val isDimmed by rememberUpdatedState(dimmed)
    val touch by rememberUpdatedState(onTouch)
    Box(
        Modifier
            .fillMaxSize()
            .background(if (dimmed) Color.Black else MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val wasDimmed = isDimmed
                    touch()
                    if (wasDimmed) {
                        // Swallow the whole gesture that woke the screen.
                        down.consume()
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    }
                }
            },
    ) {
        if (!dimmed) Content(plantName, state, status, onExit)
    }
}

/** Circles grow up to this on the kiosk screen (the dashboard stops at 132 dp). */
private val KIOSK_MAX_NODE = 360.dp

@Composable
private fun Content(plantName: String, state: PlantState, status: PlantStatus?, onExit: () -> Unit) {
    val snapshot = (state as? PlantState.Live)?.snapshot
    var showIssues by remember { mutableStateOf(false) }
    val context = LocalContext.current
    if (showIssues && status != null) {
        AlertDialog(
            onDismissRequest = { showIssues = false },
            title = { Text(statusHeadline(status), style = MaterialTheme.typography.headlineSmall) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    IssueList(status.issues, onGuide = { GuideActivity.open(context, it) }, large = true)
                }
            },
            confirmButton = { TextButton(onClick = { showIssues = false }) { Text(stringResource(R.string.forms_about_close), style = MaterialTheme.typography.titleMedium) } },
        )
    }
    // Generous margins: wall frames and rounded screen corners cover the edges of the display.
    BoxWithConstraints(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout).padding(horizontal = 40.dp, vertical = 32.dp),
    ) {
        // Landscape (the usual wall mount): totals in a column beside the flow, so the flow gets the full height.
        val landscape = maxWidth > maxHeight * 1.2f
        val sideWidth = maxWidth * 0.22f
        Column(Modifier.fillMaxSize()) {
            Header(plantName, snapshot, status, onExit, onStatus = { showIssues = true })
            if (landscape) {
                Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Flow(state, Modifier.weight(1f).fillMaxSize())
                    snapshot?.let {
                        Column(
                            Modifier.width(sideWidth).padding(start = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(28.dp),
                        ) { TodayFigures(it, alignStart = true, large = false) }
                    }
                }
            } else {
                Flow(state, Modifier.weight(1f).fillMaxWidth())
                snapshot?.let {
                    // Portrait: two by two under the flow.
                    val figures = todayFigures(it)
                    Column(Modifier.fillMaxWidth().padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(32.dp)) {
                        figures.chunked(2).forEach { pair ->
                            Row(Modifier.fillMaxWidth()) {
                                pair.forEach { (label, kwh) -> Figure(label, kwh, Modifier.weight(1f), alignStart = false, large = true) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(plantName: String, s: PlantSnapshot?, status: PlantStatus?, onExit: () -> Unit, onStatus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(plantName, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (s != null) {
                    Text(
                        "  " + stringResource(R.string.screens_updated, TIME.format(s.readAt)),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            // Large and in plain words: what's wrong, if anything; a tap shows the details and the guides.
            if (status != null) KioskStatusLine(status, onStatus)
        }
        IconButton(onClick = onExit, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.screens_end_kiosk_mode), modifier = Modifier.size(32.dp))
        }
    }
}

@Composable
private fun Flow(state: PlantState, modifier: Modifier) {
    BoxWithConstraints(modifier, Alignment.Center) {
        when (state) {
            PlantState.Loading -> CircularProgressIndicator()
            PlantState.NoDevices -> Text(stringResource(R.string.screens_kiosk_no_devices), style = MaterialTheme.typography.titleLarge)
            is PlantState.Live -> {
                val s = state.snapshot
                EnergyFlowDiagram(
                    pvW = s.pvPowerW,
                    loadW = s.loadPowerW,
                    battery = s.battery,
                    gridW = s.gridPowerW,
                    maxHeight = maxHeight,
                    maxNode = KIOSK_MAX_NODE,
                )
            }
        }
    }
}

private fun todayFigures(s: PlantSnapshot) = listOf(
    R.string.screens_solar_today to s.today.pvKwh,
    R.string.screens_consumption_today to s.today.loadKwh,
    R.string.screens_exported_today to s.today.exportKwh,
    R.string.screens_imported_today to s.today.importKwh,
)

@Composable
private fun TodayFigures(s: PlantSnapshot, alignStart: Boolean, large: Boolean) {
    todayFigures(s).forEach { (label, kwh) -> Figure(label, kwh, Modifier, alignStart, large) }
}

@Composable
private fun Figure(@StringRes label: Int, kwh: Double?, modifier: Modifier, alignStart: Boolean, large: Boolean) {
    val type = MaterialTheme.typography
    Column(modifier, horizontalAlignment = if (alignStart) Alignment.Start else Alignment.CenterHorizontally) {
        Text(formatEnergy(kwh), style = (if (large) type.displayMedium else type.displaySmall).copy(fontWeight = FontWeight.SemiBold))
        // Labels large enough to read from across the room, not just the figures.
        Text(stringResource(label), style = if (large) type.headlineSmall else type.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
