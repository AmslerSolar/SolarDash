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

class MyPvDriverTest {
    /** Common block 1000–1032 plus whatever [extra] adds; all documented registers present. */
    private fun mypv(serial: String, status: Int, power: Int, setpoint: Int = power): FakeRegisters {
        val f = FakeRegisters().on(1)
        for (a in 1000..1032) f.u16(a, 0)
        for (a in 1041..1088) f.u16(a, 0)
        return f.u16(1000, setpoint).u16(1001, 553).u16(1002, 600).u16(1003, status).u16(1006, 450)
            .u16(1015, 381).u16(1016, 203).u16(1017, 105).u16(1028, 3).text(1018, 8, serial)
            .u16(1082, power).u16(1083, power).u16(1084, 0).u16(1069, (-1_500) and 0xFFFF).u32(1087, -1_500L and 0xFFFFFFFFL)
    }

    @Test
    fun acThorHeating() = runBlocking {
        val fake = mypv("2001001234567890", status = 9, power = 2_100).u16(1061, 232).u16(1062, 91).u16(1064, 50_010)
        val d = MyPvDriver(fake, 1)
        val info = d.identify()
        assertEquals("my-PV AC•THOR", info.model)
        assertEquals("2001001234567890", info.serial)
        val r = d.read()
        assertConsumer(r)
        assertEquals(2_100, r.loadPowerW)
        assertEquals("Heating", r.status)
        assertEquals(55.3, r.temperatureC!!, 1e-9) // 0.1 °C
        assertEquals(1, r.phases.size) // AC•THOR replies 0 for L2/L3
        assertEquals(9.1, r.phases[0].currentA!!, 1e-9)
        assertEquals(50.01, r.phases[0].frequencyHz, 1e-9)
        assertEquals("-1500 W", r.row("Heating", "Meter power seen by device"))
        assertEquals("a0020303", r.row("Device", "Firmware"))
        assertEquals("38.1 °C", r.row("Heating", "Controller temperature"))
    }

    @Test
    fun acThor9sStandbyAndThreePhases() = runBlocking {
        val fake = mypv("2003001234567890", status = 9, power = 0)
            .u16(1061, 230).u16(1067, 231).u16(1068, 0).u16(1072, 229).u16(1073, 0)
        val r = MyPvDriver(fake, 1).read()
        assertEquals("Standby", r.status)
        assertEquals(0, r.loadPowerW)
        assertEquals(listOf("L1", "L2", "L3"), r.phases.map { it.name })
        assertEquals("my-PV AC•THOR 9s", r.row("Device", "Model"))
    }

    @Test
    fun elwa2StatesAndOutputFallback() = runBlocking {
        val heating = mypv("1601501234567890", status = 2, power = 1_800)
        assertEquals("Heating", MyPvDriver(heating, 1).read().status)
        val done = MyPvDriver(mypv("1601501234567890", status = 5, power = 0), 1).read()
        assertEquals("Heat finished", done.status)
        assertEquals("Error: Safety thermostat triggered", MyPvDriver(mypv("1601501234567890", 201, 0), 1).read().status)
        // Without 1082 the output powers are summed.
        val noTotal = mypv("1601501234567890", status = 2, power = 0).remove(1082).u16(1074, 1_500).u16(1075, 300)
        assertEquals(1_800, MyPvDriver(noTotal, 1).read().loadPowerW)
    }

    @Test
    fun oldElwaEFallsBackToSetpoint() = runBlocking {
        val fake = mypv("1601241234567890", status = 4, power = 0, setpoint = 2_500).remove(1082).u16(1006, 45)
        val d = MyPvDriver(fake, 1)
        assertEquals("my-PV AC ELWA-E", d.identify().model)
        val r = d.read()
        assertEquals(2_500, r.loadPowerW)
        assertEquals("Boost heating", r.status)
        assertEquals("00203.03", r.row("Device", "Firmware"))
        assertEquals("45 °C", r.row("Heating", "Boost / min. temperature")) // whole degrees on the ELWA-E
        assertNull(r.row("Heating", "Temp 2")) // 1030 is a meter value on the ELWA-E
    }

    @Test
    fun missingTemperature() = runBlocking {
        val r = MyPvDriver(mypv("2001001234567890", status = 9, power = 0).remove(1001), 1).read()
        assertNull(r.temperatureC)
        assertEquals("Standby", r.status)
    }

    @Test
    fun otherDevicesAreRejected() = runBlocking {
        val noSerial = FakeRegisters().on(1).u16(1003, 2).text(1018, 8, "NOT A SERIAL").u16(1013, 1).u16(1014, 0).u16(1015, 0).u16(1016, 0)
        for (fake in listOf(noSerial, FakeRegisters())) {
            try {
                MyPvDriver(fake, 1).identify()
                fail("identify should fail")
            } catch (_: IOException) {
            }
        }
    }

    @Test
    fun problemsFromStatusCodes() = runBlocking {
        assertEquals(emptyList<DeviceProblem>(), MyPvDriver(mypv("2001001234567890", status = 9, power = 2_100), 1).read().problems)
        assertEquals(emptyList<DeviceProblem>(), MyPvDriver(mypv("1601501234567890", status = 3, power = 0), 1).read().problems)
        assertEquals(
            listOf(DeviceProblem(DeviceProblem.Kind.FAULT, "Power stage error (status 201)")),
            MyPvDriver(mypv("2001001234567890", status = 201, power = 0), 1).read().problems,
        )
        assertEquals(
            listOf(DeviceProblem(DeviceProblem.Kind.FAULT, "Safety thermostat triggered (status 201)")),
            MyPvDriver(mypv("1601501234567890", status = 201, power = 0), 1).read().problems,
        )
        assertEquals(
            listOf(DeviceProblem(DeviceProblem.Kind.FAULT, "Temperature sensor fault (status 205)")),
            MyPvDriver(mypv("1601241234567890", status = 205, power = 0), 1).read().problems,
        )
    }
}
