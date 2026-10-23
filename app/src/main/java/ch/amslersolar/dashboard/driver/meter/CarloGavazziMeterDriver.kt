// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.meter

import ch.amslersolar.dashboard.driver.DeviceDriver
import ch.amslersolar.dashboard.driver.Details.bits
import ch.amslersolar.dashboard.driver.Details.enum
import ch.amslersolar.dashboard.driver.Details.fmt
import ch.amslersolar.dashboard.driver.Details.section
import ch.amslersolar.dashboard.driver.meter.MeterFmt.a
import ch.amslersolar.dashboard.driver.meter.MeterFmt.exportW
import ch.amslersolar.dashboard.driver.meter.MeterFmt.hz
import ch.amslersolar.dashboard.driver.meter.MeterFmt.kvarh
import ch.amslersolar.dashboard.driver.meter.MeterFmt.kwh
import ch.amslersolar.dashboard.driver.meter.MeterFmt.pf
import ch.amslersolar.dashboard.driver.meter.MeterFmt.phase
import ch.amslersolar.dashboard.driver.meter.MeterFmt.phaseSection
import ch.amslersolar.dashboard.driver.meter.MeterFmt.v
import ch.amslersolar.dashboard.driver.meter.MeterFmt.va
import ch.amslersolar.dashboard.driver.meter.MeterFmt.vars
import ch.amslersolar.dashboard.driver.meter.MeterFmt.w
import ch.amslersolar.dashboard.driver.modbus.ModbusException
import ch.amslersolar.dashboard.driver.modbus.ModbusTcpClient
import ch.amslersolar.dashboard.driver.sunspec.RegisterReader
import ch.amslersolar.dashboard.model.DetailSection
import ch.amslersolar.dashboard.model.DeviceInfo
import ch.amslersolar.dashboard.model.DeviceProblem
import ch.amslersolar.dashboard.model.DeviceReading
import java.io.IOException

/**
 * Carlo Gavazzi energy meters over Modbus (RTU via an RS485 gateway, or TCP for the EM24 Ethernet).
 *
 * Specs (docs/specs/carlo-gavazzi/):
 * - EM24_E1_CP.pdf "EM24 Ethernet Communication Protocol" Version 0 Revision 1.3 (EM24-DIN …E1, Modbus TCP).
 * - EM330_EM340_ET330_ET340_CP.pdf "EM300 / ET300 Series Communication Protocol" Version 2 Revision 17 (2021-07-05).
 * - EM210_CP.pdf "EM210_AV Communication Protocol" Version 4 Revision 3 (2018-05-10).
 * - EM24_IS_CP.pdf "EM24-DIN PFA, PFB & X models Communication Protocol" Version 4 Revision 0 (2012-12-03): the
 *   older RS485 EM24-DIN, whose map differs from 002Eh on (PF, Hz and energies move).
 *
 * Common to all four documents:
 * - FC 03 and FC 04 "have exactly the same effect"; this driver uses FC 04 (the documents' "3xxxxx" numbering).
 *   Addresses are the documents' "physical address" (0-based word address in the frame).
 * - Byte order MSB→LSB, INT32/UINT32/UINT64 word order LSW→MSW. Exception (EM300 table 2.8-2): ID code 340
 *   "EM340 ENGINEERING SAMPLE ONLY (with MSW-LSW word order)", decoded MSW first.
 * - Model detection: "Carlo Gavazzi Controls identification code" at 000Bh, read with length 1 ("limited to a word
 *   at a time"; with a longer request the E1 forwards to the instantaneous variables). Codes: E1 1648…1653,
 *   EM330 331/332, EM331 355, ET330 335/336, EM340 340/341, EM341 346, ET340 345, EM210 210, old EM24-DIN 71/72/73.
 *   Any other answer → IOException.
 * - Scaling: V ×10, A ×1000, W/VA/var ×10, PF ×1000, kWh/kvarh ×10, hours ×100; Hz ×10 except EM210 (see below).
 * - Overflow ("EEEE"): MSW of the variable = 7FFFh → value treated as missing.
 * - Sign: EM300 and EM210 state "Negative values correspond to exported active power, positive values correspond to
 *   imported active power"; kWh(+) counts import, kWh(−) export (EM300 bidirectional mode B / PFB). The E1 and the
 *   old EM24-DIN documents use the same geometric representation and kWh(+)/kWh(−) naming but do not state the W
 *   sign in words (derived, same convention assumed). gridPowerW = −W sys.
 *   EM300 "easy connection" mode (A / PFA): P is always > 0 and kWh always increases → export can't be seen; the
 *   driver reports this in [DeviceReading.status].
 * - Max registers per request: E1 125, EM300 50 (also register 2004h), EM210 61, old EM24-DIN 11 (the frame tables'
 *   "1 to 20" / "1 to 11" contradict the EM300/EM210 text; the text values are used).
 *
 * Per family:
 * - EM210: Hz at 0033h is documented with "Value weight: Hz" (×1, unlike the others). UNVERIFIED: a raw value > 100
 *   is taken as Hz×10. No kvarh(−) register. Registers 0038h…004Dh are undocumented and not read (two requests).
 * - Old EM24-DIN: 11 registers per request → 8 requests per poll; serial number at 1300h, measuring system 1102h.
 *   Line settings: 4800/9600 baud, 8N1 fixed (doc 2.9.7).
 * - EM300/EM210 line settings: 9600 baud, no parity, 1 stop bit, address 1 by default (EM300 table 2.9-7,
 *   EM210 table 2.8-10).
 * - Serial number (5000h, 7 words; old EM24 1300h): E1/EM210/old EM24 two ASCII characters per word; the EM300 table
 *   says "MSB: not to be used, LSB: ASCII". Both are handled (printable MSBs are kept).
 * - Measuring system 1P / 2P (1002h, old EM24 1102h) limits the reported phases to L1 / L1+L2.
 * - No daily energy counters: [DeviceReading.today] stays empty; lifetime import/export go into the details.
 */
class CarloGavazziMeterDriver(private val reader: RegisterReader, private val unitId: Int) : DeviceDriver {
    constructor(client: ModbusTcpClient, unitId: Int) : this(RegisterReader { u, r, c -> client.readInputRegisters(u, r, c) }, unitId)

    internal enum class Family(val maxRegs: Int, val maxGap: Int) {
        EM24_E1(125, 32), EM300(50, 32), EM210(61, 0), EM24_DIN(11, 6)
    }

    internal data class Model(val family: Family, val name: String, val idCode: Int, val mswFirst: Boolean = false)

    private val io = RegFieldReader(reader, unitId)
    private var model: Model? = null
    private var static: Map<Int, Int>? = null
    private var polls = 0

    override suspend fun identify(): DeviceInfo {
        val m = detect()
        val serial = RegImage(io.fetch(listOf(RegField(serialAddr(m.family), 7)), m.family.maxRegs)).let { serial(it, serialAddr(m.family)) }
        return DeviceInfo(model = "Carlo Gavazzi ${m.name}", serial = serial.orEmpty())
    }

    override suspend fun read(): DeviceReading {
        val m = model ?: detect()
        if (static == null || polls % STATIC_REFRESH_POLLS == 0) {
            io.forgetMissing()
            static = io.fetch(staticFields(m.family), m.family.maxRegs, 0)
        }
        polls++
        val live = io.fetch(liveFields(m.family), m.family.maxRegs, m.family.maxGap)
        if (live.isEmpty()) throw IOException("Carlo Gavazzi meter returned no measurements")
        return parse(m, RegImage(static.orEmpty() + live, lswFirst = !m.mswFirst))
    }

    private suspend fun detect(): Model {
        val raw = try {
            reader.read(unitId, ID_CODE, 1).u16(ID_CODE)
        } catch (e: ModbusException) {
            throw IOException("No Carlo Gavazzi identification code at 000Bh (Modbus exception ${e.code})")
        }
        return (modelFor(raw) ?: throw IOException("Not a supported Carlo Gavazzi meter (identification code $raw)"))
            .also { model = it }
    }

    internal companion object {
        const val STATIC_REFRESH_POLLS = 12
        const val ID_CODE = 0x000B

        private val E1_NAMES = mapOf(
            1648 to "EM24DINAV23XE1X", 1649 to "EM24DINAV23XE1PFA", 1650 to "EM24DINAV23XE1PFB",
            1651 to "EM24DINAV53XE1X", 1652 to "EM24DINAV53XE1PFA", 1653 to "EM24DINAV53XE1PFB",
        )

        fun modelFor(code: Int): Model? = when (code) {
            in E1_NAMES -> Model(Family.EM24_E1, "EM24 Ethernet (${E1_NAMES[code]})", code)
            331 -> Model(Family.EM300, "EM330 (AV6)", code)
            332 -> Model(Family.EM300, "EM330 (AV5)", code)
            355 -> Model(Family.EM300, "EM331", code)
            335 -> Model(Family.EM300, "ET330 (AV5)", code)
            336 -> Model(Family.EM300, "ET330 (AV6)", code)
            340 -> Model(Family.EM300, "EM340 (engineering sample)", code, mswFirst = true)
            341 -> Model(Family.EM300, "EM340", code)
            346 -> Model(Family.EM300, "EM341", code)
            345 -> Model(Family.EM300, "ET340", code)
            210 -> Model(Family.EM210, "EM210", code)
            71 -> Model(Family.EM24_DIN, "EM24-DIN (AV9/AV2)", code)
            72 -> Model(Family.EM24_DIN, "EM24-DIN (AV5)", code)
            73 -> Model(Family.EM24_DIN, "EM24-DIN (AV6)", code)
            else -> null
        }

        fun serialAddr(f: Family) = if (f == Family.EM24_DIN) 0x1300 else 0x5000

        /** 7 words; two characters per word, or (EM300 table) only the LSB. Unprintable bytes are dropped. */
        fun serial(r: RegImage, addr: Int): String? {
            val sb = StringBuilder()
            for (i in 0 until 7) {
                val w = r.u16(addr + i) ?: return null
                for (c in intArrayOf(w shr 8, w and 0xFF)) if (c in 0x21..0x7E) sb.append(c.toChar())
            }
            return sb.toString().takeIf { it.isNotEmpty() }
        }

        // Live data, physical addresses. New map = EM24 E1, EM300, EM210 (identical up to 0037h).
        private val INSTANT_32 = (0x0000..0x002C step 2).toList() // V, A, W, VA, var per phase; sys values
        private val NEW_LIVE = regFields(2, *INSTANT_32.toIntArray()) + regFields(1, 0x2E, 0x2F, 0x30, 0x31, 0x32, 0x33) +
            regFields(2, 0x34, 0x36)
        private val OLD_LIVE = regFields(2, *INSTANT_32.toIntArray()) + regFields(2, 0x2E, 0x30) +
            regFields(1, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37) + regFields(2, 0x3E, 0x40, 0x5C, 0x5E)

        fun liveFields(f: Family): List<RegField> = when (f) {
            Family.EM24_E1, Family.EM300 -> NEW_LIVE + regFields(2, 0x38, 0x4E, 0x50)
            Family.EM210 -> NEW_LIVE + regFields(2, 0x4E)
            Family.EM24_DIN -> OLD_LIVE
        }

        fun staticFields(f: Family): List<RegField> = when (f) {
            Family.EM24_E1 -> listOf(
                RegField(0x5000, 7), RegField(0x0302, 1, alone = true), RegField(0x0304, 1, alone = true), RegField(0x005A, 2),
                RegField(0x1002, 1), RegField(0x1003, 2), RegField(0x1005, 2), RegField(0xA000, 1), RegField(0x1201, 1), RegField(0xA100, 1),
                RegField(0x210C, 1), RegField(0x210D, 1), RegField(0x2110, 6), RegField(0x2120, 12),
            )
            Family.EM300 -> listOf(
                RegField(0x5000, 7), RegField(0x5010, 1), RegField(0x0302, 1), RegField(0x0303, 1), RegField(0x005A, 2),
                RegField(0x1002, 1), RegField(0x1003, 2), RegField(0x1005, 2), RegField(0x1103, 1), RegField(0x1105, 1),
                RegField(0x2000, 5),
            )
            Family.EM210 -> listOf(
                RegField(0x5000, 7), RegField(0x5007, 1), RegField(0x0302, 1, alone = true), RegField(0x0303, 1, alone = true),
                RegField(0x0304, 1, alone = true), RegField(0x005A, 2), RegField(0x1002, 1), RegField(0x1003, 2), RegField(0x1005, 2),
                RegField(0x1105, 1), RegField(0x1300, 1), RegField(0x2000, 4),
            )
            Family.EM24_DIN -> listOf(
                RegField(0x1300, 7), RegField(0x0301, 1, alone = true), RegField(0x0302, 1, alone = true),
                RegField(0x0303, 1, alone = true), RegField(0x0304, 1, alone = true), RegField(0x0060, 2),
                RegField(0x1101, 1), RegField(0x1102, 1), RegField(0x110A, 2), RegField(0x112C, 2), RegField(0x112E, 2),
            )
        }

        private val MEAS_E1 = mapOf(0 to "3P.n", 1 to "3P.1", 2 to "2P", 3 to "1P", 4 to "3P")
        private val MEAS_EM300 = mapOf(0 to "3Pn", 1 to "3P", 2 to "2P")
        private val MEAS_EM210 = mapOf(0 to "3Pn", 1 to "3P", 2 to "2P", 3 to "1P", 4 to "3P1")
        private val MEAS_OLD = mapOf(0 to "3Pn", 1 to "3P1", 2 to "2P", 3 to "1P", 4 to "3P")
        private val APPLICATIONS = (0..7).associateWith { "${'A' + it}" }
        private val WIRING = listOf(
            "wrong voltage sequence", "phase 1 inverted", "phase 2 inverted", "phase 3 inverted",
            "phases 1 and 2 exchanged", "phases 1 and 3 exchanged", "phases 2 and 3 exchanged", "phases 1, 2, 3 exchanged",
        )

        /** INT32 with the overflow marker (MSW 7FFFh) as null, divided by [div]. */
        private fun RegImage.i32(a: Int, div: Double): Double? =
            if (msw32(a) == 0x7FFF) null else s32(a)?.div(div)

        private fun RegImage.i16(a: Int, div: Double): Double? = s16(a)?.takeIf { it != 0x7FFF }?.div(div)

        /** Old EM24-DIN version code (table 2.6-1) → item number. */
        fun oldEm24Item(code: Int): String? {
            val (base, suffix) = when {
                code >= 128 -> code - 128 to "PFB"
                code >= 64 -> code - 64 to "X"
                else -> code to "PFA"
            }
            val item = when (base) {
                0 -> "AV93XO2"; 2 -> "AV93XIS"; 3 -> "AV93XXX"
                4 -> "AV23XO2"; 5 -> "AV23XIS"; 6 -> "AV23XXX"
                7 -> "AV53xO2"; 9 -> "AV53xIS"; 10 -> "AV53xXX"
                11 -> "AV63xO2"; 12 -> "AV63xR2"; 13 -> "AV63xIS"; 14 -> "AV63xXX"
                else -> return null
            }
            if (base >= 11 && suffix != "X") return null
            return "EM24DIN$item$suffix"
        }

        fun parse(m: Model, r: RegImage): DeviceReading {
            val f = m.family
            val old = f == Family.EM24_DIN
            val pfBase = if (old) 0x32 else 0x2E
            val hzAddr = if (old) 0x37 else 0x33
            val seqAddr = if (old) 0x36 else 0x32
            val kwhImp = if (old) 0x3E else 0x34
            val kvarhImp = if (old) 0x40 else 0x36
            val kwhExp = if (old) 0x5C else 0x4E
            val kvarhExp = if (old) 0x5E else 0x50
            val hoursAddr = if (old) 0x60 else 0x5A
            val measAddr = if (old) 0x1102 else 0x1002

            val hz = r.s16(hzAddr)?.takeIf { it != 0x7FFF }?.let {
                if (f == Family.EM210 && it <= 100) it.toDouble() else it / 10.0
            }
            val measNames = when (f) {
                Family.EM24_E1 -> MEAS_E1; Family.EM300 -> MEAS_EM300; Family.EM210 -> MEAS_EM210; Family.EM24_DIN -> MEAS_OLD
            }
            val meas = r.u16(measAddr)?.let { measNames[it] }
            val phaseCount = when (meas) { "1P" -> 1; "2P" -> 2; else -> 3 }

            fun vln(i: Int) = r.i32(2 * i, 10.0)
            fun vll(i: Int) = r.i32(0x06 + 2 * i, 10.0)
            fun amps(i: Int) = r.i32(0x0C + 2 * i, 1000.0)
            fun watts(i: Int) = r.i32(0x12 + 2 * i, 10.0)
            fun vas(i: Int) = r.i32(0x18 + 2 * i, 10.0)
            fun varsOf(i: Int) = r.i32(0x1E + 2 * i, 10.0)
            fun pfOf(i: Int) = r.i16(pfBase + i, 1000.0)

            val names = listOf("L1", "L2", "L3")
            val phases = (0 until phaseCount).mapNotNull { i -> phase(names[i], vln(i), amps(i), hz, watts(i)) }
            val wSys = r.i32(0x28, 10.0)

            val easyMode = f == Family.EM300 && r.u16(0x1103) == 0
            val wiring = r.u16(0x1105)?.takeIf { f == Family.EM300 || f == Family.EM210 }?.let { bitsSet ->
                WIRING.filterIndexed { i, _ -> bitsSet and (1 shl i) != 0 }
            }.orEmpty()
            val status = when {
                wiring.isNotEmpty() -> "Wiring: " + wiring.joinToString(", ")
                easyMode -> "Easy connection mode (A): power always positive, export not measured"
                else -> null
            }

            val firmware = when (f) {
                Family.EM24_E1 -> r.u16(0x0302)?.let { e1Version(it) }
                Family.EM300, Family.EM210 -> r.u16(0x0302)?.let { v -> "${'A' + v}" + (r.u16(0x0303)?.let { ".$it" } ?: "") }
                Family.EM24_DIN -> r.u16(0x0303)?.let { "revision $it" }
            }
            val details = mutableListOf<DetailSection>()
            details += section(
                "Meter",
                "Model" to m.name,
                "Item number" to if (old) r.u16(0x0302)?.let { oldEm24Item(it) } else null,
                "Identification code" to m.idCode.toString(),
                "Serial number" to serial(r, serialAddr(f)),
                "Firmware" to firmware,
                "Communication module firmware" to if (f == Family.EM24_E1) r.u16(0x0304)?.let { e1Version(it) } else null,
                "Production year" to when (f) {
                    Family.EM300 -> r.u16(0x5010)?.toString(); Family.EM210 -> r.u16(0x5007)?.toString(); else -> null
                },
                "Measuring system" to enum(r.u16(measAddr), measNames),
                "Application" to enum(r.u16(if (f == Family.EM24_E1) 0xA000 else if (old) 0x1101 else 0x1300), APPLICATIONS)
                    .takeIf { f != Family.EM300 },
                "Measurement mode" to if (f == Family.EM300) enum(r.u16(0x1103), mapOf(0 to "A (easy connection)", 1 to "B (bidirectional)")) else null,
                "CT ratio" to fmt(r.u32(if (old) 0x112C else 0x1003)?.div(10.0), "", 1),
                "VT ratio" to fmt(r.u32(if (old) 0x112E else 0x1005)?.div(10.0), "", 1),
                "Wiring check" to r.u16(0x1105)?.takeIf { f == Family.EM300 || f == Family.EM210 }?.let {
                    if (it == 0) "OK" else bits(it.toLong(), 2)
                },
                "Current tariff" to when (f) {
                    Family.EM24_E1 -> enum(r.u16(0x1201), mapOf(0 to "disabled", 1 to "1", 2 to "2", 3 to "3", 4 to "4"))
                    Family.EM24_DIN -> r.u16(0x0301)?.let { "${it + 1}" }
                    else -> null
                },
                "Front selector" to when (f) {
                    Family.EM24_E1 -> enum(r.u16(0xA100), mapOf(3 to "LOCK", 2 to "1", 1 to "2", 0 to "kvarh"))
                    Family.EM24_DIN -> enum(r.u16(0x0304), mapOf(3 to "locked", 2 to "unlocked", 1 to "unlocked", 0 to "unlocked"))
                    Family.EM210 -> enum(r.u16(0x0304), mapOf(1 to "programming locked", 0 to "programming unlocked"))
                    else -> null
                },
            )
            details += section(
                "System",
                "Active power (+ import)" to w(wSys),
                "Apparent power" to va(r.i32(0x2A, 10.0)),
                "Reactive power" to vars(r.i32(0x2C, 10.0)),
                "Power factor" to pf(r.i16(pfBase + 3, 1000.0)),
                "Frequency" to hz(hz),
                "Voltage L-N (system)" to v(r.i32(0x24, 10.0)),
                "Voltage L-L (system)" to v(r.i32(0x26, 10.0)),
                "Phase sequence" to if (phaseCount == 3) enum(r.s16(seqAddr), mapOf(0 to "L1-L2-L3", -1 to "L1-L3-L2", 1 to "L1-L3-L2")) else null,
                "Power demand" to w(r.i32(if (old) 0x2E else 0x38, 10.0)),
                "VA demand" to if (old) va(r.i32(0x30, 10.0)) else null,
            )
            for (i in 0 until phaseCount) {
                details += phaseSection(names[i], vln(i), vll(i), amps(i), watts(i), vas(i), varsOf(i), pfOf(i))
            }
            details += section(
                "Energy (lifetime)",
                "Imported kWh(+)" to kwh(r.i32(kwhImp, 10.0), 1),
                "Exported kWh(−)" to kwh(r.i32(kwhExp, 10.0), 1),
                "Reactive imported kvarh(+)" to kvarh(r.i32(kvarhImp, 10.0), 1),
                "Reactive exported kvarh(−)" to if (f == Family.EM210) null else kvarh(r.i32(kvarhExp, 10.0), 1),
                "Run hours" to fmt(r.i32(hoursAddr, 100.0), "h", 2),
            )
            details += when (f) {
                Family.EM24_E1 -> section(
                    "Modbus TCP",
                    "IP address" to ip(r, 0x2120),
                    "Subnet mask" to ip(r, 0x2124),
                    "Gateway" to ip(r, 0x2128),
                    "Port" to r.u16(0x210C)?.toString(),
                    "DHCP" to enum(r.u16(0x210D), mapOf(0 to "off", 1 to "on")),
                    "MAC address" to (0 until 6).map { r.u16(0x2110 + it) }
                        .takeIf { b -> b.none { it == null } }?.joinToString(":") { "%02X".format(it) },
                )
                Family.EM300 -> section(
                    "Modbus RTU",
                    "Address" to r.u16(0x2000)?.toString(),
                    "Baud rate" to enum(r.u16(0x2001), mapOf(1 to "9600", 2 to "19200", 3 to "38400", 4 to "57600", 5 to "115200")),
                    "Parity" to enum(r.u16(0x2002), mapOf(1 to "none", 2 to "even")),
                    "Stop bits" to enum(r.u16(0x2003), mapOf(0 to "1", 1 to "1", 2 to "2")),
                    "Max registers per request" to r.u16(0x2004)?.toString(),
                )
                Family.EM210 -> section(
                    "Modbus RTU",
                    "Address" to r.u16(0x2000)?.toString(),
                    "Baud rate" to enum(r.u16(0x2001), mapOf(0 to "9600", 1 to "19200", 2 to "38400", 3 to "57600", 4 to "115200")),
                    "Parity" to enum(r.u16(0x2002), mapOf(0 to "none", 1 to "even")),
                    "Stop bits" to enum(r.u16(0x2003), mapOf(0 to "1", 1 to "2")),
                )
                Family.EM24_DIN -> section(
                    "Modbus RTU",
                    "Address" to r.u16(0x110A)?.toString(),
                    "Baud rate" to enum(r.u16(0x110B), mapOf(0 to "4800", 1 to "9600")),
                    "Framing" to "8N1 (fixed)",
                )
            }
            // Wrong connection status 1105h (EM300 table 2.8-2, EM210 table 2.8-4): one warning per bit set.
            val problems = r.u16(0x1105)?.takeIf { f == Family.EM300 || f == Family.EM210 }?.let { bitsSet ->
                WIRING.indices.filter { bitsSet and (1 shl it) != 0 }
                    .map { DeviceProblem(DeviceProblem.Kind.WARNING, "Wrong wiring: ${WIRING[it]} (bit $it)") }
            }.orEmpty()
            return DeviceReading(gridPowerW = exportW(wSys), phases = phases, status = status, details = details, problems = problems)
        }

        /** E1 version word: MSB bits 4–7 major, 0–3 minor; LSB revision (101Eh = 1.0.30). */
        fun e1Version(v: Int) = "${(v shr 12) and 0xF}.${(v shr 8) and 0xF}.${v and 0xFF}"

        private fun ip(r: RegImage, a: Int): String? =
            (0 until 4).map { r.u16(a + it) ?: return null }.joinToString(".")
    }
}
