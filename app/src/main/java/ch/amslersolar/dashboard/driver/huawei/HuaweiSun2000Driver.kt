// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.huawei

import ch.amslersolar.dashboard.driver.DeviceDriver
import ch.amslersolar.dashboard.driver.Details.bits
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
import ch.amslersolar.dashboard.model.EnergyTotals
import ch.amslersolar.dashboard.model.PhaseReading
import ch.amslersolar.dashboard.model.PvString
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Huawei SUN2000 inverters (SUN2000MA residential, incl. hybrid with LUNA2000 battery) over Modbus TCP.
 *
 * Register map: "SUN2000MA V200R024C00SPC106 Modbus Interface Definitions (V3.0)" (docs/specs/huawei/).
 * - Port 502. Over TCP, unit 0 is the directly connected device (the inverter's own WLAN/FE port); devices behind
 *   an SDongle / SmartLogger have their own address, usually 1.
 * - At most 125 registers per read, and reads must not touch undefined registers (exception 2), so the driver
 *   reads only contiguous blocks of defined registers. The inverter ignores requests sent straight after
 *   connecting, hence a short connect delay.
 * - Battery: 37765 charge/discharge power, > 0 charging (as stated for the per-unit registers) → our sign is
 *   the opposite. Grid meter: 37113 active power; the spec doesn't state its sign; like the community
 *   `huawei-solar` library it's taken as > 0 export, with 37119 exported and 37121 imported energy.
 *   To confirm on a real installation.
 * - Device status codes (32089) refer to a table that isn't in this document; the codes below are Huawei's
 *   published list as used by `huawei-solar`. Unknown codes are shown in hex.
 */
class HuaweiSun2000Driver(private val reader: RegisterReader, private val unitId: Int) : DeviceDriver {
    constructor(client: ModbusTcpClient, unitId: Int) :
        this(RegisterReader { u, r, c -> client.readHoldingRegisters(u, r, c) }, unitId) {
        client.connectDelayMs = maxOf(client.connectDelayMs, CONNECT_DELAY_MS)
    }

    private val unsupported = mutableSetOf<Int>()
    private var identity: Registers? = null
    private var detail = Registers(emptyList())
    private var polls = 0

    private suspend fun read(segments: List<Pair<Int, Int>>, optional: Boolean): Registers {
        val blocks = mutableListOf<RegisterBlock>()
        for ((start, count) in segments) {
            if (start in unsupported) continue
            try {
                blocks += reader.read(unitId, start, count)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ModbusException) {
                if (!optional) throw e
                unsupported += start // e.g. no battery or meter on this installation
            } catch (e: IOException) {
                if (!optional) throw e
            }
        }
        return Registers(blocks)
    }

    override suspend fun identify(): DeviceInfo {
        val r = read(IDENTITY, optional = false)
        return DeviceInfo(model = r.text(30000, 15), serial = r.text(30015, 10))
    }

    override suspend fun read(): DeviceReading {
        if (identity == null || polls % STATIC_REFRESH_POLLS == 0) identity = read(IDENTITY, optional = true)
        val live = read(LIVE, optional = false)
        val meter = read(METER, optional = true)
        val battery = read(BATTERY, optional = true)
        if (polls % DETAIL_REFRESH_POLLS == 0) detail = read(DETAIL, optional = true)
        polls++
        val ident = identity ?: Registers(emptyList())
        return parse(ident, live, meter, battery)
            .copy(details = details(ident, live, meter, battery, detail), problems = problems(live, battery, detail))
    }

    /** Several register blocks read in one poll, addressed by absolute register number. */
    class Registers(private val blocks: List<RegisterBlock>) {
        private fun block(r: Int, size: Int = 1) = blocks.firstOrNull { it.start <= r && r + size <= it.start + it.count }

        fun has(r: Int, size: Int = 1) = block(r, size) != null
        fun u16(r: Int): Int? = block(r)?.u16(r)
        fun s16(r: Int): Int? = block(r)?.s16(r)
        fun u32(r: Int): Long? = block(r, 2)?.u32(r)
        fun s32(r: Int): Int? = block(r, 2)?.s32(r)
        fun text(r: Int, size: Int): String = block(r, size)?.ascii(r, size) ?: ""
        fun scaled(value: Number?, gain: Int): Double? = value?.toDouble()?.div(gain)
    }

    companion object {
        const val CONNECT_DELAY_MS = 1_000L
        private const val STATIC_REFRESH_POLLS = 60 // identity and ratings: about every 5 minutes
        private const val DETAIL_REFRESH_POLLS = 12 // alarms and per-unit battery data: about once a minute

        // (start, count): contiguous runs of defined registers, checked against the spec's tables.
        val IDENTITY = listOf(30000 to 35, 30070 to 17)
        val LIVE = listOf(32016 to 12, 32064 to 31, 32106 to 14)
        val METER = listOf(37100 to 39)
        val BATTERY = listOf(37758 to 3, 37762 to 5, 37780 to 8)
        val DETAIL = listOf(32008 to 4, 37000 to 5, 37006 to 5, 37014 to 5, 37021 to 2, 37025 to 1, 37046 to 4, 37066 to 4)

        val DEVICE_STATUS = mapOf(
            0x0000 to "Standby: initializing", 0x0001 to "Standby: detecting insulation resistance",
            0x0002 to "Standby: detecting irradiation", 0x0003 to "Standby: grid detecting", 0x0100 to "Starting",
            0x0200 to "On-grid", 0x0201 to "On-grid: power limited", 0x0202 to "On-grid: self-derating",
            0x0203 to "Off-grid", 0x0300 to "Shutdown: fault", 0x0301 to "Shutdown: command", 0x0302 to "Shutdown: OVGR",
            0x0303 to "Shutdown: communication disconnected", 0x0304 to "Shutdown: power limited",
            0x0305 to "Shutdown: manual startup required", 0x0306 to "Shutdown: DC switches disconnected",
            0x0307 to "Shutdown: rapid cutoff", 0x0308 to "Shutdown: input underpower",
            0x0401 to "Grid scheduling: cosφ-P curve", 0x0402 to "Grid scheduling: Q-U curve",
            0x0403 to "Grid scheduling: PF-U curve", 0x0404 to "Grid scheduling: dry contact",
            0x0405 to "Grid scheduling: Q-P curve", 0x0500 to "Spot-check ready", 0x0501 to "Spot-checking",
            0x0600 to "Inspecting", 0x0700 to "AFCI self-check", 0x0800 to "I-V scanning", 0x0900 to "DC input detection",
            0x0A00 to "Running: off-grid charging", 0xA000 to "Standby: no irradiation",
        )
        private val BATTERY_STATUS = mapOf(0 to "Offline", 1 to "Standby", 2 to "Running", 3 to "Fault", 4 to "Sleep mode")
        private val BATTERY_WORKING_MODES = mapOf(
            0 to "None", 1 to "Forcible charge/discharge", 2 to "Time of use", 3 to "Fixed charge/discharge",
            4 to "Maximise self-consumption", 5 to "Fully fed to grid",
        )
        private val METER_STATUS = mapOf(0 to "Offline", 1 to "Normal")
        private val PHASES = listOf("L1", "L2", "L3")

        private fun hex(code: Int) = "0x" + code.toString(16).uppercase().padStart(4, '0')

        fun parse(ident: Registers, live: Registers, meter: Registers, bat: Registers): DeviceReading {
            val nStrings = ident.u16(30071) ?: 6
            val strings = (0 until minOf(nStrings, 6)).mapNotNull { i -> // this map defines PV1–PV6
                val v = live.scaled(live.s16(32016 + 2 * i), 10)
                val a = live.scaled(live.s16(32017 + 2 * i), 100)
                if (v != null && (v > 0 || (a ?: 0.0) > 0)) PvString(i + 1, v, a ?: 0.0, null) else null // no per-string power
            }
            val pvKw = live.scaled(live.s32(32064), 1000)

            val battery = if (bat.has(37762) && (bat.u32(37758) ?: 0L) > 0) { // rated capacity 0 → no battery
                val power = -(bat.s32(37765) ?: 0) // Huawei: > 0 charging
                val status = bat.u16(37762)
                val mode = when {
                    power > 0 -> "Discharging"
                    power < 0 -> "Charging"
                    else -> BATTERY_STATUS[status] ?: "Standby"
                }
                BatteryState(power, bat.scaled(bat.u16(37760), 10)?.roundToInt(), bat.scaled(bat.u16(37763), 10), mode)
            } else null

            var grid: Int? = null
            var phases = emptyList<PhaseReading>()
            if (meter.has(37100) && meter.u16(37100) == 1) { // meter online
                grid = meter.s32(37113) // taken as > 0 export
                val hz = meter.scaled(meter.s16(37118), 100) ?: 0.0
                phases = PHASES.mapIndexedNotNull { i, name ->
                    meter.scaled(meter.s32(37101 + 2 * i), 10)?.takeIf { it > 0 }?.let { v ->
                        PhaseReading(name, v, meter.scaled(meter.s32(37107 + 2 * i), 100)?.let(::abs), hz, meter.s32(37132 + 2 * i))
                    }
                }
            }

            val status = live.u16(32089)?.let { DEVICE_STATUS[it] ?: "Status ${hex(it)}" }
            return DeviceReading(
                pvPowerW = pvKw?.let { (it * 1000).roundToInt() },
                pvStrings = strings,
                battery = battery,
                gridPowerW = grid,
                phases = phases,
                temperatureC = live.scaled(live.s16(32087), 10),
                status = status,
                today = EnergyTotals(pvKwh = live.scaled(live.u32(32114), 100)),
                lifetimePvKwh = live.scaled(live.u32(32106), 100),
            )
        }

        private val F = DeviceProblem.Kind.FAULT
        private val W = DeviceProblem.Kind.WARNING

        /**
         * Table 5-1 "Alarm information": (register, bit) → name, alarm ID, kind (level Major → fault, Minor and
         * Warning → warning). Alarm 1 bit 7 "Grid Loss" (2032) is the mains loss.
         */
        private val ALARMS: Map<Pair<Int, Int>, Triple<String, Int, DeviceProblem.Kind>> = mapOf(
            (32008 to 0) to Triple("High string input voltage", 2001, F), (32008 to 1) to Triple("DC arc fault", 2002, F),
            (32008 to 2) to Triple("String reverse connection", 2011, F), (32008 to 3) to Triple("String current backfeed", 2012, W),
            (32008 to 4) to Triple("Abnormal string power", 2013, W), (32008 to 5) to Triple("AFCI self-check failed", 2021, F),
            (32008 to 6) to Triple("Phase wire short-circuited to PE", 2031, F),
            (32008 to 7) to Triple("Grid loss", 2032, DeviceProblem.Kind.MAINS_LOST),
            (32008 to 8) to Triple("Grid undervoltage", 2033, F), (32008 to 9) to Triple("Grid overvoltage", 2034, F),
            (32008 to 10) to Triple("Grid voltage imbalance", 2035, F), (32008 to 11) to Triple("Grid overfrequency", 2036, F),
            (32008 to 12) to Triple("Grid underfrequency", 2037, F), (32008 to 13) to Triple("Unstable grid frequency", 2038, F),
            (32008 to 14) to Triple("Output overcurrent", 2039, F), (32008 to 15) to Triple("Output DC component overhigh", 2040, F),
            (32009 to 0) to Triple("Abnormal residual current", 2051, F), (32009 to 1) to Triple("Abnormal grounding", 2061, F),
            (32009 to 2) to Triple("Low insulation resistance", 2062, F), (32009 to 3) to Triple("Overtemperature", 2063, W),
            (32009 to 4) to Triple("Device fault", 2064, F), (32009 to 5) to Triple("Upgrade failed or version mismatch", 2065, W),
            (32009 to 6) to Triple("License expired", 2066, W), (32009 to 7) to Triple("Faulty monitoring unit", 61440, W),
            (32009 to 8) to Triple("Faulty power collector", 2067, F), (32009 to 9) to Triple("Battery abnormal", 2068, W),
            (32009 to 10) to Triple("Active islanding", 2070, F), (32009 to 11) to Triple("Passive islanding", 2071, F),
            (32009 to 12) to Triple("Transient AC overvoltage", 2072, F), (32009 to 13) to Triple("Peripheral port short circuit", 2075, W),
            (32009 to 14) to Triple("Churn output overload", 2077, F), (32009 to 15) to Triple("Abnormal PV module configuration", 2080, F),
            (32010 to 0) to Triple("Optimizer fault", 2081, W), (32010 to 1) to Triple("Built-in PID operation abnormal", 2085, W),
            (32010 to 2) to Triple("High input string voltage to ground", 2014, F), (32010 to 3) to Triple("External fan abnormal", 2086, F),
            (32010 to 4) to Triple("Battery reverse connection", 2069, F),
            (32010 to 5) to Triple("On-grid/off-grid controller abnormal", 2082, F), (32010 to 6) to Triple("PV string loss", 2015, W),
            (32010 to 7) to Triple("Internal fan abnormal", 2087, F), (32010 to 8) to Triple("DC protection unit abnormal", 2088, F),
        )

        /**
         * Alarm 1–3 (32008–32010, table 5-1; refreshed with the details), device status 0x0300 (shutdown: fault),
         * 0x0202 (self-derating), 0x0203 / 0x0A00 (off-grid), battery status 3 (fault, 37762) and the battery unit's
         * fault ID (37014, no table in the document).
         */
        fun problems(live: Registers, bat: Registers, alarms: Registers): List<DeviceProblem> {
            val out = mutableListOf<DeviceProblem>()
            for (reg in 32008..32010) {
                val v = alarms.u16(reg) ?: continue
                for (bit in 0 until 16) {
                    if ((v shr bit) and 1 == 0) continue
                    val (name, id, kind) = ALARMS[reg to bit] ?: Triple("Alarm ${reg - 32007} bit $bit", null, W)
                    out += DeviceProblem(kind, if (id != null) "$name ($id)" else name)
                }
            }
            fun none(kind: DeviceProblem.Kind) = out.none { it.kind == kind }
            val status = live.u16(32089)
            when (status) {
                0x0300 -> if (none(F)) out += DeviceProblem(F, "Shutdown: fault (0x0300)")
                0x0202 -> out += DeviceProblem(W, "Self-derating (0x0202)")
                0x0203, 0x0A00 -> out += DeviceProblem(DeviceProblem.Kind.BACKUP_MODE, "${DEVICE_STATUS[status]} (${hex(status)})")
            }
            if ((bat.u32(37758) ?: 0L) > 0) {
                val faultId = alarms.u16(37014) ?: 0
                when {
                    faultId != 0 -> out += DeviceProblem(F, "Battery fault (ID $faultId)")
                    bat.u16(37762) == 3 -> out += DeviceProblem(F, "Battery fault (status 3)")
                }
            }
            return out
        }

        private fun time(epoch: Long?): String? =
            epoch?.takeIf { it > 0 }?.let { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(it * 1000)) }

        fun details(i: Registers, l: Registers, m: Registers, b: Registers, e: Registers): List<DetailSection> {
            val n = minOf(i.u16(30071) ?: 6, 6)
            val out = mutableListOf(
                section(
                    "Identity (30000–30086)",
                    "Model" to i.text(30000, 15), "Serial number" to i.text(30015, 10), "Product code" to i.text(30025, 10),
                    "Model ID" to fmt(i.u16(30070)), "PV strings" to fmt(i.u16(30071)), "MPPTs" to fmt(i.u16(30072)),
                    "Rated power" to fmt(i.scaled(i.u32(30073), 1000), "kW", 3),
                    "Max. active power" to fmt(i.scaled(i.u32(30075), 1000), "kW", 3),
                    "Max. apparent power" to fmt(i.scaled(i.u32(30077), 1000), "kVA", 3),
                    "Max. active power (real)" to fmt(i.scaled(i.u32(30083), 1000), "kW", 3),
                    "Max. apparent power (real)" to fmt(i.scaled(i.u32(30085), 1000), "kVA", 3),
                ),
                section(
                    "PV inputs (32016–32027)",
                    *(0 until n).flatMap { k ->
                        listOf(
                            "PV${k + 1} voltage" to fmt(l.scaled(l.s16(32016 + 2 * k), 10), "V", 1),
                            "PV${k + 1} current" to fmt(l.scaled(l.s16(32017 + 2 * k), 100), "A", 2),
                        )
                    }.toTypedArray(),
                    "Total input power" to fmt(l.scaled(l.s32(32064), 1000), "kW", 3),
                ),
                section(
                    "Inverter output (32066–32094)",
                    "Line voltage L1-L2 (or grid voltage)" to fmt(l.scaled(l.u16(32066), 10), "V", 1),
                    "Line voltage L2-L3" to fmt(l.scaled(l.u16(32067), 10), "V", 1),
                    "Line voltage L3-L1" to fmt(l.scaled(l.u16(32068), 10), "V", 1),
                    *PHASES.mapIndexed { k, p -> "$p voltage" to fmt(l.scaled(l.u16(32069 + k), 10), "V", 1) }.toTypedArray(),
                    *PHASES.mapIndexed { k, p -> "$p current" to fmt(l.scaled(l.s32(32072 + 2 * k), 1000), "A", 3) }.toTypedArray(),
                    "Peak active power today" to fmt(l.scaled(l.s32(32078), 1000), "kW", 3),
                    "Active power" to fmt(l.scaled(l.s32(32080), 1000), "kW", 3),
                    "Reactive power" to fmt(l.scaled(l.s32(32082), 1000), "kvar", 3),
                    "Power factor" to fmt(l.scaled(l.s16(32084), 1000), "", 3),
                    "Grid frequency" to fmt(l.scaled(l.u16(32085), 100), "Hz", 2),
                    "Efficiency" to fmt(l.scaled(l.u16(32086), 100), "%", 2),
                    "Internal temperature" to fmt(l.scaled(l.s16(32087), 10), "°C", 1),
                    "Insulation resistance" to fmt(l.scaled(l.u16(32088), 1000), "MΩ", 3),
                    "Device status" to l.u16(32089)?.let { "${DEVICE_STATUS[it] ?: "unknown"} (${hex(it)})" },
                    "Error code" to fmt(l.u16(32090)),
                    "Startup time" to time(l.u32(32091)), "Shutdown time" to time(l.u32(32093)),
                ),
                section(
                    "Energy (32106–32119)",
                    "Total yield" to fmt(l.scaled(l.u32(32106), 100), "kWh", 2),
                    "Total DC input energy" to fmt(l.scaled(l.u32(32108), 100), "kWh", 2),
                    "Yield this hour" to fmt(l.scaled(l.u32(32112), 100), "kWh", 2),
                    "Yield today" to fmt(l.scaled(l.u32(32114), 100), "kWh", 2),
                    "Yield this month" to fmt(l.scaled(l.u32(32116), 100), "kWh", 2),
                    "Yield this year" to fmt(l.scaled(l.u32(32118), 100), "kWh", 2),
                ),
            )
            if (m.has(37100)) out += section(
                "Power meter (37100–37138)",
                "Status" to enum(m.u16(37100), METER_STATUS), "Meter type" to fmt(m.u16(37125)),
                *PHASES.mapIndexed { k, p -> "$p voltage" to fmt(m.scaled(m.s32(37101 + 2 * k), 10), "V", 1) }.toTypedArray(),
                *PHASES.mapIndexed { k, p -> "$p current" to fmt(m.scaled(m.s32(37107 + 2 * k), 100), "A", 2) }.toTypedArray(),
                "Active power (+ export)" to fmt(m.s32(37113), "W"), "Reactive power" to fmt(m.s32(37115), "var"),
                "Power factor" to fmt(m.scaled(m.s16(37117), 1000), "", 3),
                "Frequency" to fmt(m.scaled(m.s16(37118), 100), "Hz", 2),
                "Exported energy (positive active)" to fmt(m.scaled(m.s32(37119), 100), "kWh", 2),
                "Imported energy (reverse active)" to fmt(m.scaled(m.s32(37121), 100), "kWh", 2),
                "Reactive energy" to fmt(m.scaled(m.s32(37123), 100), "kvarh", 2),
                *listOf("L1-L2", "L2-L3", "L3-L1").mapIndexed { k, p -> "Line voltage $p" to fmt(m.scaled(m.s32(37126 + 2 * k), 10), "V", 1) }.toTypedArray(),
                *PHASES.mapIndexed { k, p -> "$p active power" to fmt(m.s32(37132 + 2 * k), "W") }.toTypedArray(),
            )
            if (b.has(37762)) out += section(
                "Battery (37758–37787)",
                "Rated capacity" to fmt(b.u32(37758), "Wh"), "State of charge" to fmt(b.scaled(b.u16(37760), 10), "%", 1),
                "Status" to enum(b.u16(37762), BATTERY_STATUS),
                "Bus voltage" to fmt(b.scaled(b.u16(37763), 10), "V", 1),
                "Bus current" to fmt(b.scaled(b.s16(37764), 10), "A", 1),
                "Charge/discharge power (+ charging)" to fmt(b.s32(37765), "W"),
                "Charged total" to fmt(b.scaled(b.u32(37780), 100), "kWh", 2),
                "Discharged total" to fmt(b.scaled(b.u32(37782), 100), "kWh", 2),
                "Charged today" to fmt(b.scaled(b.u32(37784), 100), "kWh", 2),
                "Discharged today" to fmt(b.scaled(b.u32(37786), 100), "kWh", 2),
            )
            if (e.has(37000)) out += section(
                "Battery unit 1 (37000–37069)",
                "Status" to enum(e.u16(37000), BATTERY_STATUS),
                "Charge/discharge power (+ charging)" to fmt(e.s32(37001), "W"),
                "Bus voltage" to fmt(e.scaled(e.u16(37003), 10), "V", 1),
                "State of charge" to fmt(e.scaled(e.u16(37004), 10), "%", 1),
                "Working mode" to enum(e.u16(37006), BATTERY_WORKING_MODES),
                "Rated charge power" to fmt(e.u32(37007), "W"), "Rated discharge power" to fmt(e.u32(37009), "W"),
                "Fault ID" to fmt(e.u16(37014)),
                "Charged today" to fmt(e.scaled(e.u32(37015), 100), "kWh", 2),
                "Discharged today" to fmt(e.scaled(e.u32(37017), 100), "kWh", 2),
                "Bus current" to fmt(e.scaled(e.s16(37021), 10), "A", 1),
                "Temperature" to fmt(e.scaled(e.s16(37022), 10), "°C", 1),
                "Remaining charge/discharge time" to fmt(e.u16(37025), "min"),
                "Max. charge power (system)" to fmt(e.u32(37046), "W"),
                "Max. discharge power (system)" to fmt(e.u32(37048), "W"),
                "Charged total" to fmt(e.scaled(e.u32(37066), 100), "kWh", 2),
                "Discharged total" to fmt(e.scaled(e.u32(37068), 100), "kWh", 2),
            )
            if (e.has(32008)) out += section("Alarms (32008–32011)", *(0 until 4).map { k -> "Alarm ${k + 1}" to bits(e.u16(32008 + k)?.toLong(), 4) }.toTypedArray())
            return out.filter { it.rows.isNotEmpty() }
        }
    }
}
