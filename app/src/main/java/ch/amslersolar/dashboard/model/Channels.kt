// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.model

/**
 * Every numeric value a device reports, as named channels for recording and charting.
 *
 * Keys are stable across polls: "r:…" for the normalised reading, "d:<section>:<label>" for detail-view rows
 * (only rows that start with a number; enums, versions, dates and bit fields are skipped).
 */
data class Channel(val key: String, val label: String, val unit: String, val value: Double)

object Channels {
    private val NUMBER = Regex("""^([-+]?\d+(?:\.\d+)?)(?:\s+(\D.*))?$""")

    /** "238.4 V" → (238.4, "V"); "219.800003" → (219.8, ""); "Discharging (3)", "10.05.7437", "0x0000" → null. */
    fun parseNumber(text: String): Pair<Double, String>? {
        val m = NUMBER.matchEntire(text.trim()) ?: return null
        return m.groupValues[1].toDouble() to m.groupValues[2].trim()
    }

    fun extract(r: DeviceReading): List<Channel> {
        val out = mutableListOf<Channel>()
        fun add(key: String, label: String, unit: String, value: Number?) {
            if (value != null) out += Channel(key, label, unit, value.toDouble())
        }
        add("r:pv_power", "Solar power", "W", r.pvPowerW)
        add("r:grid_power", "Grid power (+ export)", "W", r.gridPowerW)
        add("r:load_power", "Load power", "W", r.loadPowerW)
        add("r:temperature", "Temperature", "°C", r.temperatureC)
        r.battery?.let {
            add("r:battery_power", "Battery power (+ discharge)", "W", it.powerW)
            add("r:battery_soc", "Battery state of charge", "%", it.socPercent)
            add("r:battery_voltage", "Battery voltage", "V", it.voltageV)
        }
        r.pvStrings.forEach {
            add("r:pv${it.index}_voltage", "PV${it.index} voltage", "V", it.voltageV)
            add("r:pv${it.index}_current", "PV${it.index} current", "A", it.currentA)
            add("r:pv${it.index}_power", "PV${it.index} power", "W", it.powerW)
        }
        r.phases.forEach {
            add("r:${it.name}_voltage", "${it.name} voltage", "V", it.voltageV)
            add("r:${it.name}_current", "${it.name} current", "A", it.currentA)
            add("r:${it.name}_power", "${it.name} power", "W", it.powerW)
            add("r:${it.name}_frequency", "${it.name} frequency", "Hz", it.frequencyHz)
        }
        add("r:today_pv", "Solar yield today", "kWh", r.today.pvKwh)
        add("r:today_export", "Exported today", "kWh", r.today.exportKwh)
        add("r:today_import", "Imported today", "kWh", r.today.importKwh)
        add("r:today_load", "Consumption today", "kWh", r.today.loadKwh)
        add("r:lifetime_pv", "Lifetime yield", "kWh", r.lifetimePvKwh)
        for (section in r.details) for ((label, text) in section.rows) {
            parseNumber(text)?.let { (value, unit) -> out += Channel("d:${section.title}:$label", "$label (${section.title})", unit, value) }
        }
        return out
    }

    /** Display units: power on one kW axis, energy in kWh, so mixed W / kW channels share a scale. */
    fun displayUnit(unit: String): Pair<String, Double> = when (unit) {
        "W" -> "kW" to 1e-3
        "Wh" -> "kWh" to 1e-3
        "VA" -> "kVA" to 1e-3
        "var", "Var" -> "kvar" to 1e-3
        "mV" -> "V" to 1e-3
        "MWh" -> "kWh" to 1e3
        "" -> "value" to 1.0
        else -> unit to 1.0
    }

    /** The channel's own name: for detail-view channels, the row label without its section. */
    fun name(key: String, label: String): String = if (key.startsWith("d:")) key.split(":", limit = 3)[2] else label

    fun section(key: String): String = if (key.startsWith("d:")) key.split(":", limit = 3)[1] else "Live values"

    /** Chart labels stay short: names longer than [max] characters end in "…" (the full name is in the tooltip). */
    fun shorten(name: String, max: Int = 15): String = if (name.length > max) name.take(max).trimEnd() + "…" else name
}
