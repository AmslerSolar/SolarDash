// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.driver

import ch.amslersolar.solardash.model.DeviceStatus
import ch.amslersolar.solardash.model.HistoryMath
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Drops a device's "today" solar counter while it may still be yesterday's ([HistoryMath.pvCounterFresh]). Remembers
 * each device's first counter of the day for the whole process, so background reads and the live view share it.
 */
object DayCounters {
    private data class First(val day: LocalDate, val pvKwh: Double)

    private val first = ConcurrentHashMap<String, First>()

    fun check(status: DeviceStatus, now: ZonedDateTime = ZonedDateTime.now()): DeviceStatus {
        val r = status.reading ?: return status
        val pv = r.today.pvKwh ?: return status
        val day = now.toLocalDate()
        val seen = first.compute(status.device.id) { _, f -> if (f?.day == day) f else First(day, pv) }!!
        return if (HistoryMath.pvCounterFresh(pv, seen.pvKwh, r.pvPowerW, now.toLocalTime())) status
        else status.copy(reading = r.copy(today = r.today.copy(pvKwh = null)))
    }

    /** For tests. */
    fun clear() = first.clear()
}
