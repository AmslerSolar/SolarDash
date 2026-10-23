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
import ch.amslersolar.dashboard.driver.modbus.RegisterBlock
import ch.amslersolar.dashboard.driver.sunspec.RegisterReader
import ch.amslersolar.dashboard.model.DetailSection
import ch.amslersolar.dashboard.model.DeviceInfo
import ch.amslersolar.dashboard.model.DeviceReading
import ch.amslersolar.dashboard.model.EnergyTotals
import java.io.IOException

/**
 * STIEBEL ELTRON heat pumps with heat pump manager WPM 3, WPM 3i or WPMsystem, read through the Internet Service
 * Gateway (ISG web / ISG plus / ISG Connect) with the "Modbus TCP/IP" software extension. READ ONLY: this driver
 * never writes a register.
 *
 * Spec: "ISG Modbus TCP Software Documentation" (docs/specs/stiebel-eltron/), English part, chapters 4–7 and 10.2:
 * - Function code 04 (read input registers) for blocks 1 (system values 501 ff.), 3 (system status 2501 ff.),
 *   4 (energy data 3501 ff.) and 6 (energy management information 5001 ff.); function code 03 for block 2
 *   (system parameters 1501 ff.), of which only the operating mode 1501 is read.
 * - Addresses: "ISG addresses are 1 based", and the doc's own example: "To read register 30501, address 501 is
 *   brought up with function code 04". 30501 in Modicon notation is the 501st input register, i.e. protocol (PDU)
 *   address 500. So documented address N is sent as N − [addressOffset] (default 1). Chapter 5.1 adds "Depending on
 *   the configuration, allow for an offset of 1", hence the constructor parameter.
 * - Data types: 2 = signed, × 0.1; 6 = unsigned, × 1; 7 = signed, × 0.01; 8 = 0..255, × 1. "The substitute value
 *   32768 (0x8000H) is issued for unavailable objects": such registers are reported as not available (null).
 * - Energy: day counters are whole kWh; totals are split into a kWh part (0..999) and an MWh part.
 * - Identification: register 5002 "SOFTWARE – controller identification": 390 = WPM 3, 391 = WPM 3i,
 *   449 = WPMsystem (WPM 4). Other documented codes (LWZ integral units, WPM I/2/X …) use other register maps and
 *   are rejected. If the ISG doesn't answer 5002, a plausibility check over block 1 / block 3 decides (outdoor
 *   temperature within −60..80 °C or 0x8000, operating status using only bits B0–B11, fatal error 0/1, bus status
 *   −4..0, at least one plausible temperature).
 *
 * Mapping to [DeviceReading]:
 * - [DeviceReading.loadPowerW]: null. The ISG reports no instantaneous electrical power (3680 ff. "INVERTER POWER
 *   IWS" only exists for WPMsystem with IWS inverter units and is not used).
 * - [DeviceReading.temperatureC]: outdoor temperature (507).
 * - [DeviceReading.status]: from the bit-coded operating status 2501 plus 2504 (fatal error) and 2506 (defrost):
 *   Fault > Defrost > Hot water > Cooling > Heating > Standby / Idle.
 * - today.loadKwh: electrical energy of the compressors today, VD HEATING DAY (3511) + VD DHW DAY (3514). The
 *   booster heater (NHZ) has no electrical day counter and is not included.
 *
 * Polling: a heat pump changes slowly, so the full register set (≈ 75 registers in 5 requests) is read every
 * [REFRESH_POLLS] polls and the cached reading is returned in between. Identification and ISG version every
 * [STATIC_REFRESH_POLLS] polls.
 *
 * UNVERIFIED: the default TCP port 502 and unit id 1 (the spec names neither); whether a WPM 3i answers the heat
 * pump 1 registers 542–548 with 0x8000 or a Modbus exception (both are handled); the meaning of 2501 B8 "COOLING
 * MODE" as active cooling (it is only reported as Cooling when the compressor bit B6 is set as well).
 */
class StiebelEltronIsgDriver(
    private val input: RegisterReader,
    private val holding: RegisterReader,
    private val unitId: Int,
    /** Subtracted from the documented (1-based) address to get the protocol address; see the class doc. */
    private val addressOffset: Int = 1,
) : DeviceDriver {
    constructor(client: ModbusTcpClient, unitId: Int) : this(
        RegisterReader { u, r, c -> client.readInputRegisters(u, r, c) },
        RegisterReader { u, r, c -> client.readHoldingRegisters(u, r, c) },
        unitId,
    )

    private val split = mutableSetOf<Int>() // blocks (documented start address) the ISG rejected as a whole
    private val missing = mutableSetOf<Int>() // single registers it rejected
    private var static: Map<Int, Int>? = null
    private var cached: DeviceReading? = null
    private var polls = 0

    override suspend fun identify(): DeviceInfo {
        val img = Image(fetchStatic() + fetch(input, IDENT_BLOCKS))
        if (img.controller == null && !img.plausible()) {
            throw IOException("No STIEBEL ELTRON ISG heat pump (WPM 3 / WPM 3i / WPMsystem) found at unit id $unitId")
        }
        return DeviceInfo(model = "STIEBEL ELTRON " + (img.controllerName ?: "WPM") + " (ISG)", serial = "")
    }

    override suspend fun read(): DeviceReading {
        cached?.let { if (polls % REFRESH_POLLS != 0) { polls++; return it } }
        if (static == null || polls % STATIC_REFRESH_POLLS == 0) {
            split.clear()
            missing.clear()
            static = fetchStatic()
        }
        val img = Image(static.orEmpty() + fetch(input, LIVE_BLOCKS) + fetch(holding, HOLDING_BLOCKS))
        if (img.controller == null && !img.plausible()) throw IOException("Unexpected register values: not a STIEBEL ELTRON ISG")
        val reading = parse(img).copy(details = details(img))
        cached = reading
        polls++
        return reading
    }

    /** Controller identification and ISG version; rejects controllers whose register map this driver doesn't know. */
    private suspend fun fetchStatic(): Map<Int, Int> {
        val regs = fetch(input, STATIC_BLOCKS)
        val code = regs[5002]
        if (code != null && code != NA && code !in CONTROLLERS) {
            val name = OTHER_CONTROLLERS[code]
            throw IOException("STIEBEL ELTRON controller ${name ?: "code $code"} is not supported (only WPM 3, WPM 3i, WPMsystem)")
        }
        return regs
    }

    /** Reads blocks (documented start address to count); a block the ISG rejects is read register by register. */
    private suspend fun fetch(reader: RegisterReader, blocks: List<Pair<Int, Int>>): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        for ((start, count) in blocks) {
            if (count > 1 && start !in split) {
                try {
                    out.putAll(reader.read(unitId, start - addressOffset, count).values(start))
                    continue
                } catch (_: ModbusException) {
                    split += start
                }
            }
            for (a in start until start + count) {
                if (a in missing) continue
                try {
                    out.putAll(reader.read(unitId, a - addressOffset, 1).values(a))
                } catch (_: ModbusException) {
                    missing += a
                }
            }
        }
        return out
    }

    /** Values keyed by documented address. */
    private fun RegisterBlock.values(docStart: Int): Map<Int, Int> = (0 until count).associate { docStart + it to u16(start + it) }

    private fun parse(img: Image): DeviceReading = DeviceReading(
        loadPowerW = null,
        temperatureC = img.temp(507),
        status = img.status(),
        today = EnergyTotals(loadKwh = img.sumOrNull(img.u(3511), img.u(3514))?.toDouble()),
    )

    private fun details(img: Image): List<DetailSection> {
        val status = img.u(2501)
        val heatDay = img.sumOrNull(img.u(3501), img.u(3504))
        val elecDay = img.sumOrNull(img.u(3511), img.u(3514))
        val heatTotal = img.sumOrNull(img.total(3502), img.total(3505))
        val elecTotal = img.sumOrNull(img.total(3512), img.total(3515))
        return listOf(
            section(
                "Heat pump",
                "Controller" to img.controllerName,
                "Operating mode" to enum(img.u(1501), OPERATING_MODES),
                "Operating status" to status?.let { s -> bits(s.toLong(), 4) + STATUS_BITS.filter { s and (1 shl it.key) != 0 }.values.joinToString(", ", " ") },
                "Compressor" to status?.let { if (it and (1 shl 6) != 0) "On" else "Off" },
                "Defrost" to enum(img.u(2506), mapOf(0 to "Off", 1 to "Initiated")),
                "Booster heater" to status?.let { if (it and (1 shl 3) != 0) "On" else "Off" },
                "SG Ready operating state" to enum(img.u(5001), mapOf(1 to "1 (blocked)", 2 to "2 (normal)", 3 to "3 (increased)", 4 to "4 (maximum)")),
                "Fault" to enum(img.u(2504), mapOf(0 to "No fault", 1 to "Fault")),
                "Bus status" to enum(img.s(2505), BUS_STATUS),
            ),
            section(
                "Temperatures",
                "Outdoor" to fmt(img.temp(507), "°C", 1),
                "Flow" to fmt(img.temp(515) ?: img.temp(609), "°C", 1),
                "Flow heat pump" to fmt(img.temp(513), "°C", 1),
                "Flow booster" to fmt(img.temp(514), "°C", 1),
                "Return" to fmt(img.temp(516), "°C", 1),
                "Hot water" to fmt(img.temp(522), "°C", 1),
                "Hot water set" to fmt(img.temp(523), "°C", 1),
                "Buffer" to fmt(img.temp(518), "°C", 1),
                "Buffer set" to fmt(img.temp(519), "°C", 1),
                "Heating circuit 1" to fmt(img.temp(508), "°C", 1),
                "Heating circuit 2" to fmt(img.temp(511), "°C", 1),
                "Source" to fmt(img.temp(536), "°C", 1),
                "Hot gas" to fmt(img.temp(544) ?: img.temp(539), "°C", 1),
                "Room (FE7)" to fmt(img.temp(501), "°C", 1),
                "Heating pressure" to fmt(img.hundredths(520), "bar", 2),
                "Flow rate" to fmt(img.hundredths(521), "l/min", 2),
            ),
            section(
                "Energy",
                "Electrical today" to fmt(elecDay, "kWh"),
                "Heat today" to fmt(heatDay, "kWh"),
                "COP today (calculated)" to cop(heatDay, elecDay),
                "Electrical heating total" to fmt(img.total(3512), "kWh"),
                "Electrical hot water total" to fmt(img.total(3515), "kWh"),
                "Heat heating total" to fmt(img.total(3502), "kWh"),
                "Heat hot water total" to fmt(img.total(3505), "kWh"),
                "Booster heat heating total" to fmt(img.total(3507), "kWh"),
                "Booster heat hot water total" to fmt(img.total(3509), "kWh"),
                "COP total (calculated)" to cop(heatTotal, elecTotal),
            ),
            section(
                "Device",
                "ISG version" to img.isgVersion(),
                "Controller code" to img.u(5002)?.toString(),
            ),
        ).filter { it.rows.isNotEmpty() }
    }

    private fun cop(heat: Long?, elec: Long?): String? =
        if (heat == null || elec == null || elec <= 0) null else fmt(heat.toDouble() / elec, decimals = 2)

    /** Register values keyed by documented address. */
    internal class Image(private val regs: Map<Int, Int>) {
        /** Raw register, null when absent or 0x8000 ("unavailable object"). */
        fun u(a: Int): Int? = regs[a]?.takeIf { it != NA }
        fun s(a: Int): Int? = u(a)?.toShort()?.toInt()
        fun temp(a: Int): Double? = s(a)?.let { it / 10.0 } // data type 2
        fun hundredths(a: Int): Double? = s(a)?.let { it / 100.0 } // data type 7

        /** kWh part (0..999) at [a] plus MWh part at a + 1, in kWh. */
        fun total(a: Int): Long? {
            val kwh = u(a) ?: return null
            val mwh = u(a + 1) ?: return null
            return mwh * 1000L + kwh
        }

        fun sumOrNull(vararg parts: Number?): Long? = if (parts.all { it == null }) null else parts.sumOf { it?.toLong() ?: 0L }

        val controller: Int? get() = u(5002)?.takeIf { it in CONTROLLERS }
        val controllerName: String? get() = controller?.let { CONTROLLERS[it] }

        fun isgVersion(): String? {
            val major = u(2571) ?: return null
            val minor = u(2572) ?: return null
            val rev = u(2573)
            val ext = u(2570)
            return listOfNotNull("$major.$minor" + (rev?.let { ".$it" } ?: ""), ext?.let { "(extension $it)" }).joinToString(" ")
        }

        fun plausible(): Boolean {
            val outdoor = regs[507] ?: return false
            if (outdoor != NA && outdoor.toShort() !in -600..800) return false
            val status = regs[2501] ?: return false
            if (status != NA && status >= 0x1000) return false
            val fault = regs[2504]
            if (fault != null && fault != NA && fault !in 0..1) return false
            val bus = regs[2505]
            if (bus != null && bus != NA && bus.toShort() !in -4..0) return false
            return listOf(507, 515, 516, 522, 536).any { a -> temp(a)?.let { it in -60.0..120.0 } == true }
        }

        fun status(): String? {
            if (u(2504) == 1) return "Fault"
            val bits = u(2501)
            val defrost = u(2506) == 1 || (bits != null && bits and (1 shl 9) != 0)
            if (defrost) return "Defrost"
            if (bits == null) return if (u(1501) == 1) "Standby" else null
            val compressor = bits and (1 shl 6) != 0
            return when {
                bits and (1 shl 5) != 0 -> "Hot water"
                bits and (1 shl 8) != 0 && compressor -> "Cooling"
                bits and (1 shl 4) != 0 -> if (bits and (1 shl 3) != 0 && !compressor) "Heating (booster)" else "Heating"
                u(1501) == 1 -> "Standby"
                else -> "Idle"
            }
        }
    }

    internal companion object {
        const val REFRESH_POLLS = 3 // full read every 15 s at a 5 s poll interval
        const val STATIC_REFRESH_POLLS = 12
        const val NA = 0x8000

        val CONTROLLERS = mapOf(390 to "WPM 3", 391 to "WPM 3i", 449 to "WPMsystem")
        val OTHER_CONTROLLERS = mapOf(
            103 to "LWZ integral unit", 104 to "LWZ integral unit", 190 to "WPM I", 325 to "WPM IW", 340 to "WPM ME",
            165 to "WPM 2", 331 to "WPM 2_1", 220 to "WPM X", 219 to "WPM X PLUS",
        )

        /** Documented start address to register count. */
        val STATIC_BLOCKS = listOf(2570 to 4, 5002 to 1)
        val IDENT_BLOCKS = listOf(501 to 48, 2501 to 6)
        val LIVE_BLOCKS = listOf(501 to 48, 609 to 1, 2501 to 6, 3501 to 16, 5001 to 1)
        val HOLDING_BLOCKS = listOf(1501 to 1)

        val OPERATING_MODES = mapOf(
            0 to "Emergency operation", 1 to "Standby", 2 to "Programmed operation", 3 to "Comfort", 4 to "Eco", 5 to "DHW mode",
        )
        val STATUS_BITS = mapOf(
            0 to "HC1 pump", 1 to "HC2 pump", 2 to "Heat-up program", 3 to "Booster heater", 4 to "Heating", 5 to "DHW heating",
            6 to "Compressor", 7 to "Summer mode", 8 to "Cooling mode", 9 to "Defrost", 10 to "Silent mode 1", 11 to "Silent mode 2",
        )
        val BUS_STATUS = mapOf(0 to "OK", -1 to "Fault", -2 to "Fault", -3 to "CAN error", -4 to "Line fault")
    }
}
