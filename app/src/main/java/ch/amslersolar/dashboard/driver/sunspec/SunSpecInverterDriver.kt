// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.sunspec

import ch.amslersolar.dashboard.driver.DeviceDriver
import ch.amslersolar.dashboard.driver.Details.enum
import ch.amslersolar.dashboard.driver.Details.fmt
import ch.amslersolar.dashboard.driver.Details.section
import ch.amslersolar.dashboard.driver.modbus.ModbusTcpClient
import ch.amslersolar.dashboard.model.BatteryState
import ch.amslersolar.dashboard.model.DetailSection
import ch.amslersolar.dashboard.model.DeviceInfo
import ch.amslersolar.dashboard.model.DeviceProblem
import ch.amslersolar.dashboard.model.DeviceProblem.Kind
import ch.amslersolar.dashboard.model.DeviceReading
import ch.amslersolar.dashboard.model.PhaseReading
import ch.amslersolar.dashboard.model.PvString
import kotlinx.coroutines.CancellationException
import java.io.IOException
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Inverters speaking SunSpec over Modbus TCP: the models are found by walking the SunSpec chain, both "float"
 * (111–113 / 211–213) and "int + SF" (101–103 / 201–203) models are read, scale factors with every poll.
 * Official model definitions: SunSpec Alliance models workbook (docs/specs/sunspec/models_workbook.xlsx).
 *
 * What differs between makes is a [Profile]: Fronius (meter under its own unit id, documented sign), SolarEdge
 * (meters chained after the inverter on the same unit id), any other SunSpec device.
 * - Solar: the PV inputs of model 160 (Multiple MPPT) when present, else the inverter model's DC power. Fronius adds
 *   two storage modules to model 160, "StCha" / "StDisCha"; battery power = discharge − charge.
 * - Battery: those modules, else model 802 (Battery Base: W with ChaSt 3 = discharging / 4 = charging, SoC, V; the
 *   model itself doesn't define the sign of W, so the direction comes from ChaSt), charge level from 124 / 802.
 * - Grid: meter model W. SunSpec doesn't define its sign: [Profile.meterImportPositive] says whether + means drawing
 *   from the grid; the device form can invert it per device.
 * - Home: inverter AC power − grid (+ export). SunSpec has no daily energy counters: the history integrates.
 * - Problems: St 7 (FAULT) of the inverter model, its Evt1 bits (0–15; Evt2 is "reserved for future use", the
 *   EvtVnd fields are vendor-defined and not decoded), and per model-160 input DCSt 7 and the DCEvt bits.
 */
open class SunSpecInverterDriver(reader: RegisterReader, unitId: Int, private val profile: Profile) : DeviceDriver {
    constructor(client: ModbusTcpClient, unitId: Int, profile: Profile) :
        this(RegisterReader { u, r, c -> client.readHoldingRegisters(u, r, c) }, unitId, profile)

    /** What differs between makes speaking SunSpec. */
    data class Profile(
        /** identify() requires this in the Common model's manufacturer; null accepts any SunSpec device. */
        val maker: String?,
        /** Unit id of a separate meter (Fronius: 200); null when meters are in the inverter's own model chain. */
        val meterUnitId: Int?,
        /** Meter W > 0 means drawing from the grid (Fronius, documented); false: > 0 means feeding in. */
        val meterImportPositive: Boolean,
        val meterTitle: String = "Energy meter (grid)",
    ) {
        fun invertedGrid(invert: Boolean) = if (invert) copy(meterImportPositive = !meterImportPositive) else this
    }

    private val inverter = SunSpecDevice(reader, unitId)
    private var meter: SunSpecDevice? = profile.meterUnitId?.let { SunSpecDevice(reader, it) }
    private val meterReader = reader
    private var static: List<DetailSection> = emptyList()
    private var polls = 0

    override suspend fun identify(): DeviceInfo {
        val common = inverter.model(1) ?: throw IOException("SunSpec Common model (1) missing")
        val maker = common.text(2, 16)
        if (profile.maker != null && !maker.contains(profile.maker, ignoreCase = true)) {
            throw IOException("Not a ${profile.maker} device ($maker)")
        }
        val version = common.text(42, 8)
        val model = common.text(18, 16).trim()
        val name = if (profile.maker != null) model.ifEmpty { profile.maker } else listOf(maker, model).filter { it.isNotEmpty() }.joinToString(" ")
        return DeviceInfo(
            model = name.ifEmpty { "SunSpec device" } + if (version.isNotEmpty()) " (firmware $version)" else "",
            serial = common.text(50, 16),
        )
    }

    override suspend fun read(): DeviceReading {
        val inv = inverter.firstModel(113, 112, 111, 103, 102, 101) ?: throw IOException("No SunSpec inverter model (101–113)")
        val mppt = optional { inverter.model(160) }
        val storage = optional { inverter.model(124) }
        val bank = optional { inverter.model(802) }
        if (polls % STATIC_REFRESH_POLLS == 0) {
            if (meter == null && profile.meterUnitId != null) meter = SunSpecDevice(meterReader, profile.meterUnitId) // added later
            static = listOfNotNull(
                optional { inverter.model(1) }?.let { commonDetails("Inverter", it) },
                optional { inverter.model(120) }?.let(::nameplateDetails),
            )
        }
        val met = if (profile.meterUnitId == null) optional { inverter.firstModel(*METER_MODELS) } else meter?.let { m ->
            try {
                m.firstModel(*METER_MODELS)
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                null.also { meter = null } // no Smart Meter at that unit id: asked again with the static data
            }
        }
        polls++
        val reading = parse(Inverter(inv.first, inv.second), mppt, storage, met?.let { Meter(it.first, it.second) }, bank, profile.meterImportPositive)
        val live = listOfNotNull(
            inverterDetails(Inverter(inv.first, inv.second)),
            mppt?.let(::mpptDetails),
            storage?.let(::storageDetails),
            bank?.let(::bankDetails),
            met?.let { meterDetails(Meter(it.first, it.second), profile.meterTitle, profile.meterImportPositive) },
        )
        return reading.copy(details = static.take(1) + live + static.drop(1))
    }

    private suspend fun <T> optional(block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: IOException) {
        null
    }

    /** A value in the float model at [fl], or in the int+SF model at [i] with its scale factor at [sf]. */
    internal class Field(val fl: Int, val i: Int, val sf: Int, val unsigned: Boolean = false, val acc32: Boolean = false)

    internal open class Values(val id: Int, val m: Model) {
        val float = id % 100 >= 10 // 111–113, 211–213
        operator fun get(f: Field): Double? = if (float) m.f32(f.fl)?.toDouble() else m.scaled(
            when {
                f.acc32 -> m.u32(f.i)
                f.unsigned -> m.u16(f.i)
                else -> m.s16(f.i)
            },
            f.sf,
        )
    }

    /** Inverter model 101–103 (int+SF) / 111–113 (float), offsets from the model's ID register. */
    internal class Inverter(id: Int, m: Model) : Values(id, m) {
        val state: Int? get() = m.u16(if (float) 48 else 38)
        val vendorState: Int? get() = m.u16(if (float) 49 else 39)
        val evt1: Long? get() = m.u32(if (float) 50 else 40)
    }

    /** Meter model 201–203 (int+SF) / 211–213 (float). */
    internal class Meter(id: Int, m: Model) : Values(id, m)

    internal companion object {
        private const val STATIC_REFRESH_POLLS = 12
        val METER_MODELS = intArrayOf(213, 212, 211, 214, 203, 202, 201, 204)

        val INV_A = Field(2, 2, 6, unsigned = true)
        val INV_PH_A = listOf(Field(4, 3, 6, true), Field(6, 4, 6, true), Field(8, 5, 6, true))
        val INV_PH_V = listOf(Field(16, 10, 13, true), Field(18, 11, 13, true), Field(20, 12, 13, true))
        val INV_W = Field(22, 14, 15)
        val INV_HZ = Field(24, 16, 17, unsigned = true)
        val INV_VA = Field(26, 18, 19)
        val INV_VAR = Field(28, 20, 21)
        val INV_PF = Field(30, 22, 23)
        val INV_WH = Field(32, 24, 26, acc32 = true)
        val INV_DCW = Field(38, 31, 32)
        val INV_TMP_CAB = Field(40, 33, 37)
        val INV_TMP_SNK = Field(42, 34, 37)

        val MET_PH_A = listOf(Field(4, 3, 6), Field(6, 4, 6), Field(8, 5, 6))
        val MET_PH_V = listOf(Field(12, 8, 15), Field(14, 9, 15), Field(16, 10, 15))
        val MET_HZ = Field(26, 16, 17)
        val MET_W = Field(28, 18, 22)
        val MET_PH_W = listOf(Field(30, 19, 22), Field(32, 20, 22), Field(34, 21, 22))
        val MET_VA = Field(36, 23, 27)
        val MET_VAR = Field(44, 28, 32)
        val MET_PF = Field(52, 33, 37)
        val MET_WH_EXP = Field(60, 38, 54, acc32 = true)
        val MET_WH_IMP = Field(68, 46, 54, acc32 = true)

        private val STATES = mapOf(
            1 to "Off", 2 to "Sleeping", 3 to "Starting", 4 to "Producing", 5 to "Throttled", 6 to "Shutting down",
            7 to "Fault", 8 to "Standby",
        )
        private val CHARGE_STATES = mapOf(1 to "Off", 2 to "Empty", 3 to "Discharging", 4 to "Charging", 5 to "Full", 6 to "Holding", 7 to "Testing")
        private val MPPT_STATES = mapOf(1 to "Off", 2 to "Sleeping", 3 to "Starting", 4 to "MPPT", 5 to "Throttled", 6 to "Shutting down", 7 to "Fault", 8 to "Standby", 9 to "Test", 10 to "Reserved")

        /** One DC input of model 160. */
        data class Module(
            val index: Int,
            val name: String,
            val a: Double?,
            val v: Double?,
            val w: Double?,
            val wh: Double?,
            val tmp: Int?,
            val state: Int?,
            val events: Long? = null,
        ) {
            val charging get() = name.contains("StCha", ignoreCase = true)
            val discharging get() = name.contains("StDisCha", ignoreCase = true)
            val storage get() = charging || discharging
        }

        fun modules(m: Model): List<Module> {
            val n = m.u16(8) ?: 0
            return (0 until n).mapNotNull { k ->
                val o = 10 + 20 * k
                if (!m.has(o + 19)) return@mapNotNull null
                Module(
                    index = m.u16(o) ?: (k + 1),
                    name = m.text(o + 1, 8),
                    a = m.scaled(m.u16(o + 9), 2),
                    v = m.scaled(m.u16(o + 10), 3),
                    w = m.scaled(m.u16(o + 11), 4),
                    wh = m.scaled(m.u32(o + 12), 5),
                    tmp = m.s16(o + 16),
                    state = m.u16(o + 17),
                    events = m.u32(o + 18),
                )
            }
        }

        /** The battery from model 124 (storage), 802 (battery base) or Fronius's storage modules in 160; null without. */
        fun battery(storage: Model?, bank: Model?, modules: List<Module> = emptyList()): BatteryState? {
            if (storage == null && bank == null && modules.none { it.storage }) return null
            val chaSt = storage?.u16(11) ?: bank?.u16(16)
            val power = if (modules.any { it.storage }) {
                val charge = modules.firstOrNull { it.charging }?.w ?: 0.0
                val discharge = modules.firstOrNull { it.discharging }?.w ?: 0.0
                discharge - charge
            } else {
                // Model 802: W has no defined sign; the direction comes from ChaSt.
                val magnitude = bank?.let { b -> b.scaled(b.s16(47), 63) }?.let(::abs) ?: 0.0
                when (chaSt) {
                    3 -> magnitude
                    4 -> -magnitude
                    else -> 0.0
                }
            }
            return BatteryState(
                powerW = power.roundToInt(),
                socPercent = (storage?.let { s -> s.scaled(s.u16(8), 22) } ?: bank?.let { b -> b.scaled(b.u16(11), 56) })
                    ?.roundToInt()?.takeIf { it in 0..100 },
                voltageV = storage?.let { s -> s.scaled(s.u16(10), 24) } ?: bank?.let { b -> b.scaled(b.u16(34), 59) },
                mode = CHARGE_STATES[chaSt] ?: when {
                    power > 0 -> "Discharging"
                    power < 0 -> "Charging"
                    else -> "Idle"
                },
            )
        }

        /** A meter on its own: grid power ([importPositive]: + means drawing), phases and frequency. */
        fun parseMeter(meter: Meter, importPositive: Boolean = true): DeviceReading {
            val hz = meter[MET_HZ] ?: 0.0
            val sign = if (importPositive) -1 else 1
            return DeviceReading(
                gridPowerW = meter[MET_W]?.let { (sign * it).roundToInt() },
                phases = (0 until 3).mapNotNull { i ->
                    val v = meter[MET_PH_V[i]]?.takeIf { it > 0 } ?: return@mapNotNull null
                    PhaseReading("L${i + 1}", v, meter[MET_PH_A[i]], hz, meter[MET_PH_W[i]]?.let { (sign * it).roundToInt() })
                },
            )
        }

        fun parse(inv: Inverter, mppt: Model?, storage: Model?, meter: Meter?, bank: Model? = null, meterImportPositive: Boolean = true): DeviceReading {
            val modules = mppt?.let(::modules).orEmpty()
            val pvModules = modules.filterNot { it.storage }
            val acW = inv[INV_W]
            val pv = if (pvModules.isNotEmpty()) pvModules.sumOf { it.w ?: 0.0 } else inv[INV_DCW]

            val battery = battery(storage, bank, modules)

            val grid = meter?.get(MET_W)?.let { if (meterImportPositive) -it.roundToInt() else it.roundToInt() }
            val load = if (acW != null && grid != null) maxOf(0, (acW - grid).roundToInt()) else null
            val hz = inv[INV_HZ] ?: 0.0
            val phases = (0 until 3).mapNotNull { i ->
                val v = inv[INV_PH_V[i]]?.takeIf { it > 0 } ?: return@mapNotNull null
                PhaseReading("L${i + 1}", v, inv[INV_PH_A[i]], hz, null)
            }

            return DeviceReading(
                pvPowerW = pv?.roundToInt(),
                pvStrings = pvModules.mapNotNull { m -> m.v?.let { PvString(m.index, it, m.a ?: 0.0, m.w?.roundToInt()) } },
                battery = battery,
                gridPowerW = grid,
                phases = phases,
                loadPowerW = load,
                temperatureC = inv[INV_TMP_CAB] ?: inv[INV_TMP_SNK],
                status = inv.state?.let { STATES[it] ?: "State $it" },
                lifetimePvKwh = inv[INV_WH]?.div(1000), // AC energy of the inverter
                problems = problems(inv, modules),
            )
        }

        /** Inverter model Evt1 bits (SunSpec models workbook, models 101–103 / 111–113). */
        private val EVT1 = mapOf(
            0 to (Kind.FAULT to "Ground fault"),
            1 to (Kind.FAULT to "DC over-voltage"),
            2 to (Kind.FAULT to "AC disconnect open"),
            3 to (Kind.FAULT to "DC disconnect open"),
            4 to (Kind.MAINS_LOST to "Grid disconnected"),
            5 to (Kind.WARNING to "Cabinet open"),
            6 to (Kind.WARNING to "Manual shutdown"),
            7 to (Kind.WARNING to "Over-temperature"),
            8 to (Kind.MAINS_LOST to "Grid over-frequency"),
            9 to (Kind.MAINS_LOST to "Grid under-frequency"),
            10 to (Kind.MAINS_LOST to "Grid over-voltage"),
            11 to (Kind.MAINS_LOST to "Grid under-voltage"),
            12 to (Kind.FAULT to "Blown string fuse"),
            13 to (Kind.WARNING to "Under-temperature"),
            14 to (Kind.FAULT to "Memory loss"),
            15 to (Kind.FAULT to "Hardware test failure"),
        )

        /** Model 160 DCEvt bits (workbook); reserved bits and INPUT_UNDER_VOLTAGE (normal at dusk) are left out. */
        private val DC_EVT = mapOf(
            0 to (Kind.FAULT to "ground fault"),
            1 to (Kind.FAULT to "input over-voltage"),
            3 to (Kind.FAULT to "DC disconnect open"),
            5 to (Kind.WARNING to "cabinet open"),
            6 to (Kind.WARNING to "manual shutdown"),
            7 to (Kind.WARNING to "over-temperature"),
            12 to (Kind.FAULT to "blown fuse"),
            13 to (Kind.WARNING to "under-temperature"),
            14 to (Kind.FAULT to "memory loss"),
            15 to (Kind.FAULT to "arc detected"),
            20 to (Kind.FAULT to "test failed"),
            22 to (Kind.FAULT to "input over-current"),
        )

        private const val ST_FAULT = 7

        fun problems(inv: Inverter, modules: List<Module>): List<DeviceProblem> {
            val out = mutableListOf<DeviceProblem>()
            if (inv.state == ST_FAULT) {
                out += DeviceProblem(Kind.FAULT, inv.vendorState?.takeIf { it != 0 }?.let { "Inverter fault (vendor state $it)" } ?: "Inverter fault (St $ST_FAULT)")
            }
            val evt1 = inv.evt1 ?: 0L
            for ((bit, p) in EVT1) if (evt1 and (1L shl bit) != 0L) out += DeviceProblem(p.first, "${p.second} (Evt1 bit $bit)")
            for (m in modules) {
                val n = m.name.ifEmpty { "Input ${m.index}" }
                if (m.state == ST_FAULT) out += DeviceProblem(Kind.FAULT, "$n: fault (DCSt $ST_FAULT)")
                val evt = m.events ?: 0L
                for ((bit, p) in DC_EVT) if (evt and (1L shl bit) != 0L) out += DeviceProblem(p.first, "$n: ${p.second} (DCEvt bit $bit)")
            }
            return out.distinct()
        }

        private fun w(v: Double?) = fmt(v, "W", 0)

        fun commonDetails(title: String, c: Model) = section(
            title,
            "Manufacturer" to c.text(2, 16),
            "Model" to c.text(18, 16),
            "Options" to c.text(34, 8),
            "Firmware" to c.text(42, 8),
            "Serial number" to c.text(50, 16),
            "Modbus device address" to c.u16(66)?.toString(),
        )

        /** Model 120. The spec's offsets (WRtg 4, ARtg 13, …) count from 1; these count from the ID register. */
        fun nameplateDetails(n: Model) = section(
            "Nameplate",
            "Rated power" to w(n.scaled(n.u16(3), 4)),
            "Rated apparent power" to fmt(n.scaled(n.u16(5), 6), "VA", 0),
            "Rated current" to fmt(n.scaled(n.u16(12), 13), "A", 1),
            "Storage capacity" to fmt(n.scaled(n.u16(19), 20)?.div(1000), "kWh", 1),
            "Max charge rate" to w(n.scaled(n.u16(23), 24)),
            "Max discharge rate" to w(n.scaled(n.u16(25), 26)),
        )

        fun inverterDetails(inv: Inverter) = section(
            "Inverter (AC)",
            "SunSpec model" to "${inv.id} (${if (inv.float) "float" else "int + SF"})",
            "State" to enum(inv.state, STATES),
            "Vendor state" to inv.vendorState?.toString(),
            "Active power" to w(inv[INV_W]),
            "Apparent power" to fmt(inv[INV_VA], "VA", 0),
            "Reactive power" to fmt(inv[INV_VAR], "var", 0),
            "Power factor" to fmt(inv[INV_PF], "%", 1),
            "Frequency" to fmt(inv[INV_HZ], "Hz", 2),
            "Current" to fmt(inv[INV_A], "A", 2),
            *(0 until 3).flatMap { i ->
                listOf("L${i + 1} voltage" to fmt(inv[INV_PH_V[i]], "V", 1), "L${i + 1} current" to fmt(inv[INV_PH_A[i]], "A", 2))
            }.toTypedArray(),
            "DC power" to w(inv[INV_DCW]),
            "Cabinet temperature" to fmt(inv[INV_TMP_CAB], "°C", 1),
            "Heat sink temperature" to fmt(inv[INV_TMP_SNK], "°C", 1),
            "AC energy (lifetime)" to fmt(inv[INV_WH]?.div(1000), "kWh", 1),
        )

        fun mpptDetails(m: Model) = DetailSection(
            "DC inputs (MPPT)",
            modules(m).flatMap { mod ->
                val n = mod.name.ifEmpty { "Input ${mod.index}" }
                listOfNotNull(
                    fmt(mod.w, "W", 0)?.let { "$n power" to it },
                    fmt(mod.v, "V", 1)?.let { "$n voltage" to it },
                    fmt(mod.a, "A", 2)?.let { "$n current" to it },
                    fmt(mod.wh?.div(1000), "kWh", 1)?.let { "$n energy (lifetime)" to it },
                    enum(mod.state, MPPT_STATES)?.let { "$n state" to it },
                )
            },
        )

        fun storageDetails(s: Model) = section(
            "Battery",
            "State of charge" to fmt(s.scaled(s.u16(8), 22), "%", 1),
            "State" to enum(s.u16(11), CHARGE_STATES),
            "Battery voltage" to fmt(s.scaled(s.u16(10), 24), "V", 1),
            "Max charge/discharge power" to w(s.scaled(s.u16(2), 18)),
            "Minimum reserve" to fmt(s.scaled(s.u16(7), 21), "%", 0),
            "Available energy" to fmt(s.scaled(s.u16(9), 23)?.div(1000), "kWh", 2),
            "Control mode (StorCtl_Mod)" to s.u16(5)?.toString(),
            "Discharge limit (OutWRte)" to fmt(s.scaled(s.s16(12), 25), "%", 0),
            "Charge limit (InWRte)" to fmt(s.scaled(s.s16(13), 25), "%", 0),
            "Charging from grid" to s.u16(17)?.let { if (it == 1) "Allowed" else "Not allowed" },
        )

        /** Model 802 (Battery Base); offsets from the SunSpec models workbook. */
        fun bankDetails(b: Model) = section(
            "Battery bank (SunSpec 802)",
            "State of charge" to fmt(b.scaled(b.u16(11), 56), "%", 1),
            "State of health" to fmt(b.scaled(b.u16(13), 58), "%", 0),
            "Charge status" to enum(b.u16(16), CHARGE_STATES),
            "Power (magnitude)" to w(b.scaled(b.s16(47), 63)?.let(::abs)),
            "Voltage" to fmt(b.scaled(b.u16(34), 59), "V", 1),
            "Current" to fmt(b.scaled(b.s16(44), 61), "A", 1),
            "Capacity" to fmt(b.scaled(b.u16(3), 53)?.div(1000), "kWh", 1),
            "Max charge rate" to w(b.scaled(b.u16(4), 54)),
            "Max discharge rate" to w(b.scaled(b.u16(5), 54)),
        )

        fun meterDetails(m: Meter, title: String = "Energy meter (grid)", importPositive: Boolean = true) = section(
            title,
            "SunSpec model" to "${m.id} (${if (m.float) "float" else "int + SF"})",
            (if (importPositive) "Power (+ from grid)" else "Power (+ to grid)") to w(m[MET_W]),
            *(0 until 3).flatMap { i ->
                listOf(
                    "L${i + 1} voltage" to fmt(m[MET_PH_V[i]], "V", 1),
                    "L${i + 1} current" to fmt(m[MET_PH_A[i]], "A", 2),
                    "L${i + 1} power" to w(m[MET_PH_W[i]]),
                )
            }.toTypedArray(),
            "Apparent power" to fmt(m[MET_VA], "VA", 0),
            "Reactive power" to fmt(m[MET_VAR], "var", 0),
            "Power factor" to fmt(m[MET_PF], "%", 1),
            "Frequency" to fmt(m[MET_HZ], "Hz", 2),
            "Imported (lifetime)" to fmt(m[MET_WH_IMP]?.div(1000), "kWh", 1),
            "Exported (lifetime)" to fmt(m[MET_WH_EXP]?.div(1000), "kWh", 1),
        )
    }
}
