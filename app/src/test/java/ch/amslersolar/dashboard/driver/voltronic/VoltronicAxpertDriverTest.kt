// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.voltronic

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.time.LocalDate
import kotlin.concurrent.thread

class VoltronicAxpertDriverTest {
    /** Replies by command; a missing command answers NAK like the inverter does. */
    private class FakeAxpert(vararg replies: Pair<String, String>) : AxpertTransport {
        val replies = mutableMapOf(*replies)
        val asked = mutableListOf<String>()
        val timeouts = mutableSetOf<String>()
        var closed = false

        override suspend fun query(command: String): String {
            asked += command
            if (command in timeouts) throw SocketTimeoutException("$command: no reply")
            return replies[command] ?: throw AxpertNakException(command)
        }

        override fun close() {
            closed = true
        }
    }

    private val day = LocalDate.of(2026, 10, 21)

    private fun maxII() = FakeAxpert(
        "QPI" to "PI30",
        "QID" to "96332309100452",
        "QMN" to "MAX-8000",
        "QVFW" to "VERFW:00072.70",
        "QPIRI" to "230.0 34.7 230.0 50.0 34.7 8000 8000 48.0 48.0 45.0 56.4 54.0 8 040 120 0 2 1 9 01 0 0 52.0 0 1 480 0 150",
        "QPIGS" to "231.4 49.9 230.1 50.0 0920 0850 018 380 52.10 012 078 0035 08.5 310.2 52.08 00000 00010110 00 00 02650 010 0 00 0000 00.0",
        "QPIGS2" to "05.2 285.3 01480",
        "QMOD" to "L",
        "QPIWS" to "000001000000000000000000000000000000",
        "QET" to "04567890",
        "QED20261021" to "00012340",
        "QLT" to "03210000",
        "QLD20261021" to "00008100",
    )

    @Test
    fun identifyUsesQmnQidAndFirmware() = runBlocking {
        val info = VoltronicAxpertDriver(maxII()) { day }.identify()
        assertEquals("Voltronic MAX-8000 (PI30, firmware 00072.70)", info.model)
        assertEquals("96332309100452", info.serial)
    }

    @Test
    fun identifyWithoutQpiAndQmnFallsBackToRatings() = runBlocking {
        val ks = FakeAxpert(
            "QID" to "92931509101901",
            "QPIRI" to "230.0 21.7 230.0 50.0 21.7 5000 4000 48.0 46.0 42.0 56.4 54.0 0 30 060 0 2 3 9 01 0 0 54.0 0 1 000",
        )
        val info = VoltronicAxpertDriver(ks) { day }.identify()
        assertEquals("Voltronic Axpert 5000 VA 48 V", info.model)
        assertEquals("92931509101901", info.serial)
    }

    @Test
    fun identifyRejectsNonAxpertReplies() {
        // Something else answers: QPI isn't a protocol ID.
        val other = FakeAxpert("QPI" to "230.0 50.0", "QID" to "123456", "QPIRI" to "x")
        assertThrows(IOException::class.java) { runBlocking { VoltronicAxpertDriver(other).identify() } }
        // Every command NAKs.
        assertThrows(IOException::class.java) { runBlocking { VoltronicAxpertDriver(FakeAxpert()).identify() } }
        // Serial but no usable rated information.
        val noRatings = FakeAxpert("QPI" to "PI30", "QID" to "92931509101901", "QPIRI" to "NAKss")
        assertThrows(IOException::class.java) { runBlocking { VoltronicAxpertDriver(noRatings).identify() } }
        // Nothing answers at all.
        val silent = FakeAxpert().apply { timeouts += listOf("QPI", "QID", "QPIRI") }
        assertThrows(IOException::class.java) { runBlocking { VoltronicAxpertDriver(silent).identify() } }
    }

    @Test
    fun readMapsQpigsWithSecondPvInput() = runBlocking {
        val r = VoltronicAxpertDriver(maxII()) { day }.read()
        assertEquals(2650 + 1480, r.pvPowerW)
        assertEquals(listOf(310.2, 285.3), r.pvStrings.map { it.voltageV })
        assertEquals(2650, r.pvStrings[0].powerW)
        val b = r.battery!!
        assertEquals(-625, b.powerW) // charging
        assertEquals(78, b.socPercent)
        assertEquals(52.10, b.voltageV!!, 1e-9)
        assertEquals("Charging (solar)", b.mode)
        assertNull(r.gridPowerW)
        assertEquals(850, r.loadPowerW)
        val l1 = r.phases.single()
        assertEquals("L1", l1.name)
        assertEquals(231.4, l1.voltageV, 1e-9)
        assertEquals(49.9, l1.frequencyHz, 1e-9)
        assertEquals("Line", r.status)
        assertEquals(35.0, r.temperatureC!!, 1e-9)
        assertEquals(12.34, r.today.pvKwh!!, 1e-9)
        assertEquals(8.1, r.today.loadKwh!!, 1e-9)
        assertEquals(4567.89, r.lifetimePvKwh!!, 1e-9)
        val details = r.details.associate { it.title to it.rows.toMap() }
        assertEquals("Line fail", details["Warnings (QPIWS)"]!!["Active"])
        assertEquals("SBU (solar, battery, utility) (2)", details["Ratings (QPIRI)"]!!["Output source priority"])
        assertEquals("00010110", details["Status bits (QPIGS)"]!!["b7…b0"])
    }

    @Test
    fun ksUnitPollsMinimallyAndSkipsNakedCommands() = runBlocking {
        val ks = FakeAxpert(
            "QPIRI" to "230.0 21.7 230.0 50.0 21.7 5000 4000 48.0 46.0 42.0 56.4 54.0 0 30 060 0 2 3 9 01 0 0 54.0 0 1 000",
            "QPIGS" to "000.0 00.0 230.0 50.0 1150 1100 022 360 50.40 000 064 0040 0000 000.0 00.00 00023 00010000",
            "QMOD" to "B",
            "QPIWS" to "00000100000000000000000000000000",
        )
        val driver = VoltronicAxpertDriver(ks) { day }
        val r = driver.read()
        assertEquals(1159, r.battery!!.powerW) // discharging is positive
        assertEquals("Discharging", r.battery!!.mode)
        assertEquals(0, r.pvPowerW) // no PV power field: V × I
        assertEquals("Battery", r.status)
        assertNull(r.today.pvKwh)
        assertNull(r.lifetimePvKwh)
        assertTrue("QPIGS2" !in ks.asked) // not a MAXII
        // The next 11 polls only ask QPIGS and QMOD.
        ks.asked.clear()
        repeat(11) { driver.read() }
        assertEquals(List(11) { listOf("QPIGS", "QMOD") }.flatten(), ks.asked)
        // The once-a-minute refresh doesn't repeat commands that answered NAK (QVFW, QET, QED, QLT, QLD).
        ks.asked.clear()
        driver.read()
        assertEquals(listOf("QPIRI", "QPIWS", "QPIGS", "QMOD"), ks.asked)
    }

    @Test
    fun qpigsFailureFailsThePollAndCloseClosesTransport() {
        val fake = maxII().apply { replies.remove("QPIGS") }
        val driver = VoltronicAxpertDriver(fake) { day }
        assertThrows(IOException::class.java) { runBlocking { driver.read() } }
        driver.close()
        assertTrue(fake.closed)
    }

    @Test
    fun tcpTransportFramesDiscardsStaleBytesAndReconnects() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val received = mutableListOf<String>()
        val worker = thread {
            // First connection: answers QMOD after sending junk, then drops the link on the next request.
            server.accept().use { c ->
                val input = c.getInputStream(); val out = c.getOutputStream()
                fun readCommand(): ByteArray {
                    val b = mutableListOf<Byte>()
                    while (true) { val x = input.read(); if (x < 0 || x == 0x0D) break; b += x.toByte() }
                    return b.toByteArray()
                }
                val cmd = readCommand()
                synchronized(received) { received += String(cmd, Charsets.ISO_8859_1) }
                val body = "(L".toByteArray()
                out.write(body + AxpertProtocol.crcBytes(body) + byteArrayOf(0x0D))
                out.write("(stale".toByteArray()) // a late, unterminated reply fragment
                out.flush()
                readCommand()
            }
            // Second connection: the retried request.
            server.accept().use { c ->
                val input = c.getInputStream()
                val b = mutableListOf<Byte>()
                while (true) { val x = input.read(); if (x < 0 || x == 0x0D) break; b += x.toByte() }
                synchronized(received) { received += String(b.toByteArray(), Charsets.ISO_8859_1) }
                val body = "(PI30".toByteArray()
                c.getOutputStream().apply { write(body + AxpertProtocol.crcBytes(body) + byteArrayOf(0x0D)); flush() }
            }
        }
        val transport = TcpAxpertTransport("127.0.0.1", server.localPort, timeoutMs = 2000)
        try {
            assertEquals("L", transport.query("QMOD"))
            Thread.sleep(100) // let the stale fragment arrive
            assertEquals("PI30", transport.query("QPI"))
        } finally {
            transport.close()
            worker.join(5000)
            server.close()
        }
        // Command bytes are sent with their CRC (QMOD: 49 C1).
        assertEquals(String(byteArrayOf(0x51, 0x4D, 0x4F, 0x44, 0x49, 0xC1.toByte()), Charsets.ISO_8859_1), received[0])
        assertEquals(String(AxpertProtocol.frame("QPI").copyOf(5), Charsets.ISO_8859_1), received[1])
    }
}
