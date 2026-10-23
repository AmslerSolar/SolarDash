// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.ingeteam

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
import kotlinx.coroutines.delay
import java.io.IOException
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Ingeteam INGECON SUN 1Play / 3Play string inverters (PV only), both register generations.
 *
 * **Current generation** — "INGECON SUN 1Play/3Play Monitoring data" ACL2010IMB05 rev. _B (24/07/2026), Modbus TCP
 * through the inverter's dongle:
 * - Function 03, read-only holding registers at 0x1001–0x1092. Addresses are protocol addresses (0-based; Modicon
 *   40001 + address). At most 124 registers per request; unit id 1 by default; port 502.
 * - Ingeteam recommends a single client and a request period of at least 1 s. This driver waits [requestGapMs]
 *   (1 s) between any two of its requests, so a poll takes about 1–2 s; don't add a second client.
 * - Scaling "[V x 10]", "[A x 100]", "[Hz x 100]", "[PF x 1000]", "[W x 10]": register = value × factor, i.e.
 *   divide by the factor. Resolved with the document's own types: 230 V can only be stored as 2300 (V x 10), and
 *   all "[W x 10]" powers are 32-bit, which a 0.1 W resolution needs and a 10 W one wouldn't. So 0.1 W resolution.
 * - The map has no identification registers (no model, serial or rated power); the model shown is "1Play" or
 *   "3Play" from whether phases 2 and 3 report a voltage, the serial is empty.
 * - UNVERIFIED: 32-bit word order (not stated; high word first is assumed, as in the Modbus convention). The
 *   reserved ranges are listed as starting right at the second word of 0x1027 (daily energy) and 0x1040 (Pmppt4);
 *   both are read as 32-bit. Reserved registers are read with their neighbours (two requests per poll); if the
 *   dongle rejects that, only the defined registers are read (five requests). Unavailable values aren't
 *   documented; 0xFFFF / 0xFFFFFFFF are treated as missing. Event codes (Note 1, ACL2010IMC01) are shown raw.
 *
 * **Older generation** — "Online data input register list" AAA0030IMB02_Q (03/05/24), function 04 input
 * registers 0–124 ("start address" = protocol address, Modicon 30001 + address), Modbus RTU or TCP. One request
 * of registers 0–66 per poll. The document has one map per product and no identification register, so the map is
 * recognised by where the plausible values sit: date/time in 0–5 (all maps), then
 * - 1Play HF (ABF1000) / TLM (ABE1000): status 0–2 in 26, grid frequency in 32. HF has reserved (0xFFFF) 38, 40;
 *   TLM has a second PV input there.
 * - 3Play (ABI1000 / ABI1004): status in 26, phase voltages 32–34, frequency in 35.
 * - 3Play 100TL / 160TL (ABS1004): status in 20, frequency in 27.
 * Needs the inverter connected to the grid (frequency reported) the first time; until then identify fails.
 * Not supported: IS 350TL-M12 (ACB1005, same first 32 registers as the 100TL — it would be shown as a 100TL with
 * wrong DC and energy values) and Power B (ABK1000, central). "Not implemented" is 0xFFFF (stated for reserved
 * registers) and treated as missing; 32-bit counters are high word first (stated: "bits 31-16" in the first
 * register). "Watt/10" and "kWh*100" are read as register = W / 10 and register = kWh × 100 (from the usual
 * maximum values: 10 000 ≙ 100 kW for a 3Play). UNVERIFIED: the packed string currents of the 100TL
 * ("string 2_1": high byte = string 2 assumed) and the sign convention of the self-consumption wattmeter
 * (shown in the details only, not used as grid power).
 *
 * Solar is the DC power (sum of the PV inputs). Grid: null — the inverters don't measure the grid connection.
 *
 * Problems: the alarm / event bit meanings are in documents not in docs/specs (ACL2010IMC01, AAA0030IMB07).
 * - Current map: the event codes 0x101E–0x1020 ("events and status") aren't reported; the active power reduction
 *   bits 0x1036 (note 4) bit 0 over-temperature and bit 2 fan locked → WARNING.
 * - Older maps: a non-zero "instantaneous alarm code n bits" register → FAULT with the raw bits, a non-zero
 *   "warning code" → WARNING; 1Play power reduction reason 2–4 (temperature) → WARNING.
 */
class IngeteamDriver(
    private val holding: RegisterReader,
    private val input: RegisterReader,
    private val unitId: Int,
    private val requestGapMs: Long = 1_000,
) : DeviceDriver {
    constructor(client: ModbusTcpClient, unitId: Int) : this(
        RegisterReader { u, r, c -> client.readHoldingRegisters(u, r, c) },
        RegisterReader { u, r, c -> client.readInputRegisters(u, r, c) },
        unitId,
    )

    /** Which register map the inverter answered with. */
    enum class Layout(val label: String, val model: String) {
        CURRENT("ACL2010IMB05 (holding registers 0x1001 ff.)", "INGECON SUN"),
        PLAY1_HF("AAA0030IMB02, 1Play HF (ABF1000)", "INGECON SUN 1Play HF"),
        PLAY1_TLM("AAA0030IMB02, 1Play TLM (ABE1000)", "INGECON SUN 1Play TLM"),
        PLAY3("AAA0030IMB02, 3Play (ABI1000/ABI1004)", "INGECON SUN 3Play"),
        PLAY3_100TL("AAA0030IMB02, 3Play 100TL/160TL (ABS1004)", "INGECON SUN 3Play 100TL/160TL"),
    }

    private var layout: Layout? = null
    private var strict = false // the dongle rejected reads spanning reserved registers
    private val missing = mutableSetOf<Int>() // strict blocks it rejected
    private var lastRequestNs = 0L

    override suspend fun identify(): DeviceInfo {
        val (l, img) = detect()
        layout = l
        return DeviceInfo(model = img.model(l), serial = "")
    }

    override suspend fun read(): DeviceReading {
        val l = layout ?: detect().first.also { layout = it }
        val img = if (l == Layout.CURRENT) Image(fetchCurrent()) else Image(legacyBlock().values())
        return img.reading(l)
    }

    /** Current map first (FC03 0x1001), then the older one (FC04 0–66). */
    private suspend fun detect(): Pair<Layout, Image> {
        val current = try {
            paced { holding.read(unitId, 0x1001, 36) }
        } catch (e: ModbusException) {
            if (e.code in GATEWAY_NO_ANSWER) throw e
            null
        }
        if (current != null) {
            val img = Image(current.values())
            if (img.plausibleCurrent()) return Layout.CURRENT to img
        }
        val legacy = try {
            Image(legacyBlock().values())
        } catch (e: ModbusException) {
            throw IOException("No Ingeteam register map (holding 0x1001 or input 0–66) on this device (Modbus exception ${e.code})")
        }
        val l = legacy.legacyLayout()
            ?: throw IOException("No Ingeteam INGECON SUN register map recognised (or the inverter isn't connected to the grid yet)")
        return l to legacy
    }

    private suspend fun legacyBlock(): RegisterBlock = paced { input.read(unitId, 0, LEGACY_COUNT) }

    private suspend fun fetchCurrent(): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        if (!strict) {
            try {
                for ((start, count) in WIDE_BLOCKS) out.putAll(paced { holding.read(unitId, start, count) }.values())
                return out
            } catch (e: ModbusException) {
                if (e.code in GATEWAY_NO_ANSWER) throw e
                strict = true
                out.clear()
            }
        }
        var any = false
        for ((start, count) in STRICT_BLOCKS) {
            if (start in missing) continue
            try {
                out.putAll(paced { holding.read(unitId, start, count) }.values())
                any = true
            } catch (e: ModbusException) {
                if (e.code in GATEWAY_NO_ANSWER || start == STRICT_BLOCKS.first().first) throw e
                missing += start // e.g. MPPT 5–9 on a small inverter
            }
        }
        if (!any) throw IOException("Ingeteam inverter returned no data")
        return out
    }

    /** At least [requestGapMs] between two requests to the inverter, as Ingeteam asks. */
    private suspend fun <T> paced(block: suspend () -> T): T {
        if (requestGapMs > 0 && lastRequestNs != 0L) {
            val wait = requestGapMs - (System.nanoTime() - lastRequestNs) / 1_000_000
            if (wait > 0) delay(wait)
        }
        try {
            return block()
        } finally {
            lastRequestNs = System.nanoTime()
        }
    }

    private fun RegisterBlock.values(): Map<Int, Int> = (start until start + count).associateWith { u16(it) }

    /** Register values by address. 0xFFFF (not implemented) reads as null everywhere. */
    internal class Image(private val regs: Map<Int, Int>) {
        fun u16(a: Int): Int? = regs[a]?.takeIf { it != 0xFFFF }
        fun s16(a: Int): Int? = u16(a)?.toShort()?.toInt()
        fun u32(a: Int): Long? {
            val hi = regs[a] ?: return null
            val lo = regs[a + 1] ?: return null
            return ((hi.toLong() shl 16) or lo.toLong()).takeIf { it != 0xFFFFFFFFL }
        }
        fun s32(a: Int): Int? = u32(a)?.toInt()

        private fun hz(v: Int?) = v != null && v in 4_500..6_500

        fun plausibleCurrent(): Boolean {
            val state = regs[0x101D] ?: return false
            val freq = regs[0x1005] ?: return false
            val vac = regs[0x1001] ?: return false
            return state in CURRENT_STATES && (freq == 0 || freq in 4_000..7_000) && vac <= 6_000
        }

        fun legacyLayout(): Layout? {
            val dateOk = u16(0) in 1900..2100 && u16(1) in 1..12 && u16(2) in 1..31 &&
                u16(3) in 0..23 && u16(4) in 0..59 && u16(5) in 0..59
            if (!dateOk) return null
            return when {
                u16(26) in 0..2 && hz(u16(32)) -> if (regs[38] == 0xFFFF && regs[40] == 0xFFFF) Layout.PLAY1_HF else Layout.PLAY1_TLM
                u16(26) in 0..2 && hz(u16(35)) && (32..34).all { (u16(it) ?: 0) <= 5_000 } -> Layout.PLAY3
                u16(20) in 0..2 && hz(u16(27)) && (24..26).all { (u16(it) ?: 0) <= 5_000 } -> Layout.PLAY3_100TL
                else -> null
            }
        }

        fun model(l: Layout): String = when (l) {
            Layout.CURRENT -> if ((1..2).any { (u16(0x1001 + 5 * it) ?: 0) > 0 }) "INGECON SUN 3Play" else "INGECON SUN 1Play"
            else -> l.model
        }

        fun reading(l: Layout): DeviceReading = when (l) {
            Layout.CURRENT -> current()
            Layout.PLAY1_HF, Layout.PLAY1_TLM -> play1(l)
            Layout.PLAY3 -> play3()
            Layout.PLAY3_100TL -> play3Large()
        }

        // ---- ACL2010IMB05 ----

        private fun mppt(i: Int): Triple<Double?, Double?, Double?> {
            val b = MPPT_BASES[i]
            return Triple(u16(b)?.div(10.0), s16(b + 1)?.div(100.0), u32(b + 2)?.div(10.0))
        }

        private fun current(): DeviceReading {
            val phases = (0..2).mapNotNull { i ->
                val b = 0x1001 + 5 * i
                val v = u16(b)?.div(10.0)?.takeIf { it > 0 } ?: return@mapNotNull null
                PhaseReading("L${i + 1}", v, u16(b + 1)?.div(100.0), u16(b + 4)?.div(100.0) ?: 0.0, s32(b + 2)?.div(10.0)?.roundToInt())
            }
            val inputs = MPPT_BASES.indices.map { mppt(it) }
            val strings = inputs.mapIndexedNotNull { i, (v, a, p) ->
                if (v == null || v <= 0) null else PvString(i + 1, v, a ?: 0.0, p?.roundToInt())
            }
            val dc = inputs.mapNotNull { it.third }.takeIf { it.isNotEmpty() }?.sum()
            return DeviceReading(
                pvPowerW = (dc ?: s32(0x1037)?.div(10.0))?.roundToInt()?.coerceAtLeast(0),
                pvStrings = strings,
                phases = phases,
                temperatureC = s16(0x101C)?.toDouble(),
                status = u16(0x101D)?.let { CURRENT_STATES[it] ?: "State $it" },
                today = EnergyTotals(pvKwh = u32(0x1027)?.div(1000.0)),
                lifetimePvKwh = u32(0x1021)?.toDouble(),
                details = currentDetails(phases),
                problems = u16(0x1036)?.let { mask ->
                    CURRENT_REDUCTION_PROBLEMS.filterKeys { mask and (1 shl it) != 0 }
                        .map { (bit, text) -> DeviceProblem(Kind.WARNING, "$text (reduction bit $bit)") }
                }.orEmpty(),
            )
        }

        private fun currentDetails(phases: List<PhaseReading>): List<DetailSection> = listOf(
            section(
                "Inverter",
                "Register map" to Layout.CURRENT.label,
                "State" to enum(u16(0x101D), CURRENT_STATES),
                "Event code 1" to bits(u16(0x101E)?.toLong(), 4),
                "Event code 2" to bits(u16(0x101F)?.toLong(), 4),
                "Event code 3" to bits(u16(0x1020)?.toLong(), 4),
                "Active power reduction" to u16(0x1036)?.let { reasons(it, CURRENT_REDUCTION_BITS) },
                "Internal temperature" to fmt(s16(0x101C), "°C"),
                "Generation time" to fmt(u32(0x1023), "h"),
            ),
            section(
                "AC output",
                "Active power" to fmt(s32(0x1037)?.div(10.0), "W", 0),
                "Reactive power" to fmt(s32(0x1039)?.div(10.0), "var", 0),
                "cos φ" to fmt(s16(0x103D)?.div(1000.0), "", 3),
                "Peak power" to fmt(u32(0x103B)?.div(10.0), "W", 0),
                *phases.flatMap { p ->
                    listOf(
                        "${p.name} voltage" to fmt(p.voltageV, "V", 1), "${p.name} current" to fmt(p.currentA, "A", 2),
                        "${p.name} power" to fmt(p.powerW, "W"), "${p.name} frequency" to fmt(p.frequencyHz, "Hz", 2),
                    )
                }.toTypedArray(),
            ),
            section(
                "DC inputs (MPPT)",
                *MPPT_BASES.indices.flatMap { i ->
                    val (v, a, p) = mppt(i)
                    if (v == null || v <= 0) emptyList()
                    else listOf("MPPT ${i + 1} voltage" to fmt(v, "V", 1), "MPPT ${i + 1} current" to fmt(a, "A", 2), "MPPT ${i + 1} power" to fmt(p, "W", 0))
                }.toTypedArray(),
            ),
            section(
                "Strings",
                *(0 until 18).flatMap { i ->
                    val v = u16(0x1050 + 2 * i)?.div(10.0)
                    if (v == null || v <= 0) emptyList()
                    else listOf("String ${i + 1} voltage" to fmt(v, "V", 1), "String ${i + 1} current" to fmt(s16(0x1051 + 2 * i)?.div(100.0), "A", 2))
                }.toTypedArray(),
            ),
            section(
                "Energy",
                "Today" to fmt(u32(0x1027)?.div(1000.0), "kWh", 2),
                "Total" to fmt(u32(0x1021), "kWh"),
            ),
        ).filter { it.rows.isNotEmpty() }

        // ---- AAA0030IMB02 ----

        private fun play1(l: Layout): DeviceReading {
            val hf = l == Layout.PLAY1_HF
            val l1 = u16(30)?.div(10.0)?.takeIf { it > 0 }?.let { PhaseReading("L1", it, u16(29)?.div(100.0), u16(32)?.div(100.0) ?: 0.0, s16(34)) }
            val strings = if (hf) listOfNotNull(pv(1, s16(39), s16(37), s16(41)))
                else listOfNotNull(pv(1, s16(39), s16(37), s16(42)), pv(2, s16(40), s16(38), s16(43)))
            return legacyReading(
                l, pvW = s16(41), strings = strings, phases = listOfNotNull(l1),
                status = u16(26), daily = u32(49), total = u32(6),
                extra = listOf(
                    "Split-phase voltage 2" to fmt(u16(31)?.div(10.0), "V", 1),
                    "Apparent power" to fmt(s16(33), "VA"),
                    "Reactive power" to fmt(s16(35), "var"),
                    "cos φ" to fmt(s16(36)?.div(1000.0), "", 3),
                    "Isolation resistance" to fmt(u16(44), "kΩ"),
                    "Wattmeter (self-consumption)" to fmt(s16(45), "W"),
                    "Self-consumption ratio" to fmt(u16(51), "%"),
                    "Power reduction" to fmt(u16(46), "%"),
                    "Power reduction reason" to enum(u16(47), LEGACY_REDUCTION),
                    "Reactive power reference" to enum(u16(48), LEGACY_QREF),
                    "Stop event" to if (hf) null else u16(52)?.toString(),
                ),
                alarms = 18..22,
                temperatureDerating = u16(47)?.takeIf { it in 2..4 },
            )
        }

        private fun play3(): DeviceReading {
            val phases = (0..2).mapNotNull { i ->
                u16(32 + i)?.div(10.0)?.takeIf { it > 0 }?.let { PhaseReading("L${i + 1}", it, u16(29 + i)?.div(100.0), u16(35)?.div(100.0) ?: 0.0, null) }
            }
            val strings = listOfNotNull(pv(1, s16(42), s16(40), s16(45)?.times(10)), pv(2, s16(43), s16(41), s16(46)?.times(10)))
            return legacyReading(
                Layout.PLAY3, pvW = s16(44)?.times(10) ?: strings.mapNotNull { it.powerW }.takeIf { it.isNotEmpty() }?.sum(),
                strings = strings, phases = phases, status = u16(26), daily = u32(63), total = u32(6),
                extra = listOf(
                    "Active power (AC)" to fmt(s16(37)?.times(10), "W"),
                    "Apparent power" to fmt(s16(36)?.times(10), "VA"),
                    "Reactive power" to fmt(s16(38)?.times(10), "var"),
                    "cos φ" to fmt(s16(39)?.div(1000.0), "", 3),
                    "Isolation resistance" to fmt(u16(47), "kΩ"),
                    "Wattmeter (self-consumption)" to fmt(s16(48)?.times(10), "W"),
                    "Self-consumption ratio" to fmt(u16(65), "%"),
                    "Power reduction" to fmt(u16(49), "%"),
                    "Power reduction reason" to enum(u16(50), LEGACY_REDUCTION_3PLAY),
                    "Reactive power reference" to enum(u16(51), LEGACY_QREF),
                    "Stop event" to u16(66)?.toString(),
                    "String kit warning" to bits(u16(62)?.toLong(), 4),
                    *(0 until 10).map { "String ${it + 1} current" to fmt(s16(52 + it)?.div(10.0), "A", 1) }.toTypedArray(),
                ),
                alarms = 18..22,
            )
        }

        private fun play3Large(): DeviceReading {
            val phases = (0..2).mapNotNull { i ->
                u16(24 + i)?.div(10.0)?.takeIf { it > 0 }?.let { PhaseReading("L${i + 1}", it, u16(21 + i)?.div(100.0), u16(27)?.div(100.0) ?: 0.0, null) }
            }
            val input1 = pv(1, u16(33), s16(32), s16(34)?.times(10))
            return legacyReading(
                Layout.PLAY3_100TL, pvW = s16(34)?.times(10), strings = listOfNotNull(input1), phases = phases,
                status = u16(20), daily = u32(12), total = u32(57),
                extra = listOf(
                    "Active power (AC)" to fmt(s16(29)?.times(10), "W"),
                    "Apparent power" to fmt(s16(28)?.times(10), "VA"),
                    "Reactive power" to fmt(s16(30)?.times(10), "var"),
                    "cos φ" to fmt(s16(31)?.div(1000.0), "", 3),
                    "Isolation resistance" to fmt(u16(35), "kΩ"),
                    "Wattmeter (self-consumption)" to fmt(s16(36)?.times(10), "W"),
                    "Self-consumption ratio" to fmt(u16(37), "%"),
                    "Power reduction" to fmt(u16(52), "%"),
                    "Power reduction reason" to enum(u16(53), LEGACY_REDUCTION_3PLAY),
                    "Reactive power reference" to enum(u16(54), LEGACY_QREF),
                    "Stop event" to u16(18)?.toString(),
                    "String kit warning 1" to bits(u16(50)?.toLong(), 4),
                    "String kit warning 2" to bits(u16(51)?.toLong(), 4),
                    "Operating hours" to fmt(u32(59), "h"),
                    "Partial energy" to fmt(u32(6), "kWh"),
                    *(0 until 12).flatMap { i ->
                        val packed = u16(38 + i) ?: return@flatMap emptyList()
                        listOf(
                            "String ${2 * i + 1} current" to fmt((packed and 0xFF) / 10.0, "A", 1),
                            "String ${2 * i + 2} current" to fmt((packed shr 8) / 10.0, "A", 1),
                        )
                    }.toTypedArray(),
                ),
                alarms = 14..17,
                warning = 19,
            )
        }

        private fun pv(i: Int, volts: Int?, centiAmps: Int?, watts: Int?): PvString? {
            val v = volts?.toDouble()?.takeIf { it > 0 } ?: return null
            return PvString(i, v, centiAmps?.div(100.0) ?: 0.0, watts)
        }

        private fun legacyReading(
            l: Layout, pvW: Int?, strings: List<PvString>, phases: List<PhaseReading>, status: Int?, daily: Long?, total: Long?,
            extra: List<Pair<String, String?>>, alarms: IntRange, warning: Int = alarms.last, temperatureDerating: Int? = null,
        ): DeviceReading = DeviceReading(
            pvPowerW = pvW?.coerceAtLeast(0),
            pvStrings = strings,
            phases = phases,
            status = status?.let { LEGACY_STATES[it] ?: "State $it" },
            today = EnergyTotals(pvKwh = daily?.div(100.0)),
            lifetimePvKwh = total?.toDouble(),
            problems = alarms.filter { it != warning }.mapIndexedNotNull { i, a ->
                u16(a)?.takeIf { it != 0 }?.let { DeviceProblem(Kind.FAULT, "Alarm code ${i + 1}: ${bits(it.toLong(), 4)}") }
            } + listOfNotNull(
                u16(warning)?.takeIf { it != 0 }?.let { DeviceProblem(Kind.WARNING, "Warning code ${bits(it.toLong(), 4)}") },
                temperatureDerating?.let { DeviceProblem(Kind.WARNING, "Temperature derating (reason $it)") },
            ),
            details = listOf(
                section(
                    "Inverter",
                    "Register map" to l.label,
                    "Status" to enum(status, LEGACY_STATES),
                    "Clock" to clock(),
                    *alarms.filter { it != warning }.mapIndexed { i, a -> "Alarm code ${i + 1}" to bits(u16(a)?.toLong(), 4) }.toTypedArray(),
                    "Warning code" to bits(u16(warning)?.toLong(), 4),
                ),
                section(
                    "AC output",
                    *phases.flatMap { p ->
                        listOf("${p.name} voltage" to fmt(p.voltageV, "V", 1), "${p.name} current" to fmt(p.currentA, "A", 2))
                    }.toTypedArray(),
                    "Frequency" to fmt(phases.firstOrNull()?.frequencyHz, "Hz", 2),
                ),
                section(
                    "DC inputs",
                    "PV power" to fmt(pvW, "W"),
                    *strings.flatMap { s ->
                        listOf("PV${s.index} voltage" to fmt(s.voltageV, "V", 0), "PV${s.index} current" to fmt(s.currentA, "A", 2), "PV${s.index} power" to fmt(s.powerW, "W"))
                    }.toTypedArray(),
                ),
                section("More", *extra.toTypedArray()),
                section("Energy", "Today" to fmt(daily?.div(100.0), "kWh", 2), "Total" to fmt(total, "kWh")),
            ).filter { it.rows.isNotEmpty() },
        )

        private fun clock(): String? {
            val p = (0..5).map { u16(it) ?: return null }
            return String.format(Locale.ROOT, "%04d-%02d-%02d %02d:%02d:%02d", p[0], p[1], p[2], p[3], p[4], p[5])
        }

        private fun reasons(mask: Int, names: Map<Int, String>): String =
            if (mask == 0) "None" else names.filterKeys { mask and (1 shl it) != 0 }.values.joinToString(", ").ifEmpty { bits(mask.toLong(), 4)!! }
    }

    internal companion object {
        /** Gateway path unavailable / target didn't respond: the device is there but not answering. */
        val GATEWAY_NO_ANSWER = setOf(0x0A, 0x0B)
        const val LEGACY_COUNT = 67 // input registers 0–66: covers the 1Play, 3Play and 3Play 100TL maps

        /** Two requests including the reserved registers 0x1025–0x1026, 0x1029–0x1035, 0x1074–0x107F. */
        val WIDE_BLOCKS = listOf(0x1001 to 65, 0x1050 to 68)
        /** Only defined registers: 0x1001–0x1024, 0x1027–0x1028, 0x1036–0x1041, 0x1050–0x1073, 0x1080–0x1093. */
        val STRICT_BLOCKS = listOf(0x1001 to 36, 0x1027 to 2, 0x1036 to 12, 0x1050 to 36, 0x1080 to 20)
        val MPPT_BASES = listOf(0x1010, 0x1014, 0x1018, 0x103E, 0x1080, 0x1084, 0x1088, 0x108C, 0x1090)

        val CURRENT_STATES = mapOf(0 to "Factory default", 1 to "Disconnected from grid", 3 to "Connected to grid")
        val CURRENT_REDUCTION_BITS = mapOf(
            0 to "Over-temperature", 1 to "PV input voltage", 2 to "Fan locked", 3 to "Initial ramp", 4 to "High grid frequency",
            5 to "EMS communication lost", 7 to "Maximum apparent power", 8 to "Night powered mode",
            9 to "Communications (wattmeter or master)", 10 to "High grid voltage", 11 to "Low grid frequency",
            12 to "Remote off power gradient", 13 to "Configuration", 15 to "P(f) algorithm",
        )
        /** Reduction reasons that are problems; the others are grid support, ramps, settings or communication. */
        val CURRENT_REDUCTION_PROBLEMS = mapOf(0 to "Over-temperature derating", 2 to "Fan locked")
        val LEGACY_STATES = mapOf(0 to "Not ready to connect", 1 to "Waiting to connect", 2 to "Connected to the grid")
        val LEGACY_REDUCTION = mapOf(
            0 to "No reduction", 1 to "No reduction", 2 to "Temperature", 3 to "Temperature", 4 to "Temperature",
            5 to "Communications", 6 to "Grid frequency / active power control", 7 to "Grid voltage / active power control",
            8 to "Reactive priority and output current limit", 9 to "Configuration", 10 to "Connection initial ramp",
            11 to "Self-consumption mode", 12 to "Manufacturer reserved", 13 to "Pac vs Vac algorithm",
            14 to "cos φ or tangent target", 15 to "Low Vac to reach nominal power",
        )
        /** 3Play maps: values 2–4 are "Not defined" there (temperature on the 1Play). */
        val LEGACY_REDUCTION_3PLAY = LEGACY_REDUCTION + (2..4).associateWith { "Not defined" }
        val LEGACY_QREF = mapOf(
            0 to "cos φ configuration", 1 to "Qref manual", 2 to "cos φ manual", 3 to "Qref communications",
            4 to "cos φ communications", 5 to "Qref Q(V)", 6 to "cos φ (P)",
        )
    }
}
