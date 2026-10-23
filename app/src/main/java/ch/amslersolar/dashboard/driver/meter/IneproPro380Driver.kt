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
 * inepro PRO380-Mod (three-phase MID meter, RS485 Modbus RTU via a Modbus TCP gateway).
 *
 * Spec: "PRO380 Series MID Three phase energy meter – User manual" version 2.18-8 (docs/specs/inepro/), appendix 3
 * "A3.2 Modbus register map" (the new map; the old V1.14 map is not used).
 * - All registers FC 03 (holding). Addresses as printed (hex, 0-based: the write example "01 06 4003 000A" sends
 *   4003h in the frame).
 * - Floats "Float ABCD" (IEEE 754, most significant word first); serial number 4000h is 2 registers "HEX".
 * - Units: power kW / kvar / kVA (converted to W here), energy kWh / kvarh, voltage V, current A, frequency Hz.
 * - Sign: the manual has no sign statement for the power registers. It names energy "Forward" (consumption: "the
 *   LED will flash … the more power is consumed"; day counter = "energy forward") and "Reverse", and its combination
 *   code example shows reverse flow as negative (L3 −12 kWh). Derived: active power > 0 = forward = import, so
 *   gridPowerW = −P (UNVERIFIED in the meter's register description).
 * - Identification: the 4000h…401Fh information block must be readable and the grid frequency (5008h) plausible
 *   (40–70 Hz), else IOException. The "Meter code" (4002h) value for the PRO380 isn't documented, so it is only
 *   shown, not checked.
 * - "Voltage*" (5000h), "Current*" (500Ah) and "Error code*" (4015h) are marked "PRO1 only" and not used.
 * - The resettable day counter (6049h) is reset by hand, not daily, so it goes into the details, not into today.
 * - Line settings (A3.1): 9600 baud, 8 data bits, even parity, 1 stop bit; default address 1 (7.12).
 * - Max registers per request: not documented (UNVERIFIED); requests are ≤ 50 registers.
 */
class IneproPro380Driver(private val reader: RegisterReader, private val unitId: Int) : DeviceDriver {
    constructor(client: ModbusTcpClient, unitId: Int) : this(RegisterReader { u, r, c -> client.readHoldingRegisters(u, r, c) }, unitId)

    private val io = RegFieldReader(reader, unitId)
    private var static: Map<Int, Int>? = null
    private var polls = 0

    override suspend fun identify(): DeviceInfo {
        val info = io.tryRead(INFO, INFO_SIZE) ?: throw IOException("No inepro information block (holding 4000h)")
        val hz = io.tryRead(FREQUENCY, 2)?.let { RegImage(it).f32(FREQUENCY) }
        if (hz == null || hz !in 40.0..70.0) throw IOException("Not an inepro PRO380: 5008h is not a plausible grid frequency")
        static = (static ?: emptyMap()) + info
        return DeviceInfo(model = "inepro PRO380", serial = serial(RegImage(info)).orEmpty())
    }

    override suspend fun read(): DeviceReading {
        if (static == null || polls % STATIC_REFRESH_POLLS == 0) {
            io.forgetMissing()
            static = io.fetch(STATIC_FIELDS, MAX_REGS, 0)
        }
        polls++
        val live = io.fetch(LIVE_FIELDS, MAX_REGS, 0)
        if (live.isEmpty()) throw IOException("inepro PRO380 returned no measurements")
        return parse(RegImage(static.orEmpty() + live))
    }

    internal companion object {
        const val STATIC_REFRESH_POLLS = 12
        const val MAX_REGS = 50
        const val INFO = 0x4000
        const val INFO_SIZE = 0x20
        const val FREQUENCY = 0x5008

        val STATIC_FIELDS = listOf(RegField(INFO, INFO_SIZE), RegField(0x6024, 0x24), RegField(0x6048, 1), RegField(0x6049, 2))
        val LIVE_FIELDS = listOf(RegField(0x5002, 0x30), RegField(0x6000, 0x24))

        fun serial(r: RegImage): String? = r.u32(INFO)?.let { "%08X".format(it) }

        private val PARITY = mapOf(1 to "even", 2 to "none", 3 to "odd")
        private val COMBINATION = mapOf(
            1 to "forward only", 4 to "reverse only", 5 to "forward + reverse", 6 to "reverse − forward",
            9 to "forward − reverse", 10 to "forward − reverse (C-10)", 11 to "forward − reverse (C-11)",
        )

        private fun RegImage.kw(a: Int): Double? = f32(a)?.times(1000)
        private fun RegImage.char(a: Int): String? = u16(a)?.let { listOf(it shr 8, it and 0xFF) }
            ?.filter { it in 0x21..0x7E }?.map { it.toChar() }?.joinToString("")?.takeIf { it.isNotEmpty() }

        fun parse(r: RegImage): DeviceReading {
            val hzValue = r.f32(FREQUENCY)
            val names = listOf("L1", "L2", "L3")
            val phases = (0 until 3).mapNotNull { i -> phase(names[i], r.f32(0x5002 + 2 * i), r.f32(0x500C + 2 * i), hzValue, r.kw(0x5014 + 2 * i)) }
            val totalW = r.kw(0x5012)
            val details = mutableListOf<DetailSection>()
            details += section(
                "Meter",
                "Model" to "PRO380",
                "Serial number" to serial(r),
                "Meter code" to r.u16(0x4002)?.let { "%04X".format(it) },
                "Software version" to fmt(r.f32(0x4007), "", 2),
                "Hardware version" to fmt(r.f32(0x4009), "", 2),
                "Protocol version" to fmt(r.f32(0x4005), "", 2),
                "Meter amps" to fmt(r.s16(0x400B), "A"),
                "CT ratio" to r.u16(0x400C)?.let { "%04X".format(it) },
                "CT mode" to r.s16(0x401F)?.toString(),
                "Combination code" to enum(r.s16(0x400F), COMBINATION),
                "Current direction L1 / L2 / L3" to listOf(0x4012, 0x4013, 0x4014).map { r.char(it) ?: "–" }
                    .takeIf { d -> d.any { it != "–" } }?.joinToString(" / "),
                "Quadrant (total, L1, L2, L3)" to listOf(0x4017, 0x4018, 0x4019, 0x401A).map { r.s16(it) }
                    .takeIf { q -> q.none { it == null } }?.joinToString(", "),
                "Power down counter" to r.s16(0x4016)?.toString(),
                "Active status word" to bits(r.u32(0x401D)),
                "Tariff" to r.s16(0x6048)?.let { "T$it" },
                "S0 output rate" to fmt(r.f32(0x400D), "imp/kWh"),
            )
            details += section(
                "System",
                "Active power (+ import)" to w(totalW),
                "Reactive power" to vars(r.kw(0x501A)),
                "Apparent power" to va(r.kw(0x5022)),
                "Power factor" to pf(r.f32(0x502A)),
                "Frequency" to hz(hzValue),
            )
            for (i in 0 until 3) {
                details += phaseSection(
                    names[i], r.f32(0x5002 + 2 * i), null, r.f32(0x500C + 2 * i), r.kw(0x5014 + 2 * i),
                    r.kw(0x5024 + 2 * i), r.kw(0x501C + 2 * i), r.f32(0x502C + 2 * i),
                    extra = listOf(
                        "Imported (forward)" to kwh(r.f32(0x6012 + 2 * i)),
                        "Exported (reverse)" to kwh(r.f32(0x601E + 2 * i)),
                        "Total" to kwh(r.f32(0x6006 + 2 * i)),
                    ),
                )
            }
            details += section(
                "Energy (lifetime)",
                "Imported (forward)" to kwh(r.f32(0x600C)),
                "Exported (reverse)" to kwh(r.f32(0x6018)),
                "Total (combination code)" to kwh(r.f32(0x6000)),
                "Imported T1 / T2" to pair(kwh(r.f32(0x600E)), kwh(r.f32(0x6010))),
                "Exported T1 / T2" to pair(kwh(r.f32(0x601A)), kwh(r.f32(0x601C))),
                "Reactive forward" to kvarh(r.f32(0x6030)),
                "Reactive reverse" to kvarh(r.f32(0x603C)),
                "Reactive total" to kvarh(r.f32(0x6024)),
                "Resettable day counter" to kwh(r.f32(0x6049)),
            )
            details += section(
                "Modbus RTU",
                "Address" to r.s16(0x4003)?.toString(),
                "Baud rate" to r.s16(0x4004)?.toString(),
                "Parity" to enum(r.s16(0x4011), PARITY),
            )
            return DeviceReading(gridPowerW = exportW(totalW), phases = phases, details = details)
        }

        private fun pair(x: String?, y: String?) = if (x == null && y == null) null else "${x ?: "–"} / ${y ?: "–"}"
    }
}
