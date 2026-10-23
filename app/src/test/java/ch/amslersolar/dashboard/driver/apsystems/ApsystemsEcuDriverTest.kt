// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.apsystems

import ch.amslersolar.dashboard.driver.sunspec.FakeRegisterImage
import ch.amslersolar.dashboard.driver.sunspec.FakeRegisterImage.Companion.common
import ch.amslersolar.dashboard.driver.sunspec.FakeRegisterImage.Companion.model
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ApsystemsEcuDriverTest {
    private val nan = Double.NaN

    /** The SunSpec models in front of model 114: Common at 40002, 101 at 40070, 111 at 40122, 123 at 40184. */
    private fun chain(maker: String, vararg more: Triple<Int, Int, FakeRegisterImage.(Int) -> Unit>) =
        FakeRegisterImage(unitId = 5).sunSpec(
            40000,
            common(maker, "YC600", "V0", "409000064675"),
            model(101, 50) { b -> u16(b + 2, 450); u16(b + 6, 0xFFFE); u16(b + 14, 4); u16(b + 15, 2); u16(b + 38, 4) },
            model(111, 60) { b ->
                f32(b + 2, 4.51) // A
                f32(b + 4, 4.51)
                f32(b + 16, 231.4) // PhVphA
                f32(b + 18, nan); f32(b + 20, nan) // unimplemented
                f32(b + 22, 401.0) // W
                f32(b + 24, 50.02)
                f32(b + 32, 1_520_000.0) // WH
                f32(b + 34, nan); f32(b + 36, nan); f32(b + 38, nan) // DCA, DCV, DCW unimplemented
                f32(b + 40, 39.0)
                u16(b + 48, 4)
            },
            model(123, 24),
            *more,
        )

    /** DCV/DCA/DCW of model 114 whose ID register is at [b]: inputs 1 and 2 used, 3–8 not implemented. */
    private fun FakeRegisterImage.dcValues(b: Int) {
        f32(b + 2, 35.2); f32(b + 4, 34.8) // DCV1, DCV2
        f32(b + 18, 6.0); f32(b + 20, 5.8) // DCA1, DCA2
        f32(b + 34, 211.2); f32(b + 36, 201.8) // DCW1, DCW2
        for (k in 2 until 8) { f32(b + 2 + 2 * k, nan); f32(b + 18 + 2 * k, nan); f32(b + 34 + 2 * k, nan) }
    }

    /**
     * Microinverter with Modbus address 5 behind an ECU-R as in "SunSpec Modbus Rev 3.3": the chain ends after
     * model 123 (end marker at 40210), and model 114 sits at 40212 outside it. Its header reads 0, as in a
     * published ECU-R register dump; the DC values start at 40214.
     */
    private fun ecu(maker: String = "APsystems") = chain(maker).apply {
        u16(40212, 0); u16(40213, 0)
        dcValues(ApsystemsEcuDriver.DC_MODEL_ADDRESS)
    }

    @Test
    fun readsTheFloatModelAndTheDcInputsOfModel114() = runBlocking {
        val image = ecu()
        assertEquals(listOf(1, 101, 111, 123, 0xFFFF), listOf(40002, 40070, 40122, 40184, 40210).map { image.regs[it] }) // the spec's addresses
        val driver = ApsystemsEcuDriver(image, 5)
        val info = driver.identify()
        assertEquals("YC600 (firmware V0)", info.model)
        assertEquals("409000064675", info.serial)
        val r = driver.read()
        assertEquals(413, r.pvPowerW) // 211.2 + 201.8
        assertEquals(listOf(1, 2), r.pvStrings.map { it.index })
        assertEquals(35.2, r.pvStrings[0].voltageV, 1e-4)
        assertEquals(211, r.pvStrings[0].powerW)
        assertEquals(listOf("L1"), r.phases.map { it.name }) // single-phase: only PhVphA implemented
        assertEquals(1_520.0, r.lifetimePvKwh!!, 1e-9)
        assertEquals("Producing", r.status)
        assertNull(r.gridPowerW)
        assertTrue(r.details.any { it.title == "DC inputs (APsystems model 114)" && it.rows.size == 6 })
    }

    @Test
    fun model114FoundInTheChainWhenNotAtItsFixedAddress() = runBlocking {
        // An ECU without model 123 whose chain lists model 114 (at 40184): 40214–40261 can't be read in one go
        // (the image ends at 40235), so the chain is used.
        val image = FakeRegisterImage(unitId = 5).sunSpec(
            40000,
            common("APsystems", "YC600"),
            model(101, 50),
            model(111, 60) { b -> f32(b + 22, 401.0); f32(b + 16, 231.4); f32(b + 38, nan); u16(b + 48, 4) },
            model(ApsystemsEcuDriver.DC_MODEL, 48) { b -> dcValues(b) },
        )
        assertEquals(ApsystemsEcuDriver.DC_MODEL, image.regs[40184])
        val r = ApsystemsEcuDriver(image, 5).read()
        assertEquals(413, r.pvPowerW)
        assertEquals(listOf(1, 2), r.pvStrings.map { it.index })
    }

    @Test
    fun withoutModel114TheInverterModelStillReads() = runBlocking {
        val image = FakeRegisterImage(unitId = 5).sunSpec(
            40000,
            common("APsystems", "DS3"),
            model(111, 60) { b -> f32(b + 22, 640.0); f32(b + 16, 230.0); f32(b + 38, nan); u16(b + 48, 4) },
        )
        val r = ApsystemsEcuDriver(image, 5).read()
        assertNull(r.pvPowerW) // DCW unimplemented, no model 114
        assertEquals(listOf("L1"), r.phases.map { it.name })
    }

    @Test
    fun rejectsOtherMakes() = runBlocking {
        val error = runCatching { ApsystemsEcuDriver(ecu(maker = "Enphase"), 5).identify() }.exceptionOrNull()
        assertTrue(error is IOException)
    }
}
