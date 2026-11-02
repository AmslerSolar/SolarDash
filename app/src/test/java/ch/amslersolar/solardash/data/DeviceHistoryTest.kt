// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import ch.amslersolar.solardash.model.DeviceConfig
import ch.amslersolar.solardash.model.DeviceReading
import ch.amslersolar.solardash.model.DeviceStatus
import ch.amslersolar.solardash.model.DeviceType
import ch.amslersolar.solardash.model.EnergyTotals
import ch.amslersolar.solardash.model.PlantAggregator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/** One device's own curves and energy in the history, from what was recorded for it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DeviceHistoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val inverter = DeviceConfig(id = "inv", name = "Inverter", type = DeviceType.DEMO_INVERTER)
    private val heatPump = DeviceConfig(id = "hp", name = "Heat pump", type = DeviceType.DEMO_HEAT_PUMP)

    @Test
    fun aDevicesCurvesAndDailyEnergy() = runBlocking {
        context.deleteDatabase("history.db")
        val history = HistoryRepository(context)
        val day = LocalDate.now().minusDays(1)
        val start = day.atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant()
        // An hour, every minute: the inverter makes 2 kW (its own counter says 7 kWh), the heat pump draws 1 kW.
        for (m in 0..60) {
            val statuses = listOf(
                DeviceStatus(inverter, DeviceReading(pvPowerW = 2000, today = EnergyTotals(pvKwh = 7.0)), null),
                DeviceStatus(heatPump, DeviceReading(loadPowerW = 1000), null),
            )
            history.record("p", PlantAggregator.aggregate(statuses, start.plusSeconds(60L * m)))
        }
        val from = start.epochSecond
        val samples = history.deviceSamples("hp", from, from + 3601)
        assertEquals(61, samples.size)
        assertEquals(1000, samples.first().loadW)
        assertNull(samples.first().pvW)

        val pump = history.deviceDays("hp", day.minusDays(3), day).single()
        assertEquals(day, pump.day)
        assertEquals(1.0, pump.energy.loadKwh!!, 1e-6) // integrated
        assertEquals(7.0, history.deviceDays("inv", day, day).single().energy.pvKwh!!, 1e-6) // the counter wins
        // Kept once worked out: the same again.
        assertEquals(pump, history.deviceDays("hp", day, day).single())
    }
}
