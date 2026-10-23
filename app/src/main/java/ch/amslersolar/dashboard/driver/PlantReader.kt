// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver

import ch.amslersolar.dashboard.driver.modbus.ModbusConnections
import ch.amslersolar.dashboard.model.DeviceConfig
import ch.amslersolar.dashboard.model.DeviceStatus
import ch.amslersolar.dashboard.model.PlantAggregator
import ch.amslersolar.dashboard.model.PlantSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.Closeable

/** Reads all devices of a plant in parallel. Holds their connections open until [close]. */
class PlantReader(devices: List<DeviceConfig>) : Closeable {
    private val connections = ModbusConnections()
    private val drivers = devices.map { device -> device to runCatching { DeviceDrivers.create(device, connections) } }
    private val serialAsked = mutableSetOf<String>()

    /**
     * Serial numbers of devices that had none stored, asked once over the running connection after their first
     * successful read (a second connection could be refused by single-client dongles). Device id → serial.
     */
    suspend fun learnSerials(): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for ((device, driver) in drivers) {
            if (device.serial.isNotBlank() || device.id in serialAsked) continue
            val d = driver.getOrNull() ?: continue
            serialAsked += device.id
            try {
                d.identify().serial.trim().takeIf { it.isNotEmpty() }?.let { out[device.id] = it }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                serialAsked -= device.id // try again with a later poll
            }
        }
        return out
    }

    suspend fun read(): PlantSnapshot {
        val statuses = coroutineScope {
            drivers.map { (device, driver) -> async { readDevice(device, driver) } }.awaitAll()
        }
        return PlantAggregator.aggregate(statuses)
    }

    private suspend fun readDevice(device: DeviceConfig, driver: Result<DeviceDriver>): DeviceStatus {
        val d = driver.getOrElse { return DeviceStatus(device, null, it.message ?: "Unsupported device") }
        return try {
            DeviceStatus(device, d.read(), null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DeviceStatus(device, null, e.message ?: e::class.simpleName.orEmpty())
        }
    }

    override fun close() {
        drivers.forEach { (_, driver) -> driver.getOrNull()?.let { runCatching { it.close() } } } // e.g. log out of HTTPS
        connections.close()
    }
}

/**
 * Plants the UI is polling right now. Background recording skips them, so it never opens a second
 * connection to a dongle that only accepts one.
 */
object ActivePolling {
    private val counts = mutableMapOf<String, Int>()

    @Synchronized
    fun start(plantId: String) {
        counts[plantId] = (counts[plantId] ?: 0) + 1
    }

    @Synchronized
    fun stop(plantId: String) {
        val n = (counts[plantId] ?: 1) - 1
        if (n <= 0) counts.remove(plantId) else counts[plantId] = n
    }

    @Synchronized
    fun isActive(plantId: String): Boolean = plantId in counts
}
