// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.driver

import ch.amslersolar.solardash.driver.enphase.EnphaseAuthException
import ch.amslersolar.solardash.driver.lg.LgEssAuthException
import ch.amslersolar.solardash.driver.modbus.ModbusException
import ch.amslersolar.solardash.model.ConnectionError
import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** What kind of failure a device read was, so the plant status can say what to check. */
fun classifyConnectionError(e: Throwable): ConnectionError = when (e) {
    is LgEssAuthException, is EnphaseAuthException -> ConnectionError.LOGIN
    is ModbusException -> ConnectionError.DEVICE_REPLY
    is SocketTimeoutException -> ConnectionError.TIMEOUT
    is NoRouteToHostException, is UnknownHostException, is PortUnreachableException -> ConnectionError.UNREACHABLE
    is ConnectException ->
        if (e.message.orEmpty().contains("refused", ignoreCase = true)) ConnectionError.REFUSED else ConnectionError.UNREACHABLE
    is EOFException -> ConnectionError.CLOSED
    is SocketException ->
        if (e.message.orEmpty().contains("unreachable", ignoreCase = true)) ConnectionError.UNREACHABLE else ConnectionError.CLOSED
    else -> when {
        e.message.orEmpty().startsWith("SunSpec map") -> ConnectionError.NOT_READY // garbled map while it starts
        e.message.orEmpty().contains("closed the connection", ignoreCase = true) -> ConnectionError.CLOSED
        e.message.orEmpty().contains("timed out", ignoreCase = true) -> ConnectionError.TIMEOUT
        else -> ConnectionError.OTHER
    }
}
