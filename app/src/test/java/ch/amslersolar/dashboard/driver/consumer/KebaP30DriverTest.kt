// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.consumer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class KebaP30DriverTest {
    private fun keba(state: Long = 3, cable: Long = 7, powerMw: Long = 11_040_000) = FakeRegisters().on(255)
        .u32(1000, state).u32(1004, cable).u32(1006, 0)
        .u32(1008, 16_000).u32(1010, 16_000).u32(1012, 16_000)
        .u32(1014, 18_416_854).u32(1016, 304_111).u32(1018, 0x030A0D00)
        .u32(1020, powerMw).u32(1036, 38_101).u32(1040, 230).u32(1042, 231).u32(1044, 229)
        .u32(1046, 928).u32(1100, 16_000).u32(1110, 32_000).u32(1500, 0).u32(1502, 165)
        .u32(1550, 3).u32(1552, 3).u32(1600, 6_000).u32(1602, 11)

    @Test
    fun identifiesAndReadsChargingStation() = runBlocking {
        val fake = keba()
        val d = KebaP30Driver(fake, 255)
        val info = d.identify()
        assertEquals("KEBA KeContact P30 c-series", info.model)
        assertEquals("18416854", info.serial)

        val r = d.read()
        assertConsumer(r)
        assertEquals(11_040, r.loadPowerW) // mW → W
        assertEquals("Charging", r.status)
        assertEquals(listOf("L1", "L2", "L3"), r.phases.map { it.name })
        assertEquals(16.0, r.phases[0].currentA!!, 1e-9) // mA → A
        assertEquals(231.0, r.phases[1].voltageV, 1e-9)
        assertEquals("0.02 kWh", r.row("Charging", "Session energy")) // 165 × 0.1 Wh = 16.5 Wh
        assertEquals("3.8 kWh", r.row("Charging", "Total energy")) // 38101 × 0.1 Wh
        assertEquals("92.8 %", r.row("Charging", "Power factor"))
        assertEquals("32 A", r.row("Device", "Supported current"))
        assertEquals("3.10.13 (hex 30A0D00)", r.row("Device", "Firmware"))
        // One UINT32 per request, always 2 words, unit 255.
        assertTrue(fake.requests.all { it.first == 255 && it.third == 2 })
    }

    @Test
    fun laterPollsReadLiveRegistersPlusOneSlow() = runBlocking {
        val fake = keba()
        val d = KebaP30Driver(fake, 255)
        d.read()
        val before = fake.requests.size
        d.read()
        assertEquals(KebaP30Driver.LIVE.size + 1, fake.requests.size - before)
        // Slow values from the first poll are kept.
        assertEquals("3.8 kWh", d.read().row("Charging", "Total energy"))
    }

    @Test
    fun idleAndCarConnected() = runBlocking {
        assertEquals("Standby", KebaP30Driver(keba(state = 1, cable = 0, powerMw = 0), 255).read().status)
        val connected = KebaP30Driver(keba(state = 2, cable = 5, powerMw = 0), 255).read()
        assertEquals("Car connected", connected.status)
        assertEquals(0, connected.loadPowerW)
        val error = keba(state = 4).u32(1006, 262_144)
        assertEquals("Error 40000", KebaP30Driver(error, 255).read().status)
    }

    @Test
    fun missingRegistersAreLeftOut() = runBlocking {
        val fake = keba().remove(1020, 2).remove(1040, 2).remove(1042, 2).remove(1044, 2).remove(1018, 2).u32(1013, 0x030B0000)
        val r = KebaP30Driver(fake, 255).read()
        assertNull(r.loadPowerW)
        assertEquals(0.0, r.phases[0].voltageV, 1e-9)
        assertEquals("3.11.0 (hex 30B0000)", r.row("Device", "Firmware")) // 1013 fallback
    }

    @Test
    fun otherDevicesAreRejected() = runBlocking {
        for (fake in listOf(FakeRegisters().on(255).u32(1000, 3), keba().u32(1016, 112_345), FakeRegisters())) {
            try {
                KebaP30Driver(fake, 255).identify()
                fail("identify should fail")
            } catch (_: IOException) {
            }
        }
    }
}
