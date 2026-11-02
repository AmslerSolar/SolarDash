// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.driver.spec

import ch.amslersolar.solardash.driver.DeviceDriver
import ch.amslersolar.solardash.driver.modbus.ModbusException
import ch.amslersolar.solardash.driver.modbus.RegisterBlock
import ch.amslersolar.solardash.driver.sunspec.FakeRegisterImage
import ch.amslersolar.solardash.driver.sunspec.RegisterReader
import ch.amslersolar.solardash.model.DeviceReading
import ch.amslersolar.solardash.model.DeviceType
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KOSTAL PIKO CI, Schneider Conext CL-60 and Conext Gateway, CPS 403X, Power-One TRIO, Ingeteam INGECON SUN and
 * Riello central inverters: the ModbusSpec drivers must give the essentials of the hand-written drivers on the
 * register images of those drivers' tests (problem kinds as a multiset; the texts may differ). The hand-written
 * drivers are gone; what they read was recorded in `src/test/resources/golden/Inverters2GoldenTest/`, one entry per
 * comparison, numbered in the order the test makes them.
 */
class Inverters2GoldenTest {
    private val library = SpecLibrary { File("src/main/assets/modbusspec/$it").readText() }

    private fun spec(id: String, tables: SpecTables, unit: Int) =
        SpecDriver(library.spec(id), library.vocabulary, tables, unit, SpecDevices.hooks(id), requestGapMs = 0)

    private fun spec(id: String, reader: RegisterReader, unit: Int) = spec(id, SpecTables(holding = reader, input = reader), unit)

    private fun essentials(r: DeviceReading) = listOf(
        "pv" to r.pvPowerW, "grid" to r.gridPowerW, "load" to r.loadPowerW,
        "battery" to r.battery?.let { listOf(it.powerW, it.socPercent) },
        "phases" to r.phases.map { listOf(it.name, "%.2f".format(java.util.Locale.ROOT, it.voltageV), it.currentA?.let { a -> "%.2f".format(java.util.Locale.ROOT, a) }, it.powerW) },
        "lifetimePv" to r.lifetimePvKwh?.let { "%.3f".format(java.util.Locale.ROOT, it) },
        "todayPv" to r.today.pvKwh?.let { "%.3f".format(java.util.Locale.ROOT, it) },
        "problemKinds" to r.problems.map { it.kind }.sorted(),
    )

    /**
     * The device type's drivers as [SpecDevices.create] makes them (spec ids × address offsets, the first whose
     * identify checks pass), on [tables].
     */
    private fun typeDriver(type: DeviceType, unit: Int, tables: (Int) -> SpecTables): DeviceDriver =
        ChoosingSpecDriver(SpecDevices.SPEC_IDS.getValue(type).flatMap { id ->
            (SpecDevices.ADDRESS_OFFSETS[type] ?: listOf(0)).map { off ->
                SpecDriver(library.spec(id), library.vocabulary, tables(off), unit, SpecDevices.hooks(id), requestGapMs = 0, sleepRetryMs = 0)
            }
        })

    private fun typeDriver(type: DeviceType, unit: Int, holding: RegisterReader, input: RegisterReader = holding) =
        typeDriver(type, unit) { SpecTables(holding, input, it) }

    @get:org.junit.Rule val testName = org.junit.rules.TestName()
    private val golden by lazy { InvertersGoldenRecords("Inverters2GoldenTest", testName.methodName) }
    @org.junit.After fun finishGolden() = golden.finish()
    private var case = 0

    /** The next recorded entry: what the hand-written driver gave at this point. */
    private fun recorded() = golden.fields(golden.take("${++case}"))

    /** [new] against what the hand-written driver read on the same image. */
    private fun same(new: DeviceDriver) = runBlocking {
        val a = recorded()
        val b = new.read()
        assertEquals(a.getValue(""), essentials(b).joinToString("\n"))
        assertEquals(a.getValue("sleeping").toBooleanStrict(), b.status == "Sleeping (no sun)")
        if (a.getValue("problems") != b.problems.map { it.text }.sorted().toString()) {
            println("Problem texts differ: old ${a.getValue("problems")}, spec ${b.problems.map { it.text }}")
        }
    }

    // ---------------------------------------------------------------- KOSTAL PIKO CI (KostalPikoCiDriverTest)

    private fun piko(wordSwapped: Boolean, meterOnLoad: Boolean = false) =
        FakeRegisterImage(unitId = 71, lowWordFirst = wordSwapped).apply {
            text(768, 32, "PIKO CI"); text(800, 32, "50"); text(14, 8, "7654321X"); text(535, 16, "KOSTAL")
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

    private fun pikoSame(build: () -> FakeRegisterImage) = same(spec("kostal-piko-ci", build(), 71))

    @Test
    fun pikoCiBothWordOrders() {
        for (swapped in listOf(true, false)) pikoSame { piko(swapped) }
    }

    @Test
    fun pikoCiMeterOnTheLoadSideAndNoMeter() {
        pikoSame { piko(true, meterOnLoad = true).apply { f32(252, 2_600.0) } }
        pikoSame { piko(true).apply { f32(220, 0.0) } }
    }

    @Test
    fun pikoCiWithoutTotalDcPowerSumsTheInputs() {
        pikoSame { piko(true).apply { regs.remove(100); regs.remove(101) } }
    }

    @Test
    fun pikoCiErrorBits() {
        pikoSame {
            piko(true).apply {
                u16(4126, (1 shl 3) or (1 shl 2)); u16(4127, (1 shl 2) or (1 shl 6) or (1 shl 11)); u16(4128, 1 shl 6)
            }
        }
    }

    @Test
    fun pikoCiIdentify() = runBlocking {
        val d = spec("kostal-piko-ci", piko(true), 71)
        assertEquals("7654321X", d.identify().serial)
        val other = runCatching { spec("kostal-piko-ci", piko(true).apply { text(768, 32, "PLENTICORE plus") }, 71).identify() }.exceptionOrNull()
        assertTrue(other is IOException)
    }

    // ---------------------------------------------------------------- Conext CL-60 (ConextCl60DriverTest)

    private fun cl60(modelCode: Int = 0x1062, wiring: Int = 1) = FakeRegisterImage(unitId = 1, lowWordFirst = true).apply {
        text(0x0A15, 15, "LCD_CL-60E_V11_V03_Q_M"); text(0x0A24, 15, "MDSP_CL-60E_V11_V1_B")
        u32(0x1355, 3); u32(0x1357, 30)
        text(0x137D, 10, "A1612160390")
        u16(0x1387, modelCode); u16(0x1388, 600); u16(0x1389, wiring); u16(0x13B8, 300)
        u16(0x138A, 2_345); u32(0x138B, 123_456); u32(0x138D, 5_000); s16(0x138F, 452)
        u16(0x1392, 6_500); u16(0x1393, 1_085); u16(0x1395, 0)
        u32(0x1398, 70_500)
        u16(0x139A, 2_301); u16(0x139B, 2_305); u16(0x139C, 2_299)
        u16(0x139D, 1_002); u16(0x139E, 1_001); u16(0x139F, 1_003)
        u32(0x13A4, 69_800); u32(0x13A6, 68_912); u32(0x13A8, -1_200L and 0xFFFFFFFFL); s16(0x13AA, -998); u16(0x13AB, 500)
        u16(0x13AD, 0x0000); u16(0x13B4, 0); u16(0x13CE, 1_500); u32(0x13D8, 1L shl 17); u16(0x13F8, 600)
        u32(0x1407, 45_678); s16(0x1419, -12); u16(0x141A, 7_100)
        for (i in 0 until 14) u16(0x1B64 + i, 850 + i)
    }

    private fun cl60Same(build: () -> FakeRegisterImage) = same(spec("schneider-conext-cl60", build(), 1))

    @Test
    fun conextCl60RunningAndWiring() {
        cl60Same { cl60() }
        cl60Same { cl60(wiring = 2) }
        cl60Same { cl60(modelCode = 0x1061) }
    }

    @Test
    fun conextCl60FaultAndAlarmCodes() {
        for ((state, code) in listOf(0x5500 to 10, 0x5500 to 39, 0x5500 to 999, 0x5500 to 0, 0x9100 to 70, 0x8100 to 0, 0x5500 to 75, 0x9100 to 75, 0x1400 to 0, 0x2500 to 0, 0x1400 to 10)) {
            cl60Same { cl60().apply { u16(0x13AD, state); u16(0x13B4, code) } }
        }
    }

    @Test
    fun conextCl60Identify() = runBlocking {
        assertEquals("A1612160390", spec("schneider-conext-cl60", cl60(), 1).identify().serial)
        assertTrue(runCatching { spec("schneider-conext-cl60", cl60(modelCode = 0x2001), 1).identify() }.exceptionOrNull() is IOException)
    }

    // ---------------------------------------------------------------- Conext Gateway (ConextGatewayDriverTest)

    private fun gateway(address: Int = 1) = FakeRegisterImage(unitId = 1, rejectAcross = setOf(0x0050, 0x03F8)).apply {
        text(0x0000, 8, "Conext Gateway"); text(0x000A, 8, "865-0329"); text(0x001E, 10, "V1.07 BN 6"); u16(0x0028, address)
        text(0x002B, 8, "00502ABCD")
        u16(0x0040, (1 shl 6) or (1 shl 7)); u16(0x0041, 0); u16(0x0042, 0); u16(0x0043, 1)
        for (a in 0x0044..0x006D) u16(a, 0)
        u32(0x0044, 4_150); u32(0x0046, 1_480); u32(0x004A, 0)
        u32(0x004C, 240_100); u32(0x004E, 6_001)
        u32(0x0052, 0); u32(0x0058, 1_100)
        u32(0x0066, 1_600)
        u32(0x009C, 52_480); u32(0x009E, 29_820)
        u32(0x0158, -1_500L and 0xFFFFFFFFL)
        u32(0x00A4, 1_200); u32(0x00BC, 6_400); u32(0x00D4, 2_100); u32(0x00E4, 12_345_678); u32(0x00EC, 9_870)
        u32(0x00FC, 23_456_789); u32(0x0104, 11_200); u32(0x0114, 34_567_890); u32(0x0134, 18_000); u32(0x0144, 45_000_000)
        u32(0x03A8, 120_200); u32(0x03AA, 4_500); u32(0x03AC, 120_100); u32(0x03AE, 4_600); u32(0x03B0, 0); u32(0x03B2, 0)
        u32(0x03C6, -1_500L and 0xFFFFFFFFL); u32(0x03C8, 81)
        u32(0x03F8, 1_100); u32(0x03FA, 4_200); u32(0x03FC, 0); u32(0x03FE, 1_500)
        u32(0x0414, 18_250); u32(0x041C, 45_100_000); u32(0x041E, 97)
    }

    private fun gatewaySame(build: () -> FakeRegisterImage) = same(spec("schneider-conext-gateway", build(), 1))

    private fun FakeRegisterImage.drop(vararg a: Int) = apply { a.forEach { regs.remove(it); regs.remove(it + 1) } }

    @Test
    fun conextGatewaySystemTotals() {
        gatewaySame { gateway() }
        gatewaySame { gateway().apply { u32(0x0052, 2_300); u32(0x0058, 0) } } // buying
    }

    @Test
    fun conextGatewayFallbacksAndMissingRegisters() {
        gatewaySame { gateway().drop(0x03C8, 0x03FC, 0x03FE, 0x0046, 0x004A) } // no battery
        gatewaySame { gateway().drop(0x03FA, 0x03FC, 0x03FE, 0x0414, 0x041C) } // older registers only
        gatewaySame { gateway().drop(0x0058) } // only the buying register
        gatewaySame { gateway().apply { u32(0x0066, -5L and 0xFFFFFFFFL); u32(0x03C8, 255) } }
    }

    @Test
    fun conextGatewayFaultAndWarningCounts() {
        gatewaySame { gateway().apply { u16(0x0043, 0) } }
        gatewaySame { gateway().apply { u16(0x0041, 2) } }
    }

    @Test
    fun conextGatewayIdentify() = runBlocking {
        assertEquals("00502ABCD", spec("schneider-conext-gateway", gateway(), 1).identify().serial)
        assertTrue(runCatching { spec("schneider-conext-gateway", gateway(address = 10), 1).identify() }.exceptionOrNull() is IOException)
    }

    // ---------------------------------------------------------------- CPS 403X (CpsDriverTest)

    private class FakeCps : RegisterReader {
        val regs = HashMap<Int, Int>()
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
            val data = ByteArray(count * 2)
            for (i in 0 until count) {
                val v = regs[register + i] ?: throw ModbusException(2)
                data[2 * i] = (v shr 8).toByte(); data[2 * i + 1] = v.toByte()
            }
            return RegisterBlock(register, data)
        }
    }

    private fun cps(device: Int, model: String, last: Int = 0x0048) = FakeCps().apply {
        u16(0x0000, device); u16(0x0003, 80); u16(0x0004, 315); u16(0x0005, 0x1203)
        u16(0x0006, 0x0001); u16(0x0007, 0x0100); u16(0x0008, 0x9111); u16(0x0009, 0x4001)
        text(0x000A, 10, model); u16(0x0014, 100); u16(0x0015, 0x1000)
        fill(0x0001..last)
    }

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

    private fun cpsSame(build: () -> FakeCps) = same(spec("chint-cps-403x", build(), 1))

    @Test
    fun cpsFamilies() {
        cpsSame { sixty() }
        cpsSame {
            cps(0x4036, "SC25KTL-DO/US-480", last = 0x0042).apply {
                u16(0x001D, 240); u16(0x001F, 4800)
                u16(0x0025, 6000); u16(0x0026, 150); u16(0x0027, 5000); u16(0x0028, 200)
                u16(0x002E, 612); u16(0x002F, 0x0800)
            }
        }
        cpsSame {
            cps(0x4035, "SCA125KTL-DO/US-600").apply {
                u16(0x001D, 1210); u16(0x0025, 9000); u16(0x0026, 1380); u16(0x0027, 7000); u16(0x0028, 500)
                u16(0x0045, 1235); u16(0x002F, 0x0000)
            }
        }
        cpsSame { cps(0x403F, "SCH350KTL-DO/US-800").apply { u16(0x0025, 12000); u16(0x0026, 9876); u16(0x0045, 0xFFFF); u16(0x001D, 1100) } }
        cpsSame { cps(0x403A, "SC25KTL-DO/US-208").apply { u16(0x0045, 200) } }
    }

    @Test
    fun cpsMapWithGaps() {
        cpsSame {
            FakeCps().apply {
                u16(0x0000, 0x4048); fill(0x0003..0x0024); fill(0x002B..0x002D); fill(0x002F..0x003B); u16(0x0041, 0); fill(0x0043..0x0048)
                text(0x000A, 10, "SCH250-T-US-600")
                u16(0x0022, -15)
                u16(0x001F, 6000); u16(0x0045, 2480); u16(0x001D, 2400); u16(0x002F, 0x0400); u16(0x0019, 98)
            }
        }
    }

    @Test
    fun cpsNotImplementedValues() {
        cpsSame {
            sixty().apply {
                u32(0x0016, 0xFFFFFFFFL); u16(0x0018, 0xFFFF); u16(0x002C, 0x8000); u16(0x0045, 0xFFFF)
                u16(0x0026, 0x8000); u16(0x002F, 0x0600)
                for (a in 0x0006..0x0009) u16(a, 0xFFFF)
            }
        }
    }

    @Test
    fun cpsEventBitsAndRawFaultWords() {
        cpsSame { sixty().apply { u16(0x0035, 0) } } // all OK
        cpsSame { sixty().apply { u16(0x0035, 0); u16(0x002F, 0x8000); u16(0x0036, 1 shl 11); u16(0x0037, 1 shl 3) } }
        cpsSame { sixty().apply { u16(0x0035, 0); u16(0x002F, 0x8000) } } // mode only
        cpsSame { sixty().apply { u16(0x002F, 0x8000) } } // mode with a warning
        cpsSame { sixty().apply { u16(0x0036, (1 shl 1) or (1 shl 5)); u16(0x0035, 1 shl 14) } } // bits outside the event table
        cpsSame { cps(0x4035, "SCA125KTL-DO/US-600").apply { u16(0x002F, 0x8000); u16(0x0036, 0x0400) } }
        cpsSame { cps(0x4035, "SCA125KTL-DO/US-600").apply { u16(0x002F, 0x1000) } }
    }

    // ---------------------------------------------------------------- Power-One TRIO (PowerOneTrioDriverTest)

    /** Input registers by register number (1-based as in the specs), answered at register + [offset]. */
    private class FakeTrio(private val offset: Int = -1, private val swapped: Boolean = false) : RegisterReader {
        val regs = HashMap<Int, Int>()
        var asleep = false
        var gatewayNoAnswer = false

        fun u16(r: Int, v: Int) = apply { regs[r] = v and 0xFFFF }
        fun u32(r: Int, v: Long) = apply { u16(r, (v shr 16).toInt()); u16(r + 1, v.toInt()) }
        fun f32(r: Int, v: Double, swap: Boolean = swapped) = apply {
            val bits = v.toFloat().toRawBits()
            val hi = bits ushr 16; val lo = bits and 0xFFFF
            if (swap) { u16(r, lo); u16(r + 1, hi) } else { u16(r, hi); u16(r + 1, lo) }
        }
        fun chars(r: Int, s: String) = apply { s.forEachIndexed { i, c -> u16(r + i, c.code) } }

        override suspend fun read(unitId: Int, register: Int, count: Int): RegisterBlock {
            assertEquals(2, unitId)
            if (asleep) throw if (gatewayNoAnswer) ModbusException(0x0B) else SocketTimeoutException("Read timed out")
            val data = ByteArray(count * 2)
            for (i in 0 until count) {
                val v = regs[register + i - offset] ?: if (regs.containsKey(register - offset)) 0 else throw ModbusException(2)
                data[2 * i] = (v shr 8).toByte(); data[2 * i + 1] = v.toByte()
            }
            return RegisterBlock(register, data)
        }
    }

    /** TRIO-20.0 in the GT map: 14.2 kW AC from two inputs. */
    private fun trio20(offset: Int = -1, swapped: Boolean = false) = FakeTrio(offset, swapped).apply {
        u16(100, 4370); u16(101, 72); u16(102, 2); u16(103, 2)
        chars(104, "123456"); chars(110, "-3G97-"); u16(116, 'y'.code)
        for (r in 117..131) regs.putIfAbsent(r, 0)
        u16(124, 6); u16(125, 2); u16(126, 2); u16(127, 2); u16(128, 0)
        f32(132, 31_400.0); f32(134, 98_765.4); f32(136, 0.0); f32(138, 140.0); f32(140, 600.0); f32(142, 7_000.0)
        f32(144, 231.0); f32(146, 20.5); f32(148, 14_200.0); f32(150, 50.01)
        f32(152, 7_400.0); f32(154, 620.0); f32(156, 11.9); f32(158, 7_200.0); f32(160, 610.0); f32(162, 11.8)
        f32(164, 48.0); f32(166, 52.0); f32(168, 12.0); f32(170, 0.0); f32(172, 1.0); u16(174, 1); u16(175, 0)
    }

    private fun trio60(state: Int = 6, alarm: Int = 0, derating: Int = 0, offset: Int = -1) = FakeTrio(offset).apply {
        u16(1000, 261); u16(1001, 132); u16(1002, 1); chars(1003, "-3N63-"); chars(1009, "654321")
        for (r in 1015..1022) regs.putIfAbsent(r, 0)
        u16(1022, 0x2F)
        for (r in 1050..1135) regs.putIfAbsent(r, 0)
        u16(1050, state); u16(1051, alarm); u16(1054, derating)
        u32(1070, 210_500); u32(1072, 1_530_000_000)
        f32(1094, 48_300.0, swap = true); f32(1106, 50.0, swap = true)
        f32(1108, 48_900.0, swap = true); f32(1110, 700.0, swap = true); f32(1112, 69.9, swap = true)
        f32(1122, 55.0, swap = true)
    }

    private fun trio(fake: FakeTrio) = typeDriver(DeviceType.POWER_ONE, 2, fake)

    private fun trioSame(build: () -> FakeTrio) = same(trio(build()))

    @Test
    fun trioGtMapBothNumberingsAndWordOrders() {
        for (offset in listOf(-1, 0)) for (swapped in listOf(false, true)) trioSame { trio20(offset, swapped) }
        trioSame { trio20().apply { f32(148, -3.0) } } // night consumption: solar 0
    }

    @Test
    fun trioGtMapStatesAndAlarms() {
        for (state in 0..255) trioSame { trio20().apply { u16(124, state) } }
        for (state in 0..255) trioSame { trio20().apply { u16(125, state) } }
        for (alarm in (0..255) + 0xFFFF) trioSame { trio20().apply { u16(128, alarm) } }
        trioSame { trio20().apply { u16(124, 9); u16(125, 43); u16(128, 32) } }
    }

    @Test
    fun trioGt03MapBothNumberings() {
        trioSame { trio60() }
        trioSame { trio60(offset = 0) }
    }

    @Test
    fun trioGt03MapStatesAlarmsAndDerating() {
        for (state in 0..255) trioSame { trio60(state = state) }
        for (alarm in (0..255) + 0xFFFF) trioSame { trio60(alarm = alarm) }
        for (bit in 0..15) trioSame { trio60(derating = 1 shl bit) }
        trioSame { trio60(10, 62, 0x21) }
    }

    @Test
    fun trioIdentify() = runBlocking {
        val gt = trio(trio20(offset = 0)).identify()
        assertEquals("123456", gt.serial)
        assertEquals("654321", trio(trio60()).identify().serial)
        assertTrue(runCatching { trio(FakeTrio().apply { u16(100, 1); u16(1000, 7) }).identify() }.exceptionOrNull() is IOException)
    }

    @Test
    fun trioSleepsAfterSunsetOnly() = runBlocking {
        for (gateway in listOf(false, true)) {
            // Evening, 120 W: then no answer is sleeping.
            val b = trio20().apply { f32(148, 120.0); gatewayNoAnswer = gateway }
            val new = trio(b)
            same(new)
            b.asleep = true
            same(new)
            assertEquals("Sleeping (no sun)", new.read().status)
            // Morning: it answers again (the old driver waits a minute before asking; this one is told not to wait).
            b.asleep = false
            assertEquals(120, new.read().pvPowerW)
        }
        // No answer at all, or no answer at 14.2 kW: an error with both.
        for (first in listOf(false, true)) {
            val news = trio20().apply { asleep = !first }
            val new = trio(news)
            if (first) { same(new); news.asleep = true }
            assertTrue(recorded().getValue("throwsIOException").toBooleanStrict())
            assertTrue(runCatching { new.read() }.exceptionOrNull() is IOException)
        }
    }

    // ---------------------------------------------------------------- Riello central (RielloCentralDriverTest)

    /** Registers 1–112 by document number, answered at register + [offset]; [swapped]: LSByte first. */
    private class FakeRiello(private val offset: Int = -1, private val swapped: Boolean = false, newFirmware: Boolean = true) : RegisterReader {
        val regs = HashMap<Int, Int>()
        init {
            for (r in 1..112) regs[r] = 0
            if (!newFirmware) for (r in 42..53) regs.remove(r)
        }
        fun u16(r: Int, v: Int) = apply { regs[r] = v and 0xFFFF }
        fun u32(r: Int, v: Long) = apply { u16(r, (v shr 16).toInt()); u16(r + 1, v.toInt()) }
        fun text(r: Int, n: Int, s: String) = apply {
            val p = s.padEnd(n * 2, ' ')
            for (i in 0 until n) u16(r + i, (p[2 * i].code shl 8) or p[2 * i + 1].code)
        }

        override suspend fun read(unitId: Int, register: Int, count: Int): RegisterBlock {
            assertEquals(1, unitId)
            val data = ByteArray(count * 2)
            for (i in 0 until count) {
                val v = regs[register + i - offset] ?: throw ModbusException(2)
                val (first, second) = if (swapped) v to (v shr 8) else (v shr 8) to v
                data[2 * i] = first.toByte(); data[2 * i + 1] = second.toByte()
            }
            return RegisterBlock(register, data)
        }
    }

    private fun central(offset: Int = -1, swapped: Boolean = false, newFirmware: Boolean = true) = FakeRiello(offset, swapped, newFirmware).apply {
        u16(80, 0x0033); text(81, 3, "100000"); text(84, 2, "4500"); text(86, 3, "01.30")
        text(89, 8, "RS1234567890"); text(97, 8, "SIRIO K100"); text(105, 8, "RIELLO")
        u16(11, 452); u16(12, 6100); u16(13, 0xFFFF); u16(14, 0xFFFF)
        u16(15, 1000); u16(16, 0xFFFF); u16(17, 0xFFFF)
        u16(18, 870); u16(19, 865); u16(20, 868); u16(21, 2301); u16(22, 2298); u16(23, 2305)
        u16(24, 5002); u16(25, 2000); u16(26, 1990); u16(27, 2010)
        u32(31, 12_345_678); u32(33, 23_456); u16(35, 3)
        if (newFirmware) { u16(42, -5); u32(49, 3_456); u16(52, 1_200) }
    }

    private fun riello(fake: FakeRiello) = typeDriver(DeviceType.RIELLO_CENTRAL, 1, fake)

    private fun rielloSame(build: () -> FakeRiello) = same(riello(build()))

    @Test
    fun rielloBothNumberingsAndByteOrders() {
        for (offset in listOf(-1, 0)) for (swapped in listOf(false, true)) rielloSame { central(offset, swapped) }
        rielloSame { central(newFirmware = false) }
    }

    @Test
    fun rielloSinglePhaseAndAcFallback() {
        rielloSame { central().apply { u16(80, 0x0031); u16(12, 0); u16(25, 0) } }
        rielloSame { central().apply { u16(80, 0x0031) } }
        rielloSame { central().apply { u16(12, 0) } } // no PV voltage: AC power
        rielloSame { central().apply { u16(15, 0xFFFF) } } // no PV current: 0 W from the inputs
        rielloSame { central().apply { u16(22, 0) } } // a phase without voltage
    }

    @Test
    fun rielloAlarmBits() {
        rielloSame { central() }
        for (r in listOf(7, 8)) for (bit in 0..15) rielloSame { central().apply { u16(r, 1 shl bit) } }
        rielloSame { central().apply { u16(7, 1 shl 2); u16(8, (1 shl 9) or (1 shl 13)) } }
    }

    @Test
    fun rielloIdentify() = runBlocking {
        assertEquals("RS1234567890", riello(central(offset = 0, swapped = true)).identify().serial)
        assertTrue(runCatching { riello(FakeRiello().apply { u16(80, 0x1234); u16(79, 7) }).identify() }.exceptionOrNull() is IOException)
    }

    // ---------------------------------------------------------------- Ingeteam (IngeteamDriverTest)

    /** One register table; reads touching an undefined register fail like a strict device. */
    private class Regs : RegisterReader {
        val regs = HashMap<Int, Int>()
        fun u16(a: Int, v: Int) = apply { regs[a] = v and 0xFFFF }
        fun u32(a: Int, v: Long) = apply { u16(a, (v shr 16).toInt()); u16(a + 1, v.toInt()) }
        fun fill(range: IntRange, v: Int = 0) = apply { range.forEach { regs.putIfAbsent(it, v) } }

        override suspend fun read(unitId: Int, register: Int, count: Int): RegisterBlock {
            assertEquals(1, unitId)
            assertTrue(count in 1..124)
            val data = ByteArray(count * 2)
            for (i in 0 until count) {
                val v = regs[register + i] ?: throw ModbusException(2)
                data[2 * i] = (v shr 8).toByte(); data[2 * i + 1] = v.toByte()
            }
            return RegisterBlock(register, data)
        }
    }

    private fun play3Current(reserved: Boolean = true) = Regs().apply {
        if (reserved) { fill(0x1001..0x1041); fill(0x1050..0x1093) } else {
            fill(0x1001..0x1024); fill(0x1027..0x1028); fill(0x1036..0x1041); fill(0x1050..0x1073); fill(0x1080..0x1093)
        }
        for (i in 0..2) {
            val b = 0x1001 + 5 * i
            u16(b, 2300 + 10 * i); u16(b + 1, 1400); u32(b + 2, 32_000); u16(b + 4, 5000 - i)
        }
        u16(0x1010, 6205); u16(0x1011, 850); u32(0x1012, 52_700)
        u16(0x1014, 5980); u16(0x1015, 760); u32(0x1016, 45_300)
        u16(0x101C, 41); u16(0x101D, 3); u16(0x101E, 0x0004)
        u32(0x1021, 123_456); u32(0x1023, 9_876); u32(0x1027, 31_250)
        u16(0x1036, 0b1001)
        u32(0x1037, 96_000); u32(0x1039, (-1_500).toLong()); u16(0x103D, 998)
        u16(0x1050, 6200); u16(0x1051, 425)
    }

    private fun legacy3Play() = Regs().apply {
        fill(0..66, 0xFFFF)
        listOf(2026, 10, 21, 13, 5, 30).forEachIndexed { i, v -> u16(i, v) }
        u32(6, 54_321); u16(26, 2)
        u16(29, 2100); u16(30, 2110); u16(31, 2090)
        u16(32, 2301); u16(33, 2310); u16(34, 2295); u16(35, 5001)
        u16(36, 1460); u16(37, 1450); u16(38, 20); u16(39, 995)
        u16(40, 1210); u16(41, 1190); u16(42, 640); u16(43, 630)
        u16(44, 1500); u16(45, 770); u16(46, 730)
        u16(47, 1500); u16(49, 100); u16(50, 0); u16(51, 0)
        for (i in 52..61) u16(i, 0)
        u16(52, 85)
        u32(63, 4_210)
        u16(66, 0)
    }

    private fun onePlay(hf: Boolean) = Regs().apply {
        fill(0..66, 0xFFFF)
        listOf(2025, 6, 1, 12, 0, 0).forEachIndexed { i, v -> u16(i, v) }
        u32(6, 9_999); u16(26, 2); u16(29, 2_000); u16(30, 2_302); u16(32, 4_998); u16(34, 4_500)
        u16(37, 1_250); u16(39, 380); u16(41, 4_700); u32(49, 2_345)
        if (!hf) { u16(38, 1_100); u16(40, 360); u16(42, 2_480); u16(43, 2_220) }
    }

    private fun legacy100Tl() = Regs().apply {
        fill(0..66, 0xFFFF)
        listOf(2024, 3, 3, 9, 30, 0).forEachIndexed { i, v -> u16(i, v) }
        u32(12, 15_000); u16(20, 2)
        u16(21, 14_000); u16(22, 14_100); u16(23, 13_900); u16(24, 2_310); u16(25, 2_305); u16(26, 2_300); u16(27, 5_000)
        u16(29, 9_800); u16(32, 16_000); u16(33, 700); u16(34, 10_100)
        u16(38, (52 shl 8) or 51)
        u32(57, 1_000_000)
    }

    private fun ingeteamSame(holding: () -> Regs, input: () -> Regs = { Regs() }) =
        same(typeDriver(DeviceType.INGETEAM, 1, holding(), input()))

    @Test
    fun ingeteamCurrentMap() {
        ingeteamSame({ play3Current() })
        ingeteamSame({ play3Current(reserved = false) })
        ingeteamSame({
            play3Current().apply {
                for (i in 1..2) { val b = 0x1001 + 5 * i; u16(b, 0); u16(b + 1, 0); u32(b + 2, 0); u16(b + 4, 0) }
                u16(0x1014, 0); u16(0x1015, 0); u32(0x1016, 0)
                u16(0x101C, 0xFFFF)
                u32(0x1027, 0xFFFFFFFFL)
            }
        })
        ingeteamSame({ play3Current().apply { u32(0x1012, 0xFFFFFFFFL) } }) // MPPT 1 power missing: the others
        ingeteamSame({ play3Current().apply { u32(0x1012, 0xFFFFFFFFL); u32(0x1016, 0xFFFFFFFFL) } }) // none: AC power
        for (bit in 0..15) ingeteamSame({ play3Current().apply { u16(0x1036, 1 shl bit) } })
    }

    @Test
    fun ingeteamLegacyMaps() {
        ingeteamSame({ Regs() }, { legacy3Play() })
        ingeteamSame({ Regs() }, { legacy3Play().apply { u16(44, 0xFFFF) } }) // ABI1004: no PV total
        ingeteamSame({ Regs() }, { onePlay(true) })
        ingeteamSame({ Regs() }, { onePlay(false) })
        ingeteamSame({ Regs() }, { legacy100Tl() })
    }

    @Test
    fun ingeteamLegacyAlarmsWarningsAndDerating() {
        ingeteamSame({ Regs() }, { legacy3Play().apply { u16(18, 0x0004); u16(19, 0); u16(22, 0x0010) } })
        ingeteamSame({ Regs() }, { legacy3Play().apply { for (a in 18..22) u16(a, 0) } }) // all OK
        for (reason in 0..15) {
            ingeteamSame({ Regs() }, { legacy3Play().apply { u16(50, reason) } })
            ingeteamSame({ Regs() }, { onePlay(true).apply { u16(47, reason) } })
        }
        ingeteamSame({ Regs() }, { onePlay(false).apply { u16(18, 1); u16(21, 2); u16(22, 3) } })
        ingeteamSame({ Regs() }, { legacy100Tl().apply { for (a in 14..19) u16(a, 0); u16(15, 0x80); u16(19, 1) } })
    }

    @Test
    fun ingeteamIdentify() = runBlocking {
        val d = typeDriver(DeviceType.INGETEAM, 1, play3Current(), Regs())
        assertEquals("", d.identify().serial)
        val foreign = Regs().apply { fill(0x1001..0x1024, 0x1234) }
        assertTrue(runCatching { typeDriver(DeviceType.INGETEAM, 1, foreign, Regs()).identify() }.exceptionOrNull() is IOException)
        assertTrue(runCatching { typeDriver(DeviceType.INGETEAM, 1, Regs(), Regs().fill(0..66, 7)).identify() }.exceptionOrNull() is IOException)
    }
}
