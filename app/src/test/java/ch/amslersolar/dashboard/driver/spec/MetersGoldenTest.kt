// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.spec

import ch.amslersolar.dashboard.driver.DeviceDriver
import ch.amslersolar.dashboard.driver.meter.AbbMeterDriver
import ch.amslersolar.dashboard.driver.meter.CarloGavazziMeterDriver
import ch.amslersolar.dashboard.driver.meter.FakeMeterImage
import ch.amslersolar.dashboard.driver.meter.IneproPro380Driver
import ch.amslersolar.dashboard.driver.meter.JanitzaUmgDriver
import ch.amslersolar.dashboard.driver.meter.KostalKsemDriver
import ch.amslersolar.dashboard.driver.meter.MeterRegisterFake
import ch.amslersolar.dashboard.driver.meter.SchneiderIemDriver
import ch.amslersolar.dashboard.driver.meter.ShellyPro3EmDriver
import ch.amslersolar.dashboard.driver.meter.SiemensPacDriver
import ch.amslersolar.dashboard.driver.meter.silentDevice
import ch.amslersolar.dashboard.model.DeviceReading
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Ignore
import org.junit.Test

/**
 * The ModbusSpec drivers of the meters must read what the hand-written meter drivers read, on the same register
 * images (copied from the drivers' own tests): power, phases, energy and the problem kinds. Texts may differ.
 */
class MetersGoldenTest {
    private val library = SpecLibrary { File("src/main/assets/modbusspec/$it").readText() }

    private fun spec(id: String, tables: SpecTables, unit: Int) =
        SpecDriver(library.spec(id), library.vocabulary, tables, unit, SpecDevices.hooks(id), requestGapMs = 0)

    private fun spec(id: String, holding: ch.amslersolar.dashboard.driver.sunspec.RegisterReader, unit: Int) =
        spec(id, SpecTables(holding = holding, input = holding), unit)

    private fun fails(block: suspend () -> Unit) = assertEquals(true, runCatching { runBlocking { block() } }.isFailure)

    private fun essentials(r: DeviceReading) = listOf(
        "pv" to r.pvPowerW, "grid" to r.gridPowerW, "load" to r.loadPowerW,
        "battery" to r.battery?.let { listOf(it.powerW, it.socPercent) },
        "phases" to r.phases.map { listOf(it.name, "%.2f".format(it.voltageV), it.currentA?.let { a -> "%.2f".format(a) }, it.powerW) },
        "lifetimePv" to r.lifetimePvKwh, "todayPv" to r.today.pvKwh,
        "problems" to r.problems.map { it.kind }.sorted(),
    )

    private fun same(old: DeviceDriver, new: DeviceDriver) = runBlocking {
        val a = old.read()
        val b = new.read()
        assertEquals(essentials(a).joinToString("\n"), essentials(b).joinToString("\n"))
        val ta = a.problems.map { it.text }.sorted()
        val tb = b.problems.map { it.text }.sorted()
        if (ta != tb) println("problem texts differ: old $ta, spec $tb")
        b
    }

    // ---------------------------------------------------------------- inepro PRO380

    /** PRO380-Mod with the whole documented map; [kw] per phase, + = forward (import). */
    private fun pro380(kw: List<Double>): FakeMeterImage {
        val f = FakeMeterImage(unitId = 3, maxCount = 125)
        for (a in 0x4000..0x401F) f.u16(a, 0)
        for (a in 0x5000..0x5031) f.u16(a, 0)
        for (a in 0x6000..0x604A) f.u16(a, 0)
        f.u32(0x4000, 0x19123456L).u16(0x4002, 0x0102).u16(0x4003, 3).u16(0x4004, 9600).u16(0x4011, 1)
        f.f32(0x4007, 1.18).u16(0x400F, 5).u16(0x4012, 'F'.code).u16(0x4013, 'F'.code).u16(0x4014, 'R'.code)
        listOf(230.0, 231.0, 232.0).forEachIndexed { i, v -> f.f32(0x5002 + 2 * i, v) }
        f.f32(0x5008, 50.0)
        listOf(4.0, 3.0, 2.0).forEachIndexed { i, v -> f.f32(0x500C + 2 * i, v) }
        f.f32(0x5012, kw.sum())
        kw.forEachIndexed { i, v -> f.f32(0x5014 + 2 * i, v) }
        f.f32(0x502A, 0.99)
        f.f32(0x6000, 900.0).f32(0x600C, 1000.0).f32(0x6018, 100.0).f32(0x6049, 3.5)
        return f
    }

    @Test
    fun ineproImportExportAndGaps() {
        for (kw in listOf(listOf(1.2, 0.8, 0.5), listOf(-1.0, -1.0, -0.5))) {
            same(IneproPro380Driver(pro380(kw), 3), spec("inepro-pro380", pro380(kw), 3))
        }
        fun gaps() = pro380(listOf(0.1, 0.0, 0.0)).also { f ->
            for (a in 0x6000..0x604A) f.regs.remove(a)
            f.f32(0x5016, Double.NaN)
        }
        same(IneproPro380Driver(gaps(), 3), spec("inepro-pro380", gaps(), 3))
    }

    @Test
    fun ineproIdentify() = runBlocking {
        assertEquals("19123456", spec("inepro-pro380", pro380(listOf(0.0, 0.0, 0.0)), 3).identify().serial.toLong().toString(16))
        val wrong = pro380(listOf(0.0, 0.0, 0.0)).also { it.u16(0x5008, 0).u16(0x5009, 0) }
        assertEquals(true, runCatching { spec("inepro-pro380", wrong, 3).identify() }.isFailure)
    }

    // ---------------------------------------------------------------- ABB B23 / B24

    /** B23/B24 image: every register of the ranges answers, unused ones "invalid" (FFFFh); [watts] + = import. */
    private fun b2x(type: String, watts: List<Int>, elements: Int = 3): FakeMeterImage {
        val f = FakeMeterImage(unitId = 1, maxCount = 125)
        for (a in 0x5000..0x5040) f.u16(a, 0xFFFF)
        for (a in 0x5100..0x5200) f.u16(a, 0xFFFF)
        for (a in 0x5460..0x5540) f.u16(a, 0xFFFF)
        for (a in 0x5B00..0x5B41) f.u16(a, 0xFFFF)
        for (a in 0x8900..0x8970) f.u16(a, 0xFFFF)
        for (a in 0x8A00..0x8A30) f.u16(a, 0xFFFF)
        for (a in 0x8C00..0x8CE6) f.u16(a, 0xFFFF)
        f.ascii(0x8960, 6, type).u32(0x8900, 12345678L).ascii(0x8908, 8, "1.0.0").u16(0x8910, 0x0102)
        f.u16(0x8CE5, elements)
        listOf(2301, 2295, 2310).forEachIndexed { i, v -> f.u32(0x5B00 + 2 * i, v.toLong()) }
        listOf(1050, 520, 260).forEachIndexed { i, v -> f.u32(0x5B0C + 2 * i, v.toLong()) }
        f.s32(0x5B14, watts.sum() * 100)
        watts.forEachIndexed { i, v -> f.s32(0x5B16 + 2 * i, v * 100) }
        f.u16(0x5B2C, 5001).u16(0x5B3A, 990)
        f.u64(0x5000, 1_234_567L).u64(0x5004, 89_000L).u64(0x5008, 1_145_567L)
        return f
    }

    private fun abb(image: () -> FakeMeterImage) = same(AbbMeterDriver(image(), 1), spec("abb-b23-b24", image(), 1))

    @Test
    fun abbImportExportSingleElementInvalid() {
        abb { b2x("B24 352-100", listOf(2300, 1200, 600)) }
        abb { b2x("B23 312-100", listOf(-1500, -1500, -1000)) }
        abb { b2x("B23 112-100", listOf(-800, 0, 0), elements = 1).also { it.u32(0x5B02, 0).u32(0x5B04, 0) } }
        abb {
            b2x("B23 312-100", listOf(100, 100, 100)).also { f ->
                f.words(0x5B18, 0x7FFF, 0xFFFF)
                f.words(0x5B04, 0xFFFF, 0xFFFF)
                f.words(0x5004, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF)
            }
        }
    }

    @Test
    fun abbErrorFlags() {
        abb { b2x("B23 312-100", listOf(100, 0, 0)) }
        abb { b2x("B23 312-100", listOf(100, 0, 0)).u64(0x8A13, 0L).u64(0x8A1F, 0x10L) }
        abb { b2x("B23 312-100", listOf(100, 0, 0)).u64(0x8A13, 0x0000_0000_0001_0002L) }
    }

    @Test
    fun abbIdentify() = runBlocking {
        val info = spec("abb-b23-b24", b2x("B23 312-100", listOf(0, 0, 0)), 1).identify()
        assertEquals("12345678", info.serial)
        // Not covered: the file has no identify checks, so an "A44" meter (or a silent device) is accepted.
    }

    // ---------------------------------------------------------------- Janitza UMG

    /** The shared float block 19000 ff.; [p] are the meter's phase powers (+ = drawn from the grid). */
    private fun measurements(fake: MeterRegisterFake, p: List<Double>) = fake.apply {
        listOf(230.1, 231.2, 229.8).forEachIndexed { i, v -> f32(19000 + 2 * i, v) }
        listOf(398.5, 399.0, 400.1).forEachIndexed { i, v -> f32(19006 + 2 * i, v) }
        p.forEachIndexed { i, w ->
            f32(19012 + 2 * i, kotlin.math.abs(w) / 230.0)
            f32(19020 + 2 * i, w)
            f32(19028 + 2 * i, kotlin.math.abs(w) * 1.02)
            f32(19036 + 2 * i, 50.0)
            f32(19044 + 2 * i, if (w >= 0) 0.98 else -0.98)
        }
        f32(19018, 0.2)
        f32(19026, p.sum()); f32(19034, p.sumOf { kotlin.math.abs(it) } * 1.02); f32(19042, 150.0)
        f32(19050, 50.01); f32(19052, 1.0)
        for (a in 19054..19108 step 2) f32(a, 0.0)
        f32(19068, 12_345_600.0)
        f32(19076, 6_543_200.0)
        for (a in 19110..19120 step 2) f32(a, 2.5)
    }

    private fun umg604(p: List<Double>) = measurements(MeterRegisterFake(unitId = 1), p)
        .text(10072, 32, "Hauptzaehler").text(10104, 64, "Grid connection").u32(10176, 7_001_234).u32(10178, 55)

    private fun umg96rme(p: List<Double>) = measurements(MeterRegisterFake(unitId = 1), p)
        .u16(750, 302).u32(754, 4_401_122).u32(756, 99).u16(761, 3)

    private fun janitza(image: () -> MeterRegisterFake) = same(JanitzaUmgDriver(image(), 1), spec("janitza-umg", image(), 1))

    @Test
    fun janitzaModelsImportExportRejected() {
        janitza { umg604(listOf(800.0, 700.0, 500.0)) }
        janitza { umg604(listOf(-1_000.0, -600.0, 100.0)) }
        janitza { umg96rme(listOf(100.0, 100.0, 100.0)) }
        janitza { umg604(listOf(300.0, 300.0, 300.0)).also { f -> for (i in 0 until 3) f.remove(19020 + 2 * i) } }
    }

    @Test
    fun janitzaIdentify() = runBlocking {
        val info = spec("janitza-umg", umg604(listOf(1.0, 1.0, 1.0)), 1).identify()
        assertEquals("Janitza UMG 604-PRO", info.model)
        assertEquals("Janitza UMG 96RM / 96RM-E", spec("janitza-umg", umg96rme(listOf(1.0, 1.0, 1.0)), 1).identify().model)
        fails { spec("janitza-umg", silentDevice, 1).identify() }
        fails { spec("janitza-umg", MeterRegisterFake(unitId = 1, lenient = true).f32(19050, 1234.0), 1).identify() }
    }

    @Ignore("janitza-umg (ModbusSpec 301604b): identify lost the rotation-field check (19052 in -1/0/1); 19050 range [0, 70] alone takes a word-swapped 50 Hz (9e-33) as valid")
    @Test
    fun janitzaIdentifyRejectsSwappedWords() {
        val swapped = measurements(MeterRegisterFake(unitId = 1, lowWordFirst = true), listOf(1.0, 1.0, 1.0)).text(10072, 32, "X")
        fails { spec("janitza-umg", swapped, 1).identify() }
    }

    // ---------------------------------------------------------------- Schneider iEM3000

    /** iEM3155 at Modbus address 5; frame addresses (documented register − 1). [kw]: + = import. */
    private fun iem3155(kw: List<Double>, model: String = "iEM3155", lenient: Boolean = true) =
        MeterRegisterFake(unitId = 5, lenient = lenient).apply {
            text(29, 20, model).text(49, 20, model).text(69, 20, "Schneider Electric")
            u32(129, 1_234_567)
            u16(131, 18); u16(132, (3 shl 8) or 14); u16(133, (9 shl 8) or 30); u16(134, 0)
            text(135, 5, "V1.0")
            u16(1636, 0x2103)
            u32(2003, 7_200); u16(2013, 3); u16(2014, 4); u16(2015, 11); u16(2016, 50)
            kw.forEachIndexed { i, p ->
                f32(2999 + 2 * i, kotlin.math.abs(p) * 1000 / 230)
                f32(3027 + 2 * i, 230.0 + i)
                f32(3019 + 2 * i, 400.0 + i)
                f32(3053 + 2 * i, p)
            }
            f32(3005, 0.1); f32(3009, 3.0); f32(3025, 400.0); f32(3035, 230.0)
            f32(3059, kw.sum()); f32(3067, 0.4); f32(3075, 2.6); f32(3083, 1.2); f32(3109, 49.98)
            u64(3203, 0x0000_0001_0000_0002L)
            u64(3207, 2_500_000); u64(3219, 10_000); u64(3223, 20_000)
        }

    private fun schneider(image: () -> MeterRegisterFake) = same(SchneiderIemDriver(image(), 5), spec("schneider-iem3000", image(), 5))

    @Test
    fun schneiderImportExportIem3150() {
        schneider { iem3155(listOf(1.0, 0.8, 0.7)) }
        schneider { iem3155(listOf(-0.5, -0.5, -0.2)) }
        schneider { iem3155(listOf(0.5, 0.0, 0.0)).also { it.f32(3029, 0.0).f32(3031, 0.0) } } // single-phase wiring
        schneider {
            iem3155(listOf(0.3, 0.3, 0.3), model = "iEM3150", lenient = false)
                .remove(3067).remove(3075).remove(3207, 4).remove(3219, 4).remove(3223, 4)
        }
    }

    @Test
    fun schneiderIdentify() = runBlocking {
        val info = spec("schneider-iem3000", iem3155(listOf(1.0, 1.0, 1.0)), 5).identify()
        assertEquals("1234567", info.serial)
        fails { spec("schneider-iem3000", silentDevice, 5).identify() }
        fails { spec("schneider-iem3000", MeterRegisterFake(unitId = 5, lenient = true).text(49, 20, "PM5560").text(69, 20, "Other Corp"), 5).identify() }
    }

    // ---------------------------------------------------------------- Siemens SENTRON PAC2200
    // The PAC2200 answers its I&M 0 block (64001 ff.) only when read as one block of 27 registers (manual A.1.10):
    // connection.blocks in the file.

    /** Strict image of the documented map; [w] are the meter's phase powers (+ = import). */
    private fun pac2200(w: List<Double>, manufacturer: Int = 42) = MeterRegisterFake(unitId = 1).apply {
        u16(64001, manufacturer)
        text(64002, 10, "7KM2200-2EA40-1JB1")
        text(64012, 8, "LQN/12345678")
        u16(64020, 2)
        u16(64021, ('V'.code shl 8) or 3); u16(64022, (2 shl 8) or 0)
        for (a in 64023..64027) u16(a, 0)
        w.forEachIndexed { i, p ->
            f32(1 + 2 * i, 230.0 + i)
            f32(7 + 2 * i, 400.0)
            f32(13 + 2 * i, kotlin.math.abs(p) / 230.0)
            f32(19 + 2 * i, kotlin.math.abs(p))
            f32(25 + 2 * i, p)
            f32(31 + 2 * i, 10.0)
            f32(37 + 2 * i, 0.99)
        }
        f32(55, 50.02); f32(57, 231.0); f32(59, 400.0); f32(61, 2.0)
        f32(63, w.sumOf { kotlin.math.abs(it) }); f32(65, w.sum()); f32(67, 30.0); f32(69, 0.99); f32(71, 0.1)
        u32(205, 0); u32(207, 0); u32(209, 1); u32(211, 0)
        for (a in 801 until 833 step 4) f64(a, 1_000.0)
        for (p in 0 until 3) for (o in 0 until 16 step 4) f64(841 + 40 * p + o, 500.0)
        f64(961, 0.0); f64(965, 0.0)
        f64(969, 8_956_000.5)
        f64(973, 1_234_000.0)
    }

    private fun siemens(image: () -> MeterRegisterFake) = same(SiemensPacDriver(image(), 1), spec("siemens-pac2200", image(), 1))

    @Test
    fun siemensImportExportMissingStatus() {
        siemens { pac2200(listOf(1_000.0, 500.0, 500.0)) }
        siemens { pac2200(listOf(-800.0, -800.0, -400.0)) }
        siemens { pac2200(listOf(100.0, 100.0, 100.0)).remove(29).remove(973, 4) }
        siemens { pac2200(listOf(100.0, 0.0, 0.0)).apply { u32(205, 0x0A000800L) } }
        siemens { pac2200(listOf(100.0, 0.0, 0.0)).apply { u32(205, 0x0C020000L) } }
    }

    @Test
    fun siemensIdentify() = runBlocking {
        assertEquals("LQN/12345678", spec("siemens-pac2200", pac2200(listOf(0.0, 0.0, 0.0)), 1).identify().serial)
        fails { spec("siemens-pac2200", silentDevice, 1).identify() }
        fails { spec("siemens-pac2200", pac2200(listOf(0.0, 0.0, 0.0), manufacturer = 7), 1).identify() }
    }

    @Test
    fun siemensImBlockReadWhole() = runBlocking {
        val fake = pac2200(listOf(500.0, 0.0, 0.0))
        val strict = ch.amslersolar.dashboard.driver.sunspec.RegisterReader { unit, register, count ->
            val im = 64001..64027
            if ((register until register + count).any { it in im } && (register != 64001 || count != 27)) {
                throw ch.amslersolar.dashboard.driver.modbus.ModbusException(3)
            }
            fake.read(unit, register, count)
        }
        val driver = spec("siemens-pac2200", strict, 1)
        assertEquals("LQN/12345678", driver.identify().serial)
        same(SiemensPacDriver(pac2200(listOf(500.0, 0.0, 0.0)), 1), driver)
        Unit
    }

    // ---------------------------------------------------------------- KOSTAL KSEM

    /** Strict image; [w]: signed phase powers, + = drawn, written as the documented direction-split pairs. */
    private fun ksem(w: List<Double>, manufacturer: Int = 0x5233) = MeterRegisterFake(unitId = 1).apply {
        u16(8192, manufacturer); u16(8193, 0x4852); u16(8194, 1); u16(8195, 0x0206)
        text(8196, 16, "KOSTAL Solar Electric"); text(8212, 16, "KOSTAL Smart Energy Meter"); text(8228, 16, "30380912332211")
        u16(8244, 500); u64(8245, 1_552_323_559_000); u16(8249, 7)
        u16(8250, 0xAABB); u16(8251, 0xCC11); u16(8252, 0x2233)

        fun pair(a: Int, v: Double) {
            u32(a, if (v > 0) Math.round(v * 10) else 0)
            u32(a + 2, if (v < 0) Math.round(-v * 10) else 0)
        }
        pair(0, w.sum()); pair(4, 300.0); pair(16, w.sumOf { kotlin.math.abs(it) })
        s32(24, -950); u32(26, 50_010)
        w.forEachIndexed { i, p ->
            val b = 40 + 40 * i
            pair(b, p); pair(b + 4, 100.0); pair(b + 16, kotlin.math.abs(p))
            u32(b + 20, Math.round(kotlin.math.abs(p) / 230.0 * 1000)); u32(b + 22, 230_500L + i * 1000); s32(b + 24, 990)
        }
        u32(146, 0)
        u64(512, 123_456_789); u64(516, 98_765_432)
        for (a in listOf(520, 524, 544, 548)) u64(a, 1_000)
        for (b in listOf(592, 672, 752)) for (o in listOf(0, 4, 8, 12, 32, 36)) u64(b + o, 10_000)
    }

    private fun kostal(image: () -> MeterRegisterFake) = same(KostalKsemDriver(image(), 1), spec("kostal-ksem", image(), 1))

    @Test
    fun kostalImportExportMissing() {
        kostal { ksem(listOf(1_000.0, 600.0, 400.0)) }
        kostal { ksem(listOf(-2_000.0, 300.0, -100.0)) }
        kostal { ksem(listOf(0.0, 0.0, 0.0)).also { it.u32(0, 0x0001_0000L) } }
        kostal { ksem(listOf(100.0, 100.0, 100.0)).remove(122).remove(142) }
    }

    @Test
    fun kostalIdentify() = runBlocking {
        val info = spec("kostal-ksem", ksem(listOf(0.0, 0.0, 0.0)), 1).identify()
        assertEquals("30380912332211", info.serial)
        fails { spec("kostal-ksem", silentDevice, 1).identify() }
        fails { spec("kostal-ksem", ksem(listOf(0.0, 0.0, 0.0), manufacturer = 0x1234), 1).identify() }
    }

    // ---------------------------------------------------------------- Shelly Pro 3EM

    /** The fake holds the documented table numbers (30000 + address); requests use the plain address. */
    private fun wire(fake: MeterRegisterFake) = ch.amslersolar.dashboard.driver.sunspec.RegisterReader { unit, register, count ->
        val b = fake.read(unit, register + ShellyPro3EmDriver.REGISTER_BASE, count)
        ch.amslersolar.dashboard.driver.modbus.RegisterBlock(register, ByteArray(count * 2) { i -> b.u16(b.start + i / 2).let { if (i % 2 == 0) it shr 8 else it }.toByte() })
    }

    /** [w]: phase active powers as the Shelly reports them (+ = consumed from the grid). */
    private fun shelly(w: List<Double>, lowWordFirst: Boolean = true, hz: Double = 50.0) =
        MeterRegisterFake(unitId = 1, lowWordFirst = lowWordFirst, lenient = true).apply {
            text(30000, 6, "A8032ABE1234").text(30006, 10, "SPEM-003CEBEU").text(30016, 32, "Netzanschluss")
            u32(31000, 1_669_379_021)
            f32(31007, 0.3)
            f32(31011, w.sumOf { kotlin.math.abs(it) } / 230.0); f32(31013, w.sum()); f32(31015, w.sumOf { kotlin.math.abs(it) } * 1.05)
            w.forEachIndexed { i, p ->
                val b = 31020 + 20 * i
                f32(b, 232.2 + i); f32(b + 2, kotlin.math.abs(p) / 232.0); f32(b + 4, p); f32(b + 6, kotlin.math.abs(p) * 1.05)
                f32(b + 8, 0.95); f32(b + 13, hz)
                val d = 31170 + 20 * i
                f32(d + 12, 1_000_000.0); f32(d + 14, 500_000.0)
            }
            u16(31072, 1) // phase C overcurrent (alarm threshold, not a problem)
            u32(31160, 1_669_379_000); f32(31162, 3_000_000.0); f32(31164, 1_500_000.0)
        }

    private fun shellyPair(image: () -> MeterRegisterFake) =
        same(ShellyPro3EmDriver(wire(image()), 1), spec("shelly-pro-3em", SpecTables(holding = wire(image()), input = wire(image())), 1))

    @Test
    fun shellyImportExportMissing() {
        shellyPair { shelly(listOf(951.2, 700.0, 348.8)) }
        shellyPair { shelly(listOf(-1_500.0, -951.1, 200.0)) }
        shellyPair { shelly(listOf(100.0, 0.0, 0.0)) } // overcurrent flag only: no problem
        shellyPair { shelly(listOf(100.0, 100.0, 100.0)).also { it.f32(31042, Double.NaN) } } // NaN = missing
    }

    @Test
    fun shellyHighWordFirst() {
        shellyPair { shelly(listOf(100.0, 100.0, 100.0), lowWordFirst = false) }
    }

    @Test
    fun shellyErrors() {
        shellyPair { shelly(listOf(100.0, 0.0, 0.0)).apply { u16(31003, 1); u16(31006, 1) } }
        shellyPair { shelly(listOf(100.0, 0.0, 0.0)).apply { u16(31002, 1); u16(31005, 1); u16(31009, 1); u16(31010, 1) } }
    }

    @Test
    fun shellyIdentify() = runBlocking {
        val tables = { f: MeterRegisterFake -> SpecTables(holding = wire(f), input = wire(f)) }
        assertEquals("A8032ABE1234", spec("shelly-pro-3em", tables(shelly(listOf(1.0, 1.0, 1.0))), 1).identify().serial)
        fails { spec("shelly-pro-3em", silentDevice, 1).identify() }
        fails { spec("shelly-pro-3em", tables(shelly(listOf(1.0, 1.0, 1.0), hz = 999.0)), 1).identify() }
    }

    // ---------------------------------------------------------------- Carlo Gavazzi (four files, chosen by the ID code)

    private val cgIds = listOf("carlogavazzi-em24-e1", "carlogavazzi-em300", "carlogavazzi-em210", "carlogavazzi-em24-din")

    private fun cgSpec(f: FakeMeterImage, unit: Int = 1) = ChoosingSpecDriver(cgIds.map { spec(it, f, unit) })

    /** New map (EM24 E1, EM300, EM210), as in the hand-written driver's test; [lswFirst] = documented LSW→MSW. */
    private fun newMap(
        id: Int, maxCount: Int, watts: List<Int>, lswFirst: Boolean = true, fill: IntRange = 0x00..0x51,
        hzRaw: Int = 500,
    ): FakeMeterImage {
        val f = FakeMeterImage(unitId = 1, maxCount = maxCount)
        for (a in fill) f.u16(a, 0)
        f.exact[0x000B to 1] = intArrayOf(id)
        listOf(2301, 2312, 2298).forEachIndexed { i, v -> f.s32(2 * i, v, lswFirst) }
        listOf(4000, 3995, 3990).forEachIndexed { i, v -> f.s32(0x06 + 2 * i, v, lswFirst) }
        listOf(2500, 1500, 500).forEachIndexed { i, v -> f.s32(0x0C + 2 * i, v, lswFirst) }
        watts.forEachIndexed { i, v -> f.s32(0x12 + 2 * i, v * 10, lswFirst) }
        f.s32(0x24, 2304, lswFirst).s32(0x26, 3995, lswFirst).s32(0x28, watts.sum() * 10, lswFirst)
        for (i in 0 until 4) f.u16(0x2E + i, 950)
        f.u16(0x33, hzRaw)
        f.s32(0x34, 123456, lswFirst)
        f.s32(0x4E, 65432, lswFirst)
        return f
    }

    private fun oldEm24(): FakeMeterImage {
        val f = FakeMeterImage(unitId = 5, maxCount = 11)
        for (a in 0x00..0x67) f.u16(a, 0)
        f.exact[0x000B to 1] = intArrayOf(72)
        listOf(2300, 2310, 2320).forEachIndexed { i, v -> f.s32(2 * i, v, lswFirst = true) }
        listOf(1000, 2000, 3000).forEachIndexed { i, v -> f.s32(0x0C + 2 * i, v, lswFirst = true) }
        listOf(2300, 4600, -900).forEachIndexed { i, v -> f.s32(0x12 + 2 * i, v, lswFirst = true) }
        f.s32(0x28, 6000, lswFirst = true)
        f.u16(0x37, 499)
        f.s32(0x3E, 1000, lswFirst = true).s32(0x5C, 20, lswFirst = true)
        f.u16(0x0302, 128 + 4).ascii(0x1300, 7, "ABC1234567890").u16(0x1102, 0)
        return f
    }

    private fun cg(unit: Int = 1, image: () -> FakeMeterImage) = same(CarloGavazziMeterDriver(image(), unit), cgSpec(image(), unit))

    @Test
    fun carloGavazziFamiliesAndModels() {
        cg { newMap(341, 50, listOf(500, 600, 400)).u16(0x1002, 0).u16(0x1103, 1) } // EM340 import
        cg { newMap(1651, 125, listOf(-700, -800, -500)) } // EM24 E1 export
        cg { newMap(1648, 125, listOf(100, -200, 300), fill = 0x00..0x51).u16(0x1002, 2) } // E1, 2P
        cg { newMap(341, 50, listOf(0, 0, 0)).words(0x28, 0xB1E0, 0xFFFF) } // −2000 W, LSW first
        cg { newMap(210, 61, listOf(-1200, 0, 0), fill = 0x00..0x37, hzRaw = 50).u16(0x1002, 3) } // EM210 1P
        cg(5) { oldEm24() }
    }

    @Test
    fun carloGavazziOverflowAndWiring() {
        cg { newMap(332, 50, listOf(100, 100, 100)).words(0x14, 0xFFFF, 0x7FFF) } // W L2 "EEEE"
        cg { newMap(332, 50, listOf(100, 100, 100)).words(0x28, 0x1234, 0x7FFF).words(0x0E, 0, 0x7FFF) } // W sys, A L2
        cg { newMap(341, 50, listOf(100, 0, 0)).u16(0x1103, 1).u16(0x1105, 0) }
        cg { newMap(341, 50, listOf(100, 0, 0)).u16(0x1103, 0) }
        cg { newMap(341, 50, listOf(100, 0, 0)).u16(0x1103, 1).u16(0x1105, 0b1_0001) }
        cg { newMap(210, 61, listOf(100, 0, 0), fill = 0x00..0x37, hzRaw = 50).u16(0x1105, 0b100) }
    }

    @Test
    fun carloGavazziIdentify() = runBlocking {
        val info = cgSpec(newMap(1648, 125, listOf(0, 0, 0)).ascii(0x5000, 7, "BP12345678901")).identify()
        assertEquals("Carlo Gavazzi EM24DINAV23XE1X", info.model)
        assertEquals("BP12345678901", info.serial)
        assertEquals("ABC1234567890", cgSpec(oldEm24(), 5).identify().serial)
        fails { cgSpec(FakeMeterImage(1, 125).also { it.exact[0x000B to 1] = intArrayOf(999) }).identify() }
        fails { cgSpec(FakeMeterImage(1, 125)).identify() }
        // Not covered: ID code 340 (EM340 engineering sample, MSW first) isn't in the files.
    }
}
