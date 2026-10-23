// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.consumer

import ch.amslersolar.dashboard.model.DeviceProblem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class GoEChargerDriverTest {
    private fun goe(car: Int = 2, powerCw: Long = 1_104_000, lowWordFirst: Boolean = false) = FakeRegisters(lowWordFirst).on(1)
        .u16(100, car).u16(101, 32).text(105, 2, "56.8").u16(107, 0)
        .u32(108, 230).u32(110, 231).u32(112, 229)
        .u32(114, 160).u32(116, 161).u32(118, 159)
        .u32(120, powerCw).u32(128, 3_605).u32(132, 360_000)
        .u32(144, 0).u32(146, 37).u32(148, 37).u32(150, 36)
        .u32(152, 99).u32(154, 99).u32(156, 98).u32(158, 0)
        .u16(200, 1).u16(202, 0).u16(203, 0).u16(205, 0b111111)
        .text(304, 6, "012345678901").text(310, 5, "go-e-012345")

    @Test
    fun chargingWithScaledValues() = runBlocking {
        val d = GoEChargerDriver(goe(), 1)
        val info = d.identify()
        assertEquals("go-eCharger", info.model)
        assertEquals("012345678901", info.serial)
        val r = d.read()
        assertConsumer(r)
        assertEquals(11_040, r.loadPowerW) // 0.01 W
        assertEquals("Charging", r.status)
        assertEquals(16.1, r.phases[1].currentA!!, 1e-9) // 0.1 A
        assertEquals(3_700, r.phases[0].powerW) // 0.1 kW
        assertEquals(229.0, r.phases[2].voltageV, 1e-9)
        assertEquals("1.00 kWh", r.row("Charging", "Session energy")) // 360000 × 10 Ws = 1 kWh
        assertEquals("360.5 kWh", r.row("Charging", "Total energy")) // 0.1 kWh
        assertEquals("56.8", r.row("Device", "Firmware"))
    }

    @Test
    fun swappedWordsAndBytesAreDetected() = runBlocking {
        val words = GoEChargerDriver(goe(lowWordFirst = true), 1).read()
        assertEquals(11_040, words.loadPowerW)
        assertEquals(230.0, words.phases[0].voltageV, 1e-9)

        val fake = goe()
        for (m in fake.units.values) m.replaceAll { _, v -> ((v and 0xFF) shl 8) or (v shr 8) }
        val bytes = GoEChargerDriver(fake, 1).read()
        assertEquals(11_040, bytes.loadPowerW)
        assertEquals("Charging", bytes.status)
        assertEquals("56.8", bytes.row("Device", "Firmware"))
    }

    @Test
    fun idleConnectedAndError() = runBlocking {
        val idle = GoEChargerDriver(goe(car = 1, powerCw = 0), 1).read()
        assertEquals("Standby", idle.status)
        assertEquals(0, idle.loadPowerW)
        assertEquals("Car connected", GoEChargerDriver(goe(car = 3, powerCw = 0), 1).read().status)
        assertEquals("Finished, car connected", GoEChargerDriver(goe(car = 4, powerCw = 0), 1).read().status)
        assertEquals("Error: RCCB (residual current)", GoEChargerDriver(goe().u16(107, 1), 1).read().status)
    }

    @Test
    fun holdingRegistersAreOptional() = runBlocking {
        val holding = FakeRegisters().on(1).u16(211, 20).u16(299, 10).u16(300, 16)
        val r = GoEChargerDriver(goe(), 1, holding).read()
        assertEquals("10 A", r.row("Charging", "Max current (volatile)"))
        assertEquals("20 A", r.row("Charging", "Absolute max current"))
        assertNull(GoEChargerDriver(goe(), 1).read().row("Charging", "Max current (volatile)"))
    }

    @Test
    fun missingValues() = runBlocking {
        val r = GoEChargerDriver(goe().remove(120, 2).remove(146, 6), 1).read()
        assertNull(r.loadPowerW)
        assertNull(r.phases[0].powerW)
        assertEquals(16.0, r.phases[0].currentA!!, 1e-9)
    }

    @Test
    fun otherDevicesAreRejected() = runBlocking {
        for (fake in listOf(goe(car = 9), goe().remove(304, 6), FakeRegisters())) {
            try {
                GoEChargerDriver(fake, 1).identify()
                fail("identify should fail")
            } catch (_: IOException) {
            }
        }
    }

    @Test
    fun problemsFromErrorRegisterAndCarState() = runBlocking {
        assertEquals(emptyList<DeviceProblem>(), GoEChargerDriver(goe(), 1).read().problems)
        assertEquals(emptyList<DeviceProblem>(), GoEChargerDriver(goe(car = 3, powerCw = 0), 1).read().problems)
        assertEquals(
            listOf(DeviceProblem(DeviceProblem.Kind.FAULT, "No ground detected (error 8)")),
            GoEChargerDriver(goe(car = 1, powerCw = 0).u16(107, 8), 1).read().problems,
        )
        assertEquals(
            listOf(DeviceProblem(DeviceProblem.Kind.FAULT, "Internal error (error 12)")),
            GoEChargerDriver(goe(car = 1, powerCw = 0).u16(107, 12), 1).read().problems,
        )
        assertEquals(
            listOf(DeviceProblem(DeviceProblem.Kind.FAULT, "Charging station defective (car state 0)")),
            GoEChargerDriver(goe(car = 0, powerCw = 0), 1).read().problems,
        )
    }
}
