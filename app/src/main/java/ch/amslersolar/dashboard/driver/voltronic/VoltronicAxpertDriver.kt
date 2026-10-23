// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.voltronic

import ch.amslersolar.dashboard.driver.DeviceDriver
import ch.amslersolar.dashboard.driver.Details.enum
import ch.amslersolar.dashboard.driver.Details.fmt
import ch.amslersolar.dashboard.driver.Details.section
import ch.amslersolar.dashboard.driver.voltronic.AxpertProtocol.Qpigs
import ch.amslersolar.dashboard.driver.voltronic.AxpertProtocol.Qpigs2
import ch.amslersolar.dashboard.driver.voltronic.AxpertProtocol.Qpiri
import ch.amslersolar.dashboard.model.BatteryState
import ch.amslersolar.dashboard.model.DetailSection
import ch.amslersolar.dashboard.model.DeviceInfo
import ch.amslersolar.dashboard.model.DeviceReading
import ch.amslersolar.dashboard.model.EnergyTotals
import ch.amslersolar.dashboard.model.PhaseReading
import ch.amslersolar.dashboard.model.PvString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.time.LocalDate
import kotlin.math.roundToInt

/** One request/reply exchange with an Axpert inverter. */
interface AxpertTransport : Closeable {
    /**
     * Sends [command] (framed with CRC and CR) and returns the reply data without `(`, CRC and CR.
     * Throws [AxpertNakException] for NAK and another [IOException] on timeout or a broken/CRC-failed frame.
     */
    suspend fun query(command: String): String
}

/**
 * An RS232↔TCP gateway in transparent mode (e.g. Elfin EW11 as TCP server, port 8899): command bytes go to the
 * socket unchanged and the inverter's reply comes back unchanged. One request at a time; stale bytes are discarded
 * before each request; a failed exchange (not a NAK) reconnects and is retried once.
 */
class TcpAxpertTransport(private val host: String, private val port: Int, private val timeoutMs: Int = 3000) : AxpertTransport {
    private val mutex = Mutex()
    @Volatile private var socket: Socket? = null

    override suspend fun query(command: String): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                exchange(command)
            } catch (e: AxpertNakException) {
                throw e
            } catch (_: IOException) {
                disconnect()
                exchange(command)
            }
        }
    }

    private fun exchange(command: String): String {
        val s = socket ?: Socket().also {
            it.connect(InetSocketAddress(host, port), timeoutMs)
            it.tcpNoDelay = true
            socket = it
        }
        val input = s.getInputStream()
        discard(s, input)
        s.getOutputStream().apply { write(AxpertProtocol.frame(command)); flush() }
        return AxpertProtocol.parseReply(command, readFrame(s, input, command))
    }

    /** Drops whatever arrived since the last reply (late answers, gateway noise). */
    private fun discard(s: Socket, input: InputStream) {
        val buf = ByteArray(256)
        while (input.available() > 0) {
            s.soTimeout = 1
            try {
                if (input.read(buf, 0, minOf(buf.size, input.available())) < 0) throw IOException("Gateway closed the connection")
            } catch (_: SocketTimeoutException) {
                break
            }
        }
    }

    /** Reads up to and including the first CR (CRC bytes are never 0x0D). Bytes after it are discarded next time. */
    private fun readFrame(s: Socket, input: InputStream, command: String): ByteArray {
        val out = ByteArrayOutputStream()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) throw SocketTimeoutException("$command: no reply within $timeoutMs ms")
            s.soTimeout = left.toInt()
            val b = try {
                input.read()
            } catch (e: SocketTimeoutException) {
                throw SocketTimeoutException("$command: no reply within $timeoutMs ms")
            }
            if (b < 0) throw IOException("Gateway closed the connection")
            out.write(b)
            if (b == 0x0D) return out.toByteArray()
            if (out.size() > MAX_FRAME) throw AxpertFrameException("$command: reply too long")
        }
    }

    private fun disconnect() {
        runCatching { socket?.close() }
        socket = null
    }

    override fun close() = disconnect()

    private companion object {
        const val MAX_FRAME = 1024
    }
}

/**
 * Voltronic Power "Axpert" off-grid / hybrid inverter-chargers (KS, MKS, V, VM, MAXII, VMIV, MKSIV …) and their
 * rebrands (MPP Solar, EASUN, Mecer, ACDC Dynamics, …), over the ASCII RS232 protocol with protocol ID PI30.
 *
 * Documents (docs/specs/voltronic/): "Axpert KS&MKS&V Communication Protocol" (2017-08-21, "KS") and "Axpert
 * MAXII/VMIV/MKSIV Communication Protocol" (2021-08-04, "MAX"). Framing and CRC: see [AxpertProtocol].
 *
 * Connection: the inverter's RJ45 "RS232" port (KS/MAX: "RS232 to RJ45 cable", 2400 baud, 8N1; the documents
 * don't give the RJ45 pinout) to an RS232↔TCP gateway (e.g. Elfin EW11) set to 2400 8N1, TCP server, transparent
 * mode (no Modbus conversion), default port 8899. The app writes raw command bytes and reads the raw reply.
 *
 * Commands used (all read-only):
 * - Every poll: QPIGS (KS §2.7 / MAX §2.9), QMOD (KS §2.9 / MAX §2.12), QPIGS2 (MAX §2.10) when QPIRI has the
 *   MAXII field count.
 * - About once a minute: QPIRI (KS §2.5 / MAX §2.7), QPIWS (KS §2.10 / MAX §2.13), QET, QEDyyyymmdd, QLT,
 *   QLDyyyymmdd (MAX §2.23–2.30; not in the KS document). QVFW (KS §2.3 / MAX §2.4) once.
 * - identify(): QPI (MAX §2.1; absent from KS, a NAK is tolerated), QID (KS §2.1 / MAX §2.2), QPIRI, QMN
 *   (KS §3.20 / MAX §2.21, optional), QVFW.
 * Commands that answer NAK (or fail repeatedly) are not asked again until the driver is recreated.
 *
 * Mapping (our signs: battery positive = discharging):
 * - PV power: QPIGS "PV charging power" (field 20), else PV voltage × current; plus PV2 from QPIGS2.
 * - Battery: power = battery voltage × (discharge current − charge current); SoC = "battery capacity".
 * - Load: AC output active power. Grid: voltage and frequency only (L1). These units don't measure grid power,
 *   so [DeviceReading.gridPowerW] is always null.
 * - Status: QMOD mode name. Today/lifetime energy only from the inverter's own counters (QED/QLD/QET); the day is
 *   the phone's date, which must match the inverter's clock.
 *
 * Not verified on a real unit: the CRC byte order (high byte first is the community's usage, the document only
 * gives the function), the reply CRC covering the `(`, QPIWS bit tables per model, whether a given model answers
 * QPIGS2/QET/QED/QLD/QMN. Heat sink temperature is an NTC A/D value, not °C, on Axpert 1–3 kVA (KS §2.7).
 * Units with other protocol IDs (PI16, PI17, PI18, …; QPI tells which) use different commands and aren't covered.
 */
class VoltronicAxpertDriver(
    private val transport: AxpertTransport,
    private val today: () -> LocalDate = { LocalDate.now() },
) : DeviceDriver {
    constructor(host: String, port: Int) : this(TcpAxpertTransport(host, port))

    private var polls = 0
    private var protocolId: Int? = null
    private var firmware: String? = null
    private var modelName: String? = null
    private var rated: Qpiri? = null
    private var warnings: List<String>? = null
    private var energy = Energy()
    private val disabled = mutableSetOf<String>() // command prefixes that answered NAK or kept failing
    private val failures = HashMap<String, Int>()

    private data class Energy(val pvTotalWh: Long? = null, val pvTodayWh: Long? = null, val loadTotalWh: Long? = null, val loadTodayWh: Long? = null)

    override suspend fun identify(): DeviceInfo {
        val pi = try {
            transport.query("QPI")
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            null // QPI isn't in the KS document: older firmware may NAK or ignore it
        }
        if (pi != null) {
            protocolId = AxpertProtocol.parseProtocolId(pi) ?: throw IOException("Not an Axpert inverter (QPI answered '$pi')")
        }
        val serial = AxpertProtocol.parseSerial(transport.query("QID"))
            ?: throw IOException("Not an Axpert inverter (no serial number from QID)")
        val ratings = AxpertProtocol.parseQpiri(transport.query("QPIRI"))
        rated = ratings
        modelName = optional("QMN")?.trim()?.takeIf { it.isNotEmpty() }
        firmware = optional("QVFW")?.let { AxpertProtocol.parseFirmware(it) }
        return DeviceInfo(model = model(ratings), serial = serial)
    }

    private fun model(ratings: Qpiri?): String {
        val name = modelName?.let { "Voltronic $it" }
            ?: ratings?.let { "Voltronic Axpert ${it.outputRatedApparentVa} VA ${fmt(it.batteryRatedVoltageV, "V", 0)}" }
            ?: "Voltronic Axpert"
        val extra = listOfNotNull(protocolId?.let { "PI$it" }, firmware?.let { "firmware $it" })
        return if (extra.isEmpty()) name else "$name (${extra.joinToString(", ")})"
    }

    override suspend fun read(): DeviceReading {
        if (polls % STATIC_REFRESH_POLLS == 0) refreshStatic()
        polls++
        val g = AxpertProtocol.parseQpigs(transport.query("QPIGS"))
        val mode = optional("QMOD")?.let { AxpertProtocol.parseMode(it) }
        val pv2 = if (rated?.looksLikeMaxII == true) optional("QPIGS2")?.let { runCatching { AxpertProtocol.parseQpigs2(it) }.getOrNull() } else null
        return reading(g, pv2, mode)
    }

    private suspend fun refreshStatic() {
        optional("QPIRI")?.let { runCatching { AxpertProtocol.parseQpiri(it) }.getOrNull() }?.let { rated = it }
        optional("QPIWS")?.let { runCatching { AxpertProtocol.parseWarnings(it) }.getOrNull() }?.let { warnings = it }
        if (firmware == null) firmware = optional("QVFW")?.let { AxpertProtocol.parseFirmware(it) }
        val day = today()
        energy = Energy(
            pvTotalWh = optional("QET")?.let { AxpertProtocol.parseEnergyWh(it) },
            pvTodayWh = optional(AxpertProtocol.pvOfDay(day), key = "QED")?.let { AxpertProtocol.parseEnergyWh(it) },
            loadTotalWh = optional("QLT")?.let { AxpertProtocol.parseEnergyWh(it) },
            loadTodayWh = optional(AxpertProtocol.loadOfDay(day), key = "QLD")?.let { AxpertProtocol.parseEnergyWh(it) },
        )
    }

    /** A query whose failure doesn't fail the poll; NAK or [MAX_FAILURES] failures in a row disable it. */
    private suspend fun optional(command: String, key: String = command): String? {
        if (key in disabled) return null
        return try {
            transport.query(command).also { failures.remove(key) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: AxpertNakException) {
            disabled += key
            null
        } catch (_: IOException) {
            val n = (failures[key] ?: 0) + 1
            failures[key] = n
            if (n >= MAX_FAILURES) disabled += key
            null
        }
    }

    private fun reading(g: Qpigs, pv2: Qpigs2?, mode: String?): DeviceReading {
        val pv1Power = g.pvPowerW ?: (g.pvVoltageV * g.pvCurrentA).roundToInt()
        val strings = listOfNotNull(
            PvString(1, g.pvVoltageV, g.pvCurrentA, pv1Power),
            pv2?.let { PvString(2, it.pvVoltageV, it.pvCurrentA, it.pvPowerW) },
        )
        val batteryPower = g.batteryPowerW
        val batteryMode = when {
            batteryPower > 0 -> "Discharging"
            batteryPower < 0 -> "Charging" + chargeSource(g)
            g.floatCharging == true -> "Float"
            else -> "Idle"
        }
        return DeviceReading(
            pvPowerW = pv1Power + (pv2?.pvPowerW ?: 0),
            pvStrings = strings,
            battery = BatteryState(batteryPower, g.batteryCapacityPercent, g.batteryVoltageV, batteryMode),
            gridPowerW = null, // not measured by these units
            phases = listOf(PhaseReading("L1", g.gridVoltageV, null, g.gridFrequencyHz, null)),
            loadPowerW = g.outputActiveW,
            temperatureC = g.heatSinkTemperature.toDouble(),
            status = mode,
            today = EnergyTotals(pvKwh = energy.pvTodayWh?.let { it / 1000.0 }, loadKwh = energy.loadTodayWh?.let { it / 1000.0 }),
            lifetimePvKwh = energy.pvTotalWh?.let { it / 1000.0 },
            details = details(g, pv2, mode),
        )
    }

    private fun chargeSource(g: Qpigs) = when {
        g.sccCharging && g.acCharging -> " (solar + grid)"
        g.sccCharging -> " (solar)"
        g.acCharging -> " (grid)"
        else -> ""
    }

    private fun yesNo(v: Boolean?) = v?.let { if (it) "Yes" else "No" }

    private fun details(g: Qpigs, pv2: Qpigs2?, mode: String?): List<DetailSection> {
        val r = rated
        return listOf(
            section(
                "Inverter",
                "Model" to model(r),
                "Protocol ID" to protocolId?.let { "PI$it" },
                "Main CPU firmware" to firmware,
                "Mode (QMOD)" to mode,
                "Heat sink temperature" to "${fmt(g.heatSinkTemperature, "°C")} (A/D value on 1–3 kVA units)",
                "Bus voltage" to fmt(g.busVoltageV, "V"),
                "EEPROM version" to g.eepromVersion,
            ),
            section(
                "AC",
                "Grid voltage" to fmt(g.gridVoltageV, "V", 1),
                "Grid frequency" to fmt(g.gridFrequencyHz, "Hz", 1),
                "Output voltage" to fmt(g.outputVoltageV, "V", 1),
                "Output frequency" to fmt(g.outputFrequencyHz, "Hz", 1),
                "Output apparent power" to fmt(g.outputApparentVa, "VA"),
                "Output active power" to fmt(g.outputActiveW, "W"),
                "Output load" to fmt(g.loadPercent, "%"),
            ),
            section(
                "Battery",
                "Voltage" to fmt(g.batteryVoltageV, "V", 2),
                "Voltage at charge controller (SCC)" to fmt(g.sccBatteryVoltageV, "V", 2),
                "Charging current" to fmt(g.batteryChargeA, "A"),
                "Discharge current" to fmt(g.batteryDischargeA, "A"),
                "Capacity" to fmt(g.batteryCapacityPercent, "%"),
                "Fan-on voltage offset" to g.fanOffset10mV?.let { fmt(it * 10, "mV") },
            ),
            section(
                "PV",
                "PV1 voltage" to fmt(g.pvVoltageV, "V", 1),
                "PV1 current" to fmt(g.pvCurrentA, "A", 1),
                "PV1 charging power" to fmt(g.pvPowerW, "W"),
                "PV2 voltage" to fmt(pv2?.pvVoltageV, "V", 1),
                "PV2 current" to fmt(pv2?.pvCurrentA, "A", 1),
                "PV2 charging power" to fmt(pv2?.pvPowerW, "W"),
            ),
            section(
                "Status bits (QPIGS)",
                "b7…b0" to g.status,
                "b10…b8" to g.status2,
                "Load on" to yesNo(g.loadOn),
                "Charging" to yesNo(g.charging),
                "Solar (SCC) charging" to yesNo(g.sccCharging),
                "AC charging" to yesNo(g.acCharging),
                "Charging to float" to yesNo(g.floatCharging),
                "Switch on" to yesNo(g.switchOn),
                "Battery voltage steady while charging" to yesNo(g.batteryVoltageSteady),
                "Configuration changed" to yesNo(g.configurationChanged),
                "SCC firmware updated" to yesNo(g.sccFirmwareUpdated),
                "SBU priority version" to yesNo(g.sbuPriorityVersion),
                "Dustproof kit installed" to yesNo(g.dustproofInstalled),
            ),
            section(
                "Warnings (QPIWS)",
                "Active" to warnings?.let { if (it.isEmpty()) "None" else it.joinToString(", ") },
            ),
            section(
                "Energy",
                "PV today" to fmt(energy.pvTodayWh?.let { it / 1000.0 }, "kWh", 2),
                "PV total" to fmt(energy.pvTotalWh?.let { it / 1000.0 }, "kWh", 1),
                "Load today" to fmt(energy.loadTodayWh?.let { it / 1000.0 }, "kWh", 2),
                "Load total" to fmt(energy.loadTotalWh?.let { it / 1000.0 }, "kWh", 1),
            ),
            section(
                "Ratings (QPIRI)",
                "Grid rated voltage" to fmt(r?.gridRatedVoltageV, "V", 1),
                "Grid rated current" to fmt(r?.gridRatedCurrentA, "A", 1),
                "Output rated voltage" to fmt(r?.outputRatedVoltageV, "V", 1),
                "Output rated frequency" to fmt(r?.outputRatedFrequencyHz, "Hz", 1),
                "Output rated current" to fmt(r?.outputRatedCurrentA, "A", 1),
                "Output rated apparent power" to fmt(r?.outputRatedApparentVa, "VA"),
                "Output rated active power" to fmt(r?.outputRatedActiveW, "W"),
                "Battery rated voltage" to fmt(r?.batteryRatedVoltageV, "V", 1),
                "Battery re-charge voltage" to fmt(r?.batteryRechargeVoltageV, "V", 1),
                "Battery re-discharge voltage" to fmt(r?.batteryRedischargeVoltageV, "V", 1),
                "Battery under voltage" to fmt(r?.batteryUnderVoltageV, "V", 1),
                "Battery bulk voltage" to fmt(r?.batteryBulkVoltageV, "V", 1),
                "Battery float voltage" to fmt(r?.batteryFloatVoltageV, "V", 1),
                "Battery type" to enum(r?.batteryType, AxpertProtocol.BATTERY_TYPES),
                "Max AC charging current" to fmt(r?.maxAcChargeA, "A"),
                "Max charging current" to fmt(r?.maxChargeA, "A"),
                "Max discharging current" to fmt(r?.maxDischargeA, "A"),
                "Max charging time at C.V." to fmt(r?.maxCvChargeTimeMin, "min"),
                "Input voltage range" to enum(r?.inputVoltageRange, AxpertProtocol.INPUT_RANGES),
                "Output source priority" to enum(r?.outputSourcePriority, AxpertProtocol.OUTPUT_PRIORITIES),
                "Charger source priority" to enum(r?.chargerSourcePriority, AxpertProtocol.CHARGER_PRIORITIES),
                "Operation logic" to enum(r?.operationLogic, AxpertProtocol.OPERATION_LOGIC),
                "Parallel max number" to r?.parallelMax?.toString(),
                "Machine type" to r?.machineType?.let { t -> AxpertProtocol.MACHINE_TYPES[t]?.let { "$it ($t)" } ?: t },
                "Topology" to enum(r?.topology, AxpertProtocol.TOPOLOGIES),
                "Output mode" to enum(r?.outputMode, AxpertProtocol.OUTPUT_MODES),
                "PV OK condition for parallel" to r?.pvOkCondition?.let { if (it == 1) "All units have PV (1)" else "Any unit has PV (0)" },
                "PV power balance" to r?.pvPowerBalance?.let { if (it == 1) "Charge + load power (1)" else "Max charge current (0)" },
            ),
        ).filter { it.rows.isNotEmpty() }
    }

    override fun close() {
        runCatching { transport.close() }
    }

    internal companion object {
        /** Ratings, warnings and energy counters: about once a minute at the 5 s poll interval. */
        const val STATIC_REFRESH_POLLS = 12
        const val MAX_FAILURES = 3
    }
}
