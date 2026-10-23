// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.sunspec

import ch.amslersolar.dashboard.driver.fimer.FimerSunSpecDriver
import ch.amslersolar.dashboard.driver.kaco.KacoBlueplanetDriver
import ch.amslersolar.dashboard.driver.sunspec.FakeRegisterImage.Companion.common
import ch.amslersolar.dashboard.driver.sunspec.FakeRegisterImage.Companion.model
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class MoreSunSpecProfilesTest {
    /**
     * KACO blueplanet 125 NX3: the application note numbers from 40001, so "SunS" is at 40000 on the wire; nothing
     * answers at 0. Models 1, 103 (int + SF, 100 W steps), 113 (float) and 160 with two MPPT inputs.
     */
    private fun kaco(maker: String = "KACO new energy") = FakeRegisterImage(unitId = 1).sunSpec(
        40000,
        common(maker, "blueplanet 125 NX3", "V5.1.9.0", "125NX3012345"),
        model(103, 50) { b -> u16(b + 14, 950); u16(b + 15, 2); u16(b + 38, 4) },
        model(113, 60) { b ->
            f32(b + 22, 95_000.4) // W
            f32(b + 24, 50.0)
            f32(b + 16, 230.1); f32(b + 18, 231.0); f32(b + 20, 229.5)
            f32(b + 4, 137.0); f32(b + 6, 138.0); f32(b + 8, 136.0)
            f32(b + 32, 1.234e9) // WH
            f32(b + 40, 48.5) // TmpCab
            u16(b + 48, 4) // MPPT
        },
        model(160, 48) { b ->
            u16(b + 2, 0xFFFE); u16(b + 3, 0xFFFF); u16(b + 4, 0) // DCA_SF −2, DCV_SF −1, DCW_SF 0
            u16(b + 8, 2)
            for ((k, w) in listOf(48_000, 49_000).withIndex()) {
                val o = b + 10 + 20 * k
                u16(o, k + 1); u16(o + 9, 7_400); u16(o + 10, 6_500); u16(o + 11, w); u16(o + 17, 4)
            }
        },
    )

    @Test
    fun kacoReadsTheFloatModelAndTheMpptInputsFromTheChainAt40000() = runBlocking {
        val image = kaco()
        val driver = KacoBlueplanetDriver(image, 1)
        val info = driver.identify()
        assertEquals("blueplanet 125 NX3 (firmware V5.1.9.0)", info.model)
        assertEquals("125NX3012345", info.serial)
        assertEquals(0 to 2, image.requests.first()) // tried 0 first: exception, then 40000
        val r = driver.read()
        assertEquals(97_000, r.pvPowerW) // model 160, not the inverter's DC power
        assertEquals(listOf(650.0, 650.0), r.pvStrings.map { it.voltageV })
        assertEquals(74.0, r.pvStrings[0].currentA, 1e-9)
        assertEquals(listOf("L1", "L2", "L3"), r.phases.map { it.name })
        assertEquals(230.1, r.phases[0].voltageV, 1e-3)
        assertEquals("Producing", r.status)
        assertEquals(1_234_000.0, r.lifetimePvKwh!!, 1e-6)
        assertEquals(48.5, r.temperatureC!!, 1e-9)
        assertNull(r.gridPowerW) // no meter, no battery
        assertNull(r.battery)
        assertTrue(r.details.any { it.title == "Inverter (AC)" && it.rows.any { row -> row.second.startsWith("113") } })
    }

    @Test
    fun kacoRejectsOtherMakes() = runBlocking {
        val error = runCatching { KacoBlueplanetDriver(kaco(maker = "SMA"), 1).identify() }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(error!!.message.orEmpty().contains("Not a KACO"))
    }

    /** VSN300 logger on unit 247 in front of a three-phase TRIO (int + SF model 103). */
    private fun fimer(maker: String) = FakeRegisterImage(unitId = 247).sunSpec(
        40000,
        common(maker, "VSN300", "1.9.2", "111111-3N16-1234"),
        model(103, 50) { b ->
            u16(b + 14, 8_250); u16(b + 15, 0)
            u16(b + 16, 4_999); u16(b + 17, 0xFFFE)
            u16(b + 10, 2_301); u16(b + 11, 2_310); u16(b + 12, 2_295); u16(b + 13, 0xFFFF)
            u16(b + 31, 8_600); u16(b + 32, 0)
            u16(b + 24, 0x0123); u16(b + 25, 0x4567); u16(b + 26, 0)
            u16(b + 38, 4)
        },
    )

    @Test
    fun fimerAcceptsAbbAndFimerAsManufacturer() = runBlocking {
        for (maker in listOf("ABB", "FIMER")) {
            val driver = FimerSunSpecDriver(fimer(maker), 247)
            assertEquals("$maker VSN300 (firmware 1.9.2)", driver.identify().model)
            val r = driver.read()
            assertEquals(8_600, r.pvPowerW) // no model 160: DC power of model 103
            assertEquals(49.99, r.phases[0].frequencyHz, 1e-9)
            assertEquals(0x01234567 / 1000.0, r.lifetimePvKwh!!, 1e-9)
            assertNull(r.gridPowerW)
        }
    }

    @Test
    fun fimerRejectsOtherMakes() = runBlocking {
        val error = runCatching { FimerSunSpecDriver(fimer("Fronius"), 247).identify() }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(error!!.message.orEmpty().contains("Not a ABB / FIMER device"))
    }
}
