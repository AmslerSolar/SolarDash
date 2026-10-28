// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.modbus

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/** Function 43/14, Read Device Identification: framing, streaming over several answers, and both transports. */
class ModbusDeviceIdTest {
    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** An answer PDU with [objects] (id to text). */
    private fun answer(code: Int, conformity: Int, next: Int?, vararg objects: Pair<Int, String>): ByteArray {
        var pdu = byteArrayOf(0x2B, 0x0E, code.toByte(), conformity.toByte(), if (next != null) 0xFF.toByte() else 0, (next ?: 0).toByte(), objects.size.toByte())
        for ((id, text) in objects) pdu += byteArrayOf(id.toByte(), text.length.toByte()) + text.toByteArray()
        return pdu
    }

    @Test
    fun parsesAnAnswerAndAnException() {
        val a = ModbusDeviceId.parse(answer(1, 0x81, null, 0 to "ABB", 1 to "B23", 2 to "1.2"))
        assertEquals(0x81, a.conformity)
        assertEquals(listOf(0, 1, 2), a.objects.map { it.first })
        assertEquals("ABB", String(a.objects[0].second))
        val e = assertThrows(ModbusException::class.java) { ModbusDeviceId.parse(hex("AB 01")) }
        assertEquals(1, e.code)
        assertThrows(ProtocolException::class.java) { ModbusDeviceId.parse(answer(1, 1, null, 0 to "ABB").copyOf(9)) }
    }

    @Test
    fun readsEveryLevelUpToTheConformityFollowingMoreFollows() = runBlocking {
        val asked = mutableListOf<Pair<Int, Int>>()
        val id = ModbusDeviceId.readAll { code, objectId ->
            asked += code to objectId
            ModbusDeviceId.parse(
                when (code to objectId) {
                    1 to 0 -> answer(1, 0x02, 2, 0 to "Eastron", 1 to "SDM630")
                    1 to 2 -> answer(1, 0x02, null, 2 to "3.04")
                    2 to 3 -> answer(2, 0x02, null, 3 to "eastron.com", 4 to "SDM630 Modbus")
                    else -> error("asked $code/$objectId")
                },
            )
        }
        assertEquals(listOf(1 to 0, 1 to 2, 2 to 3), asked) // no extended level: the device is regular (2)
        assertEquals(listOf(0, 1, 2, 3, 4), id.objects.keys.toList())
        assertEquals("\"SDM630 Modbus\"", ModbusDeviceId.text(id.objects.getValue(4)))
    }

    @Test
    fun aDeviceWithoutItIsAskedOnce() = runBlocking {
        var asked = 0
        val refused = ModbusDeviceId.readAll { _, _ -> asked++; throw ModbusException(1) }
        val silent = ModbusDeviceId.readAll { _, _ -> asked++; throw SocketTimeoutException("Read timed out") }
        assertEquals(0, refused.conformity)
        assertEquals(0, silent.conformity)
        assertEquals(2, asked)
    }

    @Test
    fun anRtuAnswerIsReadObjectByObject() {
        val frame = ModbusRtu.withCrc(byteArrayOf(11) + answer(1, 1, null, 0 to "CHINT", 1 to "DTSU666"))
        val input = DataInputStream(ByteArrayInputStream(frame + hex("0B 03"))) // the next frame must stay unread
        assertArrayEquals(frame, ModbusDeviceId.readRtuFrame(input))
        assertEquals(2, input.available())
        assertArrayEquals(ModbusRtu.withCrc(hex("0B AB 02")), ModbusDeviceId.readRtuFrame(DataInputStream(ByteArrayInputStream(ModbusRtu.withCrc(hex("0B AB 02"))))))
    }

    @Test
    fun theClientAsksOverTcpAndRtu() = runBlocking {
        for (rtu in listOf(false, true)) {
            ServerSocket(0).use { server ->
                var request = ByteArray(0)
                thread(isDaemon = true) {
                    server.accept().use { s ->
                        val n = if (rtu) 7 else 11
                        request = ByteArray(n).also { DataInputStream(s.getInputStream()).readFully(it) }
                        val pdu = answer(1, 1, null, 0 to "CHINT")
                        s.getOutputStream().write(
                            if (rtu) ModbusRtu.withCrc(byteArrayOf(11) + pdu)
                            else byteArrayOf(request[0], request[1], 0, 0, 0, (pdu.size + 1).toByte(), 11) + pdu,
                        )
                    }
                }
                ModbusTcpClient("127.0.0.1", server.localPort, rtu = rtu).use { c ->
                    val a = c.readDeviceIdentification(11, 1, 0)
                    assertEquals("CHINT", String(a.objects.single().second))
                }
                val pdu = if (rtu) request.copyOfRange(1, 5) else request.copyOfRange(7, 11)
                assertArrayEquals(hex("2B 0E 01 00"), pdu)
            }
        }
    }
}
