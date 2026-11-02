// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.driver.spec

import ch.amslersolar.solardash.model.BatteryState
import ch.amslersolar.solardash.model.DeviceProblem
import ch.amslersolar.solardash.model.DeviceProblem.Kind
import ch.amslersolar.solardash.model.DeviceReading
import ch.amslersolar.solardash.model.PhaseReading
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Hooks for KOSTAL PLENTICORE, SMA (SMA Modbus profile) and Sungrow SHx / SG: what their ModbusSpec files describe
 * in `notes` but the format can't express (presence rules, fallbacks, directions from status words, "B only when
 * not A"). Only trusted values are used, as in [SpecDriver].
 */
internal object Inverters1Hooks {
    private fun Map<String, SpecValue>.n(key: String): Double? = this[key]?.takeIf { it.trusted }?.number

    private fun mode(p: Int) = when { p > 0 -> "Discharging"; p < 0 -> "Charging"; else -> "Idle" }

    /**
     * PLENTICORE: a battery only with a battery type (588 ≠ 0; else a SOC in 210); its power |582| (else |216 × 200|)
     * with the direction of the current 200 (charge − / discharge +); SOC 514, else 210. Grid and home
     * (116 + 106 + 108) only with an energy meter (1082 sensor ≠ FFh; else the meter reporting a frequency). Solar
     * 1066, else the DC inputs up to register 34. Phases with a voltage. The PSSB fuse only with a battery.
     */
    val KOSTAL_PLENTICORE = SpecHooks { v, r ->
        val hasBattery = v.n("x.battery_type")?.let { it != 0.0 } ?: ((v.n("x.battery_soc_float") ?: 0.0) > 0)
        val battery = if (!hasBattery) null else {
            val current = v.n("battery_current")
            val raw582 = v.n("x.battery_power")
            val magnitude = raw582?.let { abs(it).roundToInt() }
                ?: current?.let { c -> v.n("battery_voltage")?.let { abs(it * c).roundToInt() } } ?: 0
            val direction = if (current != null && abs(current) >= 0.1) current.sign.toInt() else raw582?.sign?.toInt() ?: 0
            val p = magnitude * direction
            BatteryState(
                p, v.n("battery_soc")?.toInt()?.takeIf { it in 0..100 } ?: v.n("x.battery_soc_float")?.roundToInt(),
                v.n("battery_voltage"), mode(p),
            )
        }
        val meter = v.n("x.sensor_type")?.let { (it.toInt() and 0xFF) != 0xFF } ?: ((v.n("x.meter_frequency") ?: 0.0) > 0)
        val parts = listOf("x.home_consumption_pv", "x.home_consumption_battery", "x.home_consumption_grid").map { v.n(it) }
        val count = (v.n("x.pv_strings")?.toInt() ?: 3).coerceIn(1, 3)
        val dc = (1..count).mapNotNull { k -> v.n("x.dc${k}_voltage") ?: return@mapNotNull null; v.n("x.dc${k}_power")?.roundToInt() }
        r.copy(
            pvPowerW = r.pvPowerW ?: dc.takeIf { it.isNotEmpty() }?.sum(),
            battery = battery,
            gridPowerW = if (meter) r.gridPowerW else null,
            loadPowerW = if (meter && parts.all { it != null }) parts.sumOf { it!! }.roundToInt() else null,
            phases = r.phases.filter { it.voltageV > 0 },
            problems = if (hasBattery) r.problems else r.problems.filterNot { it.text == "Battery fuse failed (PSSB)" },
        )
    }

    /** An SMA status / tag value; 0xFFFFFFFF (NaN of a U32, 0xFFFFFF after the 24-bit mask) and 0xFFFFFD aren't values. */
    private fun Map<String, SpecValue>.tag(key: String): Int? = n(key)?.toInt()?.takeIf { it != 0xFFFFFF && it != 0xFFFFFD }

    private val SUNNY_ISLAND_TYPES = setOf(9223, 9224, 9278, 9279)

    /**
     * SMA: a Sunny Island (device type, or 31393 / 31395 answering) has no solar power of its own, measures the home
     * load (30861) and gives its battery power as 31395 − 31393; other batteries (SOC 30845 or battery status 30955)
     * |30851 × 30843| with the direction from 30955 (2292 charging, 2293 discharging, 2291 standby = 0), else from
     * the current (> 0 charging, unverified). Solar = the DC inputs that report a voltage, else (no battery) the AC
     * power, else 0. Grid = feed-in − reference where either is there. Phases with a voltage, current 30977 ff.,
     * else 30797 ff. Daily / total yield from the U64 registers, else the U32 ones. Operating status 1392 only
     * when the condition gives no fault; a condition text with the event number.
     */
    val SMA = SpecHooks { v, r ->
        val islandType = v.tag("x.device_type")?.let { it in SUNNY_ISLAND_TYPES } == true
        val batteryInverter = islandType || v.n("x.battery_charge_power") != null || v.n("x.battery_discharge_power") != null
        val hasBattery = batteryInverter || v.n("battery_soc") != null || v.tag("x.battery_status") != null
        val status = v.tag("x.battery_status")
        val batteryPower: Int? = run {
            val charge = v.n("x.battery_charge_power")
            val discharge = v.n("x.battery_discharge_power")
            if (charge != null || discharge != null) return@run ((discharge ?: 0.0) - (charge ?: 0.0)).roundToInt()
            if (status == 2291) return@run 0
            val current = v.n("battery_current")?.let { -it } ?: return@run null // the file's + charge, as reported
            val volts = v.n("battery_voltage") ?: return@run null
            val magnitude = abs(current * volts).roundToInt()
            when (status) {
                2292 -> -magnitude
                2293 -> magnitude
                else -> if (current > 0) -magnitude else magnitude
            }
        }
        val battery = if (!hasBattery) null else {
            val p = batteryPower ?: 0
            BatteryState(p, v.n("battery_soc")?.toInt()?.takeIf { it in 0..100 }, v.n("battery_voltage"), mode(p))
        }
        val dc = (1..2).mapNotNull { k -> v.n("x.dc${k}_voltage") ?: return@mapNotNull null; v.n("x.dc${k}_power")?.roundToInt() }
        val pv = when {
            batteryInverter -> null
            dc.isNotEmpty() -> dc.sum()
            battery == null -> v.n("x.ac_power")?.roundToInt() ?: 0
            else -> 0
        }
        val imp = v.n("x.grid_import_power")
        val exp = v.n("x.grid_export_power")
        val grid = if (imp != null || exp != null) ((exp ?: 0.0) - (imp ?: 0.0)).roundToInt() else null
        val phases = (1..3).mapNotNull { k ->
            val volts = v.n("l${k}_voltage")?.takeIf { it > 0 } ?: return@mapNotNull null
            PhaseReading("L$k", volts, v.n("l${k}_current") ?: v.n("x.l${k}_current_u32"), v.n("frequency") ?: 0.0, v.n("l${k}_power")?.roundToInt())
        }
        val event = v.n("x.event_number")?.toLong()?.takeIf { it > 0 }
        var problems = r.problems.map { p ->
            if (event != null && p.text.endsWith("; event number in 30197)")) p.copy(text = p.text.substringBefore(" (") + " (event $event)") else p
        }
        if (v.tag("x.condition") == 35) problems = problems.filterNot { it.text == "Fault (operating status 1392)" }
        r.copy(
            pvPowerW = pv, battery = battery, gridPowerW = grid, phases = phases,
            loadPowerW = if (batteryInverter) v.n("load_power")?.roundToInt() else null,
            today = r.today.copy(pvKwh = if (batteryInverter) null else v.n("pv_energy_today") ?: v.n("x.daily_yield_u32")),
            lifetimePvKwh = if (batteryInverter) null else v.n("pv_energy") ?: v.n("x.total_yield_u32"),
            problems = problems,
        )
    }

    /** Sungrow output type (5001): 0 single phase (L1), 1 3P4L; 2 3P3L gives line voltages only: no phase rows. */
    private fun sungrowPhases(v: Map<String, SpecValue>, current: (Int) -> Double?): List<PhaseReading> {
        val n = when (v.n("x.output_type")?.toInt()) { 0 -> 1; 1 -> 3; else -> 0 }
        val hz = v.n("frequency") ?: v.n("x.grid_frequency_0_1") ?: 0.0
        return (1..n).mapNotNull { k ->
            val volts = v.n("l${k}_voltage")?.takeIf { it > 0 } ?: return@mapNotNull null
            PhaseReading("L$k", volts, current(k), hz, null) // l1_power … are the meter's, not this phase's
        }
    }

    /** SHx fault / alarm words (13049 ff.): label in the problem texts, and whether set bits are warnings. */
    private val SHX_FIELDS = listOf(
        Triple("x.inverter_alarm", "inverter alarm", true), Triple("x.grid_side_fault", "grid-side fault", false),
        Triple("x.system_fault_1", "system fault 1", false), Triple("x.system_fault_2", "system fault 2", false),
        Triple("x.dc_side_fault", "DC-side fault", false), Triple("x.permanent_fault", "permanent fault", false),
        Triple("x.bdc_side_fault", "BDC-side fault", false), Triple("x.bdc_side_permanent_fault", "BDC-side permanent fault", false),
        Triple("x.battery_fault", "battery fault", false), Triple("x.battery_alarm", "battery alarm", true),
        Triple("x.bms_alarm", "BMS alarm", true), Triple("x.bms_protection", "BMS protection", false),
        Triple("x.bms_fault_1", "BMS fault 1", false), Triple("x.bms_fault_2", "BMS fault 2", false),
        Triple("x.bms_alarm_2", "BMS alarm 2", true),
    )
    private val SHX_STATE_FAULTS = setOf("Fault (0x5500)", "Fault (0x0100)", "Communicate fault (0x2500)")

    /**
     * Sungrow SHx: grid from the meter (5600); the file's fallback, export power 13009, only on firmware known to be
     * older than protocol V1.1.7 (4951; 5600 came with it): on newer firmware a missing 5600 means no meter, and
     * 13009 is only valid with one. Home (13007) only with a grid figure. Battery |5213| (else |13021|), direction
     * from the power-flow bits 13000 (1 charging, 2 discharging; neither: 0 W), none when voltage, SOC and power
     * are all 0. Phases per output type with current amounts. Set bits without a named rule as "Code (… bit n)";
     * the fault / warn-running states only when no bit already reports a fault / warning.
     */
    val SUNGROW_SHX = SpecHooks { v, r ->
        val proto = v.n("x.protocol_version")?.takeIf { it > 0 }
        val meter = v.n("x.meter_power") != null
        val older = proto != null && proto < 0x01010700
        val grid = if (meter || older) r.gridPowerW else null
        val flow = v.n("x.power_flow")?.toInt()
        val magnitude = (v.n("x.battery_power") ?: v.n("x.battery_power_legacy"))?.let { abs(it).roundToInt() }
        val soc = v.n("battery_soc")
        val volts = v.n("battery_voltage")
        val battery = if ((volts ?: 0.0) <= 0 && (soc ?: 0.0) <= 0 && (magnitude ?: 0) == 0) null else {
            val charging = flow != null && flow and 2 != 0
            val discharging = flow != null && flow and 4 != 0
            val p = when {
                discharging && !charging -> magnitude ?: 0
                charging && !discharging -> -(magnitude ?: 0)
                else -> 0
            }
            BatteryState(p, soc?.roundToInt(), volts, mode(p))
        }
        val problems = r.problems.toMutableList()
        for ((key, label, warning) in SHX_FIELDS) {
            val bits = v.n(key)?.toLong() ?: continue
            for (bit in 0 until 32) {
                if ((bits shr bit) and 1L == 0L || problems.any { it.text.endsWith("($label bit $bit)") }) continue
                problems += DeviceProblem(if (warning) Kind.WARNING else Kind.FAULT, "Code ($label bit $bit)")
            }
        }
        val bitFault = problems.any { it.kind == Kind.FAULT && it.text !in SHX_STATE_FAULTS }
        val bitWarning = problems.any { it.kind == Kind.WARNING && it.text.contains(" bit ") }
        r.copy(
            gridPowerW = grid,
            loadPowerW = if (grid != null) r.loadPowerW else null,
            battery = battery,
            phases = sungrowPhases(v) { k -> v.n("l${k}_current")?.let(::abs) },
            problems = problems.filterNot { (bitFault && it.text in SHX_STATE_FAULTS) || (bitWarning && it.text == "Warn running (0x9100)") },
        )
    }

    /**
     * Sungrow SG: solar = AC power 5030 (not below 0), else DC 5016; total yield 5143, else 5003 (kWh). The meter
     * power 5082 has no documented sign: + import by default, switched when the energy balance (load = AC + import)
     * keeps saying otherwise (per device, so a new instance per driver). Home only with a meter. Phases per output
     * type. The fault / alarm code (5044) is only valid in the fault (0x5500) and alarm-run (0x9100) states: one
     * problem then (code 10 the grid outage).
     */
    class SungrowSg : SpecHooks {
        private var votes = 0 // > 0: 5082 > 0 is import (default), < 0 export

        override fun adjust(values: Map<String, SpecValue>, reading: DeviceReading): DeviceReading {
            val v = values
            val ac = v.n("pv_power")
            val meter = v.n("grid_power")?.let { -it } // back to the raw value (the file says + import)
            val load = v.n("load_power")
            if (meter != null && load != null && ac != null && abs(meter) >= 100) {
                val asImport = abs(ac + meter - load)
                val asExport = abs(ac - meter - load)
                if (asImport + 50 < asExport) votes = minOf(votes + 1, 10) else if (asExport + 50 < asImport) votes = maxOf(votes - 1, -10)
            }
            val state = v.n("state")?.toInt()
            val code = v["error_code"]?.takeIf { it.trusted && it.number != null && it.number != 0.0 }
            val problems = reading.problems.filterNot {
                it.text.startsWith("Fault (work state 0x5500") || it.text.startsWith("Alarm run (work state 0x9100") || it.text == "Grid power outage (code 10)"
            }.toMutableList()
            if (state == 0x5500 || state == 0x9100) {
                val fault = state == 0x5500
                val n = code?.number?.toInt()
                val kind = when { n == 10 -> Kind.MAINS_LOST; fault -> Kind.FAULT; else -> Kind.WARNING }
                val name = code?.enumText?.takeIf { it != "Unknown" }
                val text = when {
                    n == null -> if (fault) "Fault (0x5500)" else "Alarm run (0x9100)"
                    name != null -> "$name ($n)"
                    else -> "${if (fault) "Fault" else "Alarm"} code $n"
                }
                problems.add(0, DeviceProblem(kind, text))
            }
            return reading.copy(
                pvPowerW = ac?.roundToInt()?.coerceAtLeast(0) ?: v.n("x.dc_power")?.roundToInt(),
                gridPowerW = meter?.let { if (votes >= 0) -it else it }?.roundToInt(),
                loadPowerW = if (meter != null) load?.roundToInt() else null,
                phases = sungrowPhases(v) { k -> v.n("l${k}_current") },
                lifetimePvKwh = v.n("pv_energy") ?: v.n("x.total_yield_kwh"),
                problems = problems,
            )
        }
    }
}
