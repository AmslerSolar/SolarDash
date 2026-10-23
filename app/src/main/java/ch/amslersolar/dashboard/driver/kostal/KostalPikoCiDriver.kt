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
import ch.amslersolar.dashboard.model.DetailSection
import ch.amslersolar.dashboard.model.DeviceInfo
import ch.amslersolar.dashboard.model.DeviceProblem
import ch.amslersolar.dashboard.model.DeviceProblem.Kind
import ch.amslersolar.dashboard.model.DeviceReading
import ch.amslersolar.dashboard.model.EnergyTotals
import ch.amslersolar.dashboard.model.PhaseReading
import ch.amslersolar.dashboard.model.PvString
import java.io.IOException
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * KOSTAL PIKO CI 30 / 50 / 60, PIKO CI 30/50 G2 and PIKO CI 100 (PV only) over the KOSTAL Modbus TCP map.
 * Spec: "PIKO CI – KOSTAL MODBUS (TCP) with control information", revision 1.5 of 11.08.2025
 * (docs/specs/kostal/BA_PIKO-CI_…). Port 1502, unit id 71 (modifiable); function code 03 only.
 *
 * Why not [KostalPlenticoreDriver]: its addresses mostly match, but the PIKO CI
 * - has no register 5 (byte order), so the Plenticore driver would always assume word-swapped floats;
 * - has no battery (no 106 "home consumption from battery", so the Plenticore home figure would be missing), no
 *   1066 total PV power (it has 100 "Total DC power"), no 1082 sensor type;
 * - has up to 8 MPPT inputs (0x7000 block: current, power, voltage per input, 6 registers apart; DC1–3 also at
 *   258 ff., DC4 at 300 ff.), which the Plenticore string layout (258 + 10·n, up to 3) doesn't cover.
 * Mapping:
 * - Float word order: not stated in the PIKO CI document. It's detected from the grid frequency (152) – the order
 *   that gives 45–65 Hz wins, and is kept –, until then KOSTAL's usual little-endian CDAB (word-swapped) is assumed.
 * - Solar: 100 total DC power, else the sum of the inputs; inputs from 0x7000 ff. (count from 34).
 * - Grid: 252 total active power of the powermeter, "sensor position 2 (grid connection): (+) power supply,
 *   (−) feed-in"; our sign is the opposite. A meter counts as present when it reports a frequency (220).
 *   611 "power limit meter position" 1 = meter on the load side is taken as sensor position 1 ("(+) house
 *   consumption"): then 252 is home consumption and there is no grid figure (UNVERIFIED that 611 is the sensor
 *   position the 252 note means). 609 "digital meter power direction" is shown, not applied (UNVERIFIED effect).
 * - Home (meter at the grid): 116 own consumption from PV + 108 from grid.
 * - Today: 322 daily yield (Wh); lifetime 320 total yield (Wh). State 56: 1 init, 6 feed-in, 10 standby, 15 shutdown.
 * - Problems: the error message bits 4126–4128 (note 5, pages 32–33). Grid voltage / frequency / absent / abnormal
 *   → MAINS_LOST; losses of a peer device, fan, surge protection, over-temperature → WARNING; the rest → FAULT.
 *   Not reported: 4126 bit 2 "Remote off" (an operator command), 4127 bit 11 "PV under voltage" (low light).
 */
class KostalPikoCiDriver(private val reader: RegisterReader, private val unitId: Int) : DeviceDriver {
    constructor(client: ModbusTcpClient, unitId: Int) : this(RegisterReader { u, r, c -> client.readHoldingRegisters(u, r, c) }, unitId)

    private val split = mutableSetOf<Int>()
    private val missing = mutableSetOf<Int>()
    private var static: Map<Int, Int>? = null
    private var swapped: Boolean? = null
    private var polls = 0

    override suspend fun identify(): DeviceInfo {
        val img = Image(fetch(STATIC_FIELDS), swapped = true)
        val product = img.text(768, 32) ?: throw IOException("No KOSTAL product name (register 768)")
        if (!product.contains("PIKO CI", ignoreCase = true)) throw IOException("Not a KOSTAL PIKO CI ($product)")
        val model = listOfNotNull(product, img.text(800, 32)).joinToString(" ")
        return DeviceInfo(model = model, serial = img.text(14, 8) ?: img.text(559, 16).orEmpty())
    }

    override suspend fun read(): DeviceReading {
        if (static == null || polls % STATIC_REFRESH_POLLS == 0) {
            missing.clear()
            static = fetch(STATIC_FIELDS)
        }
        polls++
        val regs = static.orEmpty() + fetch(LIVE_FIELDS)
        swapped = detectSwapped(regs) ?: swapped
        val img = Image(regs, swapped ?: true)
        return parse(img).copy(details = details(img))
    }

    private suspend fun fetch(fields: List<Pair<Int, Int>>): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        for (block in KostalPlenticoreDriver.blocks(fields)) {
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
                    missing += addr
                }
            }
        }
        return out
    }

    private fun RegisterBlock.values(): Map<Int, Int> = (start until start + count).associateWith { u16(it) }

    internal class Image(private val regs: Map<Int, Int>, val swapped: Boolean) {
        fun u16(a: Int): Int? = regs[a]
        fun s16(a: Int): Int? = u16(a)?.toShort()?.toInt()
        fun u32(a: Int): Long? {
            val first = u16(a) ?: return null
            val second = u16(a + 1) ?: return null
            val (hi, lo) = if (swapped) second to first else first to second
            return (hi.toLong() shl 16) or lo.toLong()
        }
        fun f32(a: Int): Double? = u32(a)?.let { Float.fromBits(it.toInt()).toDouble() }?.takeIf { it.isFinite() }
        fun text(a: Int, n: Int): String? {
            val chars = (a until a + n).map { u16(it) ?: return null }.flatMap { listOf((it shr 8).toChar(), (it and 0xFF).toChar()) }
            return String(chars.toCharArray()).trim { it <= ' ' }.takeIf { it.isNotEmpty() }
        }
    }

    internal companion object {
        const val STATIC_REFRESH_POLLS = 12
        private const val DC_BLOCK = 0x7000
        private const val MAX_INPUTS = 8

        private fun f(a: Int) = a to 2
        private fun r(a: Int) = a to 1

        val STATIC_FIELDS = listOf(
            6 to 8, 14 to 8, r(30), r(32), r(34), f(36), 38 to 8, 46 to 8, f(54), f(515), r(531), r(532),
            535 to 16, 551 to 8, 559 to 16, r(609), r(611), 768 to 32, 800 to 32, 0x1A1C to 3, 0x1A60 to 3,
        )
        val LIVE_FIELDS = listOf(f(56)) + listOf(100, 108, 112, 114, 116, 118, 122, 124, 144).map(::f) +
            ((150..174 step 2) + 178 + (218..252 step 2)).map(::f) +
            listOf(258, 260, 266, 268, 270, 276, 278, 280, 286, 300, 302, 308, 320, 322, 324, 326).map(::f) +
            listOf(r(575), r(576), f(577), r(579), r(4126), r(4127), r(4128)) +
            (0 until MAX_INPUTS * 3).map { f(DC_BLOCK + 2 * it) }

        val STATES = mapOf(1 to "Init", 6 to "Feed-in", 10 to "Standby", 15 to "Shutdown")
        private val METER_DIRECTION = mapOf(0 to "Positive", 1 to "Negative")
        private val METER_POSITION = mapOf(0 to "Grid connection", 1 to "Load (home consumption)")

        /** Word order from the grid frequency: true = word-swapped (CDAB), false = ABCD, null = can't tell. */
        fun detectSwapped(regs: Map<Int, Int>): Boolean? {
            fun plausible(swapped: Boolean) = Image(regs, swapped).f32(152)?.let { it in 45.0..65.0 } == true
            val cdab = plausible(true)
            val abcd = plausible(false)
            return if (cdab != abcd) cdab else null
        }

        /** Input [k] (0-based): from the 0x7000 block, else DC1–3 at 258 + 10·k and DC4 at 300. */
        private fun input(img: Image, k: Int): PvString? {
            val base = DC_BLOCK + 6 * k
            val fallback = when (k) {
                in 0..2 -> 258 + 10 * k
                3 -> 300
                else -> null
            }
            val v = img.f32(base + 4) ?: fallback?.let { img.f32(it + 8) } ?: return null
            val a = img.f32(base) ?: fallback?.let { img.f32(it) } ?: 0.0
            val w = img.f32(base + 2) ?: fallback?.let { img.f32(it + 2) }
            return PvString(k + 1, v, a, w?.roundToInt())
        }

        fun strings(img: Image): List<PvString> {
            val count = (img.u16(34) ?: 2).coerceIn(1, MAX_INPUTS)
            return (0 until count).mapNotNull { input(img, it) }
        }

        /** Error message bits (register, bit) → (kind, text), note 5 of the map. Reserved bits aren't listed. */
        private val ERRORS: Map<Pair<Int, Int>, Pair<Kind, String>> = mapOf(
            (4126 to 0) to (Kind.FAULT to "DC-bias over-current"),
            (4126 to 1) to (Kind.FAULT to "Relay abnormal"),
            (4126 to 3) to (Kind.WARNING to "Over-temperature"),
            (4126 to 4) to (Kind.FAULT to "GFCI abnormal"),
            (4126 to 5) to (Kind.FAULT to "PV string reversed"),
            (4126 to 6) to (Kind.FAULT to "System type error"),
            (4126 to 7) to (Kind.WARNING to "Fan abnormal"),
            (4126 to 8) to (Kind.FAULT to "DC link unbalance or under-voltage"),
            (4126 to 9) to (Kind.FAULT to "DC link over-voltage"),
            (4126 to 10) to (Kind.FAULT to "Internal communication error"),
            (4126 to 11) to (Kind.FAULT to "Software incompatibility"),
            (4126 to 12) to (Kind.FAULT to "Internal storage error"),
            (4126 to 13) to (Kind.FAULT to "Data inconsistency"),
            (4126 to 14) to (Kind.FAULT to "Inverter abnormal"),
            (4126 to 15) to (Kind.FAULT to "Boost abnormal"),
            (4127 to 0) to (Kind.MAINS_LOST to "Grid over-voltage"),
            (4127 to 1) to (Kind.MAINS_LOST to "Grid under-voltage"),
            (4127 to 2) to (Kind.MAINS_LOST to "Grid absent"),
            (4127 to 3) to (Kind.MAINS_LOST to "Grid over-frequency"),
            (4127 to 4) to (Kind.MAINS_LOST to "Grid under-frequency"),
            (4127 to 5) to (Kind.FAULT to "PV over-voltage"),
            (4127 to 6) to (Kind.FAULT to "PV insulation abnormal"),
            (4127 to 7) to (Kind.FAULT to "Leakage current abnormal"),
            (4127 to 9) to (Kind.FAULT to "Internal power supply abnormal"),
            (4127 to 10) to (Kind.FAULT to "PV string abnormal"),
            (4127 to 12) to (Kind.WARNING to "Surge protection module defective"),
            (4127 to 13) to (Kind.MAINS_LOST to "Grid abnormal"),
            (4127 to 14) to (Kind.FAULT to "Arc fault detected"),
            (4127 to 15) to (Kind.MAINS_LOST to "Grid average voltage high"),
            (4128 to 3) to (Kind.WARNING to "PID device abnormal"),
            (4128 to 4) to (Kind.WARNING to "AFCI module lost"),
            (4128 to 5) to (Kind.WARNING to "Master lost"),
            (4128 to 6) to (Kind.WARNING to "Meter lost"),
            (4128 to 7) to (Kind.WARNING to "Park controller lost"),
            (4128 to 8) to (Kind.FAULT to "Grid N abnormal"),
        )

        fun problems(img: Image): List<DeviceProblem> = ERRORS.mapNotNull { (at, p) ->
            val (reg, bit) = at
            val v = img.u16(reg)?.takeIf { it != 0xFFFF } ?: 0
            if (v and (1 shl bit) != 0) DeviceProblem(p.first, "${p.second} ($reg bit $bit)") else null
        }

        fun hasMeter(img: Image) = (img.f32(220) ?: 0.0) > 0
        fun meterOnLoad(img: Image) = img.u16(611) == 1

        fun parse(img: Image): DeviceReading {
            val strings = strings(img)
            val pv = img.f32(100)?.roundToInt() ?: strings.mapNotNull { it.powerW }.takeIf { it.isNotEmpty() }?.sum()
            val meter = hasMeter(img)
            val grid = if (meter && !meterOnLoad(img)) img.f32(252)?.let { -it.roundToInt() } else null
            val load = when {
                !meter -> null
                meterOnLoad(img) -> img.f32(252)?.roundToInt()?.coerceAtLeast(0)
                else -> {
                    val fromPv = img.f32(116)
                    val fromGrid = img.f32(108)
                    if (fromPv != null && fromGrid != null) (fromPv + fromGrid).roundToInt() else null
                }
            }
            val hz = img.f32(152) ?: 0.0
            val phases = listOf("L1" to 154, "L2" to 160, "L3" to 166).mapNotNull { (name, base) ->
                val v = img.f32(base + 4)?.takeIf { it > 0 } ?: return@mapNotNull null
                PhaseReading(name, v, img.f32(base), hz, img.f32(base + 2)?.roundToInt())
            }
            return DeviceReading(
                pvPowerW = pv,
                pvStrings = strings.filter { it.voltageV > 0 || (it.powerW ?: 0) > 0 },
                gridPowerW = grid,
                phases = phases,
                loadPowerW = load,
                status = img.u32(56)?.toInt()?.let { STATES[it] ?: "State $it" },
                today = EnergyTotals(pvKwh = img.f32(322)?.div(1000)),
                lifetimePvKwh = img.f32(320)?.div(1000),
                problems = problems(img),
            )
        }

        private fun scaled(value: Number?, sf: Int?): Double? = value?.toDouble()?.times(10.0.pow(sf?.toShort()?.toInt() ?: 0))
        private fun w(img: Image, a: Int) = fmt(img.f32(a), "W", 0)
        private fun kwh(img: Image, a: Int) = fmt(img.f32(a)?.div(1000), "kWh", 1)

        fun details(img: Image): List<DetailSection> {
            val meter = hasMeter(img)
            return listOf(
                section(
                    "Device",
                    "Product" to listOfNotNull(img.text(768, 32), img.text(800, 32)).joinToString(" ").ifBlank { null },
                    "Manufacturer" to img.text(535, 16),
                    "Model ID" to img.text(551, 8),
                    "Article number" to img.text(6, 8),
                    "Serial number" to (img.text(14, 8) ?: img.text(559, 16)),
                    "Software main controller" to img.text(38, 8),
                    "Software IO controller" to img.text(46, 8),
                    "Control board firmware" to img.text(0x1A1C, 3),
                    "Communication board firmware" to img.text(0x1A60, 3),
                    "Hardware version" to img.u32(36)?.let { bits(it) },
                    "Power-ID" to img.u32(54)?.let { bits(it) },
                    "Inverter state" to enum(img.u32(56)?.toInt(), STATES),
                    "PV inputs" to img.u16(34)?.toString(),
                    "AC phases" to img.u16(32)?.toString(),
                    "Maximum power" to fmt(scaled(img.u16(531), img.u16(532)), "W", 0),
                    "Float word order" to if (img.swapped) "Little-endian (CDAB)" else "Big-endian (ABCD)",
                    "Error messages 1 / 2 / 3" to listOf(4126, 4127, 4128).map { img.u16(it) }
                        .takeIf { it.any { v -> v != null } }?.joinToString(" / ") { v -> v?.let { bits(it.toLong(), 4) } ?: "–" },
                ),
                section(
                    "Inverter (AC)",
                    "Active power" to w(img, 172),
                    "Reactive power" to fmt(img.f32(174), "var", 0),
                    "Apparent power" to fmt(img.f32(178), "VA", 0),
                    "Generation power" to fmt(scaled(img.s16(575), img.u16(576)), "W", 0),
                    "cos φ" to fmt(img.f32(150), "", 3),
                    "Grid frequency" to fmt(img.f32(152), "Hz", 2),
                    *listOf(154, 160, 166).flatMapIndexed { i, base ->
                        listOf(
                            "L${i + 1} voltage" to fmt(img.f32(base + 4), "V", 1),
                            "L${i + 1} current" to fmt(img.f32(base), "A", 2),
                            "L${i + 1} power" to w(img, base + 2),
                        )
                    }.toTypedArray(),
                    "Power limit (grid operator)" to fmt(img.f32(122), "%", 0),
                    "Operating time" to fmt(img.f32(144)?.div(3600), "h", 0),
                ),
                section(
                    "PV",
                    "Total DC power" to w(img, 100),
                    *strings(img).flatMap { s ->
                        listOf(
                            "DC${s.index} voltage" to fmt(s.voltageV, "V", 1),
                            "DC${s.index} current" to fmt(s.currentA, "A", 2),
                            "DC${s.index} power" to fmt(s.powerW, "W"),
                        )
                    }.toTypedArray(),
                    "Yield today" to kwh(img, 322),
                    "Yield this month" to kwh(img, 326),
                    "Yield this year" to kwh(img, 324),
                    "Yield total" to kwh(img, 320),
                    "Generation energy" to fmt(scaled(img.u32(577), img.u16(579))?.div(1000), "kWh", 1),
                ),
                section(
                    "Home consumption",
                    "From PV" to w(img, 116),
                    "From grid" to w(img, 108),
                    "Self-consumption rate" to fmt(img.f32(124), "%", 1),
                    "Total from PV" to kwh(img, 114),
                    "Total from grid" to kwh(img, 112),
                    "Total" to kwh(img, 118),
                ),
                section(
                    "Energy meter",
                    "Meter position (611)" to enum(img.u16(611), METER_POSITION),
                    "Meter power direction (609)" to enum(img.u16(609), METER_DIRECTION),
                    (if (meterOnLoad(img)) "Active power (+ house consumption)" else "Active power (+ from grid)") to if (meter) w(img, 252) else null,
                    "cos φ" to if (meter) fmt(img.f32(218), "", 3) else null,
                    "Frequency" to if (meter) fmt(img.f32(220), "Hz", 2) else null,
                    *(0 until 3).flatMap { i ->
                        val base = 222 + 10 * i
                        if (!meter) emptyList() else listOf(
                            "L${i + 1} voltage" to fmt(img.f32(base + 8), "V", 1),
                            "L${i + 1} current" to fmt(img.f32(base), "A", 2),
                            "L${i + 1} power" to w(img, base + 2),
                        )
                    }.toTypedArray(),
                ),
            ).filter { it.rows.isNotEmpty() }
        }
    }
}
