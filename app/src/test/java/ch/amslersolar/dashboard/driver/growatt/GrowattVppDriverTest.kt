// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.growatt

import ch.amslersolar.dashboard.driver.modbus.ModbusException
import ch.amslersolar.dashboard.driver.modbus.RegisterBlock
import ch.amslersolar.dashboard.driver.sunspec.RegisterReader
import ch.amslersolar.dashboard.model.DeviceProblem
import ch.amslersolar.dashboard.model.DeviceProblem.Kind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Against register images built from the VPP V2.01 tables. Like the inverter, the fakes answer exception 2 for
 * undefined registers and for reads crossing the spec's register groups.
 */
class GrowattVppDriverTest {
    /** One register space (holding or input) with group boundaries. */
    private class Space(private val groups: List<IntRange>) : RegisterReader {
        val regs = HashMap<Int, Int>()
        val reads = mutableListOf<Pair<Int, Int>>()

        fun zero(start: Int, count: Int) { for (a in start until start + count) regs[a] = 0 }
        fun u32(a: Int, v: Long) { regs[a] = (v ushr 16 and 0xFFFF).toInt(); regs[a + 1] = (v and 0xFFFF).toInt() }
        fun s32(a: Int, v: Int) = u32(a, v.toLong() and 0xFFFFFFFFL)
        fun s16(a: Int, v: Int) { regs[a] = v and 0xFFFF }
        fun text(a: Int, s: String, n: Int) {
            val b = s.toByteArray().copyOf(2 * n)
            repeat(n) { regs[a + it] = ((b[2 * it].toInt() and 0xFF) shl 8) or (b[2 * it + 1].toInt() and 0xFF) }
        }

        override suspend fun read(unitId: Int, register: Int, count: Int): RegisterBlock {
            reads += register to count
            val range = register until register + count
            if (range.any { it !in regs }) throw ModbusException(2)
            if (groups.none { register in it && range.last in it }) throw ModbusException(2) // crossing groups
            val buf = ByteBuffer.allocate(count * 2)
            repeat(count) { buf.putShort(regs.getValue(register + it).toShort()) }
            return RegisterBlock(register, buf.array())
        }
    }

    private class FakeGrowatt(
        dtc: Int = 3601,
        serial: String = "XYZ1A2B3C4",
        batteries: Int = 1,
        batteryRaw: List<Int> = listOf(-25_000, 0, 0, 0), // 0.1 W, > 0 charging (spec)
        meterRaw: Int = 12_000,                            // 0.1 W, > 0 import (spec)
        acRaw: Int = 30_000,                               // 0.1 W, > 0 export (spec)
        threePhase: Boolean = true,
        versions: Boolean = true,
    ) {
        val holding = Space(listOf(30000..30099, 30100..30149, 30150..30299, 30300..30499))
        val input = Space(listOf(31000..31009, 31010..31099, 31100..31199, 31200..31299, 31300..31399, 31400..31499, 31500..31599))

        init {
            with(holding) {
                zero(30000, 60)
                if (versions) zero(30060, 24)
                zero(30099, 1); zero(30100, 50)
                regs[30000] = dtc; text(30001, serial, 15)
                u32(30016, 100_000); u32(30018, 110_000); u32(30024, 110_000); u32(30028, 150_000); regs[30030] = 1
                if (versions) { text(30060, "TLAA", 2); regs[30062] = 1; regs[30063] = 2; regs[30064] = 3 }
                regs[30099] = 201; regs[30102] = 11; regs[30112] = 1
            }
            with(input) {
                zero(31000, 10); zero(31010, 90); zero(31100, 100)
                regs[31000] = if (batteries > 0) 6 else 5; regs[31001] = 2; regs[31002] = 0
                regs[31010] = 3805; regs[31011] = 82; regs[31012] = 3790; regs[31013] = 80
                s32(31058, 61_520)
                s32(31100, acRaw); s32(31102, -150); regs[31105] = 5001
                regs[31106] = if (threePhase) 4010 else 2312
                regs[31107] = if (threePhase) 4005 else 0; regs[31108] = if (threePhase) 3998 else 0
                regs[31109] = 43; regs[31110] = if (threePhase) 44 else 0; regs[31111] = if (threePhase) 42 else 0
                s32(31112, meterRaw); s16(31114, 452)
                u32(31118, 34); u32(31120, 12_345); u32(31122, 87); u32(31124, 54_321)
                for (k in 0 until batteries) {
                    val b = 31200 + 100 * k
                    zero(b, 100)
                    s32(b, batteryRaw[k]); u32(b + 2, 52); u32(b + 6, 11); regs[b + 14] = 5120 + k
                    s32(b + 15, batteryRaw[k] / 512); regs[b + 17] = listOf(60, 80, 40, 50)[k]; regs[b + 18] = 99
                    u32(b + 19, listOf(100L, 100L, 200L, 100L)[k]); s16(b + 23, 251)
                }
            }
        }

        fun driver() = GrowattVppDriver(input, holding, 1)
    }

    @Test
    fun identifyFamilyAndSerial() = runBlocking {
        val info = FakeGrowatt(dtc = 5100, serial = "QWE4CK5003").driver().identify()
        assertEquals("Growatt MIN 2500-6000TL-XH/XH(P), 10 kW", info.model)
        assertEquals("QWE4CK5003", info.serial)
        assertEquals("Growatt WIT 100KTL3-H, 10 kW", FakeGrowatt(dtc = 5601).driver().identify().model)
    }

    @Test
    fun serialWithOneCharacterPerRegister() = runBlocking {
        val f = FakeGrowatt()
        "AB12345678".forEachIndexed { i, c -> f.holding.regs[30001 + i] = c.code }
        for (i in 10 until 15) f.holding.regs[30001 + i] = 0
        assertEquals("AB12345678", f.driver().identify().serial)
    }

    @Test
    fun hybridChargingAndImporting() = runBlocking {
        val r = FakeGrowatt(batteryRaw = listOf(25_000, 0, 0, 0), meterRaw = 12_000, acRaw = -5_000).driver().read()
        assertEquals(6152, r.pvPowerW)
        assertEquals(listOf(Triple(1, 380.5, 8.2), Triple(2, 379.0, 8.0)), r.pvStrings.map { Triple(it.index, it.voltageV, it.currentA) })
        val bat = r.battery!!
        assertEquals(-2500, bat.powerW) // charging → negative (ours)
        assertEquals("Charging", bat.mode)
        assertEquals(60, bat.socPercent)
        assertEquals(512.0, bat.voltageV!!, 1e-9)
        assertEquals(-1200, r.gridPowerW) // meter +1200 W import → negative (ours)
        assertEquals(700, r.loadPowerW) // −500 W AC + 1200 W import
        assertEquals("Battery online, PV online/offline", r.status)
        assertEquals(45.2, r.temperatureC!!, 1e-9)
        assertEquals(8.7, r.today.exportKwh!!, 1e-9)
        assertEquals(3.4, r.today.importKwh!!, 1e-9)
        assertNull(r.today.pvKwh)
        assertNull(r.lifetimePvKwh)
        assertTrue(r.phases.isEmpty()) // three-phase: line voltages only, in the details
        val ac = r.details.first { it.title.startsWith("AC output") }.rows.toMap()
        assertEquals("400.5 V", ac["Line voltage L2-L3"])
        assertTrue(r.details.any { it.title.startsWith("Battery 1") })
    }

    @Test
    fun dischargingAndExporting() = runBlocking {
        val r = FakeGrowatt(batteryRaw = listOf(-18_000, 0, 0, 0), meterRaw = -9_000, acRaw = 40_000).driver().read()
        assertEquals(1800, r.battery!!.powerW)
        assertEquals("Discharging", r.battery!!.mode)
        assertEquals(900, r.gridPowerW)
        assertEquals(3100, r.loadPowerW)
    }

    @Test
    fun pvOnlyMinXWithoutBattery() = runBlocking {
        val f = FakeGrowatt(dtc = 5200, batteries = 0, meterRaw = -20_000, acRaw = 45_000, threePhase = false)
        val d = f.driver()
        assertEquals("Growatt MIC/MIN 2500-6000TL-X/X2, 10 kW", d.identify().model)
        val r = d.read()
        assertNull(r.battery)
        assertEquals(2000, r.gridPowerW)
        assertEquals(2500, r.loadPowerW)
        assertEquals("PV online, battery offline", r.status)
        assertEquals(1, r.phases.size)
        val l1 = r.phases[0]
        assertEquals("L1", l1.name); assertEquals(231.2, l1.voltageV, 1e-9); assertEquals(4.3, l1.currentA!!, 1e-9)
        assertEquals(50.01, l1.frequencyHz, 1e-9)
        assertTrue(f.input.reads.none { it.first >= 31200 }) // spec: no battery registers on MIC/MIN-X
    }

    @Test
    fun multipleBatteriesAreSummed() = runBlocking {
        val r = FakeGrowatt(batteries = 3, batteryRaw = listOf(10_000, 20_000, -5_000, 0)).driver().read()
        val bat = r.battery!!
        assertEquals(-2500, bat.powerW) // +1000 +2000 −500 W charging
        assertEquals(55, bat.socPercent) // (60·100 + 80·100 + 40·200) / 400
        assertEquals(3, r.details.count { it.title.startsWith("Battery ") })
    }

    @Test
    fun missingOptionalBlocksAreSkippedAndNotAskedAgain() = runBlocking {
        val f = FakeGrowatt(batteries = 1, versions = false)
        val d = f.driver()
        d.read()
        d.read()
        assertEquals(1, f.input.reads.count { it.first == 31300 })
        assertEquals(1, f.holding.reads.count { it.first == 30060 })
        val r = d.read()
        assertNotNull(r.battery)
        val inv = r.details.first { it.title.startsWith("Inverter") }.rows.toMap()
        assertNull(inv["Inverter firmware"])
        assertEquals("V2.01", inv["VPP protocol version"])
    }

    @Test
    fun firmwareAndStatusDetails() = runBlocking {
        val r = FakeGrowatt().driver().read()
        val inv = r.details.first { it.title.startsWith("Inverter") }.rows.toMap()
        assertEquals("TLAA 1.2.3", inv["Inverter firmware"])
        assertEquals("SPH 4000-10000TL3 BH-UP", inv["Model family"])
        assertEquals("SA_EN50438 (11)", inv["Country / grid code"])
        val st = r.details.first { it.title.startsWith("Status") }.rows.toMap()
        assertEquals("Load first (0)", st["Priority"])
    }

    @Test
    fun problemsAreEmptyWhenAllIsFine() = runBlocking {
        assertEquals(emptyList<DeviceProblem>(), FakeGrowatt().driver().read().problems)
    }

    @Test
    fun faultAlarmBatteryFaultAndOffGridAreReported() = runBlocking {
        val f = FakeGrowatt()
        with(f.input.regs) { this[31000] = 3; this[31005] = 102; this[31006] = 3; this[31007] = 205; this[31008] = 1; this[31001] = 4 }
        assertEquals(
            listOf(
                DeviceProblem(Kind.FAULT, "Fault code 102/3"),
                DeviceProblem(Kind.WARNING, "Alarm code 205/1"),
                DeviceProblem(Kind.FAULT, "Battery fault (battery state 4)"),
            ),
            f.driver().read().problems,
        )
        val offGrid = FakeGrowatt().apply { input.regs[31000] = 7 }
        assertEquals(listOf(DeviceProblem(Kind.BACKUP_MODE, "Off-grid operation (state 7)")), offGrid.driver().read().problems)
        val faultNoCode = FakeGrowatt().apply { input.regs[31000] = 3 }
        assertEquals(listOf(DeviceProblem(Kind.FAULT, "Fault (state 3)")), faultNoCode.driver().read().problems)
    }

    @Test
    fun allRequestsStayWithinOneGroupAndLimit() = runBlocking {
        val f = FakeGrowatt(batteries = 4, batteryRaw = listOf(1, 2, 3, 4))
        f.driver().read() // the fakes throw on group crossing; reaching here means none crossed
        assertTrue((f.input.reads + f.holding.reads).all { it.second in 1..125 })
    }

    @Test
    fun nonGrowattIsRejected() = runBlocking {
        val sunspec = Space(listOf(0..65535)).apply { zero(40000, 70) }
        expectIo { GrowattVppDriver(sunspec, sunspec, 1).identify() } // 30000 undefined → exception 2
        val garbage = Space(listOf(30000..30099)).apply { zero(30000, 100); regs[30000] = 1234 }
        expectIo { GrowattVppDriver(garbage, garbage, 1).identify() } // unknown DTC, no serial
    }

    private suspend fun expectIo(block: suspend () -> Unit) {
        try {
            block(); fail("expected IOException")
        } catch (e: IOException) {
            // expected
        }
    }
}
