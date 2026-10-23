// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.modbus

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ModbusTcpTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    /** Builds a read response the way a device would: MBAP + function + byte count + data. */
    private fun response(transactionId: Int, data: ByteArray): ByteArray {
        val length = 3 + data.size // unit + function + byte count + data
        return bytes(transactionId shr 8, transactionId and 0xFF, 0, 0, length shr 8, length and 0xFF, 0xF7, 0x03, data.size) + data
    }

    @Test
    fun buildsEtRunningDataRequest() {
        assertArrayEquals(
            bytes(0x00, 0x01, 0x00, 0x00, 0x00, 0x06, 0xF7, 0x03, 0x89, 0x1C, 0x00, 0x7D),
            ModbusTcp.readRequest(transactionId = 1, unitId = 0xF7, register = 35100, count = 0x7D),
        )
    }

    @Test
    fun parsesValidResponse() {
        val frame = response(0x1234, bytes(0x12, 0x34, 0xFF, 0xFE))
        assertEquals(0x1234, ModbusTcp.transactionId(frame))
        assertEquals(frame.size - ModbusTcp.MBAP_SIZE, ModbusTcp.remainingLength(frame.copyOf(ModbusTcp.MBAP_SIZE)))
        assertArrayEquals(bytes(0x12, 0x34, 0xFF, 0xFE), ModbusTcp.parseReadResponse(frame, 0x1234, 2))
    }

    @Test
    fun readsInputRegistersWithFunction04() {
        // TRIO-50 spec example: global state, register 1050 sent as 1049 (0x0419), inverter address 2.
        assertArrayEquals(
            bytes(0x00, 0x05, 0x00, 0x00, 0x00, 0x06, 0x02, 0x04, 0x04, 0x19, 0x00, 0x01),
            ModbusTcp.readRequest(transactionId = 5, unitId = 2, register = 1049, count = 1, function = ModbusTcp.READ_INPUT_REGISTERS),
        )
        val answer = bytes(0x00, 0x05, 0x00, 0x00, 0x00, 0x05, 0x02, 0x04, 0x02, 0x00, 0x06)
        assertArrayEquals(bytes(0x00, 0x06), ModbusTcp.parseReadResponse(answer, 5, 1, ModbusTcp.READ_INPUT_REGISTERS))
    }

    @Test(expected = ProtocolException::class)
    fun rejectsAnswerToAnotherFunction() {
        ModbusTcp.parseReadResponse(response(1, bytes(0x00, 0x01)), 1, 1, ModbusTcp.READ_INPUT_REGISTERS)
    }

    @Test(expected = ProtocolException::class)
    fun rejectsWrongTransactionId() {
        ModbusTcp.parseReadResponse(response(7, bytes(0x00, 0x01)), 8, 1)
    }

    @Test(expected = ProtocolException::class)
    fun rejectsWrongRegisterCount() {
        ModbusTcp.parseReadResponse(response(1, bytes(0x00, 0x01)), 1, 2)
    }

    @Test
    fun reportsModbusExceptionCode() {
        try {
            ModbusTcp.parseReadResponse(bytes(0x00, 0x01, 0x00, 0x00, 0x00, 0x03, 0xF7, 0x83, 0x02), 1, 1)
            fail("expected ModbusException")
        } catch (e: ModbusException) {
            assertEquals(2, e.code)
        }
    }

    @Test
    fun decodesSignedAndWideRegisters() {
        val block = RegisterBlock(35180, bytes(0xFF, 0x38, 0xFF, 0xFF, 0xFC, 0x18, 0x00, 0x01, 0x86, 0xA0))
        assertEquals(-200, block.s16(35180))
        assertEquals(-1000, block.s32(35181))
        assertEquals(100_000L, block.u32(35183))
    }
}
