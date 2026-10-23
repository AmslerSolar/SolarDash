// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.consumer

import ch.amslersolar.dashboard.model.DeviceProblem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class AlfenEveDriverTest {
    /** Station at unit 200, socket at unit 1; [shift] = −1 places every register one lower (the "subtract 1" reading). */
    private fun alfen(lowWordFirst: Boolean = true, shift: Int = 0, mode3: String = "C2", power: Double = 7_200.0): FakeRegisters {
        val f = FakeRegisters(lowWordFirst)
        fun at(a: Int) = a + shift
        f.on(200).nan(at(100), 79)
            .text(at(100), 17, "ALF_1000").text(at(117), 5, "Alfen B.V.").u16(at(122), 1).text(at(123), 17, "6.4.0-4210")
            .text(at(140), 17, "NG910").text(at(157), 11, "ACE0012345").u16(at(178), 60)
        f.u32(at(174), 0).u32(at(176), 0).u16(at(174), 0).u16(at(175), 0)
        f.f32(at(1100), 16.0).f32(at(1102), 42.5).u16(at(1104), 1).u16(at(1105), 1)
        f.on(1).nan(at(300), 94).u16(at(300), 3).u16(at(305), 0)
            .f32(at(306), 230.0).f32(at(308), 231.0).f32(at(310), 229.0)
            .f32(at(320), 10.4).f32(at(322), 10.5).f32(at(324), 10.6)
            .f32(at(336), 50.0).f32(at(338), 2_400.0).f32(at(340), 2_400.0).f32(at(342), 2_400.0).f32(at(344), power)
            .f64(at(374), 1_234_567.0).f64(at(390), 1_000.0)
            .nan(at(1200), 16).u16(at(1200), 1).text(at(1201), 5, mode3).f32(at(1206), 16.0).f32(at(1212), 6.0).u16(at(1215), 3)
        return f
    }

    @Test
    fun chargingSocketWithMixedEndianFloats() = runBlocking {
        val d = AlfenEveDriver(alfen(), 1)
        val info = d.identify()
        assertEquals("Alfen Eve NG910", info.model)
        assertEquals("ACE0012345", info.serial)
        val r = d.read()
        assertConsumer(r)
        assertEquals(7_200, r.loadPowerW)
        assertEquals("Charging", r.status)
        assertEquals(42.5, r.temperatureC!!, 1e-6)
        assertEquals(3, r.phases.size)
        assertEquals(10.5, r.phases[1].currentA!!, 1e-5)
        assertEquals(50.0, r.phases[0].frequencyHz, 1e-6)
        assertEquals(2_400, r.phases[2].powerW)
        assertEquals("1234.6 kWh", r.row("Charging", "Energy delivered (total)"))
        assertEquals("6.4.0-4210", r.row("Station", "Firmware"))
        assertNull(r.row("Charging", "Current N")) // NaN → left out
    }

    @Test
    fun highWordFirstAndShiftedAddressesAreDetected() = runBlocking {
        val r = AlfenEveDriver(alfen(lowWordFirst = false, shift = -1), 1).read()
        assertEquals(7_200, r.loadPowerW)
        assertEquals(230.0, r.phases[0].voltageV, 1e-6)
        assertEquals("1234.6 kWh", r.row("Charging", "Energy delivered (total)"))
        assertEquals("Charging", r.status)
    }

    @Test
    fun idleAndConnectedStates() = runBlocking {
        val idle = AlfenEveDriver(alfen(mode3 = "A", power = 0.0), 1).read()
        assertEquals("Standby", idle.status)
        assertEquals(0, idle.loadPowerW)
        assertEquals("Car connected", AlfenEveDriver(alfen(mode3 = "B1", power = 0.0), 1).read().status)
        assertEquals("Car connected, paused", AlfenEveDriver(alfen(mode3 = "C1", power = 0.0), 1).read().status)
        assertEquals("Error (F)", AlfenEveDriver(alfen(mode3 = "F", power = 0.0), 1).read().status)
    }

    @Test
    fun missingPowerSumFallsBackToPhases() = runBlocking {
        val fake = alfen().on(1).nan(344, 2) // Reallin meters: NaN sum
        val r = AlfenEveDriver(fake, 1).read()
        assertEquals(7_200, r.loadPowerW)
    }

    @Test
    fun socketTwoUsesUnitTwo() = runBlocking {
        val fake = alfen()
        fake.units[2] = HashMap(fake.units[1]!!)
        val d = AlfenEveDriver(fake, 2)
        assertEquals("Alfen Eve NG910 socket 2", d.identify().model)
        d.read()
        assertTrue(fake.requests.none { it.first == 1 })
    }

    @Test
    fun otherDevicesAreRejected() = runBlocking {
        val other = FakeRegisters().on(200).text(100, 79, "SOMETHING ELSE")
        for (fake in listOf(other, FakeRegisters())) {
            try {
                AlfenEveDriver(fake, 1).identify()
                fail("identify should fail")
            } catch (_: IOException) {
            }
        }
    }

    @Test
    fun problemsFromMode3StateAndMeterState() = runBlocking {
        assertEquals(emptyList<DeviceProblem>(), AlfenEveDriver(alfen(), 1).read().problems)
        assertEquals(emptyList<DeviceProblem>(), AlfenEveDriver(alfen(mode3 = "B1", power = 0.0), 1).read().problems)
        val fault = AlfenEveDriver(alfen(mode3 = "F", power = 0.0).on(1).u16(300, 0x0B), 1).read()
        assertEquals(
            listOf(
                DeviceProblem(DeviceProblem.Kind.FAULT, "Charging error (Mode 3 state F)"),
                DeviceProblem(DeviceProblem.Kind.WARNING, "Energy meter error (meter state 0x08)"),
            ),
            fault.problems,
        )
    }
}
