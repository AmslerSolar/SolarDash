// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.driver.spec

import ch.amslersolar.solardash.driver.sunspec.FakeRegisterImage
import ch.amslersolar.solardash.driver.sunspec.FakeRegisterImage.Companion.common
import ch.amslersolar.solardash.driver.sunspec.FakeRegisterImage.Companion.model
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** ModbusSpec files marked `sunspec: true`: the chain is walked on the device, the file adds the rest. */
class SunSpecSpecDriverTest {
    private val library = SpecLibrary { File("src/main/assets/modbusspec/$it").readText() }

    private fun driver(id: String, image: FakeRegisterImage, unit: Int) =
        SunSpecSpecDriver(library.spec(id), library.vocabulary, SpecTables(holding = image, input = image), unit)

    /** Inverter model 103 (int + SF): AC power, DC power, state "producing". */
    private fun inverter103(acW: Int, dcW: Int) = model(103, 50) { b -> u16(b + 14, acW); u16(b + 31, dcW); u16(b + 38, 4) }

    @Test
    fun theChainIsFoundWhereTheDeviceHasIt() = runBlocking {
        // The file gives the usual chain at 40000; this device starts at 50000 with the nameplate model before 103.
        val image = FakeRegisterImage(1).sunSpec(50000, common("Fronius", "Symo 8.2-3-M", serial = "12345678"), model(120, 26), inverter103(2900, 3000))
        val d = driver("fronius-sunspec", image, 1)
        assertTrue(library.spec("fronius-sunspec").sunspec)
        assertEquals("Fronius Symo 8.2-3-M", d.identify().model)
        assertEquals("12345678", d.identify().serial)
        val r = d.read()
        assertEquals(3000, r.pvPowerW)
        assertEquals("Producing", r.status)
    }

    /** Meter model 203 (int + SF): total W at offset 18, its scale factor at 22. */
    private fun meter203(w: Int) = model(203, 105) { b -> u16(b + 18, w); u16(b + 8, 2300); u16(b + 15, -1); u16(b + 16, 5000); u16(b + 17, -2) }

    @Test
    fun theGridSignComesFromTheFile() = runBlocking {
        // fronius-sunspec-meter (openWB): + = export; fronius-smart-meter (Fronius): + = import.
        val export = driver("fronius-sunspec-meter", FakeRegisterImage(1).sunSpec(40000, common("Fronius", "Smart Meter"), meter203(1500)), 1).read()
        assertEquals(1500, export.gridPowerW)
        val import = driver("fronius-smart-meter", FakeRegisterImage(200).sunSpec(40000, common("Fronius", "Smart Meter"), meter203(1500)), 200).read()
        assertEquals(-1500, import.gridPowerW)
        assertEquals(230.0, import.phases.single().voltageV, 0.01)
        assertEquals(50.0, import.phases.single().frequencyHz, 0.01)
    }

    @Test
    fun vendorRegistersBesideTheChainAreRead() = runBlocking {
        val image = FakeRegisterImage(10).sunSpec(40000, common("SCHNEIDER", "Conext CL 25000", serial = "ZX123"), inverter103(20000, 20500))
            .u32(2050, 123_456).u32(2052, 456).u32(2054, 789).u32(2078, 4242).u16(6184, 0)
        val d = driver("schneider-conext-cl", image, 10)
        assertEquals("ZX123", d.identify().serial) // the file's identify checks
        val r = d.read()
        assertEquals(20500, r.pvPowerW)
        assertEquals(45.6, r.today.pvKwh!!, 0.01) // only in the vendor registers
        assertTrue(r.details.flatMap { it.rows }.any { it.second.startsWith("4242") })
        // Nothing is read at the file's fixed chain addresses beyond the walk.
        assertTrue(image.requests.toString(), image.requests.none { (a, _) -> a in 40100..40200 && a != 40070 && a != 40122 })
    }
}
