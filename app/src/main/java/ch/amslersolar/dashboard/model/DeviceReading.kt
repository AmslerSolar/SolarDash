// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.model

/**
 * What one device measured in one poll. Every field is optional: an inverter has no meter readings,
 * a meter has no PV, and so on.
 *
 * Sign conventions: [gridPowerW] is positive when exporting, negative when importing.
 * [BatteryState.powerW] is positive when discharging, negative when charging.
 */
data class DeviceReading(
    val pvPowerW: Int? = null,
    val pvStrings: List<PvString> = emptyList(),
    val battery: BatteryState? = null,
    val gridPowerW: Int? = null,
    val phases: List<PhaseReading> = emptyList(),
    val loadPowerW: Int? = null,
    val temperatureC: Double? = null,
    val status: String? = null,
    val today: EnergyTotals = EnergyTotals(),
    val lifetimePvKwh: Double? = null,
    /** Everything else the driver knows, for the detail view. */
    val details: List<DetailSection> = emptyList(),
)

/** A titled group of (label, formatted value) rows in the device detail view. */
data class DetailSection(val title: String, val rows: List<Pair<String, String>>)

/** [powerW] is null when the device only reports voltage and current for that string. */
data class PvString(val index: Int, val voltageV: Double, val currentA: Double, val powerW: Int?)

/**
 * One grid phase, named L1, L2 or L3 in every driver. [currentA] and [powerW] are null when the device only
 * reports the phase voltage (power then exists only as the grid total).
 */
data class PhaseReading(val name: String, val voltageV: Double, val currentA: Double?, val frequencyHz: Double, val powerW: Int?)

data class BatteryState(val powerW: Int, val socPercent: Int?, val voltageV: Double?, val mode: String)

data class EnergyTotals(
    val pvKwh: Double? = null,
    val exportKwh: Double? = null,
    val importKwh: Double? = null,
    val loadKwh: Double? = null,
)

data class DeviceInfo(val model: String, val serial: String)
