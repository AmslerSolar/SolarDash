// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.driver.modbus

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

/** Modbus RTU over TCP, for RS485 gateways in transparent mode. */
class ModbusRtuTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun framesCarryTheModbusCrc() {
        assertArrayEquals(hex("01030000000184 0a".replace(" ", "")), ModbusRtu.readRequest(1, 0, 1))
    }

    /** As a Huawei DTSU666-H (unit 11) answered through a Waveshare gateway: L1 voltage 235.8 V at 2110. */
    @Test
    fun aRealAnswerIsRead() {
        val data = ModbusRtu.parseReadResponse(hex("0b0304436bcccda0fe"), 11, 2)
        assertEquals(235.8f, RegisterBlock(2110, data).f32(2110), 0.01f)
        assertThrows(ProtocolException::class.java) { ModbusRtu.parseReadResponse(hex("0b0304436bcccda0ff"), 11, 2) } // CRC
        assertThrows(ProtocolException::class.java) { ModbusRtu.parseReadResponse(hex("0b0304436bcccda0fe"), 12, 2) } // other unit
        val e = assertThrows(ModbusException::class.java) { ModbusRtu.parseReadResponse(ModbusRtu.withCrc(hex("0b8302")), 11, 2) }
        assertEquals(2, e.code)
    }

    @Test
    fun theClientSpeaksRtuToAGateway() = runBlocking {
        ServerSocket(0).use { server ->
            val asked = mutableListOf<String>()
            thread(isDaemon = true) {
                server.accept().use { s ->
                    val input = s.getInputStream()
                    repeat(2) {
                        val req = ByteArray(8).also { var n = 0; while (n < 8) n += input.read(it, n, 8 - n) }
                        asked += req.joinToString("") { "%02x".format(it) }
                        s.getOutputStream().write(ModbusRtu.withCrc(hex("0b0304436bcccd")))
                    }
                }
            }
            ModbusTcpClient("127.0.0.1", server.localPort, rtu = true).use { c ->
                assertEquals(235.8f, c.readHoldingRegisters(11, 2110, 2).f32(2110), 0.01f)
                assertEquals(235.8f, c.readHoldingRegisters(11, 2110, 2).f32(2110), 0.01f)
            }
            assertEquals(ModbusRtu.readRequest(11, 2110, 2).joinToString("") { "%02x".format(it) }, asked.first())
        }
    }

    /** A lost answer: the client says so, connects anew and sends the request again; the traffic shows both. */
    @Test
    fun aLostAnswerIsToldAndSentAgain() = runBlocking {
        ServerSocket(0).use { server ->
            thread(isDaemon = true) {
                val first = server.accept() // never answers; the client gives up on it and connects again
                thread(isDaemon = true) { first.use { it.getInputStream().read(ByteArray(8)); Thread.sleep(2_000) } }
                server.accept().use { s ->
                    s.getInputStream().read(ByteArray(8))
                    s.getOutputStream().write(ModbusRtu.withCrc(hex("0b0304436bcccd")))
                }
            }
            val told = mutableListOf<String>()
            val frames = mutableListOf<Boolean>()
            ModbusTcpClient("127.0.0.1", server.localPort, timeoutMs = 300, rtu = true).use { c ->
                c.retrying = { told += it }
                c.traffic = { outgoing, _ -> frames += outgoing }
                assertEquals(235.8f, c.readHoldingRegisters(11, 2110, 2).f32(2110), 0.01f)
            }
            assertEquals(1, told.size)
            assertEquals(listOf(true, true, false), frames)
        }
    }
}
