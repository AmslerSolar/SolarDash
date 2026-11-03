// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.driver.abb

import ch.amslersolar.solardash.model.DeviceProblem
import ch.amslersolar.solardash.model.DeviceProblem.Kind.FAULT
import ch.amslersolar.solardash.model.DeviceProblem.Kind.MAINS_LOST
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class AuroraDriverTest {
    /**
     * Replies of a TRIO-27.6-TL-OUTD-400 at address 2 (read 2026-11-03 14:55, serial number changed), keyed by
     * command and first argument; data bytes only, as [AuroraTransport.exchange] returns them.
     */
    private val trio = mapOf(
        "50" to "00 06 02 02 02 00",
        "52" to "2d 33 4d 32 32 2d",
        "58" to "00 06 59 45 4e 4e",
        "63" to "31 32 33 34 35 36",
        "65" to "00 06 30 34 32 30",
        "70" to "00 06 32 7c b2 6e",
        "72" to "00 06 43 33 35 37",
        "86" to "00 06 0b 0b 0b 0b",
        "78/0" to "00 06 00 00 23 37",
        "78/1" to "00 06 00 00 bd 83",
        "78/3" to "00 06 00 02 02 e7",
        "78/4" to "00 06 02 5f 60 57",
        "78/5" to "00 06 21 00 f2 73",
        "59/1" to "00 06 43 6b dd c3",
        "59/2" to "00 06 3f 72 6c ab",
        "59/3" to "00 06 44 18 fb 3d",
        "59/4" to "00 06 42 48 0b 44",
        "59/5" to "00 06 44 23 66 6c",
        "59/6" to "00 06 00 00 00 00",
        "59/7" to "00 06 3c 47 06 c4",
        "59/8" to "00 06 43 a3 e5 c8",
        "59/9" to "00 06 43 97 87 28",
        "59/21" to "00 06 41 ad a1 00",
        "59/22" to "00 06 41 9f 04 00",
        "59/23" to "00 06 44 0a 1b 58",
        "59/25" to "00 06 3f 3b ef 5c",
        "59/26" to "00 06 44 0a 28 2b",
        "59/27" to "00 06 3f 26 e5 c3",
        "59/30" to "00 06 40 41 ff d6",
        "59/34" to "00 06 46 ea 9c 2e",
        "59/35" to "00 06 45 0a 75 1a",
        "59/39" to "00 06 3f 72 6c ab",
        "59/40" to "00 06 3f 72 6c ab",
        "59/41" to "00 06 3f 72 6c ab",
        "59/61" to "00 06 43 6b a4 b2",
        "59/62" to "00 06 43 6b 77 d1",
        "59/63" to "00 06 43 6a c3 fa",
    )

    private class FakeBus(private val address: Int, private val replies: Map<String, String>) : AuroraTransport {
        val calls = mutableListOf<String>()
        override suspend fun exchange(address: Int, command: Int, vararg args: Int): ByteArray {
            if (address != this.address) throw SocketTimeoutException("no reply")
            val key = if (command == AuroraProtocol.ENERGY || command == AuroraProtocol.MEASURE) "$command/${args[0]}" else "$command"
            calls += key
            val hex = replies[key] ?: "33 06 00 00 00 00" // transmission state 51: not implemented
            return hex.split(' ').map { it.toInt(16).toByte() }.toByteArray()
        }
        override fun close() {}
    }

    private fun hex(s: String) = s.split(' ').map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun crcAndFraming() {
        // The frames sent to and answered by the real TRIO.
        assertArrayEquals(hex("02 32 00 00 00 00 00 00 ed 69"), AuroraProtocol.request(2, 50))
        val reply = hex("00 06 02 02 02 00 69 73")
        assertTrue(AuroraProtocol.isReply(reply, 8))
        val echoed = AuroraProtocol.request(2, 50) + reply
        assertTrue(AuroraProtocol.isReply(echoed, echoed.size))
        reply[7] = 0
        assertTrue(!AuroraProtocol.isReply(reply, 8))
    }

    @Test
    fun identify() = runBlocking {
        val info = AuroraDriver(FakeBus(2, trio), 2).identify()
        assertEquals("ABB TRIO-27.6-TL-OUTD-400, firmware C.3.5.7", info.model)
        assertEquals("123456", info.serial)
    }

    @Test
    fun unknownModelCodeIsShownRaw() = runBlocking {
        val info = AuroraDriver(FakeBus(3, trio + ("58" to "00 06 6e 45 4e 4e")), 3).identify()
        assertEquals("ABB Aurora inverter (model code 'n'), firmware C.3.5.7", info.model)
    }

    @Test
    fun silentAddressFails() {
        try {
            runBlocking { AuroraDriver(FakeBus(2, trio), 5).identify() }
            fail("expected IOException")
        } catch (_: IOException) {
        }
    }

    @Test
    fun reading() = runBlocking {
        val r = AuroraDriver(FakeBus(2, trio), 2).read()
        assertEquals(612, r.pvPowerW)
        assertEquals("Run", r.status)
        assertEquals(9.015, r.today.pvKwh!!, 1e-9)
        assertEquals(553_710.195, r.lifetimePvKwh!!, 1e-6)
        assertEquals(21.7, r.temperatureC!!, 1e-9)
        assertTrue(r.problems.isEmpty())
        assertNull(r.gridPowerW)

        assertEquals(listOf("L1", "L2", "L3"), r.phases.map { it.name })
        assertEquals(235.6, r.phases[0].voltageV, 1e-9)
        assertEquals(234.8, r.phases[2].voltageV, 1e-9)
        assertEquals(0.95, r.phases[1].currentA!!, 1e-9)
        assertEquals(50.01, r.phases[0].frequencyHz, 1e-9)

        assertEquals(2, r.pvStrings.size)
        assertEquals(552.4, r.pvStrings[0].voltageV, 1e-9)
        assertEquals(0.73, r.pvStrings[0].currentA, 1e-9)
        assertEquals(328, r.pvStrings[0].powerW)
        assertEquals(303, r.pvStrings[1].powerW)

        val d = r.details.associate { it.title to it.rows.toMap() }
        assertEquals("-3M22-", d.getValue("Inverter")["Part number"])
        assertEquals("week 04 of 2020", d.getValue("Inverter")["Manufactured"])
        assertEquals("2026-11-03 14:55:42", d.getValue("Inverter")["Inverter clock"])
        assertEquals("No (N)", d.getValue("Inverter")["Transformer"])
        assertEquals("MPPT (2)", d.getValue("State")["DC/DC 1 state"])
        assertEquals("Bulk UV (W011), Bulk UV (W011), Bulk UV (W011), Bulk UV (W011)", d.getValue("State")["Recent alarms"])
        assertEquals("3.03 MΩ", d.getValue("DC")["Isolation resistance"])
        assertEquals("30030 W", d.getValue("AC")["Power peak"])
        assertEquals("48.5 kWh", d.getValue("Energy")["This week"])
        assertEquals("553710 kWh", d.getValue("Energy")["Total"])
    }

    @Test
    fun refusedMeasurementsAreNotAskedAgain() = runBlocking {
        val bus = FakeBus(2, trio - "59/61" - "59/62" - "59/63")
        val driver = AuroraDriver(bus, 2)
        val r = driver.read()
        driver.read()
        assertEquals(1, bus.calls.count { it == "59/61" })
        // Without per-phase voltages: the grid voltage and current as one phase.
        assertEquals(listOf("L1"), r.phases.map { it.name })
        assertEquals(235.9, r.phases[0].voltageV, 1e-9)
        assertEquals(612, r.phases[0].powerW)
    }

    @Test
    fun slowValuesAreReadOncePerMinute() = runBlocking {
        val bus = FakeBus(2, trio)
        val driver = AuroraDriver(bus, 2)
        repeat(AuroraDriver.SLOW_REFRESH_POLLS + 1) { driver.read() }
        assertEquals(2, bus.calls.count { it == "78/5" })
        assertEquals(AuroraDriver.SLOW_REFRESH_POLLS + 1, bus.calls.count { it == "59/3" })
        assertEquals(1, bus.calls.count { it == "63" }) // identity once
    }

    @Test
    fun recentAlarmsAreKeptAfterTheInverterClearsThem() = runBlocking {
        var alarms = "00 06 0b 26 00 00"
        val bus = object : AuroraTransport {
            val inner = FakeBus(2, trio)
            override suspend fun exchange(address: Int, command: Int, vararg args: Int): ByteArray =
                if (command == AuroraProtocol.LAST_ALARMS) hex(alarms).also { alarms = "00 06 00 00 00 00" } else inner.exchange(address, command, *args)
            override fun close() {}
        }
        val driver = AuroraDriver(bus, 2)
        driver.read()
        repeat(AuroraDriver.SLOW_REFRESH_POLLS) { driver.read() }
        val r = driver.read()
        val state = r.details.first { it.title == "State" }.rows.toMap()
        assertEquals("Bulk UV (W011), Riso low (E025)", state["Recent alarms"])
    }

    @Test
    fun aLostStateReplyIsAskedAgain() = runBlocking {
        var lost = 1
        val bus = object : AuroraTransport {
            val inner = FakeBus(2, trio)
            override suspend fun exchange(address: Int, command: Int, vararg args: Int): ByteArray {
                if (command == AuroraProtocol.STATE && lost-- > 0) throw SocketTimeoutException("lost")
                return inner.exchange(address, command, *args)
            }
            override fun close() {}
        }
        assertEquals("Run", AuroraDriver(bus, 2).read().status)
    }

    @Test
    fun problems() = runBlocking {
        fun problemsFor(state: String) = runBlocking { AuroraDriver(FakeBus(2, trio + ("50" to state)), 2).read().problems }
        // Sun low (alarm 1) and bulk UV (11) at dusk are normal.
        assertTrue(problemsFor("00 01 00 00 00 01").isEmpty())
        assertTrue(problemsFor("00 06 02 02 02 0b").isEmpty())
        assertEquals(listOf(DeviceProblem(FAULT, "Riso low (E025)")), problemsFor("00 06 02 02 02 26"))
        assertEquals(listOf(DeviceProblem(MAINS_LOST, "Grid UV (W005)")), problemsFor("00 02 2b 02 02 21"))
        assertEquals(listOf(DeviceProblem(MAINS_LOST, "Grid not present (inverter state 43)")), problemsFor("00 02 2b 00 00 00"))
        assertEquals(
            listOf(DeviceProblem(FAULT, "Ground fault (E018)"), DeviceProblem(FAULT, "Ground fault (state 9)")),
            problemsFor("00 09 00 00 00 17"),
        )
    }
}
