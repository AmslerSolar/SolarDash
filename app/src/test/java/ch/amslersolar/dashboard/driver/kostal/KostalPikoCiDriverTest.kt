// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.kostal

import ch.amslersolar.dashboard.driver.sunspec.FakeRegisterImage
import ch.amslersolar.dashboard.model.DeviceProblem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class KostalPikoCiDriverTest {
    /**
     * PIKO CI 50 at unit 71 (map rev 1.5): no register 5, no battery registers; four MPPT inputs in the 0x7000
     * block; meter at the grid connection reading −2 kW (feed-in); 4.6 kW PV, home 2.4 kW from PV + 0.2 kW grid.
     */
    private fun piko(wordSwapped: Boolean, product: String = "PIKO CI", meterOnLoad: Boolean = false) =
        FakeRegisterImage(unitId = 71, lowWordFirst = wordSwapped).apply {
            text(768, 32, product); text(800, 32, "50"); text(14, 8, "7654321X"); text(535, 16, "KOSTAL")
            u16(34, 4); u16(32, 3); u16(611, if (meterOnLoad) 1 else 0); u16(609, 0)
            u32(56, 6)
            f32(100, 4_600.0)
            listOf(Triple(3.1, 1_200.0, 387.0), Triple(3.0, 1_150.0, 383.0), Triple(2.9, 1_130.0, 390.0), Triple(2.8, 1_120.0, 400.0))
                .forEachIndexed { k, (a, w, v) -> f32(0x7000 + 6 * k, a); f32(0x7002 + 6 * k, w); f32(0x7004 + 6 * k, v) }
            f32(150, 1.0); f32(152, 50.02)
            f32(154, 6.5); f32(156, 1_500.0); f32(158, 230.5)
            f32(160, 6.4); f32(162, 1_480.0); f32(164, 231.5)
            f32(166, 6.6); f32(168, 1_520.0); f32(170, 229.5)
            f32(172, 4_500.0)
            f32(220, 50.0); f32(252, -2_000.0)
            f32(116, 2_400.0); f32(108, 200.0)
            f32(320, 98_765_000.0); f32(322, 21_300.0)
        }

    @Test
    fun readsTheWordSwappedDefault() = runBlocking {
        val driver = KostalPikoCiDriver(piko(wordSwapped = true), 71)
        val info = driver.identify()
        assertEquals("PIKO CI 50", info.model)
        assertEquals("7654321X", info.serial)
        check(driver.read())
    }

    @Test
    fun detectsBigEndianFloatsFromTheGridFrequency() = runBlocking {
        val r = KostalPikoCiDriver(piko(wordSwapped = false), 71).read()
        check(r)
        assertTrue(r.details.first { it.title == "Device" }.rows.toMap()["Float word order"]!!.startsWith("Big-endian"))
    }

    private fun check(r: ch.amslersolar.dashboard.model.DeviceReading) {
        assertEquals(4_600, r.pvPowerW)
        assertEquals(listOf(1, 2, 3, 4), r.pvStrings.map { it.index })
        assertEquals(400.0, r.pvStrings[3].voltageV, 1e-3)
        assertEquals(1_120, r.pvStrings[3].powerW)
        assertEquals(2_000, r.gridPowerW) // meter −2 kW = feed-in → + export
        assertEquals(2_600, r.loadPowerW)
        assertNull(r.battery)
        assertEquals("Feed-in", r.status)
        assertEquals(21.3, r.today.pvKwh!!, 1e-6)
        assertEquals(98_765.0, r.lifetimePvKwh!!, 1e-3)
        assertEquals(listOf("L1", "L2", "L3"), r.phases.map { it.name })
        assertEquals(50.02, r.phases[0].frequencyHz, 1e-4)
    }

    @Test
    fun meterOnTheLoadSideGivesHomeConsumptionAndNoGrid() = runBlocking {
        val image = piko(wordSwapped = true, meterOnLoad = true).apply { f32(252, 2_600.0) }
        val r = KostalPikoCiDriver(image, 71).read()
        assertNull(r.gridPowerW)
        assertEquals(2_600, r.loadPowerW)
    }

    @Test
    fun rejectsOtherKostalProducts() = runBlocking {
        val error = runCatching { KostalPikoCiDriver(piko(wordSwapped = true, product = "PLENTICORE plus"), 71).identify() }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(error!!.message.orEmpty().contains("Not a KOSTAL PIKO CI"))
    }

    @Test
    fun thePlenticoreDriverMisreadsAPikoCi() = runBlocking {
        val r = KostalPlenticoreDriver(piko(wordSwapped = true), 71).read()
        assertNull(r.pvPowerW) // no 1066, and the inputs are only in the 0x7000 block
        assertNull(r.loadPowerW) // no 106 (home consumption from battery)
    }

    @Test
    fun noProblemsWithoutErrorBits() = runBlocking {
        assertEquals(emptyList<DeviceProblem>(), KostalPikoCiDriver(piko(wordSwapped = true), 71).read().problems)
    }

    @Test
    fun errorMessageBitsBecomeProblems() = runBlocking {
        val img = piko(wordSwapped = true).apply {
            u16(4126, (1 shl 3) or (1 shl 2)) // over-temperature; remote off isn't a problem
            u16(4127, (1 shl 2) or (1 shl 6) or (1 shl 11)) // grid absent, insulation; PV under-voltage isn't one
            u16(4128, 1 shl 6) // meter lost
        }
        assertEquals(
            listOf(
                DeviceProblem(DeviceProblem.Kind.WARNING, "Over-temperature (4126 bit 3)"),
                DeviceProblem(DeviceProblem.Kind.MAINS_LOST, "Grid absent (4127 bit 2)"),
                DeviceProblem(DeviceProblem.Kind.FAULT, "PV insulation abnormal (4127 bit 6)"),
                DeviceProblem(DeviceProblem.Kind.WARNING, "Meter lost (4128 bit 6)"),
            ),
            KostalPikoCiDriver(img, 71).read().problems,
        )
    }
}
