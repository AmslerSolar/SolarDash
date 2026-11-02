// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.driver.spec

import ch.amslersolar.solardash.model.BatteryState
import ch.amslersolar.solardash.model.DeviceProblem
import ch.amslersolar.solardash.model.DeviceProblem.Kind
import kotlin.math.roundToInt

/**
 * Hooks for KOSTAL PIKO CI, Schneider Conext CL-60 / Conext Gateway, CPS 403X, Power-One TRIO, Ingeteam INGECON SUN
 * and Riello central inverters: what their ModbusSpec files
 * describe in `notes` but the format can't express (fallback registers, "B only when not A", meter position).
 * Only trusted values are used, as in [SpecDriver].
 */
internal object Inverters2Hooks {
    private fun Map<String, SpecValue>.n(key: String): Double? = this[key]?.takeIf { it.trusted }?.number

    /**
     * PIKO CI: solar = 100, else Σ of the MPPT inputs (0x7000 block, else DC1–4 at 258 ff.) up to register 34;
     * 252 is the grid only with a meter (220 > 0) at the grid connection (611 ≠ 1): home = 116 + 108 then; with
     * the meter on the load side (611 = 1) 252 is the home consumption and there is no grid figure.
     */
    val KOSTAL_PIKO_CI = SpecHooks { v, r ->
        val meter = (v.n("x.meter_frequency") ?: 0.0) > 0
        val onLoad = v.n("x.meter_position") == 1.0
        val count = (v.n("x.pv_inputs")?.toInt() ?: 2).coerceIn(1, 8)
        val inputs = (1..count).mapNotNull { k ->
            v.n("x.mppt${k}_voltage") ?: v.n("x.dc${k}_voltage") ?: return@mapNotNull null
            v.n("x.mppt${k}_power") ?: v.n("x.dc${k}_power")
        }
        val raw252 = v.n("grid_power")?.let { -it } // the file gives 252 as + import; back to the raw value
        val load = when {
            !meter -> null
            onLoad -> raw252?.roundToInt()?.coerceAtLeast(0)
            else -> {
                val fromPv = v.n("x.home_consumption_pv")
                val fromGrid = v.n("x.home_consumption_grid")
                if (fromPv != null && fromGrid != null) (fromPv + fromGrid).roundToInt() else null
            }
        }
        r.copy(
            pvPowerW = r.pvPowerW ?: inputs.takeIf { it.isNotEmpty() }?.sum()?.roundToInt(),
            gridPowerW = if (meter && !onLoad) r.gridPowerW else null,
            loadPowerW = load,
            phases = r.phases.filter { it.voltageV > 0 },
        )
    }

    /**
     * Conext CL-60: phase rows only with wiring 1 (3P/4W; else the voltages are line-to-line). Fault code only in
     * the fault (0x5500) and alarm (0x9100) states: a grid code makes the fault MAINS_LOST, an alarm is a warning
     * naming the code, code 75 (low irradiance) is no problem.
     */
    val CONEXT_CL60 = SpecHooks { v, r ->
        val state = v.n("state")?.toInt()
        val code = v.n("error_code")?.toInt()?.takeIf { it != 0 }
        val name = code?.let { c -> v["error_code"]?.enumText?.takeIf { it != "Unknown" }?.let { "$it ($c)" } }
        val problems = when (state) {
            0x5500 -> when (code) {
                75 -> emptyList()
                null -> listOf(DeviceProblem(Kind.FAULT, "Fault (state 0x5500)"))
                in setOf(2, 3, 4, 5, 8, 9, 10, 13, 14, 15, 17) -> listOf(DeviceProblem(Kind.MAINS_LOST, name ?: "Fault code $code"))
                else -> listOf(DeviceProblem(Kind.FAULT, name ?: "Fault code $code"))
            }
            0x9100 -> if (code == null || code == 75) emptyList() else listOf(DeviceProblem(Kind.WARNING, name ?: "Alarm code $code"))
            else -> r.problems.filter { it.kind != Kind.MAINS_LOST } // derating, communication failure
        }
        r.copy(
            phases = if (v.n("x.wiring") != 1.0) emptyList() else r.phases.filter { it.voltageV > 0 },
            problems = problems,
        )
    }

    /**
     * Conext Gateway: fallbacks 0x0044 PV harvest, 0x004A / 0x0046 DC inverting / charging power, 0x0134 / 0x0144
     * PV input energy; grid and battery from whichever of the two unsigned registers answer; SoC only 0–100;
     * lines without voltage left out; a negative load power dropped.
     */
    val CONEXT_GATEWAY = SpecHooks { v, r ->
        val sell = v.n("x.grid_output_power")
        val buy = v.n("x.grid_input_power")
        val discharge = v.n("x.battery_discharge_power") ?: v.n("x.dc_inverting_power")
        val charge = v.n("x.battery_charge_power") ?: v.n("x.dc_charging_power")
        val soc = v.n("battery_soc")?.roundToInt()?.takeIf { it in 0..100 }
        val battery = if (discharge == null && charge == null && soc == null) null else {
            val p = ((discharge ?: 0.0) - (charge ?: 0.0)).roundToInt()
            BatteryState(p, soc, v.n("battery_voltage"), when { p > 0 -> "Discharging"; p < 0 -> "Charging"; else -> "Idle" })
        }
        r.copy(
            pvPowerW = r.pvPowerW ?: v.n("x.pv_harvest_power")?.roundToInt(),
            gridPowerW = if (sell != null || buy != null) ((sell ?: 0.0) - (buy ?: 0.0)).roundToInt() else null,
            battery = battery,
            phases = r.phases.filter { it.voltageV > 0 },
            loadPowerW = r.loadPowerW?.takeIf { it >= 0 },
            today = r.today.copy(pvKwh = r.today.pvKwh ?: v.n("x.pv_input_energy_today")),
            lifetimePvKwh = r.lifetimePvKwh ?: v.n("x.pv_input_energy"),
        )
    }

    private val CPS_EVENT_WORDS = listOf("x.pfault" to "PFault", "x.fault0" to "Fault0", "x.fault1" to "Fault1", "x.fault2" to "Fault2", "x.fault3" to "Fault3", "x.fault4" to "Fault4", "x.warn" to "Warn")

    /**
     * CPS 403X: no phase rows (line-to-line voltages only); solar = Pdc, else Σ U × I of the PV inputs (a missing
     * current counts as 0), else Pac; on the 50/60 kW map set bits outside the event table are "<word> bit <n>";
     * mode 0x8000 only when no fault bit is reported.
     */
    val CPS_403X = SpecHooks { v, r ->
        val computed = (1..3).mapNotNull { i ->
            val volts = v.n("x.pv${i}_voltage")?.takeIf { it > 0 } ?: return@mapNotNull null
            volts * (v.n("x.pv${i}_current") ?: 0.0)
        }.takeIf { it.isNotEmpty() }?.sum()?.roundToInt()
        val pac = v.n("x.ac_power")?.roundToInt()
        val undecoded = CPS_EVENT_WORDS.flatMap { (key, word) ->
            val value = v[key]?.takeIf { it.trusted } ?: return@flatMap emptyList()
            value.flags.filter { it.startsWith("Bit ") }.reversed().map { bit ->
                DeviceProblem(if (key == "x.warn") Kind.WARNING else Kind.FAULT, "$word ${bit.lowercase()}")
            }
        }
        val all = r.problems + undecoded
        val modeFault = all.filter { it.text.endsWith("(mode 0x8000)") }
        val problems = if (all.any { it.kind != Kind.WARNING && it !in modeFault }) all - modeFault.toSet() else all
        r.copy(
            pvPowerW = (r.pvPowerW ?: computed ?: pac)?.coerceAtLeast(0),
            phases = emptyList(),
            problems = problems.distinct(),
        )
    }

    /** An alarm code the file doesn't list (not 0, not 0xFFFF): a fault, as the files' notes say. */
    private fun unlistedAlarm(v: Map<String, SpecValue>): DeviceProblem? {
        val a = v["x.alarm_state"]?.takeIf { it.trusted } ?: return null
        val code = a.number?.toInt() ?: return null
        return if (code == 0 || code == 0xFFFF || a.enumText != "Unknown") null else DeviceProblem(Kind.FAULT, "Alarm code $code")
    }

    /** Power-One TRIO (GT map): solar not below 0; the L1 row only with a voltage, its current from 145; unlisted alarm codes. */
    val POWER_ONE_TRIO = SpecHooks { v, r ->
        r.copy(
            pvPowerW = r.pvPowerW?.coerceAtLeast(0),
            phases = r.phases.filter { it.voltageV > 0 }.map { it.copy(currentA = v.n("x.grid_current")) },
            problems = (r.problems + listOfNotNull(unlistedAlarm(v))).distinct(),
        )
    }

    /** Power-One TRIO-50/60 (Gt03 map): solar not below 0; unlisted alarm codes. */
    val POWER_ONE_TRIO_50 = SpecHooks { v, r ->
        r.copy(pvPowerW = r.pvPowerW?.coerceAtLeast(0), problems = (r.problems + listOfNotNull(unlistedAlarm(v))).distinct())
    }

    /**
     * Riello central inverter: solar = Σ Vpv × Ipv of the PV inputs with a voltage (a missing current counts as 0),
     * else the AC power Σ Pac of the phases shown; phases only with a voltage.
     */
    val RIELLO_CENTRAL = SpecHooks { v, r ->
        val phases = r.phases.filter { it.voltageV > 0 }
        val dc = (1..3).mapNotNull { i ->
            val volts = v.n("x.pv${i}_voltage")?.takeIf { it > 0 } ?: return@mapNotNull null
            volts * (v.n("x.pv${i}_current") ?: 0.0)
        }.takeIf { it.isNotEmpty() }?.sum()?.roundToInt()
        val ac = phases.mapNotNull { it.powerW }.takeIf { it.isNotEmpty() }?.sum()
        r.copy(pvPowerW = (dc ?: ac)?.coerceAtLeast(0), phases = phases)
    }

    /**
     * INGECON SUN (current map): solar = Σ of the MPPT powers present (any of 1–9), else the AC power; phases only
     * with a voltage, each with its own frequency.
     */
    val INGETEAM = SpecHooks { v, r ->
        val mppt = (1..9).mapNotNull { v.n("x.mppt${it}_power") }.takeIf { it.isNotEmpty() }?.sum()
        val freq = mapOf("L1" to "frequency", "L2" to "x.frequency2", "L3" to "x.frequency3")
        r.copy(
            pvPowerW = (mppt ?: v.n("x.ac_power"))?.roundToInt()?.coerceAtLeast(0),
            phases = r.phases.filter { it.voltageV > 0 }.map { it.copy(frequencyHz = v.n(freq.getValue(it.name)) ?: 0.0) },
        )
    }

    /**
     * INGECON SUN (older maps): solar = the PV power, on the 3Play (ABI1004 has none) else Σ of the PV inputs with a
     * voltage; phases only with a voltage; reduction reasons 2–4 are temperature only on the 1Play (on the 3Play maps
     * they're "not defined").
     */
    val INGETEAM_LEGACY = SpecHooks { v, r ->
        val inputs = (1..2).mapNotNull { i ->
            v.n("x.pv${i}_voltage")?.takeIf { it > 0 } ?: return@mapNotNull null
            v.n("x.pv${i}_power")
        }.takeIf { it.isNotEmpty() }?.sum()?.roundToInt()
        // The detected model: the points read are those of one model (or shared by several).
        val play3 = v.values.any { it.point?.models == listOf("play3") }
        val onePlay = v.values.any { p -> p.point?.models?.let { m -> m.isNotEmpty() && m.all { it.startsWith("play1") } } == true }
        r.copy(
            pvPowerW = (r.pvPowerW ?: inputs.takeIf { play3 })?.coerceAtLeast(0),
            phases = r.phases.filter { it.voltageV > 0 },
            problems = if (onePlay) r.problems else r.problems.filterNot { it.text.startsWith("Temperature derating") },
        )
    }
}
