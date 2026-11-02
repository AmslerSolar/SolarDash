// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.data

import ch.amslersolar.solardash.model.DeviceConfig
import kotlinx.coroutines.flow.first

/**
 * Keeps a device's recorded data with the device itself, not with its place in a plant: data is stored under the
 * device id, and a device is recognised by type and serial number. Removing a device keeps its data; adding the
 * same device again (to any plant) gives it its old id back, so the data is there again.
 */
class DeviceRegistry(private val plants: PlantRepository, private val history: HistoryRepository) {
    /** Adds [device]; one with a known type and serial gets its stored id. Returns the device as added. */
    suspend fun add(plantId: String, device: DeviceConfig): DeviceConfig {
        val added = device.copy(id = storedId(device) ?: device.id)
        plants.addDevice(plantId, added)
        remember(added)
        return added
    }

    suspend fun update(plantId: String, device: DeviceConfig) {
        plants.updateDevice(plantId, device)
        claim(plantId, device)
    }

    /**
     * A configured device whose serial became known (learned while polling, or edited): if data of the same device
     * is stored under another id, what was recorded meanwhile is merged into it and the device takes that id.
     */
    suspend fun claim(plantId: String, device: DeviceConfig) {
        val stored = storedId(device)
        if (stored == null) {
            remember(device)
            return
        }
        history.mergeDevice(from = device.id, to = stored)
        plants.replaceDeviceId(plantId, device.id, stored)
        remember(device.copy(id = stored))
    }

    /** Devices configured before serials were kept: make them recognisable. */
    suspend fun rememberAll(devices: List<DeviceConfig>) = devices.forEach { remember(it) }

    /**
     * Removes a device from its plant. Its data is kept when it can be recognised again (serial known), unless
     * [deleteData]; without a serial it could never be matched again, so it's deleted.
     */
    suspend fun remove(plantId: String, device: DeviceConfig, deleteData: Boolean) {
        plants.removeDevice(plantId, device.id)
        if (deleteData || device.serial.isBlank()) history.deleteDevices(listOf(device.id)) else history.markRemoved(listOf(device.id))
    }

    /** Removes a plant and its plant-level history; its devices are removed as by [remove]. */
    suspend fun removePlant(plantId: String, deleteDeviceData: Boolean) {
        val devices = plants.plants.first().find { it.id == plantId }?.devices.orEmpty()
        plants.removePlant(plantId)
        history.deletePlant(plantId)
        val (keep, drop) = devices.partition { !deleteDeviceData && it.serial.isNotBlank() }
        history.markRemoved(keep.map { it.id })
        history.deleteDevices(drop.map { it.id })
    }

    private suspend fun remember(d: DeviceConfig) = history.rememberDevice(d.id, d.type.name, d.serial, d.name)

    /** Stored id of the same device (type + serial) that no configured device uses. */
    private suspend fun storedId(device: DeviceConfig): String? {
        if (device.serial.isBlank()) return null
        val configured = plants.plants.first().flatMap { p -> p.devices.map { it.id } }.toSet()
        return history.storedDeviceId(device.type.name, device.serial, excluding = configured + device.id)
    }
}
