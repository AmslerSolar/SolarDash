// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.model

import java.time.Instant
import kotlin.math.roundToInt

data class DeviceStatus(
    val device: DeviceConfig,
    val reading: DeviceReading?,
    val error: String?,
    /** Why the read failed, when it did. */
    val errorKind: ConnectionError? = null,
)

/** Kinds of failed device reads (see driver/ConnectionErrors.kt). */
enum class ConnectionError {
    /** No answer in time: device off, asleep, or not on this network. */
    TIMEOUT,
    /** The device or gateway turned the connection down: port closed, Modbus TCP off, or another client connected. */
    REFUSED,
    /** The connection dropped right away, typically because the device only serves one client at a time. */
    CLOSED,
    /** The address can't be reached from this network. */
    UNREACHABLE,
    /** Wrong password or access token. */
    LOGIN,
    /** The device answered, but with an error or unexpected data: wrong unit id, type or setting. */
    DEVICE_REPLY,
    /** The device is still starting up. */
    NOT_READY,
    /** The device type isn't supported. */
    UNSUPPORTED,
    OTHER,
}

enum class GridSource { METERS, INVERTERS }

data class PlantBattery(val powerW: Int, val socPercent: Int?)

/** A consumer inside the home (consumer device or consumer sub-meter): part of the home load, shown on its own. */
data class ConsumerLoad(val device: DeviceConfig, val powerW: Int?, val status: String?)

/** Plant-wide totals for one poll. Totals are null when no device delivered that value. */
data class PlantSnapshot(
    val readAt: Instant,
    val devices: List<DeviceStatus>,
    val pvPowerW: Int?,
    val battery: PlantBattery?,
    val gridPowerW: Int?,
    val gridSource: GridSource?,
    val loadPowerW: Int?,
    val today: EnergyTotals,
    val consumers: List<ConsumerLoad> = emptyList(),
) {
    val failedDevices: List<DeviceStatus> get() = devices.filter { it.error != null }
}

object PlantAggregator {
    fun aggregate(statuses: List<DeviceStatus>, readAt: Instant = Instant.now()): PlantSnapshot {
        val all = statuses.mapNotNull { status -> status.reading?.let { status.device to normalized(status.device, it) } }
        fun isGridMeter(d: DeviceConfig) = d.type.kind == DeviceKind.METER && d.meterRole == MeterRole.GRID
        // Plant devices: producers, batteries and grid meters. PV and consumer sub-meters and consumer devices
        // are handled on their own below.
        val readings = all.filter { (d, _) ->
            d.type.kind == DeviceKind.INVERTER || d.type.kind == DeviceKind.BATTERY || isGridMeter(d)
        }
        val pvMeters = all.filter { (d, _) -> d.type.kind == DeviceKind.METER && d.meterRole == MeterRole.PV }

        // A PV production meter counts what flows from the PV side through it (+ = "export" in meter terms).
        val pvFromMeters = pvMeters.mapNotNull { (_, r) -> r.gridPowerW?.let { maxOf(0, it) } }
        val pv = sumOrNull(readings.map { it.second.pvPowerW } + pvFromMeters)

        val batteries = readings.mapNotNull { it.second.battery }
        val battery = if (batteries.isEmpty()) null else PlantBattery(
            powerW = batteries.sumOf { it.powerW },
            // Without capacities this is a plain average across batteries.
            // Values outside 0–100 % are device glitches (seen: an LG with a garbled map after a restart).
            socPercent = batteries.mapNotNull { it.socPercent?.takeIf { soc -> soc in 0..100 } }.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
        )

        // A grid meter measures the plant's grid connection. If the plant has one, inverters' own grid values
        // are ignored (they'd double count); decided by configuration, so a failed meter shows "unknown".
        val gridSource = when {
            statuses.any { isGridMeter(it.device) } -> GridSource.METERS
            statuses.any { it.device.type.kind == DeviceKind.INVERTER } -> GridSource.INVERTERS
            else -> null
        }
        val gridReadings = readings.filter {
            when (gridSource) {
                GridSource.METERS -> isGridMeter(it.first)
                GridSource.INVERTERS -> it.first.type.kind == DeviceKind.INVERTER
                null -> false
            }
        }
        val grid = sumOrNull(gridReadings.map { it.second.gridPowerW })

        // Home: the inverters' own load measurement when they're the whole plant (no meter, no standalone
        // battery, no PV meter) and all report one; their PV and battery figures don't always add up (the LG
        // HTTPS API's DC values can give a negative balance). Otherwise the balance, which can't be below zero.
        val ownLoads = readings.map { it.second.loadPowerW }
        val load = when {
            gridSource == GridSource.INVERTERS && readings.all { it.first.type.kind == DeviceKind.INVERTER } &&
                pvMeters.isEmpty() && readings.isNotEmpty() && ownLoads.all { it != null } -> ownLoads.sumOf { it!! }
            grid != null -> maxOf(0, (pv ?: 0) + (battery?.powerW ?: 0) - grid)
            else -> sumOrNull(ownLoads)
        }

        // Consumers are already inside the home load (the grid meter or balance includes them).
        val consumers = statuses.mapNotNull { s ->
            val d = s.device
            val r = s.reading?.let { normalized(d, it) }
            when {
                d.type.kind == DeviceKind.CONSUMER -> ConsumerLoad(d, r?.loadPowerW, r?.status ?: s.error)
                d.type.kind == DeviceKind.METER && d.meterRole == MeterRole.CONSUMER ->
                    ConsumerLoad(d, r?.gridPowerW?.let { maxOf(0, -it) }, s.error)
                else -> null
            }
        }

        return PlantSnapshot(
            readAt = readAt,
            devices = statuses,
            pvPowerW = pv,
            battery = battery,
            gridPowerW = grid,
            gridSource = gridSource,
            loadPowerW = load,
            today = EnergyTotals(
                pvKwh = sumKwh(readings.map { it.second.today.pvKwh }),
                exportKwh = sumKwh(gridReadings.map { it.second.today.exportKwh }),
                importKwh = sumKwh(gridReadings.map { it.second.today.importKwh }),
                loadKwh = sumKwh(readings.map { it.second.today.loadKwh }),
            ),
            consumers = consumers,
        )
    }

    /** Meters: the device form's "invert sign" flips the meter's direction (SunSpec inverters do it in their driver). */
    private fun normalized(d: DeviceConfig, r: DeviceReading): DeviceReading =
        if (d.type.kind == DeviceKind.METER && d.invertGrid) {
            r.copy(gridPowerW = r.gridPowerW?.let { -it }, phases = r.phases.map { p -> p.copy(powerW = p.powerW?.let { -it }) })
        } else r

    private fun sumOrNull(values: List<Int?>): Int? = values.filterNotNull().takeIf { it.isNotEmpty() }?.sum()

    private fun sumKwh(values: List<Double?>): Double? = values.filterNotNull().takeIf { it.isNotEmpty() }?.sum()
}
