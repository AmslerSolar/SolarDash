// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Battery5Bar
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ElectricMeter
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.EvStation
import androidx.compose.material.icons.outlined.HeatPump
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.SolarPower
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.amslersolar.solardash.R
import ch.amslersolar.solardash.driver.spec.SpecDevices
import ch.amslersolar.solardash.driver.spec.SpecLibrary
import ch.amslersolar.solardash.model.DeviceConfig
import ch.amslersolar.solardash.model.DeviceKind
import ch.amslersolar.solardash.model.DeviceProblem
import ch.amslersolar.solardash.model.DeviceReading
import ch.amslersolar.solardash.model.DeviceStatus
import ch.amslersolar.solardash.model.DeviceType
import ch.amslersolar.solardash.model.MeterRole
import ch.amslersolar.solardash.ui.status.severityColor
import ch.amslersolar.solardash.ui.theme.LocalSeriesColors

/** A device's state on its card, worst first. */
enum class CardState { FAULT, NO_CONNECTION, WARNING, WAITING, SLEEPING, ONLINE, RUNNING }

/** What the card says about a device: its connection, then what the device itself reports. */
fun cardState(device: DeviceConfig, status: DeviceStatus?): CardState {
    if (status == null) return CardState.WAITING
    val r = status.reading ?: return CardState.NO_CONNECTION
    return when {
        r.problems.any { it.kind == DeviceProblem.Kind.FAULT } -> CardState.FAULT
        r.problems.any { it.kind == DeviceProblem.Kind.WARNING } -> CardState.WARNING
        r.status?.startsWith("Sleeping") == true -> CardState.SLEEPING
        device.type.kind == DeviceKind.METER -> CardState.ONLINE
        else -> CardState.RUNNING
    }
}

/**
 * One device as a card for the Devices tab: name, state (with the status card's colours), its one or two main
 * values in large type, and a drawing of its kind. Tap: details; long press: edit or remove.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DeviceCard(device: DeviceConfig, status: DeviceStatus?, onOpen: () -> Unit, onEdit: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val state = cardState(device, status)
    val r = status?.reading
    Box {
        Card(
            Modifier.fillMaxWidth().heightIn(min = 196.dp).combinedClickable(onClick = onOpen, onLongClick = { menu = true }),
            shape = RoundedCornerShape(20.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(device.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                StateLine(state, status)
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (r != null) CardValues(device, r)
                    }
                    Icon(
                        kindIcon(device), contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    )
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.screens_edit_device, device.name)) }, onClick = { menu = false; onEdit() })
            DropdownMenuItem(text = { Text(stringResource(R.string.screens_remove_device, device.name)) }, onClick = { menu = false; onRemove() })
        }
    }
}

@Composable
private fun StateLine(state: CardState, status: DeviceStatus?) {
    val (icon, color, text) = when (state) {
        CardState.FAULT -> Triple(Icons.Outlined.Error, MaterialTheme.colorScheme.error, stringResource(R.string.screens_card_fault))
        CardState.NO_CONNECTION -> Triple(Icons.Outlined.CloudOff, MaterialTheme.colorScheme.error, stringResource(R.string.screens_card_no_connection))
        CardState.WARNING -> Triple(Icons.Outlined.Warning, severityColor(ch.amslersolar.solardash.model.Severity.WARNING), stringResource(R.string.screens_card_warning))
        CardState.WAITING -> Triple(Icons.Outlined.HourglassEmpty, MaterialTheme.colorScheme.onSurfaceVariant, stringResource(R.string.screens_waiting_for_data))
        CardState.SLEEPING -> Triple(Icons.Outlined.Bedtime, MaterialTheme.colorScheme.onSurfaceVariant, stringResource(R.string.screens_card_sleeping))
        CardState.ONLINE -> Triple(Icons.Outlined.CheckCircle, MaterialTheme.colorScheme.secondary, stringResource(R.string.screens_card_online))
        CardState.RUNNING -> Triple(Icons.Outlined.CheckCircle, severityColor(null), stringResource(R.string.screens_card_running))
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(text, color = color, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 6.dp))
    }
}

/** The values that matter for the device's kind, large. */
@Composable
private fun CardValues(device: DeviceConfig, r: DeviceReading) {
    val big = MaterialTheme.typography.titleLarge
    val c = LocalSeriesColors.current
    when (device.type.kind) {
        DeviceKind.INVERTER -> {
            r.pvPowerW?.let { Text(formatPower(it), style = big) }
            r.today.pvKwh?.let { Text(formatEnergy(it), style = MaterialTheme.typography.titleMedium) }
            r.battery?.socPercent?.let { SocBar(it, c.battery) }
            if (r.pvPowerW == null) r.gridPowerW?.let { Text(formatPower(it), style = big) }
        }
        DeviceKind.BATTERY -> {
            val b = r.battery
            b?.socPercent?.let { SocBar(it, c.battery) }
            b?.let {
                Text(formatPower(kotlin.math.abs(it.powerW)), style = big)
                if (it.powerW != 0) Text(batteryDirection(it.powerW), style = MaterialTheme.typography.bodyMedium)
            }
        }
        DeviceKind.METER -> {
            val w = when (device.meterRole) {
                MeterRole.GRID -> r.gridPowerW
                MeterRole.PV -> r.pvPowerW ?: r.gridPowerW?.let { -it }
                MeterRole.CONSUMER -> r.loadPowerW ?: r.gridPowerW?.let { -it }
            }
            w?.let {
                Text(formatPower(kotlin.math.abs(it)), style = big)
                if (device.meterRole == MeterRole.GRID) Text(gridDirectionLabel(it), style = MaterialTheme.typography.bodyMedium)
            }
        }
        DeviceKind.CONSUMER -> r.loadPowerW?.let { Text(formatPower(it), style = big) }
    }
}

/** State of charge as "87 %" over a bar of ten segments, like a battery's own display. */
@Composable
private fun SocBar(percent: Int, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$percent %", style = MaterialTheme.typography.titleLarge)
        val lit = ((percent + 5) / 10).coerceIn(0, 10)
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.semantics { contentDescription = "$percent %" },
        ) {
            repeat(10) { i ->
                Box(
                    Modifier.width(9.dp).height(5.dp).clip(RoundedCornerShape(1.dp))
                        .background(if (i < lit) color else MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
    }
}

/** A drawing of what the device is: from its ModbusSpec kind where it has one, else from its type. */
fun kindIcon(device: DeviceConfig): ImageVector {
    val specKind = if (device.type in SpecDevices.GENERIC) SpecLibrary.current?.entry(device.specId)?.kind else null
    return when (specKind ?: namedKind(device.type)) {
        "charger" -> Icons.Outlined.EvStation
        "heat_pump" -> Icons.Outlined.HeatPump
        "heating_rod", "boiler" -> Icons.Outlined.WaterDrop
        "burner" -> Icons.Outlined.LocalFireDepartment
        "ups" -> Icons.Outlined.Power
        "battery" -> Icons.Outlined.Battery5Bar
        "meter" -> Icons.Outlined.ElectricMeter
        else -> when (device.type.kind) {
            DeviceKind.BATTERY -> Icons.Outlined.Battery5Bar
            DeviceKind.METER -> Icons.Outlined.ElectricMeter
            DeviceKind.CONSUMER -> Icons.Outlined.Power
            DeviceKind.INVERTER -> Icons.Outlined.SolarPower
        }
    }
}

/** The kind of the named consumer types, which have no ModbusSpec kind of their own on the device. */
private fun namedKind(type: DeviceType): String? = when (type) {
    DeviceType.KEBA_P30, DeviceType.ALFEN_EVE, DeviceType.MENNEKES_AMTRON, DeviceType.GO_E -> "charger"
    DeviceType.STIEBEL_ELTRON_ISG, DeviceType.NIBE_S, DeviceType.HOVAL_TOPTRONIC, DeviceType.DEMO_HEAT_PUMP -> "heat_pump"
    DeviceType.MY_PV -> "heating_rod"
    else -> null
}
