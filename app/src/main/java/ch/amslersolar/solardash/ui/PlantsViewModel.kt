// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.amslersolar.solardash.data.DeviceRegistry
import ch.amslersolar.solardash.data.PlantRepository
import ch.amslersolar.solardash.driver.DeviceDrivers
import ch.amslersolar.solardash.driver.TrafficPause
import ch.amslersolar.solardash.driver.modbus.ModbusConnections
import ch.amslersolar.solardash.model.ChartChannel
import ch.amslersolar.solardash.model.DeviceConfig
import ch.amslersolar.solardash.model.DeviceInfo
import ch.amslersolar.solardash.model.Plant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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

    fun setOffGrid(id: String, offGrid: Boolean) = viewModelScope.launch { repository.setOffGrid(id, offGrid) }

    fun updateDevice(plantId: String, device: DeviceConfig) = viewModelScope.launch { registry.update(plantId, device) }

    fun hideProblem(plantId: String, deviceId: String, text: String) = viewModelScope.launch { repository.hideProblem(plantId, deviceId, text) }

    /**
     * Connects once and identifies the device; the serial (when there is one) is kept with the device. Modbus devices
     * are tested with polling paused: one that takes a single client (Huawei EMMA, dongles) drops a second connection
     * while it's polled, here or for another device behind it. Such a drop is tried again once polling has let go.
     */
    suspend fun testConnection(device: DeviceConfig): ConnectionTest = try {
        val info = if (!device.type.modbus) identify(device) else TrafficPause.during { identifyRetrying(device) }
        ConnectionTest.Connected(info.model, info.serial)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ConnectionTest.Failed(e.message ?: e::class.simpleName.orEmpty())
    }

    private suspend fun identify(device: DeviceConfig) = ModbusConnections().use { DeviceDrivers.create(device, it).identify() }

    private suspend fun identifyRetrying(device: DeviceConfig, attempt: Int = 1): DeviceInfo = try {
        identify(device)
    } catch (e: java.io.IOException) {
        if (attempt >= TEST_ATTEMPTS || !droppedConnection(e)) throw e
        delay(TEST_RETRY_MS)
        identifyRetrying(device, attempt + 1)
    }

    /** Accepted and then dropped, as a single-client device does with a second connection. */
    private fun droppedConnection(e: java.io.IOException) =
        e is java.io.EOFException || e is java.net.SocketException && e.message.orEmpty().contains("reset", ignoreCase = true)

    private companion object {
        const val TEST_ATTEMPTS = 3
        const val TEST_RETRY_MS = 1_000L
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
