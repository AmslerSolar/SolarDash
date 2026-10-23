// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.sma

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
import ch.amslersolar.dashboard.model.DeviceReading
import ch.amslersolar.dashboard.model.EnergyTotals
import ch.amslersolar.dashboard.model.PhaseReading
import ch.amslersolar.dashboard.model.PvString
import java.io.IOException
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * SMA inverters over the native "SMA Modbus" profile (not SunSpec): Sunny Boy, Sunny Tripower, Sunny Boy Smart
 * Energy (hybrid), Sunny Island, and ennexOS devices (Sunny Tripower Storage X / Sunny Island X) where the
 * registers match.
 *
 * Specs (docs/specs/sma/): "SMA and SunSpec Modbus Interface" SMA-Modbus-general-TI-en-10 (v1.0), "SMA Modbus
 * interface ennexOS" SMAModbus-ennexOS-TI-en-14, "Remote Control STPS X / SI X" SMAModbus-STPS-X-SI-X-TI-en-11
 * (v1.1), and the device register lists SMA_Modbus-TI-en-31 (v3.1, one sheet per device family).
 * - Modbus TCP port 502, unit id 3 (SMA profile, configurable 3..123; SunSpec is unit id + 123 = 126, not used).
 * - Big-endian; U32/S32/U64 span 2/4 registers, high word first ("Big-Big").
 * - NaN (value not available): U16 0xFFFF, S16 0x8000, U32 0xFFFF FFFF, S32 0x8000 0000, U64 0xFFFF FFFF FFFF FFFF,
 *   status/enum U32 0xFFFFFD (16777213 "Information not available"), STR32 zero.
 * - Formats: FIXn = n decimals (value / 10^n), TEMP = °C with one decimal, FW = BCD major.minor, build, release type.
 * - Function code 04 (Read Input Registers). Both 03 and 04 are listed as supported (1..125 registers) in the
 *   general and ennexOS TIs; the STPS X / SI X TI explicitly reads with 04, so 04 is used everywhere.
 * - Block reads: per the TIs, a block containing at least one defined register is answered, undefined registers in
 *   it read as NaN; only a block without any defined register raises a Modbus exception. Such a block is skipped
 *   until the next static refresh (about once a minute).
 * - The general TI asks for at least 10 s between data transfers on older Speedwire devices (ennexOS: 1 s). The app
 *   polls every 5 s; each poll is about 8 block reads.
 *
 * Derived values:
 * - Solar: sum of DC input power (30773, 30961), else for a pure PV inverter the AC power (30775); NaN at night → 0.
 *   A Sunny Island (battery inverter) reports no solar power (its "PV power generated" comes from other devices on
 *   the Speedwire network and would be counted twice); it is shown in the details.
 * - Grid: power grid feed-in (30867) − power grid reference (30865), from the energy meter on SB Smart Energy and
 *   Sunny Island. > 0 = exporting. Plain PV inverters have no meter registers: null.
 * - Battery: Sunny Island 31395 − 31393 ("Battery discharging/charging", unit W; > 0 = discharging). SB Smart
 *   Energy: |30851 battery voltage × 30843 battery current|, direction from 30955 battery operating status
 *   (2292 charging, 2293 discharging, 2291 standby; codes from the STPS X TI). SOC 30845.
 * - Home: only a Sunny Island measures it (30861 load power); otherwise null and the app computes it.
 * - Today: daily yield 30517 (PV inverters), Sunny Island grid energy today 30577 / 30579. Lifetime: 30513.
 *
 * NOT verified in the spec:
 * - The sign of 30843 battery current: when 30955 gives no direction, positive current is taken as charging.
 * - 31393 / 31395 are named "Battery charging/discharging: current battery state of charge" in the SI sheets but
 *   carry the unit W; read as charge / discharge power.
 * - Storage X / Sunny Island X / newer models (SBS, SB-41, STP-40, STP SE) have no full register list in the specs;
 *   only 30201, 30845, 30955, 31391, 40029, 40187 are documented for Storage X. Their battery power is unknown (0).
 * - Device class 30051: only 8001 (solar inverter) and 8009 (hybrid inverter) are documented; identify() accepts
 *   any class in 8000..8999 (assumed SMA device-class tag range).
 * - Treating NaN DC / AC power as 0 W (an SMA inverter that answers but has no PV, e.g. at night).
 */
class SmaDriver(
    private val reader: RegisterReader,
    private val unitId: Int,
    /** Older Speedwire devices want at least 10 s between transfers (general TI); in between, the last reading is reused. */
    private val minIntervalMs: Long = 0,
    private val clock: () -> Long = System::currentTimeMillis,
) : DeviceDriver {
    constructor(client: ModbusTcpClient, unitId: Int) :
        this(RegisterReader { u, r, c -> client.readInputRegisters(u, r, c) }, unitId, MIN_INTERVAL_MS)

    private var last: DeviceReading? = null
    private var lastAt = 0L

    private val missing = mutableSetOf<Int>() // block starts the device rejected (no defined register in them)
    private var static: Map<Int, Int>? = null
    private var polls = 0

    override suspend fun identify(): DeviceInfo {
        val img = Image(reader.read(unitId, IDENTITY.first, IDENTITY.second).values())
        checkSma(img)
        return DeviceInfo(model = model(img), serial = img.u32(30057)?.toString().orEmpty())
    }

    override suspend fun read(): DeviceReading {
        val now = clock()
        last?.let { if (now - lastAt < minIntervalMs) return it }
        return poll().also { last = it; lastAt = now }
    }

    private suspend fun poll(): DeviceReading {
        if (static == null || polls % STATIC_REFRESH_POLLS == 0) {
            missing.clear()
            val regs = reader.read(unitId, IDENTITY.first, IDENTITY.second).values().toMutableMap()
            regs.putAll(fetch(STATIC_BLOCKS))
            static = regs
        }
        polls++
        val img = Image(static.orEmpty() + fetch(LIVE_BLOCKS))
        return parse(img).copy(details = details(img))
    }

    private suspend fun fetch(blocks: List<Pair<Int, Int>>): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        for ((start, count) in blocks) {
            if (start in missing) continue
            try {
                out.putAll(reader.read(unitId, start, count).values())
            } catch (e: ModbusException) {
                // 1 illegal function, 2 illegal address, 3 illegal value: not on this model or firmware.
                if (e.code in 1..3) missing += start else throw e
            }
        }
        return out
    }

    private fun RegisterBlock.values(): Map<Int, Int> = (start until start + count).associateWith { u16(it) }

    /** Register values by address, decoded big-endian with the SMA NaN markers mapped to null. */
    internal class Image(private val regs: Map<Int, Int>) {
        fun u16(a: Int): Int? = regs[a]
        fun u32raw(a: Int): Long? {
            val hi = u16(a) ?: return null
            val lo = u16(a + 1) ?: return null
            return (hi.toLong() shl 16) or lo.toLong()
        }
        fun u32(a: Int): Long? = u32raw(a)?.takeIf { it != 0xFFFFFFFFL }
        fun s32(a: Int): Int? = u32raw(a)?.toInt()?.takeIf { it != Int.MIN_VALUE }
        fun u64(a: Int): Long? {
            val hi = u32raw(a) ?: return null
            val lo = u32raw(a + 2) ?: return null
            return ((hi shl 32) or lo).takeIf { it != -1L && it >= 0 } // NaN 0xFFFF FFFF FFFF FFFF
        }

        /** An ENUM / TAGLIST value; 0xFFFFFD means "information not available". */
        fun tag(a: Int): Int? = u32(a)?.takeIf { it != 0xFFFFFDL }?.toInt()

        /** FIXn of an S32 / U32 register. */
        fun fixS(a: Int, decimals: Int): Double? = s32(a)?.let { it / POW10[decimals] }
        fun fixU(a: Int, decimals: Int): Double? = u32(a)?.let { it / POW10[decimals] }

        /** UTF8 text, zero-terminated. */
        fun text(a: Int, n: Int): String? {
            val bytes = (a until a + n).map { u16(it) ?: return null }.flatMap { listOf((it shr 8).toByte(), it.toByte()) }
            val end = bytes.indexOf(0).let { if (it < 0) bytes.size else it }
            return String(bytes.subList(0, end).toByteArray(), Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
        }
    }

    internal companion object {
        const val STATIC_REFRESH_POLLS = 12 // identity and ratings: every 12 transfers
        /** General TI: older Speedwire devices need ≥ 10 s between Modbus transfers (ennexOS: 1 s). */
        const val MIN_INTERVAL_MS = 10_000L
        private val POW10 = doubleArrayOf(1.0, 10.0, 100.0, 1000.0, 10000.0)

        /** 30051 device class, 30053 device type, 30057 serial number, 30059 software package. */
        val IDENTITY = 30051 to 10
        val STATIC_BLOCKS = listOf(
            40031 to 38, // SI battery ratings 40031..40038, firmware 40063 / 40065, serial 40067
            40109 to 2, // country standard
            40185 to 8, // max apparent power, battery nominal capacity, battery max charge / discharge power
        )
        val LIVE_BLOCKS = listOf(
            30197 to 56, // events, condition, nominal power, grid relay, derating, insulation, power limit … 30252
            30513 to 88, // yields, operating times, grid counters, SI energy counters … 30600
            30769 to 114, // DC1, AC power / phases, reactive / apparent, battery, meter, SI power … 30882
            30883 to 118, // SI grid / generator, temperature, battery status, DC2, phase currents, SI battery … 31000
            31001 to 78, // SI battery statistics, Speedwire IP address 31017 (STR32, 8 registers), SI PV energy … 31078
            31221 to 28, // EEI displacement power factor, residual current … 31248
            31391 to 42, // battery state (Storage X), SI charge / discharge power and energy, SI external power … 31432
            40029 to 2, // operating status
        )

        /** Device types (30053) from "SMA Device Type Enum." in SMA_Modbus-TI-en-31. */
        val DEVICE_TYPES = mapOf(
            9225 to "SB 5000SE-10", 9226 to "SB 3600SE-10",
            9074 to "SB 3000TL-21", 9165 to "SB 3600TL-21", 9075 to "SB 4000TL-21", 9076 to "SB 5000TL-21",
            9184 to "SB 2500TLST-21", 9185 to "SB 3000TLST-21",
            9161 to "SB 3000TL-JP-22", 9162 to "SB 3500TL-JP-22", 9163 to "SB 4000TL-JP-22", 9164 to "SB 4500TL-JP-22",
            9317 to "SB 5400TL-JP-22",
            9198 to "SB 3000TL-US-22", 9199 to "SB 3800TL-US-22", 9200 to "SB 4000TL-US-22", 9201 to "SB 5000TL-US-22",
            9274 to "SB 6000TL-US-22", 9275 to "SB 7000TL-US-22", 9293 to "SB 7700TL-US-22",
            9101 to "STP 8000TL-10", 9067 to "STP 10000TL-10", 9068 to "STP 12000TL-10", 9069 to "STP 15000TL-10",
            9070 to "STP 17000TL-10", 9182 to "STP 15000TLEE-10", 9181 to "STP 20000TLEE-10",
            9222 to "STP 10000TLEE-JP-10", 9271 to "STP 20000TLEE-JP-11", 9272 to "STP 10000TLEE-JP-11",
            9194 to "STP 12000TL-US-10", 9195 to "STP 15000TL-US-10", 9196 to "STP 20000TL-US-10",
            9197 to "STP 24000TL-US-10", 9310 to "STP 30000TL-US-10",
            9098 to "STP 5000TL-20", 9099 to "STP 6000TL-20", 9100 to "STP 7000TL-20", 9103 to "STP 8000TL-20",
            9102 to "STP 9000TL-20", 9281 to "STP 10000TL-20", 9282 to "STP 11000TL-20", 9283 to "STP 12000TL-20",
            9354 to "STP 24500TL-JP-30", 9311 to "STP 25000TL-JP-30",
            9278 to "SI3.0M-11", 9279 to "SI4.4M-11", 9223 to "SI6.0H-11", 9224 to "SI8.0H-11",
        )
        private val SUNNY_ISLAND_TYPES = setOf(9223, 9224, 9278, 9279)

        private val DEVICE_CLASSES = mapOf(8001 to "Solar inverter", 8009 to "Hybrid inverter")

        /**
         * Status codes: the register-specific lists in the device sheets and the STPS X TI, plus the "frequently used
         * number codes" of the ennexOS TI.
         */
        val TAGS = mapOf(
            35 to "Fault", 51 to "Closed", 55 to "Communication impaired", 295 to "MPP", 303 to "Off", 307 to "Ok",
            308 to "On", 309 to "Operation", 311 to "Open", 336 to "Contact manufacturer", 337 to "Contact installer",
            338 to "Invalid", 381 to "Stop", 443 to "Constant voltage", 455 to "Warning", 557 to "Temperature derating",
            569 to "Switched on", 884 to "Not active", 885 to "None", 886 to "None", 887 to "None",
            1041 to "Leading", 1042 to "Lagging",
            1077 to "Active power limitation P in W", 1078 to "Active power limitation as % of Pmax",
            1079 to "Active power limitation via PV system control",
            1295 to "Standby", 1392 to "Fault", 1393 to "Waiting for PV voltage", 1394 to "Waiting for valid AC grid",
            1395 to "DC area", 1396 to "AC grid", 1440 to "Grid mode", 1441 to "Separate network mode",
            1455 to "Emergency stop", 1466 to "Waiting", 1467 to "Start", 1468 to "MPP search", 1469 to "Shut down",
            1470 to "Disturbance", 1479 to "Waiting after grid interruption", 1480 to "Waiting for utility company",
            1779 to "Separated", 1780 to "Public electricity mains", 1781 to "Island mains", 1795 to "Blocked",
            1855 to "Stand-alone operation", 2119 to "Derating",
            2291 to "Battery standby", 2292 to "Battery charging", 2293 to "Battery discharging",
        )

        private const val BATTERY_STANDBY = 2291
        private const val BATTERY_CHARGING = 2292
        private const val BATTERY_DISCHARGING = 2293

        fun checkSma(img: Image) {
            val cls = img.tag(30051)
            if (img.tag(30053) == null && img.u32(30057) == null) throw IOException("No SMA device type or serial (30053 / 30057)")
            if (cls != null && cls !in 8000..8999) throw IOException("Not an SMA device class: $cls (30051)")
        }

        fun model(img: Image): String {
            val type = img.tag(30053)
            return "SMA " + (type?.let { DEVICE_TYPES[it] } ?: type?.let { "device type $it" } ?: "inverter")
        }

        fun firmware(v: Long?): String? {
            v ?: return null
            val major = ((v shr 24) and 0xFF).toString(16)
            val minor = ((v shr 16) and 0xFF).toString(16).padStart(2, '0')
            val build = (v shr 8) and 0xFF
            val release = (v and 0xFF).toInt().let { listOf("N", "E", "A", "B", "R", "S").getOrNull(it) ?: "$it" }
            return "$major.$minor.$build.$release".uppercase()
        }

        fun isBatteryInverter(img: Image): Boolean =
            img.tag(30053)?.let { it in SUNNY_ISLAND_TYPES } == true || img.u32(31393) != null || img.u32(31395) != null

        fun hasBattery(img: Image): Boolean =
            isBatteryInverter(img) || img.u32(30845) != null || img.tag(30955) != null

        fun hasMeter(img: Image): Boolean = img.s32(30865) != null || img.s32(30867) != null

        private fun strings(img: Image): List<PvString> = listOf(30769, 30957).mapIndexedNotNull { i, base ->
            // DC current (FIX3 A), voltage (+2, FIX2 V), power (+4, W)
            val v = img.fixS(base + 2, 2) ?: return@mapIndexedNotNull null
            PvString(i + 1, v, img.fixS(base, 3) ?: 0.0, img.s32(base + 4))
        }

        private fun batteryPower(img: Image): Int? {
            val charge = img.u32(31393)
            val discharge = img.u32(31395)
            if (charge != null || discharge != null) return ((discharge ?: 0L) - (charge ?: 0L)).toInt()
            val status = img.tag(30955)
            val current = img.fixS(30843, 3)
            val voltage = img.fixU(30851, 2)
            if (status == BATTERY_STANDBY) return 0
            if (current == null || voltage == null) return null
            val magnitude = abs(current * voltage).roundToInt()
            return when (status) {
                BATTERY_CHARGING -> -magnitude
                BATTERY_DISCHARGING -> magnitude
                else -> if (current > 0) -magnitude else magnitude // unverified: positive current = charging
            }
        }

        fun parse(img: Image): DeviceReading {
            val batteryInverter = isBatteryInverter(img)
            val battery = if (!hasBattery(img)) null else {
                val power = batteryPower(img) ?: 0
                val status = img.tag(30955)
                BatteryState(
                    powerW = power,
                    socPercent = img.u32(30845)?.toInt()?.takeIf { it in 0..100 },
                    voltageV = img.fixU(30851, 2),
                    mode = when {
                        power > 0 -> "Discharging"
                        power < 0 -> "Charging"
                        status == BATTERY_STANDBY -> "Standby"
                        status == 303 -> "Off"
                        else -> "Idle"
                    },
                )
            }

            val strings = strings(img)
            val dc = strings.mapNotNull { it.powerW }.takeIf { it.isNotEmpty() }?.sum()
            val pv = when {
                batteryInverter -> null
                dc != null -> dc
                battery == null -> img.s32(30775) ?: 0 // AC power of a pure PV inverter
                else -> 0
            }

            val grid = if (hasMeter(img)) (img.s32(30867) ?: 0) - (img.s32(30865) ?: 0) else null

            val hz = img.fixU(30803, 2) ?: 0.0
            val phases = listOf("L1", "L2", "L3").mapIndexedNotNull { i, name ->
                val v = img.fixU(30783 + 2 * i, 2)?.takeIf { it > 0 } ?: return@mapIndexedNotNull null
                val a = img.fixS(30977 + 2 * i, 3) ?: img.fixU(30797 + 2 * i, 3)
                PhaseReading(name, v, a, hz, img.s32(30777 + 2 * i))
            }

            val status = img.tag(40029)?.let { TAGS[it] ?: "Status $it" }
                ?: img.tag(30201)?.let { TAGS[it] ?: "Condition $it" }

            return DeviceReading(
                pvPowerW = pv,
                pvStrings = strings,
                battery = battery,
                gridPowerW = grid,
                phases = phases,
                loadPowerW = if (batteryInverter) img.s32(30861) else null,
                temperatureC = img.fixS(30953, 1),
                status = status,
                today = if (batteryInverter) {
                    EnergyTotals(importKwh = img.u32(30577)?.div(1000.0), exportKwh = img.u32(30579)?.div(1000.0))
                } else {
                    EnergyTotals(pvKwh = (img.u64(30517) ?: img.u32(30535))?.div(1000.0))
                },
                lifetimePvKwh = if (batteryInverter) null else (img.u64(30513) ?: img.u32(30529))?.div(1000.0),
            )
        }

        private fun w(v: Number?) = fmt(v, "W", 0)
        private fun kwh(wh: Long?) = fmt(wh?.div(1000.0), "kWh", 1)
        private fun hours(s: Long?) = fmt(s?.div(3600.0), "h", 0)
        private fun tag(img: Image, a: Int) = enum(img.tag(a), TAGS)

        fun details(img: Image): List<DetailSection> {
            val out = mutableListOf<DetailSection>()
            out += section(
                "Device",
                "Model" to model(img),
                "Device type" to img.tag(30053)?.toString(),
                "Device class" to enum(img.tag(30051), DEVICE_CLASSES),
                "Serial number" to (img.u32(30057) ?: img.u32(40067))?.toString(),
                "Software package" to firmware(img.u32(30059)),
                "Firmware main processor" to firmware(img.u32(40063)),
                "Firmware logic component" to firmware(img.u32(40065)),
                "Country standard" to img.tag(40109)?.toString(),
                "IP address" to img.text(31017, 8),
                "Nominal power (Ok mode)" to w(img.u32(30203)),
                "Maximum active power" to w(img.u32(30231)),
                "Maximum apparent power" to fmt(img.u32(40185), "VA", 0),
            )
            out += section(
                "Status",
                "Condition" to tag(img, 30201),
                "Operating status" to tag(img, 40029),
                "Recommended action" to tag(img, 30211),
                "Message" to tag(img, 30213),
                "Fault correction measure" to tag(img, 30215),
                "Current event number" to img.u32(30197)?.toString(),
                "Event number for manufacturer" to img.u32(30247)?.toString(),
                "Waiting time until feed-in" to fmt(img.u32(30199), "s"),
                "Grid relay" to tag(img, 30217),
                "Derating" to tag(img, 30219),
                "Backup mode" to tag(img, 30235),
                "Plant mains connection" to tag(img, 30881),
                "Restart block" to tag(img, 30251),
                "Feed-in management" to tag(img, 30835),
                "Set active power limit" to w(img.u32(30233)),
                "Active power limitation" to w(img.u32(30837)),
                "Active power limitation (%)" to fmt(img.u32(30839), "%"),
                "Events for user" to img.u32(30559)?.toString(),
                "Events for installer" to img.u32(30561)?.toString(),
                "Grid connections" to img.u32(30599)?.toString(),
            )
            out += section(
                "AC",
                "Active power" to w(img.s32(30775)),
                *(0 until 3).flatMap { i ->
                    listOf(
                        "L${i + 1} voltage" to fmt(img.fixU(30783 + 2 * i, 2), "V", 1),
                        "L${i + 1} current" to fmt(img.fixS(30977 + 2 * i, 3) ?: img.fixU(30797 + 2 * i, 3), "A", 2),
                        "L${i + 1} power" to w(img.s32(30777 + 2 * i)),
                    )
                }.toTypedArray(),
                "L1–L2 voltage" to fmt(img.fixU(30789, 2), "V", 1),
                "L2–L3 voltage" to fmt(img.fixU(30791, 2), "V", 1),
                "L3–L1 voltage" to fmt(img.fixU(30793, 2), "V", 1),
                "Grid current" to fmt(img.fixU(30795, 3), "A", 2),
                "Grid frequency" to fmt(img.fixU(30803, 2), "Hz", 2),
                "Reactive power" to fmt(img.s32(30805), "var", 0),
                "Apparent power" to fmt(img.s32(30813), "VA", 0),
                "Displacement power factor" to fmt(img.fixU(30949, 3), "", 3),
                "EEI displacement power factor" to fmt(img.fixS(31221, 3), "", 3),
                "Internal temperature" to fmt(img.fixS(30953, 1), "°C", 1),
            )
            out += section(
                "PV",
                *listOf(30769, 30957).flatMapIndexed { i, base ->
                    listOf(
                        "DC${i + 1} voltage" to fmt(img.fixS(base + 2, 2), "V", 1),
                        "DC${i + 1} current" to fmt(img.fixS(base, 3), "A", 2),
                        "DC${i + 1} power" to w(img.s32(base + 4)),
                    )
                }.toTypedArray(),
                "Intermediate circuit voltage" to fmt(img.fixS(30975, 2), "V", 1),
                "Insulation resistance" to fmt(img.u32(30225)?.div(1000.0), "kΩ", 0),
                "Residual current" to fmt(img.fixS(31247, 3), "A", 3),
            )
            if (hasBattery(img)) {
                out += section(
                    "Battery",
                    "Power (+ discharge)" to w(batteryPower(img)),
                    "State of charge" to fmt(img.u32(30845), "%"),
                    "Capacity (state of health)" to fmt(img.u32(30847), "%"),
                    "Voltage" to fmt(img.fixU(30851, 2), "V", 1),
                    "Current" to fmt(img.fixS(30843, 3), "A", 2),
                    "Temperature" to fmt(img.fixS(30849, 1), "°C", 1),
                    "Operating status" to tag(img, 30955),
                    "Battery state" to tag(img, 31391),
                    "Charging power" to w(img.u32(31393)),
                    "Discharging power" to w(img.u32(31395)),
                    "Charging set voltage" to fmt(img.fixU(30855, 2), "V", 1),
                    "Active charging mode" to img.tag(30853)?.toString(),
                    "Nominal capacity" to fmt(img.u32(40187)?.div(1000.0), "kWh", 1),
                    "Rated capacity" to fmt(img.u32(40031), "Ah"),
                    "Rated voltage" to fmt(img.u32(40037), "V"),
                    "Battery type" to img.tag(40035)?.toString(),
                    "Max charge power (manufacturer)" to w(img.u32(40189)),
                    "Max discharge power (manufacturer)" to w(img.u32(40191)),
                    "Charge throughputs" to img.s32(30857)?.toString(),
                    "Full charges" to img.u32(31069)?.toString(),
                    "Charged energy" to kwh(img.u64(31397)),
                    "Discharged energy" to kwh(img.u64(31401)),
                    "Charged Ah" to fmt(img.u32(30567), "Ah"),
                    "Discharged Ah" to fmt(img.u32(30569), "Ah"),
                    "Lowest temperature" to fmt(img.fixS(30997, 1), "°C", 1),
                    "Highest temperature" to fmt(img.fixS(30999, 1), "°C", 1),
                    "Highest voltage" to fmt(img.fixU(31001, 2), "V", 1),
                    "Time until full charge" to fmt(img.u32(31003)?.div(60.0), "min", 0),
                    "Lower discharge limit (self-consumption)" to fmt(img.u32(31009), "%"),
                )
            }
            if (hasMeter(img)) {
                out += section(
                    "Energy meter",
                    "Grid power (+ export)" to w((img.s32(30867) ?: 0) - (img.s32(30865) ?: 0)),
                    "Power from grid" to w(img.s32(30865)),
                    "Power to grid" to w(img.s32(30867)),
                    "Energy from grid today" to kwh(img.u32(30577)),
                    "Energy to grid today" to kwh(img.u32(30579)),
                    "Grid reference counter" to kwh(img.u32(30581)),
                    "Grid feed-in counter" to kwh(img.u32(30583)),
                )
            }
            if (isBatteryInverter(img)) {
                out += section(
                    "Sunny Island",
                    "Load power" to w(img.s32(30861)),
                    "PV power generated" to w(img.s32(30869)),
                    "Output of photovoltaics" to w(img.u32(30983)),
                    "Self-consumption" to w(img.u32(30871)),
                    "Rise in self-consumption" to w(img.s32(30873)),
                    "Power supply status" to img.tag(30877)?.toString(),
                    "Utility grid status" to img.tag(30883)?.toString(),
                    "Generator status" to img.tag(30917)?.toString(),
                    "Generator starts" to img.u32(30565)?.toString(),
                    "External connection frequency" to fmt(img.fixU(30901, 2), "Hz", 2),
                    *(0 until 3).flatMap { i ->
                        val p = "ABC"[i]
                        listOf(
                            "External voltage $p" to fmt(img.fixU(30903 + 2 * i, 2), "V", 1),
                            "External current $p" to fmt(img.fixS(30909 + 2 * i, 3), "A", 2),
                            "External power $p" to w(img.s32(31419 + 2 * i)),
                        )
                    }.toTypedArray(),
                    "External power" to w(img.s32(31417)),
                    "Consumption meter" to kwh(img.u32(30571)),
                    "PV generation counter" to kwh(img.u32(30587)),
                    "PV energy total" to fmt(img.u32(31063), "kWh", 0),
                    "PV energy today" to kwh(img.u32(31065)),
                    "Rise in self-consumption today" to kwh(img.u32(30591)),
                    "Power outage" to hours(img.u32(30585)),
                    "Status master (L1)" to img.tag(31015)?.toString(),
                    "Status slave 1 (L2)" to img.tag(31053)?.toString(),
                    "Status slave 2 (L3)" to img.tag(31055)?.toString(),
                )
            }
            out += section(
                "Energy",
                (if (isBatteryInverter(img)) "Total AC energy fed in" else "Total yield") to kwh(img.u64(30513) ?: img.u32(30529)),
                "Daily yield" to kwh(img.u64(30517) ?: img.u32(30535)),
                "Grid feed-in counter" to if (hasMeter(img)) null else kwh(img.u32(30583)),
                "Operating time" to hours(img.u64(30521) ?: img.u32(30541)),
                "Feed-in time" to hours(img.u64(30525) ?: img.u32(30543)),
            )
            return out.filter { it.rows.isNotEmpty() }
        }
    }
}
