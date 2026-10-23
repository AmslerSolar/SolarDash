// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.ui

import ch.amslersolar.dashboard.ui.status.FaultLogActivity
import ch.amslersolar.dashboard.ui.status.rememberPlantStatus
import ch.amslersolar.dashboard.ui.status.StatusCard
import ch.amslersolar.dashboard.ui.status.GuideActivity
import ch.amslersolar.dashboard.model.Guide
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Checkbox
import androidx.compose.material.icons.filled.MoreVert
import ch.amslersolar.dashboard.i18n.DetailTexts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.DevicesOther
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ch.amslersolar.dashboard.R
import ch.amslersolar.dashboard.model.DeviceConfig
import ch.amslersolar.dashboard.model.DeviceKind
import ch.amslersolar.dashboard.model.DeviceReading
import ch.amslersolar.dashboard.model.DeviceStatus
import ch.amslersolar.dashboard.model.GridSource
import ch.amslersolar.dashboard.model.Plant
import ch.amslersolar.dashboard.model.PlantSnapshot
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

enum class PlantTab(@StringRes val label: Int, val icon: ImageVector) {
    DASHBOARD(R.string.screens_tab_dashboard, Icons.Outlined.Dashboard),
    HISTORY(R.string.screens_tab_history, Icons.Outlined.History),
    DEVICES(R.string.screens_tab_devices, Icons.Outlined.DevicesOther),
}

/** A plant's main screen: top bar with its name, bottom bar with the three tabs, and the tab's [content]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlantScreen(
    plant: Plant,
    tab: PlantTab,
    onTab: (PlantTab) -> Unit,
    onBack: () -> Unit,
    onAddDevice: () -> Unit,
    onRename: (String) -> Unit,
    onKiosk: () -> Unit,
    onOffGrid: (Boolean) -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    var renaming by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    if (renaming) RenameDialog(stringResource(R.string.screens_rename_plant), plant.name, onRename = { onRename(it); renaming = false }, onDismiss = { renaming = false })
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(plant.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.screens_back)) }
                },
                actions = {
                    if (tab == PlantTab.DASHBOARD) {
                        IconButton(onClick = onKiosk) { Icon(Icons.Outlined.Fullscreen, contentDescription = stringResource(R.string.screens_kiosk_mode)) }
                    }
                    IconButton(onClick = { renaming = true }) { Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.screens_rename_plant)) }
                    if (tab == PlantTab.DEVICES) {
                        IconButton(onClick = onAddDevice) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.screens_add_device)) }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = null) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.log_title)) },
                                onClick = { menu = false; FaultLogActivity.open(context, plant.id) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.status_off_grid)) },
                                leadingIcon = { Checkbox(checked = plant.offGrid, onCheckedChange = null) },
                                onClick = { menu = false; onOffGrid(!plant.offGrid) },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                PlantTab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { onTab(t) },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(stringResource(t.label)) },
                    )
                }
            }
        },
    ) { padding ->
        content(Modifier.padding(padding))
    }
}

@Composable
fun DashboardTab(
    plant: Plant,
    state: PlantState,
    onAddDevice: () -> Unit,
    onShowDevices: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        PlantState.Loading -> Box(modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        PlantState.NoDevices -> NoDevices(onAddDevice, modifier)
        is PlantState.Live -> BoxWithConstraints(modifier) {
            val status = rememberPlantStatus(plant, state.snapshot)
            val context = LocalContext.current
            val onGuide: (Guide) -> Unit = { GuideActivity.open(context, it) }
            // Room left for the diagram after the status line and padding.
            val flowHeight = maxHeight - 112.dp
            if (maxWidth >= WIDE_LAYOUT && maxWidth > maxHeight) {
                // Scrolls only when the screen is shorter than the smallest diagram (phones in landscape).
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatusLine(state.snapshot, onShowDevices)
                    StatusCard(status, onGuide, onLog = { FaultLogActivity.open(context, plant.id) })
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PlantEnergyFlow(state.snapshot, flowHeight, Modifier.weight(3f))
                        Column(Modifier.weight(2f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            TodayCard(state.snapshot)
                            ConsumersCard(state.snapshot)
                        }
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { StatusLine(state.snapshot, onShowDevices) }
                    item { StatusCard(status, onGuide, onLog = { FaultLogActivity.open(context, plant.id) }) }
                    item { PlantEnergyFlow(state.snapshot, flowHeight) }
                    item { TodayCard(state.snapshot) }
                    if (state.snapshot.consumers.isNotEmpty()) item { ConsumersCard(state.snapshot) }
                }
            }
        }
    }
}

@Composable
fun DevicesTab(
    plant: Plant,
    state: PlantState,
    onAddDevice: () -> Unit,
    onOpenDevice: (DeviceConfig) -> Unit,
    onRemoveDevice: (DeviceConfig, deleteData: Boolean) -> Unit,
    onEditDevice: (DeviceConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<DeviceConfig?>(null) }

    if (plant.devices.isEmpty()) {
        NoDevices(onAddDevice, modifier)
    } else {
        val statusById = (state as? PlantState.Live)?.snapshot?.devices?.associateBy { it.device.id }.orEmpty()
        LazyColumn(
            modifier = modifier,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DeviceKind.entries.forEach { kind ->
                val devices = plant.devices.filter { it.type.kind == kind }
                if (devices.isNotEmpty()) {
                    item(key = kind.name) {
                        Text(deviceKindName(kind), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    }
                    items(devices, key = { it.id }) { device ->
                        DeviceRow(
                            device, statusById[device.id], onClick = { onOpenDevice(device) },
                            onEdit = { onEditDevice(device) }, onRemove = { pendingDelete = device },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { device ->
        ConfirmRemoveDialog(
            name = device.name,
            detail = if (device.serial.isNotBlank()) {
                stringResource(R.string.screens_remove_keeps_data, device.serial)
            } else {
                stringResource(R.string.screens_remove_deletes_data)
            },
            deleteDataOption = if (device.serial.isNotBlank()) stringResource(R.string.screens_remove_delete_data) else null,
            onConfirm = { deleteData -> onRemoveDevice(device, deleteData); pendingDelete = null },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun NoDevices(onAddDevice: () -> Unit, modifier: Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.screens_no_devices))
        Button(onClick = onAddDevice) { Text(stringResource(R.string.screens_add_device)) }
    }
}

@Composable
private fun StatusLine(snapshot: PlantSnapshot, onShowDevices: () -> Unit) {
    Column {
        Text(stringResource(R.string.screens_updated, TIME.format(snapshot.readAt)), style = MaterialTheme.typography.bodySmall)
        val failed = snapshot.failedDevices.size
        if (failed > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    pluralStringResource(R.plurals.screens_not_responding_incomplete, failed, failed, snapshot.devices.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onShowDevices) { Text(stringResource(R.string.screens_show)) }
            }
        }
    }
}

/** From this width, on landscape screens, the diagram and the Today card sit side by side. */
private val WIDE_LAYOUT = 720.dp

@Composable
private fun PlantEnergyFlow(s: PlantSnapshot, maxHeight: Dp, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) {
        EnergyFlowDiagram(
            pvW = s.pvPowerW,
            loadW = s.loadPowerW,
            battery = s.battery,
            gridW = s.gridPowerW,
            modifier = Modifier.padding(vertical = 16.dp),
            maxHeight = maxHeight - 32.dp,
        )
    }
}

/** Consumers inside the home (heat pump, wallbox, heating rod, sub-metered loads); part of the home load. */
@Composable
private fun ConsumersCard(s: PlantSnapshot) {
    if (s.consumers.isEmpty()) return
    InfoCard(stringResource(R.string.screens_in_the_home)) {
        s.consumers.forEach { c ->
            val power = c.powerW?.let(::formatPower) ?: "–"
            InfoRow(c.device.name, listOfNotNull(power, c.status?.takeIf { it.isNotBlank() }?.let(DetailTexts::tr)).joinToString(" · "))
        }
        val known = s.consumers.mapNotNull { it.powerW }
        val home = s.loadPowerW
        if (home != null && known.isNotEmpty()) InfoRow(stringResource(R.string.screens_rest_of_home), formatPower(maxOf(0, home - known.sum())))
    }
}

@Composable
private fun TodayCard(s: PlantSnapshot) {
    InfoCard(stringResource(R.string.screens_today)) {
        InfoRow(stringResource(R.string.screens_solar_yield), formatEnergy(s.today.pvKwh))
        InfoRow(stringResource(R.string.screens_consumption), formatEnergy(s.today.loadKwh))
        InfoRow(stringResource(R.string.screens_exported), formatEnergy(s.today.exportKwh))
        InfoRow(stringResource(R.string.screens_imported), formatEnergy(s.today.importKwh))
    }
}

@Composable
private fun DeviceRow(device: DeviceConfig, status: DeviceStatus?, onClick: () -> Unit, onEdit: () -> Unit, onRemove: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleMedium)
                Text(deviceAddress(device), style = MaterialTheme.typography.bodySmall)
                when {
                    status == null -> Text(stringResource(R.string.screens_waiting_for_data), style = MaterialTheme.typography.bodyMedium)
                    status.error != null -> Text(status.error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    status.reading != null -> Text(summary(status.reading), style = MaterialTheme.typography.bodyMedium)
                }
            }
            IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.screens_edit_device, device.name)) }
            IconButton(onClick = onRemove) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.screens_remove_device, device.name)) }
        }
    }
}

@Composable
fun deviceAddress(device: DeviceConfig): String = when {
    !device.type.needsHost -> device.type.displayName
    device.type.modbus ->
        "${device.type.displayName} · ${device.host}:${device.port} · ${stringResource(R.string.screens_unit_id, device.unitId)}"
    else -> "${device.type.displayName} · ${device.host}:${device.port}"
}

@Composable
private fun summary(r: DeviceReading): String = listOfNotNull(
    r.pvPowerW?.let { stringResource(R.string.screens_summary_pv, formatPower(it)) },
    r.battery?.let { b ->
        val power = formatPower(b.powerW)
        b.socPercent?.let { stringResource(R.string.screens_summary_battery_soc, power, it) }
            ?: stringResource(R.string.screens_summary_battery, power)
    },
    r.gridPowerW?.let { stringResource(R.string.screens_summary_grid, formatPower(it)) },
).joinToString(" · ").ifEmpty { r.status?.let(DetailTexts::tr) ?: stringResource(R.string.screens_ok) }

@Composable
fun batteryDirection(powerW: Int): String = stringResource(
    when {
        powerW > 0 -> R.string.screens_discharging
        powerW < 0 -> R.string.screens_charging
        else -> R.string.screens_idle
    },
)

/** Localized "Exporting" / "Importing" / "Idle" for a grid power (+ export, − import). */
@Composable
fun gridDirectionLabel(gridW: Int): String = stringResource(
    when {
        gridW > 0 -> R.string.screens_exporting
        gridW < 0 -> R.string.screens_importing
        else -> R.string.screens_idle
    },
)
