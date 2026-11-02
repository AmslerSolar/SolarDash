// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.driver.demo

import ch.amslersolar.solardash.driver.DeviceDriver
import ch.amslersolar.solardash.model.BatteryState
import ch.amslersolar.solardash.model.DeviceInfo
import ch.amslersolar.solardash.model.DeviceReading
import ch.amslersolar.solardash.model.EnergyTotals
import ch.amslersolar.solardash.model.PhaseReading
import ch.amslersolar.solardash.model.PvString
import java.time.Instant
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Fake live data for exercising the UI across its whole range: every 5 s all demo devices switch to a new
 * random scenario in which each power is between 1 W and 99 kW in magnitude (log-uniform, so watts and
 * tens of kilowatts are equally common). The scenario is shared, so plant totals stay consistent:
 * solar, battery, grid and the derived home consumption all stay in range for any mix of demo devices.
 */
private object DemoScenario {
    private const val MIN_W = 1
    private const val MAX_W = 99_000
    private const val PERIOD_S = 5

    /** Signs as in DeviceReading: grid + export, battery + discharge. */
    data class Values(val pv: Int, val inverterBattery: Int, val acBattery: Int, val grid: Int)

    fun now(): Values = at(Instant.now().epochSecond / PERIOD_S)

    private fun at(bucket: Long): Values {
        val random = Random(bucket)
        fun magnitude() = exp(random.nextDouble() * ln(MAX_W.toDouble())).roundToInt().coerceIn(MIN_W, MAX_W)
        fun signed() = magnitude() * if (random.nextBoolean()) 1 else -1
        fun inRange(w: Int) = abs(w) in MIN_W..MAX_W
        repeat(10_000) {
            val v = Values(pv = magnitude(), inverterBattery = signed(), acBattery = signed(), grid = signed())
            // Home with and without the AC battery in the plant, and the combined battery total.
            val homeWithout = v.pv + v.inverterBattery - v.grid
            val homeWith = homeWithout + v.acBattery
            if (homeWithout in MIN_W..MAX_W && homeWith in MIN_W..MAX_W && inRange(v.inverterBattery + v.acBattery)) return v
        }
        return Values(pv = 5_000, inverterBattery = -1_000, acBattery = 500, grid = 2_000)
    }
}

private fun batteryMode(powerW: Int) = when {
    powerW < 0 -> "Charging"
    powerW > 0 -> "Discharging"
    else -> "Standby"
}

/** A 29.9 kW hybrid inverter with its own battery. */
class DemoInverter : DeviceDriver {
    private var soc = 62.0

    override suspend fun identify() = DeviceInfo(model = "GW29.9K-ET (demo)", serial = "DEMO-INV-0001")

    override suspend fun read(): DeviceReading {
        val v = DemoScenario.now()
        val strings = listOf(0.4, 0.35, 0.25).mapIndexed { i, share ->
            val power = (v.pv * share).roundToInt()
            val voltage = 580.0 + Random.nextDouble() * 40
            PvString(i + 1, voltage, power / voltage, power)
        }
        val battery = v.inverterBattery
        soc = (soc - battery / 20_000.0).coerceIn(0.0, 100.0)
        val output = v.pv + battery

        return DeviceReading(
            pvPowerW = v.pv,
            pvStrings = strings,
            battery = BatteryState(powerW = battery, socPercent = soc.roundToInt(), voltageV = 480.0, mode = batteryMode(battery)),
            gridPowerW = v.grid,
            phases = listOf("L1", "L2", "L3").map { PhaseReading(it, 229.0 + Random.nextDouble() * 4, output / 3 / 230.0, 50.0, output / 3) },
            loadPowerW = output - v.grid,
            temperatureC = 32.0 + Random.nextDouble() * 15,
            status = "On-grid",
            today = EnergyTotals(pvKwh = 84.3, exportKwh = 41.7, importKwh = 3.2, loadKwh = 29.8),
            lifetimePvKwh = 61_420.5,
        )
    }
}

/** A standalone (AC-coupled) battery. */
class DemoBattery : DeviceDriver {
    private var soc = 45.0

    override suspend fun identify() = DeviceInfo(model = "AC battery 15 kWh (demo)", serial = "DEMO-BAT-0001")

    override suspend fun read(): DeviceReading {
        val power = DemoScenario.now().acBattery
        soc = (soc - power / 30_000.0).coerceIn(5.0, 100.0)
        return DeviceReading(
            battery = BatteryState(powerW = power, socPercent = soc.roundToInt(), voltageV = 51.2, mode = batteryMode(power)),
            temperatureC = 24.0,
            status = "Online",
        )
    }
}

/** A three-phase meter at the grid connection point. */
class DemoMeter : DeviceDriver {
    override suspend fun identify() = DeviceInfo(model = "3-phase meter (demo)", serial = "DEMO-MTR-0001")

    override suspend fun read(): DeviceReading {
        val grid = DemoScenario.now().grid
        return DeviceReading(
            gridPowerW = grid,
            phases = listOf("L1", "L2", "L3").map { PhaseReading(it, 230.0 + Random.nextDouble() * 3, grid / 3 / 230.0, 50.0, grid / 3) },
            status = "Online",
            today = EnergyTotals(exportKwh = 44.1, importKwh = 5.6),
        )
    }
}

/** A heat pump inside the home: compressor cycling over the hour, power only while it runs. */
class DemoHeatPump : DeviceDriver {
    override suspend fun identify() = DeviceInfo(model = "Air-to-water heat pump (demo)", serial = "DEMO-HP-0001")

    override suspend fun read(): DeviceReading {
        val minute = java.time.LocalTime.now().minute
        val running = minute % 20 < 12
        val power = if (running) 1_800 + Random.nextInt(-150, 150) else 15
        return DeviceReading(
            loadPowerW = power,
            temperatureC = 47.5,
            status = if (running) "Heating" else "Standby",
            details = listOf(
                ch.amslersolar.solardash.driver.Details.section(
                    "Heat pump",
                    "Flow temperature" to "47.5 °C",
                    "Return temperature" to "41.0 °C",
                    "Outdoor temperature" to "6.0 °C",
                    "Compressor" to if (running) "On" else "Off",
                ),
            ),
        )
    }
}

