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

        // Spec table 8-9 (0 = no battery)
        internal val BATTERY_MODES = mapOf(
            1 to "Standby",
            2 to "Discharging",
            3 to "Charging",
        )
    }
}
