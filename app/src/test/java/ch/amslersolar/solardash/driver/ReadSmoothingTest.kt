// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.driver

import ch.amslersolar.solardash.model.ConnectionError
import ch.amslersolar.solardash.model.DeviceConfig
import ch.amslersolar.solardash.model.DeviceProblem
import ch.amslersolar.solardash.model.DeviceReading
import ch.amslersolar.solardash.model.DeviceStatus
import ch.amslersolar.solardash.model.DeviceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadSmoothingTest {
    private val device = DeviceConfig(id = "trio", name = "TRIO", type = DeviceType.AURORA, host = "192.168.178.66")
    private var now = 0L
    private val smoothing = ReadSmoothing { now }

    private fun ok(w: Int) = DeviceStatus(device, DeviceReading(pvPowerW = w), null)
    private val lost = DeviceStatus(device, null, "Aurora address 2, command 50: no reply within 2500 ms", ConnectionError.TIMEOUT)

    /** One poll every 10 s. */
    private fun poll(s: DeviceStatus): DeviceStatus = smoothing.apply(s).also { now += 10_000 }

    @Test
    fun aFailedReadShowsTheLastReadingUntilSeveralFailInARow() {
        poll(ok(2300))
        repeat(ReadSmoothing.REPORT_AFTER - 1) {
            val held = poll(lost)
            assertNull(held.error)
            assertEquals(2300, held.reading?.pvPowerW)
        }
        val reported = poll(lost)
        assertEquals(ConnectionError.TIMEOUT, reported.errorKind)
        assertNull(reported.reading)
    }

    @Test
    fun withoutAnEarlierReadingTheErrorIsReportedAtOnce() {
        assertSame(lost, poll(lost))
    }

    @Test
    fun aReadingOlderThanTheHoldTimeDoesntStandIn() {
        poll(ok(2300))
        now += ReadSmoothing.HOLD_MS
        assertSame(lost, poll(lost))
    }

    @Test
    fun aDeviceThatKeepsLosingReadsGetsAWarning() {
        poll(ok(2300))
        repeat(ReadSmoothing.UNSTABLE_FAILURES - 1) { poll(lost); poll(ok(2300)) }
        assertTrue(poll(ok(2300)).reading!!.problems.isEmpty()) // two lost reads: no warning yet
        poll(lost)
        val r = poll(ok(2310)).reading!!
        assertEquals(listOf(DeviceProblem(DeviceProblem.Kind.WARNING, ReadSmoothing.UNSTABLE_TEXT)), r.problems)
        // A held reading carries the warning once, too.
        assertEquals(1, poll(lost).reading!!.problems.count { it.text == ReadSmoothing.UNSTABLE_TEXT })
    }

    @Test
    fun theWarningEndsWhenTheReadsStopFailing() {
        poll(ok(2300))
        repeat(ReadSmoothing.UNSTABLE_FAILURES) { poll(lost); poll(ok(2300)) }
        assertTrue(poll(ok(2300)).reading!!.problems.isNotEmpty())
        now += ReadSmoothing.UNSTABLE_WINDOW_MS
        assertTrue(poll(ok(2300)).reading!!.problems.isEmpty())
    }

    @Test
    fun theDevicesOwnProblemsStay() {
        val fault = DeviceProblem(DeviceProblem.Kind.FAULT, "Riso low (E025)")
        poll(DeviceStatus(device, DeviceReading(pvPowerW = 0, problems = listOf(fault)), null))
        assertEquals(listOf(fault), poll(lost).reading!!.problems)
    }
}
