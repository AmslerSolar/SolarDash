// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.driver.modbus

import java.io.Closeable

/** One [ModbusTcpClient] per host:port (and framing), shared by all devices (unit ids) behind it. */
class ModbusConnections(private val timeoutMs: Int = 3_000) : Closeable {
    private val clients = mutableMapOf<String, ModbusTcpClient>()
    private val others = mutableMapOf<String, Closeable>()

    @Synchronized
    fun get(host: String, port: Int, rtu: Boolean = false): ModbusTcpClient =
        clients.getOrPut("$host:$port" + if (rtu) ":rtu" else "") { ModbusTcpClient(host, port, timeoutMs = timeoutMs, rtu = rtu) }

    /** The client for a configured device: its address, and RTU framing for a gateway in transparent mode. */
    fun get(config: ch.amslersolar.solardash.model.DeviceConfig): ModbusTcpClient = get(config.host, config.port, config.rtuOverTcp)

    /** Another link shared by the devices behind one gateway (e.g. an Aurora RS485 bus), closed with the rest. */
    @Synchronized
    @Suppress("UNCHECKED_CAST")
    fun <T : Closeable> shared(key: String, create: () -> T): T = others.getOrPut(key, create) as T

    @Synchronized
    override fun close() {
        clients.values.forEach { it.close() }
        clients.clear()
        others.values.forEach { runCatching { it.close() } }
        others.clear()
    }
}
