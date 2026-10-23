// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.modbus

import java.io.Closeable

/** One [ModbusTcpClient] per host:port, shared by all devices (unit ids) behind it. */
class ModbusConnections : Closeable {
    private val clients = mutableMapOf<String, ModbusTcpClient>()

    @Synchronized
    fun get(host: String, port: Int): ModbusTcpClient = clients.getOrPut("$host:$port") { ModbusTcpClient(host, port) }

    @Synchronized
    override fun close() {
        clients.values.forEach { it.close() }
        clients.clear()
    }
}
