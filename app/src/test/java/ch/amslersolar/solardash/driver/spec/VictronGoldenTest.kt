// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.driver.spec

import ch.amslersolar.solardash.model.DeviceProblem
import ch.amslersolar.solardash.model.DeviceType
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Victron GX system service: the ModbusSpec driver (victron-gx-system) must read what the hand-written driver read
 * on the same register images ([VictronImages]), recorded in `golden/VictronGoldenTest/old-drivers.txt` before
 * that driver was removed: energy flow, battery, status and the kinds of problems, on a first and a second poll.
 */
class VictronGoldenTest {
    private val library = SpecLibrary { File("src/main/assets/modbusspec/$it").readText() }
    private val old = OldDriverRecords("VictronGoldenTest")

    private fun driver(fake: VictronImages.FakeGx) = SpecDriver(
        library.spec("victron-gx-system"), library.vocabulary, SpecTables(holding = fake, input = fake), 100,
        SpecDevices.hooks("victron-gx-system"), requestGapMs = 0,
    )

    @Test
    fun theDeviceTypeReadsThroughTheSpecFile() {
        assertEquals(listOf("victron-gx-system"), SpecDevices.SPEC_IDS[DeviceType.VICTRON])
    }

    @Test
    fun readsWhatTheHandWrittenDriverRead() = runBlocking {
        for ((name, image) in VictronImages.CASES) {
            val d = driver(image())
            // Status texts may differ in case: the file has the register list's ("VE.Bus Fault condition").
            assertEquals(name, old["read/$name"].lowercase(), VictronImages.essentials(d.read()).lowercase())
            assertEquals(name, old["second read/$name"].lowercase(), VictronImages.essentials(d.read()).lowercase())
        }
    }

    @Test
    fun aDynamicEssErrorIsAWarningOnlyWhileDynamicEssIsActive() = runBlocking {
        val warnings = driver(VictronImages.faults()).read().problems.filter { it.kind == DeviceProblem.Kind.WARNING }
        assertEquals(listOf("Dynamic ESS: SOC low (4)"), warnings.map { it.text })
        assertTrue(driver(VictronImages.inverting()).read().problems.isEmpty())
    }

    @Test
    fun olderFirmwareIsReadWithoutTheMissingRegisters() = runBlocking {
        val fake = VictronImages.singlePhaseOldFirmware()
        val d = driver(fake)
        // Refused blocks and registers are remembered: from the third poll on, only what answers is asked (the
        // second poll's requests are made from the registers left, and may still reach across a missing one).
        d.read()
        d.read()
        val before = fake.requests.size
        d.read()
        assertTrue(fake.requests.drop(before).all { (a, n) -> (a until a + n).all { it in fake.regs } })
    }

    @Test
    fun aGxAnsweringNothingIsAnError() = runBlocking {
        // A unit id the GX doesn't know: every request gets gateway exception 10.
        try {
            driver(VictronImages.FakeGx(code = 0x0A)).read()
            fail("expected IOException")
        } catch (_: IOException) {
        }
    }

    @Test
    fun identifiesTheGxByItsSerialAndNotOtherDevices() = runBlocking {
        assertEquals("c0619ab1f2e3", driver(VictronImages.threePhase()).identify().serial)
        assertEquals("c0619ab1f2e3", driver(VictronImages.singlePhaseOldFirmware()).identify().serial)
        for (other in listOf(VictronImages.FakeGx(), VictronImages.FakeGx().text(800, 6, "SUN2000-10KTL").u16(834, 1))) {
            try {
                driver(other).identify()
                fail("expected IOException")
            } catch (_: IOException) {
            }
        }
    }
}
