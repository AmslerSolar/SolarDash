// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.schneider

import ch.amslersolar.dashboard.driver.DeviceDriver
import ch.amslersolar.dashboard.driver.Details.bits
import ch.amslersolar.dashboard.driver.Details.fmt
import ch.amslersolar.dashboard.driver.Details.section
import ch.amslersolar.dashboard.driver.modbus.ModbusTcpClient
import ch.amslersolar.dashboard.driver.sunspec.RegisterReader
import ch.amslersolar.dashboard.model.BatteryState
import ch.amslersolar.dashboard.model.DetailSection
import ch.amslersolar.dashboard.model.DeviceInfo
import ch.amslersolar.dashboard.model.DeviceReading
import ch.amslersolar.dashboard.model.EnergyTotals
import ch.amslersolar.dashboard.model.PhaseReading
import java.io.IOException
import kotlin.math.roundToInt

/**
 * A Schneider Electric Conext XW / XW Pro system (inverter/chargers, MPPT charge controllers, battery monitor or
 * BMS) as one device, through the Conext Gateway / Insight Home's proprietary Modbus TCP server on port 503.
 * Spec: "Conext Gateway Modbus Interface Specification (503)", 990-6271B, firmware v1.07 (docs/specs/schneider/,
 * zip). The gateway's own map (unit id 1) carries system-wide totals, so the other units aren't needed.
 *
 * Unit ids on port 503 per device class (each spec's "Modbus Slave Address Range", assigned first come first served
 * and then tied to the serial number): gateway 1, XW 10–29, MPPT 60 30–49, AGS 50–69, SCP 70–89, SW 90–109,
 * MPPT 80 170–189, battery monitor 190–209, MPPT Disc RS 210–229. Port 502 is the gateway's SunSpec server (not
 * used here).
 *
 * Registers (on-the-wire addresses; the spec's "Reg. No." is 1-based), all uint32 / sint32 unless noted:
 * - Solar: 0x03FA PV total power (W), else 0x0044 PV harvest power. Today 0x0414 PV total energy today (kWh,
 *   ×0.001), else 0x0134 PV input energy today; lifetime 0x041C, else 0x0144.
 * - Grid: 0x0058 grid output power (W, selling) − 0x0052 grid input power (W, buying) = + export, our sign. Each is
 *   unsigned with its direction in the name; 0x03F8 "Grid Net Power" (sint32) has no documented sign and is only
 *   shown in the details. Today: 0x00D4 grid input energy, 0x00EC grid output energy.
 * - Battery: 0x03FC total inverting power (discharge) − 0x03FE total charging power = + discharging, our sign
 *   (else 0x004A DC inverting − 0x0046 DC charging). SoC 0x03C8 (battery bank 1, %), voltage 0x009C (×0.001 V),
 *   temperature 0x009E (×0.01 − 273 °C). 0x0158 "Battery Power Net" (no documented sign): details only.
 * - Home: 0x0066 load power (W); today 0x0104 AC load energy today.
 * - Grid phases: 0x03A8 / 0x03AC / 0x03B0 voltage L1–L3 (×0.001 V), 0x03AA / 0x03AE / 0x03B2 current (sint32
 *   ×0.001 A), frequency 0x004E (×0.01 Hz). Lines without voltage are left out (split-phase systems have two).
 * - The server answers 02 to reads that cross a "blank row" of the map, start or end inside a 32-bit value or touch
 *   an undefined register; such blocks are read field by field (fields always whole).
 * UNVERIFIED: the function code (03 holding registers assumed; the spec doesn't name it), the word order of 32-bit
 * values (high word first assumed; not stated), whether "grid input/output" includes AC-coupled grid-tie inverters
 * on the grid side, and the gateway's Device Name / FGA strings. identify() requires 0x0028 Modbus Address = 1 (the
 * gateway's documented address) and a readable PV/grid block; it can't tell the gateway from a look-alike.
 */
class ConextGatewayDriver(reader: RegisterReader, unitId: Int) : DeviceDriver {
    constructor(client: ModbusTcpClient, unitId: Int) : this(RegisterReader { u, r, c -> client.readHoldingRegisters(u, r, c) }, unitId)

    private val fetcher = RegisterFetcher(reader, unitId)
    private var static: Map<Int, Int>? = null
    private var polls = 0

    override suspend fun identify(): DeviceInfo {
        val img = Registers(fetcher.fetch(STATIC_FIELDS + LIVE_FIELDS), lowWordFirst = false)
        if (img.u16(0x0028) != GATEWAY_ADDRESS) throw IOException("Not a Conext Gateway (Modbus address register ${img.u16(0x0028)})")
        if (img.u32(0x0044) == null && img.u32(0x0052) == null) throw IOException("Not a Conext Gateway (no PV / grid registers)")
        val name = listOfNotNull(img.text(0x0000, 8), img.text(0x000A, 8)?.let { "($it)" }).joinToString(" ")
        return DeviceInfo(model = name.ifEmpty { "Conext Gateway" }, serial = img.text(0x002B, 8).orEmpty())
    }

    override suspend fun read(): DeviceReading {
        if (static == null || polls % STATIC_REFRESH_POLLS == 0) {
            fetcher.forget()
            static = fetcher.fetch(STATIC_FIELDS)
        }
        polls++
        val img = Registers(static.orEmpty() + fetcher.fetch(LIVE_FIELDS), lowWordFirst = false)
        if (img.u32(0x0044) == null && img.u32(0x03FA) == null && img.u32(0x0052) == null) throw IOException("Conext Gateway: no power registers answered")
        return parse(img).copy(details = details(img))
    }

    internal companion object {
        const val GATEWAY_ADDRESS = 1
        const val STATIC_REFRESH_POLLS = 12

        private fun r(a: Int) = a to 1
        private fun d(a: Int) = a to 2

        val STATIC_FIELDS = listOf(0x0000 to 8, 0x000A to 8, 0x001E to 10, r(0x0028), 0x002B to 8)
        val LIVE_FIELDS = listOf(r(0x0040), r(0x0041), r(0x0042), r(0x0043)) +
            (0x0044..0x006C step 2).map(::d) + listOf(d(0x009C), d(0x009E), d(0x0158)) +
            listOf(0x00A4, 0x00BC, 0x00D4, 0x00E4, 0x00EC, 0x00FC, 0x0104, 0x0114, 0x0134, 0x0144).map(::d) +
            (0x03A8..0x03B2 step 2).map(::d) + listOf(d(0x03C6), d(0x03C8)) +
            listOf(0x03F8, 0x03FA, 0x03FC, 0x03FE, 0x0414, 0x041C, 0x041E).map(::d)

        private val POWER_FLOW = listOf(
            0 to "Grid to load", 1 to "Generator to load", 3 to "Battery to grid", 4 to "Grid to battery",
            5 to "Generator to battery", 6 to "PV to battery", 7 to "PV to grid", 8 to "Battery to load",
        )

        private fun Registers.w(a: Int) = u32(a)?.takeIf { it != 0xFFFFFFFFL }
        private fun Registers.sw(a: Int) = s32(a)?.takeIf { it != Int.MIN_VALUE.toLong() && it != Int.MAX_VALUE.toLong() }
        private fun Registers.milli(a: Int) = w(a)?.div(1000.0)
        private fun Registers.kwh(a: Int) = milli(a)

        fun parse(img: Registers): DeviceReading {
            val pv = img.w(0x03FA) ?: img.w(0x0044)
            val sell = img.w(0x0058)
            val buy = img.w(0x0052)
            val grid = if (sell != null || buy != null) ((sell ?: 0) - (buy ?: 0)).toInt() else null

            val discharge = img.w(0x03FC) ?: img.w(0x004A)
            val charge = img.w(0x03FE) ?: img.w(0x0046)
            val soc = img.w(0x03C8)?.toInt()?.takeIf { it in 0..100 }
            val battery = if (discharge == null && charge == null && soc == null) null else {
                val power = ((discharge ?: 0) - (charge ?: 0)).toInt()
                BatteryState(
                    powerW = power,
                    socPercent = soc,
                    voltageV = img.milli(0x009C),
                    mode = when {
                        power > 0 -> "Discharging"
                        power < 0 -> "Charging"
                        else -> "Idle"
                    },
                )
            }

            val hz = img.w(0x004E)?.div(100.0) ?: 0.0
            val phases = (0 until 3).mapNotNull { i ->
                val v = img.milli(0x03A8 + 4 * i)?.takeIf { it > 0 } ?: return@mapNotNull null
                PhaseReading("L${i + 1}", v, img.sw(0x03AA + 4 * i)?.div(1000.0), hz, null)
            }
            val faults = img.u16(0x0041) ?: 0
            val warnings = img.u16(0x0043) ?: 0
            return DeviceReading(
                pvPowerW = pv?.toInt(),
                battery = battery,
                gridPowerW = grid,
                phases = phases,
                loadPowerW = img.sw(0x0066)?.toInt()?.takeIf { it >= 0 },
                status = when {
                    faults > 0 -> "Active faults: $faults"
                    warnings > 0 -> "Active warnings: $warnings"
                    else -> "OK"
                },
                today = EnergyTotals(
                    pvKwh = img.kwh(0x0414) ?: img.kwh(0x0134),
                    exportKwh = img.kwh(0x00EC),
                    importKwh = img.kwh(0x00D4),
                    loadKwh = img.kwh(0x0104),
                ),
                lifetimePvKwh = img.kwh(0x041C) ?: img.kwh(0x0144),
            )
        }

        /** Battery temperature: ×0.01 − 273 °C. Shown in the details only (the reading's temperature is the inverter's). */
        private fun batteryTemperature(img: Registers) = img.w(0x009E)?.let { (it * 0.01 - 273.0) * 10 }?.roundToInt()?.div(10.0)

        fun details(img: Registers): List<DetailSection> = listOf(
            section(
                "Gateway",
                "Device name" to img.text(0x0000, 8),
                "FGA number" to img.text(0x000A, 8),
                "Firmware" to img.text(0x001E, 10),
                "Serial number" to img.text(0x002B, 8),
                "Active faults" to img.u16(0x0041)?.toString(),
                "Active warnings" to img.u16(0x0043)?.toString(),
                "Generator" to img.u16(0x0042)?.let { if (it == 1) "On" else "Off" },
                "Power flow" to img.u16(0x0040)?.let { m ->
                    POWER_FLOW.filter { (bit, _) -> m and (1 shl bit) != 0 }.joinToString(", ") { it.second }.ifEmpty { "None" } + " (${bits(m.toLong(), 4)})"
                },
            ),
            section(
                "Grid",
                "Buying (grid input)" to fmt(img.w(0x0052), "W"),
                "Selling (grid output)" to fmt(img.w(0x0058), "W"),
                "Grid net power (sign not documented)" to fmt(img.sw(0x03F8), "W"),
                "Voltage" to fmt(img.milli(0x004C), "V", 1),
                "Frequency" to fmt(img.w(0x004E)?.div(100.0), "Hz", 2),
                *(0 until 3).flatMap { i ->
                    listOf(
                        "L${i + 1} voltage" to fmt(img.milli(0x03A8 + 4 * i)?.takeIf { it > 0 }, "V", 1),
                        "L${i + 1} current" to fmt(img.sw(0x03AA + 4 * i)?.div(1000.0)?.takeIf { img.milli(0x03A8 + 4 * i)?.let { v -> v > 0 } == true }, "A", 2),
                    )
                }.toTypedArray(),
                "Bought today" to fmt(img.kwh(0x00D4), "kWh", 2),
                "Sold today" to fmt(img.kwh(0x00EC), "kWh", 2),
                "Bought (lifetime)" to fmt(img.kwh(0x00E4), "kWh", 1),
                "Sold (lifetime)" to fmt(img.kwh(0x00FC), "kWh", 1),
                "Generator power" to fmt(img.w(0x005C), "W"),
            ),
            section(
                "Battery",
                "State of charge (bank 1)" to fmt(img.w(0x03C8), "%"),
                "State of health (bank 1)" to fmt(img.w(0x041E), "%"),
                "Discharging (inverting) power" to fmt(img.w(0x03FC) ?: img.w(0x004A), "W"),
                "Charging power" to fmt(img.w(0x03FE) ?: img.w(0x0046), "W"),
                "Net power (sign not documented)" to fmt(img.sw(0x0158), "W"),
                "Bank 1 power (sign not documented)" to fmt(img.sw(0x03C6), "W"),
                "Voltage" to fmt(img.milli(0x009C), "V", 2),
                "Charging current" to fmt(img.milli(0x0048), "A", 1),
                "Temperature" to fmt(batteryTemperature(img), "°C", 1),
                "Discharged today" to fmt(img.kwh(0x00A4), "kWh", 2),
                "Charged today" to fmt(img.kwh(0x00BC), "kWh", 2),
            ),
            section(
                "PV and loads",
                "PV total power" to fmt(img.w(0x03FA), "W"),
                "PV harvest power (charge controllers)" to fmt(img.w(0x0044), "W"),
                "PV today" to fmt(img.kwh(0x0414) ?: img.kwh(0x0134), "kWh", 2),
                "PV (lifetime)" to fmt(img.kwh(0x041C) ?: img.kwh(0x0144), "kWh", 1),
                "Load power" to fmt(img.sw(0x0066), "W"),
                "Load voltage" to fmt(img.milli(0x0068), "V", 1),
                "Load frequency" to fmt(img.w(0x006A)?.div(100.0), "Hz", 2),
                "Load today" to fmt(img.kwh(0x0104), "kWh", 2),
                "Load (lifetime)" to fmt(img.kwh(0x0114), "kWh", 1),
            ),
        ).filter { it.rows.isNotEmpty() }
    }
}
