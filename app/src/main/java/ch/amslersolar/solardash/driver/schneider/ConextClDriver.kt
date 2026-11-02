// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.driver.schneider

import ch.amslersolar.solardash.driver.DeviceDriver
import ch.amslersolar.solardash.driver.Details.fmt
import ch.amslersolar.solardash.driver.Details.section
import ch.amslersolar.solardash.driver.modbus.ModbusException
import ch.amslersolar.solardash.driver.modbus.ModbusTcpClient
import ch.amslersolar.solardash.driver.modbus.RegisterBlock
import ch.amslersolar.solardash.driver.sunspec.MoreSunSpecProfiles
import ch.amslersolar.solardash.driver.sunspec.RegisterReader
import ch.amslersolar.solardash.driver.sunspec.SunSpecInverterDriver
import ch.amslersolar.solardash.model.DetailSection
import ch.amslersolar.solardash.model.DeviceInfo
import ch.amslersolar.solardash.model.DeviceProblem
import ch.amslersolar.solardash.model.DeviceProblem.Kind
import ch.amslersolar.solardash.model.DeviceReading
import ch.amslersolar.solardash.model.EnergyTotals
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * Schneider Electric Conext CL 18 / 20 / 25 (NA and E), PV only, over SunSpec Modbus TCP with the shared
 * [SunSpecInverterDriver] ([MoreSunSpecProfiles.CONEXT_CL]: port 502, unit id 10 by default). Spec: "Modbus
 * Registers Map – Conext CL", 503-0243-01-01 rev 1.5 (docs/specs/schneider/); all reads are function code 03.
 *
 * Two things the map needs on top of plain SunSpec:
 * - Common model length: 0x9C43 says 65, but Common ends with a pad register (0x9C85) and model 103 starts at
 *   0x9C86, i.e. 66 registers. A chain walk with 65 would land on the pad register. When 0x9C43 reads 65 and 0x9C86
 *   holds 103, the length is passed on as 66.
 * - Resolution and energy: model 103 has W / VA / VAr with SF 2 (100 W steps) and no DC power (DC voltage and power
 *   are "NA"); WH_SF is listed as "1 (multiply by 1)", ambiguous. Solar is therefore the sum of the two MPPT inputs
 *   of model 160 (DCW_SF 0, 1 W), and yield comes from the vendor registers: 0x0802 lifetime and 0x0804 today
 *   (U32, high word first, kWh/10), 0x0806 yesterday, 0x081E operating hours. Unused vendor addresses read 0xFF.
 * - Model 160's input IDs are 161 / 162 in the map ("01 / 02 (example)"); strings are numbered 1, 2 here.
 * - Problems: the SunSpec ones (St, Evt1), plus the one active alarm the inverter reports in 0x1828 ("Inverter
 *   Alarm code", section 4 "Alarm codes", pages 20–23): services and errors stop the unit (FAULT; the grid
 *   voltage / frequency errors and "No-Grid" are MAINS_LOST), warnings let it run (WARNING). The "internal
 *   events" 7000 / 8000 / 9000 are for Schneider's own analysis and left out.
 * No grid meter: grid and home come from another device of the plant.
 */
class ConextClDriver(reader: RegisterReader, private val unitId: Int) : DeviceDriver {
    constructor(client: ModbusTcpClient, unitId: Int) : this(RegisterReader { u, r, c -> client.readHoldingRegisters(u, r, c) }, unitId)

    private val fixed = CommonLengthFix(reader)
    private val inner = SunSpecInverterDriver(fixed, unitId, MoreSunSpecProfiles.CONEXT_CL)
    private val fetcher = RegisterFetcher(reader, unitId)

    override suspend fun identify(): DeviceInfo = inner.identify()

    override suspend fun read(): DeviceReading {
        val base = inner.read()
        val vendor = try {
            Registers(fetcher.fetch(VENDOR_FIELDS), lowWordFirst = false)
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            null
        }
        val strings = base.pvStrings.mapIndexed { i, s -> s.copy(index = i + 1) }
        val today = vendor?.let { energy(it, 0x0804) }
        return base.copy(
            pvStrings = strings,
            today = if (today != null) EnergyTotals(pvKwh = today) else base.today,
            lifetimePvKwh = vendor?.let { energy(it, 0x0802) } ?: base.lifetimePvKwh,
            details = base.details + listOfNotNull(vendor?.let(::vendorDetails)?.takeIf { it.rows.isNotEmpty() }),
            problems = (base.problems + listOfNotNull(vendor?.u16(ALARM_CODE)?.let(::alarm))).distinct(),
        )
    }

    /** Passes 66 on as the Common model's length where the device says 65 but lays out 66 registers (see class doc). */
    internal class CommonLengthFix(private val raw: RegisterReader) : RegisterReader {
        private val padded = HashMap<Int, Boolean>()

        override suspend fun read(unitId: Int, register: Int, count: Int): RegisterBlock {
            val block = raw.read(unitId, register, count)
            if (COMMON_LENGTH !in register until register + count || block.u16(COMMON_LENGTH) != 65) return block
            val fix = padded.getOrPut(unitId) {
                try {
                    raw.read(unitId, FIRST_MODEL, 1).u16(FIRST_MODEL) == 103
                } catch (_: ModbusException) {
                    false
                }
            }
            if (!fix) return block
            val data = ByteArray(count * 2)
            for (i in 0 until count) {
                val v = if (register + i == COMMON_LENGTH) 66 else block.u16(register + i)
                data[2 * i] = (v shr 8).toByte()
                data[2 * i + 1] = v.toByte()
            }
            return RegisterBlock(register, data)
        }
    }

    internal companion object {
        const val COMMON_LENGTH = 0x9C43
        const val FIRST_MODEL = 0x9C86
        const val ALARM_CODE = 0x1828
        val VENDOR_FIELDS = listOf(0x0802 to 2, 0x0804 to 2, 0x0806 to 2, 0x081E to 2, ALARM_CODE to 1)

        /** Section 4 of the map: code → (kind, message). */
        private val ALARMS: Map<Int, Pair<Kind, String>> = mapOf(
            // Services: the unit stops and needs manual intervention.
            10 to "AC relay fault", 31 to "AC current sensor fault", 32 to "AC temperature sensor fault",
            33 to "DC temperature sensor fault", 47 to "AC voltage sensor fault", 131 to "Low efficiency fault",
            203 to "DC initialisation fault", 204 to "DC self-test fault", 205 to "AC initialisation fault",
            206 to "AC self-test fault", 701 to "DC injection fault", 706 to "DC injection high current",
            702 to "RCMU fault", 708 to "RCMU fault", 703 to "Arc fault (AFCI)", 709 to "Arc fault",
            704 to "Arc fault self-test failed", 705 to "PV input reversed", 710 to "System configuration fault",
            711 to "Shared system configuration fault", 712 to "AC internal configuration fault",
            713 to "DC internal configuration fault", 714 to "RPO power module error",
            // Errors: the unit stops and recovers by itself.
            2018 to "AC internal communication error", 2060 to "AC low temperature", 2061 to "AC modules over-temperature",
            2062 to "DC modules over-temperature", 2101 to "DC internal communication error", 2102 to "DC-AC communication error",
            2411 to "AC over-current", 2460 to "AC over-current", 2605 to "PV 1 over-voltage", 2606 to "PV 2 over-voltage",
            2616 to "PV insulation error", 2624 to "PV 1 and 2 over-current",
            2633 to "PV 1 over-current", 2634 to "PV 2 over-current", 2646 to "RCMU error", 2647 to "RCMU error",
            2648 to "Low efficiency error",
        ).mapValues { Kind.FAULT to it.value } + mapOf(
            2401 to "Grid under-frequency (fast)", 2402 to "Grid over-frequency (fast)", 2406 to "Grid under-voltage (fast)",
            2407 to "Grid over-voltage (fast)", 2408 to "Grid under-voltage (instantaneous)",
            2410 to "Grid over-voltage (instantaneous)", 2415 to "Grid over-voltage (10 min average)",
            2416 to "Grid under-frequency (slow)", 2417 to "Grid over-frequency (slow)", 2418 to "Grid under-voltage (slow)",
            2419 to "Grid over-voltage (slow)", 2450 to "Grid not available",
        ).mapValues { Kind.MAINS_LOST to it.value } + mapOf(
            // Warnings: the unit keeps running.
            4003 to "Replace fan", 4004 to "Loss of cooling", 4061 to "Ambient over-temperature derating",
            4065 to "DC module 1 over-temperature derating", 4066 to "DC module 2 over-temperature derating",
            4068 to "DC module 1 over-temperature, PV 1 off", 4069 to "DC module 2 over-temperature, PV 2 off",
            4660 to "AC module over-temperature derating",
            4661 to "PV 1 surge protection needs service", 4662 to "PV 2 surge protection needs service",
            4663 to "AC surge protection needs service", 4680 to "Invalid date",
        ).mapValues { Kind.WARNING to it.value }

        /**
         * Not reported: the internal events, and the PV under-voltage / low power alarms (2631, 4656, 4657, 4700),
         * which the map doesn't tell apart from the normal low light at dawn and dusk.
         */
        private val IGNORED = setOf(7000, 8000, 9000, 2631, 4656, 4657, 4700)

        /** 0 = no active alarm, 0xFFFF = not implemented (unused addresses read 0xFF). */
        fun alarm(code: Int): DeviceProblem? {
            if (code == 0 || code == 0xFFFF || code in IGNORED) return null
            val (kind, text) = ALARMS[code] ?: return DeviceProblem(Kind.FAULT, "Alarm code $code")
            return DeviceProblem(kind, "$text ($code)")
        }

        /** U32 kWh/10; 0xFFFFFFFF (unused) → null. */
        fun energy(img: Registers, a: Int): Double? = img.u32(a)?.takeIf { it != 0xFFFFFFFFL }?.div(10.0)

        fun vendorDetails(img: Registers): DetailSection = section(
            "Energy (Conext CL registers)",
            "Yield today" to fmt(energy(img, 0x0804), "kWh", 1),
            "Yield yesterday" to fmt(energy(img, 0x0806), "kWh", 1),
            "Yield total" to fmt(energy(img, 0x0802), "kWh", 1),
            "Operating hours" to fmt(img.u32(0x081E)?.takeIf { it != 0xFFFFFFFFL }, "h"),
        )
    }
}
