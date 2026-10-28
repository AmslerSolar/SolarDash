// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.spec

import ch.amslersolar.dashboard.driver.DeviceDriver
import ch.amslersolar.dashboard.driver.modbus.ModbusException
import ch.amslersolar.dashboard.driver.modbus.RegisterBlock
import ch.amslersolar.dashboard.driver.sunspec.RegisterReader
import ch.amslersolar.dashboard.model.DeviceReading
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Inverters (group 1): the ModbusSpec drivers must read what the hand-written drivers read, on the same register
 * images. Problem texts may differ; their kinds must match (as a multiset). The hand-written drivers are gone except
 * KOSTAL PLENTICORE's; what they read was recorded in `src/test/resources/golden/Inverters1GoldenTest/`.
 */
class Inverters1GoldenTest {
    private val library = SpecLibrary { File("src/main/assets/modbusspec/$it").readText() }

    private fun spec(id: String, tables: SpecTables, unit: Int) =
        SpecDriver(library.spec(id), library.vocabulary, tables, unit, SpecDevices.hooks(id), requestGapMs = 0)

    private fun spec(id: String, reader: RegisterReader, unit: Int) = spec(id, SpecTables(holding = reader, input = reader), unit)

    /** What has to match, with powers to the watt and voltages/currents to 0.01. */
    private fun essentials(r: DeviceReading) = listOf(
        "pv" to r.pvPowerW, "grid" to r.gridPowerW, "load" to r.loadPowerW,
        "battery" to r.battery?.let { listOf(it.powerW, it.socPercent) },
        "phases" to r.phases.map { listOf(it.name, "%.2f".format(java.util.Locale.ROOT, it.voltageV), it.currentA?.let { a -> "%.2f".format(java.util.Locale.ROOT, a) }, it.powerW) },
        "lifetimePv" to r.lifetimePvKwh?.let { "%.3f".format(java.util.Locale.ROOT, it) },
        "todayPv" to r.today.pvKwh?.let { "%.3f".format(java.util.Locale.ROOT, it) },
        "problemKinds" to r.problems.map { it.kind }.sorted(),
    )

    /** Problem texts that differ between the drivers (allowed; listed for the report). */
    val textDifferences = linkedSetOf<String>()

    @get:org.junit.Rule val testName = org.junit.rules.TestName()
    private val golden by lazy { InvertersGoldenRecords("Inverters1GoldenTest", testName.methodName) }
    @org.junit.After fun finishGolden() = golden.finish()

    /** [new] against what the hand-written driver read on the same image (recorded under [label]). */
    private fun same(new: DeviceDriver, label: String) = runBlocking {
        val old = golden.fields(golden.take(label))
        val b = new.read()
        assertEquals(label, old.getValue(""), essentials(b).joinToString("\n"))
        val ta = old.getValue("problems")
        val tb = b.problems.map { "${it.kind}: ${it.text}" }.sorted().toString()
        if (ta != tb) {
            textDifferences += "$label old=$ta spec=$tb"
            println("problem texts differ ($label): old=$ta spec=$tb")
        }
    }

    private fun rejects(d: DeviceDriver) = runBlocking {
        try {
            d.identify()
            fail("identify() should have thrown")
        } catch (_: IOException) {
        }
    }

    // ---------------------------------------------------------------- FENECON FEMS (copied from FeneconFemsDriverTest)

    private class FakeFems(private val strict: Boolean = false) : RegisterReader {
        val regs = HashMap<Int, Int>()

        fun u16(a: Int, v: Int) = apply { regs[a] = v and 0xFFFF }
        fun f32(a: Int, v: Double) = apply {
            val bits = v.toFloat().toRawBits()
            regs[a] = (bits ushr 16) and 0xFFFF
            regs[a + 1] = bits and 0xFFFF
        }
        fun f64(a: Int, v: Double) = apply {
            val bits = v.toRawBits()
            for (i in 0 until 4) regs[a + i] = ((bits ushr (48 - 16 * i)) and 0xFFFF).toInt()
        }
        fun text(a: Int, n: Int, s: String) = apply {
            val padded = s.padEnd(n * 2, '\u0000')
            for (i in 0 until n) regs[a + i] = (padded[2 * i].code shl 8) or padded[2 * i + 1].code
        }

        override suspend fun read(unitId: Int, register: Int, count: Int): RegisterBlock {
            val data = ByteArray(count * 2)
            for (i in 0 until count) {
                val v = regs[register + i] ?: if (strict) throw ModbusException(2) else 0
                data[2 * i] = (v shr 8).toByte()
                data[2 * i + 1] = v.toByte()
            }
            return RegisterBlock(register, data)
        }
    }

    private fun fems(strict: Boolean = false) = FakeFems(strict).text(200, 16, "_sum").u16(222, 0).u16(417, 1)
        .f64(351, 1_200_000.0).f64(355, 1_000_000.0).f64(359, 3_000_000.0).f64(363, 4_500_000.0)
        .f64(367, 9_876_500.0).f64(371, 0.0).f64(375, 9_876_500.0).f64(379, 8_000_000.0)
        .f64(383, 1_250_000.0).f64(387, 1_050_000.0)

    private fun chargingExport(strict: Boolean = false) = fems(strict)
        .u16(302, 64).f32(303, 4_000.0).f32(415, -2_000.0).f32(315, -2_500.0)
        .f32(327, 6_000.0).f32(339, 6_000.0).f32(331, 0.0).f32(343, 1_500.0)

    private fun dischargingImport() = fems()
        .u16(302, 35).f32(303, 1_800.0).f32(415, 1_800.0).f32(315, 700.0)
        .f32(327, 0.0).f32(339, 0.0).f32(343, 2_500.0).u16(222, 2)

    private fun noEssNoMeter() = FakeFems().text(200, 16, "_sum").u16(222, 0).u16(417, 0xFFFF)
        .u16(302, 0xFFFF).f32(303, Double.NaN).f32(309, Double.NaN).f32(415, Double.NaN).f32(339, Double.NaN)
        .f32(315, Double.NaN).f32(327, 4_200.0).f32(343, Double.NaN).f64(367, Double.NaN)

    @Test
    fun feneconFems() {
        val cases: List<Pair<String, () -> FakeFems>> = listOf(
            "charging/export" to { chargingExport() },
            "charging/export strict" to { chargingExport(strict = true) },
            "discharging/import, warning" to ::dischargingImport,
            "battery fallback 303 − 339" to { chargingExport().f32(415, Double.NaN) },
            "info state" to { chargingExport().u16(222, 1) },
            "fault + off-grid" to { chargingExport().u16(222, 3).u16(417, 2) },
            "off-grid" to { fems().u16(417, 2).u16(302, 80).f32(415, 900.0).f32(315, 0.0).f32(327, 300.0).f32(343, 1_200.0) },
            "no ESS, no meter (NaN)" to ::noEssNoMeter,
        )
        for ((label, image) in cases) same(spec("fenecon-fems", image(), 1), "FEMS $label")
    }

    @Test
    fun feneconIdentify() = runBlocking {
        spec("fenecon-fems", fems(), 1).identify()
        for (fake in listOf(FakeFems().text(200, 16, "GoodWe ET"), FakeFems(strict = true), FakeFems().text(200, 16, "_sum").u16(222, 42))) {
            rejects(spec("fenecon-fems", fake, 1))
        }
    }

    // ---------------------------------------------------------------- GoodWe ET (image built from the driver and the spec)

    /** Holding registers in [ranges]; a read touching anything else is answered with exception 2. */
    private class Image(private val ranges: List<IntRange>) : RegisterReader {
        val regs = HashMap<Int, Int>()

        fun u16(a: Int, v: Int) = apply { regs[a] = v and 0xFFFF }
        fun s32(a: Int, v: Long) = apply { regs[a] = ((v shr 16) and 0xFFFF).toInt(); regs[a + 1] = (v and 0xFFFF).toInt() }
        fun text(a: Int, n: Int, s: String) = apply {
            val padded = s.padEnd(n * 2, '\u0000')
            for (i in 0 until n) regs[a + i] = (padded[2 * i].code shl 8) or padded[2 * i + 1].code
        }

        override suspend fun read(unitId: Int, register: Int, count: Int): RegisterBlock {
            if (ranges.none { register in it && register + count - 1 in it }) throw ModbusException(2)
            val data = ByteArray(count * 2)
            for (i in 0 until count) {
                val v = regs[register + i] ?: 0
                data[2 * i] = (v shr 8).toByte()
                data[2 * i + 1] = v.toByte()
            }
            return RegisterBlock(register, data)
        }
    }

    private fun goodWe(pvExtended: Boolean = true, battery2: Boolean = false, warnings: Boolean = false): Image {
        val ranges = listOf(35000..35032, 35100..35224) + listOfNotNull(
            if (battery2) 35262..35266 else null, if (pvExtended) 35301..35307 else null, 37000..37023,
            if (warnings) 35328..35336 else null, // firmware with the 32-bit warning registers
        )
        val i = Image(ranges).u16(35001, 10_000).text(35003, 8, "9010KETU224W0001").text(35011, 5, "GW10K-ET")
            .u16(35100, 0x1A0A).u16(35101, 0x180C).u16(35102, 0x1E00)
            // PV1 600.0 V 5.2 A 3120 W, PV2 580.0 V 4.0 A 2320 W
            .u16(35103, 6000).u16(35104, 52).s32(35105, 3120).u16(35107, 5800).u16(35108, 40).s32(35109, 2320)
            // phases: voltage, current, frequency, power
            .u16(35121, 2305).u16(35122, 65).u16(35123, 5001).s32(35124, 1500)
            .u16(35126, 2310).u16(35127, 66).u16(35128, 5000).s32(35129, 1520)
            .u16(35131, 2298).u16(35132, 64).u16(35133, 5000).s32(35134, 1480)
            .u16(35136, 1).s32(35137, 4500).s32(35139, 1200)
            .s32(35169, 300).s32(35171, 3000)
            .u16(35176, 452).u16(35180, 5120).u16(35181, 0xFFEC).s32(35182, -1000).u16(35184, 3)
            .u16(35187, 1).s32(35191, 123_456).s32(35193, 234)
            .u16(37003, 251).u16(37007, 64).u16(37008, 98)
        if (pvExtended) i.s32(35301, 5440).u16(35303, 2)
        if (battery2) i.u16(35262, 5000).u16(35263, 30).s32(35264, 1500).u16(35266, 2)
        return i
    }

    private fun goodWeSame(label: String, image: () -> Image) = same(spec("goodwe-et", image(), 247), "GoodWe $label")

    @Test
    fun goodWeEt() {
        goodWeSame("PV extension, battery charging") { goodWe() }
        goodWeSame("no PV extension: sum of PV1–4") { goodWe(pvExtended = false) }
        goodWeSame("two batteries") { goodWe(battery2 = true) }
        goodWeSame("battery 2 only") { goodWe(battery2 = true).u16(35184, 0) }
        goodWeSame("no battery, BMS codes ignored") { goodWe().u16(35184, 0).u16(37006, 0x20).u16(37010, 8) }
        goodWeSame("isolation fault, off-grid, warning, BMS codes") {
            goodWe().u16(35189, 0x0004).u16(35187, 2).u16(35185, 0x0012).u16(37006, 0x0020).u16(37012, 1).u16(37010, 8)
        }
        goodWeSame("off-grid") { goodWe().u16(35187, 2) }
        goodWeSame("grid mode 2 + work mode 3: one fault") { goodWe().u16(35187, 3).u16(35136, 2) }
        goodWeSame("work mode 3") { goodWe().u16(35187, 3) }
        goodWeSame("waiting, grid disconnected") { goodWe().u16(35187, 0).u16(35136, 0) }
        goodWeSame("error bit fault + work mode 3") { goodWe().u16(35189, 0x0001).u16(35187, 3) }
    }

    /** 35185 = 0x40, as on a real ET whose own web page showed no warning: no problem. 35328 is a documented warning. */
    @Test
    fun goodWeEtWarningRegisters() = runBlocking {
        val quiet = spec("goodwe-et", goodWe().u16(35185, 0x40), 247).read()
        assertEquals(emptyList<ch.amslersolar.dashboard.model.DeviceProblem>(), quiet.problems)
        val warned = spec("goodwe-et", goodWe(warnings = true).u16(35185, 0x40).u16(35329, 0x0100), 247).read()
        assertEquals(listOf("Warning 0x100"), warned.problems.map { it.text })
    }

    @Test
    fun goodWeEtLowErrorBits() {
        goodWeSame("utility loss + error bit 2") { goodWe().u16(35190, 0x0204) }
    }

    @Test
    fun goodWeIdentify() = runBlocking {
        assertEquals("9010KETU224W0001", spec("goodwe-et", goodWe(), 247).identify().serial)
    }

    // ---------------------------------------------------------------- Huawei SUN2000 (copied from HuaweiSun2000DriverTest)

    private class FakeSun2000(battery: Boolean = true, meter: Boolean = true, meterStatus: Int = 1,
                              gridW: Int = -1234, batteryW: Int = 2000, status: Int = 0x0200) : RegisterReader {
        val regs = HashMap<Int, Int>()

        init {
            val blocks = IDENTITY + LIVE + DETAIL + (if (meter) METER else emptyList()) + (if (battery) BATTERY else emptyList())
            for ((start, count) in blocks) for (a in start until start + count) regs[a] = 0
            text(30000, "SUN2000-10KTL-M1", 15); text(30015, "HV2150000001", 10)
            regs[30071] = 4; u32(30073, 10_000)
            listOf(3805 to 812, 3790 to 800, 0 to 0, 0 to 0).forEachIndexed { i, (v, a) -> regs[32016 + 2 * i] = v; regs[32017 + 2 * i] = a }
            u32(32064, 6_125); u32(32080, 5_900)
            regs[32085] = 5002; regs[32087] = 452; regs[32089] = status
            u32(32106, 1_234_567); u32(32114, 2_345)
            if (meter) {
                regs[37100] = meterStatus
                u32(37101, 2311); u32(37103, 2320); u32(37105, 2298)
                u32(37107, 512); u32(37109, 498); u32(37111, 505)
                u32(37113, gridW); regs[37118] = 5001
                u32(37119, 512_300); u32(37121, 98_700)
                u32(37132, -400); u32(37134, -420); u32(37136, -414)
            }
            if (battery) {
                u32(37758, 10_000); regs[37760] = 653; regs[37762] = 2; regs[37763] = 4501; u32(37765, batteryW)
            }
        }

        fun u32(a: Int, v: Int) { regs[a] = v ushr 16 and 0xFFFF; regs[a + 1] = v and 0xFFFF }
        private fun text(a: Int, s: String, n: Int) {
            val b = s.toByteArray().copyOf(2 * n)
            repeat(n) { regs[a + it] = ((b[2 * it].toInt() and 0xFF) shl 8) or (b[2 * it + 1].toInt() and 0xFF) }
        }

        override suspend fun read(unitId: Int, register: Int, count: Int): RegisterBlock {
            if ((register until register + count).any { it !in regs }) throw ModbusException(2)
            val data = ByteArray(count * 2)
            repeat(count) { val v = regs.getValue(register + it); data[2 * it] = (v shr 8).toByte(); data[2 * it + 1] = v.toByte() }
            return RegisterBlock(register, data)
        }

        companion object {
            // (start, count): the hand-written driver's runs of defined registers.
            val IDENTITY = listOf(30000 to 35, 30070 to 17)
            val LIVE = listOf(32016 to 12, 32064 to 31, 32106 to 14)
            val METER = listOf(37100 to 39)
            val BATTERY = listOf(37758 to 3, 37762 to 5, 37780 to 8)
            val DETAIL = listOf(32008 to 4, 37000 to 5, 37006 to 5, 37014 to 5, 37021 to 2, 37025 to 1, 37046 to 4, 37066 to 4)
        }
    }

    @Test
    fun huaweiSun2000() {
        fun alarms() = FakeSun2000(status = 0x0203).apply {
            regs[32008] = (1 shl 7) or (1 shl 9); regs[32009] = 1 shl 3; regs[32010] = 1 shl 12
            regs[37762] = 3; regs[37014] = 17
        }
        val cases: List<Pair<String, () -> FakeSun2000>> = listOf(
            "battery charging" to { FakeSun2000() },
            "battery discharging" to { FakeSun2000(batteryW = -1500) },
            "no battery, no meter" to { FakeSun2000(battery = false, meter = false) },
            "battery with capacity 0" to { FakeSun2000().apply { u32(37758, 0); regs[37762] = 3; regs[37014] = 5 } },
            "meter offline" to { FakeSun2000(meterStatus = 0) },
            "unknown status" to { FakeSun2000(status = 0x0BEE) },
            "night" to { FakeSun2000(status = 0xA000) },
            "alarms, off-grid, battery fault ID" to ::alarms,
            "battery status 3 without fault ID" to { FakeSun2000().apply { regs[37762] = 3 } },
            "shutdown: fault" to { FakeSun2000(status = 0x0300) },
            "shutdown: fault with an alarm fault" to { FakeSun2000(status = 0x0300).apply { regs[32008] = 1 shl 9 } },
            "off-grid" to { FakeSun2000(status = 0x0203) },
            "off-grid charging" to { FakeSun2000(status = 0x0A00) },
            "self-derating" to { FakeSun2000(status = 0x0202) },
        )
        for ((label, image) in cases) {
            same(spec("huawei-sun2000", image(), 1), "Huawei $label")
        }
    }

    @Test
    fun huaweiIdentify() = runBlocking {
        assertEquals("HV2150000001", spec("huawei-sun2000", FakeSun2000(), 1).identify().serial)
    }

    // ---------------------------------------------------------------- Growatt VPP V2.01 (copied from GrowattVppDriverTest)

    private class Space(private val groups: List<IntRange>) : RegisterReader {
        val regs = HashMap<Int, Int>()

        fun zero(start: Int, count: Int) { for (a in start until start + count) regs[a] = 0 }
        fun u32(a: Int, v: Long) { regs[a] = (v ushr 16 and 0xFFFF).toInt(); regs[a + 1] = (v and 0xFFFF).toInt() }
        fun s32(a: Int, v: Int) = u32(a, v.toLong() and 0xFFFFFFFFL)
        fun s16(a: Int, v: Int) { regs[a] = v and 0xFFFF }
        fun text(a: Int, s: String, n: Int) {
            val b = s.toByteArray().copyOf(2 * n)
            repeat(n) { regs[a + it] = ((b[2 * it].toInt() and 0xFF) shl 8) or (b[2 * it + 1].toInt() and 0xFF) }
        }

        override suspend fun read(unitId: Int, register: Int, count: Int): RegisterBlock {
            val range = register until register + count
            if (range.any { it !in regs }) throw ModbusException(2)
            if (groups.none { register in it && range.last in it }) throw ModbusException(2) // crossing groups
            val data = ByteArray(count * 2)
            repeat(count) { val v = regs.getValue(register + it); data[2 * it] = (v shr 8).toByte(); data[2 * it + 1] = v.toByte() }
            return RegisterBlock(register, data)
        }
    }

    private class FakeGrowatt(
        dtc: Int = 3601,
        serial: String = "XYZ1A2B3C4",
        batteries: Int = 1,
        batteryRaw: List<Int> = listOf(-25_000, 0, 0, 0),
        meterRaw: Int = 12_000,
        acRaw: Int = 30_000,
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
    }

    private fun growattSpec(f: FakeGrowatt) = ChoosingSpecDriver(
        SpecDevices.SPEC_IDS.getValue(ch.amslersolar.dashboard.model.DeviceType.GROWATT).map { spec(it, SpecTables(holding = f.holding, input = f.input), 1) },
    )

    @Test
    fun growattVpp() {
        val cases: List<Pair<String, () -> FakeGrowatt>> = listOf(
            "charging, importing" to { FakeGrowatt(batteryRaw = listOf(25_000, 0, 0, 0), meterRaw = 12_000, acRaw = -5_000) },
            "discharging, exporting" to { FakeGrowatt(batteryRaw = listOf(-18_000, 0, 0, 0), meterRaw = -9_000, acRaw = 40_000) },
            "MIN-X without battery, single-phase" to { FakeGrowatt(dtc = 5200, batteries = 0, meterRaw = -20_000, acRaw = 45_000, threePhase = false) },
            "MIN-XH" to { FakeGrowatt(dtc = 5100) },
            "WIT" to { FakeGrowatt(dtc = 5601) },
            "SPH single-phase" to { FakeGrowatt(dtc = 3502, threePhase = false) },
            "three batteries" to { FakeGrowatt(batteries = 3, batteryRaw = listOf(10_000, 20_000, -5_000, 0)) },
            "four batteries" to { FakeGrowatt(batteries = 4, batteryRaw = listOf(1, 2, 3, 4)) },
            "no versions" to { FakeGrowatt(versions = false) },
            "negative PV and load" to { FakeGrowatt(acRaw = -50_000, meterRaw = 10_000).apply { input.s32(31058, -30) } },
            "all fine" to { FakeGrowatt() },
            "fault, alarm, battery fault" to {
                FakeGrowatt().apply { with(input.regs) { this[31000] = 3; this[31005] = 102; this[31006] = 3; this[31007] = 205; this[31008] = 1; this[31001] = 4 } }
            },
            "battery fault without battery" to { FakeGrowatt(dtc = 5200, batteries = 0).apply { input.regs[31001] = 4 } },
            "off-grid" to { FakeGrowatt().apply { input.regs[31000] = 7 } },
            "fault without code" to { FakeGrowatt().apply { input.regs[31000] = 3 } },
        )
        for ((label, image) in cases) same(growattSpec(image()), "Growatt $label")
    }

    @Test
    fun growattIdentify() = runBlocking {
        assertEquals("QWE4CK5003", growattSpec(FakeGrowatt(dtc = 5100, serial = "QWE4CK5003")).identify().serial)
        val sunspec = Space(listOf(0..65535)).apply { zero(40000, 70) }
        rejects(ChoosingSpecDriver(listOf("growatt-sph", "growatt-tl-x", "growatt-wit").map { spec(it, SpecTables(sunspec, sunspec), 1) }))
        val garbage = Space(listOf(30000..30099)).apply { zero(30000, 100); regs[30000] = 1234 }
        rejects(ChoosingSpecDriver(listOf("growatt-sph", "growatt-tl-x", "growatt-wit").map { spec(it, SpecTables(garbage, garbage), 1) }))
    }

    // ---------------------------------------------------------------- KOSTAL PLENTICORE (copied from KostalPlenticoreDriverTest)

    /** A register image that, like a strict inverter, rejects any read touching an undefined register. */
    private class FakeKostal(private val wordSwapped: Boolean) : RegisterReader {
        val regs = HashMap<Int, Int>()

        init {
            regs[5] = if (wordSwapped) 0 else 1
        }

        fun u16(a: Int, v: Int) = apply { regs[a] = v and 0xFFFF }
        fun u32(a: Int, v: Long) = apply {
            val hi = ((v shr 16) and 0xFFFF).toInt()
            val lo = (v and 0xFFFF).toInt()
            if (wordSwapped) { regs[a] = lo; regs[a + 1] = hi } else { regs[a] = hi; regs[a + 1] = lo }
        }
        fun f32(a: Int, v: Double) = u32(a, v.toFloat().toRawBits().toLong() and 0xFFFFFFFFL)
        fun text(a: Int, n: Int, s: String) = apply {
            val padded = s.padEnd(n * 2, '\u0000')
            for (i in 0 until n) regs[a + i] = (padded[2 * i].code shl 8) or padded[2 * i + 1].code
        }

        override suspend fun read(unitId: Int, register: Int, count: Int): RegisterBlock {
            assertEquals(71, unitId)
            val data = ByteArray(count * 2)
            for (i in 0 until count) {
                val v = regs[register + i] ?: throw ModbusException(2)
                data[2 * i] = (v shr 8).toByte()
                data[2 * i + 1] = v.toByte()
            }
            return RegisterBlock(register, data)
        }
    }

    /** Master: BYD battery on DC3, KSEM at the grid connection; charging 3 kW from 5 kW PV, exporting 1.2 kW. */
    private fun kostalMaster(wordSwapped: Boolean = true) = FakeKostal(wordSwapped)
        .text(768, 32, "PLENTICORE plus").text(800, 32, "10").text(14, 8, "90123ABC")
        .u16(34, 2).u16(588, 4).u16(1082, 3).u16(1545, 2)
        .u32(56, 6)
        .f32(1066, 5_000.0)
        .f32(258, 6.0).f32(260, 3_000.0).f32(266, 500.0)
        .f32(268, 4.0).f32(270, 2_000.0).f32(276, 500.0)
        .u16(582, 3_000).f32(200, -6.5).f32(216, 460.0).u16(514, 56).f32(210, 56.4)
        .f32(252, -1_200.0).f32(220, 50.0)
        .f32(116, 800.0).f32(106, 0.0).f32(108, 0.0)
        .f32(152, 50.01).f32(154, 1.5).f32(156, 330.0).f32(158, 231.0)
        .f32(160, 1.4).f32(162, 320.0).f32(164, 232.0).f32(166, 1.6).f32(168, 350.0).f32(170, 230.0)
        .f32(98, 41.5).f32(322, 18_400.0).f32(320, 23_500_000.0)

    /** Slave: PV only, no battery, no meter, big-endian, no 1066 on this firmware. */
    private fun kostalSlave() = FakeKostal(wordSwapped = false)
        .text(768, 32, "PLENTICORE plus").text(800, 32, "10")
        .u16(34, 3).u16(588, 0).u16(1082, 0xFF).u16(1545, 1)
        .f32(258, 3.0).f32(260, 1_500.0).f32(266, 500.0)
        .f32(268, 2.0).f32(270, 1_000.0).f32(276, 500.0)
        .f32(278, 1.0).f32(280, 500.0).f32(286, 500.0)
        .f32(252, 0.0).f32(322, 9_000.0)

    @Test
    fun kostalPlenticore() {
        val cases: List<Pair<String, () -> FakeKostal>> = listOf(
            "master, word-swapped" to { kostalMaster() },
            "master, big-endian" to { kostalMaster(wordSwapped = false) },
            "slave: no battery, meter or 1066" to ::kostalSlave,
            "emergency power, battery fuse failed" to { kostalMaster().u32(56, 17).f32(202, 0.0) },
            "overheating" to { kostalMaster().u32(56, 14) },
            "fuse state 0 without a battery" to { kostalMaster().u16(588, 0).f32(210, 0.0).f32(202, 0.0) },
            "discharging" to { kostalMaster().f32(200, 4.0).u16(582, 1_840) },
            "battery current ≈ 0: direction from 582" to { kostalMaster().f32(200, 0.05).u16(582, -200) },
            "no 582: |216 × 200|" to { kostalMaster().apply { regs.remove(582) } },
        )
        for ((label, image) in cases) {
            same(spec("kostal-plenticore", image(), 71), "KOSTAL $label")
        }
    }

    @Test
    fun kostalIdentify() = runBlocking {
        // Its SunSpec header (always big-endian, whatever register 5 says) names it.
        for (swapped in listOf(true, false)) {
            val fake = kostalMaster(swapped).text(40000, 2, "SunS").text(40004, 16, "KOSTAL").text(40020, 16, "PLENTICORE plus")
            assertEquals("90123ABC", spec("kostal-plenticore", fake, 71).identify().serial)
        }
    }

    /** The PLENTICORE states its word order in register 5 (0 = CDAB, 1 = ABCD): also right at night (152 reads 0 Hz). */
    @Test
    fun kostalWordSwappedWithoutFrequency() {
        same(spec("kostal-plenticore", kostalMaster().f32(152, 0.0), 71), "KOSTAL word-swapped, 0 Hz")
    }

    // ---------------------------------------------------------------- SMA (copied from SmaDriverTest)

    /**
     * Like the SMA Modbus server: a block with at least one defined register is answered, its undefined registers
     * read as NaN of their type; a block without any defined register is rejected with exception 2.
     */
    private class FakeSma : RegisterReader {
        val regs = HashMap<Int, Int>()

        fun u32(a: Int, v: Long) = apply { regs[a] = ((v shr 16) and 0xFFFF).toInt(); regs[a + 1] = (v and 0xFFFF).toInt() }
        fun s32(a: Int, v: Int) = u32(a, v.toLong() and 0xFFFFFFFFL)
        fun u64(a: Int, v: Long) = u32(a, v ushr 32).u32(a + 2, v and 0xFFFFFFFFL)
        fun text(a: Int, n: Int, s: String) = apply {
            val padded = s.padEnd(n * 2, '\u0000')
            for (i in 0 until n) regs[a + i] = (padded[2 * i].code shl 8) or padded[2 * i + 1].code
        }

        override suspend fun read(unitId: Int, register: Int, count: Int): RegisterBlock {
            assertEquals(3, unitId)
            org.junit.Assert.assertTrue(count in 1..125)
            val range = register until register + count
            if (range.none { it in regs }) throw ModbusException(2)
            val data = ByteArray(count * 2)
            for ((i, r) in range.withIndex()) {
                val v = regs[r] ?: when {
                    r in S32 -> 0x8000
                    r - 1 in S32 -> 0x0000
                    else -> 0xFFFF
                }
                data[2 * i] = (v shr 8).toByte()
                data[2 * i + 1] = v.toByte()
            }
            return RegisterBlock(register, data)
        }

        companion object {
            val S32 = setOf(
                30769, 30771, 30773, 30775, 30777, 30779, 30781, 30805, 30807, 30809, 30811, 30813, 30815, 30817,
                30819, 30829, 30831, 30843, 30849, 30857, 30861, 30865, 30867, 30869, 30873, 30909, 30911, 30913,
                30953, 30957, 30959, 30961, 30975, 30977, 30979, 30981, 30985, 30997, 30999, 31159, 31221, 31247,
                31411, 31417, 31419, 31421, 31423, 31425, 31427, 31429, 31431,
            )
        }
    }

    private fun smaIdentity(fake: FakeSma, type: Int, serial: Long, cls: Long = 8001) = fake
        .u32(30051, cls).u32(30053, type.toLong()).u32(30057, serial).u32(30059, 0x02510A04)

    private fun tripower() = smaIdentity(FakeSma(), 9281, 2_130_012_345)
        .u32(30201, 307).u32(30217, 51).u32(30219, 884).u32(30225, 3_500_000)
        .u64(30513, 45_678_900).u64(30517, 23_450)
        .s32(30769, 8_500).s32(30771, 61_234).s32(30773, 5_200)
        .s32(30957, 1_600).s32(30959, 58_000).s32(30961, 930)
        .s32(30775, 6_000).s32(30777, 2_000).s32(30779, 2_000).s32(30781, 2_000)
        .u32(30783, 23_100).u32(30785, 23_200).u32(30787, 23_000).u32(30803, 5_001)
        .s32(30977, 8_660).s32(30979, 8_620).s32(30981, 8_700)
        .s32(30953, 452)
        .u32(30583, 40_000_000)
        .u32(40029, 295)
        .text(31017, 8, "192.168.1.50")

    private fun smartEnergy() = smaIdentity(FakeSma(), 9225, 1_900_123_456)
        .u32(30201, 307)
        .u64(30513, 9_000_000).u64(30517, 12_300)
        .s32(30769, 10_000).s32(30771, 40_000).s32(30773, 4_000)
        .s32(30775, 1_800).s32(30777, 1_800).u32(30783, 23_000).u32(30803, 4_998)
        .s32(30843, 5_000).u32(30845, 64).u32(30847, 98).u32(30851, 44_000)
        .u32(30955, 2292)
        .s32(30865, 0).s32(30867, 1_200)
        .u32(30581, 1_000_000).u32(30583, 2_000_000)
        .u32(40187, 6_000)

    private fun sunnyIsland() = smaIdentity(FakeSma(), 9279, 1_800_000_001)
        .u32(30201, 307)
        .u64(30513, 3_000_000)
        .s32(30775, 1_500).s32(30777, 1_500).u32(30783, 23_050).u32(30803, 5_000)
        .s32(30843, -3_000).u32(30845, 81).u32(30851, 50_200).s32(30849, 215)
        .s32(30861, 2_100).s32(30865, 600).s32(30867, 0).s32(30869, 0)
        .u32(30577, 4_200).u32(30579, 1_100)
        .u32(31393, 0).u32(31395, 1_500)
        .u64(31397, 1_234_000).u64(31401, 1_100_000)
        .u32(40031, 100).u32(40037, 48)

    private fun smaNight() = smaIdentity(FakeSma(), 9076, 7)
        .u32(30201, 0xFFFFFD).u32(30217, 311)
        .s32(30769, Int.MIN_VALUE).s32(30771, Int.MIN_VALUE).s32(30773, Int.MIN_VALUE)
        .s32(30775, Int.MIN_VALUE).u32(30783, 0xFFFFFFFFL).u32(30803, 0xFFFFFFFFL)
        .s32(30953, Int.MIN_VALUE)
        .u64(30513, -1L).u64(30517, -1L)

    @Test
    fun sma() {
        val cases: List<Pair<String, () -> FakeSma>> = listOf(
            "Tripower, two strings" to ::tripower,
            "Smart Energy charging, exporting" to ::smartEnergy,
            "Smart Energy discharging, importing" to { smartEnergy().u32(30955, 2293).s32(30865, 300).s32(30867, 0) },
            "battery standby" to { smartEnergy().u32(30955, 2291) },
            "battery direction from the current" to { smartEnergy().u32(30955, 0xFFFFFD).s32(30843, -2_500) },
            "Sunny Island" to ::sunnyIsland,
            "Sunny Island by its 31393 / 31395" to { sunnyIsland().u32(30053, 9999) },
            "night: NaN" to ::smaNight,
            "unknown type, hybrid class" to { smaIdentity(FakeSma(), 9999, 42, cls = 8009).s32(30775, 900) },
            "fault, operating fault, derating" to { tripower().u32(30201, 35).u32(30197, 3501).u32(40029, 1392).u32(30219, 557) },
            "warning, separate network, battery warning" to { smartEnergy().u32(30201, 455).u32(30235, 1441).u32(31391, 455) },
            "operating fault only" to { tripower().u32(40029, 1392) },
            "battery error" to { smartEnergy().u32(31391, 35) },
            "one string NaN" to { tripower().s32(30959, Int.MIN_VALUE) },
            "U32 yields only" to { tripower().u64(30513, -1L).u64(30517, -1L).u32(30529, 45_678).u32(30535, 23) },
        )
        for ((label, image) in cases) same(spec("sma-inverter", image(), 3), "SMA $label")
    }

    @Test
    fun smaIdentify() = runBlocking {
        assertEquals("2130012345", spec("sma-inverter", tripower(), 3).identify().serial)
        assertEquals("42", spec("sma-inverter", smaIdentity(FakeSma(), 9999, 42, cls = 8009), 3).identify().serial)
        rejects(spec("sma-inverter", FakeSma().u32(100, 1), 3))
        rejects(spec("sma-inverter", FakeSma().u32(30059, 1), 3))
        rejects(spec("sma-inverter", smaIdentity(FakeSma(), 9076, 1, cls = 3), 3))
    }

    /** As the hand-written driver: at most one poll per 10 s (SpecDevices wraps the SMA driver so). */
    @Test
    fun smaKeepsTenSecondsBetweenPolls() = runBlocking {
        var requests = 0
        val fake = tripower()
        val counting = RegisterReader { u, r, c -> requests++; fake.read(u, r, c) }
        var now = 0L
        val driver = ThrottledDriver(spec("sma-inverter", counting, 3), 10_000) { now }
        val first = driver.read()
        val afterFirst = requests
        now = 5_000
        org.junit.Assert.assertTrue(driver.read() === first)
        assertEquals(afterFirst, requests)
        now = 10_000
        driver.read()
        org.junit.Assert.assertTrue(requests > afterFirst)
    }

    // ---------------------------------------------------------------- Sungrow (copied from SungrowDriverTest)

    /** By the specs' 1-based numbers; requests arrive at number − 1; undocumented registers give exception 2. */
    private class FakeSungrow(private val inputDefined: List<IntRange>, private val holdingDefined: List<IntRange>) {
        val inp = HashMap<Int, Int>()
        val hold = HashMap<Int, Int>()

        fun u16(r: Int, v: Int, regs: HashMap<Int, Int> = inp) = apply { regs[r] = v and 0xFFFF }
        fun s16(r: Int, v: Int) = u16(r, v)
        fun u32(r: Int, v: Long) = apply { u16(r, (v and 0xFFFF).toInt()); u16(r + 1, (v shr 16).toInt()) }
        fun s32(r: Int, v: Int) = u32(r, v.toLong() and 0xFFFFFFFFL)
        fun text(r: Int, n: Int, s: String) = apply {
            val b = s.toByteArray(Charsets.US_ASCII).copyOf(n * 2)
            for (i in 0 until n) u16(r + i, ((b[2 * i].toInt() and 0xFF) shl 8) or (b[2 * i + 1].toInt() and 0xFF))
        }

        private fun serve(regs: Map<Int, Int>, defined: List<IntRange>, unitId: Int, wire: Int, count: Int): RegisterBlock {
            assertEquals(1, unitId)
            org.junit.Assert.assertTrue("max 125 registers", count in 1..125)
            val data = ByteArray(count * 2)
            for (i in 0 until count) {
                val r = wire + 1 + i
                if (defined.none { r in it }) throw ModbusException(2)
                val v = regs[r] ?: 0
                data[2 * i] = (v shr 8).toByte(); data[2 * i + 1] = v.toByte()
            }
            return RegisterBlock(wire, data)
        }

        val input = RegisterReader { u, r, c -> serve(inp, inputDefined, u, r, c) }
        val holding = RegisterReader { u, r, c -> serve(hold, holdingDefined, u, r, c) }
    }

    private val hybridInput = listOf(
        4950..5030, 5033..5036, 5115..5133, 5214..5215, 5242..5242, 5601..5608, 5622..5623, 5628..5628, 5631..5631,
        5635..5636, 5639..5639, 5720..5727, 5731..5734, 13000..13014, 13017..13079, 13200..13207, 13250..13369,
    )
    private val hybridHolding = listOf(13050..13052, 13058..13059, 13074..13075, 13086..13090, 13100..13100)
    private val stringInput = listOf(4950..5151, 7013..7036)
    private val stringHolding = listOf(5000..5042)

    private fun sh10rt(withMeter: Boolean = true) = FakeSungrow(if (withMeter) hybridInput else hybridInput - listOf(5601..5608), hybridHolding).apply {
        text(4990, 10, "A2231234567"); u16(5000, 0xE03); u16(5001, 100); u16(5002, 1)
        u16(5003, 250); u32(5004, 732150); s16(5008, 425)
        u16(5011, 3500); u16(5012, 92); u16(5013, 3400); u16(5014, 85); u16(5015, 0xFFFF); u16(5016, 0xFFFF)
        u32(5017, 6000)
        u16(5019, 2301); u16(5020, 2310); u16(5021, 2295)
        u16(5242, 5001)
        u16(13000, 0x0000)
        u16(13002, 215); u32(13003, 123456)
        u16(13020, 4012); u16(13023, 655); u16(13024, 990); s16(13025, 251)
        s16(13031, 87); s16(13032, 86); s16(13033, -88)
        u16(13036, 12); u16(13045, 34)
        u16(13050, 0, hold); u16(13058, 1000, hold); u16(13059, 100, hold)
    }

    private fun sg10rt() = FakeSungrow(stringInput, stringHolding).apply {
        text(4990, 10, "B2219876543"); u16(5000, 0x2433); u16(5001, 100); u16(5002, 1)
        u16(5003, 312); u32(5004, 9876); u32(5006, 4321); s16(5008, 380)
        u16(5011, 6000); u16(5012, 41); u16(5013, 5900); u16(5014, 40); u32(5017, 4800)
        u16(5019, 2300); u16(5020, 2310); u16(5021, 2320); u16(5022, 61); u16(5023, 60); u16(5024, 62)
        s32(5031, 4200); u16(5038, 0x0000); u32(5081, (1L shl 0) or (1L shl 17))
        u32(5093, 85); u32(5097, 20)
        u32(5144, 987654); u16(5148, 4998)
        u16(5007, 0xAA, hold); u16(5008, 700, hold)
    }

    private fun sungrowSpec(f: FakeSungrow) = ChoosingSpecDriver(
        SpecDevices.SPEC_IDS.getValue(ch.amslersolar.dashboard.model.DeviceType.SUNGROW).map { spec(it, SpecTables(holding = f.holding, input = f.input), 1) },
    )

    @Test
    fun sungrow() {
        val cases: List<Pair<String, () -> FakeSungrow>> = listOf(
            "SH charging, exporting" to {
                sh10rt().apply { u16(13001, 0x1B); s32(5214, 2000); s32(5601, -1500); s32(5603, -500); s32(5605, -500); s32(5607, -500); s32(13008, 2500) }
            },
            "SH discharging, importing" to { sh10rt().apply { u16(13001, 0x2D); s32(5214, -1800); s32(5601, 700); s32(13008, 3100) } },
            "SH8.0RS, four MPPTs" to { sh10rt().apply { u16(5000, 0xD1A); u16(5015, 3300); u16(5016, 50); u16(5115, 3200); u16(5116, 40) } },
            "SH without meter, values not available" to {
                sh10rt(withMeter = false).apply { u16(13001, 0x01); s16(5008, 0x7FFF); u16(13023, 0xFFFF); u32(13003, 0xFFFFFFFFL) }
            },
            "SH meter marker" to { sh10rt().apply { for (reg in listOf(5601, 5603, 5605, 5607)) s32(reg, Int.MAX_VALUE) } },
            "SH without battery" to { sh10rt().apply { u16(13020, 0); u16(13023, 0) } },
            "SH single phase" to { sh10rt().apply { u16(5002, 0) } },
            "SH 3P3L" to { sh10rt().apply { u16(5002, 2) } },
            "SH fault bits, fault state" to {
                sh10rt().apply { u16(13000, 0x5500); u32(13052, (1L shl 8) or (1L shl 0)); u32(13050, 1L shl 0); u32(13066, 1L shl 1) }
            },
            "SH fault state" to { sh10rt().apply { u16(13000, 0x5500) } },
            "SH warn running with an alarm bit" to { sh10rt().apply { u16(13000, 0x9100); u32(13050, 1L shl 4) } },
            "SH warn running" to { sh10rt().apply { u16(13000, 0x9100) } },
            "SH off-grid" to { sh10rt().apply { u16(13000, 0x1000) } },
            "SH derating" to { sh10rt().apply { u16(13000, 0x8100) } },
            "SH standby" to { sh10rt().apply { u16(13000, 0x1400) } },
            "SH BMS fault 2 (no named bits)" to { sh10rt().apply { u32(13076, 1L shl 3) } },
            "SG with meter" to { sg10rt().apply { s32(5083, -1000); s32(5091, 3200) } },
            "SG meter sign from the energy balance" to { sg10rt().apply { s32(5083, 1000); s32(5091, 3200) } },
            "SG5.0RS without meter" to {
                sg10rt().apply {
                    u16(5000, 0x2606); u16(5002, 0)
                    for (reg in listOf(5083, 5085, 5087, 5089, 5091)) s32(reg, Int.MAX_VALUE)
                    u32(5093, 0xFFFFFFFFL); u32(5097, 0xFFFFFFFFL); u32(5144, 0xFFFFFFFFL)
                }
            },
            "SG negative AC power" to { sg10rt().apply { s32(5031, -20) } },
            "SG fault code" to { sg10rt().apply { u16(5038, 0x5500); u16(5045, 2) } },
            "SG grid outage" to { sg10rt().apply { u16(5038, 0x5500); u16(5045, 10) } },
            "SG fault without code" to { sg10rt().apply { u16(5038, 0x5500) } },
            "SG alarm, unknown code, PID alarm" to { sg10rt().apply { u16(5038, 0x9100); u16(5045, 9999); u16(5151, 432) } },
            "SG code outside fault / alarm" to { sg10rt().apply { u16(5045, 10) } },
            "SG derating, communicate fault" to { sg10rt().apply { u16(5038, 0x8100) } },
            "SG communicate fault" to { sg10rt().apply { u16(5038, 0x2500) } },
        )
        for ((label, image) in cases) same(sungrowSpec(image()), "Sungrow $label")
    }

    /**
     * Beyond the hand-written driver (which has grid only from 5601): firmware before protocol V1.1.7 has no 5601,
     * and the file's grid power falls back to the export power 13010 (negated). Unknown protocol versions (0, or
     * through a Logger) keep the old rule, so a meter-less inverter doesn't report 0 W from 13010.
     */
    @Test
    fun sungrowGridFromExportPowerOnOldFirmware() = runBlocking {
        val old = sh10rt(withMeter = false).apply { u32(4952, 0x01010600); u16(13001, 0x1B); s32(13010, 1500); s32(13008, 2500) }
        val r = sungrowSpec(old).read()
        assertEquals(1500, r.gridPowerW)
        assertEquals(2500, r.loadPowerW)
        val unknown = sh10rt(withMeter = false).apply { s32(13010, 1500); s32(13008, 2500) }
        assertEquals(null, sungrowSpec(unknown).read().gridPowerW)
    }

    @Test
    fun sungrowMeterSignVotesKeep() = runBlocking {
        // After the balance said "+ export", a reading too small to vote on keeps that sign (as the old driver).
        val dev = sg10rt().apply { s32(5083, 1000); s32(5091, 3200) }
        val new = sungrowSpec(dev)
        new.read()
        dev.s32(5083, 50)
        val oldGrid = golden.fields(golden.take("second reading")).getValue("gridPowerW").toIntOrNull()
        assertEquals(oldGrid, new.read().gridPowerW)
    }

    @Test
    fun sungrowIdentify() = runBlocking {
        assertEquals("A2231234567", sungrowSpec(sh10rt()).identify().serial)
        assertEquals("B2219876543", sungrowSpec(sg10rt()).identify().serial)
        rejects(sungrowSpec(FakeSungrow(stringInput, stringHolding).text(4990, 10, "XYZ1234567").u16(5000, 0x1234)))
        rejects(sungrowSpec(FakeSungrow(emptyList(), emptyList())))
    }
}
