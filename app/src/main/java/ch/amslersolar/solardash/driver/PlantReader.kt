// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.driver

import ch.amslersolar.solardash.driver.modbus.ModbusConnections
import ch.amslersolar.solardash.model.DeviceConfig
import ch.amslersolar.solardash.model.ConnectionError
import ch.amslersolar.solardash.model.DeviceStatus
import ch.amslersolar.solardash.model.PlantAggregator
import ch.amslersolar.solardash.model.PlantSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable

/**
 * Reads all devices of a plant in parallel. Holds their connections open until [close]. A single failed read of a
 * device shows its last reading; see [ReadSmoothing].
 */
class PlantReader(devices: List<DeviceConfig>, private val smoothing: ReadSmoothing = ReadSmoothing()) : Closeable {
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
            drivers.map { (device, driver) -> async { smoothing.apply(readDevice(device, driver)) } }.awaitAll()
        }
        return PlantAggregator.aggregate(statuses)
    }

    private suspend fun readDevice(device: DeviceConfig, driver: Result<DeviceDriver>): DeviceStatus {
        val d = driver.getOrElse { return DeviceStatus(device, null, it.message ?: "Unsupported device", ConnectionError.UNSUPPORTED) }
        return try {
            DeviceStatus(device, d.read(), null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DeviceStatus(device, null, e.message ?: e::class.simpleName.orEmpty(), classifyConnectionError(e))
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

/**
 * While a register sweep runs, all other device traffic waits: live polling of every plant (also the kiosk's)
 * closes its connections and pauses, background recording and the search for moved devices don't start. The
 * sweep then has the network, and single-client gateways, to itself.
 */
object TrafficPause {
    private val holders = MutableStateFlow(0)

    val isPaused: Boolean get() = holders.value > 0

    /** Runs [block] with the other traffic paused; it resumes afterwards, also when [block] fails or is stopped. */
    suspend fun <T> during(block: suspend () -> T): T {
        holders.update { it + 1 }
        try {
            return block()
        } finally {
            holders.update { it - 1 }
        }
    }

    /** Suspends while paused. */
    suspend fun awaitResumed() {
        holders.first { it == 0 }
    }

    /** Waits up to [ms], less if a pause begins meanwhile; true when it did. */
    suspend fun pauseWithin(ms: Long): Boolean = withTimeoutOrNull(ms) { holders.first { it > 0 } } != null
}
