// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelsTest {
    @Test
    fun parsesNumbersWithUnits() {
        assertEquals(238.4 to "V", Channels.parseNumber("238.4 V"))
        assertEquals(-1520.0 to "W", Channels.parseNumber("-1520 W"))
        assertEquals(219.800003 to "", Channels.parseNumber("219.800003"))
        assertNull(Channels.parseNumber("Discharging (3)"))
        assertNull(Channels.parseNumber("10.05.7437"))
        assertNull(Channels.parseNumber("0x0000"))
    }

    @Test
    fun extractsReadingAndDetailChannels() {
        val reading = DeviceReading(
            pvPowerW = 4200,
            battery = BatteryState(powerW = -800, socPercent = 64, voltageV = null, mode = "Charging"),
            phases = listOf(PhaseReading("L1", 231.0, null, 50.0, null)),
            details = listOf(DetailSection("Battery", listOf("Temperature" to "24.5 °C", "Mode" to "Charging (2)"))),
        )
        val byKey = Channels.extract(reading).associateBy { it.key }
        assertEquals(4200.0, byKey.getValue("r:pv_power").value, 0.0)
        assertEquals(64.0, byKey.getValue("r:battery_soc").value, 0.0)
        assertEquals("%", byKey.getValue("r:battery_soc").unit)
        assertTrue("r:battery_voltage" !in byKey)
        assertTrue("r:L1_voltage" in byKey && "r:L1_current" !in byKey && "r:L1_power" !in byKey)
        val temp = byKey.getValue("d:Battery:Temperature")
        assertEquals(24.5, temp.value, 0.0)
        assertEquals("°C", temp.unit)
        assertTrue("d:Battery:Mode" !in byKey)
    }

    @Test
    fun displayUnitsShareAxes() {
        assertEquals("kW" to 1e-3, Channels.displayUnit("W"))
        assertEquals("kWh" to 1e-3, Channels.displayUnit("Wh"))
        assertEquals("V" to 1.0, Channels.displayUnit("V"))
        assertEquals("value" to 1.0, Channels.displayUnit(""))
    }

    @Test
    fun namesAndSections() {
        assertEquals("Temperature", Channels.name("d:Battery:Temperature", "Temperature (Battery)"))
        assertEquals("Battery", Channels.section("d:Battery:Temperature"))
        assertEquals("Solar power", Channels.name("r:pv_power", "Solar power"))
        assertEquals("Live values", Channels.section("r:pv_power"))
        // Labels may themselves contain colons.
        assertEquals("Time: local", Channels.name("d:System:Time: local", "x"))
    }

    @Test
    fun shortensLongNames() {
        assertEquals("Solar power", Channels.shorten("Solar power"))
        assertEquals("123456789012345", Channels.shorten("123456789012345"))
        assertEquals("Battery state o…", Channels.shorten("Battery state of charge"))
    }
}
