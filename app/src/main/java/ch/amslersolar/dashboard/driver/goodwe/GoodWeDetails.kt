// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.goodwe

import ch.amslersolar.dashboard.driver.Details.bits
import ch.amslersolar.dashboard.driver.Details.enum
import ch.amslersolar.dashboard.driver.Details.fmt
import ch.amslersolar.dashboard.driver.Details.section
import ch.amslersolar.dashboard.driver.modbus.RegisterBlock
import ch.amslersolar.dashboard.model.DetailSection

/** Detail view: the named registers of each block (spec V1.2, scaling as in its SF column). */
internal object GoodWeDetails {
    private val GRID_MODES = mapOf(0 to "Disconnected", 1 to "Connected", 2 to "Fault") // table 8-10
    private val BATTERY_MODES_ALL = mapOf(0 to "No battery") + GoodWeEtDriver.BATTERY_MODES
    private val PHASES = listOf("L1", "L2", "L3")

    private fun v(x: Number, sf: Int): Double = x.toDouble() / sf

    fun deviceInfo(i: RegisterBlock) = section(
        "Device info (35000)",
        "Rated power" to fmt(i.u16(35001), "W"), "Serial number" to i.ascii(35003, 8),
        "Model" to i.ascii(35011, 5), "Firmware DSP master" to fmt(i.u16(35016)),
        "Firmware DSP slave" to fmt(i.u16(35017)), "Firmware ARM" to fmt(i.u16(35019)),
    )

    fun running(r: RegisterBlock): List<DetailSection> {
        fun two(x: Int) = x.toString().padStart(2, '0')
        val clock = "20${two(r.u16(35100) shr 8)}-${two(r.u16(35100) and 0xFF)}-${two(r.u16(35101) shr 8)} " +
            "${two(r.u16(35101) and 0xFF)}:${two(r.u16(35102) shr 8)}:${two(r.u16(35102) and 0xFF)}"
        val pv = section(
            "PV inputs (35103–35119)",
            *(0 until 4).flatMap { i ->
                val b = 35103 + 4 * i
                listOf(
                    "PV${i + 1} voltage" to fmt(v(r.u16(b), 10), "V", 1), "PV${i + 1} current" to fmt(v(r.u16(b + 1), 10), "A", 1),
                    "PV${i + 1} power" to fmt(r.u32(b + 2), "W"),
                )
            }.toTypedArray(),
            "PV mode" to bits(r.u32(35119)),
        )
        val grid = section(
            "Grid side (35121–35143)",
            *PHASES.zip(listOf(35121, 35126, 35131)).flatMap { (name, b) ->
                listOf(
                    "$name voltage" to fmt(v(r.u16(b), 10), "V", 1), "$name current" to fmt(v(r.u16(b + 1), 10), "A", 1),
                    "$name frequency" to fmt(v(r.u16(b + 2), 100), "Hz", 2), "$name power" to fmt(r.s32(b + 3), "W"),
                )
            }.toTypedArray(),
            "Grid mode" to enum(r.u16(35136), GRID_MODES), "Total inverter power" to fmt(r.s32(35137), "W"),
            "AC active power (meter)" to fmt(r.s32(35139), "W"), "AC reactive power" to fmt(r.s32(35141), "var"),
            "AC apparent power" to fmt(r.s32(35143), "VA"),
        )
        val backup = section(
            "Back-up output (35145–35173)",
            *PHASES.zip(listOf(35145, 35151, 35157)).flatMap { (name, b) ->
                listOf(
                    "$name voltage" to fmt(v(r.u16(b), 10), "V", 1), "$name current" to fmt(v(r.u16(b + 1), 10), "A", 1),
                    "$name frequency" to fmt(v(r.u16(b + 2), 100), "Hz", 2), "$name load mode" to fmt(r.u16(b + 3)),
                    "$name power" to fmt(r.s32(b + 4), "W"),
                )
            }.toTypedArray(),
            "On-grid load L1" to fmt(r.s32(35163), "W"), "On-grid load L2" to fmt(r.s32(35165), "W"),
            "On-grid load L3" to fmt(r.s32(35167), "W"), "Total back-up load" to fmt(r.s32(35169), "W"),
            "Total on-grid load" to fmt(r.s32(35171), "W"), "UPS load" to fmt(v(r.u16(35173), 100), "%", 2),
        )
        val inverter = section(
            "Inverter (35174–35189)",
            "Clock" to clock, "Air temperature" to fmt(v(r.s16(35174), 10), "°C", 1),
            "Module temperature" to fmt(v(r.s16(35175), 10), "°C", 1),
            "Heat sink temperature" to fmt(v(r.s16(35176), 10), "°C", 1),
            "Function bits" to bits(r.u16(35177).toLong(), 4), "Bus voltage" to fmt(v(r.u16(35178), 10), "V", 1),
            "N-bus voltage" to fmt(v(r.u16(35179), 10), "V", 1), "Warning code" to bits(r.u16(35185).toLong(), 4),
            "Safety country" to fmt(r.u16(35186)), "Work mode" to enum(r.u16(35187), GoodWeEtDriver.WORK_MODES),
            "Error message" to bits(r.u32(35189)), "Operating hours" to fmt(r.u32(35197), "h"),
        )
        val battery = section(
            "Battery 1 (35180–35184)",
            "Voltage" to fmt(v(r.u16(35180), 10), "V", 1), "Current" to fmt(v(r.s16(35181), 10), "A", 1),
            "Power" to fmt(r.s32(35182), "W"), "Mode" to enum(r.u16(35184), BATTERY_MODES_ALL),
            "Battery strings" to fmt(r.u16(35212)),
        )
        val energy = section(
            "Energy (35191–35211)",
            "PV total" to fmt(v(r.u32(35191), 10), "kWh", 1), "PV today" to fmt(v(r.u32(35193), 10), "kWh", 1),
            // 35199/35202: the English says grid sell/buy, the Chinese "当天逆变的输出/输入电量", the inverter's AC
            // output/input energy of the day (includes battery discharge); the Chinese is authoritative. 35195/35200
            // aren't in Map V1.2 and are taken to be the matching lifetime totals.
            "Inverter output total" to fmt(v(r.u32(35195), 10), "kWh", 1), "Inverter output today" to fmt(v(r.u16(35199), 10), "kWh", 1),
            "Inverter input total" to fmt(v(r.u32(35200), 10), "kWh", 1), "Inverter input today" to fmt(v(r.u16(35202), 10), "kWh", 1),
            "Load total (without back-up)" to fmt(v(r.u32(35203), 10), "kWh", 1),
            "Load today (without back-up)" to fmt(v(r.u16(35205), 10), "kWh", 1),
            "Battery charged total" to fmt(v(r.u32(35206), 10), "kWh", 1),
            "Battery charged today" to fmt(v(r.u16(35208), 10), "kWh", 1),
            "Battery discharged total" to fmt(v(r.u32(35209), 10), "kWh", 1),
            "Battery discharged today" to fmt(v(r.u16(35211), 10), "kWh", 1),
            "CPLD warning code" to bits(r.u16(35213).toLong(), 4),
            "Diagnosis status high" to bits(r.u32(35218)), "Diagnosis status low" to bits(r.u32(35220)),
        )
        return listOf(inverter, pv, grid, backup, battery, energy)
    }

    fun pvExtended(e: RegisterBlock) = section(
        "PV extended (35301–35307)",
        "PV total power" to fmt(e.u32(35301), "W"), "PV channels" to fmt(e.u16(35303)),
        "PV5 voltage" to fmt(v(e.u16(35304), 10), "V", 1), "PV5 current" to fmt(v(e.u16(35305), 10), "A", 1),
        "PV6 voltage" to fmt(v(e.u16(35306), 10), "V", 1), "PV6 current" to fmt(v(e.u16(35307), 10), "A", 1),
    )

    fun battery2(b: RegisterBlock) = section(
        "Battery 2 (35262–35266)",
        "Voltage" to fmt(v(b.u16(35262), 10), "V", 1), "Current" to fmt(v(b.s16(35263), 10), "A", 1),
        "Power" to fmt(b.s32(35264), "W"), "Mode" to enum(b.u16(35266), BATTERY_MODES_ALL),
    )

    fun bms(m: RegisterBlock) = section(
        "Battery management (37000–37023)",
        "DRM status" to fmt(m.u16(37000)), "Battery type index" to fmt(m.u16(37001)),
        "BMS status" to fmt(m.u16(37002)), "Pack temperature" to fmt(v(m.u16(37003), 10), "°C", 1),
        "Max. charge current" to fmt(m.u16(37004), "A"), "Max. discharge current" to fmt(m.u16(37005), "A"),
        "Error code low" to bits(m.u16(37006).toLong(), 4), "State of charge" to fmt(m.u16(37007), "%"),
        "State of health" to fmt(m.u16(37008), "%"), "Battery strings" to fmt(m.u16(37009)),
        "Warning code low" to bits(m.u16(37010).toLong(), 4), "Battery protocol" to fmt(m.u16(37011)),
        "Error code high" to bits(m.u16(37012).toLong(), 4), "Warning code high" to bits(m.u16(37013).toLong(), 4),
        "BMS software version" to fmt(m.u16(37014)), "Battery hardware version" to fmt(m.u16(37015)),
        "Max. cell temperature" to fmt(v(m.u16(37020), 10), "°C", 1),
        "Min. cell temperature" to fmt(v(m.u16(37021), 10), "°C", 1),
        "Max. cell voltage" to fmt(m.u16(37022), "mV"), "Min. cell voltage" to fmt(m.u16(37023), "mV"),
    )
}
