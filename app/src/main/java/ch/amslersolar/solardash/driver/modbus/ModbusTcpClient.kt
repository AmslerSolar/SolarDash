// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.driver.modbus

import ch.amslersolar.solardash.driver.net.DeviceNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Keeps one TCP connection open between polls. Many inverter dongles accept only one or two Modbus TCP
 * clients at a time, so share one client per host (see [ModbusConnections]) and [close] it when polling stops.
 * Requests are serialized, so several unit ids can use the same client.
 *
 * [rtu]: an RS485 gateway in transparent mode, which passes the bytes on as they are: the requests are Modbus
 * RTU frames ([ModbusRtu]) instead of Modbus TCP. Without a transaction id, a late answer to a request that
 * timed out can't be told from the next one by id; its unit, function, length and CRC are checked, and a
 * mismatch drops the connection (and with it what's still on its way).
 */
class ModbusTcpClient(
    private val host: String,
    private val port: Int,
    private val timeoutMs: Int = 3_000,
    private val rtu: Boolean = false,
    /** Send a request again on a new connection when it got no answer (off for quick presence checks). */
    private val retry: Boolean = true,
) : Closeable {
    private val mutex = Mutex()
    private var socket: Socket? = null

    /** Some devices (Huawei) ignore requests sent right after the TCP connection opens. */
    @Volatile
    var connectDelayMs: Long = 0

    /** Gets every frame as it goes out ([outgoing]) or comes in, for recording the raw traffic (register sweeps). */
    @Volatile
    var traffic: ((outgoing: Boolean, frame: ByteArray) -> Unit)? = null

    /** Told when a request got no answer and is sent again on a new connection (with why). */
    @Volatile
    var retrying: ((why: String) -> Unit)? = null

    /** How long to wait for an answer; [timeoutMs] unless changed (register sweeps set it from [answered]). */
    @Volatile
    var answerTimeoutMs: Int = timeoutMs

    /** Told how long each answer (or refusal) took, in ms, from the request going out until it was in. */
    @Volatile
    var answered: ((ms: Long) -> Unit)? = null
    private var nextTransactionId = 1

    suspend fun readHoldingRegisters(unitId: Int, register: Int, count: Int): RegisterBlock =
        read(unitId, register, count, ModbusTcp.READ_HOLDING_REGISTERS)

    /** Function 04: read-only measurement registers (Power-One / ABB TRIO keeps its live values there). */
    suspend fun readInputRegisters(unitId: Int, register: Int, count: Int): RegisterBlock =
        read(unitId, register, count, ModbusTcp.READ_INPUT_REGISTERS)

    /** Function 43/14: one answer of the device's identification objects ([ModbusDeviceId]), read only. */
    suspend fun readDeviceIdentification(unitId: Int, readCode: Int, objectId: Int): ModbusDeviceId.Answer =
        call { ModbusDeviceId.parse(exchangePdu(unitId, ModbusDeviceId.pdu(readCode, objectId))) }

    private suspend fun read(unitId: Int, register: Int, count: Int, function: Int): RegisterBlock =
        call { exchange(unitId, register, count, function) }

    /** Runs [exchange] alone on the connection, once more on a new connection when it got no answer. */
    private suspend fun <T> call(exchange: () -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                exchange()
            } catch (e: ModbusException) {
                throw e
            } catch (e: IOException) {
                if (!retry) {
                    disconnect()
                    throw e
                }
                // Dongles drop idle or stale connections; reconnect once before giving up.
                retrying?.invoke(e.message ?: e::class.simpleName.orEmpty())
                disconnect()
                try {
                    exchange()
                } catch (e: IOException) {
                    if (e !is ModbusException) disconnect()
                    throw e
                }
            }
        }
    }

    private fun connected(): Socket {
        val s = socket ?: DeviceNetwork.current.socket(host).also {
            it.connect(InetSocketAddress(host, port), timeoutMs)
            it.soTimeout = timeoutMs
            it.tcpNoDelay = true
            socket = it
            if (connectDelayMs > 0) Thread.sleep(connectDelayMs) // runs on Dispatchers.IO
        }
        s.soTimeout = answerTimeoutMs
        return s
    }

    /** Sends [pdu] to [unitId] and returns the answer's PDU (function code onwards); only for device identification. */
    private fun exchangePdu(unitId: Int, pdu: ByteArray): ByteArray {
        val s = connected()
        val request = if (rtu) ModbusRtu.withCrc(byteArrayOf(unitId.toByte()) + pdu) else {
            val transactionId = nextTransactionId
            nextTransactionId = (nextTransactionId + 1) and 0xFFFF
            byteArrayOf(
                (transactionId shr 8).toByte(), transactionId.toByte(), 0, 0,
                ((pdu.size + 1) shr 8).toByte(), (pdu.size + 1).toByte(), unitId.toByte(),
            ) + pdu
        }
        s.getOutputStream().apply {
            traffic?.invoke(true, request)
            write(request)
            flush()
        }
        val sent = System.nanoTime()
        val input = DataInputStream(s.getInputStream())
        if (rtu) {
            val frame = ModbusDeviceId.readRtuFrame(input)
            traffic?.invoke(false, frame)
            answered?.invoke((System.nanoTime() - sent) / 1_000_000)
            return ModbusRtu.pdu(frame, unitId)
        }
        val transactionId = ModbusTcp.transactionId(request)
        while (true) {
            val header = ByteArray(ModbusTcp.MBAP_SIZE).also(input::readFully)
            val body = ByteArray(ModbusTcp.remainingLength(header)).also(input::readFully)
            traffic?.invoke(false, header + body)
            if (ModbusTcp.transactionId(header) != transactionId) continue // a late answer to an earlier request
            answered?.invoke((System.nanoTime() - sent) / 1_000_000)
            return body
        }
    }

    private fun exchange(unitId: Int, register: Int, count: Int, function: Int): RegisterBlock {
        val s = connected()
        if (rtu) return exchangeRtu(s, unitId, register, count, function)
        val transactionId = nextTransactionId
        nextTransactionId = (nextTransactionId + 1) and 0xFFFF

        s.getOutputStream().apply {
            val request = ModbusTcp.readRequest(transactionId, unitId, register, count, function)
            traffic?.invoke(true, request)
            write(request)
            flush()
        }
        val sent = System.nanoTime()
        val input = DataInputStream(s.getInputStream())
        while (true) {
            val header = ByteArray(ModbusTcp.MBAP_SIZE).also(input::readFully)
            val body = ByteArray(ModbusTcp.remainingLength(header)).also(input::readFully)
            val frame = header + body
            traffic?.invoke(false, frame)
            // A late answer to an earlier request that timed out: skip it.
            if (ModbusTcp.transactionId(frame) != transactionId) continue
            answered?.invoke((System.nanoTime() - sent) / 1_000_000)
            return RegisterBlock(register, ModbusTcp.parseReadResponse(frame, transactionId, count, function))
        }
    }

    private fun exchangeRtu(s: Socket, unitId: Int, register: Int, count: Int, function: Int): RegisterBlock {
        s.getOutputStream().apply {
            val request = ModbusRtu.readRequest(unitId, register, count, function)
            traffic?.invoke(true, request)
            write(request)
            flush()
        }
        val sent = System.nanoTime()
        val input = DataInputStream(s.getInputStream())
        val head = ByteArray(3).also(input::readFully)
        val rest = ByteArray(ModbusRtu.remainingLength(head)).also(input::readFully)
        traffic?.invoke(false, head + rest)
        answered?.invoke((System.nanoTime() - sent) / 1_000_000)
        return RegisterBlock(register, ModbusRtu.parseReadResponse(head + rest, unitId, count, function))
    }

    private fun disconnect() {
        runCatching { socket?.close() }
        socket = null
    }

    override fun close() = disconnect()
}
