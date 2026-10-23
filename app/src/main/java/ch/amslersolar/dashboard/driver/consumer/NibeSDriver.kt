// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.consumer

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
import ch.amslersolar.dashboard.model.DeviceReading
import kotlinx.coroutines.delay
import java.io.IOException
import kotlin.math.roundToInt

/**
 * NIBE S-series heat pumps and indoor modules (S1155/S1255/S1156/S1256, VVM S320/S325/S500, SVM S332, SMO S40 with
 * S2125/F2120/F2040, S735) over their built-in Modbus TCP. READ ONLY: this driver never writes a register.
 *
 * Spec: NIBE "Technical information MODBUS S-SERIES" M12676EN (TIF EN 2608), docs/specs/nibe/:
 * - Enable in menu 7.5.9 "Modbus TCP/IP" (software 2.2.1 or later); "Reading Modbus only" can be ticked there.
 *   Port 502. Only clients from private address ranges (10/8, 172.16/12, 192.168/16) are accepted; an IP address
 *   restriction can be set in the same menu.
 * - FC04 input registers for measurements, FC03 holding registers for settings (two are read here: degree minutes
 *   11 and operating mode 237). Register numbers are used as protocol addresses as listed ("ID").
 * - At most 20 registers per query and 100 registers per second: requests are ≤ [MAX_QUERY] registers and a
 *   sliding one-second budget of [MAX_PER_SECOND] registers is enforced (with a pause if needed).
 * - 32-bit values: "registers are shown in reverse order, meaning that reading holding registers 11–12 will display
 *   the values as register 12 and register 11", i.e. the low word comes first. Implemented here (RegisterBlock is
 *   high word first).
 * - Factors: value = raw / factor (temperatures s16 / 10, used power u32 / 10 W, heat energy u32 / 10 kWh, pulse
 *   energy meters u32 / 100 kWh, compressor frequency / 10 Hz).
 * - "Available registers are shown in the display for the current product and its installed and activated
 *   accessories": registers a model lacks are expected to be rejected; blocks are then read register by register
 *   and the rejected ones are left out (asked again every [RETRY_MISSING_POLLS] polls).
 *
 * Identification: the spec has no model or serial register. A NIBE is recognised by plausibility: outdoor
 * temperature BT1 (1) within −50..70 °C (or not connected), operating prioritisation 1028 one of the documented
 * values 10/20/30/40/60 and active alarm 2195 0 or 1, all over FC04.
 *
 * Mapping to [DeviceReading]:
 * - [DeviceReading.loadPowerW]: "Instantaneous used power" 2166 (u32, W, factor 10; compressor and addition).
 * - [DeviceReading.temperatureC]: outdoor temperature BT1 (1), else BT28 of the outdoor unit (1621).
 * - [DeviceReading.status]: Fault (2195 active alarm) > Defrost (1805) > operating prioritisation 1028
 *   (10 Standby, 20 Hot water, 30 Heating, 40 Pool, 60 Cooling).
 * - today.loadKwh: null. The spec has no daily energy counter.
 *
 * Polling: ≈ 90 registers in 15 requests; read in full every [REFRESH_POLLS] polls, the cached reading is
 * returned in between.
 *
 * UNVERIFIED: unit id 1 (not given in the spec); 0x8000 (−32768) as the "sensor not connected" value of s16
 * temperatures; whether registers a model lacks are rejected with a Modbus exception (rather than answered with 0);
 * that the pulse energy meters 396/398 are electricity meters (shown as plain counters only); which outdoor unit
 * registers apply to which product (S2125/F2120 slave 1 and F2040 share 1621/1803/1805).
 */
class NibeSDriver(
    private val input: RegisterReader,
    private val holding: RegisterReader,
    private val unitId: Int,
    /** Milliseconds, monotonic; and the pause. Replaceable for tests of the rate limit. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : DeviceDriver {
    constructor(client: ModbusTcpClient, unitId: Int) : this(
        RegisterReader { u, r, c -> client.readInputRegisters(u, r, c) },
        RegisterReader { u, r, c -> client.readHoldingRegisters(u, r, c) },
        unitId,
    )

    private val split = mutableSetOf<Int>() // blocks (by start) the pump rejected as a whole
    private val missing = mutableSetOf<Int>() // single fields it rejected
    private val sent = ArrayDeque<Pair<Long, Int>>() // (time ms, registers) of requests in the last second
    private var cached: DeviceReading? = null
    private var polls = 0

    override suspend fun identify(): DeviceInfo {
        val img = Image(fetch(input, IDENT_FIELDS))
        if (!img.plausible()) throw IOException("No NIBE S-series heat pump found at unit id $unitId (implausible register values)")
        return DeviceInfo(model = "NIBE S-series heat pump", serial = "")
    }

    override suspend fun read(): DeviceReading {
        cached?.let { if (polls % REFRESH_POLLS != 0) { polls++; return it } }
        if (polls % RETRY_MISSING_POLLS == 0) {
            split.clear()
            missing.clear()
        }
        val img = Image(fetch(input, INPUT_FIELDS) + fetch(holding, HOLDING_FIELDS).mapKeys { HOLDING + it.key })
        if (!img.plausible()) throw IOException("Unexpected register values: not a NIBE S-series heat pump")
        val reading = parse(img).copy(details = details(img))
        cached = reading
        polls++
        return reading
    }

    /** Reads [fields] (address to size) in blocks of at most 20 registers, falling back to single fields. */
    private suspend fun fetch(reader: RegisterReader, fields: List<Pair<Int, Int>>): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        for (block in blocks(fields)) {
            val start = block.first().first
            val end = block.last().let { it.first + it.second }
            if (block.size > 1 && start !in split) {
                try {
                    out.putAll(request(reader, start, end - start).values())
                    continue
                } catch (_: ModbusException) {
                    split += start
                }
            }
            for ((addr, size) in block) {
                if (addr in missing) continue
                try {
                    out.putAll(request(reader, addr, size).values())
                } catch (_: ModbusException) {
                    missing += addr
                }
            }
        }
        return out
    }

    /** One query, keeping to ≤ 100 registers per second. */
    private suspend fun request(reader: RegisterReader, register: Int, count: Int): RegisterBlock {
        require(count <= MAX_QUERY)
        while (true) {
            val now = clock()
            while (sent.isNotEmpty() && now - sent.first().first >= 1_000) sent.removeFirst()
            if (sent.sumOf { it.second } + count <= MAX_PER_SECOND) break
            sleep((1_000 - (now - sent.first().first)).coerceAtLeast(10))
        }
        sent.addLast(clock() to count)
        return reader.read(unitId, register, count)
    }

    private fun RegisterBlock.values(): Map<Int, Int> = (start until start + count).associateWith { u16(it) }

    private fun parse(img: Image): DeviceReading = DeviceReading(
        loadPowerW = img.u32(2166)?.let { (it / 10.0).roundToInt() },
        temperatureC = img.temp(1) ?: img.temp(1621),
        status = img.status(),
        problems = img.problems(),
    )

    private fun details(img: Image): List<DetailSection> {
        val alarm = img.u16(2195)
        return listOf(
            section(
                "Heat pump",
                "Operating prioritisation" to enum(img.u16(1028), PRIORITIES),
                "Operating mode" to enum(img.u16(HOLDING + 237), mapOf(0 to "Auto", 1 to "Manual", 2 to "Add. heat only")),
                "Compressor" to enum(img.u16(1100), mapOf(0 to "Off", 1 to "On")),
                "Compressor frequency" to fmt((img.u16(1046)?.let { it / 10.0 }) ?: img.temp(1803), "Hz", 1),
                "Requested compressor frequency" to fmt(img.u16(301)?.let { it / 10.0 }, "Hz", 1),
                "Defrost" to enum(img.u16(1805), DEFROST),
                "Diverter valve (QN10)" to enum(img.u16(2196), mapOf(0 to "Heating", 1 to "Hot water")),
                "Heating medium pump (GP1)" to fmt(img.u16(1102), "%"),
                "Add. heat steps" to img.u16(1029)?.toString(),
                "Degree minutes" to fmt(img.s32(HOLDING + 11)?.let { it / 10.0 }, decimals = 1),
                "Used power" to fmt(img.u32(2166)?.let { it / 10.0 }, "W", 0),
                "Alarm" to when (alarm) {
                    null -> null
                    0 -> "No alarm"
                    else -> "Active" + (img.u16(1975)?.let { " (alarm $it)" } ?: "")
                },
            ),
            section(
                "Temperatures",
                "Outdoor (BT1)" to fmt(img.temp(1), "°C", 1),
                "Outdoor unit (BT28)" to fmt(img.temp(1621), "°C", 1),
                "Supply (BT2)" to fmt(img.temp(5), "°C", 1),
                "Return (BT3)" to fmt(img.temp(7), "°C", 1),
                "Calculated supply" to fmt(img.temp(1017), "°C", 1),
                "Hot water top (BT7)" to fmt(img.temp(8), "°C", 1),
                "Hot water charging (BT6)" to fmt(img.temp(9), "°C", 1),
                "Hot water start (BT5)" to fmt(img.temp(2014), "°C", 1),
                "Brine in (BT10)" to fmt(img.temp(10), "°C", 1),
                "Brine out (BT11)" to fmt(img.temp(11), "°C", 1),
                "Condenser supply (BT12)" to fmt(img.temp(12), "°C", 1),
                "Discharge (BT14)" to fmt(img.temp(13), "°C", 1),
                "Liquid line (BT15)" to fmt(img.temp(14), "°C", 1),
                "Suction gas (BT17)" to fmt(img.temp(16), "°C", 1),
                "Exhaust air (BT20)" to fmt(img.temp(19), "°C", 1),
                "Extract air (BT21)" to fmt(img.temp(20), "°C", 1),
                "External supply (BT25)" to fmt(img.temp(39), "°C", 1),
                "Room (BT50)" to fmt(img.temp(26), "°C", 1),
                "Flow (BF1)" to fmt(img.temp(40), "l/min", 1),
            ),
            section(
                "Energy",
                "Heat produced, heating" to fmt(img.u32(1577)?.let { it / 10.0 }, "kWh", 1),
                "Heat produced, hot water" to fmt(img.u32(1575)?.let { it / 10.0 }, "kWh", 1),
                "Heat by compressor, heating" to fmt(img.u32(1585)?.let { it / 10.0 }, "kWh", 1),
                "Heat by compressor, hot water" to fmt(img.u32(1583)?.let { it / 10.0 }, "kWh", 1),
                "Heat by compressor, pool" to fmt(img.u32(1581)?.let { it / 10.0 }, "kWh", 1),
                "Pulse energy meter (BE7/BF3)" to fmt(img.u32(396)?.let { it / 100.0 }, "kWh", 2),
                "Pulse energy meter (BE6/BF2)" to fmt(img.u32(398)?.let { it / 100.0 }, "kWh", 2),
                "Compressor starts" to img.s32(1083)?.toString(),
                "Compressor operating time" to fmt(img.s32(1087), "h"),
                "Add. heat operating time" to fmt(img.s32(1025)?.let { it / 10.0 }, "h", 1),
            ),
        ).filter { it.rows.isNotEmpty() }
    }

    /** Register values by address; holding registers are offset by [HOLDING]. */
    internal class Image(private val regs: Map<Int, Int>) {
        fun u16(a: Int): Int? = regs[a]
        fun s16(a: Int): Int? = u16(a)?.toShort()?.toInt()
        fun temp(a: Int): Double? = s16(a)?.takeIf { it != Short.MIN_VALUE.toInt() }?.let { it / 10.0 }

        /** 32-bit value, low word first. */
        fun u32(a: Int): Long? {
            val lo = u16(a) ?: return null
            val hi = u16(a + 1) ?: return null
            return (hi.toLong() shl 16) or lo.toLong()
        }
        fun s32(a: Int): Int? = u32(a)?.toInt()

        fun plausible(): Boolean {
            val outdoor = s16(1) ?: return false
            if (outdoor != Short.MIN_VALUE.toInt() && outdoor !in -500..700) return false
            val priority = u16(1028) ?: return false
            if (priority !in PRIORITIES) return false
            val alarm = u16(2195) ?: return false
            return alarm in 0..1
        }

        /** 2195 "Active alarm" with the alarm number 1975 (the spec lists no alarm texts). */
        fun problems(): List<DeviceProblem> {
            if (u16(2195) != 1) return emptyList()
            val number = u16(1975)?.takeIf { it != 0 }
            return listOf(DeviceProblem(DeviceProblem.Kind.FAULT, "Heat pump alarm" + (number?.let { " (alarm $it)" } ?: "")))
        }

        fun status(): String? {
            if (u16(2195) == 1) return "Fault"
            if (u16(1805) == 1) return "Defrost"
            return when (u16(1028)) {
                10 -> "Standby"
                20 -> "Hot water"
                30 -> "Heating"
                40 -> "Pool"
                60 -> "Cooling"
                else -> null
            }
        }
    }

    internal companion object {
        const val MAX_QUERY = 20
        const val MAX_PER_SECOND = 100
        const val REFRESH_POLLS = 2 // full read every 10 s at a 5 s poll interval
        const val RETRY_MISSING_POLLS = 120
        private const val MAX_GAP = 4
        /** Holding registers are kept in the same image, offset by this. */
        const val HOLDING = 100_000

        private fun r(a: Int) = a to 1
        private fun d(a: Int) = a to 2 // 32-bit

        val IDENT_FIELDS = listOf(r(1), r(1028), r(2195))
        val INPUT_FIELDS = listOf(
            r(1), r(5), r(7), r(8), r(9), r(10), r(11), r(12), r(13), r(14), r(16), r(19), r(20),
            r(26), r(39), r(40),
            r(301), d(396), d(398),
            r(1017), d(1025), r(1028), r(1029), r(1046), d(1083), d(1087), r(1100), r(1102),
            d(1575), d(1577), d(1581), d(1583), d(1585), r(1621), r(1803), r(1805),
            r(1975), r(2014), d(2166), r(2195), r(2196),
        )
        val HOLDING_FIELDS = listOf(d(11), r(237))

        val PRIORITIES = mapOf(10 to "Off", 20 to "Hot water", 30 to "Heat", 40 to "Pool", 60 to "Cooling")
        val DEFROST = mapOf(0 to "Off", 1 to "Active", 2 to "Passive")

        /** Fields grouped into queries of at most 20 registers, bridging gaps of up to [MAX_GAP]. */
        fun blocks(fields: List<Pair<Int, Int>>): List<List<Pair<Int, Int>>> {
            val out = mutableListOf<MutableList<Pair<Int, Int>>>()
            for (field in fields.sortedBy { it.first }) {
                val current = out.lastOrNull()
                val start = current?.first()?.first
                val end = current?.last()?.let { it.first + it.second }
                if (current != null && field.first - end!! <= MAX_GAP && field.first + field.second - start!! <= MAX_QUERY) current += field
                else out += mutableListOf(field)
            }
            return out
        }
    }
}
