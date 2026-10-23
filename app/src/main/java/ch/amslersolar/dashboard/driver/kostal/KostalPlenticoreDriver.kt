// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.kostal

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
import ch.amslersolar.dashboard.model.DeviceReading
import ch.amslersolar.dashboard.model.EnergyTotals
import ch.amslersolar.dashboard.model.PhaseReading
import ch.amslersolar.dashboard.model.PvString
import java.io.IOException
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * KOSTAL PLENTICORE (plus G2, G3, BI, MP) over Modbus TCP, KOSTAL register map (not SunSpec).
 *
 * Register map: "BA KOSTAL Interface MODBUS-TCP SunSpec with Control" (docs/specs/kostal/). Port 1502, unit id 71.
 * - Floats (and 32-bit integers) are word-swapped by default ("little-endian CDAB"); "big-endian ABCD" can be set
 *   in the inverter. Register 5 says which, so it's read before anything else.
 * - The map has gaps. Registers are read in blocks; if the inverter rejects a block, its registers are read one by
 *   one, and registers it rejects are left out of the reading (asked again with the static data).
 * - Master / slave (host / client, "Multi Device Control"): every inverter answers on its own IP. Add each one as
 *   a device of the same plant. Whether an inverter has a battery (588 battery type) or an energy meter (1082
 *   installed sensor) is detected, so only the one with the meter reports grid power and home consumption, and
 *   the plant totals don't count anything twice. A host's plant totals (1547 ff.) are shown in its details only.
 * - Grid: powermeter total active power (252), > 0 drawn from the grid when the sensor sits at the grid
 *   connection (sensor position 2, the usual one); our sign is the opposite. Position 1 (home consumption) can't be
 *   told from the registers and isn't supported.
 * - Battery: 582 charge/discharge power has no documented sign; the direction comes from the battery current
 *   (200, charge − / discharge +), which matches our sign convention.
 * - Solar: 1066 total DC power of all PV inputs, else the PV strings (34 says how many: a battery uses DC3).
 * - Home: own consumption from PV + battery + grid (116 + 106 + 108).
 */
class KostalPlenticoreDriver(private val reader: RegisterReader, private val unitId: Int) : DeviceDriver {
    constructor(client: ModbusTcpClient, unitId: Int) : this(RegisterReader { u, r, c -> client.readHoldingRegisters(u, r, c) }, unitId)

    private val split = mutableSetOf<Int>() // blocks (by start) the inverter rejected as a whole
    private val missing = mutableSetOf<Int>() // single fields it rejected
    private var static: Map<Int, Int>? = null
    private var polls = 0

    override suspend fun identify(): DeviceInfo {
        val img = Image(fetch(STATIC_FIELDS))
        val product = img.text(768, 32) ?: throw IOException("No KOSTAL product name (register 768)")
        // A PIKO CI has the same product-name register but a different map: it has its own driver.
        if (product.contains("PIKO CI", ignoreCase = true)) throw IOException("A KOSTAL PIKO CI, not a PLENTICORE")
        val model = listOfNotNull(product, img.text(800, 32)).joinToString(" ")
        return DeviceInfo(model = model, serial = img.text(14, 8).orEmpty())
    }

    override suspend fun read(): DeviceReading {
        if (static == null || polls % STATIC_REFRESH_POLLS == 0) {
            missing.clear()
            static = fetch(STATIC_FIELDS)
        }
        polls++
        val img = Image(static.orEmpty() + fetch(LIVE_FIELDS))
        return parse(img).copy(details = details(img))
    }

    /** Reads [fields] (address to size) in blocks, falling back to single fields where a block is rejected. */
    private suspend fun fetch(fields: List<Pair<Int, Int>>): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        for (block in blocks(fields)) {
            val start = block.first().first
            val end = block.last().let { it.first + it.second }
            if (start !in split) {
                try {
                    out.putAll(reader.read(unitId, start, end - start).values())
                    continue
                } catch (_: ModbusException) {
                    split += start
                }
            }
            for ((addr, size) in block) {
                if (addr in missing) continue
                try {
                    out.putAll(reader.read(unitId, addr, size).values())
                } catch (_: ModbusException) {
                    missing += addr // not on this model or firmware
                }
            }
        }
        return out
    }

    private fun RegisterBlock.values(): Map<Int, Int> = (start until start + count).associateWith { u16(it) }

    /** Register values by address, decoding multi-register values in the inverter's byte order. */
    internal class Image(private val regs: Map<Int, Int>) {
        /** Register 5: 0 = little-endian CDAB (default, word-swapped), 1 = big-endian ABCD. */
        val wordSwapped = regs[5] != 1

        fun u16(a: Int): Int? = regs[a]
        fun s16(a: Int): Int? = u16(a)?.toShort()?.toInt()
        fun u32(a: Int): Long? {
            val first = u16(a) ?: return null
            val second = u16(a + 1) ?: return null
            val (hi, lo) = if (wordSwapped) second to first else first to second
            return (hi.toLong() shl 16) or lo.toLong()
        }
        fun f32(a: Int): Double? = u32(a)?.let { Float.fromBits(it.toInt()).toDouble() }?.takeIf { it.isFinite() }
        fun text(a: Int, n: Int): String? {
            val chars = (a until a + n).map { u16(it) ?: return null }.flatMap { listOf((it shr 8).toChar(), (it and 0xFF).toChar()) }
            return String(chars.toCharArray()).trim { it <= ' ' }.takeIf { it.isNotEmpty() }
        }
    }

    internal companion object {
        const val STATIC_REFRESH_POLLS = 12 // identity, battery and meter setup: about once a minute
        private const val MAX_BLOCK = 125
        private const val MAX_GAP = 16

        private fun f(a: Int) = a to 2 // a float or 32-bit value
        private fun r(a: Int) = a to 1

        val STATIC_FIELDS = listOf(
            r(5), 6 to 8, 14 to 8, r(30), r(32), r(34), f(36), 38 to 8, 46 to 8, f(54), 58 to 13,
            f(512), 517 to 8, f(527), f(529), r(531), r(532), 535 to 16, 559 to 16, f(586), r(588),
            768 to 32, 800 to 32, f(1068), f(1070), f(1076), f(1078), r(1080), r(1082), r(1545),
        )
        val LIVE_FIELDS = listOf(f(56)) + (98..124 step 2).map(::f) + f(144) +
            ((150..174 step 2) + 178 + listOf(190, 194, 200, 202, 208, 210, 214, 216) + (218..256 step 2) +
                listOf(258, 260, 266, 268, 270, 276, 278, 280, 286, 320, 322, 324, 326)).map(::f) +
            listOf(r(514), r(575), r(576), f(577), r(579), r(582)) + (1046..1066 step 2).map(::f) + (1547..1555 step 2).map(::f)

        /** Fields grouped into reads of at most 125 registers, bridging small gaps. */
        fun blocks(fields: List<Pair<Int, Int>>): List<List<Pair<Int, Int>>> {
            val out = mutableListOf<MutableList<Pair<Int, Int>>>()
            for (field in fields.sortedBy { it.first }) {
                val current = out.lastOrNull()
                val start = current?.first()?.first
                val end = current?.last()?.let { it.first + it.second }
                if (current != null && field.first - end!! <= MAX_GAP && field.first + field.second - start!! <= MAX_BLOCK) current += field
                else out += mutableListOf(field)
            }
            return out
        }

        val INVERTER_STATES = mapOf(
            0 to "Off", 1 to "Init", 2 to "Isolation measurement", 3 to "Grid check", 4 to "Start-up", 6 to "Feed-in",
            7 to "Throttled", 8 to "External switch off", 9 to "Update", 10 to "Standby", 11 to "Grid sync",
            12 to "Grid pre-check", 13 to "Grid switch off", 14 to "Overheating", 15 to "Shutdown",
            16 to "Improper DC voltage", 17 to "ESB", 18 to "Unknown",
        )
        private val ENERGY_MANAGER = mapOf(0 to "Idle", 2 to "Emergency battery charge", 8 to "Winter mode step 1", 16 to "Winter mode step 2")
        private val BATTERY_TYPES = mapOf(
            0 to "No battery", 2 to "PIKO Battery Li", 4 to "BYD", 8 to "BMZ", 0x10 to "AXIstorage Li SH", 0x40 to "LG",
            0x200 to "Pylontech", 0x400 to "AXIstorage Li SV", 0x1000 to "Dyness", 0x2000 to "VARTA", 0x4000 to "ZYC",
            0x8000 to "HELIVOR",
        )
        private val SENSOR_TYPES = mapOf(
            0 to "SDM 630", 1 to "B-Control EM-300 LR", 3 to "KSEM", 4 to "KEM-C", 5 to "KEM-P", 6 to "KEM MP-P", 0xFF to "No sensor",
        )
        private val BATTERY_MANAGEMENT = mapOf(0 to "None", 1 to "External via digital I/O", 2 to "External via Modbus")
        private val MDC_ROLES = mapOf(-1 to "Not available", 0 to "Off", 1 to "Client (slave)", 2 to "Host (master)")
        private val PSSB = mapOf(0 to "Fuse fail", 1 to "Fuse ok", 0xFF to "Unchecked")

        fun hasBattery(img: Image): Boolean = img.u16(588)?.let { it != 0 } ?: ((img.f32(210) ?: 0.0) > 0)

        /** An energy meter on this inverter: from the installed sensor type, else from the meter reporting a frequency. */
        fun hasMeter(img: Image): Boolean = img.u16(1082)?.let { (it and 0xFF) != 0xFF } ?: ((img.f32(220) ?: 0.0) > 0)

        private fun strings(img: Image): List<PvString> {
            val count = (img.u16(34) ?: 3).coerceIn(1, 3)
            return (0 until count).mapNotNull { i ->
                val base = 258 + 10 * i // DC1 current 258, power 260, voltage 266; DC2 +10; DC3 +20
                val v = img.f32(base + 8) ?: return@mapNotNull null
                PvString(i + 1, v, img.f32(base) ?: 0.0, img.f32(base + 2)?.roundToInt())
            }
        }

        fun parse(img: Image): DeviceReading {
            val strings = strings(img)
            val pv = img.f32(1066)?.roundToInt() ?: strings.mapNotNull { it.powerW }.takeIf { it.isNotEmpty() }?.sum()

            val battery = if (!hasBattery(img)) null else {
                val current = img.f32(200)
                val magnitude = img.s16(582)?.let(::abs)
                    ?: current?.let { c -> img.f32(216)?.let { abs(it * c).roundToInt() } } ?: 0
                // Direction from the current when there is one (documented: charge −, discharge +).
                val direction = when {
                    current != null && abs(current) >= 0.1 -> current.sign.toInt()
                    else -> img.s16(582)?.sign ?: 0
                }
                val power = magnitude * direction
                BatteryState(
                    powerW = power,
                    socPercent = img.u16(514)?.takeIf { it in 0..100 } ?: img.f32(210)?.roundToInt(),
                    voltageV = img.f32(216),
                    mode = when {
                        power > 0 -> "Discharging"
                        power < 0 -> "Charging"
                        else -> "Idle"
                    },
                )
            }

            val meter = hasMeter(img)
            val grid = if (meter) img.f32(252)?.let { -it.roundToInt() } else null // meter: > 0 drawn from the grid
            val parts = listOf(116, 106, 108).map { img.f32(it) }
            val load = if (meter && parts.all { it != null }) parts.sumOf { it!! }.roundToInt() else null

            val hz = img.f32(152) ?: 0.0
            val phases = listOf("L1" to 154, "L2" to 160, "L3" to 166).mapNotNull { (name, base) ->
                val v = img.f32(base + 4)?.takeIf { it > 0 } ?: return@mapNotNull null
                PhaseReading(name, v, img.f32(base), hz, img.f32(base + 2)?.roundToInt())
            }

            return DeviceReading(
                pvPowerW = pv,
                pvStrings = strings.filter { it.voltageV > 0 || (it.powerW ?: 0) > 0 },
                battery = battery,
                gridPowerW = grid,
                phases = phases,
                loadPowerW = load,
                temperatureC = img.f32(98),
                status = img.u32(56)?.toInt()?.let { INVERTER_STATES[it] ?: "State $it" },
                today = EnergyTotals(pvKwh = img.f32(322)?.div(1000)),
                lifetimePvKwh = img.f32(320)?.div(1000),
            )
        }

        private fun kwh(img: Image, a: Int) = fmt(img.f32(a)?.div(1000), "kWh", 1)
        private fun w(img: Image, a: Int) = fmt(img.f32(a), "W", 0)

        /** A value with a SunSpec-style scale factor register: value × 10^sf. */
        private fun scaled(value: Number?, sf: Int?): Double? = value?.toDouble()?.times(10.0.pow(sf?.toShort()?.toInt() ?: 0))

        fun details(img: Image): List<DetailSection> {
            val out = mutableListOf<DetailSection>()
            out += section(
                "Device",
                "Product" to listOfNotNull(img.text(768, 32), img.text(800, 32)).joinToString(" ").ifBlank { null },
                "Article number" to img.text(6, 8),
                "Serial number" to img.text(14, 8),
                "Manufacturer" to img.text(535, 16),
                "Software (UI / SW)" to img.text(58, 13),
                "Software main controller" to img.text(38, 8),
                "Software IO controller" to img.text(46, 8),
                "Hardware version" to img.u32(36)?.let { bits(it) },
                "Power-ID" to img.u32(54)?.let { bits(it) },
                "Inverter state" to enum(img.u32(56)?.toInt(), INVERTER_STATES),
                "Energy manager" to enum(img.u32(104)?.toInt(), ENERGY_MANAGER),
                "Multi Device Control role" to enum(img.s16(1545), MDC_ROLES),
                "PV strings" to img.u16(34)?.toString(),
                "AC phases" to img.u16(32)?.toString(),
                "Bidirectional converters" to img.u16(30)?.toString(),
                "Byte order" to img.u16(5)?.let { if (it == 1) "Big-endian (ABCD)" else "Little-endian (CDAB)" },
                "Maximum power" to fmt(scaled(img.u16(531), img.u16(532)), "W", 0),
            )
            out += section(
                "Inverter (AC)",
                "Active power" to w(img, 172),
                "Reactive power" to fmt(img.f32(174), "var", 0),
                "Apparent power" to fmt(img.f32(178), "VA", 0),
                "Generation power" to fmt(scaled(img.s16(575), img.u16(576)), "W", 0),
                "cos φ" to fmt(img.f32(150), "", 3),
                "Grid frequency" to fmt(img.f32(152), "Hz", 2),
                "L1 voltage" to fmt(img.f32(158), "V", 1), "L1 current" to fmt(img.f32(154), "A", 2), "L1 power" to w(img, 156),
                "L2 voltage" to fmt(img.f32(164), "V", 1), "L2 current" to fmt(img.f32(160), "A", 2), "L2 power" to w(img, 162),
                "L3 voltage" to fmt(img.f32(170), "V", 1), "L3 current" to fmt(img.f32(166), "A", 2), "L3 power" to w(img, 168),
                "Controller temperature" to fmt(img.f32(98), "°C", 1),
                "Isolation resistance" to fmt(img.f32(120)?.div(1000), "kΩ", 0),
                "Power limit (grid operator)" to fmt(img.f32(122), "%", 0),
                "Operating time" to fmt(img.f32(144)?.div(3600), "h", 0),
            )
            out += section(
                "PV",
                "Total DC power (PV inputs)" to w(img, 1066),
                "Total DC power" to w(img, 100),
                *(0 until 3).flatMap { i ->
                    val base = 258 + 10 * i
                    listOf(
                        "DC${i + 1} voltage" to fmt(img.f32(base + 8), "V", 1),
                        "DC${i + 1} current" to fmt(img.f32(base), "A", 2),
                        "DC${i + 1} power" to w(img, base + 2),
                    )
                }.toTypedArray(),
                "Yield today" to kwh(img, 322),
                "Yield this month" to kwh(img, 326),
                "Yield this year" to kwh(img, 324),
                "Yield total" to kwh(img, 320),
                "Generation energy" to fmt(scaled(img.u32(577), img.u16(579))?.div(1000), "kWh", 1),
            )
            if (hasBattery(img)) {
                out += section(
                    "Battery",
                    "Type" to enum(img.u16(588), BATTERY_TYPES),
                    "Manufacturer" to img.text(517, 8),
                    "Serial number" to (img.u32(1070) ?: img.u32(527))?.toString(),
                    "Firmware" to img.u32(586)?.toString(),
                    "State of charge" to fmt(img.u16(514), "%"),
                    "State of charge (float)" to fmt(img.f32(210), "%", 1),
                    "Power" to fmt(img.s16(582), "W"),
                    "Voltage" to fmt(img.f32(216), "V", 1),
                    "Current (charge − / discharge +)" to fmt(img.f32(200), "A", 2),
                    "Charge current" to fmt(img.f32(190), "A", 2),
                    "Temperature" to fmt(img.f32(214), "°C", 1),
                    "Cycles" to img.f32(194)?.roundToInt()?.toString(),
                    "Ready" to img.f32(208)?.let { if (it != 0.0) "Yes" else "No" },
                    "Gross capacity" to fmt(img.u32(512), "Ah"),
                    "Work capacity" to fmt((img.f32(1068) ?: img.u32(529)?.toDouble())?.div(1000), "kWh", 2),
                    "Max charge power (battery)" to w(img, 1076),
                    "Max discharge power (battery)" to w(img, 1078),
                    "Battery management" to enum(img.u16(1080)?.and(0xFF), BATTERY_MANAGEMENT),
                    "PSSB fuse" to enum(img.f32(202)?.roundToInt(), PSSB),
                )
            }
            out += section(
                "Home consumption",
                "From PV" to w(img, 116),
                "From battery" to w(img, 106),
                "From grid" to w(img, 108),
                "Self-consumption rate" to fmt(img.f32(124), "%", 1),
                "Total from PV" to kwh(img, 114),
                "Total from battery" to kwh(img, 110),
                "Total from grid" to kwh(img, 112),
                "Total" to kwh(img, 118),
            )
            out += section(
                "Energy meter",
                "Sensor" to enum(img.u16(1082)?.and(0xFF), SENSOR_TYPES),
                "Active power (+ from grid)" to if (hasMeter(img)) w(img, 252) else null,
                "Reactive power" to if (hasMeter(img)) fmt(img.f32(254), "var", 0) else null,
                "Apparent power" to if (hasMeter(img)) fmt(img.f32(256), "VA", 0) else null,
                "cos φ" to if (hasMeter(img)) fmt(img.f32(218), "", 3) else null,
                "Frequency" to if (hasMeter(img)) fmt(img.f32(220), "Hz", 2) else null,
                *(0 until 3).flatMap { i ->
                    val base = 222 + 10 * i // current 222, active 224, voltage 230; +10 per phase
                    if (!hasMeter(img)) emptyList() else listOf(
                        "L${i + 1} voltage" to fmt(img.f32(base + 8), "V", 1),
                        "L${i + 1} current" to fmt(img.f32(base), "A", 2),
                        "L${i + 1} power" to w(img, base + 2),
                    )
                }.toTypedArray(),
            )
            out += section(
                "Energy totals",
                "PV (DC, all inputs)" to kwh(img, 1056),
                "PV1" to kwh(img, 1058), "PV2" to kwh(img, 1060), "PV3" to kwh(img, 1062),
                "AC side to grid" to kwh(img, 1064),
                "Battery charge (DC)" to kwh(img, 1046),
                "Battery discharge (DC)" to kwh(img, 1048),
                "Battery charge (AC side)" to kwh(img, 1050),
                "Battery to grid (AC)" to kwh(img, 1052),
                "Grid to battery (AC)" to kwh(img, 1054),
            )
            if (img.s16(1545)?.let { it > 0 } == true) {
                out += section(
                    "Multi Device Control plant",
                    "Role" to enum(img.s16(1545), MDC_ROLES),
                    "Plant state of charge" to fmt(img.f32(1547), "%", 1),
                    "Plant battery power (+ discharge)" to w(img, 1549),
                    "Plant PV power" to w(img, 1551),
                    "Plant AC power" to w(img, 1553),
                    "External production" to kwh(img, 1555),
                )
            }
            return out.filter { it.rows.isNotEmpty() }
        }
    }
}
