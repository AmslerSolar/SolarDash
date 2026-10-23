// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.riello

import ch.amslersolar.dashboard.driver.DeviceDriver
import ch.amslersolar.dashboard.driver.Details.bits
import ch.amslersolar.dashboard.driver.Details.fmt
import ch.amslersolar.dashboard.driver.Details.section
import ch.amslersolar.dashboard.driver.modbus.ModbusException
import ch.amslersolar.dashboard.driver.modbus.ModbusTcpClient
import ch.amslersolar.dashboard.driver.modbus.RegisterBlock
import ch.amslersolar.dashboard.driver.sunspec.RegisterReader
import ch.amslersolar.dashboard.model.DetailSection
import ch.amslersolar.dashboard.model.DeviceInfo
import ch.amslersolar.dashboard.model.DeviceReading
import ch.amslersolar.dashboard.model.EnergyTotals
import ch.amslersolar.dashboard.model.PhaseReading
import ch.amslersolar.dashboard.model.PvString
import java.io.IOException
import kotlin.math.roundToInt

/**
 * Riello Solartech (RPS) central inverters (Sirio Central Station family), "Guida di riferimento per protocollo
 * MODBUS — Central Inverter" IT2037A rev. 03 (30/03/2023, docs/specs/riello/; Italian only).
 *
 * - Modbus RTU (RS-485 slots 1/2, RS-232 COM-2, 9600 bit/s) and Modbus/TCP (Ethernet). Function 03 and 04 both
 *   read every register area; this driver uses 03 (holding).
 * - Registers are numbered from 1 in the document, the address base isn't stated: the driver finds it by reading
 *   "Phase number" (register 80, 0x0031 single-phase / 0x0033 three-phase) at address 79 (1-based) and 80
 *   (0-based). The same register tells the byte order: 0x3100 / 0x3300 means the words come LSByte first (as the
 *   document's String Box section describes for that device); text and values are then byte-swapped.
 * - Identification 80–112 (ASCII, two characters per register): VA rating, nominal PV voltage, firmware, serial,
 *   model name, manufacturer. Read on identify and every [STATIC_REFRESH_POLLS] polls.
 * - Measures 11–53 (Temp 0.1 °C, Vpv 0.1 V, Ipv 0.1 A, Iac 0.1 A, Vac 0.1 V phase voltages, Fac 0.01 Hz, Pac per
 *   phase 10 W, E-Total 0.1 kWh high/low word, h-Total, Mode, reactive power per phase 10 var (FW ≥ 1.2.5), daily
 *   energy 0.1 kWh (FW ≥ 1.2.5), PV isolation kΩ (FW ≥ 1.3.0)). "Value not available" = 0xFFFF (stated).
 *   Registers 7–53 are read as one block; if the inverter rejects it (older firmware), 7–9, 11–41 and 42–53 are
 *   read separately and missing parts are left out.
 * - Solar: the map has no DC power register; it's Σ Vpv × Ipv of the PV inputs (else the AC power Σ Pac).
 *   Grid: null (the inverter doesn't measure the grid connection).
 * - UNVERIFIED: the alarm/status table's register numbers (read from the layout as 7 and 8 = alarm words, 9 =
 *   status flags; the table header says "1÷6") and bit numbering (bit 1 = least significant); the meaning of
 *   Mode (register 35, no value table in the document — shown as a number); default unit id (not stated, 1) and
 *   TCP port (not stated, 502); signedness of Temp (read as signed) and Pac (read as unsigned).
 * - The String Box (separate RTU device with its own map, mappatura-modbus-sb-en) isn't supported.
 */
class RielloCentralDriver(private val reader: RegisterReader, private val unitId: Int) : DeviceDriver {
    constructor(client: ModbusTcpClient, unitId: Int) : this(RegisterReader { u, r, c -> client.readHoldingRegisters(u, r, c) }, unitId)

    /** request address = register + [offset]; [swapped]: LSByte first in every register. */
    internal data class Layout(val offset: Int, val swapped: Boolean)

    private var layout: Layout? = null
    private var static: Map<Int, Int>? = null
    private var split = false
    private val missing = mutableSetOf<Int>()
    private var polls = 0

    override suspend fun identify(): DeviceInfo {
        val img = Image(loadStatic(), layout!!.swapped)
        return DeviceInfo(model = img.model(), serial = img.text(89, 8).orEmpty())
    }

    override suspend fun read(): DeviceReading {
        if (static == null || polls % STATIC_REFRESH_POLLS == 0) loadStatic()
        polls++
        val l = layout!!
        return Image(static.orEmpty() + fetchLive(l), l.swapped).reading()
    }

    private suspend fun loadStatic(): Map<Int, Int> {
        val l = layout ?: detect().also { layout = it }
        val regs = block(l, 80, 33)
        val img = Image(regs, l.swapped)
        if (img.u16(80) !in PHASES.keys || (img.text(97, 8) == null && img.text(105, 8) == null)) {
            throw IOException("No Riello central inverter identification (registers 80–112)")
        }
        static = regs
        return regs
    }

    private suspend fun detect(): Layout {
        for (offset in listOf(-1, 0)) {
            val raw = try {
                reader.read(unitId, 80 + offset, 1).u16(80 + offset)
            } catch (e: ModbusException) {
                if (e.code in GATEWAY_NO_ANSWER) throw e
                continue
            }
            when (raw) {
                0x0031, 0x0033 -> return Layout(offset, swapped = false)
                0x3100, 0x3300 -> return Layout(offset, swapped = true)
            }
        }
        throw IOException("Not a Riello central inverter (register 80 \"phase number\" isn't 0x0031 or 0x0033)")
    }

    private suspend fun fetchLive(l: Layout): Map<Int, Int> {
        if (!split) {
            try {
                return block(l, 7, 47)
            } catch (e: ModbusException) {
                if (e.code in GATEWAY_NO_ANSWER) throw e
                split = true
            }
        }
        val out = HashMap<Int, Int>()
        for (part in LIVE_PARTS) {
            if (part.first in missing) continue
            try {
                out.putAll(block(l, part.first, part.last - part.first + 1))
            } catch (e: ModbusException) {
                if (e.code in GATEWAY_NO_ANSWER || part.first == 11) throw e
                missing += part.first // older firmware
            }
        }
        return out
    }

    /** Raw register words by register number (byte order untouched). */
    private suspend fun block(l: Layout, register: Int, count: Int): Map<Int, Int> {
        val b: RegisterBlock = reader.read(unitId, register + l.offset, count)
        return (0 until count).associate { register + it to b.u16(b.start + it) }
    }

    internal class Image(private val raw: Map<Int, Int>, private val swapped: Boolean) {
        private fun word(r: Int): Int? = raw[r]?.let { if (swapped) ((it and 0xFF) shl 8) or (it shr 8) else it }
        fun u16(r: Int): Int? = word(r)?.takeIf { it != 0xFFFF }
        fun s16(r: Int): Int? = u16(r)?.toShort()?.toInt()
        fun u32(r: Int): Long? {
            val hi = word(r) ?: return null
            val lo = word(r + 1) ?: return null
            return ((hi.toLong() shl 16) or lo.toLong()).takeIf { it != 0xFFFFFFFFL }
        }

        /** Two ASCII characters per register, first character in the (byte-order corrected) high byte. */
        fun text(r: Int, n: Int): String? {
            val chars = (r until r + n).map { word(it) ?: return null }.flatMap { listOf((it shr 8).toChar(), (it and 0xFF).toChar()) }
            return String(chars.toCharArray()).replace('\u0000', ' ').trim()
                .takeIf { it.isNotEmpty() && it.all { c -> c.code in 0x20..0x7E } }
        }

        val threePhase get() = u16(80) == 0x0033

        fun model(): String {
            val name = text(97, 8)
            val maker = text(105, 8)
            return listOfNotNull(maker, name).joinToString(" ").ifEmpty { "Riello central inverter" }
        }

        private fun tenth(v: Int?) = v?.div(10.0)

        fun reading(): DeviceReading {
            val strings = (0..2).mapNotNull { i ->
                val v = tenth(u16(12 + i))?.takeIf { it > 0 } ?: return@mapNotNull null
                PvString(i + 1, v, tenth(u16(15 + i)) ?: 0.0, null)
            }
            val freq = u16(24)?.div(100.0) ?: 0.0
            val phases = (0 until if (threePhase) 3 else 1).mapNotNull { i ->
                val v = tenth(u16(21 + i))?.takeIf { it > 0 } ?: return@mapNotNull null
                PhaseReading("L${i + 1}", v, tenth(u16(18 + i)), freq, u16(25 + i)?.times(10))
            }
            val ac = phases.mapNotNull { it.powerW }.takeIf { it.isNotEmpty() }?.sum()
            val dc = strings.takeIf { it.isNotEmpty() }?.sumOf { it.voltageV * it.currentA }?.roundToInt()
            val alarms = alarmNames()
            return DeviceReading(
                pvPowerW = (dc ?: ac)?.coerceAtLeast(0),
                pvStrings = strings,
                phases = phases,
                temperatureC = tenth(s16(11)),
                status = if (alarms.isNotEmpty()) alarms.joinToString(", ") else u16(35)?.let { "Mode $it" },
                today = EnergyTotals(pvKwh = u32(49)?.div(10.0)),
                lifetimePvKwh = u32(31)?.div(10.0),
                details = details(ac, dc, alarms),
            )
        }

        private fun names(r: Int, table: Map<Int, String>): List<String> {
            val v = u16(r) ?: return emptyList()
            return table.filterKeys { bit -> v and (1 shl (bit - 1)) != 0 }.values.toList()
        }

        fun alarmNames(): List<String> = names(7, ALARMS_7) + names(8, ALARMS_8)

        private fun details(ac: Int?, dc: Int?, alarms: List<String>): List<DetailSection> = listOf(
            section(
                "Inverter",
                "Manufacturer" to text(105, 8),
                "Model" to text(97, 8),
                "Serial number" to text(89, 8),
                "Firmware" to text(86, 3),
                "Phases" to u16(80)?.let { PHASES[it] },
                "Rated power" to text(81, 3)?.toIntOrNull()?.let { fmt(it / 1000.0, "kVA", 1) },
                "Nominal PV voltage" to text(84, 2)?.toIntOrNull()?.let { fmt(it / 10.0, "V", 1) },
                "Mode" to u16(35)?.toString(),
                "Alarms" to alarms.joinToString(", ").ifEmpty { if (u16(7) != null || u16(8) != null) "None" else null },
                "Alarm word 7" to bits(u16(7)?.toLong(), 4),
                "Alarm word 8" to bits(u16(8)?.toLong(), 4),
                "Status flags" to names(9, STATUS_9).joinToString(", ").ifEmpty { bits(u16(9)?.toLong(), 4) },
                "Temperature" to fmt(tenth(s16(11)), "°C", 1),
                "PV isolation resistance" to fmt(u16(52), "kΩ"),
                "PV voltage unbalance vs. earth" to fmt(u16(53), "%"),
                "Max. deliverable power" to fmt(u16(51), "%"),
                "Operating hours" to fmt(u32(33), "h"),
            ),
            section(
                "AC output",
                "Active power" to fmt(ac, "W"),
                *(0 until if (threePhase) 3 else 1).flatMap { i ->
                    val p = "RST"[i]
                    listOf(
                        "Voltage $p" to fmt(tenth(u16(21 + i)), "V", 1),
                        "Current $p" to fmt(tenth(u16(18 + i)), "A", 1),
                        "Power $p" to fmt(u16(25 + i)?.times(10), "W"),
                        "Reactive power $p" to fmt(s16(42 + i)?.times(10), "var"),
                        "Grid impedance $p" to fmt(u16(28 + i), "mΩ"),
                    )
                }.toTypedArray(),
                "Frequency" to fmt(u16(24)?.div(100.0), "Hz", 2),
            ),
            section(
                "PV inputs",
                "PV power (V × I)" to fmt(dc, "W"),
                *(0..2).flatMap { i ->
                    listOf("PV${i + 1} voltage" to fmt(tenth(u16(12 + i)), "V", 1), "PV${i + 1} current" to fmt(tenth(u16(15 + i)), "A", 1))
                }.toTypedArray(),
            ),
            section(
                "Last fault values",
                "Grid voltage" to fmt(tenth(u16(36)), "V", 1),
                "Grid frequency" to fmt(u16(37)?.div(100.0), "Hz", 2),
                "Grid impedance" to fmt(u16(38)?.div(1000.0), "Ω", 3),
                "Temperature" to fmt(tenth(s16(39)), "°C", 1),
                "PV voltage" to fmt(tenth(u16(40)), "V", 1),
                "Leakage current" to fmt(u16(41)?.div(1000.0), "A", 3),
            ),
            section("Energy", "Today" to fmt(u32(49)?.div(10.0), "kWh", 1), "Total" to fmt(u32(31)?.div(10.0), "kWh", 1)),
        ).filter { it.rows.isNotEmpty() }
    }

    internal companion object {
        const val STATIC_REFRESH_POLLS = 12
        val GATEWAY_NO_ANSWER = setOf(0x0A, 0x0B)
        val LIVE_PARTS = listOf(7..9, 11..41, 42..53)
        val PHASES = mapOf(0x0031 to "Single-phase", 0x0033 to "Three-phase")

        /** Register 7, bit number (1 = LSB) → alarm. */
        val ALARMS_7 = mapOf(
            16 to "Master-slave communication failed", 15 to "EEPROM failure", 14 to "Grid frequency out of range (master)",
            12 to "Grid impedance out of range (master)", 10 to "Relay failure", 8 to "ENS grid voltage mismatch",
            7 to "ENS grid frequency mismatch", 6 to "ENS grid impedance mismatch", 5 to "ENS measurement mismatch",
            4 to "AC current offset failure", 3 to "PV isolation failure", 2 to "Grid voltage out of range (master)",
        )

        /** Register 8, bit number (1 = LSB) → alarm. */
        val ALARMS_8 = mapOf(
            16 to "PV voltage too high", 15 to "Self-test failed", 14 to "Over-temperature", 12 to "DC bus failure",
            11 to "Leakage current too high", 10 to "No grid", 9 to "Grid impedance change (ΔZ)", 8 to "Device fault",
            7 to "DC bus voltage too high", 6 to "DC bus voltage too low", 5 to "ENS GFCI mismatch", 4 to "ENS DC injection mismatch",
            3 to "2.5 V reference fault", 2 to "DC injection sensor fault", 1 to "GFCI sensor failure",
        )

        /** Register 9, bit number (1 = LSB) → status flag. */
        val STATUS_9 = mapOf(
            8 to "Remote control active", 7 to "Commands via Ethernet", 6 to "Commands via RS232-2", 5 to "Commands via slot 2",
            4 to "Commands via slot 1", 3 to "Characterisation updated", 2 to "Q(P) active", 1 to "DC box active",
        )
    }
}
