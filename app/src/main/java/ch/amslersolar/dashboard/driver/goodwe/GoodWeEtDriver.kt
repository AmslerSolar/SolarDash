// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.goodwe

import ch.amslersolar.dashboard.driver.DeviceDriver
import ch.amslersolar.dashboard.driver.modbus.ModbusException
import ch.amslersolar.dashboard.driver.modbus.ModbusTcpClient
import ch.amslersolar.dashboard.driver.modbus.RegisterBlock
import ch.amslersolar.dashboard.model.BatteryState
import ch.amslersolar.dashboard.model.DetailSection
import ch.amslersolar.dashboard.model.DeviceInfo
import ch.amslersolar.dashboard.model.DeviceProblem
import ch.amslersolar.dashboard.model.DeviceReading
import ch.amslersolar.dashboard.model.EnergyTotals
import ch.amslersolar.dashboard.model.PhaseReading
import ch.amslersolar.dashboard.model.PvString
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * GoodWe ET / EH / BT / BH hybrid inverters (incl. GW25K-ET, GW29.9K-ET, GW30K-ET) over Modbus TCP.
 * Modbus TCP has to be enabled on the inverter (register 47040, or via the SolarGo app).
 * The unit id is the inverter's Modbus address: 1–247, factory default 247 (0xF7).
 *
 * Register map: "GoodWe Modbus Protocol Hybrid", Modbus protocol Map V1.2 (docs/specs/goodwe/).
 * The spec does not state power sign conventions; the ones used here (grid + = export, battery + = discharge)
 * follow the open-source `goodwe` library and still need confirming on a real inverter.
 */
class GoodWeEtDriver(private val client: ModbusTcpClient, private val unitId: Int) : DeviceDriver {

    /** Optional blocks the inverter answered with a Modbus exception for; not asked again. */
    private val unsupported = mutableSetOf<Int>()
    private var deviceInfo: DetailSection? = null
    private var polls = 0

    private suspend fun registers(register: Int, count: Int): RegisterBlock = client.readHoldingRegisters(unitId, register, count)

    /** For registers older firmware may not have: a failure there shouldn't hide the rest of the data. */
    private suspend fun readOptional(register: Int, count: Int): RegisterBlock? {
        if (register in unsupported) return null
        return try {
            registers(register, count)
        } catch (e: CancellationException) {
            throw e
        } catch (_: ModbusException) {
            unsupported += register
            null
        } catch (_: IOException) {
            null
        }
    }

    override suspend fun identify(): DeviceInfo {
        val r = registers(DEVICE_INFO, DEVICE_INFO_COUNT)
        return DeviceInfo(model = r.ascii(35011, 5), serial = r.ascii(35003, 8))
    }

    override suspend fun read(): DeviceReading {
        val r = registers(RUNNING_DATA, RUNNING_DATA_COUNT)
        val pvExt = readOptional(PV_EXTENDED, PV_EXTENDED_COUNT)
        val battery2 = readOptional(BATTERY2, BATTERY2_COUNT)

        // PV1–PV4 have per-string power; PV5+ (35304…) only voltage and current.
        val strings = (0 until 4).map { i ->
            val base = 35103 + i * 4
            PvString(i + 1, r.u16(base) / 10.0, r.u16(base + 1) / 10.0, r.u32(base + 2).toInt())
        } + (pvExt?.let { ext ->
            (0 until 2).map { i -> PvString(5 + i, ext.u16(35304 + i * 2) / 10.0, ext.u16(35305 + i * 2) / 10.0, null) }
        } ?: emptyList())
        val activeStrings = strings.filter { it.voltageV > 0 || (it.powerW ?: 0) > 0 }
        val pvPower = pvExt?.u32(35301)?.toInt() ?: strings.sumOf { it.powerW ?: 0 }

        val phases = listOf("L1" to 35121, "L2" to 35126, "L3" to 35131).map { (name, base) ->
            PhaseReading(
                name = name,
                voltageV = r.u16(base) / 10.0,
                currentA = r.u16(base + 1) / 10.0,
                frequencyHz = r.u16(base + 2) / 100.0,
                powerW = r.s32(base + 3),
            )
        }

        // Meter power when a meter is connected, otherwise the inverter's grid-port power.
        val gridPower = r.s32(35139)

        val battery1Mode = r.u16(35184)
        val battery2Mode = battery2?.u16(35266) ?: 0
        var bms: RegisterBlock? = null
        val battery = if (battery1Mode == 0 && battery2Mode == 0) null else {
            // 37007 is the SOC of battery 1 only.
            if (battery1Mode != 0) bms = readOptional(BMS_DATA, BMS_DATA_COUNT)
            val soc = bms?.u16(37007)
            val power1 = if (battery1Mode != 0) r.s32(35182) else 0
            val power2 = if (battery2Mode != 0) battery2!!.s32(35264) else 0
            val mode = if (battery1Mode != 0) battery1Mode else battery2Mode
            BatteryState(
                powerW = power1 + power2,
                socPercent = soc,
                voltageV = if (battery1Mode != 0) r.u16(35180) / 10.0 else battery2!!.u16(35262) / 10.0,
                mode = BATTERY_MODES[mode] ?: "Mode $mode",
            )
        }

        if (polls % DEVICE_INFO_REFRESH_POLLS == 0) {
            readOptional(DEVICE_INFO, DEVICE_INFO_COUNT)?.let { deviceInfo = GoodWeDetails.deviceInfo(it) }
        }
        polls++
        val details = listOfNotNull(deviceInfo) + GoodWeDetails.running(r) +
            listOfNotNull(pvExt?.let(GoodWeDetails::pvExtended), battery2?.let(GoodWeDetails::battery2), bms?.let(GoodWeDetails::bms))

        return DeviceReading(
            details = details,
            pvPowerW = pvPower,
            pvStrings = activeStrings,
            gridPowerW = gridPower,
            // On-grid load (35171) excludes the back-up port, so add the back-up load (35169).
            loadPowerW = r.s32(35171) + r.s32(35169),
            battery = battery,
            phases = phases,
            temperatureC = r.s16(35176) / 10.0,
            status = WORK_MODES[r.u16(35187)] ?: "Unknown (${r.u16(35187)})",
            // No grid export/import today: 35199/35202 are the inverter's own AC output/input (per the spec's
            // Chinese text, which wins over the English "sell/buy"). The history integrates grid power instead.
            today = EnergyTotals(
                pvKwh = r.u32(35193) / 10.0,
                loadKwh = r.u16(35205) / 10.0,
            ),
            lifetimePvKwh = r.u32(35191) / 10.0,
            problems = problems(r, bms),
        )
    }

    internal companion object {
        const val DEVICE_INFO = 35000
        const val DEVICE_INFO_COUNT = 0x21
        const val RUNNING_DATA = 35100
        const val RUNNING_DATA_COUNT = 0x7D
        const val BATTERY2 = 35262
        const val BATTERY2_COUNT = 5
        const val PV_EXTENDED = 35301 // PV total power, PV channel count, PV5/PV6 voltage and current
        const val PV_EXTENDED_COUNT = 7
        const val BMS_DATA = 37000
        const val BMS_DATA_COUNT = 0x18
        const val DEVICE_INFO_REFRESH_POLLS = 60 // device info rarely changes: about every 5 minutes

        // Spec table 8-1
        internal val WORK_MODES = mapOf(
            0 to "Waiting",
            1 to "On-grid",
            2 to "Off-grid",
            3 to "Fault",
            4 to "Firmware update",
            5 to "Self-check",
        )

        /**
         * Table 8-2 "Error Message", the bits of 35189 (the register row points to table 8-12, the operation modes, in
         * English and Chinese alike; the bit table is 8-2). Bit 9 "Utility Loss" is the grid loss; "TBD" bits are
         * reported by number.
         */
        private val ERROR_BITS = mapOf(
            31 to "Internal communication failure", 30 to "EEPROM R/W failure", 29 to "Grid frequency failure (Fac)",
            28 to "DSP communication failure", 27 to "Phase angle failure", 25 to "Relay check failure",
            23 to "Vac consistency failure", 22 to "Fac consistency failure", 20 to "Back-up overload",
            19 to "DC injection high", 18 to "Isolation failure", 17 to "Grid voltage failure (Vac)",
            16 to "External fan failure", 15 to "PV over-voltage", 14 to "Utility phase failure", 13 to "Over-temperature",
            12 to "Internal fan failure", 11 to "DC bus high", 10 to "Ground current failure", 9 to "Utility loss",
            8 to "AC HCT failure", 7 to "Relay device failure", 6 to "GFCI device failure", 4 to "GFCI consistency failure",
            3 to "DCI consistency failure", 1 to "AC HCT check failure", 0 to "GFCI device check failure",
        )
        private const val UTILITY_LOSS_BIT = 9

        /**
         * 35189 error bits (table 8-2), 35185 warning code (no table: shown as hex), 35187 work mode 3 (fault) and
         * 2 (off-grid: the inverter is the AC source from PV and battery, table 8-1), 35136 grid mode 2 (fault,
         * table 8-10), and the BMS error and warning codes 37006/37012 and 37010/37013 (tables 8-7/8-8 are incomplete,
         * so shown as hex). Grid mode 0 "Loss" isn't used: it also covers the inverter waiting disconnected.
         * 35213 (CPLD warning) is "仅用于售后", for after-sales use only, and the diagnosis bits 35218/35220 are
         * explanations of the battery behaviour, not faults: both left out.
         */
        internal fun problems(r: RegisterBlock, bms: RegisterBlock?): List<DeviceProblem> {
            val out = mutableListOf<DeviceProblem>()
            val errors = r.u32(35189)
            for (bit in 31 downTo 0) {
                if ((errors shr bit) and 1L == 0L) continue
                if (bit == UTILITY_LOSS_BIT) {
                    out += DeviceProblem(DeviceProblem.Kind.MAINS_LOST, "Utility loss (error bit 9)")
                } else {
                    out += DeviceProblem(DeviceProblem.Kind.FAULT, "${ERROR_BITS[bit] ?: "Error"} (error bit $bit)")
                }
            }
            val workMode = r.u16(35187)
            if (r.u16(35136) == 2 && out.none { it.kind == DeviceProblem.Kind.FAULT }) {
                out += DeviceProblem(DeviceProblem.Kind.FAULT, "Grid fault (grid mode 2)")
            }
            if (workMode == 3 && out.none { it.kind == DeviceProblem.Kind.FAULT }) {
                out += DeviceProblem(DeviceProblem.Kind.FAULT, "Fault mode (work mode 3)")
            }
            if (workMode == 2) {
                out += DeviceProblem(DeviceProblem.Kind.BACKUP_MODE, "Off-grid mode (work mode 2)")
            }
            val warning = r.u16(35185)
            if (warning != 0) out += DeviceProblem(DeviceProblem.Kind.WARNING, "Warning code 0x%04X".format(warning))
            if (bms != null) {
                val bmsError = (bms.u16(37012).toLong() shl 16) or bms.u16(37006).toLong()
                if (bmsError != 0L) out += DeviceProblem(DeviceProblem.Kind.FAULT, "BMS error code 0x%08X".format(bmsError))
                val bmsWarning = (bms.u16(37013).toLong() shl 16) or bms.u16(37010).toLong()
                if (bmsWarning != 0L) out += DeviceProblem(DeviceProblem.Kind.WARNING, "BMS warning code 0x%08X".format(bmsWarning))
            }
            return out
        }

        // Spec table 8-9 (0 = no battery)
        internal val BATTERY_MODES = mapOf(
            1 to "Standby",
            2 to "Discharging",
            3 to "Charging",
        )
    }
}
