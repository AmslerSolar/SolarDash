// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import ch.amslersolar.solardash.model.ChartChannel
import ch.amslersolar.solardash.model.DeviceConfig
import ch.amslersolar.solardash.model.Plant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "plants")

class PlantRepository(private val context: Context) {
    private val key = stringPreferencesKey("plants_json")
    /** A copy of saved plants that couldn't be read, kept before anything new is saved. */
    private val unreadableKey = stringPreferencesKey("plants_json_unreadable")

    val plants: Flow<List<Plant>> = context.dataStore.data.map { prefs -> decode(prefs[key]) }

    fun plant(id: String): Flow<Plant?> = plants.map { list -> list.find { it.id == id } }.distinctUntilChanged()

    suspend fun addPlant(plant: Plant) = update { it + plant }

    suspend fun removePlant(id: String) = update { list -> list.filterNot { it.id == id } }

    suspend fun addDevice(plantId: String, device: DeviceConfig) = updatePlant(plantId) { it.copy(devices = it.devices + device) }

    suspend fun removeDevice(plantId: String, deviceId: String) = updatePlant(plantId) { plant ->
        plant.copy(
            devices = plant.devices.filterNot { it.id == deviceId },
            chartChannels = plant.chartChannels.filterNot { it.deviceId == deviceId },
        )
    }

    suspend fun renamePlant(id: String, name: String) = updatePlant(id) { it.copy(name = name) }

    suspend fun setOffGrid(id: String, offGrid: Boolean) = updatePlant(id) { it.copy(offGrid = offGrid) }

    /** Replaces a device's settings, keeping its id (and so its history and chart channels). */
    suspend fun updateDevice(plantId: String, device: DeviceConfig) = updatePlant(plantId) { plant ->
        plant.copy(devices = plant.devices.map { if (it.id == device.id) device else it })
    }

    /** Stops showing a problem [text] of a device in the plant status ([DeviceConfig.hiddenProblems]). */
    suspend fun hideProblem(plantId: String, deviceId: String, text: String) = updatePlant(plantId) { plant ->
        plant.copy(devices = plant.devices.map { if (it.id == deviceId && text !in it.hiddenProblems) it.copy(hiddenProblems = it.hiddenProblems + text) else it })
    }

    /** Changes one device's address if it's still at [from] (it may have been edited meanwhile). */
    suspend fun moveDevice(plantId: String, deviceId: String, from: String, to: String) = updatePlant(plantId) { plant ->
        plant.copy(devices = plant.devices.map { if (it.id == deviceId && it.host == from) it.copy(host = to) else it })
    }

    /** Gives a device another id (its stored one), in the device list and the chart channels. */
    suspend fun replaceDeviceId(plantId: String, from: String, to: String) = updatePlant(plantId) { plant ->
        plant.copy(
            devices = plant.devices.map { if (it.id == from) it.copy(id = to) else it },
            chartChannels = plant.chartChannels.map { if (it.deviceId == from) it.copy(deviceId = to) else it },
        )
    }

    suspend fun setDeviceSerial(plantId: String, deviceId: String, serial: String) = updatePlant(plantId) { plant ->
        plant.copy(devices = plant.devices.map { if (it.id == deviceId && it.serial.isBlank()) it.copy(serial = serial) else it })
    }

    suspend fun setChartChannels(plantId: String, channels: List<ChartChannel>) =
        updatePlant(plantId) { it.copy(chartChannels = channels) }

    /** Shares the plant with observers, or stops it (null). */
    suspend fun setSharing(plantId: String, sharing: Plant.Sharing?) = updatePlant(plantId) { it.copy(sharing = sharing) }

    /**
     * An observed plant as its plant item describes it now: name, devices, chart channels; the observer's own
     * settings ([Plant.observed]) and id stay.
     */
    suspend fun updateObserved(plantId: String, from: Plant) = updatePlant(plantId) { plant ->
        if (plant.observed == null) plant
        else plant.copy(name = from.name, offGrid = from.offGrid, devices = from.devices, chartChannels = from.chartChannels)
    }

    suspend fun setObserved(plantId: String, observed: Plant.Observed) = updatePlant(plantId) { it.copy(observed = observed) }

    private suspend fun updatePlant(id: String, transform: (Plant) -> Plant) =
        update { list -> list.map { if (it.id == id) transform(it) else it } }

    private suspend fun update(transform: (List<Plant>) -> List<Plant>) {
        context.dataStore.edit { prefs ->
            val current = try { PlantStore.decode(prefs[key]) } catch (e: PlantStore.Unreadable) {
                if (prefs[unreadableKey] == null) prefs[unreadableKey] = prefs[key].orEmpty()
                emptyList()
            }
            prefs[key] = PlantStore.encode(transform(current))
        }
    }

    private fun decode(raw: String?): List<Plant> = try { PlantStore.decode(raw) } catch (e: PlantStore.Unreadable) { emptyList() }
}
