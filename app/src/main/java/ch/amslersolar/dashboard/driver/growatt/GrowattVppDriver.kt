// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.growatt

import ch.amslersolar.dashboard.driver.DeviceDriver
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
import ch.amslersolar.dashboard.model.DeviceReading
import ch.amslersolar.dashboard.model.EnergyTotals
import ch.amslersolar.dashboard.model.PhaseReading
import ch.amslersolar.dashboard.model.PvString
import kotlinx.coroutines.CancellationException
import java.io.IOException
import kotlin.math.roundToInt

/**
 * Growatt inverters speaking the "GROWATT VPP Communication Protocol of Inverter", version V2.01 (2024-09-20),
 * docs/specs/growatt/Growatt_VPP_Protocol_V2.01.pdf: SPH/SPA (TL BL and TL3 BH-UP), MIN TL-XH/X/X2, MOD/MID-XH,
 * WIT 100KTL3-H and WIS 215KTL3 (Table 3-1).
 *
 * - Transport: the protocol is Modbus RTU (§3.2) on the inverter's RS485 port (and USB). The app reaches it through
 *   an RS485 ↔ Modbus TCP gateway, port 502 at the gateway; dongles offering local Modbus TCP are unverified.
 *   Unit id = the inverter's "mailing address" (holding 30112, range 1–255, default 1). Baud rate 9600 by default
 *   (30113: 0 = 9600, 1 = 38400), 8 data bits, 1 stop bit (§3.2.1; parity not stated, so 8N1).
 * - Identification and ratings are holding registers (FC03, §2.1, 30000+); live values are input registers
 *   (FC04, §2.2, 31000+). Register numbers are sent as-is as the PDU address (the spec gives no offset; unverified).
 * - Encoding (§1.3): 16-bit values high byte first; UINT32/INT32 high word first. Scales as in the tables
 *   (0.1 W, 0.1 V, 0.1 A, 0.01 Hz, 0.1 °C, 0.1 kWh). Reads must not cross the spec's register groups
 *   (exception 2, "read data crossing multi-groups", §3.2.2), so each group is read on its own.
 * - Signs: 31100 inverter active power > 0 = export to grid (= ours); 31112 meter power > 0 = import (→ negated
 *   for [DeviceReading.gridPowerW]); 31200 battery power > 0 = charging (→ negated for [BatteryState.powerW]).
 * - Batteries: up to four blocks, 31200/31300/31400/31500 (§2.2 Battery Information 1–4). Power and energies
 *   are summed, SOC is averaged weighted by the full-charge capacity (31219, Ah) when known. Blocks 2–4 are
 *   optional: exception 2 means "not there" and is not asked again for [RETRY_POLLS] polls. MIC/MIN-X/X2
 *   (DTC 5200/5201) have no battery parameters (§2.1 note 4), so no battery block is read for them.
 * - Home consumption: no register; computed as inverter AC power + meter import (31100 + 31112), the power
 *   balance of §3.3. Needs Growatt's meter: without one 31112 presumably reads 0 (unverified).
 * - Today: 31122 "Power to grid daily" → export, 31118 "Power to user daily" → import (Growatt's usual meaning of
 *   "to user" = grid to house; unverified). No daily or total PV yield register exists, so pvKwh and lifetimePvKwh
 *   stay null.
 * - Status: 31000 working state, 31001 battery state, 31002 priority have tables. The fault and alarm code tables
 *   (3-3, 3-4) are empty in this version ("reference product fault list"), so those codes are shown as numbers.
 * - Three-phase models report line voltages (31106–31108, AB/BC/CA) and phase currents; with no phase-neutral
 *   voltage, phases are only filled for single-phase (L/N) output and line values go to the details.
 * - Unverified: serial layout (30001–30015 is "UINT16 ×15": decoded as ASCII, two characters per register, or one
 *   per register when all high bytes are 0); firmware string layout (30060–30082 ASCII pairs + digits); whether the
 *   reserved registers inside a group (e.g. 31042–31057) can be read in one request with their neighbours; PV3
 *   voltage (31014) is listed as "INT32 0.1W" — taken as INT16 0.1 V like the other strings (obvious typo).
 */
class GrowattVppDriver(private val input: RegisterReader, private val holding: RegisterReader, private val unitId: Int) : DeviceDriver {
    constructor(client: ModbusTcpClient, unitId: Int) : this(
        RegisterReader { u, r, c -> client.readInputRegisters(u, r, c) },
        RegisterReader { u, r, c -> client.readHoldingRegisters(u, r, c) },
        unitId,
    )

    /** Optional block start → poll count from which it may be asked again. */
    private val skipUntil = HashMap<Int, Int>()
    private var polls = 0
    private var static: Registers? = null

    override suspend fun identify(): DeviceInfo {
        val r = Registers(listOf(holding.read(unitId, BASIC.first, BASIC.second)))
        val dtc = r.u16(30000) ?: throw IOException("No Growatt DTC")
        val serial = serial(r)
        if (dtc !in DTC_FAMILIES && (serial == null || !SERIAL_PATTERN.matches(serial))) {
            throw IOException("Not a Growatt VPP-protocol inverter (holding 30000 = $dtc, no valid serial)")
        }
        return DeviceInfo(model = model(r), serial = serial.orEmpty())
    }

    override suspend fun read(): DeviceReading {
        if (static == null || polls % STATIC_REFRESH_POLLS == 0) {
            static = Registers(listOfNotNull(
                optional(holding, BASIC), optional(holding, VERSIONS), optional(holding, VPP_VERSION), optional(holding, SYSTEM),
            ))
        }
        val s = static!!
        val live = Registers(LIVE.map { (start, count) -> input.read(unitId, start, count) })
        val noBattery = s.u16(30000) in NO_BATTERY_DTC
        val batteries = if (noBattery) emptyList() else BATTERIES.mapNotNull { start ->
            optional(input, start to BATTERY_COUNT)?.let { Battery(start, Registers(listOf(it))) }?.takeIf { it.present }
        }
        polls++
        return parse(s, live, batteries).copy(details = details(s, live, batteries))
    }

    private suspend fun optional(reader: RegisterReader, block: Pair<Int, Int>): RegisterBlock? {
        val (start, count) = block
        skipUntil[start]?.let { if (polls < it) return null }
        return try {
            reader.read(unitId, start, count).also { skipUntil.remove(start) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModbusException) {
            if (e.code != 2) throw e
            skipUntil[start] = polls + RETRY_POLLS // not on this model / installation
            null
        }
    }

    /** Several register blocks read in one poll, addressed by absolute register number. */
    internal class Registers(private val blocks: List<RegisterBlock>) {
        private fun block(r: Int, size: Int = 1) = blocks.firstOrNull { it.start <= r && r + size <= it.start + it.count }
        fun has(r: Int) = block(r) != null
        fun u16(r: Int): Int? = block(r)?.u16(r)
        fun s16(r: Int): Int? = block(r)?.s16(r)
        fun u8(r: Int): Int? = u16(r)?.and(0xFF)
        fun u32(r: Int): Long? = block(r, 2)?.u32(r)
        fun s32(r: Int): Int? = block(r, 2)?.s32(r)
        fun tenth(v: Number?): Double? = v?.toDouble()?.div(10)

        /** Two ASCII characters per register (high byte first), NULs and blanks dropped. */
        fun chars(r: Int, n: Int): String? = (r until r + n).map { u16(it) ?: return null }
            .flatMap { listOf(it shr 8, it and 0xFF) }.filter { it in 0x21..0x7E }
            .map { it.toChar() }.joinToString("").takeIf { it.isNotEmpty() }
    }

    /** One "Battery Information" block; register numbers relative to battery 1 (31200), shifted by [start]. */
    internal class Battery(val start: Int, private val r: Registers) {
        private fun a(rel: Int) = start - 31200 + rel
        val index get() = (start - 31200) / 100 + 1
        val powerRaw get() = r.s32(a(31200)) // 0.1 W, > 0 charging
        val voltage get() = r.tenth(r.s16(a(31214)))
        val soc get() = r.u8(a(31217))
        val fccAh get() = r.u32(a(31219))
        /** A block of zeros is an empty slot, not a battery. */
        val present get() = (voltage ?: 0.0) > 0 || (soc ?: 0) > 0 || (powerRaw ?: 0) != 0 || (fccAh ?: 0L) > 0

        fun rows(): DetailSection = section(
            "Battery $index (${start}–${start + BATTERY_COUNT - 1})",
            "Charge/discharge power (+ charging)" to fmt(r.tenth(powerRaw), "W", 0),
            "State of charge" to fmt(soc, "%"),
            "State of health" to fmt(r.u8(a(31218)), "%"),
            "Voltage" to fmt(voltage, "V", 1),
            "Current (+ charging)" to fmt(r.tenth(r.s32(a(31215))), "A", 1),
            "Temperature" to fmt(r.tenth(r.s16(a(31223))), "°C", 1),
            "Charged today" to fmt(r.tenth(r.u32(a(31202))), "kWh", 1),
            "Discharged today" to fmt(r.tenth(r.u32(a(31206))), "kWh", 1),
            "Charged total" to fmt(r.tenth(r.u32(a(31204))), "kWh", 1),
            "Discharged total" to fmt(r.tenth(r.u32(a(31208))), "kWh", 1),
            "Max. allowed charge power" to fmt(r.tenth(r.u32(a(31210))), "W", 0),
            "Max. allowed discharge power" to fmt(r.tenth(r.u32(a(31212))), "W", 0),
            "Full-charge capacity" to fmt(fccAh, "Ah"),
            "Clusters" to fmt(r.u16(a(31225))),
            "Modules per cluster" to fmt(r.u16(a(31226))),
            "Module rated voltage" to fmt(r.tenth(r.u16(a(31227))), "V", 1),
            "Module rated capacity" to fmt(r.tenth(r.u16(a(31228))), "Ah", 1),
        )
    }

    internal companion object {
        const val STATIC_REFRESH_POLLS = 12 // ratings and versions: about once a minute
        const val RETRY_POLLS = 60 // optional blocks that answered exception 2: ask again after about 5 minutes

        // (start, count): one request per register group of the spec (reads may not cross groups).
        val BASIC = 30000 to 31        // 30000 DTC … 30030 battery type
        val VERSIONS = 30060 to 24     // 30060–30083 inverter / battery / meter versions
        val VPP_VERSION = 30099 to 1   // VPP protocol version
        val SYSTEM = 30100 to 16       // 30100–30115: control, country, time, address, baud rate, SYN
        val LIVE = listOf(31000 to 10, 31010 to 50, 31100 to 26) // status; PV1–16 + total; AC + meter + energy
        val BATTERIES = listOf(31200, 31300, 31400, 31500)
        const val BATTERY_COUNT = 29   // 31200–31228

        val SERIAL_PATTERN = Regex("[A-Za-z0-9]{6,30}")

        val DTC_FAMILIES = mapOf(
            3502 to "SPH 3000-6000TL BL", 3735 to "SPA 3000-6000TL BL", 3725 to "SPA 4000-10000TL3 BH-UP",
            3601 to "SPH 4000-10000TL3 BH-UP", 5100 to "MIN 2500-6000TL-XH/XH(P)", 5200 to "MIC/MIN 2500-6000TL-X/X2",
            5201 to "MIN 7000-10000TL-X/X2", 5400 to "MOD-XH/MID-XH", 5601 to "WIT 100KTL3-H", 5800 to "WIS 215KTL3",
        )
        val NO_BATTERY_DTC = setOf(5200, 5201)

        val WORK_STATES = mapOf(
            0 to "Standby", 1 to "Self-test", 2 to "Reserved", 3 to "Fault", 4 to "Upgrading",
            5 to "PV online, battery offline", 6 to "Battery online, PV online/offline", 7 to "Off-grid (PV + battery)",
            8 to "Battery online, PV offline", 9 to "Bypass",
        )
        val BATTERY_STATES = mapOf(0 to "Standby", 1 to "Disconnected", 2 to "Charging", 3 to "Discharging", 4 to "Fault", 5 to "Upgrading")
        val PRIORITIES = mapOf(0 to "Load first", 1 to "Battery first", 2 to "Grid first")
        val BATTERY_TYPES = mapOf(0 to "Lead-acid", 1 to "Lithium")
        val BAUD_RATES = mapOf(0 to "9600 bps", 1 to "38400 bps")
        val COUNTRIES = listOf(
            "SA_NULL", "SA_VDE0126", "SA_G99", "SA_AS4777", "SA_CEI0_21", "SA_SPAIN", "SA_GREECE_CONTINENT", "SA_N4105",
            "SA_G98", "SA_IRELAND", "SA_CQC_2013", "SA_EN50438", "SA_HUNGARY", "SA_BELGIUM", "SA_MEA", "SA_PEA",
            "SA_NEWZWALAND", "SA_CQC_PLANT", "SA_INDIA", "SA_DEMARK_DK1", "SA_SWEDEN", "SA_NORWAY", "SA_QUEENSLAND",
            "SA_FRANCE", "SA_KOREA_60HZ", "SA_BRAZIL", "SA_CEI0_16", "SA_DEWA", "SA_CHILE", "SA_ARGENTINA",
            "SA_N4110_BDEW", "SA_TAIWAN_VPC", "SA_DEMARK_DK2", "SA_CQC_2018", "SA_DEMARK_TR3_3_1", "SA_POLAND",
            "SA_TAIWAN_TPC", "SA_IEEE1547_1", "SA_BRAZIL_240V", "SA_EN50549", "SA_AU_VICTORIA", "SA_AU_WESTERN",
            "SA_AU_HORIZON", "SA_AU_AUSGRID", "SA_AU_ENDEAVOUR", "SA_AU_ERGONENERGY", "SA_AU_ENERGEX",
            "SA_AU_SANETWORK", "SA_US_UL1741", "SA_US_RULE21", "SA_US_RULE14_HECO", "SA_NRS097", "SA_TUNISIA",
            "SA_PRC_EAST", "SA_PRC_WEST", "SA_PRC_QUEBEC", "SA_AUSTRIA", "SA_ESTONIA", "SA_NI_G98", "SA_NI_G99",
            "SA_INDIA_KERALA",
        ).withIndex().associate { it.index to it.value }

        /** 30001–30015: two ASCII characters per register, or one per register if every high byte is 0 (unverified). */
        fun serial(r: Registers): String? {
            val words = (30001 until 30016).map { r.u16(it) ?: return null }
            val oneCharEach = words.all { it shr 8 == 0 } && words.any { it != 0 }
            val chars = if (oneCharEach) words else words.flatMap { listOf(it shr 8, it and 0xFF) }
            return chars.filter { it in 0x21..0x7E }.map { it.toChar() }.joinToString("").takeIf { it.isNotEmpty() }
        }

        fun family(dtc: Int?): String? = dtc?.let { DTC_FAMILIES[it] ?: "DTC $it" }

        fun model(r: Registers): String {
            val rated = r.tenth(r.u32(30016))?.takeIf { it > 0 }?.let { fmt(it / 1000, "kW", if (it % 1000 == 0.0) 0 else 1) }
            return listOfNotNull("Growatt " + (family(r.u16(30000)) ?: "inverter"), rated).joinToString(", ")
        }

        /** "TLAA 1.2.3": two ASCII registers followed by version digits. */
        private fun version(r: Registers, ascii: Int, digits: IntRange): String? {
            val name = r.chars(ascii, 2) ?: return null
            val nums = digits.mapNotNull { r.u16(it) }
            return (listOf(name) + if (nums.isEmpty()) emptyList() else listOf(nums.joinToString("."))).joinToString(" ")
        }

        private fun threePhase(l: Registers) = (l.u16(31107) ?: 0) > 0 || (l.u16(31108) ?: 0) > 0

        fun parse(s: Registers, l: Registers, batteries: List<Battery>): DeviceReading {
            val strings = (0 until 16).mapNotNull { k ->
                val v = l.tenth(l.s16(31010 + 2 * k)) ?: return@mapNotNull null
                val a = l.tenth(l.s16(31011 + 2 * k)) ?: 0.0
                if (v > 0 || a > 0) PvString(k + 1, v, a, null) else null // no per-string power in this protocol
            }
            val acW = l.tenth(l.s32(31100))
            val meterImportW = l.tenth(l.s32(31112)) // > 0 import from grid
            val hz = l.u16(31105)?.div(100.0) ?: 0.0
            val phases = if (threePhase(l)) emptyList() else listOfNotNull(
                l.tenth(l.u16(31106))?.takeIf { it > 0 }?.let { PhaseReading("L1", it, l.tenth(l.s16(31109)), hz, null) },
            )

            val battery = if (batteries.isEmpty()) null else {
                val power = -(batteries.sumOf { (it.powerRaw ?: 0).toLong() } / 10.0).roundToInt() // spec: > 0 charging
                val withSoc = batteries.filter { it.soc != null }
                val soc = when {
                    withSoc.isEmpty() -> null
                    withSoc.all { (it.fccAh ?: 0L) > 0 } ->
                        (withSoc.sumOf { it.soc!! * it.fccAh!!.toDouble() } / withSoc.sumOf { it.fccAh!!.toDouble() }).roundToInt()
                    else -> withSoc.map { it.soc!! }.average().roundToInt()
                }
                val mode = when {
                    power > 0 -> "Discharging"
                    power < 0 -> "Charging"
                    else -> l.u16(31001)?.let { BATTERY_STATES[it] } ?: "Standby"
                }
                BatteryState(power, soc, batteries.first().voltage, mode)
            }

            val state = l.u16(31000)
            val fault = l.u16(31005) ?: 0
            val status = state?.let { st ->
                val text = WORK_STATES[st] ?: "State $st"
                if (st == 3 && fault != 0) "$text (code $fault/${l.u16(31006) ?: 0})" else text
            }
            return DeviceReading(
                pvPowerW = l.tenth(l.s32(31058))?.roundToInt()?.coerceAtLeast(0),
                pvStrings = strings,
                battery = battery,
                gridPowerW = meterImportW?.let { -it.roundToInt() },
                phases = phases,
                loadPowerW = if (acW != null && meterImportW != null) (acW + meterImportW).roundToInt().coerceAtLeast(0) else null,
                temperatureC = l.tenth(l.s16(31114)),
                status = status,
                today = EnergyTotals(exportKwh = l.tenth(l.u32(31122)), importKwh = l.tenth(l.u32(31118))),
            )
        }

        fun details(s: Registers, l: Registers, batteries: List<Battery>): List<DetailSection> {
            val dtc = s.u16(30000)
            val three = threePhase(l)
            val out = mutableListOf(
                section(
                    "Inverter (30000–30115)",
                    "Model family" to family(dtc),
                    "Device type code (DTC)" to fmt(dtc),
                    "Serial number" to serial(s),
                    "Rated power" to fmt(s.tenth(s.u32(30016)), "W", 0),
                    "Max. active power" to fmt(s.tenth(s.u32(30018)), "W", 0),
                    "Max. reactive power (fed in)" to fmt(s.tenth(s.u32(30020)), "var", 0),
                    "Max. reactive power (absorbed)" to fmt(s.tenth(s.u32(30022)), "var", 0),
                    "Max. apparent power" to fmt(s.tenth(s.u32(30024)), "VA", 0),
                    "Rated battery charge/discharge power" to fmt(s.tenth(s.u32(30026))?.takeIf { it > 0 }, "W", 0),
                    "Max. PV input power" to fmt(s.tenth(s.u32(30028)), "W", 0),
                    "Battery type" to if (dtc in NO_BATTERY_DTC) null else enum(s.u8(30030), BATTERY_TYPES),
                    "Inverter firmware" to version(s, 30060, 30062..30064),
                    "Inverter M3 firmware" to version(s, 30065, 30067..30067),
                    "Battery firmware" to version(s, 30068, 30070..30070),
                    "Battery DSP2 firmware" to version(s, 30071, 30073..30073),
                    "Battery BCU firmware" to version(s, 30074, 30076..30076),
                    "Battery M3 firmware" to version(s, 30077, 30079..30079),
                    "Meter firmware" to version(s, 30080, 30082..30082),
                    "VPP protocol version" to s.u16(30099)?.takeIf { it > 0 }?.let { "V${it / 100}.${(it % 100).toString().padStart(2, '0')}" },
                    "Country / grid code" to enum(s.u16(30102), COUNTRIES),
                    "Modbus address" to fmt(s.u16(30112)),
                    "Baud rate" to enum(s.u16(30113), BAUD_RATES),
                    "Remote control authority" to s.u16(30100)?.let { if (it == 1) "Enabled" else "Not enabled" },
                    "On/off command" to s.u16(30101)?.let { if (it == 1) "On" else "Off" },
                ),
                section(
                    "Status (31000–31008)",
                    "Working state" to enum(l.u16(31000), WORK_STATES),
                    "Battery state" to if (dtc in NO_BATTERY_DTC) null else enum(l.u16(31001), BATTERY_STATES),
                    "Priority" to enum(l.u16(31002), PRIORITIES),
                    "Fault code" to fmt(l.u16(31005)), "Fault sub-code" to fmt(l.u16(31006)),
                    "Alarm code" to fmt(l.u16(31007)), "Alarm sub-code" to fmt(l.u16(31008)),
                ),
                section(
                    "PV inputs (31010–31059)",
                    *(0 until 16).flatMap { k ->
                        val v = l.tenth(l.s16(31010 + 2 * k)); val a = l.tenth(l.s16(31011 + 2 * k))
                        if ((v ?: 0.0) <= 0 && (a ?: 0.0) <= 0) emptyList()
                        else listOf("PV${k + 1} voltage" to fmt(v, "V", 1), "PV${k + 1} current" to fmt(a, "A", 1))
                    }.toTypedArray(),
                    "Total PV input power" to fmt(l.tenth(l.s32(31058)), "W", 0),
                ),
                section(
                    "AC output and meter (31100–31125)",
                    "Inverter active power (+ export)" to fmt(l.tenth(l.s32(31100)), "W", 0),
                    "Reactive power" to fmt(l.tenth(l.s32(31102)), "var", 0),
                    "Grid frequency" to fmt(l.u16(31105)?.div(100.0), "Hz", 2),
                    (if (three) "Line voltage L1-L2" else "Grid voltage") to fmt(l.tenth(l.u16(31106)), "V", 1),
                    "Line voltage L2-L3" to if (three) fmt(l.tenth(l.u16(31107)), "V", 1) else null,
                    "Line voltage L3-L1" to if (three) fmt(l.tenth(l.u16(31108)), "V", 1) else null,
                    (if (three) "L1 current" else "Grid current") to fmt(l.tenth(l.s16(31109)), "A", 1),
                    "L2 current" to if (three) fmt(l.tenth(l.s16(31110)), "A", 1) else null,
                    "L3 current" to if (three) fmt(l.tenth(l.s16(31111)), "A", 1) else null,
                    "Meter power (+ import)" to fmt(l.tenth(l.s32(31112)), "W", 0),
                    "Inverter temperature" to fmt(l.tenth(l.s16(31114)), "°C", 1),
                ),
                section(
                    "Energy (31118–31125)",
                    "To user (from grid) today" to fmt(l.tenth(l.u32(31118)), "kWh", 1),
                    "To user (from grid) total" to fmt(l.tenth(l.u32(31120)), "kWh", 1),
                    "To grid today" to fmt(l.tenth(l.u32(31122)), "kWh", 1),
                    "To grid total" to fmt(l.tenth(l.u32(31124)), "kWh", 1),
                ),
            )
            out += batteries.map { it.rows() }
            return out.filter { it.rows.isNotEmpty() }
        }
    }
}
