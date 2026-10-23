// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.meter

import ch.amslersolar.dashboard.driver.DeviceDriver
import ch.amslersolar.dashboard.driver.Details.bits
import ch.amslersolar.dashboard.driver.Details.fmt
import ch.amslersolar.dashboard.driver.Details.section
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
 * Siemens SENTRON PAC2200 (PAC2200CLP, 7KM2200-2EA…) power meter over Modbus TCP.
 *
 * Spec: "SENTRON PAC2200CLP Equipment Manual" 12/2022, L1V30820585B-04 (docs/specs/siemens/), appendix A.1
 * "Modbus TCP". Port 502 by default (configurable on the device).
 * - Function 03 (04 works the same for all measured variables, A.1.3).
 * - Addresses: the tables' "Offset" is sent as it is (voltage L1-N = offset 1). UNVERIFIED: the manual doesn't say
 *   whether "offset" is 0- or 1-based; it does say a read starting in the second register of a value is
 *   rejected, so a wrong base fails loudly instead of returning shifted data.
 * - Encoding: Float (32-bit), Double (64-bit) for energies, unsigned long. Word order: high word first (Modbus
 *   big-endian; the logbook example in A.1.14 lists a Double as big-endian bytes). UNVERIFIED for registers.
 * - Reads: 1–42 (U, I, S, P, Q, PF per phase), 55–72 (f, averages, totals), 961–976 (MID energy totals) per poll;
 *   I&M 0 identity (64001, 27 registers, must be read as one block), status and tariff / phase energies every 12.
 * - Sign: active power is signed; the MID table calls import "positive active +A, phase angle 270°–90°" and export
 *   "negative active −A, 90°–270°", so P > 0 is drawn from the grid. Our [DeviceReading.gridPowerW] = −P.
 *   Per-phase power factors are 0…1 (unsigned, documented).
 * - Identity: I&M 0 manufacturer id 42 (= Siemens AG, documented), order number, serial number, firmware
 *   (1 char + 3 bytes, e.g. "V3.2.0"). No daily energy counters over Modbus (the demand period values 549 ff.
 *   cover the current measuring period only).
 */
class SiemensPacDriver(private val reader: RegisterReader, private val unitId: Int) : DeviceDriver {
    constructor(client: ModbusTcpClient, unitId: Int) : this(RegisterReader { u, r, c -> client.readHoldingRegisters(u, r, c) }, unitId)

    private val fields = MeterFieldReader(reader, unitId, maxGap = 0) // gaps in the map aren't documented as readable
    private var static: Map<Int, Int>? = null
    private var polls = 0

    override suspend fun identify(): DeviceInfo {
        val img = MeterRegs(fields.fetch(IDENTITY))
        if (img.u16(IM0) != SIEMENS_ID) throw IOException("Not a Siemens PAC (I&M manufacturer id ${img.u16(IM0)})")
        return DeviceInfo(model = model(img), serial = img.text(IM0 + 11, 8).orEmpty())
    }

    override suspend fun read(): DeviceReading {
        if (static == null || polls % STATIC_REFRESH_POLLS == 0) {
            fields.retryMissing()
            static = fields.fetch(STATIC)
        }
        polls++
        val img = MeterRegs(static.orEmpty() + fields.fetch(LIVE))
        return parse(img).copy(details = details(img))
    }

    internal companion object {
        const val STATIC_REFRESH_POLLS = 12
        const val SIEMENS_ID = 42
        const val IM0 = 64001 // manufacturer id; order no. 64002 (10), serial 64012 (8), HW 64020, FW 64021 (2)

        const val U1N = 1 // U2N 3, U3N 5; U12 7, U23 9, U31 11
        const val I1 = 13 // I2 15, I3 17
        const val S1 = 19
        const val P1 = 25
        const val Q1 = 31
        const val PF1 = 37
        const val HZ = 55
        const val ULN_AVG = 57
        const val ULL_AVG = 59
        const val I_AVG = 61
        const val S_TOTAL = 63
        const val P_TOTAL = 65
        const val Q_TOTAL = 67
        const val PF_TOTAL = 69
        const val I_N = 71
        const val DIAGNOSTICS = 205
        const val ACTIVE_TARIFF = 211
        const val E_TARIFF = 801 // import T1 801, T2 805, export T1 809, T2 813; reactive 817 … 829
        const val E_PHASE = 841 // L1 import T1, +40 per phase
        const val E_SECONDARY_IMPORT = 961
        const val E_SECONDARY_EXPORT = 965
        const val E_IMPORT = 969
        const val E_EXPORT = 973

        val IDENTITY = listOf(MeterField(IM0, 27))
        val LIVE = fields(2, *(1..41 step 2).toList().toIntArray()) + fields(2, *(55..71 step 2).toList().toIntArray()) +
            fields(4, E_SECONDARY_IMPORT, E_SECONDARY_EXPORT, E_IMPORT, E_EXPORT)
        val STATIC = IDENTITY + fields(2, DIAGNOSTICS, DIAGNOSTICS + 2, DIAGNOSTICS + 4, ACTIVE_TARIFF) +
            fields(4, *(E_TARIFF until E_TARIFF + 32 step 4).toList().toIntArray()) +
            (0 until 3).flatMap { p -> fields(4, *(0 until 16 step 4).map { E_PHASE + 40 * p + it }.toIntArray()) }

        fun model(img: MeterRegs): String = listOfNotNull("SENTRON PAC2200", img.text(IM0 + 1, 10)?.let { "($it)" }).joinToString(" ")

        fun firmware(img: MeterRegs): String? {
            val a = img.u16(IM0 + 20) ?: return null
            val b = img.u16(IM0 + 21) ?: return null
            val prefix = (a shr 8).toChar().takeIf { it in 'A'..'Z' }?.toString().orEmpty()
            return "$prefix${a and 0xFF}.${b shr 8}.${b and 0xFF}"
        }

        private fun w(img: MeterRegs, a: Int): Int? = img.f32(a)?.let { -it.roundToInt() }

        fun parse(img: MeterRegs): DeviceReading {
            val hz = img.f32(HZ) ?: 0.0
            val phases = (0 until 3).mapNotNull { i ->
                val v = img.f32(U1N + 2 * i) ?: return@mapNotNull null
                PhaseReading("L${i + 1}", v, img.f32(I1 + 2 * i), hz, w(img, P1 + 2 * i))
            }
            return DeviceReading(gridPowerW = w(img, P_TOTAL), phases = phases, problems = problems(img))
        }

        /** Device status bits of offset 205 (table A-5) that point at a measuring problem. */
        private val STATUS_WARNINGS = listOf(
            0x04000000L to "Voltage out of range", 0x08000000L to "Current out of range",
            0x00020000L to "Maximum pulse rate exceeded",
        )

        fun problems(img: MeterRegs): List<DeviceProblem> {
            val v = img.u32(DIAGNOSTICS) ?: return emptyList()
            return STATUS_WARNINGS.filter { v and it.first != 0L }.map { (mask, text) ->
                DeviceProblem(DeviceProblem.Kind.WARNING, "$text (status 0x" + mask.toString(16).uppercase().padStart(8, '0') + ")")
            }
        }

        private fun kwh(img: MeterRegs, a: Int, unit: String = "kWh") = fmt(img.f64(a)?.div(1000), unit, 1)

        fun details(img: MeterRegs): List<DetailSection> {
            val out = mutableListOf<DetailSection>()
            out += section(
                "Device",
                "Model" to "SENTRON PAC2200",
                "Order number" to img.text(IM0 + 1, 10),
                "Serial number" to img.text(IM0 + 11, 8),
                "Hardware version" to img.u16(IM0 + 19)?.toString(),
                "Firmware" to firmware(img),
                "Diagnostics / status" to bits(img.u32(DIAGNOSTICS)),
                "Digital outputs" to bits(img.u32(DIAGNOSTICS + 2)),
                "Digital inputs" to bits(img.u32(DIAGNOSTICS + 4)),
                "Active tariff" to img.u32(ACTIVE_TARIFF)?.let { "T${it + 1}" },
            )
            out += section(
                "Grid",
                "Active power (+ export)" to fmt(w(img, P_TOTAL), "W"),
                "Active power (meter, + import)" to fmt(img.f32(P_TOTAL), "W", 0),
                "Apparent power" to fmt(img.f32(S_TOTAL), "VA", 0),
                "Reactive power" to fmt(img.f32(Q_TOTAL), "var", 0),
                "Power factor" to fmt(img.f32(PF_TOTAL), "", 3),
                "Frequency" to fmt(img.f32(HZ), "Hz", 2),
                "Voltage L-N average" to fmt(img.f32(ULN_AVG), "V", 1),
                "Voltage L-L average" to fmt(img.f32(ULL_AVG), "V", 1),
                "Current average" to fmt(img.f32(I_AVG), "A", 2),
                "Neutral current (calculated)" to fmt(img.f32(I_N), "A", 2),
            )
            for (i in 0 until 3) {
                val e = E_PHASE + 40 * i
                out += section(
                    "L${i + 1}",
                    "Voltage L-N" to fmt(img.f32(U1N + 2 * i), "V", 1),
                    "Voltage L-L" to fmt(img.f32(7 + 2 * i), "V", 1),
                    "Current" to fmt(img.f32(I1 + 2 * i), "A", 2),
                    "Active power (meter, + import)" to fmt(img.f32(P1 + 2 * i), "W", 0),
                    "Apparent power" to fmt(img.f32(S1 + 2 * i), "VA", 0),
                    "Reactive power" to fmt(img.f32(Q1 + 2 * i), "var", 0),
                    "Power factor" to fmt(img.f32(PF1 + 2 * i), "", 3),
                    "Import T1" to kwh(img, e), "Import T2" to kwh(img, e + 4),
                    "Export T1" to kwh(img, e + 8), "Export T2" to kwh(img, e + 12),
                )
            }
            out += section(
                "Energy (lifetime)",
                "Active import (MID)" to kwh(img, E_IMPORT),
                "Active export (MID)" to kwh(img, E_EXPORT),
                "Secondary active import (MID)" to kwh(img, E_SECONDARY_IMPORT),
                "Secondary active export (MID)" to kwh(img, E_SECONDARY_EXPORT),
                "Active import T1" to kwh(img, E_TARIFF), "Active import T2" to kwh(img, E_TARIFF + 4),
                "Active export T1" to kwh(img, E_TARIFF + 8), "Active export T2" to kwh(img, E_TARIFF + 12),
                "Reactive import T1" to kwh(img, E_TARIFF + 16, "kvarh"), "Reactive import T2" to kwh(img, E_TARIFF + 20, "kvarh"),
                "Reactive export T1" to kwh(img, E_TARIFF + 24, "kvarh"), "Reactive export T2" to kwh(img, E_TARIFF + 28, "kvarh"),
            )
            return out.filter { it.rows.isNotEmpty() }
        }
    }
}
