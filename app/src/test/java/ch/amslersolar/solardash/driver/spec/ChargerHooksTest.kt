// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.driver.spec

import ch.amslersolar.solardash.model.DeviceReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ChargerHooksTest {
    private var now = 0L
    private val hook = PowerFromEnergySteps { now }

    /** One poll at [atS] seconds with the counter at [kwh]. */
    private fun poll(atS: Long, kwh: Double): DeviceReading {
        now = atS * 1000
        return hook.adjust(mapOf("consumer_energy" to SpecValue(null, kwh, null)), DeviceReading())
    }

    @Test
    fun powerFromTheTimeBetweenSteps() {
        // As on the real SCharger (2026-11-05): +0.2 kWh about every 63 s while charging at about 11 kW.
        assertNull(poll(0, 1169.000).loadPowerW) // nothing known yet
        assertNull(poll(5, 1169.200).loadPowerW) // first step: its interval is unknown
        assertNull(poll(30, 1169.200).loadPowerW)
        val r = poll(68, 1169.400)
        assertEquals(11_429, r.loadPowerW) // 0.2 kWh in 63 s
        assertEquals("Charging", r.status)
        assertEquals(11_429, poll(100, 1169.400).loadPowerW) // within the step's interval: unchanged
    }

    @Test
    fun aStopShowsAsFallingPowerThenZero() {
        poll(0, 10.0); poll(60, 10.2); poll(120, 10.4) // 12 kW
        assertEquals(12_000, poll(150, 10.4).loadPowerW)
        assertEquals(4_000, poll(300, 10.4).loadPowerW) // 3 min without a step: at most 0.2 kWh / 180 s
        val idle = poll(120 + PowerFromEnergySteps.IDLE_MS / 1000, 10.4)
        assertEquals(0, idle.loadPowerW)
        assertEquals("Not charging", idle.status)
    }

    @Test
    fun theFirstStepAfterAPauseIsntAPower() {
        poll(0, 10.0); poll(60, 10.2); poll(120, 10.4)
        poll(2_000, 10.4) // idle
        assertNull(poll(2_100, 10.6).loadPowerW) // 0.2 kWh since 120 s isn't a charging power
        assertEquals(7_200, poll(2_200, 10.8).loadPowerW)
    }

    @Test
    fun noStepAtAllMeansZeroAfterTheIdleTime() {
        poll(0, 10.0)
        assertNull(poll(60, 10.0).loadPowerW)
        assertEquals(0, poll(PowerFromEnergySteps.IDLE_MS / 1000, 10.0).loadPowerW)
    }

    @Test
    fun withoutACounterValueTheReadingStays() {
        val r = DeviceReading(temperatureC = 30.3)
        assertSame(r, hook.adjust(emptyMap(), r))
    }
}
