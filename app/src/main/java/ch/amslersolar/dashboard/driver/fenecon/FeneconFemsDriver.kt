// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.fenecon

import ch.amslersolar.dashboard.driver.DeviceDriver
import ch.amslersolar.dashboard.driver.Details.enum
import ch.amslersolar.dashboard.driver.Details.fmt
import ch.amslersolar.dashboard.driver.Details.section
import ch.amslersolar.dashboard.driver.modbus.ModbusException
import ch.amslersolar.dashboard.driver.modbus.ModbusTcpClient
import ch.amslersolar.dashboard.driver.modbus.RegisterBlock
import ch.amslersolar.dashboard.driver.sunspec.RegisterReader
import ch.amslersolar.dashboard.model.BatteryState
import ch.amslersolar.dashboard.model.DetailSection
import ch.amslersolar.dashboard.model.DeviceInfo
import ch.amslersolar.dashboard.model.DeviceProblem
import ch.amslersolar.dashboard.model.DeviceReading
import java.io.IOException
import kotlin.math.roundToInt

/**
 * FENECON FEMS (OpenEMS Edge): whole-system view from the `_sum` component over the FEMS App "Modbus/TCP Read Access".
 *
 * Register map: "FEMS App Modbus/TCP Read Access", version 2023.6.1 (docs/specs/fenecon/App_ModbusTCP_read_access.pdf),
 * table 1 (p. 3: port 502, unit id 1, function 03 or 04) and table 2 "Modbus table component Sum" (pp. 5–6).
 * Addresses are 0-based (p. 6: "Base Address … must be changed to 0").
 *
 * - Read with function 03 (holding registers); the PDF allows 03 and 04 alike.
 * - Live values 302–417 are read in one request (116 registers). The PDF lists only "the most important data points";
 *   whether the gaps between them are readable isn't stated, so if the FEMS rejects the block, the listed fields are
 *   read one by one and those it rejects are left out until the next refresh (about once a minute).
 * - Word order of float32 / float64 isn't stated in the PDF. OpenEMS writes them big-endian, high word first
 *   (UNVERIFIED against the PDF). Undefined values are NaN (OpenEMS behaviour, UNVERIFIED); 0xFFFF in an uint16 /
 *   enum16 register is also treated as missing (UNVERIFIED).
 *
 * Signs: the PDF doesn't define them. From the OpenEMS source (Sum channel docs, not the PDF):
 * - EssActivePower 303 / EssDischargePower 415: + = discharge, − = charge; same as our [BatteryState.powerW].
 *   303 is AC-side and includes surplus DC PV of a hybrid inverter; 415 is the battery's own share, so 415 is used
 *   (fallback 303 − 339).
 * - GridActivePower 315: + = buy from grid, − = sell; our [DeviceReading.gridPowerW] is the negated value.
 * - Production 327 and consumption 343 are ≥ 0.
 *
 * No daily counters exist in the `_sum` table, so `today` stays empty (the app integrates the power history); the
 * cumulative energies 351–387 are lifetime counters in the details. No serial number is available.
 */
class FeneconFemsDriver(private val reader: RegisterReader, private val unitId: Int) : DeviceDriver {
    constructor(client: ModbusTcpClient, unitId: Int) : this(RegisterReader { u, r, c -> client.readHoldingRegisters(u, r, c) }, unitId)

    private var blockRejected = false
    private val missing = mutableSetOf<Int>()
    private var polls = 0

    override suspend fun identify(): DeviceInfo {
        val id = try {
            Image(reader.read(unitId, COMPONENT_ID, 16).values())
        } catch (e: ModbusException) {
            throw IOException("Unit $unitId is not a FENECON FEMS (${e.message})", e)
        }.text(COMPONENT_ID, 16)
        if (id != "_sum") throw IOException("Unit $unitId is not a FENECON FEMS: component id ${id?.let { "\"$it\"" } ?: "empty"} (register 200)")
        // State 222 must be one of the documented values.
        val state = try {
            reader.read(unitId, STATE, 1).u16(STATE)
        } catch (e: ModbusException) {
            throw IOException("Unit $unitId is not a FENECON FEMS: no state register 222 (${e.message})", e)
        }
        if (state !in STATES.keys && state != UNDEFINED_16) throw IOException("Unit $unitId is not a FENECON FEMS: state $state (register 222)")
        return DeviceInfo(model = "FENECON FEMS", serial = "")
    }

    override suspend fun read(): DeviceReading {
        if (polls % REFRESH_POLLS == 0) {
            blockRejected = false
            missing.clear()
        }
        polls++
        val regs = HashMap<Int, Int>()
        regs += field(STATE, 1)
        if (!blockRejected) {
            try {
                regs += reader.read(unitId, LIVE_START, LIVE_END - LIVE_START).values()
            } catch (_: ModbusException) {
                blockRejected = true
            }
        }
        if (blockRejected) for ((a, n) in LIVE_FIELDS) regs += field(a, n)
        val img = Image(regs)
        return parse(img).copy(details = details(img))
    }

    private suspend fun field(a: Int, n: Int): Map<Int, Int> {
        if (a in missing) return emptyMap()
        return try {
            reader.read(unitId, a, n).values()
        } catch (_: ModbusException) {
            missing += a
            emptyMap()
        }
    }

    private fun RegisterBlock.values(): Map<Int, Int> = (start until start + count).associateWith { u16(it) }

    /** Register values by address; multi-register values big-endian, high word first. Missing / NaN → null. */
    internal class Image(private val regs: Map<Int, Int>) {
        fun u16(a: Int): Int? = regs[a]?.takeIf { it != UNDEFINED_16 }
        private fun raw(a: Int, n: Int): Long? = (a until a + n).fold(0L) { acc, r -> (acc shl 16) or (regs[r] ?: return null).toLong() }
        fun f32(a: Int): Double? = raw(a, 2)?.let { Float.fromBits(it.toInt()).toDouble() }?.takeIf { it.isFinite() }
        fun f64(a: Int): Double? = raw(a, 4)?.let { Double.fromBits(it) }?.takeIf { it.isFinite() }
        fun text(a: Int, n: Int): String? {
            val chars = (a until a + n).map { regs[it] ?: return null }.flatMap { listOf((it shr 8).toChar(), (it and 0xFF).toChar()) }
            return String(chars.toCharArray()).trim { it <= ' ' }.takeIf { it.isNotEmpty() }
        }
    }

    internal companion object {
        const val REFRESH_POLLS = 12
        const val UNDEFINED_16 = 0xFFFF
        const val COMPONENT_ID = 200
        const val STATE = 222
        const val SOC = 302
        const val ESS_ACTIVE_POWER = 303
        const val ESS_REACTIVE_POWER = 309
        const val GRID_ACTIVE_POWER = 315
        const val GRID_MIN_ACTIVE_POWER = 317
        const val GRID_MAX_ACTIVE_POWER = 319
        const val PRODUCTION_ACTIVE_POWER = 327
        const val PRODUCTION_MAX_ACTIVE_POWER = 329
        const val PRODUCTION_AC_ACTIVE_POWER = 331
        const val PRODUCTION_DC_ACTUAL_POWER = 339
        const val CONSUMPTION_ACTIVE_POWER = 343
        const val CONSUMPTION_MAX_ACTIVE_POWER = 345
        const val ESS_ACTIVE_CHARGE_ENERGY = 351
        const val ESS_ACTIVE_DISCHARGE_ENERGY = 355
        const val GRID_BUY_ACTIVE_ENERGY = 359
        const val GRID_SELL_ACTIVE_ENERGY = 363
        const val PRODUCTION_ACTIVE_ENERGY = 367
        const val PRODUCTION_AC_ACTIVE_ENERGY = 371
        const val PRODUCTION_DC_ACTIVE_ENERGY = 375
        const val CONSUMPTION_ACTIVE_ENERGY = 379
        const val ESS_DC_CHARGE_ENERGY = 383
        const val ESS_DC_DISCHARGE_ENERGY = 387
        const val ESS_DISCHARGE_POWER = 415
        const val GRID_MODE = 417

        const val LIVE_START = SOC
        const val LIVE_END = GRID_MODE + 1 // 116 registers

        val LIVE_FIELDS: List<Pair<Int, Int>> =
            listOf(SOC to 1) +
                listOf(
                    ESS_ACTIVE_POWER, ESS_REACTIVE_POWER, GRID_ACTIVE_POWER, GRID_MIN_ACTIVE_POWER, GRID_MAX_ACTIVE_POWER,
                    PRODUCTION_ACTIVE_POWER, PRODUCTION_MAX_ACTIVE_POWER, PRODUCTION_AC_ACTIVE_POWER, PRODUCTION_DC_ACTUAL_POWER,
                    CONSUMPTION_ACTIVE_POWER, CONSUMPTION_MAX_ACTIVE_POWER, ESS_DISCHARGE_POWER,
                ).map { it to 2 } +
                (ESS_ACTIVE_CHARGE_ENERGY..ESS_DC_DISCHARGE_ENERGY step 4).map { it to 4 } +
                listOf(GRID_MODE to 1)

        val STATES = mapOf(0 to "Ok", 1 to "Info", 2 to "Warning", 3 to "Fault")
        val GRID_MODES = mapOf(1 to "On-grid", 2 to "Off-grid")

        /** Battery share of the ESS power: 415, else AC-side 303 minus the hybrid inverter's DC PV 339. */
        private fun batteryPower(img: Image): Double? = img.f32(ESS_DISCHARGE_POWER)
            ?: img.f32(ESS_ACTIVE_POWER)?.let { it - (img.f32(PRODUCTION_DC_ACTUAL_POWER) ?: 0.0) }

        fun parse(img: Image): DeviceReading {
            val soc = img.u16(SOC)?.takeIf { it in 0..100 }
            val power = batteryPower(img)?.roundToInt()
            val battery = if (soc == null && power == null) null else BatteryState(
                powerW = power ?: 0,
                socPercent = soc,
                voltageV = null,
                mode = when {
                    power == null -> "Unknown"
                    power > 0 -> "Discharging"
                    power < 0 -> "Charging"
                    else -> "Idle"
                },
            )
            val state = img.u16(STATE)
            val gridMode = img.u16(GRID_MODE)
            val status = listOfNotNull(
                state?.let { STATES[it] ?: "State $it" },
                gridMode?.let { GRID_MODES[it] ?: "Grid mode $it" },
            ).joinToString(", ").ifEmpty { null }
            return DeviceReading(
                pvPowerW = img.f32(PRODUCTION_ACTIVE_POWER)?.roundToInt(),
                battery = battery,
                gridPowerW = img.f32(GRID_ACTIVE_POWER)?.let { -it.roundToInt() }, // + buy from grid → + export
                loadPowerW = img.f32(CONSUMPTION_ACTIVE_POWER)?.roundToInt(),
                status = status,
                lifetimePvKwh = img.f64(PRODUCTION_ACTIVE_ENERGY)?.div(1000),
                problems = problems(img),
            )
        }

        /** State 222 (2 Warning, 3 Fault) and GridMode 417 (2 Off-Grid), table 2. The `_sum` table has no fault codes. */
        fun problems(img: Image): List<DeviceProblem> = buildList {
            when (img.u16(STATE)) {
                2 -> add(DeviceProblem(DeviceProblem.Kind.WARNING, "Warning (state 2)"))
                3 -> add(DeviceProblem(DeviceProblem.Kind.FAULT, "Fault (state 3)"))
            }
            if (img.u16(GRID_MODE) == 2) add(DeviceProblem(DeviceProblem.Kind.BACKUP_MODE, "Off-grid (grid mode 2)"))
        }

        private fun w(img: Image, a: Int) = fmt(img.f32(a), "W", 0)
        private fun kwh(img: Image, a: Int) = fmt(img.f64(a)?.div(1000), "kWh", 1)

        fun details(img: Image): List<DetailSection> = listOf(
            section(
                "System",
                "State" to enum(img.u16(STATE), STATES),
                "Grid mode" to enum(img.u16(GRID_MODE), GRID_MODES),
            ),
            section(
                "Battery (ESS)",
                "State of charge" to fmt(img.u16(SOC), "%"),
                "Active power AC side (+ discharge)" to w(img, ESS_ACTIVE_POWER),
                "Discharge power (+ discharge)" to w(img, ESS_DISCHARGE_POWER),
                "Reactive power" to fmt(img.f32(ESS_REACTIVE_POWER), "var", 0),
            ),
            section(
                "Grid",
                "Active power (+ from grid)" to w(img, GRID_ACTIVE_POWER),
                "Minimum active power" to w(img, GRID_MIN_ACTIVE_POWER),
                "Maximum active power" to w(img, GRID_MAX_ACTIVE_POWER),
            ),
            section(
                "Production",
                "Active power" to w(img, PRODUCTION_ACTIVE_POWER),
                "Hybrid inverter DC power" to w(img, PRODUCTION_DC_ACTUAL_POWER),
                "External AC inverters" to w(img, PRODUCTION_AC_ACTIVE_POWER),
                "Maximum active power" to w(img, PRODUCTION_MAX_ACTIVE_POWER),
            ),
            section(
                "Consumption",
                "Active power" to w(img, CONSUMPTION_ACTIVE_POWER),
                "Maximum active power" to w(img, CONSUMPTION_MAX_ACTIVE_POWER),
            ),
            section(
                "Energy totals",
                "Production" to kwh(img, PRODUCTION_ACTIVE_ENERGY),
                "Production DC (hybrid inverter)" to kwh(img, PRODUCTION_DC_ACTIVE_ENERGY),
                "Production AC (external inverters)" to kwh(img, PRODUCTION_AC_ACTIVE_ENERGY),
                "Consumption" to kwh(img, CONSUMPTION_ACTIVE_ENERGY),
                "Grid buy" to kwh(img, GRID_BUY_ACTIVE_ENERGY),
                "Grid sell" to kwh(img, GRID_SELL_ACTIVE_ENERGY),
                "Battery charge (AC side)" to kwh(img, ESS_ACTIVE_CHARGE_ENERGY),
                "Battery discharge (AC side)" to kwh(img, ESS_ACTIVE_DISCHARGE_ENERGY),
                "Battery charge (DC)" to kwh(img, ESS_DC_CHARGE_ENERGY),
                "Battery discharge (DC)" to kwh(img, ESS_DC_DISCHARGE_ENERGY),
            ),
        ).filter { it.rows.isNotEmpty() }
    }
}
