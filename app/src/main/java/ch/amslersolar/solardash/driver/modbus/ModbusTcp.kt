// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.driver.modbus

import java.io.IOException

open class ProtocolException(message: String) : IOException(message)

/** The device answered with a Modbus exception, e.g. code 2 for a register it doesn't have. */
class ModbusException(val code: Int) : ProtocolException("Modbus exception $code")

/** Modbus TCP framing: 7-byte MBAP header (transaction, protocol 0, length, unit) followed by the PDU. No CRC. */
object ModbusTcp {
    const val MBAP_SIZE = 7
    const val READ_HOLDING_REGISTERS = 0x03
    /** Read-only measurement registers, e.g. Power-One / ABB TRIO. */
    const val READ_INPUT_REGISTERS = 0x04

    fun readRequest(
        transactionId: Int,
        unitId: Int,
        register: Int,
        count: Int,
        function: Int = READ_HOLDING_REGISTERS,
    ): ByteArray = byteArrayOf(
        (transactionId shr 8).toByte(), transactionId.toByte(),
        0, 0,
        0, 6,
        unitId.toByte(),
        function.toByte(),
        (register shr 8).toByte(), register.toByte(),
        (count shr 8).toByte(), count.toByte(),
    )

    fun transactionId(frame: ByteArray): Int = u16(frame, 0)

    /** Bytes still to read after the MBAP header, from its length field (which counts the unit id). */
    fun remainingLength(header: ByteArray): Int {
        val length = u16(header, 4) - 1
        if (length !in 2..253) throw ProtocolException("Invalid MBAP length $length")
        return length
    }

    /** Validates a whole response frame and returns the raw register bytes. */
    fun parseReadResponse(frame: ByteArray, transactionId: Int, expectedCount: Int, function: Int = READ_HOLDING_REGISTERS): ByteArray {
        if (frame.size < MBAP_SIZE + 2) throw ProtocolException("Response too short (${frame.size} bytes)")
        if (u16(frame, 0) != transactionId) throw ProtocolException("Transaction id mismatch")
        if (u16(frame, 2) != 0) throw ProtocolException("Not a Modbus TCP frame")
        if (u16(frame, 4) != frame.size - 6) throw ProtocolException("Length field does not match frame")

        val answered = frame[7].toInt() and 0xFF
        if (answered and 0x80 != 0) throw ModbusException(frame[8].toInt() and 0xFF)
        if (answered != function) throw ProtocolException("Unexpected function code $answered")

        val byteCount = frame[8].toInt() and 0xFF
        if (byteCount != frame.size - MBAP_SIZE - 2) throw ProtocolException("Byte count $byteCount does not match frame")
        if (byteCount != expectedCount * 2) throw ProtocolException("Expected ${expectedCount * 2} data bytes, got $byteCount")
        return frame.copyOfRange(MBAP_SIZE + 2, MBAP_SIZE + 2 + byteCount)
    }

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
}

/** Big-endian register view over a contiguous block starting at [start]. */
class RegisterBlock(val start: Int, private val data: ByteArray) {
    /** Number of registers in the block. */
    val count: Int get() = data.size / 2

    /** This block followed directly by [next] (which must start where this one ends). */
    operator fun plus(next: RegisterBlock): RegisterBlock {
        require(next.start == start + count) { "Block ${next.start} doesn't follow ${start}..${start + count - 1}" }
        return RegisterBlock(start, data + next.data)
    }

    private fun index(register: Int): Int {
        val i = (register - start) * 2
        require(i >= 0 && i + 2 <= data.size) { "Register $register is outside block $start..${start + data.size / 2 - 1}" }
        return i
    }

    fun u16(register: Int): Int {
        val i = index(register)
        return ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
    }

    fun s16(register: Int): Int = u16(register).toShort().toInt()

    fun u32(register: Int): Long = (u16(register).toLong() shl 16) or u16(register + 1).toLong()

    fun s32(register: Int): Int = (u16(register) shl 16) or u16(register + 1)

    fun s64(register: Int): Long = (u32(register) shl 32) or u32(register + 2)

    fun f32(register: Int): Float = Float.fromBits(s32(register))

    fun ascii(register: Int, registerCount: Int): String {
        val i = index(register)
        index(register + registerCount - 1)
        return String(data, i, registerCount * 2, Charsets.US_ASCII).trim { it <= ' ' }
    }
}
