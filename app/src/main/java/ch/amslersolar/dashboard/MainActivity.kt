// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard

import ch.amslersolar.dashboard.driver.TrafficPause
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ch.amslersolar.dashboard.data.HistoryRepository
import ch.amslersolar.dashboard.discovery.DeviceScanner
import ch.amslersolar.dashboard.kiosk.Kiosk
import ch.amslersolar.dashboard.data.PlantRepository
import ch.amslersolar.dashboard.data.RegisterSweeps
import ch.amslersolar.dashboard.ui.AddDeviceScreen
import ch.amslersolar.dashboard.ui.DeviceScreen
import ch.amslersolar.dashboard.ui.DashboardTab
import ch.amslersolar.dashboard.ui.DevicesTab
import ch.amslersolar.dashboard.ui.LivePlants
import ch.amslersolar.dashboard.ui.PlantScreen
import ch.amslersolar.dashboard.ui.PlantTab
import ch.amslersolar.dashboard.ui.PlantState
import ch.amslersolar.dashboard.ui.PlantViewModel
import ch.amslersolar.dashboard.ui.PlantsScreen
import ch.amslersolar.dashboard.ui.PlantsViewModel
import ch.amslersolar.dashboard.ui.history.HistoryTab
import ch.amslersolar.dashboard.ui.history.HistoryViewModel
import ch.amslersolar.dashboard.ui.theme.SolarTheme

/**
 * Routes: "plants"; a plant's tabs "plant/<id>" (dashboard), "plant/<id>/history", "plant/<id>/devices";
 * and below the devices tab "plant/<id>/add", "plant/<id>/device/<deviceId>".
 */
private const val ROUTE_PLANTS = "plants"

private fun tabRoute(plantId: String, tab: PlantTab) = when (tab) {
    PlantTab.DASHBOARD -> "plant/$plantId"
    PlantTab.HISTORY -> "plant/$plantId/history"
    PlantTab.DEVICES -> "plant/$plantId/devices"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as SolarApp
        val repository = app.repository
        val history = app.history
        // A wall-mounted tablet goes back into kiosk mode when the app is started again (reboot, crash, update).
        if (savedInstanceState == null) Kiosk.plantId(this)?.let { Kiosk.start(this, it) }

        setContent {
            SolarTheme {
                val plantsVm: PlantsViewModel = viewModel(factory = viewModelFactory { initializer { PlantsViewModel(repository, app.registry) } })
                val plants by plantsVm.plants.collectAsStateWithLifecycle()
                var route by rememberSaveable { mutableStateOf(ROUTE_PLANTS) }

                // On startup only: with a single plant, open it straight away. Back still leads to the list.
                var startupHandled by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(plants) {
                    val loaded = plants ?: return@LaunchedEffect
                    if (startupHandled) return@LaunchedEffect
                    startupHandled = true
                    if (loaded.size == 1 && route == ROUTE_PLANTS) route = tabRoute(loaded.single().id, PlantTab.DASHBOARD)
                }

                val parts = route.split("/")
                val plantId = parts.getOrNull(1)
                // Back: device screens → Devices tab → Dashboard tab → plant list.
                val parent = when {
                    plantId == null -> null
                    parts.getOrNull(2) in listOf("add", "edit", "device") -> tabRoute(plantId, PlantTab.DEVICES)
                    parts.size > 2 -> tabRoute(plantId, PlantTab.DASHBOARD)
                    else -> ROUTE_PLANTS
                }
                BackHandler(enabled = parent != null) { route = parent ?: ROUTE_PLANTS }

                val plant = plants?.find { it.id == plantId }
                if (plantId == null) {
                    PlantsScreen(
                        plants = plants,
                        onOpen = { route = tabRoute(it.id, PlantTab.DASHBOARD) },
                        onAdd = plantsVm::addPlant,
                        onDelete = { p, deleteData -> plantsVm.removePlant(p.id, deleteData) },
                        onRename = { p, name -> plantsVm.renamePlant(p.id, name) },
                    )
                } else if (plant == null) {
                    // Removed, or still loading after process death.
                    if (plants != null) LaunchedEffect(Unit) { route = ROUTE_PLANTS }
                } else {
                    val state by plantViewModel(plant.id, app.live).state.collectAsStateWithLifecycle()
                    val back = { route = parent ?: ROUTE_PLANTS }
                    val addDevice = { route = "plant/${plant.id}/add" }
                    when (val section = parts.getOrNull(2)) {
                        "add", "edit" -> {
                            val editing = if (section == "edit") plant.devices.find { it.id == parts.getOrNull(3) } else null
                            if (section == "edit" && editing == null) {
                                LaunchedEffect(Unit) { back() }
                            } else {
                                AddDeviceScreen(
                                    plantName = plant.name,
                                    onTest = plantsVm::testConnection,
                                    onScan = { extended, range ->
                                        // Configured devices are listed, not probed: a second client could disturb their poll.
                                        val known = plants.orEmpty().flatMap { p ->
                                            p.devices.filter { it.type.needsHost && it.id != editing?.id }
                                                .map { "${it.host}:${it.port}" to "${p.name} · ${it.name}" }
                                        }.toMap()
                                        DeviceScanner.scanLocalNetwork(applicationContext, known, extended, range)
                                    },
                                    onSave = {
                                        if (editing != null) plantsVm.updateDevice(plant.id, it) else plantsVm.addDevice(plant.id, it)
                                        back()
                                    },
                                    onSweep = { device, sweeps, progress, onSaved ->
                                        // All other device traffic waits meanwhile: the sweep goes faster, single-client gateways are free.
                                        TrafficPause.during { RegisterSweeps.run(applicationContext, device, sweeps, progress) { onSaved(it.path) } }
                                    },
                                    onBack = back,
                                    existing = editing,
                                )
                            }
                        }
                        "device" -> {
                            val device = plant.devices.find { it.id == parts.getOrNull(3) }
                            if (device == null) {
                                LaunchedEffect(Unit) { back() }
                            } else {
                                val status = (state as? PlantState.Live)?.snapshot?.devices?.find { it.device.id == device.id }
                                DeviceScreen(device, status, onBack = back)
                            }
                        }
                        else -> {
                            val tab = when (section) {
                                "history" -> PlantTab.HISTORY
                                "devices" -> PlantTab.DEVICES
                                else -> PlantTab.DASHBOARD
                            }
                            PlantScreen(
                                plant = plant,
                                tab = tab,
                                onTab = { route = tabRoute(plant.id, it) },
                                onBack = back,
                                onAddDevice = addDevice,
                                onRename = { plantsVm.renamePlant(plant.id, it) },
                                onKiosk = { Kiosk.start(this@MainActivity, plant.id) },
                                onOffGrid = { plantsVm.setOffGrid(plant.id, it) },
                            ) { modifier ->
                                when (tab) {
                                    PlantTab.DASHBOARD -> DashboardTab(
                                        plant = plant,
                                        state = state,
                                        onAddDevice = addDevice,
                                        onShowDevices = { route = tabRoute(plant.id, PlantTab.DEVICES) },
                                        modifier = modifier,
                                    )
                                    PlantTab.HISTORY -> {
                                        val vm: HistoryViewModel = viewModel(
                                            key = "history-${plant.id}",
                                            factory = viewModelFactory { initializer { HistoryViewModel(plant.id, repository, history) } },
                                        )
                                        val historyState by vm.state.collectAsStateWithLifecycle()
                                        val generating by vm.generating.collectAsStateWithLifecycle()
                                        val shownDevice by vm.selectedDevice.collectAsStateWithLifecycle()
                                        HistoryTab(
                                            hasDemoDevices = plant.devices.any { !it.type.needsHost },
                                            state = historyState,
                                            generating = generating,
                                            onPeriod = vm::setPeriod,
                                            onPrevious = vm::previous,
                                            onNext = vm::next,
                                            onFillDemo = vm::fillDemoHistory,
                                            devices = plant.devices,
                                            catalog = vm::catalog,
                                            onAddChannels = { selection, onRefused -> vm.addChannels(selection, onRefused) },
                                            onRemoveChannel = { deviceId, key -> vm.removeChannel(deviceId, key) },
                                            selectedDevice = shownDevice,
                                            onDevice = vm::setDevice,
                                            modifier = modifier,
                                        )
                                    }
                                    PlantTab.DEVICES -> DevicesTab(
                                        plant = plant,
                                        state = state,
                                        onAddDevice = addDevice,
                                        onOpenDevice = { route = "plant/${plant.id}/device/${it.id}" },
                                        onRemoveDevice = { d, deleteData -> plantsVm.removeDevice(plant.id, d, deleteData) },
                                        onEditDevice = { route = "plant/${plant.id}/edit/${it.id}" },
                                        modifier = modifier,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One view model per plant, shared by its tabs and device screens so polling isn't restarted when switching. */
@Composable
private fun plantViewModel(plantId: String, live: LivePlants): PlantViewModel =
    viewModel(key = plantId, factory = viewModelFactory { initializer { PlantViewModel(plantId, live) } })
