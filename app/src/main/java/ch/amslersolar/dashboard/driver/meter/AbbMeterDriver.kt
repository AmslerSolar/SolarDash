// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.meter

import ch.amslersolar.dashboard.driver.DeviceDriver
import ch.amslersolar.dashboard.driver.Details.bits
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
import ch.amslersolar.dashboard.driver.meter.MeterFmt.va
import ch.amslersolar.dashboard.driver.meter.MeterFmt.vars
import ch.amslersolar.dashboard.driver.meter.MeterFmt.w
import ch.amslersolar.dashboard.driver.modbus.ModbusTcpClient
import ch.amslersolar.dashboard.driver.sunspec.RegisterReader
import ch.amslersolar.dashboard.model.DetailSection
import ch.amslersolar.dashboard.model.DeviceInfo
import ch.amslersolar.dashboard.model.DeviceReading
import java.io.IOException

/**
 * ABB B23 / B24 energy meters, RS485 Modbus RTU via a Modbus TCP gateway.
 *
 * Spec: "B23/B24 User Manual" 2CMC485003M0201 Revision A (2013-06-20), chapter 9 "Communication with Modbus"
 * (docs/specs/abb/).
 * - FC 03 only (the meter supports 3, 6 and 16). Addresses as in the mapping tables: "expressed exactly as it is
 *   sent on the bus" (no 40000 offset, no −1).
 * - Readable range 1000h–8EFFh; 1–125 registers per request; unused registers inside the range answer normally
 *   with "invalid" (unsigned: FFFFh in all registers; signed: the highest value, 7FFFh / 7FFFFFFFh / …), which the
 *   driver reports as missing.
 * - Multi-register values: most significant byte in the high byte of the first register (big-endian, MSW first).
 * - Scaling: voltage 0.1 V, current 0.01 A, power 0.01 W/var/VA (signed 32-bit), frequency 0.01 Hz, PF 0.001,
 *   angles 0.1°, energy 0.01 kWh (64-bit).
 * - Sign: the quadrant illustration (7.1) marks "Import of active power +" and "Export of active power −"; the
 *   meter also warns "Total power is measured as negative" (export). gridPowerW = −active power total (5B14h).
 * - Identification: type designation 8960h (6 registers, ASCII, e.g. "B23 312-100") must start with B23 or B24,
 *   else IOException. Serial number 8900h (unsigned 32-bit).
 * - Single-phase use: the "number of elements" setting (8CE5h, 1–3) selects 1-, 2- or 3-element metering
 *   (7.1.1 single phase, 1-element). Phases whose voltage register is "invalid" are left out; with 1 element only
 *   phases with a non-zero voltage are reported.
 * - RS485 line: Modbus must be selected as protocol on the meter (default may be EQ-Bus); address 1–247, 1200–115200
 *   baud, parity none/odd/even (table 4:4). Factory defaults for address/baud are not given in the manual
 *   (UNVERIFIED; the app's default unit id 1 is an assumption).
 * - No daily counters: [DeviceReading.today] stays empty; the resettable accumulators are only shown in the details.
 */
class AbbMeterDriver(private val reader: RegisterReader, private val unitId: Int) : DeviceDriver {
    constructor(client: ModbusTcpClient, unitId: Int) : this(RegisterReader { u, r, c -> client.readHoldingRegisters(u, r, c) }, unitId)

    private val io = RegFieldReader(reader, unitId)
    private var static: Map<Int, Int>? = null
    private var polls = 0

    override suspend fun identify(): DeviceInfo {
        val type = io.tryRead(TYPE, 6)?.let { RegImage(it).ascii(TYPE, 6) }
            ?: throw IOException("No ABB type designation (holding 8960h)")
        if (!type.startsWith("B23") && !type.startsWith("B24")) throw IOException("Not an ABB B23/B24 meter (type '$type')")
        val serial = io.tryRead(SERIAL, 2)?.let { serial(RegImage(it)) }
        return DeviceInfo(model = "ABB $type", serial = serial.orEmpty())
    }

    override suspend fun read(): DeviceReading {
        if (static == null || polls % STATIC_REFRESH_POLLS == 0) {
            io.forgetMissing()
            static = io.fetch(STATIC_FIELDS, MAX_REGS, MAX_GAP)
        }
        polls++
        val live = io.fetch(LIVE_FIELDS, MAX_REGS, MAX_GAP)
        if (live.isEmpty()) throw IOException("ABB meter returned no measurements")
        return parse(RegImage(static.orEmpty() + live))
    }

    internal companion object {
        const val STATIC_REFRESH_POLLS = 12
        const val MAX_REGS = 125
        const val MAX_GAP = 8 // the whole 1000h–8EFFh range is readable
        const val TYPE = 0x8960
        const val SERIAL = 0x8900
        const val ELEMENTS = 0x8CE5

        val LIVE_FIELDS = listOf(RegField(0x5B00, 0x42), RegField(0x5000, 0x18))
        val STATIC_FIELDS = listOf(
            RegField(SERIAL, 2), RegField(0x8908, 8), RegField(0x8910, 1), RegField(TYPE, 6),
            RegField(0x8A07, 1), RegField(0x8A13, 0x16), RegField(0x8A2F, 1),
            RegField(0x8C04, 2), RegField(0x8C08, 2), RegField(ELEMENTS, 1),
            RegField(0x5018, 12), RegField(0x5170, 16), RegField(0x5190, 16), RegField(0x5460, 36), RegField(0x552C, 16),
        )

        fun serial(r: RegImage): String? = r.u32(SERIAL)?.takeIf { it != 0xFFFFFFFFL }?.toString()

        // "Invalid" markers (9.2) → null.
        private fun RegImage.uns16(a: Int) = u16(a)?.takeIf { it != 0xFFFF }
        private fun RegImage.sig16(a: Int, div: Double) = s16(a)?.takeIf { it != 0x7FFF }?.div(div)
        private fun RegImage.uns32(a: Int, div: Double) = u32(a)?.takeIf { it != 0xFFFFFFFFL }?.div(div)
        private fun RegImage.sig32(a: Int, div: Double) = s32(a)?.takeIf { it != Int.MAX_VALUE }?.div(div)
        private fun RegImage.uns64(a: Int) = u64(a)?.takeIf { it != -1L }?.div(100.0)
        private fun RegImage.sig64(a: Int) = u64(a)?.takeIf { it != Long.MAX_VALUE }?.div(100.0)

        fun parse(r: RegImage): DeviceReading {
            val hzValue = r.uns16(0x5B2C)?.div(100.0)
            val elements = r.uns16(ELEMENTS)
            val names = listOf("L1", "L2", "L3")
            fun volts(i: Int) = r.uns32(0x5B00 + 2 * i, 10.0)
            fun amps(i: Int) = r.uns32(0x5B0C + 2 * i, 100.0)
            fun watts(i: Int) = r.sig32(0x5B16 + 2 * i, 100.0)
            val shown = (0 until 3).filter { i -> volts(i) != null && (elements != 1 || (volts(i) ?: 0.0) > 0.0) }
            val phases = shown.mapNotNull { i -> phase(names[i], volts(i), amps(i), hzValue, watts(i)) }
            val totalW = r.sig32(0x5B14, 100.0)
            val details = mutableListOf<DetailSection>()
            details += section(
                "Meter",
                "Type designation" to r.ascii(TYPE, 6),
                "Serial number" to serial(r),
                "Firmware" to r.ascii(0x8908, 8),
                "Modbus mapping version" to r.uns16(0x8910)?.let { "${it shr 8}.${it and 0xFF}" },
                "Number of elements" to elements?.toString(),
                "CT ratio" to r.u32(0x8C04)?.takeIf { it != 0xFFFFFFFFL }?.let { num ->
                    r.u32(0x8C08)?.takeIf { it != 0xFFFFFFFFL }?.let { "$num/$it" } ?: "$num"
                },
                "Current tariff" to r.uns16(0x8A07)?.toString(),
                "Power fail counter" to r.uns16(0x8A2F)?.toString(),
                "Error flags" to flags(r, 0x8A13),
                "Information flags" to flags(r, 0x8A19),
                "Warning flags" to flags(r, 0x8A1F),
                "Alarm flags" to flags(r, 0x8A25),
            )
            details += section(
                "System",
                "Active power (+ import)" to w(totalW),
                "Reactive power" to vars(r.sig32(0x5B1C, 100.0)),
                "Apparent power" to va(r.sig32(0x5B24, 100.0)),
                "Power factor" to pf(r.sig16(0x5B3A, 1000.0)),
                "Phase angle power" to fmt(r.sig16(0x5B2D, 10.0), "°", 1),
                "Quadrant" to r.uns16(0x5B3E)?.toString(),
                "Frequency" to hz(hzValue),
                "Neutral current" to a(r.uns32(0x5B12, 100.0)),
            )
            val vll = listOf(0x5B06, 0x5B08, 0x5B0A) // L1-L2, L3-L2, L1-L3
            val vllNames = listOf("L1-L2", "L3-L2", "L1-L3")
            for (i in shown) {
                details += phaseSection(
                    names[i], volts(i), null, amps(i), watts(i), r.sig32(0x5B26 + 2 * i, 100.0),
                    r.sig32(0x5B1E + 2 * i, 100.0), r.sig16(0x5B3B + i, 1000.0),
                    extra = listOf(
                        "Voltage ${vllNames[i]}" to MeterFmt.v(r.uns32(vll[i], 10.0)),
                        "Phase angle voltage" to fmt(r.sig16(0x5B31 + i, 10.0), "°", 1),
                        "Phase angle current" to fmt(r.sig16(0x5B37 + i, 10.0), "°", 1),
                        "Phase angle power" to fmt(r.sig16(0x5B2E + i, 10.0), "°", 1),
                        "Quadrant" to r.uns16(0x5B3F + i)?.toString(),
                        "Imported" to kwh(r.uns64(0x5460 + 4 * i)),
                        "Exported" to kwh(r.uns64(0x546C + 4 * i)),
                    ),
                )
            }
            details += section(
                "Energy (lifetime)",
                "Imported" to kwh(r.uns64(0x5000)),
                "Exported" to kwh(r.uns64(0x5004)),
                "Net" to kwh(r.sig64(0x5008)),
                "Reactive imported" to kvarh(r.uns64(0x500C)),
                "Reactive exported" to kvarh(r.uns64(0x5010)),
                "Apparent imported" to fmt(r.uns64(0x5018), "kVAh", 2),
                "Apparent exported" to fmt(r.uns64(0x501C), "kVAh", 2),
                *(0 until 4).map { t -> "Imported tariff ${t + 1}" to kwh(r.uns64(0x5170 + 4 * t)) }.toTypedArray(),
                *(0 until 4).map { t -> "Exported tariff ${t + 1}" to kwh(r.uns64(0x5190 + 4 * t)) }.toTypedArray(),
                "Resettable imported" to kwh(r.uns64(0x552C)),
                "Resettable exported" to kwh(r.uns64(0x5530)),
            )
            return DeviceReading(gridPowerW = exportW(totalW), phases = phases, details = details)
        }

        /** 64 flags in 4 registers; "none" when no flag is set. */
        private fun flags(r: RegImage, a: Int): String? = r.u64(a)?.let { if (it == 0L) "none" else bits(it, 16) }
    }
}
