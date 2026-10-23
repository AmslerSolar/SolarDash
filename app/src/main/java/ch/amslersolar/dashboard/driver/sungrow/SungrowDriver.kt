// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.sungrow

import ch.amslersolar.dashboard.driver.DeviceDriver
import ch.amslersolar.dashboard.driver.Details.bits
import ch.amslersolar.dashboard.driver.Details.enum
import ch.amslersolar.dashboard.driver.Details.fmt
import ch.amslersolar.dashboard.driver.Details.section
import ch.amslersolar.dashboard.driver.modbus.ModbusException
import ch.amslersolar.dashboard.driver.modbus.ModbusTcpClient
import ch.amslersolar.dashboard.driver.modbus.RegisterBlock
import ch.amslersolar.dashboard.driver.sunspec.RegisterReader
import ch.amslersolar.dashboard.model.BatteryState
import ch.amslersolar.dashboard.model.DetailSection
import ch.amslersolar.dashboard.model.DeviceInfo
import ch.amslersolar.dashboard.model.DeviceProblem
import ch.amslersolar.dashboard.model.DeviceReading
import ch.amslersolar.dashboard.model.EnergyTotals
import ch.amslersolar.dashboard.model.PhaseReading
import ch.amslersolar.dashboard.model.PvString
import kotlinx.coroutines.CancellationException
import java.io.IOException
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Sungrow inverters over Modbus TCP: residential/C&I hybrid inverters (SH…RS, SH…RT, SH…T, SH…RL, SH…CX, MG…RL,
 * MG…TL) and grid-connected string inverters (SG…RS, SG…RT, SG…CX, SG…KTL …). The family and model come from the
 * device type code (input register 5000), so one device type covers both.
 *
 * Specs (docs/specs/sungrow/): "Communication Protocol of Residential and Small Industrial Hybrid Inverter" V1.1.17
 * (2026-09-30) and "Communication Protocol of Residential & Commercial PV Grid-Connected Inverters" V1.1.80
 * (2026-03-27). Section numbers below are the same in both unless noted.
 * - Connection (§1, §1.2): the inverter's own Ethernet port where fitted ("Ethernet (optional), Modbus TCP, Default
 *   DHCP Enable, Port: 502") or the Ethernet port of a WiNet-S / WiNet-S2 / Logger1000 — not iHomeManager or other
 *   modules. Unit id = the inverter's slave address, "1 - 247 settable, 1 by default". Behind a WiNet-S/Logger the
 *   address used for forwarding is shown in its web UI; that it is 1 for a single inverter is not stated (unverified).
 * - Running data are input registers (function 04, §2.1 Table 3), settings holding registers (03, §2.2 Table 4).
 *   The spec numbers registers from 1: "Visit all registers by subtracting 1 from the register address" (§1.2 4.1),
 *   so register X is requested at X − 1. Only documented (incl. "Reserved") register runs are read; e.g. hybrid
 *   5031–5032 and 13015–13016 are not listed and split the reads.
 * - Encoding (§1.1 Table 1): U16/S16 big-endian; U32/S32 "little-endian for double-word data", i.e. LOW word first
 *   (example: 0x01020304 is sent 03 04 01 02; the hybrid example §4.2 decodes 5004–5005 = 2B F6 00 0B as
 *   73215.0 kWh). Unavailable values (§1.2 4.5): U16 0xFFFF, U32 0xFFFFFFFF, S16 0x7FFF, S32 0x7FFFFFFF, UTF-8 0x00
 *   → null here. Caveat: the grid-connected spec's own example §4.2 (SG60KU-M) shows 5004–5005 / 5006–5007 high
 *   word first; the driver follows the table definition (unverified on old KTL/KU models).
 * - Request rate: RW registers "do not support frequent periodic reading" through WiNet-S/WiNet-S2/Logger1000
 *   (§1.2 4.6), so settings are read once every [SETTINGS_REFRESH_POLLS] polls. Per poll the driver reads 2 blocks
 *   (string) or 6–7 blocks (hybrid) plus one rotating detail block.
 *
 * Signs (ours: grid > 0 export, battery > 0 discharging):
 * - Hybrid grid: Meter Active Power 5601 is documented "> 0 buy from grid; < 0 sell to grid" → grid = −5601, same for
 *   the per-phase meter powers 5603–5607. Meter and load values (5601–5607, 13008–13011, 13036–13047, 13005–13007,
 *   13017–13019) are only valid with a directly connected smart meter (Table 3 note 1); without one 5601 is expected
 *   to read 0x7FFFFFFF (not-available rule), and grid/load are then null. Unverified: that a meter-less inverter
 *   really returns the marker and not 0.
 * - Hybrid battery: Battery power 5214–5215 (S32, recommended over 13022) has no documented sign. Its magnitude is
 *   used and the direction comes from Power Flow Status 13001 (Appendix 3: bit 1 charging, bit 2 discharging), which
 *   is documented. Neither bit set → 0 W.
 * - String inverter meter: Meter power 5083–5084 has NO documented sign. Default assumption: like the hybrid's 5601,
 *   > 0 = import (unverified). When meter, load (5091) and AC power (5031) are all present, the driver checks which
 *   reading satisfies load = AC power + import and switches convention if the readings clearly say otherwise.
 * - PV power: hybrid = Total DC power 5017 ("Total PV power"); string inverter = Total active power 5031 (AC output,
 *   what reaches the house), falling back to 5017.
 */
class SungrowDriver(private val input: RegisterReader, private val holding: RegisterReader, private val unitId: Int) : DeviceDriver {
    constructor(client: ModbusTcpClient, unitId: Int) : this(
        RegisterReader { u, r, c -> client.readInputRegisters(u, r, c) },
        RegisterReader { u, r, c -> client.readHoldingRegisters(u, r, c) },
        unitId,
    )

    enum class Family { HYBRID, STRING }

    /** Hybrid series as grouped in the hybrid spec's Appendix 1 (they differ in which registers exist). */
    enum class Series(val label: String) {
        SH_RS("SH3.0-10RS"), SH_RT("SH5.0-10RT"), SH_T("SH5-25T"), MG_RL("MG5-12RL"), SH_RL("SH3-10RL"),
        SH_CX("SH50-125CX"), MG_TL("MG10-25TL"),
    }

    /**
     * One row of Appendix 1. [mppts]: MPPT inputs; [stringCurrents]: string current registers 7013–7036 exist
     * (string/MPPT not 1, grid-connected spec Appendix 1 note 2). [known] false: a code from a Sungrow range that the
     * specs don't list (newer model).
     */
    data class ModelInfo(
        val name: String,
        val family: Family,
        val series: Series?,
        val mppts: Int,
        val stringCurrents: Boolean = false,
        val known: Boolean = true,
    )

    /** A run of documented registers, by spec (1-based) number. */
    private data class Seg(val start: Int, val count: Int, val holding: Boolean = false, val interval: Int = DETAIL_REFRESH_POLLS)

    private var model: ModelInfo? = null
    private var typeCode: Int? = null
    private var polls = 0
    private val unsupported = mutableSetOf<Seg>()
    private val detail = linkedMapOf<Seg, RegisterBlock>()
    private val lastRead = mutableMapOf<Seg, Int>()
    private var meterVotes = 0 // string inverters: > 0 means 5083 > 0 is import (default), < 0 export

    override suspend fun identify(): DeviceInfo {
        val (m, r) = identity()
        return DeviceInfo(model = m.name, serial = r.text(4990, 10).orEmpty())
    }

    /** Reads SN + type code (4990–5030 exist in both specs) and rejects devices that aren't Sungrow inverters. */
    private suspend fun identity(): Pair<ModelInfo, Regs> {
        val r = Regs(listOf(fetch(Seg(4990, 41))))
        val code = r.u16(5000) ?: throw IOException("No device type code at input register 5000: not a Sungrow inverter")
        val m = modelFor(code)
            ?: throw IOException("Device type code 0x%04X is not in the Sungrow protocol specs: not a Sungrow inverter".format(code))
        val serial = r.text(4990, 10)
        if (serial == null || serial.length < 4 || serial.any { it < ' ' || it > '~' }) {
            throw IOException("No valid serial number at input registers 4990–4999: not a Sungrow inverter")
        }
        model = m
        typeCode = code
        return m to r
    }

    override suspend fun read(): DeviceReading {
        val m = model ?: identity().first
        if (polls % UNSUPPORTED_RETRY_POLLS == 0) unsupported.clear()
        val blocks = mutableListOf<RegisterBlock>()
        for (seg in liveSegments(m)) blocks += fetch(seg)
        for (seg in optionalLiveSegments(m)) optional(seg)?.let { blocks += it }
        readDetails(m)
        polls++

        val live = Regs(blocks + detail.filterKeys { !it.holding }.values)
        val settings = Regs(detail.filterKeys { it.holding }.values)
        live.u16(5000)?.let { code -> // follows a replaced inverter behind the same address
            if (code != typeCode) modelFor(code)?.let { model = it; typeCode = code }
        }
        val current = model ?: m
        return when (current.family) {
            Family.HYBRID -> hybridReading(current, live, settings)
            Family.STRING -> stringReading(current, live, settings)
        }
    }

    private fun liveSegments(m: ModelInfo): List<Seg> = when (m.family) {
        // Hybrid Table 3: 4990–5030 SN … phase voltages; 13000–13014 and 13017–13079 (13015–13016 not listed).
        Family.HYBRID -> listOf(Seg(4990, 41), Seg(13000, 15), Seg(13017, 63))
        // Grid-connected Table 3: 4990–5151 are all documented; 125 per request at most.
        Family.STRING -> listOf(Seg(4990, 125))
    }

    private fun optionalLiveSegments(m: ModelInfo): List<Seg> = when (m.family) {
        Family.HYBRID -> listOfNotNull(
            if (m.mppts > 3) Seg(5115, 19) else null, // MPPT 4–10 (5125–5129 reserved)
            Seg(5214, 2), // battery power
            Seg(5242, 1), // grid frequency 0.01 Hz
            Seg(5601, 8), // meter active power, phases A–C
        )
        Family.STRING -> listOf(Seg(5115, 37)) // MPPT 4–12, total yield 0.1 kWh, bus voltage, frequency, PID
    }

    /** Detail-only blocks: all on the first poll, then the most overdue one per poll. */
    private fun detailSegments(m: ModelInfo): List<Seg> = when (m.family) {
        Family.HYBRID -> listOfNotNull(
            Seg(4952, 2, interval = STATIC_REFRESH_POLLS), // protocol version ("forwarding via Logger is not supported")
            Seg(4954, 30, interval = STATIC_REFRESH_POLLS), // ARM / DSP certification versions
            Seg(5033, 4), Seg(5622, 2), Seg(5628, 1), Seg(5631, 1), Seg(5635, 2), Seg(5639, 1), Seg(5720, 8), Seg(5731, 4),
            if (m.series == Series.SH_RT || m.series == Series.SH_T) Seg(13200, 8) else null, // meter channel 2
            if (m.series in FIRMWARE_INFO_SERIES) Seg(13250, 45, interval = STATIC_REFRESH_POLLS) else null,
            Seg(13050, 3, holding = true, interval = SETTINGS_REFRESH_POLLS),
            Seg(13058, 2, holding = true, interval = SETTINGS_REFRESH_POLLS),
            Seg(13074, 2, holding = true, interval = SETTINGS_REFRESH_POLLS),
            Seg(13086, 5, holding = true, interval = SETTINGS_REFRESH_POLLS),
            Seg(13100, 1, holding = true, interval = SETTINGS_REFRESH_POLLS),
        )
        Family.STRING -> listOfNotNull(
            Seg(4950, 34, interval = STATIC_REFRESH_POLLS), // protocol no./version, ARM / DSP software versions
            if (m.stringCurrents) Seg(7013, 24) else null,
            Seg(5000, 43, holding = true, interval = SETTINGS_REFRESH_POLLS), // 5000–5042, all documented
        )
    }

    private suspend fun readDetails(m: ModelInfo) {
        val segs = detailSegments(m).filter { it !in unsupported }
        val due = if (polls == 0) segs else {
            segs.filter { s -> lastRead[s]?.let { polls - it >= s.interval } ?: true }.minByOrNull { lastRead[it] ?: Int.MIN_VALUE }
                ?.let { listOf(it) } ?: emptyList()
        }
        for (seg in due) {
            lastRead[seg] = polls
            val block = optional(seg)
            if (block != null) detail[seg] = block else if (seg in unsupported) detail.remove(seg)
        }
    }

    /** Reads [seg] at its spec address − 1 and returns it addressed by spec register number. */
    private suspend fun fetch(seg: Seg): RegisterBlock {
        val b = (if (seg.holding) holding else input).read(unitId, seg.start - 1, seg.count)
        val data = ByteArray(seg.count * 2)
        for (i in 0 until seg.count) {
            val v = b.u16(b.start + i)
            data[2 * i] = (v shr 8).toByte(); data[2 * i + 1] = v.toByte()
        }
        return RegisterBlock(seg.start, data)
    }

    /** Optional blocks: illegal function/address/value → not on this model (retried hourly); other errors → skipped once. */
    private suspend fun optional(seg: Seg): RegisterBlock? {
        if (seg in unsupported) return null
        return try {
            fetch(seg)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModbusException) {
            if (e.code in 1..3) unsupported += seg
            null
        } catch (_: IOException) {
            null
        }
    }

    /** Register values by spec number; U32/S32 low word first; not-available markers → null. */
    internal class Regs(private val blocks: Collection<RegisterBlock>) {
        private fun raw(r: Int): Int? = blocks.firstOrNull { r >= it.start && r < it.start + it.count }?.u16(r)
        fun u16(r: Int): Int? = raw(r)?.takeIf { it != 0xFFFF }
        fun s16(r: Int): Int? = raw(r)?.toShort()?.toInt()?.takeIf { it != 0x7FFF }
        fun u32(r: Int): Long? {
            val lo = raw(r) ?: return null
            val hi = raw(r + 1) ?: return null
            return ((hi.toLong() shl 16) or lo.toLong()).takeIf { it != 0xFFFFFFFFL }
        }
        fun s32(r: Int): Int? {
            val lo = raw(r) ?: return null
            val hi = raw(r + 1) ?: return null
            return ((hi shl 16) or lo).takeIf { it != Int.MAX_VALUE }
        }
        /** UTF-8, high byte first, padded with 0x00. */
        fun text(r: Int, n: Int): String? {
            val bytes = ByteArray(n * 2)
            for (i in 0 until n) {
                val v = raw(r + i) ?: return null
                bytes[2 * i] = (v shr 8).toByte(); bytes[2 * i + 1] = v.toByte()
            }
            val end = bytes.indexOf(0.toByte()).let { if (it < 0) bytes.size else it }
            return String(bytes, 0, end, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
        }
    }

    // ---------------------------------------------------------------- hybrid

    private fun hybridReading(m: ModelInfo, r: Regs, s: Regs): DeviceReading {
        val flow = r.u16(13001)
        val meterW = r.s32(5601) // > 0 buy from grid (documented)
        val hasMeter = meterW != null
        val strings = pvStrings(m, r)
        val hz = r.u16(5242)?.times(0.01) ?: r.u16(5036)?.times(0.1)
        val phases = phases(r.u16(5002), hz, { 5019 + it }, { r.s16(13031 + it)?.let { a -> abs(a) * 0.1 } }, r)
        return DeviceReading(
            pvPowerW = r.u32(5017)?.toInt(),
            pvStrings = strings,
            battery = hybridBattery(m, r, flow),
            gridPowerW = meterW?.let { -it },
            phases = phases,
            loadPowerW = if (hasMeter) r.s32(13008) else null,
            temperatureC = r.s16(5008)?.times(0.1),
            status = r.u16(13000)?.let { RUNNING_STATES[it] ?: "State ${hex(it)}" },
            today = EnergyTotals(
                pvKwh = r.u16(13002)?.times(0.1),
                exportKwh = if (hasMeter) r.u16(13045)?.times(0.1) else null,
                importKwh = if (hasMeter) r.u16(13036)?.times(0.1) else null,
            ),
            lifetimePvKwh = r.u32(13003)?.times(0.1),
            details = hybridDetails(m, r, s, flow),
            problems = hybridProblems(r),
        )
    }

    private fun hybridBattery(m: ModelInfo, r: Regs, flow: Int?): BatteryState? {
        val magnitude = r.s32(5214)?.let(::abs) ?: (if (m.series == Series.SH_RS) r.s16(13022)?.let(::abs) else r.u16(13022))
        val soc = r.u16(13023)?.times(0.1)
        val volts = r.u16(13020)?.times(0.1)
        if ((volts ?: 0.0) <= 0.0 && (soc ?: 0.0) <= 0.0 && (magnitude ?: 0) == 0) return null // no battery
        val charging = flow != null && flow and FLOW_CHARGING != 0
        val discharging = flow != null && flow and FLOW_DISCHARGING != 0
        val power = when {
            discharging && !charging -> magnitude ?: 0
            charging && !discharging -> -(magnitude ?: 0)
            else -> 0
        }
        val mode = when {
            flow == null -> "Unknown"
            discharging && !charging -> "Discharging"
            charging && !discharging -> "Charging"
            else -> "Idle"
        }
        return BatteryState(power, soc?.roundToInt(), volts, mode)
    }

    private fun hybridDetails(m: ModelInfo, r: Regs, s: Regs, flow: Int?): List<DetailSection> {
        val cx = m.series == Series.SH_CX
        val outputType = r.u16(5002)
        val lineVoltages = outputType == 2
        return listOf(
            section(
                "Inverter",
                "Model" to m.name,
                "Series" to m.series?.label,
                "Device type code (5000)" to typeCode?.let(::hex),
                "Serial number" to r.text(4990, 10),
                "Nominal output power" to fmt(r.u16(5001)?.times(0.1), "kW", 1),
                "Output type" to enum(outputType, OUTPUT_TYPES),
                "Running state (13000)" to r.u16(13000)?.let { "${RUNNING_STATES[it] ?: "Unknown"} (${hex(it)})" },
                "Power flow (13001)" to flow?.let { f -> FLOW_BITS.filter { (bit, _) -> f and (1 shl bit) != 0 }.joinToString(", ") { it.second }.ifEmpty { "None" } + " (${hex(f)})" },
                "DRM state" to r.u16(13043)?.let { if (it in 1..9) "DRM${it - 1}" else "Invalid ($it)" },
                "Inside temperature" to fmt(r.s16(5008)?.times(0.1), "°C", 1),
                "Protocol version" to r.u32(4952)?.let(::protocolVersion),
                "ARM certification version" to r.text(4954, 15),
                "DSP certification version" to r.text(4969, 15),
                "Inverter firmware" to r.text(13250, 15),
                "Communication module firmware" to r.text(13265, 15),
                "Battery firmware" to r.text(13280, 15),
            ),
            section(
                "PV",
                "Total DC power" to fmt(r.u32(5017), "W"),
                *mpptRows(m, r),
                "Today" to kwh(r.u16(13002)?.times(0.1)),
                "Total" to kwh(r.u32(13003)?.times(0.1)),
            ),
            section(
                "AC output",
                *(0..2).map { i ->
                    val label = if (lineVoltages) listOf("A-B", "B-C", "C-A")[i] + " line voltage" else "Phase ${"ABC"[i]} voltage"
                    label to fmt(r.u16(5019 + i)?.times(0.1), "V", 1)
                }.toTypedArray(),
                *(0..2).map { i -> "Phase ${"ABC"[i]} current" to fmt(r.s16(13031 + i)?.times(0.1), "A", 1) }.toTypedArray(),
                "Total active power (13034)" to fmt(r.s32(13034), "W"),
                "Reactive power" to fmt(r.s32(5033), "var"),
                "Power factor (> 0 leading)" to fmt(r.s16(5035)?.times(0.001), "", 3),
                "Grid frequency" to fmt(r.u16(5242)?.times(0.01) ?: r.u16(5036)?.times(0.1), "Hz", 2),
                "Output energy today (PV + battery)" to kwh(r.u16(5003)?.times(0.1)),
                "Output energy total" to kwh(r.u32(5004)?.times(if (cx) 1.0 else 0.1)),
            ),
            section(
                "Battery",
                "Power (5214, magnitude)" to fmt(r.s32(5214)?.let(::abs), "W"),
                "Direction (power flow bits)" to hybridBattery(m, r, flow)?.mode,
                "Current" to fmt(r.s16(5631)?.times(0.1), "A", 1),
                "Voltage" to fmt(r.u16(13020)?.times(0.1), "V", 1),
                "State of charge" to fmt(r.u16(13023)?.times(0.1), "%", 1),
                "State of health" to fmt(r.u16(13024)?.times(0.1), "%", 1),
                "Temperature" to fmt(r.s16(13025)?.times(0.1), "°C", 1),
                "Capacity" to (fmt(r.u16(5639)?.times(0.01), "kWh", 2) ?: fmt(r.u16(13039)?.times(0.1), "kWh (Li-ion; Ah ×10 for lead-acid)", 1)),
                "BDC rated power" to fmt(r.u16(5628)?.times(0.1), "kW", 1),
                "Max. charging current (BMS)" to fmt(r.u16(5635), "A"),
                "Max. discharging current (BMS)" to fmt(r.u16(5636), "A"),
                "Charged today" to kwh(r.u16(13040)?.times(0.1)),
                "Charged total" to kwh(r.u32(13041)?.times(0.1)),
                "Charged from PV today" to kwh(r.u16(13012)?.times(0.1)),
                "Charged from PV total" to kwh(r.u32(13013)?.times(0.1)),
                "Discharged today" to kwh(r.u16(13026)?.times(0.1)),
                "Discharged total" to kwh(r.u32(13027)?.times(0.1)),
            ),
            section(
                "Grid meter (only with a directly connected smart meter)",
                "Meter active power (5601, > 0 import)" to fmt(r.s32(5601), "W"),
                *(0..2).map { i -> "Meter phase ${"ABC"[i]} power (> 0 import)" to fmt(r.s32(5603 + 2 * i), "W") }.toTypedArray(),
                "Export power (13010)" to fmt(r.s32(13010), "W"),
                "Load power" to fmt(r.s32(13008), "W"),
                "Imported today" to kwh(r.u16(13036)?.times(0.1)),
                "Imported total" to kwh(r.u32(13037)?.times(0.1)),
                "Exported today" to kwh(r.u16(13045)?.times(0.1)),
                "Exported total" to kwh(r.u32(13046)?.times(0.1)),
                "Exported from PV today" to kwh(r.u16(13005)?.times(0.1)),
                "Exported from PV total" to kwh(r.u32(13006)?.times(0.1)),
                "PV used directly today" to kwh(r.u16(13017)?.times(0.1)),
                "PV used directly total" to kwh(r.u32(13018)?.times(0.1)),
                "Self-consumption today" to fmt(r.u16(13029)?.times(0.1), "%", 1),
                "Channel 2 total power" to fmt(r.s32(13200), "W"),
                *(0..2).map { i -> "Channel 2 phase ${"ABC"[i]} power" to fmt(r.s32(13202 + 2 * i), "W") }.toTypedArray(),
                "Min. feed-in limitation" to fmt(r.u16(5622)?.times(0.01), "kW", 2),
                "Max. feed-in limitation" to fmt(r.u16(5623)?.times(0.01), "kW", 2),
            ),
            section(
                "Backup (EPS)",
                *(0..2).flatMap { i ->
                    val p = "ABC"[i]
                    listOf(
                        "Phase $p voltage" to fmt(r.u16(5731 + i)?.times(0.1), "V", 1),
                        "Phase $p current" to fmt(r.s16(5720 + i)?.times(0.1), "A", 1),
                        "Phase $p power" to (if (cx) fmt(r.s16(5723 + i)?.times(0.01), "kW", 2) else fmt(r.s16(5723 + i), "W")),
                    )
                }.toTypedArray(),
                "Total power" to fmt(r.s32(5726), "W"),
                "Frequency" to fmt(r.u16(5734)?.times(0.01), "Hz", 2),
            ),
            section("Faults and alarms (Appendix 4 bit fields)", *HYBRID_FAULTS.map { (reg, name) -> name to r.u32(reg)?.let { bits(it) } }.toTypedArray()),
            section(
                "Settings",
                "EMS mode" to enum(s.u16(13050), EMS_MODES),
                "Charge/discharge command" to enum(s.u16(13051), CHARGE_COMMANDS),
                "Charge/discharge power" to fmt(s.u16(13052), "W"),
                "Max. SOC" to fmt(s.u16(13058)?.times(0.1), "%", 1),
                "Min. SOC" to fmt(s.u16(13059)?.times(0.1), "%", 1),
                "Reserved SOC for backup" to fmt(s.u16(13100), "%"),
                "Off-grid option" to onOff(s.u16(13075)),
                "Feed-in limitation" to onOff(s.u16(13087)),
                "Feed-in limitation value" to fmt(s.u16(13074), "W"),
                "Feed-in limitation ratio" to fmt(s.u16(13088)?.times(0.1), "%", 1),
                "Active power limitation" to onOff(s.u16(13089)),
                "Active power limit ratio" to fmt(s.u16(13090)?.times(0.1), "%", 1),
                "Meter communication detection" to onOff(s.u16(13086)),
            ),
        ).filter { it.rows.isNotEmpty() }
    }

    // ---------------------------------------------------------------- string inverter

    private fun stringReading(m: ModelInfo, r: Regs, s: Regs): DeviceReading {
        val acW = r.s32(5031)
        val meterW = r.s32(5083)
        val loadW = r.s32(5091)
        if (meterW != null && loadW != null && acW != null && abs(meterW) >= SIGN_CHECK_MIN_W) {
            val asImport = abs(acW + meterW - loadW) // 5083 > 0 = import: load = AC + import
            val asExport = abs(acW - meterW - loadW) // 5083 > 0 = export: load = AC − export
            if (asImport + SIGN_CHECK_MARGIN_W < asExport) meterVotes = minOf(meterVotes + 1, MAX_VOTES)
            else if (asExport + SIGN_CHECK_MARGIN_W < asImport) meterVotes = maxOf(meterVotes - 1, -MAX_VOTES)
        }
        val hasMeter = meterW != null
        val hz = r.u16(5148)?.times(0.01) ?: r.u16(5036)?.times(0.1)
        val state = r.u16(5038)
        val status = state?.let { st ->
            val name = WORK_STATES[st] ?: "State ${hex(st)}"
            val code = r.u16(5045)
            if ((st == 0x5500 || st == 0x9100) && code != null) "$name: ${faultName(code)} ($code)" else name
        }
        return DeviceReading(
            pvPowerW = acW?.coerceAtLeast(0) ?: r.u32(5017)?.toInt(),
            pvStrings = pvStrings(m, r),
            gridPowerW = meterW?.let { if (meterImportPositive) -it else it },
            phases = phases(r.u16(5002), hz, { 5019 + it }, { r.u16(5022 + it)?.times(0.1) }, r),
            loadPowerW = if (hasMeter) loadW else null,
            temperatureC = r.s16(5008)?.times(0.1),
            status = status,
            today = EnergyTotals(
                pvKwh = r.u16(5003)?.times(0.1),
                exportKwh = if (hasMeter) r.u32(5093)?.times(0.1) else null,
                importKwh = if (hasMeter) r.u32(5097)?.times(0.1) else null,
            ),
            lifetimePvKwh = r.u32(5144)?.times(0.1) ?: r.u32(5004)?.toDouble(),
            details = stringDetails(m, r, s),
            problems = stringProblems(r),
        )
    }

    private val meterImportPositive get() = meterVotes >= 0

    private fun stringDetails(m: ModelInfo, r: Regs, s: Regs): List<DetailSection> {
        val outputType = r.u16(5002)
        val lineVoltages = outputType == 2
        val state = r.u16(5038)
        val faulted = state == 0x5500 || state == 0x9100
        val ws2 = r.u32(5081)
        return listOf(
            section(
                "Inverter",
                "Model" to m.name,
                "Device type code (5000)" to typeCode?.let(::hex),
                "Serial number" to r.text(4990, 10),
                "Nominal active power" to fmt(r.u16(5001)?.times(0.1), "kW", 1),
                "Nominal reactive power" to fmt(r.u16(5049)?.times(0.1), "kvar", 1),
                "Output type" to enum(outputType, STRING_OUTPUT_TYPES),
                "Work state 1 (5038)" to state?.let { "${WORK_STATES[it] ?: "Unknown"} (${hex(it)})" },
                "Work state 2 (5081)" to ws2?.let { w -> WORK_STATE_2_BITS.filter { (bit, _) -> w and (1L shl bit) != 0L }.joinToString(", ") { it.second }.ifEmpty { "None" } + " (${bits(w)})" },
                "Fault/alarm code" to if (faulted) r.u16(5045)?.let { "${faultName(it)} ($it)" } else null,
                "Fault/alarm time" to if (faulted) dateTime(r, 5039) else null,
                "Internal temperature" to fmt(r.s16(5008)?.times(0.1), "°C", 1),
                "Array insulation resistance" to fmt(r.u16(5071), "kΩ"),
                "Bus voltage" to fmt(r.u16(5147)?.times(0.1), "V", 1),
                "Negative voltage to ground" to fmt(r.s16(5146)?.times(0.1), "V", 1),
                "PID work state" to enum(r.u16(5150), PID_STATES),
                "PID alarm" to r.u16(5151)?.takeIf { it != 0 }?.let { "${PID_ALARMS[it] ?: "Unknown"} ($it)" },
                "Total running time" to fmt(r.u32(5006), "h"),
                "Running time today" to fmt(r.u16(5113), "min"),
                "Country setting" to fmt(r.u16(5114)),
                "Protocol number" to r.u32(4950)?.let { bits(it) },
                "Protocol version" to r.u32(4952)?.let(::protocolVersion),
                "ARM software" to r.text(4954, 15),
                "DSP software" to r.text(4969, 15),
            ),
            section(
                "PV",
                "Total DC power" to fmt(r.u32(5017), "W"),
                *mpptRows(m, r),
                *(0 until 24).mapNotNull { i -> r.u16(7013 + i)?.takeIf { it > 0 }?.let { "String ${i + 1} current" to fmt(it * 0.01, "A", 2) } }.toTypedArray(),
            ),
            section(
                "AC output",
                *(0..2).map { i ->
                    val label = if (lineVoltages) listOf("A-B", "B-C", "C-A")[i] + " line voltage" else "Phase ${"ABC"[i]} voltage"
                    label to fmt(r.u16(5019 + i)?.times(0.1), "V", 1)
                }.toTypedArray(),
                *(0..2).map { i -> "Phase ${"ABC"[i]} current" to fmt(r.u16(5022 + i)?.times(0.1), "A", 1) }.toTypedArray(),
                "Active power" to fmt(r.s32(5031), "W"),
                "Reactive power" to fmt(r.s32(5033), "var"),
                "Apparent power" to fmt(r.u32(5009), "VA"),
                "Power factor (> 0 leading)" to fmt(r.s16(5035)?.times(0.001), "", 3),
                "Grid frequency" to fmt(r.u16(5148)?.times(0.01) ?: r.u16(5036)?.times(0.1), "Hz", 2),
                "Active power setpoint" to fmt(r.u32(5077), "W"),
                "Reactive power setpoint" to fmt(r.s32(5079), "var"),
                "Power factor setpoint" to fmt(r.s16(5125)?.times(0.001), "", 3),
                "Yield today" to kwh(r.u16(5003)?.times(0.1)),
                "Yield this month" to kwh(r.u32(5128)?.times(0.1)),
                "Yield total" to kwh(r.u32(5144)?.times(0.1) ?: r.u32(5004)?.toDouble()),
            ),
            section(
                "Grid meter (only with a smart meter for feed-in limitation)",
                "Meter power (5083, as reported)" to fmt(r.s32(5083), "W"),
                *(0..2).map { i -> "Meter phase ${"ABC"[i]} power (as reported)" to fmt(r.s32(5085 + 2 * i), "W") }.toTypedArray(),
                "Sign used for 5083 (undocumented)" to if (r.s32(5083) != null) (if (meterImportPositive) "> 0 import" else "> 0 export") else null,
                "Load power" to fmt(r.s32(5091), "W"),
                "Feed-in today" to kwh(r.u32(5093)?.times(0.1)),
                "Feed-in total" to kwh(r.u32(5095)?.times(0.1)),
                "Purchased today" to kwh(r.u32(5097)?.times(0.1)),
                "Purchased total" to kwh(r.u32(5099)?.times(0.1)),
            ),
            section(
                "Settings",
                "Start/stop" to s.u16(5006)?.let { when (it) { 0xCF -> "Start"; 0xCE -> "Stop"; else -> hex(it) } },
                "Power limitation" to onOff(s.u16(5007)),
                "Power limitation setting" to fmt(s.u16(5008)?.times(0.1), "%", 1),
                "Power limitation adjustment" to fmt(s.u16(5039)?.times(0.1), "kW", 1),
                "Feed-in power limit" to onOff(s.u16(5010)),
                "Feed-in power limit value" to fmt(s.u16(5011)?.times(0.01), "kW", 2),
                "Feed-in power limit ratio" to fmt(s.u16(5015)?.times(0.1), "%", 1),
                "Active power overload" to onOff(s.u16(5020)),
                "Reactive power mode" to enum(s.u16(5036), REACTIVE_MODES),
                "PF setting" to fmt(s.s16(5019)?.times(0.001), "", 3),
                "Reactive power percentage" to fmt(s.s16(5037)?.times(0.1), "%", 1),
                "Reactive power adjustment" to fmt(s.s16(5040)?.times(0.1), "kvar", 1),
                "Reactive power at night" to fmt(s.s16(5033)?.times(0.1), "%", 1),
                "Night SVG" to onOff(s.u16(5035)),
                "Local / remote control" to enum(s.u16(5034), mapOf(1 to "Remote", 2 to "Local", 3 to "Local & remote")),
                "PID recovery" to onOff(s.u16(5041)),
                "Anti-PID" to onOff(s.u16(5042)),
                "Inverter clock" to dateTime(s, 5000),
            ),
        ).filter { it.rows.isNotEmpty() }
    }

    // ---------------------------------------------------------------- shared

    private fun pvStrings(m: ModelInfo, r: Regs): List<PvString> = MPPT_REGS.take(m.mppts).mapIndexedNotNull { i, base ->
        val v = r.u16(base)?.times(0.1)
        val a = r.u16(base + 1)?.times(0.1)
        if (v != null && (v > 0 || (a ?: 0.0) > 0)) PvString(i + 1, v, a ?: 0.0, null) else null // no per-MPPT power
    }

    private fun mpptRows(m: ModelInfo, r: Regs): Array<Pair<String, String?>> = MPPT_REGS.take(m.mppts).flatMapIndexed { i, base ->
        listOf("MPPT ${i + 1} voltage" to fmt(r.u16(base)?.times(0.1), "V", 1), "MPPT ${i + 1} current" to fmt(r.u16(base + 1)?.times(0.1), "A", 1))
    }.toTypedArray()

    /**
     * Inverter AC phases from 5019–5021 per Output type (5002): 0 = single/two-phase (L1 only), 1 = 3P4L (phase
     * voltages). 2 = 3P3L reports line voltages only, so no phase rows (shown in the details instead).
     */
    private fun phases(outputType: Int?, hz: Double?, voltageReg: (Int) -> Int, current: (Int) -> Double?, r: Regs): List<PhaseReading> {
        val n = when (outputType) { 0 -> 1; 1 -> 3; else -> 0 }
        return (0 until n).mapNotNull { i ->
            r.u16(voltageReg(i))?.times(0.1)?.takeIf { it > 0 }?.let { PhaseReading("L${i + 1}", it, current(i), hz ?: 0.0, null) }
        }
    }

    private fun kwh(v: Double?) = fmt(v, "kWh", 1)

    private fun onOff(v: Int?): String? = v?.let { when (it) { 0xAA -> "Enabled"; 0x55 -> "Disabled"; else -> hex(it) } }

    private fun dateTime(r: Regs, base: Int): String? {
        val parts = (0..5).map { r.u16(base + it) ?: return null }
        return "%04d-%02d-%02d %02d:%02d:%02d".format(parts[0], parts[1], parts[2], parts[3], parts[4], parts[5])
    }

    companion object {
        const val DEFAULT_PORT = 502
        const val DEFAULT_UNIT_ID = 1

        private const val DETAIL_REFRESH_POLLS = 12 // about once a minute
        private const val STATIC_REFRESH_POLLS = 120 // versions and firmware: every 10 minutes
        private const val SETTINGS_REFRESH_POLLS = 60 // RW registers: every 5 minutes (WiNet-S / Logger caveat)
        private const val UNSUPPORTED_RETRY_POLLS = 720 // re-try blocks the device rejected: hourly
        private const val SIGN_CHECK_MIN_W = 100
        private const val SIGN_CHECK_MARGIN_W = 50
        private const val MAX_VOTES = 10
        private const val FLOW_CHARGING = 1 shl 1
        private const val FLOW_DISCHARGING = 1 shl 2

        /** MPPT n voltage register (current = +1): 1–3 at 5011, 4–8 at 5115, 9–12 at 5130. */
        private val MPPT_REGS = listOf(5011, 5013, 5015, 5115, 5117, 5119, 5121, 5123, 5130, 5132, 5134, 5136)
        private val FIRMWARE_INFO_SERIES = setOf(Series.SH_RT, Series.SH_T, Series.SH_CX)

        private fun hex(v: Int) = "0x" + v.toString(16).uppercase().padStart(4, '0')

        /** e.g. 0x01010700 → V1.1.7 (hybrid Table 3 example). */
        private fun protocolVersion(v: Long) = "V${(v shr 24) and 0xFF}.${(v shr 16) and 0xFF}.${(v shr 8) and 0xFF}"

        /** Model for a device type code; codes in Sungrow's hybrid (0x0Dxx/0x0Exx) or SG (0x24xx–0x2Cxx) ranges that the specs don't list yet are accepted as "unknown" models of that family. */
        fun modelFor(code: Int): ModelInfo? = HYBRID_MODELS[code] ?: STRING_MODELS[code] ?: when (code) {
            in 0x0D00..0x0EFF -> ModelInfo("Sungrow hybrid (type ${hex(code)})", Family.HYBRID, null, 3, known = false)
            in 0x2400..0x2CFF -> ModelInfo("Sungrow SG (type ${hex(code)})", Family.STRING, null, 12, stringCurrents = true, known = false)
            else -> null
        }

        /** All device type codes from the specs' Appendix 1 tables. */
        val SUPPORTED_TYPE_CODES: Set<Int> get() = HYBRID_MODELS.keys + STRING_MODELS.keys

        private fun h(name: String, series: Series, mppts: Int) = ModelInfo(name, Family.HYBRID, series, mppts)
        private fun s(name: String, mppts: Int, strings: Boolean = false) = ModelInfo(name, Family.STRING, null, mppts, strings)

        private val SH_RS = Series.SH_RS
        private val SH_RT = Series.SH_RT
        private val SH_T = Series.SH_T
        private val MG_RL = Series.MG_RL
        private val SH_RL = Series.SH_RL
        private val SH_CX = Series.SH_CX
        private val MG_TL = Series.MG_TL

        // Hybrid spec V1.1.17 Appendix 1 Table 10 (code, model, MPPTs).
        val HYBRID_MODELS: Map<Int, ModelInfo> = mapOf(
            0xD17 to h("SH3.0RS", SH_RS, 2), 0xD0D to h("SH3.6RS", SH_RS, 2), 0xD18 to h("SH4.0RS", SH_RS, 2),
            0xD0F to h("SH5.0RS", SH_RS, 2), 0xD10 to h("SH6.0RS", SH_RS, 2), 0xD1A to h("SH8.0RS", SH_RS, 4),
            0xD1B to h("SH10RS", SH_RS, 4), 0xE00 to h("SH5.0RT", SH_RT, 2), 0xE01 to h("SH6.0RT", SH_RT, 2),
            0xE02 to h("SH8.0RT", SH_RT, 2), 0xE03 to h("SH10RT", SH_RT, 2), 0xE10 to h("SH5.0RT-20", SH_RT, 2),
            0xE11 to h("SH6.0RT-20", SH_RT, 2), 0xE12 to h("SH8.0RT-20", SH_RT, 2), 0xE13 to h("SH10RT-20", SH_RT, 2),
            0xE0C to h("SH5.0RT-V112", SH_RT, 2), 0xE0D to h("SH6.0RT-V112", SH_RT, 2), 0xE0E to h("SH8.0RT-V112", SH_RT, 2),
            0xE0F to h("SH10RT-V112", SH_RT, 2), 0xE08 to h("SH5.0RT-V122", SH_RT, 2), 0xE09 to h("SH6.0RT-V122", SH_RT, 2),
            0xE0A to h("SH8.0RT-V122", SH_RT, 2), 0xE0B to h("SH10RT-V122", SH_RT, 2), 0xE20 to h("SH5T", SH_T, 2),
            0xE21 to h("SH6T", SH_T, 2), 0xE22 to h("SH8T", SH_T, 2), 0xE23 to h("SH10T", SH_T, 2),
            0xE24 to h("SH12T", SH_T, 2), 0xE25 to h("SH15T", SH_T, 3), 0xE26 to h("SH20T", SH_T, 3),
            0xE28 to h("SH25T", SH_T, 3), 0xD27 to h("MG5RL", MG_RL, 2), 0xD28 to h("MG6RL", MG_RL, 2),
            0xD31 to h("MG7.5RL", MG_RL, 3), 0xD29 to h("MG8RL", MG_RL, 3), 0xD2A to h("MG10RL", MG_RL, 3),
            0xD2F to h("MG12RL", MG_RL, 3), 0xD41 to h("SH3RL", SH_RL, 2), 0xD42 to h("SH3.6RL", SH_RL, 2),
            0xD43 to h("SH4RL", SH_RL, 2), 0xD2B to h("SH5RL", SH_RL, 2), 0xD2C to h("SH6RL", SH_RL, 2),
            0xD2D to h("SH8RL", SH_RL, 3), 0xD2E to h("SH10RL", SH_RL, 3), 0xE51 to h("SH50CX", SH_CX, 10),
            0xE52 to h("SH80CX", SH_CX, 10), 0xE39 to h("SH100CX", SH_CX, 10), 0xE3A to h("SH110CX", SH_CX, 10),
            0xE3D to h("SH125CX", SH_CX, 10), 0xE53 to h("MG10TL", MG_TL, 3), 0xE54 to h("MG12TL", MG_TL, 3),
            0xE55 to h("MG15TL", MG_TL, 3), 0xE56 to h("MG20TL", MG_TL, 3), 0xE57 to h("MG25TL", MG_TL, 3),
        )

        // Grid-connected spec V1.1.80 Appendix 1 Tables 7–9 (code, model(s) sharing it, MPPTs, string currents).
        val STRING_MODELS: Map<Int, ModelInfo> = mapOf(
            0x243D to s("SG3.0RT-20 / SG3.0RT", 2), 0x243E to s("SG4.0RT-20 / SG4.0RT", 2), 0x2430 to s("SG5.0RT-20 / SG5.0RT", 2),
            0x2431 to s("SG6.0RT-20 / SG6.0RT", 2), 0x243C to s("SG7.0RT-20 / SG7.0RT", 2, strings = true), 0x2432 to s("SG8.0RT-20 / SG8.0RT", 2, strings = true),
            0x2433 to s("SG10RT-20 / SG10RT", 2, strings = true), 0x243A to s("SG11RT-20", 2), 0x2434 to s("SG12RT-20 / SG12RT", 2, strings = true),
            0x2435 to s("SG15RT-20 / SG15RT", 2, strings = true), 0x2436 to s("SG17RT-20 / SG17RT", 2, strings = true), 0x2437 to s("SG20RT-20 / SG20RT", 2, strings = true),
            0x2438 to s("SG22RT-20 / SG22RT", 2, strings = true), 0x243B to s("SG23RT-20 / SG23RT", 2, strings = true), 0x2439 to s("SG25RT-20 / SG25RT", 2, strings = true),
            0x2473 to s("SG10T-CN", 2), 0x2474 to s("SG12T-CN", 2), 0x2475 to s("SG15T-CN", 2),
            0x2476 to s("SG17T-CN", 2, strings = true), 0x2477 to s("SG20T-CN", 2, strings = true), 0x247B to s("SG23T-CN", 2, strings = true),
            0x2479 to s("SG25T-CN", 2, strings = true), 0x247C to s("SG30T-CN", 2, strings = true), 0x2497 to s("SG20T-P2-CN", 2, strings = true),
            0x2498 to s("SG23T-P2-CN", 2, strings = true), 0x2499 to s("SG25T-P2-CN", 2, strings = true), 0x249A to s("SG30T-P2-CN", 3, strings = true),
            0x249B to s("SG33T-P2-CN", 3, strings = true), 0x2C10 to s("SG30CX", 3, strings = true), 0x2C00 to s("SG33CX", 3, strings = true),
            0x2C01 to s("SG40CX", 4, strings = true), 0x2C02 to s("SG50CX", 5, strings = true), 0x2C22 to s("SG75CX", 9, strings = true),
            0x2C05 to s("SG100CX", 9, strings = true), 0x2C06 to s("SG110CX", 9, strings = true), 0x2C23 to s("SG30CX-P2-CN", 3, strings = true),
            0x2C2F to s("SG33CX-P2-CN", 3, strings = true), 0x2C30 to s("SG36CX-P2-CN", 3, strings = true), 0x2C24 to s("SG40CX-P2-CN", 3, strings = true),
            0x2C31 to s("SG50CX-P2-CN", 4, strings = true), 0x2C2C to s("SG110CX-P2-CN", 9, strings = true), 0x2C82 to s("SG100CX-P2-CN", 9, strings = true),
            0x2C4A to s("SG36CX-P2H-CN", 3, strings = true), 0x2C4B to s("SG40CX-P2H-CN", 3, strings = true), 0x2C4C to s("SG50CX-P2H-CN", 4, strings = true),
            0x2C4D to s("SG110CX-P2H-CN", 9, strings = true), 0x2C58 to s("SG150CX-CN-V12", 7, strings = true), 0x2C59 to s("SG150CX-CN-V11", 7, strings = true),
            0x249C to s("SG36CX-P3-CN", 4, strings = true), 0x249D to s("SG40CX-P3-CN", 4, strings = true), 0x249E to s("SG50CX-P3-CN", 4, strings = true),
            0x2C72 to s("SG40TX", 6, strings = true), 0x2C73 to s("SG50TX", 6, strings = true), 0x2C74 to s("SG60TX", 6, strings = true),
            0x2C08 to s("SG136TX", 12, strings = true), 0x2488 to s("SG3.0RT (Australian)", 2), 0x2489 to s("SG4.0RT (Australian)", 2),
            0x247D to s("SG5.0RT (Australian)", 2), 0x247E to s("SG6.0RT (Australian)", 2), 0x2487 to s("SG7.0RT (Australian)", 2, strings = true),
            0x247F to s("SG8.0RT (Australian)", 2, strings = true), 0x2480 to s("SG10RT (Australian)", 2, strings = true), 0x2481 to s("SG12RT (Australian)", 2, strings = true),
            0x2482 to s("SG15RT (Australian)", 2, strings = true), 0x2483 to s("SG17RT (Australian)", 2, strings = true), 0x2484 to s("SG20RT (Australian)", 2, strings = true),
            0x2486 to s("SG23RT (Australian)", 2, strings = true), 0x2485 to s("SG25RT (Australian)", 2, strings = true), 0x244D to s("SG3.0RT-P2", 2),
            0x244E to s("SG4.0RT-P2", 2), 0x2440 to s("SG5.0RT-P2", 2), 0x2441 to s("SG6.0RT-P2", 2),
            0x244C to s("SG7.0RT-P2", 2, strings = true), 0x2442 to s("SG8.0RT-P2", 2, strings = true), 0x2443 to s("SG10RT-P2", 2, strings = true),
            0x2444 to s("SG12RT-P2", 2, strings = true), 0x2445 to s("SG15RT-P2", 2, strings = true), 0x2446 to s("SG17RT-P2", 2, strings = true),
            0x2447 to s("SG20RT-P2", 2, strings = true), 0x244B to s("SG23RT-P2", 2, strings = true), 0x2449 to s("SG25RT-P2", 2, strings = true),
            0x2C15 to s("SG25CX-SA", 3, strings = true), 0x2C21 to s("SG49.5CX-JP", 6, strings = true), 0x2C12 to s("SG100CX-JP", 12, strings = true),
            0x2C0A to s("SG36CX-US", 4, strings = true), 0x2C0B to s("SG60CX-US", 6, strings = true), 0x2C33 to s("SG25CX-P2", 3, strings = true),
            0x2C34 to s("SG30CX-P2", 3, strings = true), 0x2C35 to s("SG33CX-P2", 3, strings = true), 0x2C36 to s("SG36CX-P2", 4, strings = true),
            0x2C37 to s("SG40CX-P2", 4, strings = true), 0x2C32 to s("SG50CX-P2", 4, strings = true), 0x2C3F to s("SG75CX-P2", 8, strings = true),
            0x2C40 to s("SG110CX-P2", 12, strings = true), 0x2C2D to s("SG125CX-P2", 12, strings = true), 0x2C60 to s("SG50CX-P2-JP", 4, strings = true),
            0x2C7F to s("SG100CX-P2", 12, strings = true), 0x2C53 to s("SG15CX-P2-LV", 2, strings = true), 0x2C54 to s("SG20CX-P2-LV", 3, strings = true),
            0x2C55 to s("SG25CX-P2-LV", 3, strings = true), 0x2C75 to s("SG75CX-LV", 7, strings = true), 0x2C80 to s("SG50CX-P2-LV", 8, strings = true),
            0x2C57 to s("SG150CX", 7, strings = true), 0x2C7C to s("SG125CX-P3", 10, strings = true), 0x2C89 to s("SG80CX-P3", 10, strings = true),
            0x2C8A to s("SG110CX-P3", 10, strings = true), 0x2C7D to s("SG110CX-P3-CN A", 8, strings = true), 0x2C7E to s("SG110CX-P3-CN", 8, strings = true),
            0x2C85 to s("SG100CX-P3-CN A", 8, strings = true), 0x2C86 to s("SG100CX-P3-CN", 8, strings = true), 0x0026 to s("SG10KTL", 2, strings = true),
            0x0029 to s("SG12KTL", 2, strings = true), 0x0028 to s("SG15KTL", 2, strings = true), 0x002A to s("SG20KTL", 2, strings = true),
            0x0027 to s("SG30KTL", 2, strings = true), 0x002D to s("SG36KTL", 2, strings = true), 0x002F to s("SG40KTL", 2, strings = true),
            0x0116 to s("SG50KTL", 3, strings = true), 0x010F to s("SG60KTL", 1, strings = true), 0x0138 to s("SG80KTL / SG80KTL-20", 1, strings = true),
            0x013F to s("SG8KTL-M", 2), 0x013E to s("SG10KTL-M", 2), 0x013C to s("SG12KTL-M", 2, strings = true),
            0x0142 to s("SG15KTL-M", 2, strings = true), 0x0149 to s("SG17KTL-M", 2, strings = true), 0x0143 to s("SG20KTL-M", 2, strings = true),
            0x0141 to s("SG30KTL-M", 3, strings = true), 0x0070 to s("SG30KTL-M-V31", 3, strings = true), 0x0134 to s("SG33KTL-M", 3, strings = true),
            0x0074 to s("SG36KTL-M", 3, strings = true), 0x0135 to s("SG40KTL-M", 3, strings = true), 0x011B to s("SG50KTL-M / SG50KTL-M-20", 4, strings = true),
            0x0131 to s("SG60KTL-M", 4, strings = true), 0x0139 to s("SG80KTL-M", 4, strings = true), 0x0147 to s("SG5KTL-MT", 2),
            0x0148 to s("SG6KTL-MT", 2), 0x2C0F to s("SG10KTL-MT", 2, strings = true), 0x002C to s("SG30KU", 2, strings = true),
            0x002E to s("SG36KU", 2, strings = true), 0x0136 to s("SG60KU", 1, strings = true), 0x0132 to s("SG60KU-M", 4, strings = true),
            0x013D to s("SG33K3J", 3, strings = true), 0x0137 to s("SG49K5J", 4, strings = true), 0x0072 to s("SG34KJ", 2, strings = true),
            0x0073 to s("LP_P34KSG", 1, strings = true), 0x2607 to s("SG6.0RS-CN / SG6.0RS", 2), 0x2608 to s("SG8.0RS-CN / SG8.0RS", 3),
            0x2609 to s("SG10RS-CN / SG10RS", 3), 0x2600 to s("SG2.0RS-S", 1), 0x2601 to s("SG2.5RS-S", 1),
            0x2602 to s("SG3.0RS-S", 1), 0x2603 to s("SG3.0RS", 2), 0x2604 to s("SG3.6RS", 2),
            0x2605 to s("SG4.0RS", 2), 0x2606 to s("SG5.0RS", 2), 0x260E to s("SG9.0RS", 3),
            0x260F to s("SG5.0RS-ADA", 3), 0x2612 to s("SG3.0RS-L", 1), 0x2618 to s("SG3.3RS-L", 1),
            0x2613 to s("SG4.0RS-L", 2), 0x2619 to s("SG4.6RS-L", 2), 0x2614 to s("SG5.0RS-L", 2),
            0x2615 to s("SG6.0RS-L", 2), 0x261A to s("SG7.5RS-L", 2, strings = true), 0x2611 to s("SG8.0RS-L", 2, strings = true),
            0x2616 to s("SG9.0RS-L", 3), 0x2617 to s("SG10RS-L", 3), 0x260D to s("SG5.5RS-JP", 3, strings = true),
            0x2622 to s("SG3.0RS-L2", 1), 0x2623 to s("SG4.0RS-L2", 2), 0x2624 to s("SG5.0RS-L2", 2),
            0x2625 to s("SG6.0RS-L2", 2), 0x2626 to s("SG7.5RS-L2", 3), 0x2627 to s("SG8.0RS-L2", 3),
            0x2628 to s("SG10RS-L2", 3), 0x2629 to s("SG3.0RS-L2-IN", 1), 0x262A to s("SG3.3RS-L2-IN", 1),
            0x262C to s("SG3.0RS-L2-S", 1), 0x262D to s("SG3.3RS-L2-S", 1), 0x262E to s("SG4.0RS-L2-S", 1),
        )

        /** Hybrid Appendix 2 (register 13000); several states have two documented values. */
        val RUNNING_STATES = mapOf(
            0x0000 to "Running (on-grid)", 0x0040 to "Running (on-grid)", 0x0014 to "Microgrid operation",
            0x0400 to "Running in maintain mode", 0x0800 to "Running in compulsory mode", 0x1000 to "Running (off-grid)",
            0x2000 to "Open loop", 0x4000 to "Running in external EMS mode", 0x4001 to "Emergency charging",
            0x1111 to "Uninitialized", 0x8000 to "Stop", 0x0001 to "Stop", 0x1300 to "Key stop", 0x0002 to "Key stop",
            0x1500 to "Emergency stop", 0x0004 to "Emergency stop", 0x1400 to "Standby", 0x0008 to "Standby",
            0x1200 to "Initial standby", 0x0010 to "Initial standby", 0x1600 to "Starting", 0x0020 to "Starting",
            0x1800 to "Intelligent station building", 0x9100 to "Warn running", 0x8100 to "Derating running",
            0x0080 to "Derating running", 0x8200 to "Dispatch running", 0x5500 to "Fault", 0x0100 to "Fault",
            0x1700 to "AFCI self-test shutdown", 0x1900 to "Safe mode", 0x0041 to "Off-grid charge", 0x0200 to "Update failed",
            0x2500 to "Communicate fault", 0x2501 to "Restarting",
        )

        /** Hybrid Appendix 3 (register 13001). */
        private val FLOW_BITS = listOf(
            0 to "PV generating", 1 to "Battery charging", 2 to "Battery discharging", 3 to "Load (positive)",
            4 to "Feeding in", 5 to "Importing", 7 to "Load generating (retrofit)",
        )

        private val OUTPUT_TYPES = mapOf(0 to "Single phase", 1 to "3P4L", 2 to "3P3L")
        private val STRING_OUTPUT_TYPES = mapOf(0 to "Two phase", 1 to "3P4L", 2 to "3P3L")
        private val EMS_MODES = mapOf(0 to "Self-consumption", 2 to "Compulsory", 3 to "External EMS", 4 to "VPP")
        private val CHARGE_COMMANDS = mapOf(0xAA to "Charge", 0xBB to "Discharge", 0xCC to "Stop")
        private val REACTIVE_MODES = mapOf(
            0x55 to "Off", 0xA1 to "Power factor", 0xA2 to "Reactive power percentage", 0xA3 to "Q(P) curve", 0xA4 to "Q(U) curve",
        )

        /** Hybrid Table 3 items 123–137: U32 fault/alarm bit fields, decoded in Appendix 4. */
        private val HYBRID_FAULTS = listOf(
            13050 to "Inverter alarm", 13052 to "Grid-side fault", 13054 to "System fault 1", 13056 to "System fault 2",
            13058 to "DC-side fault", 13060 to "Permanent fault", 13062 to "BDC-side fault", 13064 to "BDC-side permanent fault",
            13066 to "Battery fault", 13068 to "Battery alarm", 13070 to "BMS alarm", 13072 to "BMS protection",
            13074 to "BMS fault 1", 13076 to "BMS fault 2", 13078 to "BMS alarm 2",
        )

        /** Grid-connected Appendix 2 (register 5038). */
        val WORK_STATES = mapOf(
            0x0000 to "Run", 0x8000 to "Stop", 0x1300 to "Key stop", 0x1500 to "Emergency stop", 0x1400 to "Standby",
            0x1200 to "Initial standby", 0x1600 to "Starting", 0x9100 to "Alarm run", 0x8100 to "Derating run",
            0x8200 to "Dispatch run", 0x5500 to "Fault", 0x2500 to "Communicate fault", 0x1111 to "Uninitialized",
        )

        /** Grid-connected Appendix 3 (register 5081–5082). */
        private val WORK_STATE_2_BITS = listOf(
            0 to "Run", 1 to "Stop", 2 to "Initial standby", 3 to "Key stop", 4 to "Standby", 5 to "Emergency stop",
            6 to "Starting", 9 to "Fault", 10 to "Alarm run", 11 to "Derating run", 12 to "Dispatch run",
            13 to "Communicate fault", 17 to "Total run", 18 to "Total fault",
        )

        private val PID_STATES = mapOf(2 to "PID recover operation", 4 to "Anti-PID operation", 8 to "PID abnormity")
        private val PID_ALARMS = mapOf(
            432 to "PID impedance abnormity", 433 to "PID function abnormity", 434 to "PID overvoltage/overcurrent protection",
        )

        /** Grid-connected Appendix 4 (fault/alarm code 5045), as code lists and ranges. */
        private val FAULT_CODES: List<Pair<List<IntRange>, String>> = listOf(
            "2,3,14,15" to "Grid overvoltage",
            "4,5" to "Grid undervoltage",
            "8" to "Grid overfrequency",
            "9" to "Grid underfrequency",
            "10" to "Grid power outage",
            "12" to "Excess leakage current",
            "13" to "Grid abnormal",
            "17" to "Grid voltage imbalance",
            "28,29,208,448-479" to "PV reverse connection fault",
            "532-547,564-579" to "PV reverse connection alarm",
            "548-563,580-595" to "PV abnormal alarm",
            "37" to "Excessively high ambient temperature",
            "43" to "Excessively low ambient temperature",
            "39" to "Low system insulation resistance",
            "106" to "Grounding cable fault",
            "88" to "Electric arc fault",
            "84" to "Reverse connection alarm of the meter/CT",
            "514" to "Meter communication abnormal alarm",
            "323" to "Grid confrontation",
            "75" to "Inverter parallel communication alarm",
            "7,11,16,19-25,30-34,36,38,40-42,44-50,52-58,60-68,85,87,92,93,100-105,107-114,116-124,200-211,248-255," +
                "300-322,324-326,401-412,600-603,605,608,612,616,620,622-624,681,800,802,804,807,1096-1122" to "System fault",
            "59,70-72,74,76,82,83,89,77-81,216-218,220-231,432-434,500-513,515-518,900,901,910,911,635,636,637,638,86," +
                "396-397,1124-1127" to "System alarm",
            "264-283" to "MPPT reverse connection",
            "332-363" to "Boost capacitor overvoltage alarm",
            "364-395" to "Boost capacitor overvoltage fault",
            "1548-1579" to "String current reflux",
            "1600-1611" to "PV grounding fault",
            "1616" to "System hardware fault",
            "1328" to "PV cable-to-ground short circuit",
        ).map { (codes, name) ->
            codes.split(',').map { part ->
                val (a, b) = if ('-' in part) part.split('-').map(String::toInt) else listOf(part.toInt(), part.toInt())
                a..b
            } to name
        }

        /**
         * Hybrid Appendix 4: bit names of the U32 fault/alarm fields 13050–13078 (index = bit, "" = reserved), the
         * register's display name and whether its bits are warnings (the "alarm" fields) or faults.
         */
        private class FaultField(val reg: Int, val label: String, val warning: Boolean, val bits: List<String>)

        private fun names(vararg n: String) = n.toList()
        private fun same(name: String, n: Int) = List(n) { name }
        private val DEVICE_ABNORMALITY = "Device abnormality"

        private val HYBRID_FAULT_BITS = listOf(
            FaultField(13050, "inverter alarm", true, names(
                "Fan alarm", "Lightning protection alarm on AC side", "Lightning protection alarm on DC side",
                "Bypass switch abnormal", "Communication alarm", "Parallel communication alarm", DEVICE_ABNORMALITY,
                "Junction box leakage protector alarm", "PV1 abnormal warning", "PV2 abnormal warning", "PV3 abnormal warning",
                "PV4 abnormal warning", "Fan 2 low speed alarm", "Fan 2 stall alarm", "Meter ports reversed or wrongly connected",
                "Software version mismatch", "SPD or fuse alarm", "FRAM read alarm", "SPI communication alarm",
                "Ambient temperature sensor open circuit", "Ambient temperature sensor short circuit",
                "Inversion temperature sensor open circuit", "Inversion temperature sensor short circuit",
                "Boost temperature sensor open circuit", "Boost temperature sensor short circuit",
                "Input configuration alarm in DC source mode or clock reset abnormal", "PV high-voltage fault",
                "Ambient low temperature sensor open circuit", "Fan 1 low speed alarm", "Fan 1 stall alarm",
                "Meter communication alarm", "Boost short circuit alarm",
            )),
            FaultField(13052, "grid-side fault", false, names(
                "Grid overvoltage", "Grid transient overvoltage", "Grid undervoltage", "Grid low voltage", "", "AC overcurrent",
                "Grid overfrequency", "Grid underfrequency", "Grid power outage", DEVICE_ABNORMALITY, "Excessive leakage current",
                "Grid abnormal", "10-minute grid overvoltage", "High grid voltage", "Output overload", "Grid voltage imbalance",
                "Inversion overcurrent hardware fault", "High grid frequency", "Grid low frequency", "Grid voltage consistency fault",
                "Grid frequency consistency fault", "Grid protection self-test failed", "Grounding cable fault",
                "Inversion DC injection overvoltage fault", "AC side undervoltage", "AC side overvoltage",
                "AC side underfrequency", "AC side overfrequency", "AC voltage unbalance", "Bypass instantaneous overcurrent",
                "Bypass switch overcurrent", "",
            )),
            FaultField(13054, "system fault 1", false, names(
                "Inversion switch over-temperature", "Boost switch over-temperature", "PV insulation fault", "Bypass switch fault",
                "EPS relay failure", "Junction box relay fault", "Input and output mismatch fault", "PV high-voltage overpower",
                "Redundancy fault", "R-phase inverter voltage sampling abnormal", "S-phase inverter voltage sampling abnormal",
                "T-phase inverter voltage sampling abnormal", "R-phase DC component sampling abnormal",
                "S-phase DC component sampling abnormal", "T-phase DC component sampling abnormal", "PV1 current sampling abnormal",
                "PV2 current sampling abnormal", "PV1 MPPT current sampling abnormal", "PV2 MPPT current sampling abnormal",
                "Control board power supply undervoltage", "Leakage current CT self-detection fault", "SPI communication fault",
                "LCD and main DSP communication fault", "", "PV3 current sampling failure", "N-wire current sampling abnormal",
                "Balance bridge current sampling abnormal",
            )),
            FaultField(13056, "system fault 2", false, names(
                "Module over-temperature", "Excessively high ambient temperature", DEVICE_ABNORMALITY,
                "Low system insulation resistance",
            ) + same(DEVICE_ABNORMALITY, 3) + names("Low ambient temperature") + same(DEVICE_ABNORMALITY, 3) +
                names("Input configuration abnormality") + same(DEVICE_ABNORMALITY, 3) + names("Backup load overpower fault")),
            FaultField(13058, "DC-side fault", false, same(DEVICE_ABNORMALITY, 4) + names("PV access failure") +
                same(DEVICE_ABNORMALITY, 2) + names("", "", "PV1 reverse connection fault", "PV2 reverse connection fault") +
                same(DEVICE_ABNORMALITY, 5) + names(
                    "Bus overvoltage hardware fault", "Bus average undervoltage", "PV hardware overcurrent fault",
                    "PV voltage exceeds bus voltage", "Boost1 short circuit fault", "Boost2 short circuit fault", "DC overcurrent",
                    "PV3 overcurrent", "PV3 reverse connection fault", "Boost3 short circuit fault",
                    "Half-bus overvoltage hardware fault", "Balance bridge overcurrent hardware fault", "PV4 reverse connection fault",
                )),
            FaultField(13060, "permanent fault", false, names(
                "PV overcurrent permanent fault", "Inversion overcurrent permanent fault", "High DC injection permanent fault",
                "Bus overvoltage permanent fault", "Relay permanent fault", "PV1 self-check permanent fault",
                "PV2 self-check permanent fault", "Inversion open-loop self-detection permanent fault", "Temperature sensor failure",
                "Boost3 open-loop self-check permanent fault", "Balance bridge overcurrent permanent fault",
                "Half-bus overvoltage permanent fault",
            )),
            FaultField(13062, "BDC-side fault", false, names(
                "BDC charge transient overcurrent", "BDC discharge transient overcurrent", "Clamping capacitance low voltage",
                "Clamping capacitance transient overvoltage", "", "Battery pre-charge relay failure", "", "", "BDC self-detect fault",
                "", "", "", "BDC over-temperature fault", "", "", "", "BDC hardware overcurrent", "", "", "",
                "BDC current sampling abnormal", "", "Leakage current sampling abnormal", "Secondary DSP communication fault",
                "BDC soft start fault",
            )),
            FaultField(13064, "BDC-side permanent fault", false, names(
                "BDC overcurrent permanent fault", "", "Battery overvoltage permanent fault", "", "BDC self-detect permanent fault",
                "", "", "BDC soft start permanent fault",
            )),
            FaultField(13066, "battery fault", false, names(
                "", "", "", "Battery average undervoltage fault", "", "", "", "Battery over-temperature fault",
                "Battery low temperature fault", "", "", "Battery transient overvoltage", "Battery average overvoltage fault", "",
                "BMS communication fault", "Battery hardware overvoltage", "Battery reversed polarity", "BMS and battery fault",
            )),
            FaultField(13068, "battery alarm", true, names("BDC temperature sensor open circuit", "BDC temperature sensor short circuit")),
            FaultField(13070, "BMS alarm", true, names(
                "Battery overvoltage alarm", "Battery over-temperature alarm", "Battery low temperature alarm",
                "Battery charge/discharge overcurrent alarm", "", "Battery voltage unbalance", "", "Battery undervoltage alarm",
                "Abnormal SD card", "Cell voltage imbalance alarm", "Cell temperature difference alarm",
                "Battery module inconsistency alarm", "Mixed cell alarm",
            )),
            FaultField(13072, "BMS protection", false, names(
                "Battery overvoltage protection", "Battery over-temperature protection", "Battery low temperature protection",
                "Battery charge/discharge overcurrent protection", "Over charge power limit fault", "Over discharge power limit fault",
                "", "Battery low voltage protection", "Slave battery fault", "Battery pre-charge failed",
                "Abnormal external power line status", "Battery current sampling fault", "Battery temperature sampling fault",
                "Battery voltage sampling fault", "Battery internal communication failure", "Battery DC contactor failure",
            )),
            FaultField(13074, "BMS fault 1", false, names(
                "Battery FET or switch failure", "Battery internal hardware failure", "Battery overcurrent fault",
                "Battery short circuit fault", "Battery internal communication fault", "Battery input overvoltage", "",
                "Battery software version mismatch", "Battery heating alarm", "Battery heating circuit abnormal", "", "",
                "Battery self-test fault",
            )),
            FaultField(13076, "BMS fault 2", false, emptyList()),
            FaultField(13078, "BMS alarm 2", true, names("BMS internal alarm")),
        )

        /** Bit 8 of the grid-side fault field: "Grid Power Outage". */
        private const val GRID_OUTAGE_BIT = 8

        /**
         * Hybrid: the Appendix 4 bit fields 13050–13078 (reserved bits by number) and running state 13000 (Appendix 2):
         * fault, communicate fault, warn/derating running, update failed, off-grid running and off-grid charge.
         */
        internal fun hybridProblems(r: Regs): List<DeviceProblem> {
            val out = mutableListOf<DeviceProblem>()
            for (f in HYBRID_FAULT_BITS) {
                val v = r.u32(f.reg) ?: continue
                for (bit in 0 until 32) {
                    if ((v shr bit) and 1L == 0L) continue
                    val name = f.bits.getOrNull(bit)?.takeIf { it.isNotEmpty() } ?: "Code"
                    val kind = when {
                        f.reg == 13052 && bit == GRID_OUTAGE_BIT -> DeviceProblem.Kind.MAINS_LOST
                        f.warning -> DeviceProblem.Kind.WARNING
                        else -> DeviceProblem.Kind.FAULT
                    }
                    out += DeviceProblem(kind, "$name (${f.label} bit $bit)")
                }
            }
            fun none(kind: DeviceProblem.Kind) = out.none { it.kind == kind }
            val state = r.u16(13000) ?: return out
            val text = "${RUNNING_STATES[state]} (${hex(state)})"
            when (state) {
                0x5500, 0x0100, 0x2500 -> if (none(DeviceProblem.Kind.FAULT)) out += DeviceProblem(DeviceProblem.Kind.FAULT, text)
                0x9100 -> if (none(DeviceProblem.Kind.WARNING)) out += DeviceProblem(DeviceProblem.Kind.WARNING, text)
                0x8100, 0x0080, 0x0200 -> out += DeviceProblem(DeviceProblem.Kind.WARNING, text)
                0x1000, 0x0041 -> out += DeviceProblem(DeviceProblem.Kind.BACKUP_MODE, text)
            }
            return out
        }

        /**
         * String inverter: work state 5038 (Appendix 2) with the fault/alarm code 5045 (Appendix 4, valid only in
         * fault 0x5500 or alarm 0x9100; code 10 is the grid power outage), derating run, communicate fault, and the
         * PID alarm code 5151.
         */
        internal fun stringProblems(r: Regs): List<DeviceProblem> {
            val out = mutableListOf<DeviceProblem>()
            val state = r.u16(5038)
            val code = r.u16(5045)?.takeIf { it != 0 }
            when (state) {
                0x5500, 0x9100 -> {
                    val fault = state == 0x5500
                    val kind = when {
                        code == GRID_POWER_OUTAGE_CODE -> DeviceProblem.Kind.MAINS_LOST
                        fault -> DeviceProblem.Kind.FAULT
                        else -> DeviceProblem.Kind.WARNING
                    }
                    val text = when {
                        code == null -> "${WORK_STATES[state]} (${hex(state)})"
                        FAULT_CODES.any { (ranges, _) -> ranges.any { code in it } } -> "${faultName(code)} ($code)"
                        else -> "${if (fault) "Fault" else "Alarm"} code $code"
                    }
                    out += DeviceProblem(kind, text)
                }
                0x8100 -> out += DeviceProblem(DeviceProblem.Kind.WARNING, "Derating run (0x8100)")
                0x2500 -> out += DeviceProblem(DeviceProblem.Kind.FAULT, "Communicate fault (0x2500)")
            }
            r.u16(5151)?.takeIf { it != 0 }?.let {
                out += DeviceProblem(DeviceProblem.Kind.WARNING, "${PID_ALARMS[it] ?: "PID alarm"} ($it)")
            }
            return out
        }

        private const val GRID_POWER_OUTAGE_CODE = 10

        fun faultName(code: Int): String = FAULT_CODES.firstOrNull { (ranges, _) -> ranges.any { code in it } }?.second ?: "Unknown code"
    }
}
