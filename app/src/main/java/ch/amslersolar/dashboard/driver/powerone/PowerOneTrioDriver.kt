// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.powerone

import ch.amslersolar.dashboard.driver.DeviceDriver
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
import ch.amslersolar.dashboard.model.PhaseReading
import ch.amslersolar.dashboard.model.PvString
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.math.roundToInt

/**
 * Power-One / ABB TRIO string inverters (PV only) over Modbus RTU, reached through an RS485 ↔ Modbus TCP gateway.
 *
 * Specs in docs/specs/power one/: "Modbus RTU register map" for TRIO-5.8/8.5 and TRIO-20.0/27.6 (map "GT", version
 * GT05) and for TRIO-50.0/60.0 (map "Gt03.0"). The TRIO has RS485 only: the gateway must translate Modbus TCP to
 * RTU; the unit id is the inverter's RS485 address (2 in the specs' examples; 1–247). Measurements are input
 * registers (function 04).
 * - GT map: 0100 "start register" = 4370, IEEE floats; word order isn't stated and is detected from the grid
 *   frequency. Gt03 map: 1000 "Inverter ID" = 261, U32 energies, word-swapped floats (SF32, as documented).
 * - Addresses: the specs number registers from 1 (register X is sent as X − 1, stated for Gt03). The map's fixed
 *   first value tells which numbering the gateway uses, so both work.
 * - Solar: the inverter's AC output, i.e. what it delivers to the house (DC inputs are in the strings and
 *   details). No grid or home figures: a TRIO doesn't measure the grid.
 * - Problems: GT map: global state (124, note 6 of the maps) fault states → FAULT, inverter state 125 43 "Grid not
 *   present" / 46 "Grid fail" → MAINS_LOST, alarm (128, note 9): Exxx → FAULT, Wxxx → WARNING, grid OV/UV/OF/UF,
 *   grid fail and df/dt → MAINS_LOST. Gt03 map: global state (1050, annex 3) fault states → FAULT, alarm state
 *   (1051, annex 4) likewise (W015 "Island detected" → MAINS_LOST), derating bits (1054, annex 7) → WARNING except
 *   the user's curtailment, the nameplate current limit and the storage zero-injection setting. Not problems: sun
 *   low / input or bulk under-voltage / zero power (low light) and remote off (an operator command).
 * - The inverter is powered by its panels and stops answering after sunset. No answer after a low-power reading
 *   is shown as sleeping (0 W) and retried once a minute; no answer without any earlier reading is an error.
 */
class PowerOneTrioDriver(private val reader: RegisterReader, private val unitId: Int) : DeviceDriver {
    constructor(client: ModbusTcpClient, unitId: Int) : this(RegisterReader { u, r, c -> client.readInputRegisters(u, r, c) }, unitId)

    internal enum class MapType { GT, GT03 }

    /** Detected register map and numbering: request address = register + [offset] (−1 for 1-based). */
    internal data class Layout(val map: MapType, val offset: Int)

    private var layout: Layout? = null
    private var swapped: Boolean? = null // GT float word order, once known
    private var lastPvW: Int? = null
    private var sleepingSince: Long? = null
    private var lastAttempt = 0L

    override suspend fun identify(): DeviceInfo {
        val img = fetch()
        return DeviceInfo(model = img.model ?: "TRIO", serial = img.serial.orEmpty())
    }

    override suspend fun read(): DeviceReading {
        val now = System.currentTimeMillis()
        if (sleepingSince != null && now - lastAttempt < SLEEP_RETRY_MS) return sleeping()
        lastAttempt = now
        val img = try {
            fetch()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (noAnswer(e) && lastPvW != null && lastPvW!! <= SLEEP_MAX_W) {
                if (sleepingSince == null) sleepingSince = now
                return sleeping()
            }
            throw e
        }
        sleepingSince = null
        return img.reading().also { lastPvW = it.pvPowerW }
    }

    private fun sleeping() = DeviceReading(pvPowerW = 0, status = "Sleeping (no sun)")

    /** Timeouts, or the gateway saying the inverter didn't respond (exceptions 10 and 11). */
    private fun noAnswer(e: IOException) = e is SocketTimeoutException || (e is ModbusException && e.code in listOf(0x0A, 0x0B))

    private suspend fun fetch(): Image {
        val l = layout ?: detect().also { layout = it }
        return when (l.map) {
            MapType.GT -> {
                val data = block(l, 100, 76)
                // Word order isn't documented for this map: decide it from this very poll, then keep it.
                if (swapped == null) swapped = Image(l, data, null, null).detectSwap()
                Image(l, data, null, swapped)
            }
            MapType.GT03 -> Image(l, block(l, 1000, 23), block(l, 1050, 86), true)
        }
    }

    private suspend fun block(l: Layout, register: Int, count: Int): RegisterBlock {
        val b = reader.read(unitId, register + l.offset, count)
        return RegisterBlock(register, b.bytes()) // addressed by register number from here on
    }

    private fun RegisterBlock.bytes(): ByteArray = ByteArray(count * 2).also { out ->
        for (i in 0 until count) {
            val v = u16(start + i)
            out[2 * i] = (v shr 8).toByte(); out[2 * i + 1] = v.toByte()
        }
    }

    /** Finds the map by its fixed first value, trying 1-based numbering (as specified) before 0-based. */
    private suspend fun detect(): Layout {
        for ((map, register, marker) in listOf(Triple(MapType.GT, 100, GT_MARKER), Triple(MapType.GT03, 1000, GT03_MARKER))) {
            for (offset in listOf(-1, 0)) {
                val value = try {
                    reader.read(unitId, register + offset, 1).u16(register + offset)
                } catch (e: ModbusException) {
                    if (e.code in listOf(0x0A, 0x0B)) throw e // the inverter isn't answering at all
                    continue
                }
                if (value == marker) return Layout(map, offset)
            }
        }
        throw IOException("No Power-One / ABB TRIO register map found (input register 100 = 4370 or 1000 = 261)")
    }

    /** Register values of one poll, by register number as in the specs. */
    internal class Image(val layout: Layout, private val a: RegisterBlock, private val b: RegisterBlock?, private val swapped: Boolean?) {
        private fun blockFor(r: Int) = if (r >= a.start && r < a.start + a.count) a else b?.takeIf { r >= it.start && r < it.start + it.count }
        fun u16(r: Int): Int? = blockFor(r)?.u16(r)
        fun u32(r: Int): Long? = if (blockFor(r) != null && blockFor(r + 1) != null) (u16(r)!!.toLong() shl 16) or u16(r + 1)!!.toLong() else null
        private fun raw(r: Int, swap: Boolean): Float? {
            val first = u16(r) ?: return null
            val second = u16(r + 1) ?: return null
            val bits = if (swap) (second shl 16) or first else (first shl 16) or second
            return Float.fromBits(bits)
        }
        /** Unavailable values (e.g. 0xFFFFFFFF reactive power) and NaN come back as null. */
        fun f(r: Int): Double? = raw(r, swapped ?: false)?.toDouble()?.takeIf { it.isFinite() && u32(r) != 0xFFFFFFFFL }

        /** GT float word order from the grid frequency (40–70 Hz); null while the inverter reports none. */
        fun detectSwap(): Boolean? {
            fun hz(swap: Boolean) = raw(150, swap)?.toDouble()?.takeIf { it in 40.0..70.0 }
            return when {
                hz(false) != null -> false
                hz(true) != null -> true
                else -> null
            }
        }

        /** One ASCII character per register (low byte), as the serial and part numbers are stored. */
        fun chars(r: Int, n: Int): String? = (r until r + n).map { u16(it) ?: return null }
            .map { (it and 0xFF).toChar() }.joinToString("").trim { it <= ' ' }.takeIf { it.isNotEmpty() }

        val gt get() = layout.map == MapType.GT
        val serial get() = if (gt) chars(104, 6) else chars(1009, 6)
        val model: String? get() = if (gt) u16(116)?.let { GT_MODELS[it] ?: "TRIO (model '${it.toChar()}')" }
            else u16(1022)?.let { GT03_MODELS[it and 0xFF] ?: "TRIO-50/60 (model '${(it and 0xFF).toChar()}')" }

        fun reading(): DeviceReading = if (gt) gtReading() else gt03Reading()

        private fun gtReading(): DeviceReading {
            val strings = listOf(1 to 152, 2 to 158).mapNotNull { (i, base) ->
                f(base + 2)?.let { v -> PvString(i, v, f(base + 4) ?: 0.0, f(base)?.roundToInt()) }
            }
            val l1 = f(144)?.takeIf { it > 0 }?.let { PhaseReading("L1", it, f(146), f(150) ?: 0.0, null) }
            return DeviceReading(
                pvPowerW = f(148)?.roundToInt()?.coerceAtLeast(0),
                pvStrings = strings.filter { it.voltageV > 0 },
                phases = listOfNotNull(l1),
                temperatureC = f(164),
                status = u16(124)?.let { GT_GLOBAL_STATES[it] ?: "State $it" },
                today = EnergyTotals(pvKwh = f(132)?.div(1000)),
                lifetimePvKwh = f(134),
                details = gtDetails(),
                problems = gtProblems(),
            )
        }

        private fun gt03Reading(): DeviceReading {
            val strings = listOf(1 to 1108, 2 to 1114, 3 to 1130).mapNotNull { (i, base) ->
                f(base + 2)?.let { v -> PvString(i, v, f(base + 4) ?: 0.0, f(base)?.roundToInt()) }
            }
            return DeviceReading(
                pvPowerW = f(1094)?.roundToInt()?.coerceAtLeast(0),
                pvStrings = strings.filter { it.voltageV > 0 },
                temperatureC = f(1122),
                status = u16(1050)?.let { GT03_GLOBAL_STATES[it] ?: "State $it" },
                today = EnergyTotals(pvKwh = u32(1070)?.div(1000.0)),
                lifetimePvKwh = u32(1072)?.div(1000.0),
                details = gt03Details(),
                problems = gt03Problems(),
            )
        }

        private fun gtProblems(): List<DeviceProblem> = listOfNotNull(
            u16(124)?.takeIf { it in GT_FAULT_STATES }?.let { DeviceProblem(Kind.FAULT, "${GT_GLOBAL_STATES[it]} (state $it)") },
            u16(125)?.takeIf { it in GT_NO_GRID_STATES }?.let { DeviceProblem(Kind.MAINS_LOST, "${GT_INVERTER_STATES[it]} (inverter state $it)") },
            u16(128)?.let { a -> alarm(a, GT_ALARMS[a], GT_GRID_ALARMS, GT_IGNORED_ALARMS) },
        ).distinct()

        private fun gt03Problems(): List<DeviceProblem> {
            val out = mutableListOf<DeviceProblem>()
            u16(1050)?.takeIf { it in GT03_FAULT_STATES }?.let { out += DeviceProblem(Kind.FAULT, "${GT03_GLOBAL_STATES[it]} (state $it)") }
            u16(1051)?.let { a -> alarm(a, GT03_ALARMS[a], GT03_GRID_ALARMS, GT03_IGNORED_ALARMS) }?.let { out += it }
            val derating = u16(1054)?.takeIf { it != 0xFFFF } ?: 0
            for ((bit, text) in GT03_DERATING) if (derating and (1 shl bit) != 0) out += DeviceProblem(Kind.WARNING, "$text (derating bit $bit)")
            return out.distinct()
        }

        /** An alarm state: "… (Exxx)" → FAULT, "… (Wxxx)" → WARNING, grid alarms → MAINS_LOST, unknown → FAULT. */
        private fun alarm(code: Int, text: String?, grid: Set<Int>, ignored: Set<Int>): DeviceProblem? = when {
            code == 0 || code == 0xFFFF || code in ignored -> null
            text == null -> DeviceProblem(Kind.FAULT, "Alarm code $code")
            code in grid -> DeviceProblem(Kind.MAINS_LOST, text)
            text.contains("(W") -> DeviceProblem(Kind.WARNING, text)
            else -> DeviceProblem(Kind.FAULT, text)
        }

        private fun w(r: Int) = fmt(f(r), "W", 0)
        private fun kwh(v: Double?) = fmt(v, "kWh", 1)

        private fun gtDetails(): List<DetailSection> = listOf(
            section(
                "Inverter",
                "Model" to model,
                "Serial number" to serial,
                "Part number" to chars(110, 6),
                "Register map" to "GT (${if (layout.offset == -1) "1-based" else "0-based"} addresses)",
                "Global state" to enum(u16(124), GT_GLOBAL_STATES),
                "Inverter state" to enum(u16(125), GT_INVERTER_STATES),
                "DC/DC channel 1 state" to enum(u16(126), GT_DCDC_STATES),
                "DC/DC channel 2 state" to enum(u16(127), GT_DCDC_STATES),
                "Alarm" to enum(u16(128), GT_ALARMS),
                "Inverter temperature" to fmt(f(164), "°C", 1),
                "Booster temperature" to fmt(f(166), "°C", 1),
                "Isolation resistance" to fmt(f(168), "MΩ", 1),
            ),
            section(
                "AC output",
                "Power" to w(148),
                "L1 voltage (phase R)" to fmt(f(144), "V", 1),
                "Current" to fmt(f(146), "A", 2),
                "Frequency" to fmt(f(150), "Hz", 2),
                "cos φ" to fmt(f(172), "", 3),
            ),
            section(
                "DC inputs",
                "Input 1 power" to w(152), "Input 1 voltage" to fmt(f(154), "V", 1), "Input 1 current" to fmt(f(156), "A", 2),
                "Input 2 power" to w(158), "Input 2 voltage" to fmt(f(160), "V", 1), "Input 2 current" to fmt(f(162), "A", 2),
            ),
            section(
                "Energy",
                "Today" to kwh(f(132)?.div(1000)),
                "This week" to kwh(f(138)),
                "This month" to kwh(f(140)),
                "This year" to kwh(f(142)),
                "Partial" to kwh(f(136)),
                "Total" to kwh(f(134)),
            ),
        ).filter { it.rows.isNotEmpty() }

        private fun gt03Details(): List<DetailSection> = listOf(
            section(
                "Inverter",
                "Model" to model,
                "Serial number" to serial,
                "Part number" to chars(1003, 6),
                "Register map" to "Gt03 (${if (layout.offset == -1) "1-based" else "0-based"} addresses)",
                "Global state" to enum(u16(1050), GT03_GLOBAL_STATES),
                "Alarm state" to u16(1051)?.toString(),
                "Derating state" to u16(1054)?.toString(),
                "Internal temperature" to fmt(f(1120), "°C", 1),
                "Inverter temperature" to fmt(f(1122), "°C", 1),
                "Isolation resistance" to fmt(f(1126), "MΩ", 1),
                "Max leakage current" to fmt(f(1128), "A", 2),
            ),
            section(
                "AC output",
                "Power" to w(1094),
                "Peak power today" to w(1098),
                "Peak power (absolute)" to w(1096),
                "Mean grid voltage (phase–neutral)" to fmt(f(1090), "V", 1),
                "Mean current" to fmt(f(1092), "A", 2),
                "Reactive power" to fmt(f(1102), "var", 0),
                "Power factor" to fmt(f(1104), "", 3),
                "Frequency" to fmt(f(1106), "Hz", 2),
            ),
            section(
                "DC inputs",
                *listOf(1 to 1108, 2 to 1114, 3 to 1130).flatMap { (i, base) ->
                    listOf("Input $i power" to w(base), "Input $i voltage" to fmt(f(base + 2), "V", 1), "Input $i current" to fmt(f(base + 4), "A", 2))
                }.toTypedArray(),
            ),
            section(
                "Energy",
                "Today" to kwh(u32(1070)?.div(1000.0)),
                "This week" to kwh(u32(1076)?.div(1000.0)),
                "This month" to kwh(u32(1078)?.div(1000.0)),
                "This year" to kwh(u32(1080)?.div(1000.0)),
                "Partial" to kwh(u32(1074)?.div(1000.0)),
                "Total" to kwh(u32(1072)?.div(1000.0)),
            ),
        ).filter { it.rows.isNotEmpty() }
    }

    internal companion object {
        const val GT_MARKER = 4370
        const val GT03_MARKER = 261
        const val SLEEP_MAX_W = 500
        const val SLEEP_RETRY_MS = 60_000L

        val GT_MODELS = mapOf(
            'C'.code to "TRIO-5.8/8.5-TL-OUTD", 'Y'.code to "TRIO-27.6-TL-OUTD-400", 'y'.code to "TRIO-20-TL-OUTD-400",
            'F'.code to "TRIO-27.6-TL-OUTD-480", 'f'.code to "TRIO-20-TL-OUTD-480",
        )
        val GT03_MODELS = mapOf(
            0x28 to "TRIO-50-TL-OUTD-EU", 0x2F to "TRIO-60-TL-OUTD", 0x29 to "TRIO-50-TL-OUTD-US", 0x3A to "TRIO-60-TL-OUTD-US",
            0x3B to "TRIO-50-TL-OUTD-JP", 0x5C to "TRIO-TM-50.0/60.0",
        )
        val GT_GLOBAL_STATES = mapOf(
            0 to "Sending parameters", 1 to "Waiting for sun/grid", 2 to "Checking grid", 3 to "Measuring Riso", 4 to "DC/DC start",
            5 to "Inverter start", 6 to "Run", 7 to "Recovery", 8 to "Pause", 9 to "Ground fault", 10 to "Over-temperature fault",
            11 to "Address setting", 12 to "Self-test", 13 to "Self-test fail", 14 to "Sensor test + Riso", 15 to "Leak fault",
            16 to "Waiting for manual reset", 17 to "Internal error E026", 18 to "Internal error E027", 19 to "Internal error E028",
            20 to "Internal error E029", 21 to "Internal error E030", 22 to "Sending wind table", 23 to "Failed sending table",
            24 to "Under-temperature fault", 25 to "Remote off", 26 to "Interlock fail", 27 to "Executing autotest", 30 to "Waiting sun",
            31 to "Temperature fault", 32 to "Fan stuck", 33 to "Internal communication fault", 34 to "Slave insertion",
            35 to "DC switch open", 36 to "TRAS switch open", 37 to "Master exclusion", 38 to "Auto exclusion", 51 to "Arc fault",
            53 to "Arc fault self-test fail", 98 to "Erasing internal EEPROM", 99 to "Erasing external EEPROM", 100 to "Counting EEPROM",
            101 to "Freeze", 115 to "Communication loss with communication board", 118 to "Arc fault wrong configuration",
            200 to "DSP programming",
        )
        val GT_INVERTER_STATES = mapOf(
            0 to "Stand by", 1 to "Checking grid", 2 to "Run", 3 to "Bulk OV", 4 to "Out OC", 5 to "IGBT sat", 6 to "Bulk UV",
            7 to "Degauss error", 8 to "No parameters", 9 to "Bulk low", 10 to "Grid OV", 11 to "Communication error",
            12 to "Degaussing", 13 to "Starting", 14 to "Bulk cap fail", 15 to "Leak fail", 16 to "DC/DC fail", 17 to "Ileak sensor fail",
            30 to "Internal error", 31 to "Internal error", 40 to "Forbidden state", 41 to "Input UC", 42 to "Zero power",
            43 to "Grid not present", 44 to "Waiting start", 45 to "MPPT", 46 to "Grid fail", 47 to "Input OC",
            255 to "Inverter DSP not programmed",
        )
        val GT_DCDC_STATES = mapOf(
            0 to "DC/DC off", 1 to "Ramp start", 2 to "MPPT", 3 to "Not used", 4 to "Input OC", 5 to "Input UV", 6 to "Input OV",
            7 to "Input low", 8 to "No parameters", 9 to "Bulk OV", 10 to "Communication error", 11 to "Ramp fail",
            12 to "Internal error", 13 to "Input mode error", 14 to "Ground fault", 15 to "Inverter fail", 16 to "DC/DC IGBT sat",
            17 to "DC/DC ILEAK fail", 18 to "DC/DC grid fail", 19 to "DC/DC comm. error", 255 to "DC/DC DSP not programmed",
        )
        val GT_ALARMS = mapOf(
            0 to "No alarm", 1 to "Sun low (W001)", 2 to "Input OC (E001)", 3 to "Input UV (W002)", 4 to "Input OV (E002)",
            5 to "Sun low (W001)", 6 to "No parameters (E003)", 7 to "Bulk OV (E004)", 8 to "Comm. error (E005)",
            9 to "Output OC (E006)", 10 to "IGBT sat (E007)", 11 to "Bulk UV (W011)", 12 to "Internal error (E009)",
            13 to "Grid fail (W003)", 14 to "Bulk low (E010)", 15 to "Ramp fail (E011)", 16 to "DC/DC fail (E012)",
            17 to "Wrong mode (E013)", 18 to "Ground fault", 19 to "Over temp. (E014)", 20 to "Bulk cap fail (E015)",
            21 to "Inverter fail (E016)", 22 to "Start timeout (E017)", 23 to "Ground fault (E018)", 24 to "Degauss error",
            25 to "Ileak sens. fail (E019)", 26 to "DC/DC fail (E012)", 27 to "Self-test error 1 (E020)",
            28 to "Self-test error 2 (E021)", 29 to "Self-test error 3 (E019)", 30 to "Self-test error 4 (E022)",
            31 to "DC inj error (E023)", 32 to "Grid OV (W004)", 33 to "Grid UV (W005)", 34 to "Grid OF (W006)",
            35 to "Grid UF (W007)", 36 to "Z grid Hi (W008)", 37 to "Internal error (E024)", 38 to "Riso low (E025)",
            39 to "Vref error (E026)", 40 to "Error meas V (E027)", 41 to "Error meas F (E028)", 42 to "Error meas Z (E029)",
            43 to "Error meas Ileak (E030)", 44 to "Error read V (E031)", 45 to "Error read I (E032)", 46 to "Table fail (W009)",
            47 to "Fan fail (W010)", 48 to "UTH (E033)", 49 to "Interlock fail (E034)", 50 to "Remote off (E035)",
            51 to "Vout avg error (E036)", 52 to "Battery low (W012)", 53 to "Clk fail (W013)", 54 to "Input UC (E037)",
            55 to "Zero power (W014)", 56 to "Fan stuck (E038)", 57 to "DC switch open (E039)", 58 to "TRAS switch open (E040)",
            59 to "AC switch open (E041)", 60 to "Bulk UV (E042)", 61 to "Autoexclusion (E043)", 62 to "Grid df/dt (W015)",
            63 to "Den switch open (W016)", 64 to "Jbox fail (W017)", 65 to "DC door open (E044)", 66 to "AC door open (E045)",
            79 to "Arc fault (E050)", 82 to "Arc fault self-test fail (E053)", 83 to "Communication loss with communication board (E054)",
        )
        /** GT global states that are faults (note 6); the rest are normal operation, start-up or tests. */
        val GT_FAULT_STATES = setOf(9, 10, 13, 15, 16, 17, 18, 19, 20, 21, 23, 24, 26, 31, 32, 33, 35, 36, 51, 53, 115, 118)
        val GT_NO_GRID_STATES = setOf(43, 46)
        /** Grid fail (W003), grid OV / UV / OF / UF (W004–W007), grid df/dt (W015). */
        val GT_GRID_ALARMS = setOf(13, 32, 33, 34, 35, 62)
        /** Sun low (W001), input UV (W002), bulk UV (W011), remote off (E035), input UC (E037), zero power (W014). */
        val GT_IGNORED_ALARMS = setOf(1, 3, 5, 11, 50, 54, 55)

        /** Gt03 global states that are faults (annex 3); 25 / 26 interlocks are remote off / emergency stop. */
        val GT03_FAULT_STATES = setOf(9, 10, 13, 15, 24, 29, 30, 41, 42, 51, 53, 116, 118, 120, 124, 150, 151)
        /** Gt03 annex 4: alarm state → "name (code)". */
        val GT03_ALARMS = mapOf(
            2 to "Input OC (E001)", 4 to "Input OV (E002)", 6 to "No parameters (E003)", 7 to "Bulk OV (E004)",
            8 to "Internal error (E005)", 9 to "Output OC (E006)", 10 to "IGBT sat (E007)", 14 to "Bulk UV (E010)",
            15 to "Ramp fault (E011)", 16 to "Internal error (E012)", 17 to "Wrong input mode (E013)", 19 to "Over-temperature (E014)",
            20 to "Capacitor fault (E015)", 21 to "Internal error (E016)", 22 to "Internal error (E017)", 23 to "Leak fault (E018)",
            24 to "Internal error (E049)", 25 to "Internal error (E019)", 26 to "Internal error (E012)", 27 to "Internal error (E020)",
            28 to "Internal error (E021)", 29 to "Internal error (E019)", 30 to "Internal error (E022)", 31 to "DC injection (E023)",
            38 to "Riso low (E025)", 42 to "Mid bulk OV (E029)", 44 to "Internal error (E031)", 45 to "Internal error (E032)",
            48 to "Under-temperature (E033)", 49 to "IGBT not ready (E034)", 51 to "Internal error (E036)", 54 to "Riso low (E037)",
            62 to "Island detected (W015)", 78 to "Riso test fail (E078)", 79 to "Arc fault detected (E050)", 82 to "Arc detector fault (E053)",
            85 to "Arc detector wrong configuration (E055)", 90 to "Periodic grid off (W048)", 91 to "Internal error (E077)",
            95 to "Grid connection fault (W046)", 96 to "Latch, manual reset needed (E075)", 150 to "Update incomplete (W047)",
            152 to "Wrong sequence (E079)", 156 to "Back-feed OC (E084)", 167 to "Aux. bulk feed connection error (E098)",
            170 to "Ground kit self-test error (E099)",
        )
        val GT03_GRID_ALARMS = setOf(62)
        /** Input UV (W002), bulk UV (W011): low light; remote off (E035): an operator command. */
        val GT03_IGNORED_ALARMS = setOf(3, 11, 50)
        /** Annex 7 derating bits that are problems (0 user setpoint, 4 nameplate current, 7 storage zero-injection aren't). */
        val GT03_DERATING = mapOf(
            1 to "Grid over-frequency derating", 2 to "Average grid over-voltage derating", 3 to "Anti-islanding derating",
            5 to "Over-temperature derating", 6 to "DC over-voltage derating",
        )

        val GT03_GLOBAL_STATES = mapOf(
            0 to "Initializing (configuring power control)", 1 to "Waiting for sun or grid", 2 to "Connecting (checking grid)",
            3 to "Initializing (system start-up)", 4 to "Connecting (switching on DC/DC)", 5 to "Connecting (switching on DC/AC)",
            6 to "Connected to grid", 7 to "Post-alarm (recovery)", 8 to "Post-alarm (pause)", 9 to "Ground fault",
            10 to "Over-temperature fault", 12 to "Connecting (grid protection self-test)", 13 to "Grid protection self-test fault",
            14 to "Connecting (safety checks)", 15 to "Leakage fault", 24 to "Under-temperature fault", 25 to "Interlock (remote off)",
            26 to "Interlock (emergency stop)", 27 to "Executing auto-test", 29 to "Grounding-kit fault", 30 to "SW bundle not valid",
            41 to "Temperature sensors fault", 42 to "Grid sequence fault", 51 to "Arc fault", 53 to "Arc detector self-test fault",
            116 to "Power stage off-line", 118 to "Arc detector wrong configuration", 119 to "Arc detector self-test",
            120 to "Configuration fault (bad model)", 124 to "Latch with reset by hand", 150 to "Power stage communication fault",
            151 to "Configuration fault (bad global settings)", 200 to "Programming power stage",
        )
    }
}
