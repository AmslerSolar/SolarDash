// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.consumer

import ch.amslersolar.dashboard.driver.DeviceDriver
import ch.amslersolar.dashboard.driver.Details.enum
import ch.amslersolar.dashboard.driver.Details.fmt
import ch.amslersolar.dashboard.driver.Details.section
import ch.amslersolar.dashboard.driver.modbus.ModbusException
import ch.amslersolar.dashboard.driver.modbus.ModbusTcpClient
import ch.amslersolar.dashboard.driver.sunspec.RegisterReader
import ch.amslersolar.dashboard.model.DetailSection
import ch.amslersolar.dashboard.model.DeviceInfo
import ch.amslersolar.dashboard.model.DeviceReading
import ch.amslersolar.dashboard.model.PhaseReading
import kotlinx.coroutines.delay
import java.io.IOException
import kotlin.math.roundToInt

/**
 * KEBA KeContact P30 (c-series firmware ≥ 3.10.16, x-series software ≥ 1.11) wallbox, Modbus TCP, read only.
 *
 * Spec: "KeContact P30 Modbus TCP Programmers Guide" V1.07, 12.2025 (docs/specs/keba/). Verified there:
 * - Port 502, unit id must be 255, FC3 for reading (FC6 write is never used here).
 * - Register addresses start at 0 and are used as printed (1000 = charging state).
 * - Only one register per request: every value is a UINT32 over 2 words; we read 2 words per request and decode
 *   them high word first (the Modbus default; the guide gives no word order, so this is UNVERIFIED).
 * - Recommended interval between reads > 0.5 s: requests are spaced [requestGapMs] apart. Each poll reads the
 *   8 live registers (≈ 3.5 s) plus one register of the slow list in turn (lifetime energy, voltages, power
 *   factor, limits, identity, failsafe), so the slow values are at most ~75 s old. The first poll reads all.
 * - Enable on the device: DIP switch DSW1.3 = ON; must not be used together with the UDP interface.
 *
 * Registers: 1000 state, 1004 cable state, 1006 error code (show as hex), 1008/1010/1012 current mA,
 * 1014 serial, 1016 product type (decimal digits: type, cable, current, series, meter, RFID), 1018 firmware
 * (hex bytes; per the guide 1013 in "P30 Modbus TCP version 1.11", tried when 1018 is rejected), 1020 power mW,
 * 1036 total energy 0.1 Wh, 1040–1044 voltage V, 1046 power factor 0.1 %, 1100 max charging current mA,
 * 1110 max supported current mA, 1500 RFID tag, 1502 session energy 0.1 Wh, 1550/1552 phase switching
 * source/state, 1600/1602 failsafe current mA / timeout s.
 *
 * UNVERIFIED: word order (see above); the guide's firmware example (0x030A0D00 → "3.10.14") doesn't match its own
 * hex digits (0x0D = 13), so the firmware is shown byte by byte plus the raw hex value.
 */
class KebaP30Driver(
    private val reader: RegisterReader,
    private val unitId: Int,
    private val requestGapMs: Long = 0,
) : DeviceDriver {
    constructor(client: ModbusTcpClient, unitId: Int) :
        this(RegisterReader { u, r, c -> client.readHoldingRegisters(u, r, c) }, unitId, REQUEST_GAP_MS)

    private val values = HashMap<Int, Long>()
    private val missing = mutableSetOf<Int>()
    private var lastRequest = 0L
    private var slowIndex = 0
    private var firstRead = true

    override suspend fun identify(): DeviceInfo {
        val product = readOne(1016) ?: throw IOException("No KEBA product register (1016)")
        if (product / 100_000 != 3L) throw IOException("Not a KEBA KeContact P30 (product $product)")
        val state = readOne(1000)
        if (state == null || state !in 0..5) throw IOException("Not a KEBA KeContact P30 (state $state)")
        val serial = readOne(1014)
        return DeviceInfo(model = modelName(product), serial = serial?.toString().orEmpty())
    }

    override suspend fun read(): DeviceReading {
        if (slowIndex % SLOW.size == 0) missing.clear() // ask rejected registers again once per slow cycle
        for (a in LIVE) values.remove(a)
        for (a in LIVE) readOne(a)?.let { values[a] = it }
        val slow = if (firstRead) SLOW else listOf(SLOW[slowIndex++ % SLOW.size])
        firstRead = false
        for (a in slow) {
            val v = readOne(a) ?: if (a == 1018) readOne(1013) else null
            if (v != null) values[a] = v else values.remove(a)
        }
        return parse(values)
    }

    private suspend fun readOne(a: Int): Long? {
        if (a in missing) return null
        val wait = lastRequest + requestGapMs - System.currentTimeMillis()
        if (requestGapMs > 0 && wait > 0) delay(wait)
        return try {
            reader.read(unitId, a, 2).u32(a)
        } catch (_: ModbusException) {
            missing += a
            null
        } finally {
            lastRequest = System.currentTimeMillis()
        }
    }

    internal companion object {
        const val REQUEST_GAP_MS = 500L
        val LIVE = listOf(1000, 1004, 1006, 1008, 1010, 1012, 1020, 1502)
        val SLOW = listOf(1036, 1040, 1042, 1044, 1046, 1100, 1110, 1014, 1016, 1018, 1500, 1550, 1552, 1600, 1602)

        val STATES = mapOf(
            0 to "Starting", 1 to "Not ready", 2 to "Ready, waiting for car", 3 to "Charging", 4 to "Error",
            5 to "Interrupted (temperature / suspended)",
        )
        val CABLE = mapOf(
            0 to "No cable", 1 to "Cable at station", 3 to "Cable at station, locked", 5 to "Cable at station and car",
            7 to "Cable at station and car, locked",
        )
        private val CURRENT = mapOf(1 to "13 A", 2 to "16 A", 3 to "20 A", 4 to "32 A")
        private val METER = mapOf(1 to "Standard, not calibrated", 2 to "Calibratable (MID)", 3 to "Calibratable, national certification")
        private val PHASE_SOURCE = mapOf(0 to "None", 1 to "OCPP", 2 to "REST API", 3 to "Modbus TCP", 4 to "UDP")

        fun modelName(product: Long): String {
            val series = when (((product / 100) % 10).toInt()) { 1 -> " c-series"; 0 -> " x-series"; else -> "" }
            return "KEBA KeContact P30$series"
        }

        fun firmware(v: Long): String {
            val bytes = listOf(24, 16, 8).map { (v shr it) and 0xFF }
            return bytes.joinToString(".") + " (hex " + v.toString(16).uppercase() + ")"
        }

        fun status(v: Map<Int, Long>): String? {
            val state = v[1000]?.toInt() ?: return null
            val cable = v[1004]?.toInt()
            return when (state) {
                3 -> "Charging"
                4 -> "Error" + (v[1006]?.takeIf { it > 0 }?.let { " " + it.toString(16).uppercase() } ?: "")
                5 -> "Paused"
                0 -> "Starting"
                else -> if (cable != null && cable >= 5) "Car connected" else if (state == 2) "Ready" else "Standby"
            }
        }

        fun parse(v: Map<Int, Long>): DeviceReading {
            val phases = listOf(Triple("L1", 1040, 1008), Triple("L2", 1042, 1010), Triple("L3", 1044, 1012)).mapNotNull { (n, u, i) ->
                val volt = v[u]?.toDouble()
                val amp = v[i]?.div(1000.0)
                if (volt == null && amp == null) null else PhaseReading(n, volt ?: 0.0, amp, 0.0, null)
            }
            return DeviceReading(
                loadPowerW = v[1020]?.let { (it / 1000.0).roundToInt() }?.coerceAtLeast(0),
                status = status(v),
                phases = phases,
                details = details(v),
            )
        }

        private fun ma(v: Long?) = fmt(v?.div(1000.0), "A", 1)

        fun details(v: Map<Int, Long>): List<DetailSection> {
            val product = v[1016]
            return listOf(
                section(
                    "Charging",
                    "State" to enum(v[1000]?.toInt(), STATES),
                    "Cable" to enum(v[1004]?.toInt(), CABLE),
                    "Error code" to v[1006]?.let { if (it == 0L) "None" else "0x" + it.toString(16).uppercase() },
                    "Power" to fmt(v[1020]?.div(1000.0), "W", 0),
                    "Session energy" to fmt(v[1502]?.div(10_000.0), "kWh", 2),
                    "Total energy" to fmt(v[1036]?.div(10_000.0), "kWh", 1),
                    "Current L1" to ma(v[1008]), "Current L2" to ma(v[1010]), "Current L3" to ma(v[1012]),
                    "Voltage L1" to fmt(v[1040], "V"), "Voltage L2" to fmt(v[1042], "V"), "Voltage L3" to fmt(v[1044], "V"),
                    "Power factor" to fmt(v[1046]?.div(10.0), "%", 1),
                    "Max charging current" to ma(v[1100]),
                    "Max supported current (hardware)" to ma(v[1110]),
                    "Phases" to v[1552]?.let { if (it == 1L) "1-phase" else if (it == 3L) "3-phase possible" else "$it" },
                    "Phase switching source" to enum(v[1550]?.toInt(), PHASE_SOURCE),
                    "RFID tag" to v[1500]?.takeIf { it != 0L }?.let { it.toString(16).uppercase() },
                ),
                section(
                    "Device",
                    "Model" to product?.let(::modelName),
                    "Product code" to product?.toString(),
                    "Supported current" to product?.let { CURRENT[((it / 1000) % 10).toInt()] },
                    "Connection" to product?.let { if ((it / 10_000) % 10 == 1L) "Cable" else "Socket" },
                    "Energy meter" to product?.let { METER[((it / 10) % 10).toInt()] },
                    "RFID reader" to product?.let { if (it % 10 == 1L) "Yes" else "No" },
                    "Serial number" to v[1014]?.toString(),
                    "Firmware" to v[1018]?.let(::firmware),
                    "Failsafe current" to ma(v[1600]),
                    "Failsafe timeout" to fmt(v[1602], "s"),
                ),
            )
        }
    }
}
