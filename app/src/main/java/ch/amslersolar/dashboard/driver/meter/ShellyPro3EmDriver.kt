// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.meter

import ch.amslersolar.dashboard.driver.DeviceDriver
import ch.amslersolar.dashboard.driver.Details.fmt
import ch.amslersolar.dashboard.driver.Details.section
import ch.amslersolar.dashboard.driver.modbus.ModbusTcpClient
import ch.amslersolar.dashboard.driver.modbus.RegisterBlock
import ch.amslersolar.dashboard.driver.sunspec.RegisterReader
import ch.amslersolar.dashboard.model.DetailSection
import ch.amslersolar.dashboard.model.DeviceInfo
import ch.amslersolar.dashboard.model.DeviceProblem
import ch.amslersolar.dashboard.model.DeviceReading
import ch.amslersolar.dashboard.model.PhaseReading
import java.io.IOException
import java.time.Instant
import kotlin.math.roundToInt

/**
 * Shelly Pro 3EM (Gen2, EM + EMData components, three-phase profile) over Modbus TCP.
 *
 * Specs: Shelly Gen2 API docs "Modbus", "EM" and "EMData" (docs/specs/shelly/, HTML, version 1.0). The Modbus
 * server listens on TCP port 502 and must be enabled (Modbus.SetConfig {"enable": true}, or in the web UI).
 * - Registers (as numbered in the tables): device info 30000 (MAC, 6 registers ASCII), 30006 (model, 10), 30016
 *   (name, 32); EM 31000–31079 (status, totals, phase A / B / C blocks of 20); EMData 31160–31229 (energy counters,
 *   phase blocks of 20).
 *   A second EM / EMData component would sit at +80 / +70 (not used: the Pro 3EM in three-phase mode has one).
 * - Function code: not stated. The doc's modbus_cli examples read "i@1000" / "i@1020", i.e. input registers
 *   (function 04, used here). UNVERIFIED.
 * - Address base: the table numbers are 30000 + the register address: the examples read the EM timestamp
 *   (table 31000) as "i@1000" and phase A voltage (31020) as "i@1020". The code keeps the table numbers and
 *   [REGISTER_BASE] is subtracted on the wire. The HA shelly-modbus project reads the same addresses.
 * - Word order: not stated. The examples pass "-B mixed" (big-endian bytes, low word first) and decode a float
 *   and a uint32 correctly, so low word first is the default. It is also checked against the phase frequencies /
 *   voltages on the first readings and switched if only high-word-first gives plausible values. UNVERIFIED.
 * - Booleans: one register each (consecutive addresses in the table), non-zero = set.
 * - Sign: not stated for the registers. The EM status example has a negative phase power, and EMData counts
 *   "active energy" and "active returned energy" separately, so P > 0 is consumed from the grid and P < 0 returned
 *   (Shelly's convention). Our [DeviceReading.gridPowerW] = −total active power (31013). UNVERIFIED.
 * - Energies: 31162 / 31164 perpetual totals (lifetime import / returned) in details. No daily counters.
 * - Unit id: not stated; 1 by default. UNVERIFIED.
 */
class ShellyPro3EmDriver(private val reader: RegisterReader, private val unitId: Int) : DeviceDriver {
    constructor(client: ModbusTcpClient, unitId: Int) : this(RegisterReader { u, r, c -> client.readInputRegisters(u, r, c) }, unitId)

    private val fields = MeterFieldReader(onWire(reader), unitId)
    private var static: Map<Int, Int>? = null
    private var polls = 0
    private var lowWordFirst: Boolean? = null // once detected from plausible values

    override suspend fun identify(): DeviceInfo {
        val info = MeterRegs(fields.fetch(INFO))
        val model = info.printable(30006, 10) ?: throw IOException("No Shelly device model (input register 30006)")
        val em = fields.fetch(EM)
        if (detectOrder(em) == null) throw IOException("Implausible Shelly EM registers (31000 ff.)")
        return DeviceInfo(model = model, serial = info.printable(30000, 6).orEmpty())
    }

    override suspend fun read(): DeviceReading {
        if (static == null || polls % STATIC_REFRESH_POLLS == 0) {
            fields.retryMissing()
            static = fields.fetch(INFO)
        }
        polls++
        val raw = fields.fetch(EM) + fields.fetch(EMDATA)
        val order = lowWordFirst ?: detectOrder(raw)?.also { if (confident(raw)) lowWordFirst = it } ?: true
        val img = MeterRegs(static.orEmpty() + raw, lowWordFirst = order)
        return parse(img).copy(details = details(img))
    }

    internal companion object {
        const val STATIC_REFRESH_POLLS = 12

        /** Table number − this = Modbus register address (see the class doc). */
        const val REGISTER_BASE = 30000

        /** Reads table-numbered registers at their wire addresses; the block comes back table-numbered. */
        private fun onWire(reader: RegisterReader) = RegisterReader { unit, register, count ->
            val block = reader.read(unit, register - REGISTER_BASE, count)
            RegisterBlock(register, ByteArray(block.count * 2) { i ->
                val word = block.u16(block.start + i / 2)
                (if (i % 2 == 0) word shr 8 else word).toByte()
            })
        }
        val PHASE_EM = listOf(31020, 31040, 31060) // V +0, I +2, P +4, S +6, PF +8, errors +10..12, f +13
        val PHASE_DATA = listOf(31170, 31190, 31210)

        val INFO = listOf(MeterField(30000, 6), MeterField(30006, 10), MeterField(30016, 32))
        val EM = listOf(MeterField(31000, 2)) + fields(1, 31002, 31003, 31004, 31005, 31006) + fields(2, 31007) +
            fields(1, 31009, 31010) + fields(2, 31011, 31013, 31015) +
            PHASE_EM.flatMap { b -> fields(2, b, b + 2, b + 4, b + 6, b + 8) + fields(1, b + 10, b + 11, b + 12) + fields(2, b + 13) }
        val EMDATA = fields(2, 31160, 31162, 31164) + PHASE_DATA.flatMap { b -> fields(2, *(0..14 step 2).map { b + it }.toIntArray()) }

        private fun plausible(img: MeterRegs): Boolean {
            val hz = PHASE_EM.mapNotNull { img.f32(it + 13) }
            val volts = PHASE_EM.mapNotNull { img.f32(it) }
            if (hz.isEmpty() || volts.isEmpty()) return false
            return hz.all { it == 0.0 || it in 40.0..70.0 } && volts.all { it in 0.0..1000.0 }
        }

        private fun hasMains(img: MeterRegs): Boolean = PHASE_EM.any { img.f32(it + 13)?.let { hz -> hz in 40.0..70.0 } == true }

        /** Word orders (true = low word first) under which some phase shows a mains frequency. */
        private fun withMains(raw: Map<Int, Int>): List<Boolean> =
            listOf(true, false).filter { MeterRegs(raw, it).let { img -> plausible(img) && hasMains(img) } }

        /** Exactly one word order shows a mains frequency, so it can be kept. */
        fun confident(raw: Map<Int, Int>): Boolean = withMains(raw).size == 1

        /** Word order that gives plausible values, low word first preferred (doc examples); null if neither does. */
        fun detectOrder(raw: Map<Int, Int>): Boolean? =
            withMains(raw).firstOrNull() ?: listOf(true, false).firstOrNull { plausible(MeterRegs(raw, it)) }

        private fun w(img: MeterRegs, a: Int): Int? = img.f32(a)?.let { -it.roundToInt() }

        fun parse(img: MeterRegs): DeviceReading {
            val phases = PHASE_EM.mapIndexedNotNull { i, b ->
                val v = img.f32(b) ?: return@mapIndexedNotNull null
                PhaseReading("L${i + 1}", v, img.f32(b + 2), img.f32(b + 13) ?: 0.0, w(img, b + 4))
            }
            return DeviceReading(gridPowerW = w(img, 31013), phases = phases, problems = problems(img))
        }

        /**
         * The EM error booleans (Gen2-EM "Modbus registers"): phase meter errors 31002–31004, neutral meter error
         * 31005, phase sequence 31006, neutral current mismatch 31009 and neutral overcurrent 31010. The per-phase
         * overpower / overvoltage / overcurrent registers follow the user's alarm thresholds and aren't reported.
         */
        fun problems(img: MeterRegs): List<DeviceProblem> {
            val flags = (0 until 3).map { 31002 + it to "L${it + 1} meter error" } + listOf(
                31005 to "Neutral meter error", 31006 to "Phase sequence error", 31009 to "Neutral current mismatch",
                31010 to "Neutral overcurrent",
            )
            return flags.filter { (a, _) -> img.u16(a)?.let { it != 0 } == true }
                .map { DeviceProblem(DeviceProblem.Kind.WARNING, it.second) }
        }

        private fun kwh(img: MeterRegs, a: Int, unit: String = "kWh") = fmt(img.f32(a)?.div(1000), unit, 1)
        private fun flag(img: MeterRegs, a: Int) = img.u16(a)?.let { if (it != 0) "Yes" else "No" }
        private fun time(img: MeterRegs, a: Int) = img.u32(a)?.takeIf { it > 0 }?.let { Instant.ofEpochSecond(it).toString() }

        fun details(img: MeterRegs): List<DetailSection> {
            val out = mutableListOf<DetailSection>()
            out += section(
                "Device",
                "Model" to img.printable(30006, 10),
                "Name" to img.printable(30016, 32),
                "MAC address" to img.printable(30000, 6),
                "Last update (UTC)" to time(img, 31000),
                "Energy data update (UTC)" to time(img, 31160),
            )
            out += section(
                "Grid",
                "Active power (+ export)" to fmt(w(img, 31013), "W"),
                "Active power (meter, + consumed)" to fmt(img.f32(31013), "W", 0),
                "Apparent power" to fmt(img.f32(31015), "VA", 0),
                "Total current" to fmt(img.f32(31011), "A", 2),
                "Neutral current" to fmt(img.f32(31007), "A", 2),
                "Neutral meter error" to flag(img, 31005),
                "Neutral current mismatch" to flag(img, 31009),
                "Neutral overcurrent" to flag(img, 31010),
                "Phase sequence error" to flag(img, 31006),
            )
            PHASE_EM.forEachIndexed { i, b ->
                val d = PHASE_DATA[i]
                out += section(
                    "L${i + 1}",
                    "Voltage" to fmt(img.f32(b), "V", 1),
                    "Current" to fmt(img.f32(b + 2), "A", 2),
                    "Active power (meter, + consumed)" to fmt(img.f32(b + 4), "W", 0),
                    "Apparent power" to fmt(img.f32(b + 6), "VA", 0),
                    "Power factor" to fmt(img.f32(b + 8), "", 2),
                    "Frequency" to fmt(img.f32(b + 13), "Hz", 2),
                    "Meter error" to flag(img, 31002 + i),
                    "Overpower" to flag(img, b + 10),
                    "Overvoltage" to flag(img, b + 11),
                    "Overcurrent" to flag(img, b + 12),
                    "Active energy" to kwh(img, d + 12),
                    "Active returned energy" to kwh(img, d + 14),
                    "Fundamental active energy" to kwh(img, d + 2),
                    "Fundamental returned energy" to kwh(img, d + 6),
                    "Lagging reactive energy" to kwh(img, d + 8, "kvarh"),
                    "Leading reactive energy" to kwh(img, d + 10, "kvarh"),
                )
            }
            out += section(
                "Energy (lifetime)",
                "Active energy (import)" to kwh(img, 31162),
                "Active returned energy (export)" to kwh(img, 31164),
            )
            return out.filter { it.rows.isNotEmpty() }
        }
    }
}
