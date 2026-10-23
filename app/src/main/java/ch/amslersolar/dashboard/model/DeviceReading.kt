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
    /** Faults, warnings and a missing grid / AC input, as the device itself reports them (for the plant status). */
    val problems: List<DeviceProblem> = emptyList(),
)

/**
 * Something the device itself reports as wrong. [text] is English, short, and keeps the device's own code where
 * it has one, e.g. "Battery under-voltage (F04)"; it's translated for display like the detail rows.
 */
data class DeviceProblem(val kind: Kind, val text: String) {
    enum class Kind {
        /** The device stopped or can't work normally (fault, error, alarm, failure). */
        FAULT,
        /** The device works but reports something to check (warning, derating, sensor issue). */
        WARNING,
        /**
         * The grid or AC input is missing: the device reports a grid/mains loss or runs off its battery because of
         * one. Normal on an off-grid plant whose generator is off, so the plant status decides how serious it is.
         */
        MAINS_LOST,
        /**
         * The device runs in off-grid / backup / island / emergency-power mode: the house is supplied from the
         * battery (and PV) without the grid. Normal on an off-grid plant; on a grid-tied plant it usually follows a
         * power cut. Shown as its own state, not as a fault.
         */
        BACKUP_MODE,
    }
}

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
