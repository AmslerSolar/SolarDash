// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.driver.victron

import ch.amslersolar.solardash.driver.DeviceDriver
import ch.amslersolar.solardash.driver.Details.enum
import ch.amslersolar.solardash.driver.Details.fmt
import ch.amslersolar.solardash.driver.Details.section
import ch.amslersolar.solardash.driver.modbus.ModbusException
import ch.amslersolar.solardash.driver.modbus.ModbusTcpClient
import ch.amslersolar.solardash.driver.modbus.RegisterBlock
import ch.amslersolar.solardash.driver.sunspec.RegisterReader
import ch.amslersolar.solardash.model.BatteryState
import ch.amslersolar.solardash.model.DetailSection
import ch.amslersolar.solardash.model.DeviceInfo
import ch.amslersolar.solardash.model.DeviceProblem
import ch.amslersolar.solardash.model.DeviceReading
import java.io.IOException
import java.time.Instant
import kotlin.math.roundToInt

/**
 * Victron Energy GX device (Cerbo GX, Venus GX, CCGX, Ekrano … running Venus OS): whole-installation view from the
 * system service `com.victronenergy.system` over Modbus TCP.
 *
 * Register map: "CCGX-Modbus-TCP-register-list-3.81.xlsx", sheet "Field list" (docs/specs/victron/), plus
 * dbus_modbustcp-attributes.csv from the same folder for registers 930–937 (system state), which are only in the
 * attributes file, not in the 3.81 sheet. Port 502, unit id 100 ("Unit ID mapping" sheet: 100 = all registers
 * listed as com.victronenergy.system).
 *
 * Setup: on the GX enable Settings → Integrations (older Venus OS: Settings → Services) → Modbus TCP server. The
 * same page lists the available services and their unit ids.
 *
 * - Read with function 03 (read holding registers). That the GX answers function 04 the same way is not stated in
 *   the register list (unverified), so only 03 is used.
 * - The system range 800–937 has documented reserved registers "to allow fetching blocks" (document versions), so it
 *   is read in large requests (live: 806–930 and 931–937; static: 800–805, 834–835, 5400–5407 Dynamic ESS). Older firmware
 *   lacks 868–918 (added in rev 51): if the GX rejects a block (exception 2, or gateway exceptions 0x0A/0x0B), it is
 *   read again as contiguous runs, then field by field; fields it rejects are left out until the static refresh.
 * - Values that don't exist on an installation (a third phase, a genset, AC-coupled PV, …) are expected to be
 *   rejected by the GX or to read as 0. "Not available" markers 0xFFFF (uint16), 0x8000 (int16), 0xFFFFFFFF
 *   (uint32) and 0x80000000 (int32) are also treated as missing (unverified: the list doesn't name them).
 * - Prefers the 32-bit power registers (884–918) and falls back to the 16-bit ones (808–825).
 *
 * Signs (verified from the register list descriptions):
 * - Grid L1–L3 (820–822 / 908–912) "Power supplied by Grid to system" → positive = import; our [DeviceReading.gridPowerW]
 *   is the negated sum (positive = export).
 * - Battery power 842 / current 841 "Positive: battery being charged" → our [BatteryState.powerW] is negated
 *   (positive = discharging).
 * - PV: AC-coupled on output (884–888 / 808–810), on input/grid (890–894 / 811–813), on generator (896–900 /
 *   814–816, "will never be used") plus DC-coupled solar chargers (850). All ≥ 0.
 * - Load: AC consumption L1–L3 (902–906 / 817–819), "Power supplied by system to loads". DC loads (860) are only in
 *   the details.
 *
 * Not available from the system service: per-phase voltage, current and frequency (they live in the grid meter /
 *   VE.Bus services under other unit ids), so [DeviceReading.phases] stays empty and per-phase powers are in the
 *   details; temperatures; daily or lifetime yield (only per solar charger / PV inverter), so `today` stays empty
 *   and the app integrates the power history.
 */
class VictronGxDriver(private val reader: RegisterReader, private val unitId: Int) : DeviceDriver {
    constructor(client: ModbusTcpClient, unitId: Int) : this(RegisterReader { u, r, c -> client.readHoldingRegisters(u, r, c) }, unitId)

    private val rejected = mutableSetOf<Pair<Int, Int>>() // reads (start, count) the GX rejected
    private var static: Map<Int, Int>? = null
    private var polls = 0

    override suspend fun identify(): DeviceInfo {
        val img = try {
            Image(fetch(STATIC_FIELDS))
        } catch (e: ModbusException) {
            throw IOException("Unit $unitId is not a Victron GX system service (${e.message})", e)
        }
        val serial = serial(img) ?: throw IOException("Unit $unitId is not a Victron GX: no MAC address in register 800")
        return DeviceInfo(model = listOfNotNull("Victron Energy GX", firmware(img)).joinToString(" "), serial = serial)
    }

    override suspend fun read(): DeviceReading {
        if (static == null || polls % STATIC_REFRESH_POLLS == 0) {
            rejected.clear()
            static = fetch(STATIC_FIELDS)
        }
        polls++
        val live = fetch(LIVE_FIELDS)
        if (live.isEmpty()) throw IOException("Victron GX unit $unitId returned no system values")
        val img = Image(static.orEmpty() + live)
        return parse(img).copy(details = details(img))
    }

    /** Reads [fields] (address to size) in blocks; a rejected block is split into contiguous runs, then single fields. */
    private suspend fun fetch(fields: List<Pair<Int, Int>>): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        for (block in blocks(fields)) readOrSplit(block, out)
        return out
    }

    private suspend fun readOrSplit(fields: List<Pair<Int, Int>>, out: MutableMap<Int, Int>) {
        val start = fields.first().first
        val count = fields.last().let { it.first + it.second } - start
        if ((start to count) !in rejected) {
            try {
                out.putAll(reader.read(unitId, start, count).values())
                return
            } catch (_: ModbusException) {
                rejected += start to count // not on this firmware, or the value isn't available on this installation
            }
        }
        if (fields.size == 1) return
        val runs = runs(fields)
        for (part in if (runs.size > 1) runs else fields.map { listOf(it) }) readOrSplit(part, out)
    }

    private fun RegisterBlock.values(): Map<Int, Int> = (start until start + count).associateWith { u16(it) }

    /** Register values by address; "not available" markers read as null. */
    internal class Image(private val regs: Map<Int, Int>) {
        fun raw(a: Int): Int? = regs[a]
        fun u16(a: Int): Int? = regs[a]?.takeIf { it != 0xFFFF }
        fun s16(a: Int): Int? = regs[a]?.toShort()?.toInt()?.takeIf { it != Short.MIN_VALUE.toInt() }
        fun u32(a: Int): Long? {
            val hi = regs[a] ?: return null
            val lo = regs[a + 1] ?: return null
            return ((hi.toLong() shl 16) or lo.toLong()).takeIf { it != 0xFFFFFFFFL }
        }
        fun s32(a: Int): Int? {
            val hi = regs[a] ?: return null
            val lo = regs[a + 1] ?: return null
            return ((hi shl 16) or lo).takeIf { it != Int.MIN_VALUE }
        }
        fun u64(a: Int): Long? {
            val words = (a until a + 4).map { regs[it] ?: return null }
            return words.fold(0L) { acc, w -> (acc shl 16) or w.toLong() }
        }
        fun text(a: Int, n: Int): String? {
            val chars = (a until a + n).map { regs[it] ?: return null }.flatMap { listOf((it shr 8).toChar(), (it and 0xFF).toChar()) }
            return String(chars.toCharArray()).trim { it <= ' ' }.takeIf { it.isNotEmpty() }
        }
    }

    internal companion object {
        const val STATIC_REFRESH_POLLS = 12 // identity, firmware, Dynamic ESS: about once a minute
        private const val MAX_BLOCK = 125
        private const val MAX_GAP = 16 // the GX answers reserved registers in the system range

        private fun r(a: Int) = a to 1
        private fun d(a: Int) = a to 2

        val STATIC_FIELDS = listOf(800 to 6, r(834), r(835)) + (5400..5407).map(::r)
        val LIVE_FIELDS = (806..826).map(::r) + (830 to 4) + (840..846).map(::r) + listOf(r(850), r(851), r(855), r(860), r(865), r(866)) +
            (868..918 step 2).map(::d) + (930..937).map(::r)

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

        /** [fields] split where they aren't directly adjacent. */
        fun runs(fields: List<Pair<Int, Int>>): List<List<Pair<Int, Int>>> {
            val out = mutableListOf<MutableList<Pair<Int, Int>>>()
            for (field in fields) {
                val last = out.lastOrNull()?.last()
                if (last != null && last.first + last.second == field.first) out.last() += field else out += mutableListOf(field)
            }
            return out
        }

        val BATTERY_STATES = mapOf(0 to "Idle", 1 to "Charging", 2 to "Discharging")
        val ACTIVE_INPUT = mapOf(0 to "Unknown", 1 to "Grid", 2 to "Generator", 3 to "Shore power", 240 to "Not connected")
        val RELAY = mapOf(0 to "Open", 1 to "Closed")
        val NO_YES = mapOf(0 to "No", 1 to "Yes")

        /** 930 /SystemState/State, from dbus_modbustcp-attributes.csv. */
        val SYSTEM_STATES = mapOf(
            0 to "Off", 1 to "Low power", 2 to "VE.Bus fault condition", 3 to "Bulk charging", 4 to "Absorption charging",
            5 to "Float charging", 6 to "Storage mode", 7 to "Equalisation charging", 8 to "Passthru", 9 to "Inverting",
            10 to "Assisting", 244 to "Battery sustain", 252 to "External control", 256 to "Discharging", 257 to "Sustain",
            258 to "Recharge", 259 to "Scheduled recharge",
        )
        private val DESS_ACTIVE = mapOf(0 to "Off", 1 to "Active")
        private val DESS_FEED_IN = mapOf(0 to "Not allowed", 1 to "Allowed")
        private val DESS_ERRORS = mapOf(
            0 to "No error", 1 to "No ESS", 2 to "ESS mode", 3 to "No matching schedule", 4 to "SOC low", 5 to "Battery capacity not configured",
        )
        // Codes 1 and 2 are swapped between the 3.81 sheet and the attributes file, so only the unambiguous ones are named.
        private val DESS_RESTRICTIONS = mapOf(0 to "None", 1 to "One direction restricted", 2 to "One direction restricted", 3 to "No flow between battery and grid")
        private val DESS_STRATEGIES = mapOf(0 to "Target SOC", 1 to "Self-consumption", 2 to "Pro battery", 3 to "Pro grid")

        /** 800 /Serial: the GX's MAC address as 12 hex characters. */
        fun serial(img: Image): String? = img.text(800, 6)?.takeIf { MAC.matches(it) }
        private val MAC = Regex("[0-9A-Fa-f]{12}")

        /** 834 "render in hexadecimal" (0x0331 → v3.31), 835 beta release (0 for official releases). Unverified format. */
        fun firmware(img: Image): String? {
            val v = img.u16(834)?.takeIf { it != 0 } ?: return null
            val text = "v%X.%02X".format(v shr 8, v and 0xFF)
            val beta = img.u16(835)?.takeIf { it != 0 }
            return if (beta != null) "$text~$beta" else text
        }

        private val PHASES = listOf("L1", "L2", "L3")

        /** Per-phase power: the 32-bit register when it's there, else the 16-bit one. */
        private fun phase(img: Image, wide: Int, narrow: Int, i: Int, signed: Boolean): Int? =
            if (signed) img.s32(wide + 2 * i) ?: img.s16(narrow + i)
            else img.u32(wide + 2 * i)?.toInt() ?: img.u16(narrow + i)

        fun pvOnOutput(img: Image, i: Int) = phase(img, 884, 808, i, signed = false)
        fun pvOnInput(img: Image, i: Int) = phase(img, 890, 811, i, signed = false)
        fun pvOnGenset(img: Image, i: Int) = phase(img, 896, 814, i, signed = false)
        fun consumption(img: Image, i: Int) = phase(img, 902, 817, i, signed = false)
        fun grid(img: Image, i: Int) = phase(img, 908, 820, i, signed = true) // + import
        fun genset(img: Image, i: Int) = phase(img, 914, 823, i, signed = true)

        private fun sum(values: List<Int?>): Int? = values.filterNotNull().takeIf { it.isNotEmpty() }?.sum()

        fun acPv(img: Image): Int? = sum((0..2).flatMap { listOf(pvOnOutput(img, it), pvOnInput(img, it), pvOnGenset(img, it)) })

        fun parse(img: Image): DeviceReading {
            val dcPv = img.u16(850)
            val pv = sum(listOf(acPv(img), dcPv))

            val vRaw = img.u16(840)
            val battery = img.s16(842)?.let { victronPower -> // + charging
                val power = -victronPower
                BatteryState(
                    powerW = power,
                    socPercent = img.u16(843)?.takeIf { it in 0..100 },
                    voltageV = vRaw?.takeIf { it > 0 }?.div(10.0),
                    mode = BATTERY_STATES[img.u16(844)] ?: when {
                        power > 0 -> "Discharging"
                        power < 0 -> "Charging"
                        else -> "Idle"
                    },
                )
            }

            val grid = sum((0..2).map { grid(img, it) })?.let { -it }
            val load = sum((0..2).map { consumption(img, it) })

            return DeviceReading(
                pvPowerW = pv,
                battery = battery,
                gridPowerW = grid,
                loadPowerW = load,
                status = img.u16(930)?.let { SYSTEM_STATES[it] ?: "State $it" },
                problems = problems(img),
            )
        }

        /**
         * 930 state 2 "VE.Bus Fault condition", 826 active input 240 "Not connected" (the inverter/charger runs
         * without AC input), and a Dynamic ESS error (5404) while Dynamic ESS is active (5400). The system service has
         * no alarm registers; those are per device under other unit ids.
         */
        fun problems(img: Image): List<DeviceProblem> = buildList {
            if (img.u16(930) == 2) add(DeviceProblem(DeviceProblem.Kind.FAULT, "VE.Bus fault condition (state 2)"))
            if (img.s16(826) == 240) add(DeviceProblem(DeviceProblem.Kind.MAINS_LOST, "AC input not connected"))
            val dessError = img.u16(5404)
            if (img.u16(5400) == 1 && dessError != null && dessError != 0) {
                add(DeviceProblem(DeviceProblem.Kind.WARNING, "Dynamic ESS: ${DESS_ERRORS[dessError] ?: "Error code"} ($dessError)"))
            }
        }

        private fun w(v: Int?) = fmt(v, "W")
        private fun perPhase(label: String, f: (Int) -> Int?): Array<Pair<String, String?>> =
            (0..2).map { "$label ${PHASES[it]}" to w(f(it)) }.toTypedArray()

        fun details(img: Image): List<DetailSection> {
            val out = mutableListOf<DetailSection>()
            out += section(
                "GX device",
                "Serial (MAC address)" to img.text(800, 6),
                "Firmware" to firmware(img),
                "System time (UTC)" to img.u64(830)?.takeIf { it > 0 }?.let { runCatching { Instant.ofEpochSecond(it).toString() }.getOrNull() },
                "Active AC input" to enum(img.s16(826), ACTIVE_INPUT),
                "System state" to enum(img.u16(930), SYSTEM_STATES),
                "Relay 1" to enum(img.u16(806), RELAY),
                "Relay 2" to enum(img.u16(807), RELAY),
            )
            out += section(
                "Solar",
                "Total" to w(sum(listOf(acPv(img), img.u16(850)))),
                *perPhase("AC-coupled on output", { pvOnOutput(img, it) }),
                *perPhase("AC-coupled on input", { pvOnInput(img, it) }),
                *perPhase("AC-coupled on generator", { pvOnGenset(img, it) }),
                "DC-coupled (solar chargers)" to w(img.u16(850)),
                "DC-coupled current" to fmt(img.s16(851)?.div(10.0), "A", 1),
            )
            out += section(
                "Grid",
                "Total (+ import)" to w(sum((0..2).map { grid(img, it) })),
                *perPhase("Power (+ import)", { grid(img, it) }),
            )
            if ((0..2).any { (genset(img, it) ?: 0) != 0 }) {
                out += section("Generator", "Total" to w(sum((0..2).map { genset(img, it) })), *perPhase("Power", { genset(img, it) }))
            }
            out += section(
                "Consumption",
                "AC total" to w(sum((0..2).map { consumption(img, it) })),
                *perPhase("AC", { consumption(img, it) }),
                *perPhase("Between meter and inverter/charger", { img.s32(872 + 2 * it) }),
                *perPhase("On inverter/charger output", { img.s32(878 + 2 * it) }),
                "DC system (+ from battery)" to w(img.s16(860)),
            )
            out += section(
                "Battery",
                "State of charge" to fmt(img.u16(843), "%"),
                "Voltage" to fmt(img.u16(840)?.div(10.0), "V", 1),
                "Current (+ charging)" to fmt(img.s16(841)?.div(10.0), "A", 1),
                "Power (+ charging)" to w(img.s16(842)),
                "State" to enum(img.u16(844), BATTERY_STATES),
                "Consumed" to fmt(img.u16(845)?.div(-10.0), "Ah", 1),
                "Time to go" to img.u16(846)?.takeIf { it > 0 }?.let { fmt(it * 100 / 3600.0, "h", 1) },
                "Charger power" to w(img.u16(855)),
                "Low SOC" to enum(img.u16(931), NO_YES),
                "BatteryLife active" to enum(img.u16(932), NO_YES),
                "Charge disabled" to enum(img.u16(933), NO_YES),
                "Discharge disabled" to enum(img.u16(934), NO_YES),
                "Slow charge" to enum(img.u16(935), NO_YES),
                "User charge limited" to enum(img.u16(936), NO_YES),
                "User discharge limited" to enum(img.u16(937), NO_YES),
            )
            out += section(
                "Inverter/charger",
                "VE.Bus current (+ to DC)" to fmt(img.s16(865)?.div(10.0), "A", 1),
                "VE.Bus power (+ to DC)" to w(img.s16(866)),
                "DC current" to fmt(img.s32(868)?.div(10.0), "A", 1),
                "DC power" to w(img.s32(870)),
            )
            if (img.u16(5402) == 1) {
                out += section(
                    "Dynamic ESS",
                    "State" to enum(img.u16(5400), DESS_ACTIVE),
                    "Grid feed-in" to enum(img.u16(5401), DESS_FEED_IN),
                    "Charge/discharge rate" to fmt(img.u16(5403)?.times(10), "W"),
                    "Error" to enum(img.u16(5404), DESS_ERRORS),
                    "Restrictions" to enum(img.u16(5405), DESS_RESTRICTIONS),
                    "Strategy" to enum(img.u16(5406), DESS_STRATEGIES),
                    "Target SOC" to fmt(img.u16(5407), "%"),
                )
            }
            return out.filter { it.rows.isNotEmpty() }
        }
    }
}
