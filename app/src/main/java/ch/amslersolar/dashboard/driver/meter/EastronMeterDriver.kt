// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.meter

import ch.amslersolar.dashboard.driver.DeviceDriver
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
import ch.amslersolar.dashboard.model.DeviceReading
import java.io.IOException

/**
 * Eastron SDM630 (three-phase) and SDM120 (single-phase) Modbus meters, RS485 via a Modbus TCP gateway.
 *
 * Specs (docs/specs/eastron/): "Eastron SDM630Modbus Smart Meter Modbus Protocol Implementation V1.8" and
 * "Eastron SDM120Modbus Smart Meter Modbus Protocol Implementation V2.4".
 * - Measurements: FC 04 input registers ("3X"); settings, serial number: FC 03 holding registers ("4X"). Addresses
 *   are the "Modbus Protocol Start Address Hex" columns (0-based, register 30001 = 0000h).
 * - Every value is an IEEE 754 float in two registers, most significant register first by default; the "Register
 *   Order" setting can reverse that (its holding address isn't in either table). The order is therefore detected:
 *   the supply frequency (0046h) must read 40–70 Hz in one of the two orders (detection heuristic, not in the spec).
 * - Requests: start address and count must be even; at most 40 values (80 registers) per request (SDM630 1.1).
 * - Sign: neither document states the sign of the active power registers. SDM630 note 1: the power factor sign
 *   "indicate[s] the direction of the current. Positive refers to forward current, negative refers to reverse
 *   current" and note 2: power demand = "import – export". Derived: W > 0 = import, so gridPowerW = −W
 *   (UNVERIFIED for the SDM120, which documents no sign at all).
 * - Identification: the serial number (FC 03, FCh 00h, unsigned int32; both documents) must be readable and the
 *   frequency plausible, else IOException. Model: SDM120 documents "Meter code = 00 20" at FC02h; the SDM630
 *   documents no meter code but documents the system type at 000Ah (holding, 1 = 1p2w, 2 = 3p3w, 3 = 3p4w). Code
 *   0020h → SDM120; otherwise a readable system type → SDM630. The serial's word order under "Register Order" is
 *   UNVERIFIED (assumed to follow the float order).
 * - SDM630 3p3w: line-to-neutral values are not measured ("X" → 0), so no per-phase readings are reported.
 * - Defaults (holding registers, both documents): address 1; SDM630 9600 baud, SDM120 2400 baud; 8N1.
 * - The "SDM120 holding registers F900h/F910h/F920h" are 2-byte values; read as one register pair (UNVERIFIED that
 *   the meter accepts the pair; failures are ignored).
 * - No daily energy counters: [DeviceReading.today] stays empty.
 */
class EastronMeterDriver(
    private val input: RegisterReader,
    private val holding: RegisterReader,
    private val unitId: Int,
) : DeviceDriver {
    constructor(client: ModbusTcpClient, unitId: Int) : this(
        RegisterReader { u, r, c -> client.readInputRegisters(u, r, c) },
        RegisterReader { u, r, c -> client.readHoldingRegisters(u, r, c) },
        unitId,
    )

    internal enum class Model(val title: String) { SDM630("SDM630"), SDM120("SDM120") }

    private val inputs = RegFieldReader(input, unitId)
    private val holdings = RegFieldReader(holding, unitId)
    private var model: Model? = null
    private var swapped = false
    private var static: Map<Int, Int> = emptyMap()
    private var settings: Map<Int, Int> = emptyMap()
    private var polls = 0

    override suspend fun identify(): DeviceInfo {
        val m = detect()
        val serial = RegImage(settings, swapped).u32(SERIAL)
        return DeviceInfo(model = "Eastron ${m.title}", serial = serial?.toString().orEmpty())
    }

    override suspend fun read(): DeviceReading {
        val m = model ?: detect()
        if (polls % STATIC_REFRESH_POLLS == 0) {
            inputs.forgetMissing()
            holdings.forgetMissing()
            static = inputs.fetch(staticInputs(m), MAX_REGS, 2)
            settings = settings + holdings.fetch(settingFields(m), MAX_REGS, 0)
        }
        polls++
        val live = HashMap<Int, Int>()
        for ((fields, gap) in liveFields(m)) live += inputs.fetch(fields, MAX_REGS, gap)
        if (live.isEmpty()) throw IOException("Eastron meter returned no measurements")
        orderFromFrequency(live)?.let { swapped = it }
        return parse(m, RegImage(static + live, swapped), RegImage(settings, swapped), swapped)
    }

    private suspend fun detect(): Model {
        val hz = try {
            input.read(unitId, FREQUENCY, 2)
        } catch (e: ModbusException) {
            throw IOException("No Eastron frequency register (input 0046h, Modbus exception ${e.code})")
        }
        swapped = orderFromFrequency(mapOf(FREQUENCY to hz.u16(FREQUENCY), FREQUENCY + 1 to hz.u16(FREQUENCY + 1)))
            ?: throw IOException("Not an Eastron meter: input 0046h is not a plausible frequency")
        val ident = holdings.tryRead(SERIAL, 4) ?: holdings.tryRead(SERIAL, 2)
            ?: throw IOException("No Eastron serial number (holding FC00h)")
        val code = ident[METER_CODE]
        val systemType = if (code == SDM120_CODE) null else holdings.tryRead(SYSTEM_TYPE, 2)
        val sysValue = systemType?.let { RegImage(it, swapped).f32(SYSTEM_TYPE) }
        val m = when {
            code == SDM120_CODE -> Model.SDM120
            sysValue != null && sysValue in listOf(1.0, 2.0, 3.0) -> Model.SDM630
            else -> throw IOException("Unknown Eastron meter (meter code ${code?.let { "%04X".format(it) } ?: "none"})")
        }
        settings = ident + systemType.orEmpty()
        model = m
        return m
    }

    internal companion object {
        const val STATIC_REFRESH_POLLS = 12
        const val MAX_REGS = 80
        const val FREQUENCY = 0x0046
        const val SERIAL = 0xFC00
        const val METER_CODE = 0xFC02
        const val SOFTWARE = 0xFC03
        const val SYSTEM_TYPE = 0x000A
        const val SDM120_CODE = 0x0020

        /** true = word-swapped, false = normal, null = can't tell (e.g. no voltage). */
        fun orderFromFrequency(regs: Map<Int, Int>): Boolean? {
            val normal = RegImage(regs, false).f32(FREQUENCY)
            val swapped = RegImage(regs, true).f32(FREQUENCY)
            return when {
                normal != null && normal in 40.0..70.0 -> false
                swapped != null && swapped in 40.0..70.0 -> true
                else -> null
            }
        }

        private val SDM630_MAIN = regFields(2, *((0x00..0x2A step 2) + listOf(0x2E, 0x30, 0x34, 0x38, 0x3C, 0x3E, 0x42) + (0x46..0x4E step 2)).toIntArray())
        private val SDM630_LL = regFields(2, 0xC8, 0xCA, 0xCC, 0xCE, 0xE0)
        private val SDM120_MAIN = regFields(2, 0x00, 0x06, 0x0C, 0x12, 0x18, 0x1E) + regFields(2, *(0x46..0x4E step 2).toList().toIntArray())

        fun liveFields(m: Model): List<Pair<List<RegField>, Int>> = when (m) {
            Model.SDM630 -> listOf(SDM630_MAIN to 2, SDM630_LL to 16)
            Model.SDM120 -> listOf(SDM120_MAIN to 4)
        }

        fun staticInputs(m: Model): List<RegField> = when (m) {
            Model.SDM630 -> regFields(2, 0x50, 0x52, 0x54, 0x56, 0x64, 0x66, 0x68, 0x6A) +
                regFields(2, *(0xEA..0xF4 step 2).toList().toIntArray()) + regFields(2, 0xF8, 0xFA) +
                regFields(2, *(0x102..0x10C step 2).toList().toIntArray()) +
                regFields(2, *(0x156..0x17C step 2).toList().toIntArray())
            Model.SDM120 -> regFields(2, *(0x54..0x5E step 2).toList().toIntArray()) + regFields(2, 0x102, 0x108, 0x156, 0x158)
        }

        fun settingFields(m: Model): List<RegField> = when (m) {
            Model.SDM630 -> regFields(2, 0x02, 0x0A, 0x0C, 0x12, 0x14, 0x16, 0x1C, 0x56, SERIAL)
            Model.SDM120 -> regFields(2, 0x0C, 0x12, 0x14, 0x1C, 0x56, 0xF900, 0xF910, 0xF920) + RegField(SERIAL, 4)
        }

        private val PARITY = mapOf(0 to "1 stop bit, no parity", 1 to "1 stop bit, even parity", 2 to "1 stop bit, odd parity", 3 to "2 stop bits, no parity")
        private val BAUD_630 = mapOf(0 to "2400", 1 to "4800", 2 to "9600", 3 to "19200", 4 to "38400")
        private val BAUD_120 = mapOf(0 to "2400", 1 to "4800", 2 to "9600", 5 to "1200")
        private val SYSTEM = mapOf(1 to "1 phase 2 wire", 2 to "3 phase 3 wire", 3 to "3 phase 4 wire")
        private val MEASUREMENT_MODE = mapOf(1 to "total = import", 2 to "total = import + export", 3 to "total = import − export")
        private val PULSE_ENERGY = mapOf(
            1 to "import active", 2 to "total active", 4 to "export active", 5 to "import reactive",
            6 to "total reactive", 8 to "export reactive",
        )

        private fun RegImage.int(a: Int): Int? = f32(a)?.let { Math.round(it).toInt() }

        fun parse(m: Model, r: RegImage, h: RegImage, swapped: Boolean = false): DeviceReading {
            val hzValue = r.f32(FREQUENCY)
            val system = if (m == Model.SDM120) 1 else h.int(SYSTEM_TYPE)
            val names = listOf("L1", "L2", "L3")
            val count = when (system) { 1 -> 1; 2 -> 0; else -> 3 }
            val phases = (0 until count).mapNotNull { i ->
                phase(names[i], r.f32(2 * i), r.f32(0x06 + 2 * i), hzValue, r.f32(0x0C + 2 * i))
            }
            val totalW = if (m == Model.SDM120) r.f32(0x0C) else r.f32(0x34)
            val details = mutableListOf<DetailSection>()
            details += section(
                "Meter",
                "Model" to m.title,
                "Serial number" to h.u32(SERIAL)?.toString(),
                "Meter code" to h.u16(METER_CODE)?.let { "%04X".format(it) },
                "Software version" to h.u16(SOFTWARE)?.let { "%04X".format(it) },
                "System type" to if (m == Model.SDM630) enum(system, SYSTEM) else "1 phase 2 wire",
                "Register order" to if (swapped) "reversed (least significant first)" else "normal (most significant first)",
                "Measurement mode" to if (m == Model.SDM120) enum(h.u16(0xF920), MEASUREMENT_MODE) else null,
                "Demand period" to if (m == Model.SDM630) fmt(h.int(0x02), "min") else null,
                "Pulse 1 energy" to enum(h.int(0x56), PULSE_ENERGY),
            )
            if (m == Model.SDM630) {
                details += section(
                    "System",
                    "Active power (+ import)" to w(totalW),
                    "Apparent power" to va(r.f32(0x38)),
                    "Reactive power" to vars(r.f32(0x3C)),
                    "Power factor" to pf(r.f32(0x3E)),
                    "Phase angle" to fmt(r.f32(0x42), "°", 1),
                    "Frequency" to hz(hzValue),
                    "Average voltage L-N" to v(r.f32(0x2A)),
                    "Average voltage L-L" to v(r.f32(0xCE)),
                    "Average current" to a(r.f32(0x2E)),
                    "Sum of currents" to a(r.f32(0x30)),
                    "Neutral current" to a(r.f32(0xE0)),
                    "Power demand" to w(r.f32(0x54)),
                    "Maximum power demand" to w(r.f32(0x56)),
                    "VA demand" to va(r.f32(0x64)),
                    "Maximum VA demand" to va(r.f32(0x66)),
                    "Average voltage THD" to fmt(r.f32(0xF8), "%", 1),
                    "Average current THD" to fmt(r.f32(0xFA), "%", 1),
                )
                val llAddr = listOf(0xC8, 0xCA, 0xCC)
                for (i in 0 until 3) {
                    if (system == 1 && i > 0) break
                    details += phaseSection(
                        names[i], r.f32(2 * i), if (system == 1) null else r.f32(llAddr[i]), r.f32(0x06 + 2 * i),
                        r.f32(0x0C + 2 * i), r.f32(0x12 + 2 * i), r.f32(0x18 + 2 * i), r.f32(0x1E + 2 * i),
                        extra = listOf(
                            "Phase angle" to fmt(r.f32(0x24 + 2 * i), "°", 1),
                            "Voltage THD" to fmt(r.f32(0xEA + 2 * i), "%", 1),
                            "Current THD" to fmt(r.f32(0xF0 + 2 * i), "%", 1),
                            "Current demand" to a(r.f32(0x102 + 2 * i)),
                            "Maximum current demand" to a(r.f32(0x108 + 2 * i)),
                            "Imported" to kwh(r.f32(0x15A + 2 * i)),
                            "Exported" to kwh(r.f32(0x160 + 2 * i)),
                        ),
                    )
                }
            } else {
                details += phaseSection(
                    "L1", r.f32(0x00), null, r.f32(0x06), r.f32(0x0C), r.f32(0x12), r.f32(0x18), r.f32(0x1E),
                    extra = listOf(
                        "Frequency" to hz(hzValue),
                        "Power demand" to w(r.f32(0x54)),
                        "Maximum power demand" to w(r.f32(0x56)),
                        "Import power demand" to w(r.f32(0x58)),
                        "Export power demand" to w(r.f32(0x5C)),
                        "Current demand" to a(r.f32(0x102)),
                        "Maximum current demand" to a(r.f32(0x108)),
                    ),
                )
            }
            details += section(
                "Energy (lifetime)",
                "Imported" to kwh(r.f32(0x48)),
                "Exported" to kwh(r.f32(0x4A)),
                "Total (import + export)" to kwh(r.f32(0x156)),
                "Reactive imported" to kvarh(r.f32(0x4C)),
                "Reactive exported" to kvarh(r.f32(0x4E)),
                "Apparent" to if (m == Model.SDM630) fmt(r.f32(0x50), "kVAh", 2) else null,
                "Charge" to if (m == Model.SDM630) fmt(r.f32(0x52), "Ah", 1) else null,
            )
            details += section(
                "Modbus RTU",
                "Address" to h.int(0x14)?.toString(),
                "Baud rate" to enum(h.int(0x1C), if (m == Model.SDM630) BAUD_630 else BAUD_120),
                "Parity / stop bits" to enum(h.int(0x12), PARITY),
            )
            return DeviceReading(gridPowerW = exportW(totalW), phases = phases, details = details)
        }
    }
}
