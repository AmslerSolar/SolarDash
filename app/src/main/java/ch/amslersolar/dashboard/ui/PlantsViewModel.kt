// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.amslersolar.dashboard.data.DeviceRegistry
import ch.amslersolar.dashboard.data.PlantRepository
import ch.amslersolar.dashboard.driver.DeviceDrivers
import ch.amslersolar.dashboard.driver.modbus.ModbusConnections
import ch.amslersolar.dashboard.model.ChartChannel
import ch.amslersolar.dashboard.model.DeviceConfig
import ch.amslersolar.dashboard.model.Plant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

class PlantsViewModel(
    private val repository: PlantRepository,
    private val registry: DeviceRegistry,
) : ViewModel() {
    /** Null until the first load from disk, so the UI can tell "loading" from "empty". */
    val plants: StateFlow<List<Plant>?> =
        repository.plants.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun addPlant(name: String) = viewModelScope.launch {
        repository.addPlant(Plant(id = UUID.randomUUID().toString(), name = name))
    }

    fun removePlant(id: String, deleteDeviceData: Boolean) = viewModelScope.launch { registry.removePlant(id, deleteDeviceData) }

    fun addDevice(plantId: String, device: DeviceConfig) = viewModelScope.launch { registry.add(plantId, device) }

    fun removeDevice(plantId: String, device: DeviceConfig, deleteData: Boolean) =
        viewModelScope.launch { registry.remove(plantId, device, deleteData) }

    fun setChartChannels(plantId: String, channels: List<ChartChannel>) =
        viewModelScope.launch { repository.setChartChannels(plantId, channels) }

    fun renamePlant(id: String, name: String) = viewModelScope.launch { repository.renamePlant(id, name) }

    fun updateDevice(plantId: String, device: DeviceConfig) = viewModelScope.launch { registry.update(plantId, device) }

    /** Connects once and identifies the device; the serial (when there is one) is kept with the device. */
    suspend fun testConnection(device: DeviceConfig): ConnectionTest = try {
        val info = ModbusConnections().use { DeviceDrivers.create(device, it).identify() }
        ConnectionTest.Connected(info.model, info.serial)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ConnectionTest.Failed(e.message ?: e::class.simpleName.orEmpty())
    }
}

/** Outcome of a connection test; the UI turns it into localized text. */
sealed interface ConnectionTest {
    /** The serial to keep with the device, when the device reported one. */
    val serial: String?

    data class Connected(val model: String, val reportedSerial: String) : ConnectionTest {
        override val serial: String? get() = reportedSerial.trim().takeIf { it.isNotEmpty() }
    }

    /** [error] is the driver's own (untranslated) error message. */
    data class Failed(val error: String) : ConnectionTest {
        override val serial: String? get() = null
    }
}
