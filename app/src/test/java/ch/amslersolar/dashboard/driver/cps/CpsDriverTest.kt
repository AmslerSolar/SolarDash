// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.cps

import ch.amslersolar.dashboard.driver.modbus.ModbusException
import ch.amslersolar.dashboard.driver.modbus.RegisterBlock
import ch.amslersolar.dashboard.driver.sunspec.RegisterReader
import ch.amslersolar.dashboard.model.DeviceProblem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CpsDriverTest {
    /** Input registers; reads touching an undefined register fail with exception 2. */
    private class FakeCps : RegisterReader {
        val regs = HashMap<Int, Int>()
        val requests = mutableListOf<Pair<Int, Int>>()
        fun u16(a: Int, v: Int) = apply { regs[a] = v and 0xFFFF }
        fun u32(a: Int, v: Long) = apply { u16(a, (v shr 16).toInt()); u16(a + 1, v.toInt()) }
        fun text(a: Int, n: Int, s: String) = apply {
            val p = s.padEnd(n * 2, '\u0000')
            for (i in 0 until n) u16(a + i, (p[2 * i].code shl 8) or p[2 * i + 1].code)
        }
        fun fill(range: IntRange, v: Int = 0) = apply { range.forEach { regs.putIfAbsent(it, v) } }

        override suspend fun read(unitId: Int, register: Int, count: Int): RegisterBlock {
            assertEquals(1, unitId)
            assertTrue(count in 1..125)
            requests += register to count
            val data = ByteArray(count * 2)
            for (i in 0 until count) {
                val v = regs[register + i] ?: throw ModbusException(2)
                data[2 * i] = (v shr 8).toByte(); data[2 * i + 1] = v.toByte()
            }
            return RegisterBlock(register, data)
        }
    }

    /** Common group 0x0000–[last] with identification; everything else 0. */
    private fun cps(device: Int, model: String, last: Int = 0x0048) = FakeCps().apply {
        u16(0x0000, device); u16(0x0003, 80); u16(0x0004, 315); u16(0x0005, 0x1203)
        u16(0x0006, 0x0001); u16(0x0007, 0x0100); u16(0x0008, 0x9111); u16(0x0009, 0x4001) // SN 1010091114001
        text(0x000A, 10, model); u16(0x0014, 100); u16(0x0015, 0x1000)
        fill(0x0001..last)
    }

    /** 60 kW at 480 V: 55.2 kW AC, 56.4 kW DC from three inputs. */
    private fun sixty() = cps(0x4034, "SC60KTL-DO/US-480").apply {
        u32(0x0016, 0x01562318); u16(0x0018, 3125); u16(0x0019, 985); u16(0x001A, 999)
        u16(0x001D, 552); u16(0x001E, 553)
        u16(0x001F, 4802); u16(0x0020, 4795); u16(0x0021, 4810)
        u16(0x0022, 664); u16(0x0023, 665); u16(0x0024, 663)
        u16(0x0025, 6400); u16(0x0026, 300); u16(0x0027, 6380); u16(0x0028, 295); u16(0x0029, 0); u16(0x002A, 0)
        u16(0x002B, 600); u16(0x002C, 523); u16(0x002D, -51); u16(0x002E, 0xFFFF)
        u16(0x002F, 0x1000); u16(0x0035, 0x0002); u16(0x003B, -12)
        u16(0x0041, 0x0115); u16(0x0045, 564); u16(0x0046, 60); u16(0x0047, 66)
    }

    @Test
    fun fiftySixtyKwFamily() = runBlocking {
        val d = CpsDriver(sixty(), 1)
        val info = d.identify()
        assertEquals("CPS SC60KTL-DO/US-480", info.model)
        assertEquals("1010091114001", info.serial)
        val r = d.read()
        assertEquals(56_400, r.pvPowerW) // Pdc 0.1 kW
        assertEquals(listOf(1, 2), r.pvStrings.map { it.index }) // PV3 at 0 V left out
        assertEquals(640.0, r.pvStrings[0].voltageV, 1e-9)
        assertEquals(30.0, r.pvStrings[0].currentA, 1e-9)
        assertNull(r.pvStrings[0].powerW)
        assertTrue(r.phases.isEmpty()) // only line-to-line voltages: in the details, not as phases
        assertEquals(52.3, r.temperatureC!!, 1e-9)
        assertEquals("Running", r.status)
        assertEquals(312.5, r.today.pvKwh!!, 1e-9)
        assertEquals(22_422_296.0, r.lifetimePvKwh!!, 1e-9) // the spec's own example
        assertNull(r.gridPowerW)
        val inv = r.details.first { it.title == "Inverter" }.rows.toMap()
        assertEquals("50/60 kW 480 V", inv["Family"])
        assertEquals("0x0002", inv["Warning"])
        assertEquals("-5.1 °C", inv["Ambient temperature"])
        assertEquals("01.15", inv["Firmware (DSP.LCD major)"])
        val ac = r.details.first { it.title == "AC output" }.rows.toMap()
        assertEquals("55200 W", ac["Active power"])
        assertEquals("-1200 var", ac["Reactive power"])
        assertEquals("98.5 %", ac["Efficiency"])
    }

    @Test
    fun staticDataIsReadOncePerTwelvePolls() = runBlocking {
        val fake = sixty()
        val d = CpsDriver(fake, 1)
        repeat(13) { d.read() }
        assertEquals(2, fake.requests.count { it.first == 0x0000 })
        assertEquals(13, fake.requests.count { it == (0x0016 to 0x0048 - 0x0016 + 1) })
    }

    @Test
    fun twentyFiveKwHasNoPdcAndShorterGroup() = runBlocking {
        val fake = cps(0x4036, "SC25KTL-DO/US-480", last = 0x0042).apply {
            u16(0x001D, 240); u16(0x001F, 4800)
            u16(0x0025, 6000); u16(0x0026, 150); u16(0x0027, 5000); u16(0x0028, 200)
            u16(0x002E, 612); u16(0x002F, 0x0800)
        }
        val d = CpsDriver(fake, 1)
        val r = d.read()
        assertTrue(fake.requests.contains(0x0016 to 0x0042 - 0x0016 + 1))
        assertEquals(19_000, r.pvPowerW) // 600 V × 15 A + 500 V × 20 A
        assertEquals("Derating", r.status)
        assertTrue(r.details.first { it.title == "Inverter" }.rows.contains("Transformer temperature" to "61.2 °C"))
    }

    @Test
    fun hundredTwentyFiveKwHasOnePvInput() = runBlocking {
        val fake = cps(0x4035, "SCA125KTL-DO/US-600").apply {
            u16(0x001D, 1210); u16(0x0025, 9000); u16(0x0026, 1380); u16(0x0027, 7000); u16(0x0028, 500)
            u16(0x0045, 1235); u16(0x002F, 0x0000)
        }
        val r = CpsDriver(fake, 1).read()
        assertEquals(1, r.pvStrings.size) // 0x0027–0x002A are reserved on this family
        assertEquals(138.0, r.pvStrings[0].currentA, 1e-9)
        assertEquals(123_500, r.pvPowerW)
        assertEquals("Power off", r.status)
    }

    @Test
    fun threeFiftyKwCurrentsInHundredths() = runBlocking {
        val fake = cps(0x403F, "SCH350KTL-DO/US-800").apply { u16(0x0025, 12000); u16(0x0026, 9876); u16(0x0045, 0xFFFF); u16(0x001D, 1100) }
        val r = CpsDriver(fake, 1).read()
        assertEquals(98.76, r.pvStrings[0].currentA, 1e-9)
        assertEquals(118_512, r.pvPowerW) // no Pdc: 1200 V × 98.76 A
    }

    @Test
    fun mapsWithGapsAreReadInDocumentedParts() = runBlocking {
        // SCH250-T-US-600: no 0x0001–0x0002, 0x0025–0x002A, 0x002E, 0x003C–0x0040, 0x0042 in the map.
        val fake = FakeCps().apply {
            u16(0x0000, 0x4048); fill(0x0003..0x0024); fill(0x002B..0x002D); fill(0x002F..0x003B); u16(0x0041, 0); fill(0x0043..0x0048)
            text(0x000A, 10, "SCH250-T-US-600")
            u16(0x0022, -15) // signed currents in this family
            u16(0x001F, 6000); u16(0x0045, 2480); u16(0x001D, 2400); u16(0x002F, 0x0400); u16(0x0019, 98)
        }
        val d = CpsDriver(fake, 1)
        assertEquals("CPS SCH250-T-US-600", d.identify().model)
        assertEquals("", d.identify().serial) // serial registers are 0
        val r = d.read()
        assertEquals(248_000, r.pvPowerW)
        assertTrue(r.pvStrings.isEmpty())
        assertEquals("Soft shutdown", r.status)
        assertTrue(r.details.first { it.title == "AC output" }.rows.contains("Efficiency" to "98.0 %"))
        fake.requests.clear()
        d.read()
        assertTrue(fake.requests.none { it.first == 0x0025 }) // rejected parts aren't asked again
    }

    @Test
    fun notImplementedValuesAreMissing() = runBlocking {
        val fake = sixty().apply {
            u32(0x0016, 0xFFFFFFFFL); u16(0x0018, 0xFFFF); u16(0x002C, 0x8000); u16(0x0045, 0xFFFF)
            u16(0x0026, 0x8000); u16(0x002F, 0x0600)
            for (a in 0x0006..0x0009) u16(a, 0xFFFF)
        }
        val d = CpsDriver(fake, 1)
        assertEquals("", d.identify().serial)
        val r = d.read()
        assertNull(r.lifetimePvKwh)
        assertNull(r.today.pvKwh)
        assertNull(r.temperatureC)
        assertEquals(0.0, r.pvStrings[0].currentA, 1e-9)
        assertEquals(18_821, r.pvPowerW) // no Pdc: only PV2 has a current (638 V × 29.5 A)
        assertEquals("Mode 0x0600", r.status)
    }

    @Test
    fun otherDevicesAreRejected() {
        assertThrows(IOException::class.java) { runBlocking { CpsDriver(cps(0x0103, "x"), 1).identify() } }
        assertThrows(IOException::class.java) { runBlocking { CpsDriver(cps(0x4043, "12K"), 1).identify() } }
        assertThrows(IOException::class.java) { runBlocking { CpsDriver(FakeCps(), 1).identify() } }
    }

    @Test
    fun unknownCodeInTheFamilyUsesTheCommonLayout() = runBlocking {
        val r = CpsDriver(cps(0x403A, "SC25KTL-DO/US-208").apply { u16(0x0045, 200) }, 1).read()
        assertEquals(20_000, r.pvPowerW)
        assertTrue(r.details.first().rows.contains("Family" to "403X"))
    }

    @Test
    fun fiftySixtyKwEventBitsAreDecoded() = runBlocking {
        assertEquals(emptyList<DeviceProblem>(), CpsDriver(sixty().apply { u16(0x0035, 0) }, 1).read().problems)
        assertEquals(listOf(DeviceProblem(DeviceProblem.Kind.WARNING, "Internal fan alarm (IntFanErr)")), CpsDriver(sixty(), 1).read().problems)
        val r = CpsDriver(sixty().apply { u16(0x0035, 0); u16(0x002F, 0x8000); u16(0x0036, 1 shl 11); u16(0x0037, 1 shl 3) }, 1).read()
        assertEquals(
            listOf(
                DeviceProblem(DeviceProblem.Kind.MAINS_LOST, "Grid frequency low (GridF.OutLim)"),
                DeviceProblem(DeviceProblem.Kind.FAULT, "Insulation resistance too low (IsolationErr)"),
            ),
            r.problems,
        )
        val modeOnly = CpsDriver(sixty().apply { u16(0x0035, 0); u16(0x002F, 0x8000) }, 1).read()
        assertEquals(listOf(DeviceProblem(DeviceProblem.Kind.FAULT, "Fault (mode 0x8000)")), modeOnly.problems)
    }

    @Test
    fun otherFamiliesReportTheRawFaultWords() = runBlocking {
        val r = CpsDriver(cps(0x4035, "SCA125KTL-DO/US-600").apply { u16(0x002F, 0x8000); u16(0x0036, 0x0400) }, 1).read()
        assertEquals(listOf(DeviceProblem(DeviceProblem.Kind.FAULT, "Fault0 0x0400")), r.problems)
        assertEquals(emptyList<DeviceProblem>(), CpsDriver(cps(0x4035, "SCA125KTL-DO/US-600").apply { u16(0x002F, 0x1000) }, 1).read().problems)
    }
}
