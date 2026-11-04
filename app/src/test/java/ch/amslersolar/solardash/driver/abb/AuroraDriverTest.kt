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
        var lost = 0
        var stateCalls = 0
        val bus = object : AuroraTransport {
            val inner = FakeBus(2, trio)
            override suspend fun exchange(address: Int, command: Int, vararg args: Int): ByteArray {
                if (command == AuroraProtocol.STATE) {
                    stateCalls++
                    if (lost-- > 0) throw SocketTimeoutException("lost")
                }
                return inner.exchange(address, command, *args)
            }
            override fun close() {}
        }
        val driver = AuroraDriver(bus, 2)
        driver.read()
        lost = 1
        stateCalls = 0
        assertEquals("Run", driver.read().status)
        assertEquals(2, stateCalls)
    }

    @Test
    fun aSilentInverterIsAskedForItsStateOnce() = runBlocking {
        var stateCalls = 0
        val bus = object : AuroraTransport {
            override suspend fun exchange(address: Int, command: Int, vararg args: Int): ByteArray {
                if (command == AuroraProtocol.STATE) stateCalls++
                throw SocketTimeoutException("asleep")
            }
            override fun close() {}
        }
        val driver = AuroraDriver(bus, 2)
        repeat(3) { try { driver.read(); fail("expected IOException") } catch (_: IOException) {} }
        assertEquals(3, stateCalls) // at night: one timeout per poll, not two
    }

    /** [trio], with the measurements in [lost] getting no reply while it's true. */
    private class LossyBus(private val lost: Set<String>, replies: Map<String, String>) : AuroraTransport {
        var losing = false
        val inner = FakeBus(2, replies)
        val asked = mutableListOf<String>()
        override suspend fun exchange(address: Int, command: Int, vararg args: Int): ByteArray {
            if (command == AuroraProtocol.MEASURE) asked += "59/${args[0]}"
            if (losing && command == AuroraProtocol.MEASURE && "59/${args[0]}" in lost) throw SocketTimeoutException("lost")
            return inner.exchange(address, command, *args)
        }
        override fun close() {}
    }

    @Test
    fun aLostMeasurementKeepsItsLastValueForAMinute() = runBlocking {
        var now = 0L
        val bus = LossyBus(setOf("59/3", "59/61"), trio)
        val driver = AuroraDriver(bus, 2) { now }
        driver.read()
        bus.losing = true
        now = 30_000
        val r = driver.read()
        assertEquals(612, r.pvPowerW)
        assertEquals(3, r.phases.size)
        now = 61_000
        val later = driver.read()
        assertNull(later.pvPowerW)
        assertEquals(1, later.phases.size) // without L1's voltage: the single grid voltage
    }

    @Test
    fun timeoutsDontStopAMeasurementBeingAsked() = runBlocking {
        val bus = LossyBus(setOf("59/3"), trio)
        val driver = AuroraDriver(bus, 2)
        bus.losing = true
        repeat(5) { driver.read() }
        bus.losing = false
        assertEquals(612, driver.read().pvPowerW)
        assertEquals(6, bus.asked.count { it == "59/3" })
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

    @Test
    fun aLateReplyIsNotTakenForTheNextRequest() = runBlocking {
        // A gateway that sends the first reply 400 ms late, the others at once; each reply tells which request it answers.
        val server = java.net.ServerSocket(0)
        kotlin.concurrent.thread(isDaemon = true) {
            server.accept().use { s ->
                val input = s.getInputStream()
                val req = ByteArray(10)
                var n = 0
                while (true) {
                    var got = 0
                    while (got < 10) { val r = input.read(req, got, 10 - got); if (r < 0) return@use; got += r }
                    n++
                    val data = byteArrayOf(0, 6, 0, 0, 0, n.toByte())
                    val crc = AuroraProtocol.crc(data)
                    val reply = data + byteArrayOf(crc.toByte(), (crc ushr 8).toByte())
                    if (n == 1) kotlin.concurrent.thread { Thread.sleep(400); s.getOutputStream().write(reply) }
                    else s.getOutputStream().write(reply)
                }
            }
        }
        server.use {
            TcpAuroraTransport("127.0.0.1", it.localPort, timeoutMs = 200).use { bus ->
                try {
                    bus.exchange(2, AuroraProtocol.STATE)
                    fail("expected a timeout")
                } catch (_: SocketTimeoutException) {
                }
                assertEquals(2, bus.exchange(2, AuroraProtocol.STATE)[5].toInt()) // not request 1's late reply
            }
        }
    }

    /** Answers from [sequence] per key while it has entries left, then from [replies]. */
    private class ScriptedBus(replies: Map<String, String>, private val sequence: Map<String, MutableList<String>>) : AuroraTransport {
        val inner = FakeBus(2, replies)
        override suspend fun exchange(address: Int, command: Int, vararg args: Int): ByteArray {
            val key = if (command == AuroraProtocol.ENERGY || command == AuroraProtocol.MEASURE) "$command/${args[0]}" else "$command"
            val next = sequence[key]?.removeFirstOrNull() ?: return inner.exchange(address, command, *args)
            return next.split(' ').map { it.toInt(16).toByte() }.toByteArray()
        }
        override fun close() {}
    }

    @Test
    fun aStateReplyMeantForAnotherRequestIsAskedAgain() = runBlocking {
        // Seen 2026-11-04: a grid voltage reply (236.3 V) in place of the state.
        val bus = ScriptedBus(trio, mapOf("50" to mutableListOf("00 06 43 6c 4b ab")))
        val r = AuroraDriver(bus, 2).read()
        assertEquals("Run", r.status)
        assertTrue(r.problems.isEmpty())
    }

    @Test
    fun aMeasurementOutOfRangeIsDropped() = runBlocking {
        // 2.1e9 W: a counter's bytes read as a float
        val r = AuroraDriver(ScriptedBus(trio, mapOf("59/3" to mutableListOf("00 06 4f 00 00 00"))), 2).read()
        assertNull(r.pvPowerW)
    }

    @Test
    fun anEnergyCounterAboveTheTotalIsDropped() = runBlocking {
        // Seen 2026-11-04: "today" 1'150'144.7 kWh, a float reply (0x448b…) read as a counter.
        val bus = ScriptedBus(trio, mapOf("78/0" to mutableListOf("00 06 00 00 23 37", "00 06 44 8b 00 00")))
        val driver = AuroraDriver(bus, 2)
        driver.read()
        repeat(AuroraDriver.SLOW_REFRESH_POLLS) { driver.read() }
        val r = driver.read()
        assertEquals(9.015, r.today.pvKwh!!, 1e-9) // the earlier value stays
        assertEquals(553_710.195, r.lifetimePvKwh!!, 1e-6)
    }

    @Test
    fun aDroppingTotalIsTakenOnlyWhenReadTwice() = runBlocking {
        val lower = "00 06 00 00 30 39" // 12345 Wh
        val bus = ScriptedBus(trio, mapOf("78/5" to mutableListOf("00 06 21 00 f2 73", lower, lower)))
        val driver = AuroraDriver(bus, 2)
        driver.read()
        repeat(AuroraDriver.SLOW_REFRESH_POLLS) { driver.read() }
        assertEquals(553_710.195, driver.read().lifetimePvKwh!!, 1e-6) // once: kept
        repeat(AuroraDriver.SLOW_REFRESH_POLLS - 1) { driver.read() }
        assertEquals(12.345, driver.read().lifetimePvKwh!!, 1e-9) // twice: taken
    }

    @Test
    fun alarmCodesThatDontExistAreIgnored() = runBlocking {
        val bus = ScriptedBus(trio, mapOf("86" to mutableListOf("00 06 43 6c 4b ab")))
        val r = AuroraDriver(bus, 2).read()
        val state = r.details.first { it.title == "State" }.rows.toMap()
        assertNull(state["Recent alarms"])
    }

    @Test
    fun aReplyStillUnderWayDoesntShiftTheLaterOnes() = runBlocking {
        // A gateway that answers request 1 twice, the second copy 30 ms later (like a reply delayed past the one
        // taken for it): the later requests must still get their own replies.
        val server = java.net.ServerSocket(0)
        kotlin.concurrent.thread(isDaemon = true) {
            server.accept().use { s ->
                val input = s.getInputStream()
                val req = ByteArray(10)
                var n = 0
                fun reply(k: Int): ByteArray {
                    val data = byteArrayOf(0, 6, 0, 0, 0, k.toByte())
                    val crc = AuroraProtocol.crc(data)
                    return data + byteArrayOf(crc.toByte(), (crc ushr 8).toByte())
                }
                while (true) {
                    var got = 0
                    while (got < 10) { val r = input.read(req, got, 10 - got); if (r < 0) return@use; got += r }
                    n++
                    s.getOutputStream().write(reply(n))
                    if (n == 1) { Thread.sleep(30); s.getOutputStream().write(reply(1)) }
                }
            }
        }
        server.use {
            TcpAuroraTransport("127.0.0.1", it.localPort).use { bus ->
                assertEquals(listOf(1, 2, 3, 4), (1..4).map { bus.exchange(2, AuroraProtocol.STATE)[5].toInt() })
            }
        }
    }

    @Test
    fun anEarlierReplyArrivingFirstIsReplacedByTheOwnOne() = runBlocking {
        // Request 2 gets request 1's reply again first (delayed), its own reply 30 ms later.
        val server = java.net.ServerSocket(0)
        kotlin.concurrent.thread(isDaemon = true) {
            server.accept().use { s ->
                val input = s.getInputStream()
                val req = ByteArray(10)
                var n = 0
                fun reply(k: Int): ByteArray {
                    val data = byteArrayOf(0, 6, 0, 0, 0, k.toByte())
                    val crc = AuroraProtocol.crc(data)
                    return data + byteArrayOf(crc.toByte(), (crc ushr 8).toByte())
                }
                while (true) {
                    var got = 0
                    while (got < 10) { val r = input.read(req, got, 10 - got); if (r < 0) return@use; got += r }
                    n++
                    if (n == 2) { s.getOutputStream().write(reply(1)); Thread.sleep(30) }
                    s.getOutputStream().write(reply(n))
                }
            }
        }
        server.use {
            TcpAuroraTransport("127.0.0.1", it.localPort).use { bus ->
                assertEquals(listOf(1, 2, 3), (1..3).map { bus.exchange(2, AuroraProtocol.STATE)[5].toInt() })
            }
        }
    }
}
