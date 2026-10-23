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

class BenderEcuDriverTest {
    /** AMTRON Professional, software 5.22, ABB EV3 meter, charging 3 × 16 A. */
    private fun ecu(ocpp: Int = 6, vehicle: Int = 3, power: Long = 11_000) = FakeRegisters().on(1)
        .text(100, 2, "5.22").u16(104, ocpp).u32(105, 0).u32(107, 0).u32(109, 0).u32(111, 0)
        .text(120, 2, "0.9c").u16(122, vehicle).u16(124, 1).u16(130, 0).u16(131, 6).u16(132, 60).u16(134, 32)
        .text(142, 10, "CC612_2S0R").u16(152, 1)
        .u32(200, 0xFFFFFFFFL).u32(202, 0xFFFFFFFFL).u32(204, 0xFFFFFFFFL)
        .u32(206, power / 3).u32(208, power / 3).u32(210, power / 3)
        .u32(212, 16_000).u32(214, 16_100).u32(216, 15_900)
        .u32(218, 4_321_000).u32(220, power).u32(222, 230).u32(224, 231).u32(226, 229)
        .u16(600, 0)
        .u16(705, 5_000).u16(706, 16).u16(709, 1_800).u16(712, 6).u16(715, 32).u32(716, 5_000).u32(718, 1_800)
        .u16(1000, 32)

    @Test
    fun chargingAmtronProfessional() = runBlocking {
        val d = BenderEcuDriver(ecu(), 1)
        assertEquals("MENNEKES AMTRON Professional", d.identify().model)
        val r = d.read()
        assertConsumer(r)
        assertEquals(11_000, r.loadPowerW)
        assertEquals("Charging", r.status)
        assertEquals(16.1, r.phases[1].currentA!!, 1e-9) // mA → A
        assertEquals(229.0, r.phases[2].voltageV, 1e-9)
        assertEquals(3_666, r.phases[0].powerW)
        assertEquals("5.00 kWh", r.row("Charging", "Session energy"))
        assertEquals("4321.0 kWh", r.row("Charging", "Total energy (meter)"))
        assertEquals("30 min", r.row("Charging", "Charge duration"))
        assertEquals("None", r.row("Charging", "Errors"))
        assertEquals("5.22", r.row("Device", "Firmware"))
    }

    @Test
    fun oldFirmwareTotalsComeFromL1() = runBlocking {
        // < 5.22: no 218–227, no 716–719; total power / energy in the L1 registers, L2/L3 = 0xFFFFFFFF.
        val fake = ecu().remove(218, 10).remove(716, 4).remove(142, 11).remove(134)
            .u32(200, 1_000_000).u32(206, 7_000).u32(208, 0xFFFFFFFFL).u32(210, 0xFFFFFFFFL).u16(705, 1_234)
        val d = BenderEcuDriver(fake, 1)
        assertEquals("Bender ECU", d.identify().model)
        val r = d.read()
        assertEquals(7_000, r.loadPowerW)
        assertEquals("1000.0 kWh", r.row("Charging", "Total energy (meter)"))
        assertEquals("1.23 kWh", r.row("Charging", "Session energy"))
        assertNull(r.phases[0].powerW) // L1 holds the total, not a phase value
        assertEquals(0.0, r.phases[0].voltageV, 1e-9) // no voltage registers
    }

    @Test
    fun idleConnectedAndFaultStates() = runBlocking {
        val idle = BenderEcuDriver(ecu(ocpp = 0, vehicle = 1, power = 0), 1).read()
        assertEquals("Standby", idle.status)
        assertEquals(0, idle.loadPowerW)
        assertEquals("Car connected", BenderEcuDriver(ecu(ocpp = 5, vehicle = 2, power = 0), 1).read().status)
        assertEquals("Paused by car", BenderEcuDriver(ecu(ocpp = 8, vehicle = 2, power = 0), 1).read().status)
        // Error registers: low word first, low byte first: 111 = 0x4100 → 0x41 = RCM + contactor welded.
        val fault = ecu(ocpp = 4, vehicle = 1, power = 0).u16(111, 0x4100).u16(112, 0)
        val r = BenderEcuDriver(fault, 1).read()
        assertEquals("Fault: RCM triggered", r.status)
        assertEquals("RCM triggered, Contactor welded", r.row("Charging", "Errors"))
    }

    @Test
    fun fallsBackToInputRegistersOnIllegalFunction() = runBlocking {
        val holding = FakeRegisters().apply { failWith = 1 }
        val input = ecu()
        val r = BenderEcuDriver(holding, 1, input).read()
        assertEquals(11_000, r.loadPowerW)
        assertTrue(holding.requests.size == 1)
    }

    @Test
    fun otherDevicesAreRejected() = runBlocking {
        val notAscii = FakeRegisters().on(1).u16(100, 0x0001).u16(101, 0x1F40)
        for (fake in listOf(notAscii, FakeRegisters(), ecu().u16(122, 42))) {
            try {
                BenderEcuDriver(fake, 1).identify()
                fail("identify should fail")
            } catch (_: IOException) {
            }
        }
    }

    @Test
    fun problemsFromErrorBitsAndOcppStatus() = runBlocking {
        assertEquals(emptyList<DeviceProblem>(), BenderEcuDriver(ecu(), 1).read().problems)
        assertEquals(emptyList<DeviceProblem>(), BenderEcuDriver(ecu(ocpp = 7, vehicle = 2, power = 0), 1).read().problems)
        // 0x41 = RCM + contactor welded; 0x80 (byte 2 of 111) = backend disconnected.
        val fault = ecu(ocpp = 4, vehicle = 1, power = 0).u16(111, 0x4100).u16(112, 0)
        assertEquals(
            listOf(
                DeviceProblem(DeviceProblem.Kind.FAULT, "RCM triggered (error bit 0)"),
                DeviceProblem(DeviceProblem.Kind.FAULT, "Contactor welded (error bit 6)"),
            ),
            BenderEcuDriver(fault, 1).read().problems,
        )
        val backend = ecu(ocpp = 0, vehicle = 1, power = 0).u16(111, 0x8000).u16(112, 0)
        assertEquals(
            listOf(DeviceProblem(DeviceProblem.Kind.WARNING, "Backend disconnected (error bit 7)")),
            BenderEcuDriver(backend, 1).read().problems,
        )
        assertEquals(
            listOf(DeviceProblem(DeviceProblem.Kind.FAULT, "Charge point faulted (OCPP status 4)")),
            BenderEcuDriver(ecu(ocpp = 4, vehicle = 1, power = 0), 1).read().problems,
        )
    }
}
