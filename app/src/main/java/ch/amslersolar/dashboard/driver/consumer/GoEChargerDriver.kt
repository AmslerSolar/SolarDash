// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.consumer

import ch.amslersolar.dashboard.driver.DeviceDriver
import ch.amslersolar.dashboard.driver.Details.enum
import ch.amslersolar.dashboard.driver.Details.fmt
import ch.amslersolar.dashboard.driver.Details.section
import ch.amslersolar.dashboard.driver.modbus.ModbusTcpClient
import ch.amslersolar.dashboard.driver.sunspec.RegisterReader
import ch.amslersolar.dashboard.model.DetailSection
import ch.amslersolar.dashboard.model.DeviceInfo
import ch.amslersolar.dashboard.model.DeviceProblem
import ch.amslersolar.dashboard.model.DeviceReading
import ch.amslersolar.dashboard.model.PhaseReading
import java.io.IOException
import kotlin.math.roundToInt

/**
 * go-eCharger (HOME / Gemini) over its Modbus TCP interface, read only.
 *
 * Spec: go-e "Modbus" documentation (docs/specs/go-e/modbus-en.md; the HTTP API http-en.md only for the enable
 * keys). Verified there:
 * - Port 502 once enabled (app: Internet → Advanced settings → Modbus, or HTTP API key `men=true`; `msp` sets the
 *   port). Read with FC03 or FC04; the measurement table is input registers (FC04).
 * - Addresses are the "wire-format" numbers in parentheses (CAR_STATE = 100, not 30101).
 * - Input registers: 100 car state, 101 cable coding, 105 firmware (ASCII 4 bytes), 107 error, 108–113 voltage
 *   L1–L3 V, 114–119 current 0.1 A, 120 total power 0.01 W, 128 total energy 0.1 kWh, 132 session energy in
 *   deka-watt-seconds (10 Ws), 144 voltage N, 146–151 power L1–L3 0.1 kW, 152–159 power factor %, 200 allow,
 *   202 adapter, 203 unlocked by (RFID), 205 phases (bit flags), 304 serial (ASCII 12 bytes), 310 host name.
 * - Holding registers (FC03, optional [holdingReader]): 211 AMPERE_MAX, 299 AMPERE_VOLATILE, 300 AMPERE_EEPROM.
 * - Byte and word order are configurable on the charger (`msb` swap bytes, `msr` swap registers; firmware 60.3
 *   had the bytes swapped). This driver detects both from the car state (0–4) and the L1 voltage, so any setting
 *   works.
 *
 * UNVERIFIED: the unit id (not documented; 1 is used); the default word order (high word first assumed, then
 * auto-detected); whether unlisted registers in a block are rejected (blocks are split on Modbus exceptions).
 */
class GoEChargerDriver(
    private val reader: RegisterReader,
    private val unitId: Int,
    private val holdingReader: RegisterReader? = null,
) : DeviceDriver {
    constructor(client: ModbusTcpClient, unitId: Int) : this(
        RegisterReader { u, r, c -> client.readInputRegisters(u, r, c) },
        unitId,
        RegisterReader { u, r, c -> client.readHoldingRegisters(u, r, c) },
    )

    private val input = FieldFetcher(reader, unitId)
    private val holding = holdingReader?.let { FieldFetcher(it, unitId) }
    private var static: Map<Int, Int>? = null
    private var polls = 0

    override suspend fun identify(): DeviceInfo {
        val r = decode(loadStatic() + input.fetch(listOf(100 to 2)))
        return DeviceInfo(model = "go-eCharger", serial = r.text(304, 6).orEmpty())
    }

    private suspend fun loadStatic(): Map<Int, Int> {
        input.forget()
        holding?.forget()
        val regs = input.fetch(STATIC_INPUT) + (holding?.fetch(HOLDING)?.mapKeys { it.key + HOLDING_OFFSET }.orEmpty())
        val head = input.fetch(listOf(100 to 2, 105 to 2, 108 to 2))
        val all = regs + head
        val r = decode(all)
        val car = r.u16(100)
        val fw = r.text(105, 2)
        if (car == null || car !in 0..4 || fw == null || r.text(304, 6) == null) {
            throw IOException("Not a go-eCharger (car state $car, firmware '$fw')")
        }
        static = all
        return all
    }

    override suspend fun read(): DeviceReading {
        if (static == null || polls % STATIC_REFRESH_POLLS == 0) loadStatic()
        polls++
        val r = decode(static.orEmpty() + input.fetch(LIVE_INPUT))
        return parse(r)
    }

    internal companion object {
        const val STATIC_REFRESH_POLLS = 12
        val STATIC_INPUT = listOf(105 to 2, 202 to 2, 304 to 6, 310 to 5)
        val LIVE_INPUT = listOf(100 to 2, 107 to 1) + (108..120 step 2).map { it to 2 } + listOf(128 to 2, 132 to 2) +
            (144..158 step 2).map { it to 2 } + listOf(200 to 1, 205 to 1)
        val HOLDING = listOf(211 to 1, 299 to 2) // 211; 299–300, stored at + HOLDING_OFFSET

        /** Holding registers are stored at address + this, so they can't collide with input registers. */
        const val HOLDING_OFFSET = 100_000

        val CAR_STATES = mapOf(
            0 to "Unknown / charger defective", 1 to "Ready, no car", 2 to "Charging", 3 to "Waiting for car",
            4 to "Charging finished, car connected",
        )
        val ERRORS = mapOf(0 to "None", 1 to "RCCB (residual current)", 3 to "Phase disturbance", 8 to "No ground", 10 to "Internal")

        private fun swapBytes(w: Int) = ((w and 0xFF) shl 8) or (w shr 8)

        /**
         * Undoes the configurable byte swap (car state must be 0–4) and picks the word order where the L1 voltage
         * is a plausible 16-bit value.
         */
        fun decode(raw: Map<Int, Int>): Regs {
            val car = raw[100]
            val bytesSwapped = car != null && car > 0xFF && swapBytes(car) in 0..4
            val regs = if (bytesSwapped) raw.mapValues { swapBytes(it.value) } else raw
            val hi = regs[108]
            val lo = regs[109]
            val wordsSwapped = hi != null && lo != null && hi in 1..1000 && lo == 0
            return Regs(regs, lowWordFirst = wordsSwapped)
        }

        fun status(r: Regs): String? {
            val err = r.u16(107)
            if (err != null && err != 0) return "Error: " + (ERRORS[err] ?: "code $err")
            return when (r.u16(100)) {
                1 -> "Standby"
                2 -> "Charging"
                3 -> "Car connected"
                4 -> "Finished, car connected"
                0 -> "Fault"
                else -> null
            }
        }

        fun parse(r: Regs): DeviceReading {
            val phases = (0 until 3).mapNotNull { i ->
                val v = r.u32(108 + 2 * i)?.toDouble() ?: return@mapNotNull null
                PhaseReading(
                    "L${i + 1}", v, r.u32(114 + 2 * i)?.div(10.0), 0.0,
                    r.u32(146 + 2 * i)?.let { (it * 100.0).roundToInt() },
                )
            }
            return DeviceReading(
                loadPowerW = r.u32(120)?.let { (it / 100.0).roundToInt() }?.coerceAtLeast(0),
                phases = phases,
                status = status(r),
                details = details(r),
                problems = problems(r),
            )
        }

        /** ERROR 107 (1 RCCB, 3 phase, 8 no ground, 10 and any other value internal) and car state 0 "defective". */
        fun problems(r: Regs): List<DeviceProblem> {
            val err = r.u16(107)
            val text = when (err) {
                null, 0 -> null
                1 -> "Residual current breaker (RCCB) tripped (error 1)"
                3 -> "Phase disturbance (error 3)"
                8 -> "No ground detected (error 8)"
                else -> "Internal error (error $err)"
            }
            if (text != null) return listOf(DeviceProblem(DeviceProblem.Kind.FAULT, text))
            if (r.u16(100) == 0) return listOf(DeviceProblem(DeviceProblem.Kind.FAULT, "Charging station defective (car state 0)"))
            return emptyList()
        }

        private fun phaseFlags(v: Int): String {
            val before = (0 until 3).filter { v and (8 shl it) != 0 }.map { "L${it + 1}" }
            val after = (0 until 3).filter { v and (1 shl it) != 0 }.map { "L${it + 1}" }
            return "before contactor: ${before.joinToString("+").ifEmpty { "none" }}, after: ${after.joinToString("+").ifEmpty { "none" }}"
        }

        fun details(r: Regs): List<DetailSection> = listOf(
            section(
                "Charging",
                "Car state" to enum(r.u16(100), CAR_STATES),
                "Error" to enum(r.u16(107), ERRORS),
                "Charging allowed" to r.u16(200)?.let { if (it == 1) "Yes" else "No" },
                "Power" to fmt(r.u32(120)?.div(100.0), "W", 0),
                "Session energy" to fmt(r.u32(132)?.let { it * 10.0 / 3_600_000.0 }, "kWh", 2),
                "Total energy" to fmt(r.u32(128)?.div(10.0), "kWh", 1),
                *(0 until 3).flatMap { i ->
                    listOf(
                        "Voltage L${i + 1}" to fmt(r.u32(108 + 2 * i), "V"),
                        "Current L${i + 1}" to fmt(r.u32(114 + 2 * i)?.div(10.0), "A", 1),
                        "Power L${i + 1}" to fmt(r.u32(146 + 2 * i)?.div(10.0), "kW", 1),
                        "Power factor L${i + 1}" to fmt(r.u32(152 + 2 * i), "%"),
                    )
                }.toTypedArray(),
                "Voltage N" to fmt(r.u32(144), "V"),
                "Phases" to r.u16(205)?.let(::phaseFlags),
                "Cable coding" to r.u16(101)?.let { if (it == 0) "No cable" else "$it A" },
                "Adapter" to r.u16(202)?.let { if (it == 1) "16 A adapter" else "None" },
                "Unlocked by RFID card" to r.u16(203)?.toString(),
                "Max current (volatile)" to fmt(r.u16(HOLDING_OFFSET + 299), "A"),
                "Max current (saved)" to fmt(r.u16(HOLDING_OFFSET + 300), "A"),
                "Absolute max current" to fmt(r.u16(HOLDING_OFFSET + 211), "A"),
            ),
            section(
                "Device",
                "Serial number" to r.text(304, 6),
                "Host name" to r.text(310, 5),
                "Firmware" to r.text(105, 2),
            ),
        )
    }
}
