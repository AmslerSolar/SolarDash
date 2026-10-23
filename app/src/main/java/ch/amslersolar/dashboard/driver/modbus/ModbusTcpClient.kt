// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.modbus

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
 */
class ModbusTcpClient(
    private val host: String,
    private val port: Int,
    private val timeoutMs: Int = 3_000,
) : Closeable {
    private val mutex = Mutex()
    private var socket: Socket? = null

    /** Some devices (Huawei) ignore requests sent right after the TCP connection opens. */
    @Volatile
    var connectDelayMs: Long = 0
    private var nextTransactionId = 1

    suspend fun readHoldingRegisters(unitId: Int, register: Int, count: Int): RegisterBlock =
        read(unitId, register, count, ModbusTcp.READ_HOLDING_REGISTERS)

    /** Function 04: read-only measurement registers (Power-One / ABB TRIO keeps its live values there). */
    suspend fun readInputRegisters(unitId: Int, register: Int, count: Int): RegisterBlock =
        read(unitId, register, count, ModbusTcp.READ_INPUT_REGISTERS)

    private suspend fun read(unitId: Int, register: Int, count: Int, function: Int): RegisterBlock = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                exchange(unitId, register, count, function)
            } catch (e: ModbusException) {
                throw e
            } catch (_: IOException) {
                // Dongles drop idle or stale connections; reconnect once before giving up.
                disconnect()
                try {
                    exchange(unitId, register, count, function)
                } catch (e: IOException) {
                    if (e !is ModbusException) disconnect()
                    throw e
                }
            }
        }
    }

    private fun exchange(unitId: Int, register: Int, count: Int, function: Int): RegisterBlock {
        val s = socket ?: Socket().also {
            it.connect(InetSocketAddress(host, port), timeoutMs)
            it.soTimeout = timeoutMs
            it.tcpNoDelay = true
            socket = it
            if (connectDelayMs > 0) Thread.sleep(connectDelayMs) // runs on Dispatchers.IO
        }
        val transactionId = nextTransactionId
        nextTransactionId = (nextTransactionId + 1) and 0xFFFF

        s.getOutputStream().apply {
            write(ModbusTcp.readRequest(transactionId, unitId, register, count, function))
            flush()
        }
        val input = DataInputStream(s.getInputStream())
        while (true) {
            val header = ByteArray(ModbusTcp.MBAP_SIZE).also(input::readFully)
            val body = ByteArray(ModbusTcp.remainingLength(header)).also(input::readFully)
            val frame = header + body
            // A late answer to an earlier request that timed out: skip it.
            if (ModbusTcp.transactionId(frame) != transactionId) continue
            return RegisterBlock(register, ModbusTcp.parseReadResponse(frame, transactionId, count, function))
        }
    }

    private fun disconnect() {
        runCatching { socket?.close() }
        socket = null
    }

    override fun close() = disconnect()
}
