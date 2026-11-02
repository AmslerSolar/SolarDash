// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.driver.abb

import ch.amslersolar.solardash.driver.abb.AbbCddFixtures.ALARMS
import ch.amslersolar.solardash.driver.abb.AbbCddFixtures.ALARMS_EMPTY
import ch.amslersolar.solardash.driver.abb.AbbCddFixtures.PLANT
import ch.amslersolar.solardash.driver.abb.AbbCddFixtures.PLANT_NIGHT
import ch.amslersolar.solardash.driver.abb.AbbCddFixtures.SUMMARY
import ch.amslersolar.solardash.model.DeviceProblem
import ch.amslersolar.solardash.model.DeviceProblem.Kind.FAULT
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class AbbCddDriverTest {
    /** A CDD answering from [responses]; a path with several answers returns them in turn, the last one repeated. */
    private class FakeCdd(vararg responses: Pair<String, List<String>>) : CddFetch {
        private val answers = responses.associate { (path, list) -> path to ArrayDeque(list) }
        val calls = mutableListOf<String>()
        override suspend fun get(path: String): String {
            calls += path
            val queue = answers[path] ?: throw IOException("HTTP 404 from $path")
            return if (queue.size > 1) queue.removeFirst() else queue.first()
        }
    }

    private fun cdd(plant: String = PLANT, alarms: List<String> = listOf(ALARMS)) =
        FakeCdd("/plant.xml" to listOf(plant), "/summary.xml" to listOf(SUMMARY), "/alarm.xml" to alarms)

    @Test
    fun identifyFromSummary() = runBlocking {
        val info = AbbCddDriver(cdd()).identify()
        assertEquals("ABB CDD, 5 microinverters", info.model)
        assertEquals("300001", info.serial)
    }

    @Test
    fun identifyRejectsOtherWebServers() {
        for (body in listOf("<html><body>Router login</body></html>", "<data ts=\"x\"></data>")) {
            try {
                runBlocking { AbbCddDriver(FakeCdd("/summary.xml" to listOf(body))).identify() }
                fail("expected IOException for $body")
            } catch (e: IOException) {
                assertTrue(e.message, e.message!!.contains("Not an ABB CDD"))
            }
        }
    }

    @Test
    fun plantTotalsFromTheCdd() = runBlocking {
        val r = AbbCddDriver(cdd()).read()
        assertEquals(48, r.pvPowerW)
        assertEquals(7807.852, r.lifetimePvKwh!!, 1e-9)
        assertEquals("Producing", r.status)
        assertNull(r.gridPowerW)
        assertTrue(r.phases.isEmpty())
    }

    @Test
    fun activeAlarmsAreProblemsButNotTheWakeUpOrClosedOnes() = runBlocking {
        val r = AbbCddDriver(cdd()).read()
        assertEquals(
            listOf(
                DeviceProblem(FAULT, "Comm Fault (E501): microinverter 700004"),
                DeviceProblem(FAULT, "Comm Fault (E501): microinverter xxxxxx"),
            ),
            r.problems,
        )
    }

    @Test
    fun detailsPerMicroinverter() = runBlocking {
        val r = AbbCddDriver(cdd()).read()
        val byTitle = r.details.associateBy { it.title }
        val cddRows = byTitle.getValue("CDD").rows.toMap()
        assertEquals("ALARM", cddRows["State"])
        assertEquals("VDE0126", cddRows["Grid code"])
        assertEquals("Disabled", cddRows["Power limit"])
        assertEquals("2026-09-16 10:39:13", cddRows["Last boot"])
        assertEquals("2026-11-02 10:38:26", cddRows["CDD time"])

        val overview = byTitle.getValue("Microinverters (5)").rows.toMap()
        assertEquals("RUN, 8.2 W, 38.9 V DC, RSSI 31", overview["700001"])
        assertEquals("START-UP, never reported", overview["700004"])

        val running = byTitle.getValue("Microinverter 700002").rows.toMap()
        assertEquals("20.9 W", running["Power"])
        assertEquals("37.7 V", running["DC voltage"])
        assertEquals("236.1 V", running["AC voltage"])
        assertEquals("0.091 A", running["AC current"])
        assertEquals("49.98 Hz", running["Grid frequency"])
        assertEquals("3058.1 kWh", running["Lifetime energy"])
        assertEquals("2026-11-02 10:36:56", running["Last report"])
        assertEquals("C111 / B158 / 001", running["Firmware (micro / DSP / RF)"])

        // A unit that never answered: no zero readings, just its state, alarm registers and "never".
        val silent = byTitle.getValue("Microinverter 700004").rows.toMap()
        assertEquals("never", silent["Last report"])
        assertEquals("0x8000", silent["MCU alarms"])
        assertNull(silent["Power"])
        assertNull(silent["Model"])

        val log = byTitle.getValue("Alarm log").rows
        assertEquals("E501 Comm Fault, 700004" to "2026-09-16 10:59:18, active", log[1])
        assertEquals(1, log.count { it.first.startsWith("W021") }) // the duplicate entry is shown once
    }

    @Test
    fun emptyAlarmLogIsReadAgainOnce() = runBlocking {
        val fake = cdd(alarms = listOf(ALARMS_EMPTY, ALARMS))
        val r = AbbCddDriver(fake).read()
        assertEquals(2, fake.calls.count { it == "/alarm.xml" })
        assertEquals(2, r.problems.size)
    }

    @Test
    fun withoutAlarmLogTheInvertersOwnAlarmFlagCounts() = runBlocking {
        val r = AbbCddDriver(cdd(alarms = listOf(ALARMS_EMPTY))).read()
        assertEquals(
            listOf(
                DeviceProblem(FAULT, "No communication: microinverter 700004"),
                DeviceProblem(FAULT, "No communication: microinverter xxxxxx"),
            ),
            r.problems,
        )
        assertNull(r.details.firstOrNull { it.title == "Alarm log" })
    }

    @Test
    fun alarmLogIsReadEveryFiveMinutesAndKeptWhenItGlitches() = runBlocking {
        var now = 0L
        val fake = cdd(alarms = listOf(ALARMS, ALARMS_EMPTY))
        val driver = AbbCddDriver(fake) { now }
        driver.read()
        now = 60_000
        driver.read()
        assertEquals(1, fake.calls.count { it == "/alarm.xml" })
        now = 5 * 60_000L
        val r = driver.read() // empty twice: the earlier log stays
        assertEquals(3, fake.calls.count { it == "/alarm.xml" })
        assertEquals("Comm Fault (E501): microinverter 700004", r.problems.first().text)
    }

    @Test
    fun nightStatus() = runBlocking {
        val r = AbbCddDriver(cdd(plant = PLANT_NIGHT)).read()
        assertEquals(0, r.pvPowerW)
        assertEquals("Night", r.status)
    }

    @Test
    fun plantReadFailsWithoutCddElement() {
        try {
            runBlocking { AbbCddDriver(cdd(plant = "<html></html>")).read() }
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message, e.message!!.contains("Not an ABB CDD"))
        }
    }

    @Test
    fun times() {
        assertEquals("2026-11-02 10:36:55", AbbCddParser.time("02-11-26 10:36:55"))
        assertNull(AbbCddParser.time("01-01-00 00:00:00"))
        assertNull(AbbCddParser.time(""))
        assertNull(AbbCddParser.time(null))
    }
}
