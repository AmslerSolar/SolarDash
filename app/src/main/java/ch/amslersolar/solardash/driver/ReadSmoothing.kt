// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.driver

import ch.amslersolar.solardash.model.DeviceProblem
import ch.amslersolar.solardash.model.DeviceReading
import ch.amslersolar.solardash.model.DeviceStatus

/**
 * Smooths the reads of one plant's devices across polls, so a single lost answer isn't shown as a dropped
 * connection:
 * - A failed read shows the device's last reading instead (no older than [HOLD_MS]) until [REPORT_AFTER] reads in a
 *   row have failed; only then is the error reported (plant status, card, fault log).
 * - A device that keeps answering but lost [UNSTABLE_FAILURES] or more reads within [UNSTABLE_WINDOW_MS] gets the
 *   warning [UNSTABLE_TEXT] on its reading: shown in the plant status and logged like any device warning.
 * Kept by a [PlantReader] for as long as it polls; a single read (background recording) starts fresh.
 */
class ReadSmoothing(private val clock: () -> Long = System::currentTimeMillis) {
    private class Health {
        var failedInRow = 0
        var last: DeviceReading? = null
        var lastAt = 0L
        val failures = ArrayDeque<Long>()
    }

    private val health = HashMap<String, Health>()

    @Synchronized
    fun apply(status: DeviceStatus): DeviceStatus {
        val now = clock()
        val h = health.getOrPut(status.device.id) { Health() }
        while (h.failures.isNotEmpty() && now - h.failures.first() > UNSTABLE_WINDOW_MS) h.failures.removeFirst()
        val reading = status.reading
        if (status.error == null && reading != null) {
            h.failedInRow = 0
            h.last = reading
            h.lastAt = now
            return status.copy(reading = flagged(reading, h))
        }
        h.failedInRow++
        h.failures.addLast(now)
        val last = h.last
        if (h.failedInRow < REPORT_AFTER && last != null && now - h.lastAt <= HOLD_MS) {
            return DeviceStatus(status.device, flagged(last, h), null)
        }
        return status
    }

    private fun flagged(reading: DeviceReading, h: Health): DeviceReading {
        if (h.failures.size < UNSTABLE_FAILURES || reading.problems.any { it.text == UNSTABLE_TEXT }) return reading
        return reading.copy(problems = reading.problems + DeviceProblem(DeviceProblem.Kind.WARNING, UNSTABLE_TEXT))
    }

    companion object {
        /** Failed reads in a row before the device is reported as not answering. */
        const val REPORT_AFTER = 3
        /** How old the last reading may be to stand in for a failed read. */
        const val HOLD_MS = 3 * 60_000L
        const val UNSTABLE_FAILURES = 3
        const val UNSTABLE_WINDOW_MS = 10 * 60_000L
        const val UNSTABLE_TEXT = "Unstable connection: some reads fail"
    }
}
