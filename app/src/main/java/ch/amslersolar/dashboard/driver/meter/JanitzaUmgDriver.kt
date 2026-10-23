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
import kotlin.math.roundToInt

/**
 * Janitza UMG 604-PRO, UMG 96RM-E and UMG 96RM (with -P / -CBM / -PN extensions) power analysers over Modbus TCP
 * (the 96RM without Ethernet through an RS485 gateway).
 *
 * Specs (docs/specs/janitza/): "UMG 604-PRO Modbus-address and Formulary" (firmware 5.030+, doc 1.033.034.h,
 * 07/2025); "UMG 96RM-E Modbus-address list and Formulary" (firmware 3.01 / hardware 104, 07/2025);
 * "UMG 96RM Modbus-address list and Formulary" (firmware 1.14+).
 * - Function 03 (read holding registers); all three list 03 and 04 as slave functions.
 * - Addresses: the lists' addresses are sent as they are. UNVERIFIED: the documents don't state a +1 offset or
 *   0-based numbering explicitly; Janitza's lists give protocol addresses.
 * - Encoding: the documented addresses deliver "Big-Endian" data (high byte and, for floats, high word first);
 *   Little-Endian would be address + 32768 (not used). Floats are IEEE 754, already scaled by the transformer
 *   ratios.
 * - Shared float block 19000–19121 in all three ("frequently required readings"): U L-N, U L-L, I, P, S, Q,
 *   cos φ, f, rotation field, energies (Wh, varh, VAh), THD — read in one request per poll.
 * - Sign: the formulary defines cos φ as "− for the supply of real power, + for obtaining real power", and the
 *   energies split into "consumed" (19062–19068) and "delivered" (19070–19076). So P > 0 is drawn from the grid
 *   (meter at the grid connection); our [DeviceReading.gridPowerW] is the opposite. UNVERIFIED: the sign of the
 *   power registers themselves isn't written down, it follows from cos φ = P / S.
 * - 19068 is "Real energy L1..L3, consumed, rate 1" in the 604-PRO and 96RM lists but "consumed" in the 96RM-E
 *   list; it is shown as lifetime import. No daily counters.
 * - Model: the 604-PRO has a device name string at 10072 (and serial 10176, uint), the 96RM family a software
 *   release (750), serial (754) and module number (761: 0 none, 1 Profibus, 2 CBM, 3 Ethernet) at addresses where
 *   the 604-PRO keeps FFT floats. A printable device name means 604-PRO, else a valid module number means 96RM.
 *   UNVERIFIED: the SERNR type isn't defined (read as uint32, two registers apart), the module → product name
 *   suffix (-P, -CBM, -E) is inferred from the module names, and a 604-PRO with an empty device name can't be told
 *   from the registers (shown as "UMG" then).
 */
class JanitzaUmgDriver(private val reader: RegisterReader, private val unitId: Int) : DeviceDriver {
    constructor(client: ModbusTcpClient, unitId: Int) : this(RegisterReader { u, r, c -> client.readHoldingRegisters(u, r, c) }, unitId)

    private val fields = MeterFieldReader(reader, unitId)
    private var static: Map<Int, Int>? = null
    private var polls = 0

    override suspend fun identify(): DeviceInfo {
        val live = MeterRegs(fields.fetch(LIVE))
        if (!plausible(live)) throw IOException("No Janitza UMG measurement block at 19000")
        val img = MeterRegs(fetchStatic())
        val id = identity(img)
        return DeviceInfo(model = id.model, serial = id.serial.orEmpty())
    }

    override suspend fun read(): DeviceReading {
        if (static == null || polls % STATIC_REFRESH_POLLS == 0) {
            fields.retryMissing()
            static = fetchStatic()
        }
        polls++
        val live = fields.fetch(LIVE)
        val img = MeterRegs(static.orEmpty() + live)
        if (!plausible(img)) throw IOException("Implausible Janitza UMG measurement block")
        return parse(img).copy(details = details(img))
    }

    private suspend fun fetchStatic(): Map<Int, Int> {
        val umg604 = fields.fetch(STATIC_604)
        if (MeterRegs(umg604).printable(10072, 32) != null) return umg604
        return umg604 + fields.fetch(STATIC_96RM)
    }

    internal data class Identity(val model: String, val serial: String?, val family: String)

    internal companion object {
        const val STATIC_REFRESH_POLLS = 12

        val LIVE = fields(2, *(19000..19120 step 2).toList().toIntArray())
        val STATIC_604 = listOf(MeterField(10072, 32), MeterField(10104, 64), MeterField(10176, 2), MeterField(10178, 2))
        val STATIC_96RM = listOf(MeterField(750, 1), MeterField(754, 2), MeterField(756, 2), MeterField(761, 1))

        private val MODULES = mapOf(0 to "None", 1 to "Profibus", 2 to "CBM", 3 to "Ethernet")
        private val MODULE_SUFFIX = mapOf(1 to "-P", 2 to "-CBM", 3 to "-E")
        private val ROTATION = mapOf(1 to "Right", 0 to "None", -1 to "Left")

        /** The measurement block looks like a Janitza one: frequency 0 or 40–70 Hz, rotation field −1/0/1. */
        fun plausible(img: MeterRegs): Boolean {
            val hz = img.f32(19050) ?: return false
            val rotation = img.f32(19052) ?: return false
            val v = img.f32(19000) ?: return false
            return (hz == 0.0 || hz in 40.0..70.0) && rotation in setOf(-1.0, 0.0, 1.0) && v in 0.0..1.0e6
        }

        fun identity(img: MeterRegs): Identity {
            if (img.printable(10072, 32) != null) return Identity("UMG 604-PRO", img.u32(10176)?.toString(), "604")
            val module = img.u16(761)
            if (module != null && module in MODULES) {
                return Identity("UMG 96RM" + MODULE_SUFFIX[module].orEmpty(), img.u32(754)?.toString(), "96RM")
            }
            return Identity("UMG", null, "unknown")
        }

        private fun w(img: MeterRegs, a: Int) = img.f32(a)?.let { -it.roundToInt() }

        fun parse(img: MeterRegs): DeviceReading {
            val hz = img.f32(19050) ?: 0.0
            val phases = (0 until 3).mapNotNull { i ->
                val v = img.f32(19000 + 2 * i) ?: return@mapNotNull null
                PhaseReading("L${i + 1}", v, img.f32(19012 + 2 * i), hz, w(img, 19020 + 2 * i))
            }
            return DeviceReading(gridPowerW = w(img, 19026), phases = phases)
        }

        private fun kwh(img: MeterRegs, a: Int, unit: String = "kWh") = fmt(img.f32(a)?.div(1000), unit, 1)

        fun details(img: MeterRegs): List<DetailSection> {
            val id = identity(img)
            val out = mutableListOf<DetailSection>()
            out += section(
                "Device",
                "Model" to id.model,
                "Device name" to img.printable(10072, 32),
                "Description" to img.printable(10104, 64),
                "Serial number" to id.serial,
                "Production number" to (if (id.family == "604") img.u32(10178) else img.u32(756))?.toString(),
                "Software release" to if (id.family == "96RM") img.u16(750)?.toString() else null,
                "Module" to if (id.family == "96RM") enum(img.u16(761), MODULES) else null,
            )
            out += section(
                "Grid",
                "Active power (+ export)" to fmt(w(img, 19026), "W"),
                "Active power (meter, + drawn)" to fmt(img.f32(19026), "W", 0),
                "Apparent power" to fmt(img.f32(19034), "VA", 0),
                "Reactive power" to fmt(img.f32(19042), "var", 0),
                "Frequency" to fmt(img.f32(19050), "Hz", 2),
                "Rotation field" to enum(img.f32(19052)?.roundToInt(), ROTATION),
                "Neutral current (vector sum)" to fmt(img.f32(19018), "A", 2),
            )
            for (i in 0 until 3) {
                out += section(
                    "L${i + 1}",
                    "Voltage L-N" to fmt(img.f32(19000 + 2 * i), "V", 1),
                    "Voltage L-L" to fmt(img.f32(19006 + 2 * i), "V", 1),
                    "Current" to fmt(img.f32(19012 + 2 * i), "A", 2),
                    "Active power (meter, + drawn)" to fmt(img.f32(19020 + 2 * i), "W", 0),
                    "Apparent power" to fmt(img.f32(19028 + 2 * i), "VA", 0),
                    "Reactive power" to fmt(img.f32(19036 + 2 * i), "var", 0),
                    "cos φ (− supply, + obtain)" to fmt(img.f32(19044 + 2 * i), "", 3),
                    "THD U" to fmt(img.f32(19110 + 2 * i), "%", 1),
                    "THD I" to fmt(img.f32(19116 + 2 * i), "%", 1),
                    "Energy consumed" to kwh(img, 19062 + 2 * i),
                    "Energy delivered" to kwh(img, 19070 + 2 * i),
                    "Energy (net)" to kwh(img, 19054 + 2 * i),
                )
            }
            out += section(
                "Energy (lifetime)",
                "Import (consumed)" to kwh(img, 19068),
                "Export (delivered)" to kwh(img, 19076),
                "Net active energy" to kwh(img, 19060),
                "Apparent energy" to kwh(img, 19084, "kVAh"),
                "Reactive energy" to kwh(img, 19092, "kvarh"),
                "Reactive energy inductive" to kwh(img, 19100, "kvarh"),
                "Reactive energy capacitive" to kwh(img, 19108, "kvarh"),
            )
            return out.filter { it.rows.isNotEmpty() }
        }
    }
}
