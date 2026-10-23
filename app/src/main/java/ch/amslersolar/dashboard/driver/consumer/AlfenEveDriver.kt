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
import kotlin.math.roundToInt

/**
 * Alfen Eve (Single / Double, Pro-line; NG9xx platform) charging station, Modbus TCP, read only.
 *
 * Spec: Alfen "Configuration Guide – Modbus for ACE", version 1.0, 05/2025 (firmware 4.10, ACE Service
 * Installer 3.4.10-130; docs/specs/alfen/, not to be redistributed). Verified there:
 * - Port 502, FC03 (holding registers) only, up to 2 Modbus TCP clients, blocks up to 125 contiguous registers.
 * - Server address 200 = station (status 1100 ff., SCN 1400 ff.); 1 = socket 1 (single socket, or left of two),
 *   2 = right socket. [unitId] is the socket (1 or 2); the station registers are always read at 200.
 * - Reserved / not available registers read as NaN (0xFFFF for 16 bits); NaN floats are treated as missing.
 * - Enable: ACE Service Installer (service account ≥ 4.0) → Load Balancing → Active balancing (licensed Active
 *   Load Balancing feature, data source "Energy Management System") → TCP/IP EMS; "Allow reading" (off by
 *   default) must be on.
 *
 * Ambiguities in the guide, resolved by auto-detection on the device:
 * - Address base: the tables list e.g. 100..116 for the name, but the text says "always subtract 1 from the
 *   register value". [identify] reads the identification block at the printed addresses and, if the manufacturer
 *   (117..121, "Alfen B.V.") isn't there, one lower, and uses that offset for every register.
 * - Word order: the guide says 32-bit values are "mixed-endian … low word first", but also "network byte order".
 *   Low word first is assumed; on the first socket read both orders are tried on the frequency (336, 40–70 Hz)
 *   or the L1 voltage (306), and the plausible one is kept. FLOAT64 energies use the same word order.
 *
 * Registers: identification 100 name, 117 manufacturer, 122 table version, 123 firmware, 140 platform type,
 * 157 station serial, 174 uptime (u64, ms), 178 time zone; station 1100 active max current, 1102 board
 * temperature, 1104 OCPP state, 1105 number of sockets; socket 300 meter state bits, 305 meter type, 306–317
 * voltages, 318–327 currents, 328–335 power factors, 336 frequency, 338–345 real power, 346–361 apparent / reactive
 * power, 362–393 real energy delivered / consumed (FLOAT64 Wh), 1200 availability, 1201 Mode 3 state (string),
 * 1206 actual applied max current, 1208 Modbus max current valid time, 1210 Modbus max current, 1212 safe
 * current, 1214 setpoint accounted for, 1215 1/3 phases.
 *
 * UNVERIFIED: the identification registers' server address (taken as 200, then the socket's); the data types of
 * 1100..1103 (no type column; read as FLOAT32); whether "delivered" or "consumed" energy is the energy charged
 * into the car (delivered is used as the lifetime total, both are shown); OCPP state codes (raw value shown).
 * There is no session energy register.
 */
class AlfenEveDriver(private val reader: RegisterReader, private val unitId: Int) : DeviceDriver {
    constructor(client: ModbusTcpClient, unitId: Int) : this(RegisterReader { u, r, c -> client.readHoldingRegisters(u, r, c) }, unitId)

    private var offset: Int? = null
    private var lowWordFirst: Boolean? = null
    private var identity: Map<Int, Int>? = null
    private var polls = 0
    private val socket by lazy { FieldFetcher(reader, unitId) }
    private val station by lazy { FieldFetcher(reader, STATION) }

    override suspend fun identify(): DeviceInfo {
        val id = loadIdentity()
        val r = Regs(id)
        val platform = r.text(140, 17)
        return DeviceInfo(
            model = listOfNotNull("Alfen Eve", platform, if (unitId == 2) "socket 2" else null).joinToString(" "),
            serial = r.text(157, 11).orEmpty(),
        )
    }

    /** Finds the address offset (0 or −1) from where the manufacturer string sits. */
    private suspend fun loadIdentity(): Map<Int, Int> {
        val units = listOf(STATION, unitId).distinct()
        for (unit in units) for (off in offset?.let { listOf(it) } ?: listOf(0, -1)) {
            val block = try {
                reader.read(unit, ID_START + off, ID_COUNT)
            } catch (_: ModbusException) {
                continue
            }
            val regs = block.values().mapKeys { it.key - off }
            if (Regs(regs).text(117, 5)?.contains("alfen", ignoreCase = true) == true) {
                offset = off
                return regs.also { identity = it }
            }
        }
        throw IOException("No Alfen manufacturer string at register 117 (or 116)")
    }

    override suspend fun read(): DeviceReading {
        if (identity == null || polls % STATIC_REFRESH_POLLS == 0) {
            socket.forget()
            station.forget()
            loadIdentity()
        }
        polls++
        val off = offset ?: 0
        val raw = HashMap<Int, Int>()
        raw += socket.fetch(SOCKET_FIELDS.map { it.first + off to it.second }).mapKeys { it.key - off }
        raw += station.fetch(STATION_FIELDS.map { it.first + off to it.second }).mapKeys { it.key - off }
        val order = lowWordFirst ?: detectOrder(raw)?.also { lowWordFirst = it } ?: true
        val r = Regs(identity.orEmpty() + raw, lowWordFirst = order)
        return parse(r)
    }

    internal companion object {
        const val STATION = 200
        const val STATIC_REFRESH_POLLS = 12
        private const val ID_START = 100
        private const val ID_COUNT = 79 // 100..178

        val SOCKET_FIELDS = listOf(300 to 94, 1200 to 16) // 300..393, 1200..1215
        val STATION_FIELDS = listOf(1100 to 6) // 1100..1105

        /** true = low word first; null when neither order gives a plausible frequency or voltage. */
        fun detectOrder(raw: Map<Int, Int>): Boolean? {
            for ((addr, range) in listOf(336 to 40.0..70.0, 306 to 100.0..300.0)) {
                val low = Regs(raw, lowWordFirst = true).f32(addr)
                val high = Regs(raw, lowWordFirst = false).f32(addr)
                if (low != null && low in range) return true
                if (high != null && high in range) return false
            }
            return null
        }

        private fun Regs.u16n(a: Int) = u16(a)?.takeIf { it != 0xFFFF }

        fun status(r: Regs): String? {
            if (r.u16n(1200) == 0) return "Inoperative"
            val mode3 = r.text(1201, 5)?.uppercase() ?: return null
            return when {
                mode3 == "C2" || mode3 == "D2" -> "Charging"
                mode3.startsWith("C") || mode3.startsWith("D") -> "Car connected, paused"
                mode3.startsWith("B") -> "Car connected"
                mode3 == "A" -> "Standby"
                mode3 == "E" || mode3 == "F" -> "Error ($mode3)"
                else -> mode3
            }
        }

        fun power(r: Regs): Double? = r.f32(344) ?: listOf(338, 340, 342).mapNotNull { r.f32(it) }.takeIf { it.isNotEmpty() }?.sum()

        fun parse(r: Regs): DeviceReading {
            val hz = r.f32(336) ?: 0.0
            val phases = listOf(Triple("L1", 306, 320), Triple("L2", 308, 322), Triple("L3", 310, 324)).mapIndexedNotNull { i, (n, u, c) ->
                val v = r.f32(u) ?: return@mapIndexedNotNull null
                PhaseReading(n, v, r.f32(c), hz, r.f32(338 + 2 * i)?.roundToInt())
            }
            return DeviceReading(
                loadPowerW = power(r)?.roundToInt()?.coerceAtLeast(0),
                phases = phases,
                status = status(r),
                temperatureC = r.f32(1102),
                details = details(r),
                problems = problems(r),
            )
        }

        /**
         * Mode 3 states E and F ("error states", appendix F) and the socket meter state bits 0x04 warning /
         * 0x08 error (register 300).
         */
        fun problems(r: Regs): List<DeviceProblem> {
            val out = mutableListOf<DeviceProblem>()
            val mode3 = r.text(1201, 5)?.uppercase()
            if (mode3 == "E" || mode3 == "F") out += DeviceProblem(DeviceProblem.Kind.FAULT, "Charging error (Mode 3 state $mode3)")
            val meter = r.u16n(300)
            if (meter != null) {
                if (meter and 0x08 != 0) out += DeviceProblem(DeviceProblem.Kind.WARNING, "Energy meter error (meter state 0x08)")
                if (meter and 0x04 != 0) out += DeviceProblem(DeviceProblem.Kind.WARNING, "Energy meter warning (meter state 0x04)")
            }
            return out
        }

        private fun a(r: Regs, addr: Int) = fmt(r.f32(addr), "A", 1)
        private fun kwh(v: Double?) = fmt(v?.div(1000), "kWh", 1)
        private val METER_TYPES = mapOf(0 to "RTU", 1 to "TCP/IP", 2 to "UDP", 3 to "P1", 4 to "Other")

        fun details(r: Regs): List<DetailSection> = listOf(
            section(
                "Charging",
                "Mode 3 state" to r.text(1201, 5),
                "Availability" to r.u16n(1200)?.let { if (it == 1) "Operative" else "Inoperative" },
                "Power" to fmt(power(r), "W", 0),
                "Apparent power" to fmt(r.f32(352), "VA", 0),
                "Reactive power" to fmt(r.f32(360), "var", 0),
                "Power factor" to fmt(r.f32(334), "", 3),
                "Frequency" to fmt(r.f32(336), "Hz", 2),
                "Current L1" to a(r, 320), "Current L2" to a(r, 322), "Current L3" to a(r, 324), "Current N" to a(r, 318),
                "Voltage L1-N" to fmt(r.f32(306), "V", 1), "Voltage L2-N" to fmt(r.f32(308), "V", 1), "Voltage L3-N" to fmt(r.f32(310), "V", 1),
                "Energy delivered (total)" to kwh(r.f64(374)),
                "Energy consumed (total)" to kwh(r.f64(390)),
                "Actual applied max current" to a(r, 1206),
                "Modbus max current" to a(r, 1210),
                "Modbus max current valid for" to fmt(r.u32(1208)?.takeIf { it != 0xFFFFFFFFL }, "s"),
                "Safe current" to a(r, 1212),
                "Modbus setpoint accounted for" to r.u16n(1214)?.let { if (it == 1) "Yes" else "No" },
                "Phases" to r.u16n(1215)?.let { "$it-phase" },
                "Meter state" to r.u16n(300)?.let { bits(it.toLong(), 4) },
                "Meter type" to enum(r.u16n(305), METER_TYPES),
            ),
            section(
                "Station",
                "Name" to r.text(100, 17),
                "Manufacturer" to r.text(117, 5),
                "Platform" to r.text(140, 17),
                "Serial number" to r.text(157, 11),
                "Firmware" to r.text(123, 17),
                "Modbus table version" to r.s16(122)?.takeIf { it != -1 }?.toString(),
                "Active max current" to a(r, 1100),
                "Board temperature" to fmt(r.f32(1102), "°C", 1),
                "OCPP state" to r.u16n(1104)?.toString(),
                "Sockets" to r.u16n(1105)?.toString(),
                "Uptime" to r.u64(174)?.takeIf { it >= 0 }?.let { fmt(it / 3_600_000.0, "h", 1) },
            ),
        )
    }
}
