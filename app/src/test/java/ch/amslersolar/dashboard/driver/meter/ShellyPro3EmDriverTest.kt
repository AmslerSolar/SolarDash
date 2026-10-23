// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.meter

import ch.amslersolar.dashboard.model.DeviceProblem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class ShellyPro3EmDriverTest {
    /** [w]: phase active powers as the Shelly reports them (+ = consumed from the grid). */
    private fun shelly(w: List<Double>, lowWordFirst: Boolean = true, hz: Double = 50.0) =
        MeterRegisterFake(unitId = 1, lowWordFirst = lowWordFirst, lenient = true).apply {
            text(30000, 6, "A8032ABE1234").text(30006, 10, "SPEM-003CEBEU").text(30016, 32, "Netzanschluss")
            u32(31000, 1_669_379_021)
            f32(31007, 0.3)
            f32(31011, w.sumOf { kotlin.math.abs(it) } / 230.0); f32(31013, w.sum()); f32(31015, w.sumOf { kotlin.math.abs(it) } * 1.05)
            w.forEachIndexed { i, p ->
                val b = 31020 + 20 * i
                f32(b, 232.2 + i); f32(b + 2, kotlin.math.abs(p) / 232.0); f32(b + 4, p); f32(b + 6, kotlin.math.abs(p) * 1.05)
                f32(b + 8, 0.95); f32(b + 13, hz)
                val d = 31170 + 20 * i
                f32(d + 12, 1_000_000.0); f32(d + 14, 500_000.0)
            }
            u16(31072, 1) // phase C overcurrent
            u32(31160, 1_669_379_000); f32(31162, 3_000_000.0); f32(31164, 1_500_000.0)
        }

    @Test
    fun identifiesAndReadsImport() = runBlocking {
        val driver = ShellyPro3EmDriver(shelly(listOf(951.2, 700.0, 348.8)), 1)
        val info = driver.identify()
        assertEquals("SPEM-003CEBEU", info.model)
        assertEquals("A8032ABE1234", info.serial)

        val r = driver.read()
        assertEquals(-2_000, r.gridPowerW)
        assertEquals(listOf(-951, -700, -349), r.phases.map { it.powerW })
        assertEquals(232.2, r.phases[0].voltageV, 1e-3)
        assertEquals(50.0, r.phases[2].frequencyHz, 1e-6)
        val energy = rows(r, "Energy (lifetime)")
        assertEquals("3000.0 kWh", energy["Active energy (import)"])
        assertEquals("1500.0 kWh", energy["Active returned energy (export)"])
        assertEquals("Yes", rows(r, "L3")["Overcurrent"])
        assertEquals("No", rows(r, "L1")["Overcurrent"])
        assertEquals("2022-11-25T12:23:41Z", rows(r, "Device")["Last update (UTC)"])
        assertNull(r.today.importKwh)
    }

    @Test
    fun exportIsPositive() = runBlocking {
        // As in the EM status example: one phase negative (returning).
        val r = ShellyPro3EmDriver(shelly(listOf(-1_500.0, -951.1, 200.0)), 1).read()
        assertEquals(2_251, r.gridPowerW)
        assertEquals(951, r.phases[1].powerW)
        assertEquals(-200, r.phases[2].powerW)
    }

    @Test
    fun wordOrderIsDetected() = runBlocking {
        // Documented examples use low word first ("mixed"); a high-word-first image is recognised as well.
        for (order in listOf(true, false)) {
            val r = ShellyPro3EmDriver(shelly(listOf(100.0, 100.0, 100.0), lowWordFirst = order), 1).read()
            assertEquals(-300, r.gridPowerW)
            assertEquals(232.2, r.phases[0].voltageV, 1e-3)
        }
    }

    @Test
    fun nonMatchingDeviceThrows() {
        assertThrows(IOException::class.java) { runBlocking { ShellyPro3EmDriver(silentDevice, 1).identify() } }
        // Something answering input registers with zeros: no model string.
        val zeros = MeterRegisterFake(unitId = 1, lenient = true)
        assertThrows(IOException::class.java) { runBlocking { ShellyPro3EmDriver(zeros, 1).identify() } }
        // A model string but nonsense measurements (999 Hz).
        val odd = shelly(listOf(1.0, 1.0, 1.0), hz = 999.0)
        assertThrows(IOException::class.java) { runBlocking { ShellyPro3EmDriver(odd, 1).identify() } }
    }

    @Test
    fun missingValuesAreNull() = runBlocking {
        val fake = shelly(listOf(100.0, 100.0, 100.0))
        fake.regs.remove(31042); fake.regs.remove(31043) // phase B current: lenient fake answers 0 → 0.0
        fake.f32(31042, Double.NaN) // a NaN float is treated as missing
        val r = ShellyPro3EmDriver(fake, 1).read()
        assertNull(r.phases[1].currentA)
        assertEquals(-300, r.gridPowerW)
    }

    @Test
    fun errorBooleansAreWarnings() = runBlocking {
        // Phase C overcurrent (alarm threshold flag) is set in the fixture and is not a problem.
        assertEquals(emptyList<DeviceProblem>(), ShellyPro3EmDriver(shelly(listOf(100.0, 0.0, 0.0)), 1).read().problems)
        val fake = shelly(listOf(100.0, 0.0, 0.0)).apply { u16(31003, 1); u16(31006, 1) }
        assertEquals(
            listOf(
                DeviceProblem(DeviceProblem.Kind.WARNING, "L2 meter error"),
                DeviceProblem(DeviceProblem.Kind.WARNING, "Phase sequence error"),
            ),
            ShellyPro3EmDriver(fake, 1).read().problems,
        )
    }
}
