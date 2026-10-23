// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.consumer

import ch.amslersolar.dashboard.driver.DeviceDriver
import ch.amslersolar.dashboard.driver.Details.bits
import ch.amslersolar.dashboard.driver.Details.enum
import ch.amslersolar.dashboard.driver.Details.fmt
import ch.amslersolar.dashboard.driver.Details.section
import ch.amslersolar.dashboard.driver.modbus.ModbusException
import ch.amslersolar.dashboard.driver.modbus.ModbusTcpClient
import ch.amslersolar.dashboard.driver.sunspec.RegisterReader
import ch.amslersolar.dashboard.model.DetailSection
import ch.amslersolar.dashboard.model.DeviceInfo
import ch.amslersolar.dashboard.model.DeviceProblem
import ch.amslersolar.dashboard.model.DeviceReading
import ch.amslersolar.dashboard.model.PhaseReading
import java.io.IOException

/**
 * Bender ECU charge controller (ECU-BRx / ECU-BBx), as used in MENNEKES AMTRON Professional, AMTRON Charge
 * Control and AMEDIO Professional: Modbus TCP server, read only.
 *
 * Spec: "ECU-BRx and ECU-BBx Modbus TCP Server Specification", doc revision 1.07, 10 Jun 2022 (docs/specs/mennekes/).
 * Verified there:
 * - Available from ECU software ≥ 5.12; 32-bit registers 715–719, 134, 142–152, 218–227 only from 5.22.
 * - Answers any unit id 1–255. Port = "Modbus TCP Server Base Port" (502 by default); a second connector (second
 *   charge controller, e.g. AMEDIO with two charge points) answers on base port + 1 (503): add it as its own device.
 * - Addresses are used as printed (100 = FIRMWARE_VERSION), unless the MODBUS_ADDRESS_OFFSET register (130) was
 *   changed from its default 0; then every address shifts and this driver won't find the device.
 * - High byte first, 32-bit values high word first, except the ERROR_CODES registers (105–112): low word first and
 *   low byte first in each word. Only bits 0–21 are defined, all in 111–112.
 * - Meter values are unsigned 32-bit, 0xFFFFFFFF = not available. Without METER_TOTAL_* (< 5.22) the total power /
 *   energy is in the L1 registers, L2/L3 then read 0xFFFFFFFF.
 * - Enable on the charger web interface: "Modbus TCP Server" on; "Register Address Set" must be the set this
 *   document describes (its name in the UI is UNVERIFIED).
 *
 * Registers: 100 firmware (ASCII), 104 OCPP status, 105–112 error codes, 120 protocol version (ASCII), 122 vehicle
 * state, 124 CP availability, 131 safe current, 132 comm timeout, 134 operator current limit, 142–151 model (ASCII),
 * 152 plug lock; 200–227 meter (energy Wh, power W, current mA, voltage V); 600–635 DLM; 705/716 session energy Wh,
 * 706 signalled current, 709/718 charge duration s, 712 min current, 715 max EV current; 1000 HEMS current limit.
 *
 * UNVERIFIED: the function code (the spec doesn't name it): FC03 (holding) is used, and if the device answers
 * "illegal function" the optional [inputReader] (FC04) is tried. The meaning of 124 is version dependent
 * (inverted from 5.22); it's shown raw. SMA EV Charger Business may use the same register set: UNVERIFIED.
 */
class BenderEcuDriver(
    reader: RegisterReader,
    private val unitId: Int,
    private val inputReader: RegisterReader? = null,
) : DeviceDriver {
    constructor(client: ModbusTcpClient, unitId: Int) : this(
        RegisterReader { u, r, c -> client.readHoldingRegisters(u, r, c) },
        unitId,
        RegisterReader { u, r, c -> client.readInputRegisters(u, r, c) },
    )

    private var active = reader
    private var fetcher = FieldFetcher(active, unitId)
    private var static: Map<Int, Int>? = null
    private var polls = 0

    override suspend fun identify(): DeviceInfo {
        val r = Regs(loadStatic())
        return DeviceInfo(model = model(r), serial = "")
    }

    private suspend fun loadStatic(): Map<Int, Int> {
        val head = try {
            active.read(unitId, 100, 2)
        } catch (e: ModbusException) {
            val fallback = inputReader
            if (e.code != 1 || fallback == null || active === fallback) throw IOException("No ECU firmware register (100): ${e.message}")
            active = fallback
            fetcher = FieldFetcher(active, unitId)
            try {
                active.read(unitId, 100, 2)
            } catch (e2: ModbusException) {
                throw IOException("No ECU firmware register (100): ${e2.message}")
            }
        }
        val fw = Regs(head.values()).text(100, 2)
        if (fw == null || !FIRMWARE.matches(fw)) throw IOException("Not a Bender ECU (firmware register reads '$fw')")
        fetcher.forget()
        val regs = fetcher.fetch(STATIC_FIELDS) + head.values()
        val vehicle = regs[122]
        if (vehicle != null && vehicle !in 0..5) throw IOException("Not a Bender ECU (vehicle state $vehicle)")
        static = regs
        return regs
    }

    override suspend fun read(): DeviceReading {
        if (static == null || polls % STATIC_REFRESH_POLLS == 0) loadStatic()
        polls++
        val r = Regs(static.orEmpty() + fetcher.fetch(LIVE_FIELDS))
        return parse(r)
    }

    internal companion object {
        const val STATIC_REFRESH_POLLS = 12
        private val FIRMWARE = Regex("[0-9A-Za-z]{1,2}\\.[0-9A-Za-z.]{1,2}")

        private fun r(a: Int) = a to 1
        private fun d(a: Int) = a to 2

        val STATIC_FIELDS = listOf(d(120), r(122), r(124), r(130), r(131), r(132), r(134), 142 to 10, r(152), r(600), r(620), r(621))
        val LIVE_FIELDS = listOf(r(104), 105 to 8, r(122)) + (200..226 step 2).map(::d) +
            listOf(r(705), r(706), r(709), r(712), r(715), d(716), d(718), r(1000)) +
            (610..615).map(::r) + (630..635).map(::r)

        val OCPP = mapOf(
            0 to "Available", 1 to "Occupied", 2 to "Reserved", 3 to "Unavailable", 4 to "Faulted", 5 to "Preparing",
            6 to "Charging", 7 to "SuspendedEVSE", 8 to "SuspendedEV", 9 to "Finishing",
        )
        val VEHICLE = mapOf(1 to "A (no car)", 2 to "B (car connected)", 3 to "C (charging)", 4 to "D (charging, ventilation)", 5 to "E (error)")
        private val DLM = mapOf(
            0 to "Disabled", 1 to "Master (with internal slave)", 2 to "Master (standalone)", 3 to "Slave (auto discovery)",
            4 to "Slave (fixed IP)",
        )
        val ERRORS = listOf(
            "RCM triggered", "Vehicle state E", "Mode 3 diode check", "MCB type 2 triggered", "MCB domestic socket triggered",
            "RCD triggered", "Contactor welded", "Backend disconnected", "Plug locking failed", "Locking without plug",
            "Actuator stuck", "Actuator detection failed", "Firmware update running", "Tilted", "CP/PR wiring",
            "Car current overload", "Actuator unlocked while charging", "Tilted, reboot required", "PIC24 error",
            "USB stick handling", "Incorrect phase rotation", "No power on mains",
        )

        fun model(r: Regs): String {
            val text = r.text(142, 10)
            val product = when {
                text == null -> null
                text.startsWith("ICC1622") -> "MENNEKES AMEDIO Professional"
                text.startsWith("CC612") -> "MENNEKES AMTRON Professional"
                text.startsWith("_CC") -> "MENNEKES AMTRON Charge Control"
                else -> null
            }
            return product ?: listOfNotNull("Bender ECU", text).joinToString(" ")
        }

        private fun swap(w: Int) = ((w and 0xFF) shl 8) or (w shr 8)

        /** Error bits 0–21 from 111–112: low word first, low byte first. */
        fun errorBits(r: Regs): Long? {
            val lo = r.u16(111) ?: return null
            val hi = r.u16(112) ?: return null
            return (swap(hi).toLong() shl 16) or swap(lo).toLong()
        }

        fun errors(bits: Long): List<String> = ERRORS.indices.filter { bits and (1L shl it) != 0L }.map { ERRORS[it] }

        /** A meter u32, or null for 0xFFFFFFFF (not available). */
        private fun Regs.m(a: Int): Long? = u32(a)?.takeIf { it != 0xFFFFFFFFL }

        private fun Regs.u16n(a: Int): Int? = u16(a)?.takeIf { it != 0xFFFF }

        fun power(r: Regs): Long? = r.m(220) ?: run {
            val l = listOf(206, 208, 210).map { r.m(it) }
            if (l[1] == null && l[2] == null) l[0] else l.filterNotNull().sum()
        }

        fun energyWh(r: Regs): Long? = r.m(218) ?: run {
            val l = listOf(200, 202, 204).map { r.m(it) }
            if (l[1] == null && l[2] == null) l[0] else l.filterNotNull().sum()
        }

        fun status(r: Regs): String? {
            val ocpp = r.u16n(104)
            val err = errorBits(r)?.let(::errors).orEmpty()
            if (ocpp == 4 || r.u16n(122) == 5) return "Fault" + (err.firstOrNull()?.let { ": $it" } ?: "")
            return when (ocpp) {
                0 -> "Standby"
                1, 5 -> "Car connected"
                2 -> "Reserved"
                3 -> "Unavailable"
                6 -> "Charging"
                7 -> "Paused by charger"
                8 -> "Paused by car"
                9 -> "Finished"
                null -> r.u16n(122)?.let { VEHICLE[it] }
                else -> "State $ocpp"
            }
        }

        fun parse(r: Regs): DeviceReading {
            val phases = listOf(Triple("L1", 222, 212), Triple("L2", 224, 214), Triple("L3", 226, 216)).mapIndexedNotNull { i, (n, u, c) ->
                val v = r.m(u)?.toDouble()
                val a = r.m(c)?.div(1000.0)
                if (v == null && a == null) return@mapIndexedNotNull null
                // L1 power holds the total when L2/L3 aren't available (meters without per-phase power).
                val p = if (r.m(208) == null && r.m(210) == null && r.m(220) == null) null else r.m(206 + 2 * i)?.toInt()
                PhaseReading(n, v ?: 0.0, a, 0.0, p)
            }
            return DeviceReading(
                loadPowerW = power(r)?.toInt()?.coerceAtLeast(0),
                phases = phases,
                status = status(r),
                details = details(r),
                problems = problems(r),
            )
        }

        /** Error bits that don't stop the charge point: backend link, firmware update, USB stick, phase rotation. */
        private val WARNING_BITS = setOf(7, 12, 19, 20)

        /**
         * The ERROR_CODES bits 0–21 ("Error states mask mappings"; bits 22–31 of 111–112 are reserved and shown as
         * "Error bit n"), plus OCPP status 4 "Faulted" and vehicle state E when no error bit explains them.
         */
        fun problems(r: Regs): List<DeviceProblem> {
            val bits = errorBits(r) ?: 0L
            val out = (0 until 32).filter { bits and (1L shl it) != 0L }.map { b ->
                val kind = if (b in WARNING_BITS) DeviceProblem.Kind.WARNING else DeviceProblem.Kind.FAULT
                DeviceProblem(kind, if (b < ERRORS.size) "${ERRORS[b]} (error bit $b)" else "Error bit $b")
            }.toMutableList()
            if (out.none { it.kind == DeviceProblem.Kind.FAULT }) {
                if (r.u16n(104) == 4) out += DeviceProblem(DeviceProblem.Kind.FAULT, "Charge point faulted (OCPP status 4)")
                else if (r.u16n(122) == 5) out += DeviceProblem(DeviceProblem.Kind.FAULT, "Vehicle state E (error)")
            }
            return out
        }

        fun sessionWh(r: Regs): Long? = r.m(716) ?: r.u16n(705)?.toLong()

        fun details(r: Regs): List<DetailSection> {
            val err = errorBits(r)
            return listOf(
                section(
                    "Charging",
                    "OCPP status" to enum(r.u16n(104), OCPP),
                    "Vehicle state" to enum(r.u16n(122), VEHICLE),
                    "Errors" to err?.let { e -> if (e == 0L) "None" else errors(e).joinToString(", ").ifEmpty { bits(e) } },
                    "Power" to fmt(power(r), "W"),
                    "Session energy" to fmt(sessionWh(r)?.div(1000.0), "kWh", 2),
                    "Charge duration" to (r.m(718) ?: r.u16n(709)?.toLong())?.let { fmt(it / 60.0, "min", 0) },
                    "Total energy (meter)" to fmt(energyWh(r)?.div(1000.0), "kWh", 1),
                    "Current L1" to fmt(r.m(212)?.div(1000.0), "A", 1),
                    "Current L2" to fmt(r.m(214)?.div(1000.0), "A", 1),
                    "Current L3" to fmt(r.m(216)?.div(1000.0), "A", 1),
                    "Voltage L1" to fmt(r.m(222), "V"), "Voltage L2" to fmt(r.m(224), "V"), "Voltage L3" to fmt(r.m(226), "V"),
                    "Signalled current" to fmt(r.u16n(706), "A"),
                    "Minimum current" to fmt(r.u16n(712), "A"),
                    "Max current of the car" to fmt(r.u16n(715), "A"),
                    "HEMS current limit" to fmt(r.u16n(1000), "A"),
                    "Operator current limit" to fmt(r.u16n(134), "A"),
                    "Safe current" to fmt(r.u16n(131), "A"),
                    "Communication timeout" to fmt(r.u16n(132), "s"),
                    "Plug lock" to r.u16n(152)?.let { if (it == 1) "Locked" else "Unlocked" },
                    "CP availability (raw)" to r.u16n(124)?.toString(),
                ),
                section(
                    "Device",
                    "Model" to model(r),
                    "Model code" to r.text(142, 10),
                    "Firmware" to r.text(100, 2),
                    "Modbus protocol version" to r.text(120, 2),
                    "Address offset" to r.u16n(130)?.toString(),
                    "DLM mode" to enum(r.u16n(600), DLM),
                    "DLM external meter" to r.u16n(620)?.let { if (it == 1) "Enabled" else "Disabled" },
                    "DLM slaves connected" to r.u16n(621)?.toString(),
                    "DLM limit L1 / L2 / L3" to listOf(610, 611, 612).map { r.u16n(it) }.takeIf { l -> l.all { it != null } }?.joinToString(" / ", postfix = " A"),
                    "DLM applied L1 / L2 / L3" to listOf(630, 631, 632).map { r.u16n(it) }.takeIf { l -> l.all { it != null } }?.joinToString(" / ", postfix = " A"),
                    "DLM available L1 / L2 / L3" to listOf(633, 634, 635).map { r.u16n(it) }.takeIf { l -> l.all { it != null } }?.joinToString(" / ", postfix = " A"),
                ),
            )
        }
    }
}
