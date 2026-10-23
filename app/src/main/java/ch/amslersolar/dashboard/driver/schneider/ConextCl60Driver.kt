// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.schneider

import ch.amslersolar.dashboard.driver.DeviceDriver
import ch.amslersolar.dashboard.driver.Details.bits
import ch.amslersolar.dashboard.driver.Details.enum
import ch.amslersolar.dashboard.driver.Details.fmt
import ch.amslersolar.dashboard.driver.Details.section
import ch.amslersolar.dashboard.driver.modbus.ModbusTcpClient
import ch.amslersolar.dashboard.driver.sunspec.RegisterReader
import ch.amslersolar.dashboard.model.DetailSection
import ch.amslersolar.dashboard.model.DeviceInfo
import ch.amslersolar.dashboard.model.DeviceReading
import ch.amslersolar.dashboard.model.EnergyTotals
import ch.amslersolar.dashboard.model.PhaseReading
import ch.amslersolar.dashboard.model.PvString
import java.io.IOException

/**
 * Schneider Electric Conext CL 60E / CL 60A (PV only) over its vendor registers ("Modbus Registers Map – Conext
 * CL 60", 503-0269-01-01 rev 3.0, docs/specs/schneider/). Port 502; slave address 1–246, default 1.
 *
 * Why not SunSpec: the CL-60's SunSpec model 103 (0x9C86) reports W, VA and VAr with scale factor 2 / 1 (100 W
 * resolution) and DC power in 100 W steps. The vendor "operation variables" give 1 W.
 * - Function code 04 (operation variables are read-only input registers); addresses as written in the map (hex,
 *   0-based, as SunSpec's 0x9C40 = 40000 shows). U32/S32 are "little-endian for double-word data": low word at the
 *   lower address. Strings UTF-8, high byte first.
 * - Identity: 0x1387 device model code 0x1061 = CL60A, 0x1062 = CL60E (identify() throws IOException for anything
 *   else), serial 0x137D (STR*20 = 10 registers), firmware 0x0A15 (LCD) / 0x0A24 (DSP), 15 registers each.
 * - Solar: 0x1398 total DC power (U32, W). AC: 0x13A6 total active power (U32, W), 0x13A4 VA, 0x13A8 var (S32),
 *   0x13AA PF (1/1000), 0x13AB grid frequency (Hz/10), phases 0x139A–C voltage (V/10) and 0x139D–F current (A/10);
 *   0x1389 wiring 1 = 3P/4W (phase voltages), 2 = 3P/3W (line-to-line; then no per-phase readings).
 * - Energy: 0x138A today (U16, kWh/10, a real daily counter), 0x138B lifetime (U32, kWh), 0x1407 month (kWh/10).
 * - State 0x13AD (0x0000 run … 0x5500 fault); fault/alarm code 0x13B4 when the state is fault or alarm.
 * - No grid meter: grid and home come from another device of the plant.
 * UNVERIFIED: whether the inverter accepts reads that span the map's undocumented registers (if not, the fields are
 * read one by one), and its unit id when reached through a Modbus TCP gateway.
 */
class ConextCl60Driver(reader: RegisterReader, private val unitId: Int) : DeviceDriver {
    constructor(client: ModbusTcpClient, unitId: Int) : this(RegisterReader { u, r, c -> client.readInputRegisters(u, r, c) }, unitId)

    private val fetcher = RegisterFetcher(reader, unitId)
    private var static: Map<Int, Int>? = null
    private var polls = 0

    override suspend fun identify(): DeviceInfo {
        val img = Registers(fetcher.fetch(STATIC_FIELDS), lowWordFirst = true)
        val model = MODELS[img.u16(MODEL_CODE)] ?: throw IOException("Not a Conext CL-60 (device model code ${img.u16(MODEL_CODE)?.let { bits(it.toLong(), 4) }})")
        return DeviceInfo(model = model, serial = img.text(0x137D, 10).orEmpty())
    }

    override suspend fun read(): DeviceReading {
        if (static == null || polls % STATIC_REFRESH_POLLS == 0) {
            fetcher.forget()
            static = fetcher.fetch(STATIC_FIELDS)
        }
        polls++
        val img = Registers(static.orEmpty() + fetcher.fetch(LIVE_FIELDS), lowWordFirst = true)
        if (img.u16(0x13A6) == null && img.u16(0x1398) == null) throw IOException("Conext CL-60: no power registers answered")
        return parse(img).copy(details = details(img))
    }

    internal companion object {
        const val STATIC_REFRESH_POLLS = 12
        const val MODEL_CODE = 0x1387
        val MODELS = mapOf(0x1061 to "Conext CL 60A", 0x1062 to "Conext CL 60E")

        private fun r(a: Int) = a to 1
        private fun d(a: Int) = a to 2

        val STATIC_FIELDS = listOf(0x0A15 to 15, 0x0A24 to 15, d(0x1355), d(0x1357), 0x137D to 10, r(0x1387), r(0x1388), r(0x1389), r(0x13B8))
        val LIVE_FIELDS = listOf(
            r(0x138A), d(0x138B), d(0x138D), r(0x138F), r(0x1392), r(0x1393), d(0x1398),
            r(0x139A), r(0x139B), r(0x139C), r(0x139D), r(0x139E), r(0x139F),
            d(0x13A4), d(0x13A6), d(0x13A8), r(0x13AA), r(0x13AB), r(0x13AD), r(0x13B4), r(0x13CE), d(0x13D8), r(0x13F8),
            d(0x1407), r(0x1419), r(0x141A),
        ) + (0x1B64..0x1B71).map(::r)

        val STATES = mapOf(
            0x0000 to "Run", 0x8000 to "Stop", 0x1300 to "Key stop", 0x1500 to "Emergency stop", 0x1400 to "Standby",
            0x1200 to "Initial standby", 0x1600 to "Starting", 0x9100 to "Alarm", 0x8100 to "Derating", 0x8200 to "Dispatch",
            0x5500 to "Fault", 0x2500 to "Communication failure",
        )
        private val WIRING = mapOf(1 to "3-phase 4-wire", 2 to "3-phase 3-wire")

        private fun tenth(v: Int?) = v?.div(10.0)

        fun parse(img: Registers): DeviceReading {
            val dcW = img.u32(0x1398)?.toInt()
            val hz = tenth(img.u16(0x13AB)) ?: 0.0
            val phases = if (img.u16(0x1389) != 1) emptyList() else (0 until 3).mapNotNull { i ->
                val v = tenth(img.u16(0x139A + i))?.takeIf { it > 0 } ?: return@mapNotNull null
                PhaseReading("L${i + 1}", v, tenth(img.u16(0x139D + i)), hz, null)
            }
            val dc1 = tenth(img.u16(0x1392))?.takeIf { it > 0 }?.let { v -> PvString(1, v, tenth(img.u16(0x1393)) ?: 0.0, dcW) }
            return DeviceReading(
                pvPowerW = dcW,
                pvStrings = listOfNotNull(dc1),
                phases = phases,
                temperatureC = img.s16(0x138F)?.div(10.0),
                status = img.u16(0x13AD)?.let { STATES[it] ?: "State 0x${it.toString(16)}" },
                today = EnergyTotals(pvKwh = tenth(img.u16(0x138A))),
                lifetimePvKwh = img.u32(0x138B)?.toDouble(),
            )
        }

        fun details(img: Registers): List<DetailSection> {
            val wiring = img.u16(0x1389)
            val lineToLine = wiring == 2
            return listOf(
                section(
                    "Device",
                    "Model" to MODELS[img.u16(MODEL_CODE)],
                    "Serial number" to img.text(0x137D, 10),
                    "LCD firmware" to img.text(0x0A15, 15),
                    "DSP firmware" to img.text(0x0A24, 15),
                    "Protocol" to img.u32(0x1355)?.let { n -> "$n" + (img.u32(0x1357)?.let { " version $it" } ?: "") },
                    "Nominal power" to fmt(tenth(img.u16(0x1388)), "kW", 1),
                    "Nominal reactive power" to fmt(tenth(img.u16(0x13B8)), "kvar", 1),
                    "Output wiring" to enum(wiring, WIRING),
                    "Operation state" to img.u16(0x13AD)?.let { s -> STATES[s]?.let { "$it (0x${s.toString(16)})" } ?: "0x${s.toString(16)}" },
                    "Operation state bits" to img.u32(0x13D8)?.let { bits(it) },
                    "Fault / alarm code" to img.u16(0x13B4)?.takeIf { img.u16(0x13AD) == 0x5500 || img.u16(0x13AD) == 0x9100 }?.toString(),
                    "Internal temperature" to fmt(img.s16(0x138F)?.div(10.0), "°C", 1),
                    "Insulation impedance" to img.u16(0x13CE)?.takeIf { it != 0xFFFF }?.let { "$it kΩ" },
                    "Operation time today" to fmt(img.u16(0x13F8), "min"),
                    "Operation time (lifetime)" to fmt(img.u32(0x138D), "h"),
                ),
                section(
                    "Inverter (AC)",
                    "Active power" to fmt(img.u32(0x13A6), "W"),
                    "Apparent power" to fmt(img.u32(0x13A4), "VA"),
                    "Reactive power" to fmt(img.s32(0x13A8), "var"),
                    "Power factor (+ leading)" to fmt(img.s16(0x13AA)?.div(1000.0), "", 3),
                    "Grid frequency" to fmt(tenth(img.u16(0x13AB)), "Hz", 1),
                    *(0 until 3).flatMap { i ->
                        val name = if (lineToLine) listOf("L1-L2", "L2-L3", "L3-L1")[i] else "L${i + 1}"
                        listOf(
                            "$name voltage" to fmt(tenth(img.u16(0x139A + i)), "V", 1),
                            "L${i + 1} current" to fmt(tenth(img.u16(0x139D + i)), "A", 1),
                        )
                    }.toTypedArray(),
                ),
                section(
                    "PV",
                    "Total DC power" to fmt(img.u32(0x1398), "W"),
                    "DC voltage" to fmt(tenth(img.u16(0x1392)), "V", 1),
                    "DC current" to fmt(tenth(img.u16(0x1393)), "A", 1),
                    "Bus voltage" to fmt(tenth(img.u16(0x141A)), "V", 1),
                    "Negative voltage to ground" to fmt(img.s16(0x1419)?.div(10.0), "V", 1),
                    *(0 until 14).map { i -> "String ${i + 1} current" to fmt(img.u16(0x1B64 + i)?.div(100.0), "A", 2) }.toTypedArray(),
                    "Yield today" to fmt(tenth(img.u16(0x138A)), "kWh", 1),
                    "Yield this month" to fmt(img.u32(0x1407)?.div(10.0), "kWh", 1),
                    "Yield total" to fmt(img.u32(0x138B), "kWh"),
                ),
            ).filter { it.rows.isNotEmpty() }
        }
    }
}
