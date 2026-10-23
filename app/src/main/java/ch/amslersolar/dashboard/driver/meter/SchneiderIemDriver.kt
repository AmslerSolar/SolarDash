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
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Schneider Electric iEM3155 / iEM3255 / iEM3355 energy meters (Modbus RTU over RS485; reached through an
 * RS485 ↔ Modbus TCP gateway, the unit id is the meter's Modbus address 1–247).
 *
 * Spec: "iEM3100 / iEM3200 / iEM3300 series User Manual" DOCA0005EN-16 (docs/specs/schneider/), chapter
 * "Communications using Modbus". The iEM3150 / 3250 / 3350 share the map without reactive / apparent values and
 * export energy; those registers are simply left out when the meter rejects them.
 * - Function 03 (read holding registers) is the only read function.
 * - Addresses: the tables list "Address" (hex, the value in the Modbus frame) and "Register" (decimal,
 *   register = address + 1). This driver sends the address, i.e. the documented register number − 1
 *   (e.g. I1 register 3000 → address 0x0BB7 = 2999). Constants below are addresses.
 * - Encoding: Float32 IEEE, Int64, UInt32, UTF8. UNVERIFIED: the word order isn't stated; high word first
 *   (Modbus big-endian) is assumed.
 * - Sign: "Energy delivered (imported) means positive real power flow (+P) and energy received (exported) means
 *   negative real power flow (−P)"; the PF register's quadrant description also has active power negative in
 *   quadrants 2 / 3. Active power is in kW. Our [DeviceReading.gridPowerW] = −P × 1000.
 * - Power factor (3084) uses a 4-quadrant encoding −2…2 (documented); it is shown converted to −1…1.
 * - Energies: Int64 Wh totals (3204 import, 3208 export; cannot be reset). No daily counters.
 * - Requests per poll: one for U / I / P / PF / f (2999–3110), one for the energy totals; identity, setup,
 *   partial / phase / tariff energies every 12 polls.
 */
class SchneiderIemDriver(private val reader: RegisterReader, private val unitId: Int) : DeviceDriver {
    constructor(client: ModbusTcpClient, unitId: Int) : this(RegisterReader { u, r, c -> client.readHoldingRegisters(u, r, c) }, unitId)

    private val fields = MeterFieldReader(reader, unitId, maxGap = 30) // 2999–3110 in one read
    private var static: Map<Int, Int>? = null
    private var polls = 0

    override suspend fun identify(): DeviceInfo {
        val img = MeterRegs(fields.fetch(IDENTITY))
        if (!isIem(img)) throw IOException("Not a Schneider iEM meter (model register 50)")
        return DeviceInfo(model = model(img), serial = img.u32(SERIAL)?.toString().orEmpty())
    }

    override suspend fun read(): DeviceReading {
        if (static == null || polls % STATIC_REFRESH_POLLS == 0) {
            fields.retryMissing()
            static = fields.fetch(STATIC)
        }
        polls++
        val img = MeterRegs(static.orEmpty() + fields.fetch(LIVE))
        return parse(img).copy(details = details(img))
    }

    internal companion object {
        const val STATIC_REFRESH_POLLS = 12

        // Addresses (documented register − 1).
        const val METER_NAME = 29
        const val METER_MODEL = 49
        const val MANUFACTURER = 69
        const val SERIAL = 129
        const val MANUFACTURE_DATE = 131
        const val HARDWARE = 135
        const val FIRMWARE = 1636
        const val OPERATION_TIMER = 2003
        const val PHASES = 2013
        const val WIRES = 2014
        const val POWER_SYSTEM = 2015
        const val NOMINAL_HZ = 2016
        const val I1 = 2999 // I2 3001, I3 3003
        const val I_N = 3005
        const val I_AVG = 3009
        const val U12 = 3019 // U23 3021, U31 3023
        const val ULL_AVG = 3025
        const val U1N = 3027 // U2N 3029, U3N 3031
        const val ULN_AVG = 3035
        const val P1 = 3053 // P2 3055, P3 3057 (kW)
        const val P_TOTAL = 3059
        const val Q_TOTAL = 3067
        const val S_TOTAL = 3075
        const val PF = 3083
        const val HZ = 3109
        const val E_IMPORT = 3203
        const val E_EXPORT = 3207
        const val Q_IMPORT = 3219
        const val Q_EXPORT = 3223
        const val E_RESET_TIME = 3251
        const val E_PARTIAL = 3255
        const val Q_PARTIAL = 3271
        const val E_PHASE1 = 3517 // phase 2 3521, phase 3 3525
        const val ACTIVE_RATE = 4190
        const val E_RATE_A = 4195 // B 4199, C 4203, D 4207

        val IDENTITY = listOf(
            MeterField(METER_NAME, 20), MeterField(METER_MODEL, 20), MeterField(MANUFACTURER, 20),
            MeterField(SERIAL, 2), MeterField(MANUFACTURE_DATE, 4), MeterField(HARDWARE, 5),
        )
        val STATIC = IDENTITY + listOf(
            MeterField(FIRMWARE, 1), MeterField(OPERATION_TIMER, 2), MeterField(PHASES, 1), MeterField(WIRES, 1),
            MeterField(POWER_SYSTEM, 1), MeterField(NOMINAL_HZ, 1), MeterField(E_RESET_TIME, 4),
            MeterField(E_PARTIAL, 4), MeterField(Q_PARTIAL, 4), MeterField(ACTIVE_RATE, 1),
        ) + fields(4, E_PHASE1, E_PHASE1 + 4, E_PHASE1 + 8, E_RATE_A, E_RATE_A + 4, E_RATE_A + 8, E_RATE_A + 12)
        val LIVE = fields(
            2, I1, I1 + 2, I1 + 4, I_N, I_AVG, U12, U12 + 2, U12 + 4, ULL_AVG, U1N, U1N + 2, U1N + 4, ULN_AVG,
            P1, P1 + 2, P1 + 4, P_TOTAL, Q_TOTAL, S_TOTAL, PF, HZ,
        ) + fields(4, E_IMPORT, E_EXPORT, Q_IMPORT, Q_EXPORT)

        private val POWER_SYSTEMS = mapOf(
            0 to "1PH2W L-N", 1 to "1PH2W L-L", 2 to "1PH3W L-L with N", 3 to "3PH3W", 11 to "3PH4W", 13 to "1PH4W multi L with N",
        )

        fun isIem(img: MeterRegs): Boolean =
            listOfNotNull(img.text(METER_MODEL, 20), img.text(METER_NAME, 20)).any { it.contains("iEM", ignoreCase = true) } ||
                img.text(MANUFACTURER, 20)?.contains("Schneider", ignoreCase = true) == true

        fun model(img: MeterRegs): String = img.text(METER_MODEL, 20) ?: img.text(METER_NAME, 20) ?: "iEM3000"

        /** 4-quadrant PF (−2…2, documented) to the usual −1…1 with the sign of the active power. */
        fun powerFactor(raw: Double?): Double? = raw?.let {
            when {
                it > 1 -> 2 - it
                it < -1 -> -2 - it
                else -> it
            }
        }

        private fun quadrant(raw: Double?): String? = raw?.let {
            when {
                it > 1 -> "Q4 (P +, capacitive)"
                it >= 0 -> "Q1 (P +, inductive)"
                it >= -1 -> "Q2 (P −, capacitive)"
                else -> "Q3 (P −, inductive)"
            }
        }

        /** kW from the meter (+ = imported) to our W (+ = exported). */
        private fun w(img: MeterRegs, a: Int): Int? = img.f32(a)?.let { -(it * 1000).roundToInt() }

        fun parse(img: MeterRegs): DeviceReading {
            val hz = img.f32(HZ) ?: 0.0
            val phases = (0 until 3).mapNotNull { i ->
                val v = img.f32(U1N + 2 * i)?.takeIf { it > 0 } ?: return@mapNotNull null
                PhaseReading("L${i + 1}", v, img.f32(I1 + 2 * i), hz, w(img, P1 + 2 * i))
            }
            return DeviceReading(gridPowerW = w(img, P_TOTAL), phases = phases)
        }

        private fun kwh(img: MeterRegs, a: Int, unit: String = "kWh") = fmt(img.s64(a)?.toDouble()?.div(1000), unit, 1)

        /** DATETIME (4 words, documented): year 0–127 from 2000, month, day, hour, minute, milliseconds. */
        fun dateTime(img: MeterRegs, a: Int): String? {
            val w = (0 until 4).map { img.u16(a + it) ?: return null }
            val year = 2000 + (w[0] and 0x7F)
            val month = (w[1] shr 8) and 0x0F
            val day = w[1] and 0x1F
            val hour = (w[2] shr 8) and 0x1F
            val minute = w[2] and 0x3F
            if (month == 0 || day == 0) return null
            return String.format(Locale.ROOT, "%04d-%02d-%02d %02d:%02d", year, month, day, hour, minute)
        }

        fun details(img: MeterRegs): List<DetailSection> {
            val out = mutableListOf<DetailSection>()
            val rawPf = img.f32(PF)
            out += section(
                "Device",
                "Model" to img.text(METER_MODEL, 20),
                "Meter name" to img.text(METER_NAME, 20),
                "Manufacturer" to img.text(MANUFACTURER, 20),
                "Serial number" to img.u32(SERIAL)?.toString(),
                "Manufactured" to dateTime(img, MANUFACTURE_DATE),
                "Hardware revision" to img.text(HARDWARE, 5),
                "Firmware (DLF X.Y.ZTT, raw)" to img.u16(FIRMWARE)?.toString(),
                "Power system" to enum(img.u16(POWER_SYSTEM), POWER_SYSTEMS),
                "Phases / wires" to listOfNotNull(img.u16(PHASES), img.u16(WIRES)).takeIf { it.size == 2 }?.joinToString(" / "),
                "Nominal frequency" to fmt(img.u16(NOMINAL_HZ), "Hz"),
                "Operation timer" to fmt(img.u32(OPERATION_TIMER)?.div(3600.0), "h", 0),
                "Active tariff" to img.u16(ACTIVE_RATE)?.let { if (it == 0) "Multi tariff disabled" else "Rate ${'A' + it - 1}" },
            )
            out += section(
                "Grid",
                "Active power (+ export)" to fmt(w(img, P_TOTAL), "W"),
                "Active power (meter, + import)" to fmt(img.f32(P_TOTAL)?.times(1000), "W", 0),
                "Reactive power" to fmt(img.f32(Q_TOTAL)?.times(1000), "var", 0),
                "Apparent power" to fmt(img.f32(S_TOTAL)?.times(1000), "VA", 0),
                "Power factor" to fmt(powerFactor(rawPf), "", 3),
                "Quadrant" to quadrant(rawPf),
                "Frequency" to fmt(img.f32(HZ), "Hz", 2),
                "Voltage L-N average" to fmt(img.f32(ULN_AVG), "V", 1),
                "Voltage L-L average" to fmt(img.f32(ULL_AVG), "V", 1),
                "Current average" to fmt(img.f32(I_AVG), "A", 2),
                "Neutral current" to fmt(img.f32(I_N), "A", 2),
            )
            for (i in 0 until 3) {
                out += section(
                    "L${i + 1}",
                    "Voltage L-N" to fmt(img.f32(U1N + 2 * i), "V", 1),
                    "Voltage L-L" to fmt(img.f32(U12 + 2 * i), "V", 1),
                    "Current" to fmt(img.f32(I1 + 2 * i), "A", 2),
                    "Active power (meter, + import)" to fmt(img.f32(P1 + 2 * i)?.times(1000), "W", 0),
                    "Energy import" to kwh(img, E_PHASE1 + 4 * i),
                )
            }
            out += section(
                "Energy (lifetime)",
                "Active import" to kwh(img, E_IMPORT),
                "Active export" to kwh(img, E_EXPORT),
                "Reactive import" to kwh(img, Q_IMPORT, "kvarh"),
                "Reactive export" to kwh(img, Q_EXPORT, "kvarh"),
                "Partial active import" to kwh(img, E_PARTIAL),
                "Partial reactive import" to kwh(img, Q_PARTIAL, "kvarh"),
                "Last energy reset" to dateTime(img, E_RESET_TIME),
                "Rate A import" to kwh(img, E_RATE_A),
                "Rate B import" to kwh(img, E_RATE_A + 4),
                "Rate C import" to kwh(img, E_RATE_A + 8),
                "Rate D import" to kwh(img, E_RATE_A + 12),
            )
            return out.filter { it.rows.isNotEmpty() }
        }
    }
}
