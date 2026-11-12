// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.driver

import ch.amslersolar.solardash.model.DeviceConfig
import ch.amslersolar.solardash.model.DeviceReading
import ch.amslersolar.solardash.model.DeviceStatus
import ch.amslersolar.solardash.model.DeviceType
import ch.amslersolar.solardash.model.EnergyTotals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class DayCountersTest {
    private val trio = DeviceConfig(id = "trio", name = "TRIO", type = DeviceType.AURORA)
    private fun at(h: Int, m: Int) = ZonedDateTime.of(2026, 11, 12, h, m, 0, 0, ZoneId.of("Europe/Zurich"))
    private fun read(pvKwh: Double, pvW: Int, h: Int, m: Int) =
        DayCounters.check(DeviceStatus(trio, DeviceReading(pvPowerW = pvW, today = EnergyTotals(pvKwh = pvKwh)), null), at(h, m)).reading!!.today.pvKwh

    @Before
    fun clear() = DayCounters.clear()

    @Test
    fun yesterdaysCounterIsDroppedUntilItResets() {
        assertNull(read(26.5, 0, 7, 25))
        assertNull(read(26.5, 0, 7, 30))
        assertEquals(0.1, read(0.1, 0, 7, 35))
        assertEquals(15.74, read(15.74, 0, 17, 11))
    }

    @Test
    fun aCounterFirstSeenWhileProducingCounts() {
        assertEquals(4.2, read(4.2, 900, 10, 0))
    }
}
