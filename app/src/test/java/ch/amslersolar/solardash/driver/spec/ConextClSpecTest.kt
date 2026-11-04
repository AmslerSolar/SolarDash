// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.driver.spec

import ch.amslersolar.solardash.driver.sunspec.FakeRegisterImage
import ch.amslersolar.solardash.driver.sunspec.MoreSunSpecProfiles
import ch.amslersolar.solardash.driver.sunspec.SunSpecInverterDriver
import ch.amslersolar.solardash.model.DeviceProblem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Conext CL through its ModbusSpec file (schneider-conext-cl): the hand-written ConextClDriver's tests on the driver
 * that replaced it. Problem texts and detail titles are the file's, so problems are compared by kind.
 */
class ConextClSpecTest {
    private val library = SpecLibrary { java.io.File("src/main/assets/modbusspec/$it").readText() }

    /** The device type's driver, as [SpecDevices.create] makes it: schneider-conext-cl with the make's SunSpec profile and hooks. */
    @Suppress("TestFunctionName")
    private fun ConextClDriver(reader: ch.amslersolar.solardash.driver.sunspec.RegisterReader, unit: Int) = SunSpecSpecDriver(
        library.spec("schneider-conext-cl"), library.vocabulary, SpecTables(holding = reader, input = reader), unit,
        profile = SpecDevices.SUNSPEC_PROFILES.getValue(ch.amslersolar.solardash.model.DeviceType.CONEXT_CL),
        hooks = SpecDevices.hooks("schneider-conext-cl"), fixed = SpecDevices.sunSpecFixed("schneider-conext-cl"),
    )

    /**
     * Conext CL 25 at unit 10, as in map rev 1.5: Common at 0x9C42 with length 65 but a pad register at 0x9C85,
     * model 103 at 0x9C86 (W in 100 W steps), 160 at 0x9CBA with two inputs (W_SF 0), end at 0x9CEC. Unused
     * addresses read 0xFFFF without an exception. Vendor energy: lifetime 4910.9 kWh, today 85.3 kWh.
     */
    private fun cl(maker: String = "SCHNEIDER") = FakeRegisterImage(unitId = 10, filler = 0xFFFF).apply {
        u16(0x9C40, 0x5375); u16(0x9C41, 0x6E53)
        u16(0x9C42, 1); u16(0x9C43, 65)
        text(0x9C44, 16, maker); text(0x9C54, 16, "PVSCL25E"); text(0x9C64, 8, "PVSCL25E301"); text(0x9C6C, 8, "Ver2.0")
        text(0x9C74, 16, "205196AA000000"); u16(0x9C84, 10); u16(0x9C85, 0) // pad
        u16(0x9C86, 103); u16(0x9C87, 50)
        for (a in 0x9C88..0x9CB9) u16(a, 0)
        u16(0x9C90, 2_301); u16(0x9C91, 2_299); u16(0x9C92, 2_310); u16(0x9C93, 0xFFFF) // V, SF −1
        u16(0x9C94, 187); u16(0x9C95, 2) // 18 700 W
        u16(0x9C96, 500); u16(0x9C97, 0xFFFF) // 50.0 Hz
        u16(0x9CA3, 0xFFFF); u16(0x9CA5, 0x8000); u16(0x9CA6, 0x8000) // DC V / W "NA"
        u16(0x9CA7, 412); u16(0x9CAB, 0xFFFF) // 41.2 °C
        u16(0x9CAC, 4)
        u16(0x9CBA, 160); u16(0x9CBB, 48)
        for (a in 0x9CBC..0x9CEB) u16(a, 0)
        u16(0x9CBC, 0xFFFF); u16(0x9CBD, 0xFFFF); u16(0x9CBE, 0) // DCA_SF, DCV_SF −1, DCW_SF 0
        u16(0x9CC2, 2)
        u16(0x9CC4, 161); u16(0x9CCD, 151); u16(0x9CCE, 6_420); u16(0x9CCF, 9_655)
        u16(0x9CD8, 162); u16(0x9CE1, 148); u16(0x9CE2, 6_380); u16(0x9CE3, 9_444)
        u16(0x9CEC, 0xFFFF); u16(0x9CED, 0)
        u32(0x0802, 49_109); u32(0x0804, 853); u32(0x0806, 912); u32(0x081E, 313)
    }

    @Test
    fun walksTheChainDespiteTheCommonLengthAndAddsTheVendorEnergy() = runBlocking {
        val driver = ConextClDriver(cl(), 10)
        val info = driver.identify()
        assertEquals("PVSCL25E (firmware Ver2.0)", info.model)
        assertEquals("205196AA000000", info.serial)
        val r = driver.read()
        assertEquals(19_099, r.pvPowerW) // MPPT inputs at 1 W, not the 100 W AC steps
        assertEquals(listOf(1, 2), r.pvStrings.map { it.index }) // input ids 161 / 162 renumbered
        assertEquals(642.0, r.pvStrings[0].voltageV, 1e-9)
        assertEquals(85.3, r.today.pvKwh!!, 1e-9) // vendor daily counter
        assertEquals(4_910.9, r.lifetimePvKwh!!, 1e-9)
        assertEquals(listOf("L1", "L2", "L3"), r.phases.map { it.name })
        assertEquals("Producing", r.status)
        assertEquals(41.2, r.temperatureC!!, 1e-9)
        assertTrue(r.details.flatMap { it.rows }.any { it.second.startsWith("91.2") }) // yesterday, from the vendor registers
    }

    @Test
    fun theSharedSunSpecWalkHandlesTheCommonLength() = runBlocking {
        // Common says 65, a pad register follows: the chain walk takes 66 and finds model 103.
        assertEquals("Producing", SunSpecInverterDriver(cl(), 10, MoreSunSpecProfiles.CONEXT_CL).read().status)
        assertEquals(103, ch.amslersolar.solardash.driver.sunspec.SunSpecDevice(cl(), 10).discover().keys.elementAt(1))
    }

    @Test
    fun rejectsOtherMakes() = runBlocking {
        val error = runCatching { ConextClDriver(cl(maker = "Delta"), 10).identify() }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(error!!.message.orEmpty().contains("Not a Schneider"))
    }

    @Test
    fun noProblemsWithoutAnAlarm() = runBlocking {
        assertEquals(emptyList<DeviceProblem.Kind>(), ConextClDriver(cl(), 10).read().problems.map { it.kind }) // 0x1828 reads 0xFFFF filler
        assertEquals(emptyList<DeviceProblem.Kind>(), ConextClDriver(cl().apply { u16(0x1828, 0) }, 10).read().problems.map { it.kind })
    }

    @Test
    fun alarmCodeBecomesAProblem() = runBlocking {
        assertEquals(
            listOf(DeviceProblem.Kind.MAINS_LOST),
            ConextClDriver(cl().apply { u16(0x1828, 2450) }, 10).read().problems.map { it.kind },
        )
        assertEquals(
            listOf(DeviceProblem.Kind.WARNING),
            ConextClDriver(cl().apply { u16(0x1828, 4061) }, 10).read().problems.map { it.kind },
        )
        assertEquals(
            listOf(DeviceProblem.Kind.FAULT, DeviceProblem.Kind.FAULT),
            ConextClDriver(cl().apply { u16(0x9CAC, 7); u16(0x9CAD, 0); u16(0x1828, 2616) }, 10).read().problems.map { it.kind },
        )
        assertEquals(emptyList<DeviceProblem.Kind>(), ConextClDriver(cl().apply { u16(0x1828, 9000) }, 10).read().problems.map { it.kind }) // internal event
    }
}
