// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.cps

import ch.amslersolar.dashboard.driver.DeviceDriver
import ch.amslersolar.dashboard.driver.Details.bits
import ch.amslersolar.dashboard.driver.Details.enum
import ch.amslersolar.dashboard.driver.Details.fmt
import ch.amslersolar.dashboard.driver.Details.section
import ch.amslersolar.dashboard.driver.modbus.ModbusException
import ch.amslersolar.dashboard.driver.modbus.ModbusTcpClient
import ch.amslersolar.dashboard.driver.modbus.RegisterBlock
import ch.amslersolar.dashboard.driver.sunspec.RegisterReader
import ch.amslersolar.dashboard.model.DetailSection
import ch.amslersolar.dashboard.model.DeviceInfo
import ch.amslersolar.dashboard.model.DeviceProblem
import ch.amslersolar.dashboard.model.DeviceProblem.Kind
import ch.amslersolar.dashboard.model.DeviceReading
import ch.amslersolar.dashboard.model.EnergyTotals
import ch.amslersolar.dashboard.model.PvString
import java.io.IOException
import java.util.Locale
import kotlin.math.roundToInt

/**
 * CPS (Chint Power Systems America) three-phase string / central inverters, "CPS Inverter Model Data Mapping
 * Specification for 403X" (docs/specs/cps/), common input register group 0x0000–0x0048 ("Common Info").
 *
 * All 403X documents share this group: function 04, base address 0x0000 (protocol addresses), integers with fixed
 * scale factors, 32/64-bit values high word first ("big-endian ... per the MODBUS specification", example
 * 0x0016 = 0x0156 / 0x0017 = 0x2318 → 22 422 296 kWh), "Not Implemented" = 0xFFFF (uint16), 0x8000 (int16),
 * 0xFFFFFFFF (uint32), strings NUL-padded. The device type in 0x0000 picks the family:
 * - 0x4031–0x4034 50/60 kW 480 V (FW V15.00 map; 0x4032/0x4034 = 60 kW old/new MCU, 0x4031/0x4033 assumed
 *   50 kW — UNVERIFIED), 3 PV inputs.
 * - 0x4036 25(20) kW 480 V (FW V5.00): 3 PV inputs, group ends at 0x0042 (no Pdc), 0x002E transformer temperature.
 * - 0x4035 100/125 kW 1500 V (FW V16.0): one PV input (0x0027–0x002A reserved).
 * - 0x4039 250/275 kW 12-boost (275 kW 800 V map), 0x403F 320/350 kW 1500 V (PV currents 0.01 A): 3 MPPTs.
 * - 0x4048 SCH250-T-US-600 (250 kW 600 V / 200 kW 480 V map), 0x404D SCA100K/SCA50K (1000 Vdc 100/50 kW 208 V
 *   map): no PV inputs in the common group (only Pdc), signed phase currents.
 * - Any other 0x4030–0x404F: read with the 50/60 kW layout ("403X"). The 25 kW 208 V map (V4.01) has this
 *   layout but its device code isn't stated (its table repeats the 60 kW codes), so it lands here — UNVERIFIED.
 * - Not supported: 0x4043, the 12 kW PV-storage unit (protocol "for 4043", live data at 0x0800 ff., different
 *   layout). Rejected like any other non-CPS device.
 *
 * Connection: the string inverters speak Modbus RTU only (RS485, 8N1, "BaudRate: optional", 9600 / address 1
 * default where stated) — use an RS485 ↔ Modbus TCP gateway; the unit id is the inverter's RS485 address. The
 * 275 kW and 350 kW units also speak Modbus TCP on port 502.
 * Registers are read as one block per poll (0x0016 to the family's last register) plus the identification
 * (0x0000–0x0015) every [STATIC_REFRESH_POLLS] polls. Some maps leave gaps in the group; if the inverter rejects a
 * block, the documented sub-blocks are read instead and the ones it rejects are left out.
 *
 * Solar: Pdc (0x0045, PV input total power, 0.1 kW), else Σ U×I of the PV inputs, else the AC power. Phases: the
 * map only has line-to-line voltages (Uab, Ubc, Uca), which aren't phase voltages, so no phase rows; the voltages
 * and phase currents Ia/Ib/Ic are in the details. Grid: null (the inverter doesn't measure the grid connection). Mode (0x002F) values: Fault, Check,
 * Standby, Running, Derate (all maps), Soft shutdown (250/275 kW), Power off (100/125 kW). Fault / warning words
 * are shown raw (bit meanings differ between families).
 *
 * Problems: for the 50/60 kW 480 V family the fault words are decoded with that map's "Inverter Events
 * Descriptor" (section 6.0, pages 48–53): PFault 0x0034 and Fault0–4 0x0036–0x003A → FAULT ("GridV/F.OutLim" bits
 * → MAINS_LOST), Warn 0x0035 → WARNING. For the other families a non-zero word is reported with its raw value
 * ("Fault0 0x0400"). Mode 0x8000 (Fault) without any fault bit → FAULT. Mode Derating isn't reported: the map
 * doesn't say whether it's the operator's limit or a protection.
 */
class CpsDriver(private val reader: RegisterReader, private val unitId: Int) : DeviceDriver {
    constructor(client: ModbusTcpClient, unitId: Int) : this(RegisterReader { u, r, c -> client.readInputRegisters(u, r, c) }, unitId)

    /** One documented family of the common group. */
    internal data class Family(
        val name: String,
        val pvInputs: Int,
        val pvCurrentScale: Double,
        val last: Int,
        val signedCurrents: Boolean = false,
        val effScale: Double = 0.1,
        val transformerTemp: Boolean = false,
    )

    private var static: Map<Int, Int>? = null
    private var family: Family? = null
    private var split = false // the inverter rejected the whole live block
    private val missing = mutableSetOf<Int>()
    private var polls = 0

    override suspend fun identify(): DeviceInfo {
        val img = Image(loadStatic(), family!!)
        return DeviceInfo(model = img.model(), serial = img.serial().orEmpty())
    }

    override suspend fun read(): DeviceReading {
        if (static == null || polls % STATIC_REFRESH_POLLS == 0) loadStatic()
        polls++
        val f = family!!
        return Image(static.orEmpty() + fetchLive(f), f).reading()
    }

    private suspend fun loadStatic(): Map<Int, Int> {
        val regs = try {
            reader.read(unitId, 0x0000, 0x16).values()
        } catch (e: ModbusException) {
            if (e.code in GATEWAY_NO_ANSWER) throw e
            staticParts()
        }
        val f = familyFor(regs)
        static = regs
        family = f
        return regs
    }

    /** Some maps don't list every register of 0x0000–0x0015: read the documented parts one by one. */
    private suspend fun staticParts(): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        for (part in STATIC_PARTS) {
            try {
                out.putAll(reader.read(unitId, part.first, part.last - part.first + 1).values())
            } catch (e: ModbusException) {
                if (e.code in GATEWAY_NO_ANSWER) throw e
                if (part.first == 0x0000) throw IOException("No CPS 403X common register group (input register 0x0000) on this device")
            }
        }
        return out
    }

    private fun familyFor(regs: Map<Int, Int>): Family {
        val device = regs[0x0000] ?: throw IOException("No CPS device type (input register 0x0000)")
        return FAMILIES[device] ?: when {
            device == 0x4043 -> throw IOException("CPS 12 kW PV-storage inverter (device 0x4043): different register map, not supported")
            device in 0x4030..0x404F -> GENERIC
            else -> throw IOException("Not a CPS 403X inverter (device type 0x${device.toString(16).uppercase()} in input register 0x0000)")
        }
    }

    private suspend fun fetchLive(f: Family): Map<Int, Int> {
        if (!split) {
            try {
                return reader.read(unitId, 0x0016, f.last - 0x0016 + 1).values()
            } catch (e: ModbusException) {
                if (e.code in GATEWAY_NO_ANSWER) throw e
                split = true
            }
        }
        val out = HashMap<Int, Int>()
        for (part in LIVE_PARTS) {
            val range = part.first..minOf(part.last, f.last)
            if (range.isEmpty() || range.first in missing) continue
            try {
                out.putAll(reader.read(unitId, range.first, range.last - range.first + 1).values())
            } catch (e: ModbusException) {
                if (e.code in GATEWAY_NO_ANSWER || range.first == 0x0016) throw e
                missing += range.first
            }
        }
        return out
    }

    private fun RegisterBlock.values(): Map<Int, Int> = (start until start + count).associateWith { u16(it) }

    /** Register values by address; "Not Implemented" values read as null. */
    internal class Image(private val regs: Map<Int, Int>, private val f: Family) {
        fun u16(a: Int): Int? = regs[a]?.takeIf { it != 0xFFFF }
        fun s16(a: Int): Int? = regs[a]?.takeIf { it != 0x8000 }?.toShort()?.toInt()
        fun u32(a: Int): Long? {
            val hi = regs[a] ?: return null
            val lo = regs[a + 1] ?: return null
            return ((hi.toLong() shl 16) or lo.toLong()).takeIf { it != 0xFFFFFFFFL }
        }

        private fun tenth(v: Int?) = v?.div(10.0)
        private fun current(a: Int) = tenth(if (f.signedCurrents) s16(a) else u16(a))

        fun text(a: Int, n: Int): String? {
            val chars = (a until a + n).map { regs[it] ?: return null }.flatMap { listOf((it shr 8).toChar(), (it and 0xFF).toChar()) }
            return String(chars.toCharArray()).substringBefore('\u0000').trim().takeIf { it.isNotEmpty() && it.all { c -> c.code in 0x20..0x7E } }
        }

        /** 16 BCD digits in 0x0006–0x0009, the first 3 unused: 0x0001010091114001 → "1010091114001". */
        fun serial(): String? {
            val words = (0x0006..0x0009).map { regs[it] ?: return null }
            if (words.all { it == 0xFFFF } || words.all { it == 0 }) return null
            val digits = words.joinToString("") { it.toString(16).padStart(4, '0') }
            return digits.drop(3).takeIf { d -> d.all { it.isDigit() } }
        }

        fun model(): String = text(0x000A, 10)?.let { "CPS $it" } ?: "CPS ${f.name}"

        private fun pvInputs(): List<PvString> = (0 until f.pvInputs).mapNotNull { i ->
            val v = tenth(u16(0x0025 + 2 * i))?.takeIf { it > 0 } ?: return@mapNotNull null
            PvString(i + 1, v, s16(0x0026 + 2 * i)?.times(f.pvCurrentScale) ?: 0.0, null)
        }

        fun reading(): DeviceReading {
            val strings = pvInputs()
            val pac = u16(0x001D)?.times(100)
            val pdc = u16(0x0045)?.times(100)
            val computed = strings.takeIf { it.isNotEmpty() }?.sumOf { it.voltageV * it.currentA }?.roundToInt()
            return DeviceReading(
                pvPowerW = (pdc ?: computed ?: pac)?.coerceAtLeast(0),
                pvStrings = strings,
                temperatureC = tenth(s16(0x002C)),
                status = u16(0x002F)?.let { MODES[it] ?: "Mode 0x${it.toString(16).uppercase().padStart(4, '0')}" },
                today = EnergyTotals(pvKwh = tenth(u16(0x0018))),
                lifetimePvKwh = u32(0x0016)?.toDouble(),
                details = details(pac, pdc, strings),
                problems = problems(),
            )
        }

        fun problems(): List<DeviceProblem> {
            val out = mutableListOf<DeviceProblem>()
            val decoded = f == FIFTY_SIXTY
            for ((reg, name) in FAULT_WORDS) {
                val v = u16(reg)?.takeIf { it != 0 } ?: continue
                val kind = if (reg == WARN) Kind.WARNING else Kind.FAULT
                if (!decoded) {
                    out += DeviceProblem(kind, "$name ${bits(v.toLong(), 4)}")
                    continue
                }
                val table = EVENTS_50_60[reg].orEmpty()
                for (bit in 15 downTo 0) {
                    if (v and (1 shl bit) == 0) continue
                    val e = table[bit]
                    out += if (e == null) DeviceProblem(kind, "$name bit $bit") else DeviceProblem(e.first, e.second)
                }
            }
            if (out.none { it.kind != Kind.WARNING } && u16(0x002F) == MODE_FAULT) out += DeviceProblem(Kind.FAULT, "Fault (mode 0x8000)")
            return out.distinct()
        }

        private fun version(a: Int) = u16(a)?.let { String.format(Locale.ROOT, "%02X.%02X", it shr 8, it and 0xFF) }

        private fun details(pac: Int?, pdc: Int?, strings: List<PvString>): List<DetailSection> = listOf(
            section(
                "Inverter",
                "Model" to text(0x000A, 10),
                "Family" to f.name,
                "Device type" to u16(0x0000)?.let { "0x" + it.toString(16).uppercase() },
                "Serial number" to serial(),
                "Protocol version" to u16(0x0004)?.let { fmt(it / 100.0, "", 2) },
                "Firmware (DSP.LCD major)" to version(0x0041),
                "Firmware (DSP.LCD minor)" to version(0x0005),
                "Mode" to enum(u16(0x002F), MODES),
                "Permanent fault" to bits(u16(0x0034)?.toLong(), 4),
                "Warning" to bits(u16(0x0035)?.toLong(), 4),
                *(0..4).map { "Fault $it" to bits(u16(0x0036 + it)?.toLong(), 4) }.toTypedArray(),
                "Heatsink temperature" to fmt(tenth(s16(0x002C)), "°C", 1),
                "Ambient temperature" to fmt(tenth(s16(0x002D)), "°C", 1),
                "Transformer temperature" to if (f.transformerTemp) fmt(tenth(s16(0x002E)), "°C", 1) else null,
                "Rated active power" to fmt(u16(0x0046), "kW"),
                "Rated apparent power" to fmt(u16(0x0047), "kVA"),
            ),
            section(
                "AC output",
                "Active power" to fmt(pac, "W"),
                "Apparent power" to fmt(u16(0x001E)?.times(100), "VA"),
                "Reactive power" to fmt(s16(0x003B)?.times(100), "var"),
                "Power factor" to fmt(s16(0x001A)?.div(1000.0), "", 3),
                "Uab" to fmt(tenth(u16(0x001F)), "V", 1),
                "Ubc" to fmt(tenth(u16(0x0020)), "V", 1),
                "Uca" to fmt(tenth(u16(0x0021)), "V", 1),
                "Ia" to fmt(current(0x0022), "A", 1),
                "Ib" to fmt(current(0x0023), "A", 1),
                "Ic" to fmt(current(0x0024), "A", 1),
                "Frequency" to fmt(tenth(u16(0x002B)), "Hz", 1),
                "Efficiency" to fmt(u16(0x0019)?.times(f.effScale), "%", 1),
                "Peak power today" to fmt(u16(0x001B)?.times(100), "W"),
                "Feed-in time today" to fmt(tenth(u16(0x001C)), "min", 1),
            ),
            section(
                "PV inputs",
                "PV total power" to fmt(pdc, "W"),
                *strings.flatMap { s ->
                    listOf("PV${s.index} voltage" to fmt(s.voltageV, "V", 1), "PV${s.index} current" to fmt(s.currentA, "A", 2))
                }.toTypedArray(),
            ),
            section("Energy", "Today" to fmt(tenth(u16(0x0018)), "kWh", 1), "Total" to fmt(u32(0x0016), "kWh")),
        ).filter { it.rows.isNotEmpty() }
    }

    internal companion object {
        const val STATIC_REFRESH_POLLS = 12
        val GATEWAY_NO_ANSWER = setOf(0x0A, 0x0B)

        private val FIFTY_SIXTY = Family("50/60 kW 480 V", 3, 0.1, 0x0048)
        val GENERIC = Family("403X", 3, 0.1, 0x0048)
        val FAMILIES: Map<Int, Family> = buildMap {
            for (code in 0x4031..0x4034) put(code, FIFTY_SIXTY)
            put(0x4036, Family("25(20) kW 480 V", 3, 0.1, 0x0042, transformerTemp = true))
            put(0x4035, Family("100/125 kW 1500 V", 1, 0.1, 0x0048))
            put(0x4039, Family("250/275 kW", 3, 0.1, 0x0048))
            put(0x403F, Family("320/350 kW 1500 V", 3, 0.01, 0x0048))
            put(0x4048, Family("SCH250-T-US-600 (200/250 kW)", 0, 0.1, 0x0048, signedCurrents = true, effScale = 1.0))
            put(0x404D, Family("SCA100K/SCA50K (1000 Vdc)", 0, 0.1, 0x0048, signedCurrents = true))
        }

        val STATIC_PARTS = listOf(0x0000..0x0000, 0x0001..0x0002, 0x0003..0x0003, 0x0004..0x0005, 0x0006..0x0009, 0x000A..0x0013, 0x0014..0x0015)

        /** Documented sub-blocks of 0x0016–0x0048, used when a map's gaps make the whole block fail. */
        val LIVE_PARTS = listOf(
            0x0016..0x0024, 0x0025..0x002A, 0x002B..0x002D, 0x002E..0x002E, 0x002F..0x003B,
            0x003C..0x0040, 0x0041..0x0041, 0x0042..0x0042, 0x0043..0x0048,
        )

        private const val MODE_FAULT = 0x8000
        private const val WARN = 0x0035
        val FAULT_WORDS = listOf(0x0034 to "PFault", 0x0036 to "Fault0", 0x0037 to "Fault1", 0x0038 to "Fault2", 0x0039 to "Fault3", 0x003A to "Fault4", WARN to "Warn")

        private fun f(vararg e: Pair<Int, String>) = e.associate { (bit, text) -> bit to (Kind.FAULT to text) }
        private fun g(vararg e: Pair<Int, String>) = e.associate { (bit, text) -> bit to (Kind.MAINS_LOST to text) }
        private fun w(vararg e: Pair<Int, String>) = e.associate { (bit, text) -> bit to (Kind.WARNING to text) }

        /** 50/60 kW 480 V map FW V15.00, section 6.0 "Inverter Events Descriptor": register → bit → (kind, text with LCD code). */
        val EVENTS_50_60: Map<Int, Map<Int, Pair<Kind, String>>> = mapOf(
            0x0034 to f(
                15 to "Control board voltage / drive power failure (Fault0160)", 14 to "Open-loop self-test failed (Fault0150)",
                13 to "Internal hardware failure (Fault0140)", 12 to "Permanent power module failure (Fault0010)",
                11 to "Bus hardware over-voltage (Fault0020)", 8 to "Inverter hardware over-current (Fault0050)",
                7 to "CPLD clock fault (Fault0060)", 6 to "DCI too high (Fault0070)", 5 to "Boost hardware over-current (Fault0080)",
                4 to "Steady-state GFCI failure (Fault0090)", 3 to "Relay failure (Fault0100)", 2 to "Bus high failure (Fault0110)",
                0 to "Bus high fault (Fault0130)",
            ),
            WARN to w(
                15 to "External communication failed (Warn0150)", 13 to "PV string error (PVStrErr)",
                12 to "PID box communication failed", 11 to "PID box failure", 10 to "AC side MOV abnormal (Warn0100)",
                7 to "AC side lightning arrester abnormal (Warn0070)", 6 to "Fuse error (Warn0060)",
                5 to "Temperature sensor abnormal (Warn0050)", 4 to "DC side lightning protection abnormal (Warn0040)",
                3 to "EEPROM problem (Warn0030)", 2 to "Internal communication failed (CommErr)",
                1 to "Internal fan alarm (IntFanErr)", 0 to "External fan alarm (ExtFanErr)",
            ),
            0x0036 to f(
                15 to "Inverter current bias (Protect0010)", 14 to "Over-temperature protection (TempOver)",
                13 to "Grid relay protection (Protect0020)", 9 to "High inverter current (Protect0030)",
                6 to "PV1 high current (Protect0040)", 4 to "Inverter soft start timeout (Protect0050)",
                3 to "Bus soft start timeout (Protect0060)", 2 to "Bus voltage difference high (Protect0070)",
                0 to "Bus voltage high (Protect0090)",
            ) + g(
                12 to "Grid out of phase (GridV.OutLim)", 11 to "Grid frequency low (GridF.OutLim)",
                10 to "Grid frequency high (GridF.OutLim)", 8 to "Grid phase voltage out of range (GridV.OutLim)",
                7 to "Grid line voltage out of range (GridV.OutLim)",
            ),
            0x0037 to f(
                15 to "Leakage current sensor abnormal (Protect0100)", 14 to "Bus hardware over-voltage (Protect0110)",
                12 to "Power module protection (Protect0120)", 11 to "Inverter current imbalance (Protect0130)",
                8 to "Inverter hardware over-current (Protect0140)", 7 to "MCU protection (Protect0150)",
                5 to "Abnormal frequency selection (Protect0160)", 4 to "Leakage current too high (GFCIErr)",
                3 to "Insulation resistance too low (IsolationErr)", 2 to "DCI current too high (Protect0170)",
                1 to "DCI current bias (Protect0180)",
            ) + g(9 to "Grid voltage unbalanced (GridV.OutLim)"),
            0x0038 to f(
                12 to "PV3 voltage too high (PV3VoltOver)", 11 to "PV3 input reversed (PV3Reverse)",
                10 to "PV1 voltage too high (PV1VoltOver)", 9 to "PV1 input reversed (PV1Reverse)",
                7 to "Power inverter open-loop self-test (Protect0230)", 6 to "PV source input abnormal (Protect0260)",
                5 to "PV2 voltage too high (PV2VoltOver)", 4 to "PV2 input over-current (Protect0240)",
                3 to "PV2 input reversed (PV2Reverse)", 1 to "Internal hardware error (Protect0210)",
            ),
            0x0039 to f(15 to "Arc protection (ARC Protect)", 13 to "Hardware driver power supply abnormal (Protect0330)"),
            0x003A to f(
                15 to "Phase-to-ground voltage anomaly (Protect0470)", 10 to "CPLD clock abnormal (Protect0520)",
                9 to "CPLD program version abnormal (Protect0530)", 8 to "Abnormal product model (Protect0540)",
                7 to "Boost hardware over-current (Protect0550)", 6 to "Control board 3.3 V low (Protect0560)",
                5 to "PLL lock exception (Protect0570)", 4 to "PV3 input over-current (Protect0580)",
                3 to "Battery overboard (Protect0590)", 2 to "Arc board failure (Arcboard Err)",
                1 to "Steady-state GFCI protection (Protect0610)", 0 to "Control board 5 V low (Protect0620)",
            ),
        )

        val MODES = mapOf(
            0x0000 to "Power off", 0x0400 to "Soft shutdown", 0x0800 to "Derating", 0x1000 to "Running",
            0x2000 to "Standby", 0x4000 to "Checking", 0x8000 to "Fault",
        )
    }
}
