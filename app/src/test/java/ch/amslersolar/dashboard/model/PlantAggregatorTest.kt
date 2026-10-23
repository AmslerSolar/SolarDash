// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlantAggregatorTest {
    private fun device(id: String, type: DeviceType) = DeviceConfig(id = id, name = id, type = type)
    private fun ok(device: DeviceConfig, reading: DeviceReading) = DeviceStatus(device, reading, null)
    private fun failed(device: DeviceConfig) = DeviceStatus(device, null, "timeout")
    private fun battery(powerW: Int, soc: Int?) = BatteryState(powerW, soc, null, "")

    private val inverter1 = device("inv1", DeviceType.GOODWE_ET)
    private val inverter2 = device("inv2", DeviceType.GOODWE_ET)
    private val acBattery = device("bat", DeviceType.DEMO_BATTERY)
    private val meter = device("meter", DeviceType.DEMO_METER)

    @Test
    fun sumsInvertersAndUsesTheirGridWithoutMeter() {
        val s = PlantAggregator.aggregate(
            listOf(
                ok(inverter1, DeviceReading(pvPowerW = 10_000, gridPowerW = 6_000, battery = battery(-2_000, 50))),
                ok(inverter2, DeviceReading(pvPowerW = 5_000, gridPowerW = 4_000)),
            ),
        )
        assertEquals(15_000, s.pvPowerW)
        assertEquals(GridSource.INVERTERS, s.gridSource)
        assertEquals(10_000, s.gridPowerW)
        assertEquals(-2_000, s.battery?.powerW)
        // 15 kW PV − 2 kW into the battery − 10 kW exported = 3 kW used on site
        assertEquals(3_000, s.loadPowerW)
    }

    @Test
    fun prefersInvertersOwnLoadOverAnInconsistentBalance() {
        // LG HTTPS API on a real unit: PV 2.12 kW, charging 2.46 kW, grid 2 W, home measured at 0.5 kW.
        val lg = device("lg", DeviceType.LG_ESS)
        val s = PlantAggregator.aggregate(
            listOf(ok(lg, DeviceReading(pvPowerW = 2_120, gridPowerW = 2, battery = battery(-2_460, 52), loadPowerW = 500))),
        )
        assertEquals(500, s.loadPowerW)
    }

    @Test
    fun balancedLoadIsNeverNegative() {
        val s = PlantAggregator.aggregate(
            listOf(
                ok(inverter1, DeviceReading(pvPowerW = 2_000, gridPowerW = 0)),
                ok(acBattery, DeviceReading(battery = battery(-2_500, 50))),
            ),
        )
        assertEquals(0, s.loadPowerW)
    }

    @Test
    fun meterReplacesInverterGridValues() {
        val s = PlantAggregator.aggregate(
            listOf(
                ok(inverter1, DeviceReading(pvPowerW = 10_000, gridPowerW = 6_000)),
                ok(acBattery, DeviceReading(battery = battery(1_000, 80))),
                ok(meter, DeviceReading(gridPowerW = -500, today = EnergyTotals(importKwh = 2.0))),
            ),
        )
        assertEquals(GridSource.METERS, s.gridSource)
        assertEquals(-500, s.gridPowerW)
        assertEquals(10_000 + 1_000 + 500, s.loadPowerW)
        assertEquals(2.0, s.today.importKwh!!, 0.0)
    }

    @Test
    fun failedMeterMakesGridUnknownInsteadOfFallingBack() {
        val s = PlantAggregator.aggregate(
            listOf(ok(inverter1, DeviceReading(pvPowerW = 4_000, gridPowerW = 1_000, loadPowerW = 3_000)), failed(meter)),
        )
        assertEquals(GridSource.METERS, s.gridSource)
        assertNull(s.gridPowerW)
        assertEquals(3_000, s.loadPowerW) // falls back to devices' own load readings
        assertEquals(1, s.failedDevices.size)
    }

    @Test
    fun averagesStateOfChargeAcrossBatteries() {
        val s = PlantAggregator.aggregate(
            listOf(
                ok(inverter1, DeviceReading(battery = battery(500, 40))),
                ok(acBattery, DeviceReading(battery = battery(-200, 81))),
            ),
        )
        assertEquals(300, s.battery?.powerW)
        assertEquals(61, s.battery?.socPercent)
    }

    @Test
    fun pvMeterAddsProductionAndConsumersStayOutOfTheTotals() {
        val gridMeter = device("grid", DeviceType.DEMO_METER)
        val pvMeter = device("pv", DeviceType.DEMO_METER).copy(meterRole = MeterRole.PV)
        val heatPumpMeter = device("hpm", DeviceType.DEMO_METER).copy(meterRole = MeterRole.CONSUMER)
        val wallbox = device("car", DeviceType.DEMO_HEAT_PUMP)
        val s = PlantAggregator.aggregate(
            listOf(
                ok(inverter1, DeviceReading(pvPowerW = 4_000, gridPowerW = 999)), // ignored: a grid meter exists
                ok(pvMeter, DeviceReading(gridPowerW = 1_500)), // old inverter without communication
                ok(gridMeter, DeviceReading(gridPowerW = 2_000)),
                ok(heatPumpMeter, DeviceReading(gridPowerW = -1_200)), // drawing 1.2 kW
                ok(wallbox, DeviceReading(loadPowerW = 2_000, status = "Charging")),
            ),
        )
        assertEquals(5_500, s.pvPowerW)
        assertEquals(GridSource.METERS, s.gridSource)
        assertEquals(2_000, s.gridPowerW)
        assertEquals(3_500, s.loadPowerW) // 5.5 kW PV − 2 kW export; consumers are inside it, not added
        assertEquals(listOf(1_200, 2_000), s.consumers.map { it.powerW })
        assertEquals("Charging", s.consumers[1].status)
    }

    @Test
    fun invertedMeterFlipsItsDirection() {
        val meterDevice = device("m", DeviceType.DEMO_METER).copy(invertGrid = true)
        val s = PlantAggregator.aggregate(
            listOf(ok(inverter1, DeviceReading(pvPowerW = 3_000)), ok(meterDevice, DeviceReading(gridPowerW = -1_000))),
        )
        assertEquals(1_000, s.gridPowerW)
        assertEquals(2_000, s.loadPowerW)
    }
}
