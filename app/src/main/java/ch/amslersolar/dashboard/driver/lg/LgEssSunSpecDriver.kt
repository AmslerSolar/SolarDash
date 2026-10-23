// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.lg

import ch.amslersolar.dashboard.driver.DeviceDriver
import ch.amslersolar.dashboard.driver.Details.bits
import ch.amslersolar.dashboard.driver.Details.enum
import ch.amslersolar.dashboard.driver.Details.fmt
import ch.amslersolar.dashboard.driver.Details.section
import ch.amslersolar.dashboard.driver.modbus.ModbusTcpClient
import ch.amslersolar.dashboard.driver.sunspec.Model
import ch.amslersolar.dashboard.driver.sunspec.RegisterReader
import ch.amslersolar.dashboard.driver.sunspec.SunSpecDevice
import ch.amslersolar.dashboard.model.BatteryState
import ch.amslersolar.dashboard.model.DetailSection
import ch.amslersolar.dashboard.model.DeviceInfo
import ch.amslersolar.dashboard.model.DeviceReading
import ch.amslersolar.dashboard.model.EnergyTotals
import ch.amslersolar.dashboard.model.PhaseReading
import kotlinx.coroutines.CancellationException
import java.io.IOException
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * LG ESS Home 8 / Home 10 over SunSpec Modbus TCP (port 6502).
 *
 * Register map: "Home 8/10 LG ESS SunSpec ver. 1.3" (docs/specs/lg ess/), firmware 10.05.7213 and later.
 * Models: 1 Common, 103 inverter (scaled) / 113 (float), 802 battery, 64256 LG power meter, 120/123 for details.
 * - Grid: model 64256 ActPower, >0 purchase / <0 feed-in (documented) → our sign is the opposite.
 * - Battery: model 802 W, direction from ChaSt (3 discharging, 4 charging).
 * - Solar: not a register. Derived from the DC-bus balance, AC output − battery (+ = discharging); it ignores
 *   conversion losses, so it reads a few percent low, but home consumption (AC output + purchase) stays exact.
 *
 * Checked against a real Home 10 (firmware 10.05.7437):
 * - LG's scale factors are the inverse of the SunSpec standard: value ÷ 10^SF (voltage 2384, SF 1 → 238.4 V;
 *   model 113 reports 238.4 as a float). SoC 233 / SF 1 = 23.3 %, meter power 606 / SF 2 = 6.06 W.
 * - Battery W (802 offset 47) has no scale factor and is already in watts.
 * - The meter's "PV generation energy" (64256 offset 20) is today's PV yield in Wh (it equals the HTTPS API's
 *   today_pv_generation_sum); purchase and feed-in are lifetime counters.
 * - Battery voltage and currents (802 offsets 34, 44–46) aren't real readings: left out of the battery figures,
 *   marked "as reported" in the details. Inverter lifetime energy (113/103) is in kWh.
 * - The meter has a voltage per phase but only a total power: phases carry voltage and frequency only.
 */
class LgEssSunSpecDriver(reader: RegisterReader, unitId: Int) : DeviceDriver {
    constructor(client: ModbusTcpClient, unitId: Int) : this(RegisterReader { u, r, c -> client.readHoldingRegisters(u, r, c) }, unitId)

    private val device = SunSpecDevice(reader, unitId)
    private var static: List<DetailSection> = emptyList()
    private var polls = 0

    override suspend fun identify(): DeviceInfo {
        val common = device.model(1) ?: throw IOException("SunSpec Common model (1) missing")
        val model = "${common.text(2, 16)} ${common.text(18, 16)}".trim()
        val version = common.text(42, 8)
        return DeviceInfo(model = model + if (version.isNotEmpty()) " (firmware $version)" else "", serial = common.text(50, 16))
    }

    override suspend fun read(): DeviceReading {
        val m113 = device.model(113)
        val m103 = if (m113 == null) device.model(103) else null
        val m802 = device.model(802)
        val meter = device.model(64256)
        val reading = parse(m103, m113, m802, meter)

        if (polls % STATIC_REFRESH_POLLS == 0) {
            static = listOf(1 to ::commonDetails, 120 to ::nameplateDetails, 123 to ::controlsDetails).mapNotNull { (id, build) ->
                try {
                    device.model(id)?.let(build)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: IOException) {
                    null // extras: keep the live values
                }
            }
        }
        polls++
        val live = listOfNotNull(inverterDetails(m103, m113), m802?.let(::batteryDetails), meter?.let(::meterDetails))
        val identity = static.filter { it.title.startsWith("Identity") }
        return reading.copy(details = identity + live + static.filterNot { it.title.startsWith("Identity") })
    }

    companion object {
        private const val STATIC_REFRESH_POLLS = 12 // identity, nameplate and controls: about once a minute

        private val INVERTER_STATES = mapOf(
            1 to "Off", 2 to "Sleeping", 3 to "Starting", 4 to "Producing",
            5 to "Throttled", 6 to "Shutting down", 7 to "Fault", 8 to "Standby",
        )
        private val CHARGE_STATES = mapOf(
            1 to "Off", 2 to "Empty", 3 to "Discharging", 4 to "Charging", 5 to "Full", 6 to "Holding", 7 to "Testing",
        )
        private val DER_TYPES = mapOf(4 to "PV", 82 to "PV with storage")
        private val CONNECTED = mapOf(0 to "Disconnected", 1 to "Connected")
        private val ENABLED = mapOf(0 to "Disabled", 1 to "Enabled")
        private val VAR_MODES = mapOf(0 to "None", 1 to "% of max W", 2 to "% of max VAr", 3 to "% of available VAr")
        private val CONTROL_MODES = mapOf(0 to "Remote", 1 to "Local")
        private val BATTERY_TYPES = mapOf(
            0 to "Not applicable", 1 to "Lead-acid", 2 to "NiMH", 3 to "NiCd", 4 to "Li-ion", 5 to "Carbon-zinc",
            6 to "Zinc chloride", 7 to "Alkaline", 8 to "Rechargeable alkaline", 9 to "Sodium-sulfur", 10 to "Flow", 99 to "Other",
        )
        private val BANK_STATES = mapOf(
            1 to "Disconnected", 2 to "Initializing", 3 to "Connected", 4 to "Standby", 5 to "SoC protection",
            6 to "Suspending", 99 to "Fault",
        )

        /** LG scales the other way round from SunSpec: value ÷ 10^SF. A missing SF counts as 0. */
        private fun Model.lg(value: Number?, sfOffset: Int): Double? {
            value ?: return null
            return value.toDouble() / 10.0.pow(s16(sfOffset) ?: 0)
        }

        private fun Model.f(o: Int): Double? = f32(o)?.toDouble()

        fun parse(m103: Model?, m113: Model?, m802: Model?, meter: Model?): DeviceReading {
            val acW: Double?
            val temp: Double?
            val state: Int?
            if (m113 != null) {
                acW = m113.f(22); temp = m113.f(40); state = m113.u16(48)
            } else {
                acW = m103?.let { it.lg(it.s16(14), 15) }
                temp = m103?.let { it.lg(it.s16(33), 37) }
                state = m103?.u16(38)
            }

            val battery = m802?.let { m ->
                val mag = m.s16(47)?.let(::abs) ?: 0 // watts, no scale factor
                val cha = m.u16(16)
                val power = when (cha) {
                    3 -> mag
                    4 -> -mag
                    else -> 0
                }
                BatteryState(power, m.lg(m.u16(11), 56)?.roundToInt()?.takeIf { it in 0..100 }, null, CHARGE_STATES[cha] ?: "Standby")
            }

            val grid = meter?.let { m -> m.lg(m.s32(2), 4)?.let { -it.roundToInt() } } // meter: >0 purchase
            val hz = meter?.let { it.lg(it.u16(9), 10) } ?: 0.0
            val phases = if (meter == null) emptyList() else listOf("L1" to 5, "L2" to 6, "L3" to 7).mapNotNull { (name, o) ->
                meter.lg(meter.u16(o), 8)?.takeIf { it > 0 }?.let { PhaseReading(name, it, null, hz, null) }
            }
            val pvToday = meter?.let { it.lg(it.u32(20), 19) }?.div(1000) // today's PV yield in Wh

            val pv = acW?.let { maxOf(0, (it - (battery?.powerW ?: 0)).roundToInt()) }
            val load = if (acW != null && grid != null) maxOf(0, (acW - grid).roundToInt()) else null

            return DeviceReading(
                pvPowerW = pv,
                battery = battery,
                gridPowerW = grid,
                phases = phases,
                loadPowerW = load,
                temperatureC = temp,
                status = state?.let { INVERTER_STATES[it] ?: "State $it" },
                today = EnergyTotals(pvKwh = pvToday),
            )
        }

        // --- detail view: every point of LG's map -------------------------------------------------------------
        fun commonDetails(m: Model) = section(
            "Identity (SunSpec model 1)",
            "Manufacturer" to m.text(2, 16), "Model" to m.text(18, 16),
            "Version" to m.text(42, 8), "Serial number" to m.text(50, 16),
        )

        fun inverterDetails(m103: Model?, m113: Model?): DetailSection? {
            if (m113 != null) {
                val m = m113
                return section(
                    "Inverter (SunSpec model 113)",
                    "Current" to fmt(m.f(2), "A"), "L1 current" to fmt(m.f(4), "A"),
                    "L2 current" to fmt(m.f(6), "A"), "L3 current" to fmt(m.f(8), "A"),
                    "L1 voltage" to fmt(m.f(16), "V", 1), "L2 voltage" to fmt(m.f(18), "V", 1),
                    "L3 voltage" to fmt(m.f(20), "V", 1), "AC power" to fmt(m.f(22), "W"),
                    "Frequency" to fmt(m.f(24), "Hz", 2), "Apparent power" to fmt(m.f(26), "VA"),
                    "Energy (lifetime)" to fmt(m.f(32), "kWh", 0), // kWh on the real unit, not Wh
                    "DC current" to fmt(m.f(34), "A"), "DC voltage" to fmt(m.f(36), "V", 1),
                    "DC power" to fmt(m.f(38), "W"), "Cabinet temperature" to fmt(m.f(40), "°C", 1),
                    "Transformer temperature" to fmt(m.f(44), "°C", 1),
                    "Operating state" to enum(m.u16(48), INVERTER_STATES), "Events" to bits(m.u32(50)),
                )
            }
            val m = m103 ?: return null
            return section(
                "Inverter (SunSpec model 103)",
                "Current" to fmt(m.lg(m.u16(2), 6), "A"), "L1 current" to fmt(m.lg(m.u16(3), 6), "A"),
                "L2 current" to fmt(m.lg(m.u16(4), 6), "A"), "L3 current" to fmt(m.lg(m.u16(5), 6), "A"),
                "L1 voltage" to fmt(m.lg(m.u16(10), 13), "V", 1), "L2 voltage" to fmt(m.lg(m.u16(11), 13), "V", 1),
                "L3 voltage" to fmt(m.lg(m.u16(12), 13), "V", 1), "AC power" to fmt(m.lg(m.s16(14), 15), "W"),
                "Frequency" to fmt(m.lg(m.u16(16), 17), "Hz", 2), "Apparent power" to fmt(m.lg(m.s16(18), 19), "VA"),
                "Energy (lifetime)" to fmt(m.lg(m.u32(24), 26), "kWh", 0),
                "DC current" to fmt(m.lg(m.u16(27), 28), "A"), "DC voltage" to fmt(m.lg(m.u16(29), 30), "V", 1),
                "DC power" to fmt(m.lg(m.s16(31), 32), "W"),
                "Cabinet temperature" to fmt(m.lg(m.s16(33), 37), "°C", 1),
                "Transformer temperature" to fmt(m.lg(m.s16(35), 37), "°C", 1),
                "Operating state" to enum(m.u16(38), INVERTER_STATES), "Events" to bits(m.u32(40)),
            )
        }

        fun nameplateDetails(m: Model) = section(
            "Nameplate ratings (SunSpec model 120)",
            "Type" to enum(m.u16(2), DER_TYPES), "Rated power" to fmt(m.lg(m.u16(3), 4), "W"),
            "Rated apparent power" to fmt(m.lg(m.u16(5), 6), "VA"),
            *(1..4).map { "Rated reactive power Q$it" to fmt(m.lg(m.s16(6 + it), 11), "var") }.toTypedArray(),
            "Rated current" to fmt(m.lg(m.u16(12), 13), "A"),
            *(1..4).map { "Min. power factor Q$it" to fmt(m.lg(m.s16(13 + it), 18), "%") }.toTypedArray(),
            "Rated storage energy" to fmt(m.lg(m.u16(19), 20), "Wh"),
            "Max. charge rate" to fmt(m.lg(m.u16(23), 24), "W"),
            "Max. discharge rate" to fmt(m.lg(m.u16(25), 26), "W"),
        )

        fun controlsDetails(m: Model) = section(
            "Inverter controls (SunSpec model 123)",
            "Grid connection" to enum(m.u16(4), CONNECTED),
            "Power limit" to fmt(m.lg(m.u16(5), 23), "% of max"),
            "Power limit active" to enum(m.u16(9), ENABLED),
            "Power factor setpoint" to fmt(m.lg(m.s16(10), 24)),
            "Power factor setpoint active" to enum(m.u16(14), ENABLED),
            "Reactive power mode" to enum(m.u16(21), VAR_MODES),
            "Reactive power control active" to enum(m.u16(22), ENABLED),
        )

        fun batteryDetails(m: Model) = section(
            "Battery (SunSpec model 802)",
            "Capacity" to fmt(m.lg(m.u16(2), 52), "Ah"), "Energy capacity" to fmt(m.lg(m.u16(3), 53), "Wh"),
            "Max. charge rate" to fmt(m.lg(m.u16(4), 54), "W"), "Max. discharge rate" to fmt(m.lg(m.u16(5), 54), "W"),
            "State of charge" to fmt(m.lg(m.u16(11), 56), "%", 1), "Depth of discharge" to fmt(m.lg(m.u16(12), 57), "%", 1),
            "Charge status" to enum(m.u16(16), CHARGE_STATES), "Control mode" to enum(m.u16(17), CONTROL_MODES),
            "Battery type" to enum(m.u16(21), BATTERY_TYPES), "Bank state" to enum(m.u16(22), BANK_STATES),
            "Events 1" to bits(m.u32(26)), "Events 2" to bits(m.u32(28)),
            "Vendor events 1" to bits(m.u32(30)), "Vendor events 2" to bits(m.u32(32)),
            "Voltage (as reported)" to fmt(m.lg(m.u16(34), 59), "V"),
            // Not real readings on the unit (0 A while discharging, 1.2 A max), so labelled as such.
            "Current (as reported)" to fmt(m.lg(m.s16(44), 61), "A"),
            "Max. charge current (as reported)" to fmt(m.lg(m.u16(45), 62), "A"),
            "Max. discharge current (as reported)" to fmt(m.lg(m.u16(46), 62), "A"),
            "Power" to fmt(m.s16(47), "W"),
        )

        fun meterDetails(m: Model): DetailSection {
            fun kwh(o: Int) = m.lg(m.s64(o), 19)?.div(1000)
            return section(
                "Power meter (LG model 64256)",
                "Active power (+ purchase, − feed-in)" to fmt(m.lg(m.s32(2), 4), "W", 1),
                "L1 voltage" to fmt(m.lg(m.u16(5), 8), "V", 1), "L2 voltage" to fmt(m.lg(m.u16(6), 8), "V", 1),
                "L3 voltage" to fmt(m.lg(m.u16(7), 8), "V", 1), "Frequency" to fmt(m.lg(m.u16(9), 10), "Hz", 2),
                "Purchased energy (lifetime)" to fmt(kwh(11), "kWh", 1),
                "Fed-in energy (lifetime)" to fmt(kwh(15), "kWh", 1),
                "PV generation (today)" to fmt(m.lg(m.u32(20), 19)?.div(1000), "kWh", 1),
            )
        }
    }
}
