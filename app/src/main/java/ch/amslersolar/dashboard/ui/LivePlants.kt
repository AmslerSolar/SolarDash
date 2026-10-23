// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.ui

import ch.amslersolar.dashboard.model.NetworkState
import ch.amslersolar.dashboard.model.FaultLog
import ch.amslersolar.dashboard.data.DeviceRegistry
import ch.amslersolar.dashboard.data.HistoryRepository
import ch.amslersolar.dashboard.data.PlantRepository
import ch.amslersolar.dashboard.driver.ActivePolling
import ch.amslersolar.dashboard.driver.PlantReader
import ch.amslersolar.dashboard.model.DeviceConfig
import ch.amslersolar.dashboard.model.EnergyTotals
import ch.amslersolar.dashboard.model.PlantSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import ch.amslersolar.dashboard.discovery.DeviceScanner
import ch.amslersolar.dashboard.discovery.MdnsService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

sealed interface PlantState {
    data object Loading : PlantState
    data object NoDevices : PlantState
    data class Live(val snapshot: PlantSnapshot) : PlantState
}

/**
 * One live poll per plant, shared by everything that shows it (plant screen, kiosk screen, kiosk service),
 * so a device that accepts a single Modbus TCP client is never connected twice. Polls every device in
 * parallel while anyone collects, stops (and disconnects) 5 s after the last one leaves, and records one
 * sample per minute into the history. Adding or removing a device restarts polling with the new list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LivePlants(
    private val repository: PlantRepository,
    private val history: HistoryRepository,
    private val scope: CoroutineScope,
    private val registry: DeviceRegistry = DeviceRegistry(repository, history),
    /** Addresses to search for a device that moved (the phone's subnet), or null when not on a local network. */
    private val localHosts: () -> List<String>? = { null },
    /** mDNS announcements on the local network, listened to for a few seconds. */
    private val announced: suspend () -> List<MdnsService> = { emptyList() },
    private val scanner: DeviceScanner = DeviceScanner(),
    /** Records when problems start and end (null in tests that don't need it). */
    private val faultLog: FaultLog? = null,
    /** The tablet's network, for the fault log's "no Wi-Fi" / "wrong Wi-Fi". */
    private val network: () -> NetworkState = { NetworkState.UNKNOWN },
) {
    private val plants = ConcurrentHashMap<String, StateFlow<PlantState>>()
    /** When each device last answered, and when it was last searched for; by device id. */
    private val lastAnswer = ConcurrentHashMap<String, Long>()
    private val lastSearch = ConcurrentHashMap<String, Long>()

    fun state(plantId: String): StateFlow<PlantState> = plants.computeIfAbsent(plantId) { id ->
        repository.plant(id)
            .map { it?.devices }
            // Restart polling when a device is added or removed or its connection changes; a rename keeps it running.
            .distinctUntilChanged { a, b -> a?.map(::connection) == b?.map(::connection) }
            .flatMapLatest { devices ->
                when {
                    devices == null -> flowOf(PlantState.Loading)
                    devices.isEmpty() -> flowOf(PlantState.NoDevices)
                    else -> poll(id, devices)
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), PlantState.Loading)
    }

    private fun poll(plantId: String, devices: List<DeviceConfig>): Flow<PlantState> = flow {
        ActivePolling.start(plantId)
        try {
            PlantReader(devices).use { reader ->
                var lastRecorded = 0L
                var recordedToday: EnergyTotals? = null
                val started = System.currentTimeMillis()
                registry.rememberAll(devices)
                while (true) {
                    val snapshot = reader.read()
                    reader.learnSerials().forEach { (deviceId, serial) ->
                        repository.setDeviceSerial(plantId, deviceId, serial)
                        // Re-added before its serial was known: back to its stored id and data (restarts polling).
                        devices.find { it.id == deviceId }?.let { registry.claim(plantId, it.copy(serial = serial)) }
                    }
                    lookForMovedDevices(plantId, snapshot, started)
                    if (snapshot.readAt.epochSecond - lastRecorded >= RECORD_INTERVAL_S) {
                        lastRecorded = snapshot.readAt.epochSecond
                        record(plantId, snapshot)
                        recordedToday = recordedToday(plantId)
                    }
                    logFaults(plantId, snapshot)
                    emit(PlantState.Live(snapshot.copy(today = withRecorded(snapshot.today, recordedToday))))
                    delay(POLL_INTERVAL_MS)
                }
            }
        } finally {
            ActivePolling.stop(plantId)
        }
    }

    /**
     * A Modbus device that hasn't answered for [MOVED_AFTER_MS] is searched for by its serial number on its port, at
     * most every [SEARCH_EVERY_MS]; when found at another address, its host is updated, which restarts polling.
     * Other configured devices are never contacted, so their single-client connections aren't disturbed.
     */
    private fun lookForMovedDevices(plantId: String, snapshot: PlantSnapshot, started: Long) {
        val now = System.currentTimeMillis()
        for (status in snapshot.devices) {
            val d = status.device
            if (status.error == null) {
                lastAnswer[d.id] = now
                continue
            }
            if (!d.type.modbus || !d.type.needsHost || d.serial.isBlank()) continue
            if (now - (lastAnswer[d.id] ?: started) < MOVED_AFTER_MS) continue
            if (now - (lastSearch[d.id] ?: 0L) < SEARCH_EVERY_MS) continue
            lastSearch[d.id] = now
            scope.launch {
                try {
                    val hosts = localHosts() ?: return@launch
                    val configured = repository.plants.first().flatMap { p -> p.devices.map { "${it.host}:${it.port}" } }.toSet()
                    val found = scanner.find(hosts, configured, d.type, d.port, d.unitId, d.serial, announced()) ?: return@launch
                    repository.moveDevice(plantId, d.id, from = d.host, to = found)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Try again after SEARCH_EVERY_MS.
                }
            }
        }
    }

    /** Today's totals from the history, for values no device counts itself (e.g. grid export on GoodWe and LG). */
    private suspend fun recordedToday(plantId: String): EnergyTotals? = try {
        history.days(plantId, LocalDate.now(), LocalDate.now()).firstOrNull()?.energy
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun withRecorded(devices: EnergyTotals, recorded: EnergyTotals?): EnergyTotals = if (recorded == null) devices else EnergyTotals(
        pvKwh = devices.pvKwh ?: recorded.pvKwh,
        exportKwh = devices.exportKwh ?: recorded.exportKwh,
        importKwh = devices.importKwh ?: recorded.importKwh,
        loadKwh = devices.loadKwh ?: recorded.loadKwh,
    )

    private suspend fun logFaults(plantId: String, snapshot: PlantSnapshot) {
        val log = faultLog ?: return
        try {
            val plant = repository.plant(plantId).first() ?: return
            log.update(plant, snapshot, network())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A storage problem shouldn't stop the live view.
        }
    }

    private suspend fun record(plantId: String, snapshot: PlantSnapshot) {
        try {
            history.record(plantId, snapshot)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A storage problem shouldn't stop the live view.
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 5_000L
        const val RECORD_INTERVAL_S = 60L
        const val MOVED_AFTER_MS = 2 * 60_000L
        const val SEARCH_EVERY_MS = 15 * 60_000L
    }
}

private fun connection(d: DeviceConfig) = listOf(d.id, d.type, d.host, d.port, d.unitId, d.password, d.invertGrid, d.meterRole)
