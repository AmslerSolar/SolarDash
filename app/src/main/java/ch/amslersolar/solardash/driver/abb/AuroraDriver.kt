// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.driver.abb

import ch.amslersolar.solardash.driver.DeviceDriver
import ch.amslersolar.solardash.driver.Details.enum
import ch.amslersolar.solardash.driver.Details.fmt
import ch.amslersolar.solardash.driver.Details.section
import ch.amslersolar.solardash.driver.abb.AuroraProtocol.Measure
import ch.amslersolar.solardash.driver.abb.AuroraProtocol.Period
import ch.amslersolar.solardash.driver.net.DeviceNetwork
import ch.amslersolar.solardash.model.DetailSection
import ch.amslersolar.solardash.model.DeviceInfo
import ch.amslersolar.solardash.model.DeviceProblem
import ch.amslersolar.solardash.model.DeviceReading
import ch.amslersolar.solardash.model.EnergyTotals
import ch.amslersolar.solardash.model.PhaseReading
import ch.amslersolar.solardash.model.PvString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** A reply with a valid CRC that can't be the answer to the request (most likely one meant for an earlier request). */
class ImplausibleReplyException(message: String) : IOException(message)

/** One request/reply exchange on an Aurora RS485 bus. */
interface AuroraTransport : Closeable {
    /** Sends [command] to [address] and returns the reply's 6 data bytes (CRC checked, transmission state not). */
    suspend fun exchange(address: Int, command: Int, vararg args: Int): ByteArray
}

/**
 * An RS485↔TCP gateway in transparent mode (e.g. Elfin EW11 or Waveshare as TCP server, 19200 8N1): request bytes go
 * to the socket unchanged and the reply comes back unchanged. Shared by all inverters on the bus (one exchange at a
 * time); stale bytes are discarded before each request; a failed exchange reconnects and is retried once. A gateway
 * that echoes the request is fine: the reply is the last 8 bytes with a valid CRC.
 *
 * Aurora replies don't say which request they answer, so a late one would be taken for the next request's: after
 * each reply the line must stay quiet for [gapMs], and a reply arriving meanwhile replaces it (see [latest]). A
 * Waveshare RS485 TO POE ETH (B) on a switch delayed about 1 reply in 12 by 1.1 s (seen 2026-11-04, also with only
 * one client; most likely a lost TCP segment sent again): hence the long [timeoutMs], and after a timeout the bus
 * is left quiet for [SETTLE_MS] and whatever arrived meanwhile is dropped before the next request (a segment sent a
 * second time comes 1 s after the first resend). What still slips through is caught by the driver's plausibility checks.
 */
class TcpAuroraTransport(
    private val host: String,
    private val port: Int,
    private val timeoutMs: Int = TIMEOUT_MS,
    private val gapMs: Long = GAP_MS,
    private val settleMs: Long = SETTLE_MS,
) : AuroraTransport {
    private val mutex = Mutex()
    @Volatile private var socket: Socket? = null
    private var quietUntil = 0L

    override suspend fun exchange(address: Int, command: Int, vararg args: Int): ByteArray = mutex.withLock {
        withContext(Dispatchers.IO) {
            val request = AuroraProtocol.request(address, command, *args)
            try {
                send(request)
            } catch (e: SocketTimeoutException) {
                quietUntil = System.currentTimeMillis() + settleMs
                throw e // the inverter is silent (asleep, wrong address): reconnecting won't help
            } catch (_: IOException) {
                disconnect()
                send(request)
            }
        }
    }

    private fun send(request: ByteArray): ByteArray {
        val s = socket ?: DeviceNetwork.current.socket(host).also {
            it.connect(InetSocketAddress(host, port), timeoutMs)
            it.tcpNoDelay = true
            socket = it
        }
        val input = s.getInputStream()
        val wait = quietUntil - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        discard(s, input)
        s.getOutputStream().apply { write(request); flush() }
        return readReply(s, input, request)
    }

    /** Drops what has arrived; true if there was anything. */
    private fun discard(s: Socket, input: InputStream): Boolean {
        val buf = ByteArray(256)
        var any = false
        while (input.available() > 0) {
            s.soTimeout = 1
            try {
                if (input.read(buf, 0, minOf(buf.size, input.available())) < 0) throw IOException("Gateway closed the connection")
                any = true
            } catch (_: SocketTimeoutException) {
                break
            }
        }
        return any
    }

    private fun readReply(s: Socket, input: InputStream, request: ByteArray): ByteArray {
        val buf = ByteArray(MAX_BYTES)
        var size = 0
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            val what = "Aurora address ${request[0].toInt() and 0xFF}, command ${request[1].toInt() and 0xFF}"
            if (left <= 0) throw SocketTimeoutException("$what: no reply within $timeoutMs ms")
            s.soTimeout = left.toInt()
            val b = try {
                input.read()
            } catch (_: SocketTimeoutException) {
                throw SocketTimeoutException("$what: no reply within $timeoutMs ms")
            }
            if (b < 0) throw IOException("Gateway closed the connection")
            buf[size++] = b.toByte()
            if (AuroraProtocol.isReply(buf, size)) return latest(s, input, buf.copyOfRange(size - 8, size - 2))
            if (size == MAX_BYTES) throw IOException("$what: no valid reply (CRC)")
        }
    }

    /**
     * Waits until the line has been quiet for [gapMs] after [reply]. Another reply arriving meanwhile means [reply] was
     * an earlier request's, delayed (it was taken for this one); the later one is this request's and is returned.
     * Without this, every later reply would be off by one too (seen on the tablet: one inverter "not answering" for
     * 40 s, the other showing values one request off).
     */
    private fun latest(s: Socket, input: InputStream, reply: ByteArray): ByteArray {
        var result = reply
        val buf = ByteArray(MAX_BYTES)
        var size = 0
        var quietFrom = System.currentTimeMillis()
        val end = quietFrom + gapMs * MAX_QUIET_ROUNDS
        while (true) {
            val now = System.currentTimeMillis()
            val left = minOf(quietFrom + gapMs, end) - now
            if (left <= 0) return result
            s.soTimeout = left.toInt()
            val b = try {
                input.read()
            } catch (_: SocketTimeoutException) {
                return result
            }
            if (b < 0) return result
            quietFrom = System.currentTimeMillis()
            if (size == MAX_BYTES) size = 0
            buf[size++] = b.toByte()
            if (AuroraProtocol.isReply(buf, size)) {
                result = buf.copyOfRange(size - 8, size - 2)
                size = 0
            }
        }
    }

    private fun disconnect() {
        runCatching { socket?.close() }
        socket = null
    }

    override fun close() = disconnect()

    internal companion object {
        /** An echoed request (10) plus the reply (8), with some slack for noise. */
        const val MAX_BYTES = 32
        /** A reply delayed by a re-sent TCP segment comes after about 1.1 s. */
        const val TIMEOUT_MS = 2500
        /** After a timeout: quiet time for a reply still under way, dropped before the next request. */
        const val SETTLE_MS = 1_000L
        /**
         * Quiet time on the line after a reply, before it's taken and the next request goes out: long enough for this
         * request's own reply to arrive when an earlier one came first (most replies come within 40 ms).
         */
        const val GAP_MS = 60L
        const val MAX_QUIET_ROUNDS = 10
    }
}

/**
 * Power-One / ABB / FIMER string inverters (TRIO, PVI, UNO) over their own RS485 "Aurora" protocol (factory setting:
 * address 2, 19200 baud, 8N1), through an RS485↔TCP gateway in transparent mode. The inverters with Modbus RTU
 * switched on are read by ModbusSpec instead (DeviceType.POWER_ONE); framing and codes: see [AuroraProtocol].
 *
 * Commands used (all read-only):
 * - Every poll: state (50) and measurements (59): AC power, phase voltages / currents, frequency, both inputs,
 *   inverter temperature.
 * - About once a minute: energy counters (78: day, week, month, year, total), the last 4 alarms (86), booster
 *   temperature, isolation resistance, leak currents, bulk voltage, power peaks, the inverter's clock (70).
 *   Reading the alarms empties the inverter's list (seen on the TRIO: 4 × W011, then zeros), so the driver keeps
 *   the last 4 it has seen ("Recent alarms").
 * - identify(): serial number (63), part number (52), version / model code (58), firmware (72), week of manufacture (65).
 * A measurement the inverter refuses as unknown (transmission state 51 or 52) isn't asked again. One that gets no
 * reply keeps its last value for [HOLD_MS], so a lost reply doesn't blank the power for a poll.
 *
 * Verified 2026-11-03 on a TRIO-27.6-TL-OUTD-400 (firmware C357) and a second inverter with model code 'n'
 * (firmware C026) on one bus. Single-phase models (PVI, UNO) aren't verified: their per-phase measurements are
 * expected to be refused or zero, and then only the grid voltage and current are shown.
 *
 * At night the inverters switch off and stop answering (shown as asleep).
 */
class AuroraDriver(
    private val transport: AuroraTransport,
    private val address: Int,
    private val clock: () -> Long = System::currentTimeMillis,
) : DeviceDriver {
    private var polls = 0
    private var info: Identity? = null
    private var slow = Slow()
    private val disabled = mutableSetOf<Int>() // measurement types (command 59) and commands (others, + 1000)
    private val held = HashMap<Int, Pair<Float, Long>>() // FAST measurements: last value and when it was read
    private var lowerTotal: Long? = null // a total below the one shown, taken when it's read again
    private var answered = false

    private data class Identity(
        val serial: String, val partNumber: String?, val modelCode: Char?, val gridStandard: Char?,
        val transformer: Char?, val source: Char?, val firmware: String?, val manufactured: String?,
    )

    private data class Slow(
        val energyWh: Map<Int, Long> = emptyMap(),
        val measures: Map<Int, Float> = emptyMap(),
        val lastAlarms: List<Int>? = null,
        val clock: LocalDateTime? = null,
    )

    override suspend fun identify(): DeviceInfo {
        val id = identity()
        return DeviceInfo(model = modelName(id), serial = id.serial)
    }

    private suspend fun identity(): Identity {
        info?.let { return it }
        val serial = AuroraProtocol.ascii(transport.exchange(address, AuroraProtocol.SERIAL))
        if (serial.isEmpty() || serial.any { it < ' ' || it > '~' }) throw IOException("Not an Aurora inverter (serial number '$serial')")
        val version = optionalCommand(AuroraProtocol.VERSION)
        val id = Identity(
            serial = serial,
            partNumber = optionalCommand(AuroraProtocol.PART_NUMBER, checked = false)?.let { AuroraProtocol.ascii(it) },
            modelCode = version?.get(2)?.toInt()?.toChar(),
            gridStandard = version?.get(3)?.toInt()?.toChar(),
            transformer = version?.get(4)?.toInt()?.toChar(),
            source = version?.get(5)?.toInt()?.toChar(),
            firmware = optionalCommand(AuroraProtocol.FIRMWARE)?.let { AuroraProtocol.ascii(it, 2) }?.toCharArray()?.joinToString("."),
            manufactured = optionalCommand(AuroraProtocol.MANUFACTURED)?.let { AuroraProtocol.ascii(it, 2) }
                ?.takeIf { it.length == 4 }?.let { "week ${it.substring(0, 2)} of 20${it.substring(2)}" },
        )
        info = id
        return id
    }

    private fun modelName(id: Identity): String {
        val model = id.modelCode?.let { AuroraProtocol.model(it) }
            ?: "Aurora inverter" + (id.modelCode?.let { " (model code '$it')" } ?: "")
        return "ABB $model" + (id.firmware?.let { ", firmware $it" } ?: "")
    }

    override suspend fun read(): DeviceReading {
        val state = try {
            AuroraProtocol.checked(AuroraProtocol.STATE, stateReply())
        } catch (e: IOException) {
            answered = false
            throw e
        }
        answered = true
        if (info == null) runCatching { identity() }.onFailure { if (it is CancellationException) throw it }
        if (polls % SLOW_REFRESH_POLLS == 0) refreshSlow()
        polls++
        val m = HashMap<Int, Float>()
        val now = clock()
        for (type in FAST) {
            val v = measure(type)
            if (v != null) held[type] = v to now
            (v ?: held[type]?.takeIf { now - it.second <= HOLD_MS }?.first)?.let { m[type] = it }
        }
        return reading(State(state), m)
    }

    /**
     * The state, asked again when it got no reply but the inverter answered the poll before (a lost reply); an
     * inverter that already didn't answer (asleep at night) isn't asked twice.
     */
    private suspend fun stateReply(): ByteArray = try {
        plausibleState(transport.exchange(address, AuroraProtocol.STATE))
    } catch (e: SocketTimeoutException) {
        if (!answered) throw e
        plausibleState(transport.exchange(address, AuroraProtocol.STATE))
    } catch (_: ImplausibleReplyException) {
        plausibleState(transport.exchange(address, AuroraProtocol.STATE))
    }

    /** A state reply whose codes are all known; another request's reply (a float, a counter) mostly isn't. */
    private fun plausibleState(reply: ByteArray): ByteArray {
        val st = State(reply)
        val known = (reply[0].toInt() and 0xFF) != 0 || (
            st.global in AuroraProtocol.GLOBAL_STATES && st.inverter in AuroraProtocol.INVERTER_STATES &&
                st.dcdc1 in AuroraProtocol.DCDC_STATES && st.dcdc2 in AuroraProtocol.DCDC_STATES && st.alarm in AuroraProtocol.ALARMS
            )
        if (!known) throw ImplausibleReplyException("Aurora address $address: state reply ${reply.joinToString(" ") { "%02x".format(it) }} has unknown codes")
        return reply
    }

    private suspend fun refreshSlow() {
        val energy = HashMap<Int, Long>()
        for (p in listOf(Period.DAY, Period.WEEK, Period.MONTH, Period.YEAR, Period.TOTAL)) {
            optionalCommand(AuroraProtocol.ENERGY, p, key = 1000 + AuroraProtocol.ENERGY * 10 + p)?.let { energy[p] = AuroraProtocol.uint32(it) }
        }
        val measures = HashMap<Int, Float>()
        for (type in SLOW) measure(type)?.let { measures[type] = it }
        slow = Slow(
            energyWh = plausibleEnergy(energy),
            measures = measures,
            lastAlarms = optionalCommand(AuroraProtocol.LAST_ALARMS)
                ?.let { r -> (2..5).map { r[it].toInt() and 0xFF } }
                ?.takeIf { codes -> codes.all { it in AuroraProtocol.ALARMS } } // a reply meant for another request isn't
                ?.let { codes -> ((slow.lastAlarms ?: emptyList()) + codes.filter { it != 0 }).takeLast(4) }
                ?: slow.lastAlarms,
            clock = optionalCommand(AuroraProtocol.TIME)?.let {
                LocalDateTime.ofEpochSecond(AuroraProtocol.EPOCH_2000 + AuroraProtocol.uint32(it), 0, ZoneOffset.UTC)
            }?.takeIf { it.year in 2000..2099 } ?: slow.clock,
        )
    }

    /**
     * The energy counters that make sense, else the ones read before. A reply meant for another request (a float, a
     * different counter) shows up as a counter above the total, or a total that drops or jumps by more than
     * [MAX_TOTAL_STEP_WH]. A lower total read twice in a row is taken (the counter was reset, or the first one was wrong).
     */
    private fun plausibleEnergy(read: Map<Int, Long>): Map<Int, Long> {
        val before = slow.energyWh
        val prev = before[Period.TOTAL]
        val t = read[Period.TOTAL]
        val total = when {
            t == null -> prev
            prev == null || t in prev..prev + MAX_TOTAL_STEP_WH -> t.also { lowerTotal = null }
            t == lowerTotal -> t.also { lowerTotal = null }
            else -> prev.also { lowerTotal = t }
        }
        val out = HashMap<Int, Long>()
        total?.let { out[Period.TOTAL] = it }
        for (p in listOf(Period.DAY, Period.WEEK, Period.MONTH, Period.YEAR)) {
            (read[p]?.takeIf { total == null || it <= total } ?: before[p])?.let { out[p] = it }
        }
        return out
    }

    private suspend fun measure(type: Int): Float? =
        optionalCommand(AuroraProtocol.MEASURE, type, 0, key = type)?.let { AuroraProtocol.float(it) }
            ?.takeIf { v -> v.isFinite() && (RANGES[type]?.let { v in it } ?: true) }

    /**
     * A command whose failure doesn't fail the poll. Refused as unknown (command not implemented, variable doesn't
     * exist), it isn't asked again; any other failure (no reply, "retry", a reply garbled on the way) only costs this
     * poll's value.
     */
    private suspend fun optionalCommand(command: Int, vararg args: Int, key: Int = 1000 + command, checked: Boolean = true): ByteArray? {
        if (key in disabled) return null
        return try {
            val reply = transport.exchange(address, command, *args)
            if (checked) AuroraProtocol.checked(command, reply) else reply
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuroraRefusedException) {
            if (e.transmissionState in UNKNOWN) disabled += key
            null
        } catch (_: IOException) {
            null
        }
    }

    /** The state reply (command 50): global, inverter, DC/DC 1 and 2, alarm. */
    private class State(reply: ByteArray) {
        val global = reply[1].toInt() and 0xFF
        val inverter = reply[2].toInt() and 0xFF
        val dcdc1 = reply[3].toInt() and 0xFF
        val dcdc2 = reply[4].toInt() and 0xFF
        val alarm = reply[5].toInt() and 0xFF
    }

    private fun reading(st: State, m: Map<Int, Float>): DeviceReading {
        val power = m[Measure.GRID_POWER]?.roundToInt()
        val strings = listOfNotNull(
            pvString(1, m[Measure.INPUT1_VOLTAGE], m[Measure.INPUT1_CURRENT], m[Measure.INPUT1_POWER]),
            pvString(2, m[Measure.INPUT2_VOLTAGE], m[Measure.INPUT2_CURRENT], m[Measure.INPUT2_POWER]),
        )
        return DeviceReading(
            pvPowerW = power,
            pvStrings = strings,
            phases = phases(m),
            temperatureC = m[Measure.INVERTER_TEMPERATURE]?.toDouble()?.let { round1(it) },
            status = AuroraProtocol.GLOBAL_STATES[st.global] ?: "State ${st.global}",
            today = EnergyTotals(pvKwh = slow.energyWh[Period.DAY]?.let { it / 1000.0 }),
            lifetimePvKwh = slow.energyWh[Period.TOTAL]?.let { it / 1000.0 },
            details = details(st, m),
            problems = problems(st),
        )
    }

    private fun pvString(index: Int, v: Float?, a: Float?, w: Float?): PvString? {
        if (v == null || a == null) return null
        return PvString(index, round1(v.toDouble()), round2(a.toDouble()), w?.roundToInt())
    }

    /** Three phases when the inverter has per-phase values, else the single grid voltage and current as L1. */
    private fun phases(m: Map<Int, Float>): List<PhaseReading> {
        val volts = listOf(Measure.VOLTAGE_L1, Measure.VOLTAGE_L2, Measure.VOLTAGE_L3).map { m[it] }
        val amps = listOf(Measure.CURRENT_L1, Measure.CURRENT_L2, Measure.CURRENT_L3).map { m[it] }
        val freq = m[Measure.FREQUENCY]?.toDouble() ?: return emptyList()
        if (volts.all { it != null && it > 0f }) {
            return volts.indices.map { i -> PhaseReading("L${i + 1}", round1(volts[i]!!.toDouble()), amps[i]?.toDouble()?.let(::round2), round2(freq), null) }
        }
        val v = m[Measure.GRID_VOLTAGE] ?: return emptyList()
        return listOf(PhaseReading("L1", round1(v.toDouble()), m[Measure.GRID_CURRENT]?.toDouble()?.let(::round2), round2(freq), m[Measure.GRID_POWER]?.roundToInt()))
    }

    private fun problems(st: State): List<DeviceProblem> {
        val list = mutableListOf<DeviceProblem>()
        val alarm = AuroraProtocol.ALARMS[st.alarm] ?: "Alarm ${st.alarm}"
        when (st.alarm) {
            in AuroraProtocol.FAULT_ALARMS -> list += DeviceProblem(DeviceProblem.Kind.FAULT, alarm)
            in AuroraProtocol.GRID_ALARMS -> list += DeviceProblem(DeviceProblem.Kind.MAINS_LOST, alarm)
            in AuroraProtocol.WARNING_ALARMS -> list += DeviceProblem(DeviceProblem.Kind.WARNING, alarm)
        }
        if (st.global in AuroraProtocol.FAULT_STATES) {
            list += DeviceProblem(DeviceProblem.Kind.FAULT, "${AuroraProtocol.GLOBAL_STATES[st.global]} (state ${st.global})")
        }
        if (st.inverter in AuroraProtocol.GRID_LOST_INVERTER_STATES && list.none { it.kind == DeviceProblem.Kind.MAINS_LOST }) {
            list += DeviceProblem(DeviceProblem.Kind.MAINS_LOST, "${AuroraProtocol.INVERTER_STATES[st.inverter]} (inverter state ${st.inverter})")
        }
        return list
    }

    private fun details(st: State, m: Map<Int, Float>): List<DetailSection> {
        val id = info
        val s = slow.measures
        val e = slow.energyWh
        fun kwh(wh: Long?) = wh?.let { fmt(it / 1000.0, "kWh", if (it >= 1_000_000) 0 else 1) }
        return listOf(
            section(
                "Inverter",
                "Model" to id?.let { modelName(it) },
                "Model code" to id?.modelCode?.let { "'$it' (${it.code})" },
                "Part number" to id?.partNumber,
                "Serial number" to id?.serial,
                "Firmware" to id?.firmware,
                "Manufactured" to id?.manufactured,
                "Grid standard code" to id?.gridStandard?.toString(),
                "Transformer" to id?.transformer?.let { when (it) { 'T' -> "Yes (T)"; 'N' -> "No (N)"; else -> "$it" } },
                "Aurora address" to address.toString(),
                "Inverter clock" to slow.clock?.format(CLOCK),
            ),
            section(
                "State",
                "Global state" to enum(st.global, AuroraProtocol.GLOBAL_STATES),
                "Inverter state" to enum(st.inverter, AuroraProtocol.INVERTER_STATES),
                "DC/DC 1 state" to enum(st.dcdc1, AuroraProtocol.DCDC_STATES),
                "DC/DC 2 state" to enum(st.dcdc2, AuroraProtocol.DCDC_STATES),
                "Alarm" to enum(st.alarm, AuroraProtocol.ALARMS),
                "Recent alarms" to slow.lastAlarms?.let { codes ->
                    if (codes.isEmpty()) "None" else codes.joinToString(", ") { AuroraProtocol.ALARMS[it] ?: "$it" }
                },
            ),
            section(
                "AC",
                "Power" to fmt(m[Measure.GRID_POWER]?.roundToInt(), "W"),
                "Grid voltage" to fmt(m[Measure.GRID_VOLTAGE]?.toDouble(), "V", 1),
                "Grid current" to fmt(m[Measure.GRID_CURRENT]?.toDouble(), "A", 2),
                "Frequency" to fmt(m[Measure.FREQUENCY]?.toDouble(), "Hz", 2),
                "Power peak today" to fmt(s[Measure.POWER_PEAK_TODAY]?.roundToInt(), "W"),
                "Power peak" to fmt(s[Measure.POWER_PEAK]?.roundToInt(), "W"),
            ),
            section(
                "DC",
                "Input 1 voltage" to fmt(m[Measure.INPUT1_VOLTAGE]?.toDouble(), "V", 1),
                "Input 1 current" to fmt(m[Measure.INPUT1_CURRENT]?.toDouble(), "A", 2),
                "Input 1 power" to fmt(m[Measure.INPUT1_POWER]?.roundToInt(), "W"),
                "Input 2 voltage" to fmt(m[Measure.INPUT2_VOLTAGE]?.toDouble(), "V", 1),
                "Input 2 current" to fmt(m[Measure.INPUT2_CURRENT]?.toDouble(), "A", 2),
                "Input 2 power" to fmt(m[Measure.INPUT2_POWER]?.roundToInt(), "W"),
                "Bulk voltage" to fmt(s[Measure.BULK_VOLTAGE]?.toDouble(), "V", 1),
                "Isolation resistance" to fmt(s[Measure.ISOLATION_RESISTANCE]?.toDouble(), "MΩ", 2),
                "Leak current DC/DC" to fmt(s[Measure.LEAK_CURRENT_DCDC]?.toDouble(), "A", 3),
                "Leak current inverter" to fmt(s[Measure.LEAK_CURRENT_INVERTER]?.toDouble(), "A", 3),
            ),
            section(
                "Temperatures",
                "Inverter" to fmt(m[Measure.INVERTER_TEMPERATURE]?.toDouble(), "°C", 1),
                "Booster" to fmt(s[Measure.BOOSTER_TEMPERATURE]?.toDouble(), "°C", 1),
            ),
            section(
                "Energy",
                "Today" to kwh(e[Period.DAY]),
                "This week" to kwh(e[Period.WEEK]),
                "This month" to kwh(e[Period.MONTH]),
                "This year" to kwh(e[Period.YEAR]),
                "Total" to kwh(e[Period.TOTAL]),
            ),
        ).filter { it.rows.isNotEmpty() }
    }

    private fun round1(v: Double) = (v * 10).roundToInt() / 10.0
    private fun round2(v: Double) = (v * 100).roundToInt() / 100.0

    /** The transport is shared with the other inverters on the bus and closed with the plant's connections. */
    override fun close() {}

    internal companion object {
        /** Energy counters, alarms and slow measurements: about once a minute at the 5 s poll interval. */
        const val SLOW_REFRESH_POLLS = 12
        /** A measurement that got no reply shows its last value for this long. */
        const val HOLD_MS = 60_000L
        /** More than any of these inverters makes between two energy reads (about a minute): 10 MWh. */
        const val MAX_TOTAL_STEP_WH = 10_000_000L

        /**
         * What each measurement can physically be; a value outside (most often a reply meant for another request,
         * or a counter read as a float) is dropped like a lost reply.
         */
        val RANGES: Map<Int, ClosedFloatingPointRange<Float>> = buildMap {
            val volts = 0f..1500f
            val amps = 0f..300f
            val watts = -1_000f..200_000f
            listOf(Measure.GRID_VOLTAGE, Measure.BULK_VOLTAGE, Measure.INPUT1_VOLTAGE, Measure.INPUT2_VOLTAGE,
                Measure.VOLTAGE_L1, Measure.VOLTAGE_L2, Measure.VOLTAGE_L3).forEach { put(it, volts) }
            listOf(Measure.GRID_CURRENT, Measure.INPUT1_CURRENT, Measure.INPUT2_CURRENT,
                Measure.CURRENT_L1, Measure.CURRENT_L2, Measure.CURRENT_L3).forEach { put(it, amps) }
            listOf(Measure.GRID_POWER, Measure.INPUT1_POWER, Measure.INPUT2_POWER, Measure.POWER_PEAK, Measure.POWER_PEAK_TODAY)
                .forEach { put(it, watts) }
            put(Measure.FREQUENCY, 0f..70f)
            put(Measure.INVERTER_TEMPERATURE, -40f..150f)
            put(Measure.BOOSTER_TEMPERATURE, -40f..150f)
            put(Measure.ISOLATION_RESISTANCE, 0f..100_000f)
            put(Measure.LEAK_CURRENT_DCDC, 0f..100f)
            put(Measure.LEAK_CURRENT_INVERTER, 0f..100f)
        }

        /** Transmission states meaning the inverter doesn't have the command or variable. */
        val UNKNOWN = setOf(51, 52)
        private val CLOCK = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        val FAST = listOf(
            Measure.GRID_POWER, Measure.GRID_VOLTAGE, Measure.GRID_CURRENT, Measure.FREQUENCY,
            Measure.VOLTAGE_L1, Measure.VOLTAGE_L2, Measure.VOLTAGE_L3, Measure.CURRENT_L1, Measure.CURRENT_L2, Measure.CURRENT_L3,
            Measure.INPUT1_VOLTAGE, Measure.INPUT1_CURRENT, Measure.INPUT1_POWER,
            Measure.INPUT2_VOLTAGE, Measure.INPUT2_CURRENT, Measure.INPUT2_POWER,
            Measure.INVERTER_TEMPERATURE,
        )
        val SLOW = listOf(
            Measure.BOOSTER_TEMPERATURE, Measure.ISOLATION_RESISTANCE, Measure.LEAK_CURRENT_DCDC, Measure.LEAK_CURRENT_INVERTER,
            Measure.BULK_VOLTAGE, Measure.POWER_PEAK, Measure.POWER_PEAK_TODAY,
        )
    }
}
