// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.modbus

/**
 * Modbus RTU framing, for RS485 gateways in transparent mode ("RTU over TCP"): the TCP stream carries the serial
 * frames as they are, unit id + PDU + CRC-16 (low byte first), without the MBAP header and its transaction id.
 */
object ModbusRtu {
    fun readRequest(unitId: Int, register: Int, count: Int, function: Int = ModbusTcp.READ_HOLDING_REGISTERS): ByteArray =
        withCrc(
            byteArrayOf(
                unitId.toByte(), function.toByte(),
                (register shr 8).toByte(), register.toByte(),
                (count shr 8).toByte(), count.toByte(),
            ),
        )

    /** Bytes of the answer after its first three (unit, function, byte count or exception code), CRC included. */
    fun remainingLength(head: ByteArray): Int =
        if (head[1].toInt() and 0x80 != 0) 2 else (head[2].toInt() and 0xFF) + 2

    /** Validates a whole answer (CRC, unit, function, byte count) and returns the raw register bytes. */
    fun parseReadResponse(frame: ByteArray, unitId: Int, expectedCount: Int, function: Int = ModbusTcp.READ_HOLDING_REGISTERS): ByteArray {
        if (frame.size < 5) throw ProtocolException("Response too short (${frame.size} bytes)")
        if (!crc(frame, frame.size - 2).contentEquals(frame.copyOfRange(frame.size - 2, frame.size))) throw ProtocolException("CRC mismatch")
        if (frame[0].toInt() and 0xFF != unitId) throw ProtocolException("Answer from unit ${frame[0].toInt() and 0xFF}, asked $unitId")
        val answered = frame[1].toInt() and 0xFF
        if (answered and 0x80 != 0) throw ModbusException(frame[2].toInt() and 0xFF)
        if (answered != function) throw ProtocolException("Unexpected function code $answered")
        val byteCount = frame[2].toInt() and 0xFF
        if (byteCount != expectedCount * 2) throw ProtocolException("Expected ${expectedCount * 2} data bytes, got $byteCount")
        return frame.copyOfRange(3, 3 + byteCount)
    }

    fun withCrc(b: ByteArray): ByteArray = b + crc(b, b.size)

    /** CRC-16/Modbus of the first [n] bytes, low byte first as on the wire. */
    private fun crc(b: ByteArray, n: Int): ByteArray {
        var c = 0xFFFF
        for (i in 0 until n) {
            c = c xor (b[i].toInt() and 0xFF)
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xA001 else c ushr 1 }
        }
        return byteArrayOf(c.toByte(), (c shr 8).toByte())
    }
}
