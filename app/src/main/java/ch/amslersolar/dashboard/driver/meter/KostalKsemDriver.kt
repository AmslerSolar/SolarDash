// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.meter

import ch.amslersolar.dashboard.driver.DeviceDriver
import ch.amslersolar.dashboard.driver.Details.enum
import ch.amslersolar.dashboard.driver.Details.fmt
import ch.amslersolar.dashboard.driver.Details.section
import ch.amslersolar.dashboard.driver.modbus.ModbusTcpClient
import ch.amslersolar.dashboard.driver.sunspec.RegisterReader
import ch.amslersolar.dashboard.model.DetailSection
import ch.amslersolar.dashboard.model.DeviceInfo
import ch.amslersolar.dashboard.model.DeviceReading
import ch.amslersolar.dashboard.model.PhaseReading
import java.io.IOException
import java.time.Instant
import kotlin.math.roundToInt

/**
 * KOSTAL Smart Energy Meter (KSEM G1 / G2) over Modbus TCP, internal OBIS register map (not the SunSpec mirror).
 *
 * Spec: "KSEM G1/G2 Interface Description MODBUS", 13.03.2025, valid for SW 2.6.0 (docs/specs/kostal/).
 * - Modbus TCP slave on port 502 (802 with TLS, not supported here); must be activated in the KSEM web UI.
 * - Function 03 for every register. Addresses are sent as listed (the doc's example reads "holding registers
 *   0 and 1" for active power+). UNVERIFIED: the unit id isn't specified (1 used by default).
 * - Encoding: multi-register values have the most significant register first (documented), bytes big-endian.
 *   Power uint32 × 0.1 W / var / VA, current uint32 × 0.001 A, voltage uint32 × 0.001 V, frequency uint32 × 0.001
 *   Hz, power factor int32 × 0.001, energies uint64 × 0.1 Wh.
 * - Reading an unspecified register is answered with exception 2 (documented), and the map has gaps: the live
 *   values are first read in bridged blocks of up to 125 registers (0–105, 120–147, 512–519); if the
 *   meter rejects a block, it is read as its documented runs from then on.
 * - Sign: power is split by direction into two unsigned registers, "+" = OBIS 1-0:1.4.0 (active power drawn from
 *   the grid, A+) and "−" = 1-0:2.4.0 (fed in, A−); the SunSpec mirror (40088) documents "> 0: 1.4.0, < 0: 2.4.0".
 *   Our [DeviceReading.gridPowerW] = P− − P+, per phase likewise (21.4.0 / 22.4.0 etc.).
 * - Identity: KSEM/RM PnP registers 8192 ff. (manufacturer id 0x5233, product id = hardware type, firmware
 *   0x0103 = 1.3.x, vendor / product name and serial number as strings).
 * - Energies (lifetime, OBIS 1.8.0 / 2.8.0): totals every poll, per phase / reactive / apparent every 12 polls.
 *   No daily counters.
 */
class KostalKsemDriver(private val reader: RegisterReader, private val unitId: Int) : DeviceDriver {
    constructor(client: ModbusTcpClient, unitId: Int) : this(RegisterReader { u, r, c -> client.readHoldingRegisters(u, r, c) }, unitId)

    private val fields = MeterFieldReader(reader, unitId)
    private var static: Map<Int, Int>? = null
    private var polls = 0

    override suspend fun identify(): DeviceInfo {
        val img = MeterRegs(fields.fetch(PNP))
        if (img.u16(PNP_START) != MANUFACTURER_ID) throw IOException("Not a KOSTAL Smart Energy Meter (8192 = ${img.u16(PNP_START)})")
        return DeviceInfo(model = model(img), serial = img.text(8228, 16).orEmpty())
    }

    override suspend fun read(): DeviceReading {
        if (static == null || polls % STATIC_REFRESH_POLLS == 0) {
            fields.retryMissing()
            static = fields.fetch(PNP + ENERGIES)
        }
        polls++
        val live = LIVE.fold(emptyMap<Int, Int>()) { acc, group -> acc + fields.fetch(group) }
        val img = MeterRegs(static.orEmpty() + live)
        return parse(img).copy(details = details(img))
    }

    internal companion object {
        const val STATIC_REFRESH_POLLS = 12
        const val MANUFACTURER_ID = 0x5233
        const val PNP_START = 8192

        // Totals: P+ 0, P− 2, Q+ 4, Q− 6, S+ 16, S− 18, PF 24, f 26.
        // Phase L1 base 40: P+ +0, P− +2, Q+ +4, Q− +6, S+ +16, S− +18, I +20, U +22, PF +24; L2 base 80, L3 120.
        val PHASE_BASE = listOf(40, 80, 120)

        private fun phase(b: Int) = fields(2, b, b + 2, b + 4, b + 6, b + 16, b + 18, b + 20, b + 22, b + 24)

        /** Fetched one group at a time, so block boundaries fall into the map's gaps. */
        val LIVE = listOf(
            fields(2, 0, 2, 4, 6, 16, 18, 24, 26) + phase(40) + phase(80), // 0–105
            phase(120) + fields(2, 146), // 120–147
            fields(4, 512, 516),
        )
        val ENERGIES = fields(4, 520, 524, 544, 548) +
            listOf(592, 672, 752).flatMap { b -> fields(4, b, b + 4, b + 8, b + 12, b + 32, b + 36) }
        val PNP = listOf(
            MeterField(8192, 1), MeterField(8193, 1), MeterField(8194, 1), MeterField(8195, 1),
            MeterField(8196, 16), MeterField(8212, 16), MeterField(8228, 16), MeterField(8244, 1), MeterField(8245, 4),
            MeterField(8249, 1), MeterField(8250, 3),
        )

        private val PRODUCTS = mapOf(0x4842 to "HWv1", 0x4852 to "HWv2", 0x4862 to "HWv3")

        fun model(img: MeterRegs): String = img.text(8212, 16) ?: "KOSTAL Smart Energy Meter"

        /** Direction-split pair (+ drawn, − fed in) at [plus] / plus + 2 → signed value, + = fed in. */
        fun signed(img: MeterRegs, plus: Int, scale: Double = 0.1): Double? {
            val drawn = img.u32(plus) ?: return null
            val fedIn = img.u32(plus + 2) ?: return null
            return (fedIn - drawn) * scale
        }

        private fun scaled(img: MeterRegs, a: Int, scale: Double) = img.u32(a)?.times(scale)

        fun parse(img: MeterRegs): DeviceReading {
            val hz = scaled(img, 26, 0.001) ?: 0.0
            val phases = PHASE_BASE.mapIndexedNotNull { i, b ->
                val v = scaled(img, b + 22, 0.001) ?: return@mapIndexedNotNull null
                PhaseReading("L${i + 1}", v, scaled(img, b + 20, 0.001), hz, signed(img, b)?.roundToInt())
            }
            return DeviceReading(gridPowerW = signed(img, 0)?.roundToInt(), phases = phases)
        }

        private fun kwh(img: MeterRegs, a: Int, unit: String = "kWh") = fmt(img.u64(a)?.times(0.1)?.div(1000), unit, 1)
        private fun power(img: MeterRegs, a: Int, unit: String) = fmt(scaled(img, a, 0.1), unit, 0)
        private fun pf(img: MeterRegs, a: Int) = fmt(img.s32(a)?.times(0.001), "", 3)

        fun details(img: MeterRegs): List<DetailSection> {
            val out = mutableListOf<DetailSection>()
            out += section(
                "Device",
                "Product" to img.text(8212, 16),
                "Vendor" to img.text(8196, 16),
                "Serial number" to img.text(8228, 16),
                "Hardware type" to enum(img.u16(8193), PRODUCTS),
                "Hardware revision" to img.u16(8194)?.toString(),
                "Firmware" to img.u16(8195)?.let { "${it shr 8}.${it and 0xFF}" },
                "Measuring interval" to fmt(img.u16(8244), "ms"),
                "Device time (UTC)" to img.u64(8245)?.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).toString() },
                "Modbus spec version" to img.u16(8249)?.toString(),
                "MAC address" to if (img.has(8250, 3)) {
                    (8250..8252).flatMap { listOf(img.u16(it)!! shr 8, img.u16(it)!! and 0xFF) }.joinToString(":") { "%02X".format(it) }
                } else null,
            )
            out += section(
                "Grid",
                "Active power (+ export)" to fmt(signed(img, 0)?.roundToInt(), "W"),
                "Active power drawn (+)" to power(img, 0, "W"),
                "Active power fed in (−)" to power(img, 2, "W"),
                "Reactive power +" to power(img, 4, "var"),
                "Reactive power −" to power(img, 6, "var"),
                "Apparent power +" to power(img, 16, "VA"),
                "Apparent power −" to power(img, 18, "VA"),
                "Power factor" to pf(img, 24),
                "Frequency" to fmt(scaled(img, 26, 0.001), "Hz", 2),
                "Minimum active power +" to power(img, 146, "W"),
            )
            PHASE_BASE.forEachIndexed { i, b ->
                val e = 592 + 80 * i
                out += section(
                    "L${i + 1}",
                    "Voltage" to fmt(scaled(img, b + 22, 0.001), "V", 1),
                    "Current" to fmt(scaled(img, b + 20, 0.001), "A", 2),
                    "Active power drawn (+)" to power(img, b, "W"),
                    "Active power fed in (−)" to power(img, b + 2, "W"),
                    "Reactive power +" to power(img, b + 4, "var"),
                    "Reactive power −" to power(img, b + 6, "var"),
                    "Apparent power +" to power(img, b + 16, "VA"),
                    "Apparent power −" to power(img, b + 18, "VA"),
                    "Power factor" to pf(img, b + 24),
                    "Energy drawn (+)" to kwh(img, e),
                    "Energy fed in (−)" to kwh(img, e + 4),
                )
            }
            out += section(
                "Energy (lifetime)",
                "Active energy drawn (1.8.0)" to kwh(img, 512),
                "Active energy fed in (2.8.0)" to kwh(img, 516),
                "Reactive energy +" to kwh(img, 520, "kvarh"),
                "Reactive energy −" to kwh(img, 524, "kvarh"),
                "Apparent energy +" to kwh(img, 544, "kVAh"),
                "Apparent energy −" to kwh(img, 548, "kVAh"),
            )
            return out.filter { it.rows.isNotEmpty() }
        }
    }
}
