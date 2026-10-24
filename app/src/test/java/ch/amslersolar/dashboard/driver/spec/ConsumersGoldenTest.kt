// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.spec

import ch.amslersolar.dashboard.driver.DeviceDriver
import ch.amslersolar.dashboard.driver.modbus.ModbusException
import ch.amslersolar.dashboard.driver.modbus.RegisterBlock
import ch.amslersolar.dashboard.driver.sunspec.RegisterReader
import ch.amslersolar.dashboard.model.DeviceReading
import java.io.File
import java.util.Locale
import java.net.SocketTimeoutException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName

/**
 * The consumers' ModbusSpec drivers against their hand-written drivers, on the register images of the hand-written
 * drivers' tests: power, phases, energy and the problem kinds must match (problem texts may differ; they're printed).
 * Cases a spec can't read yet are @Ignore'd with the reason. The hand-written drivers are gone: what they read is
 * recorded in golden/ConsumersGoldenTest/old-drivers.txt, keyed `<test method>/<case>`.
 */
class ConsumersGoldenTest {
    private val library = SpecLibrary { File("src/main/assets/modbusspec/$it").readText() }

    private fun spec(id: String, tables: SpecTables, unit: Int, model: String? = null) =
        SpecDriver(library.spec(id), library.vocabulary, tables, unit, SpecDevices.hooks(id), requestGapMs = 0, model = model)

    private fun spec(id: String, f: RegisterReader, unit: Int) = spec(id, SpecTables(holding = f, input = f), unit)

    private fun essentials(r: DeviceReading) = listOf(
        "pv" to r.pvPowerW, "grid" to r.gridPowerW, "load" to r.loadPowerW,
        "battery" to r.battery?.let { listOf(it.powerW, it.socPercent) },
        "phases" to r.phases.map { listOf(it.name, "%.2f".format(Locale.ROOT, it.voltageV), it.currentA?.let { a -> "%.2f".format(Locale.ROOT, a) }, it.powerW) },
        "lifetimePv" to r.lifetimePvKwh, "todayPv" to r.today.pvKwh,
        "problemKinds" to r.problems.map { it.kind }.sorted(),
    )

    @get:Rule val name = TestName()

    private val old = OldDriverRecords("ConsumersGoldenTest")

    /** [new] must read what the hand-written driver read on the same image (recorded as `<test method>/<case>`). */
    private fun same(case: String, new: DeviceDriver) = runBlocking {
        val key = "${name.methodName}/$case"
        val b = new.read()
        assertEquals(case, old[key], essentials(b).joinToString("\n"))
        val oldTexts = old["$key:texts"]
        if (oldTexts != b.problems.map { it.text }.toString()) {
            println("$case: problem texts differ\n  old: $oldTexts\n  new: ${b.problems.map { it.text }}")
        }
    }

    /** A register image by address that rejects reads touching undefined registers; [timeouts] never answer. */
    private class Regs(private val maxCount: Int = 125) : RegisterReader {
        val regs = HashMap<Int, Int>()
        val timeouts = mutableSetOf<Int>()

        fun u16(a: Int, v: Int) = apply { regs[a] = v and 0xFFFF }
        fun tenths(a: Int, c: Double) = u16(a, Math.round(c * 10).toInt())
        fun u32(a: Int, v: Long) = apply { regs[a] = ((v shr 16) and 0xFFFF).toInt(); regs[a + 1] = (v and 0xFFFF).toInt() }
        fun u32lo(a: Int, v: Long) = apply { regs[a] = (v and 0xFFFF).toInt(); regs[a + 1] = ((v shr 16) and 0xFFFF).toInt() }

        override suspend fun read(unitId: Int, register: Int, count: Int): RegisterBlock {
            assertEquals(1, unitId)
            assertTrue("query of $count registers", count <= maxCount)
            if ((register until register + count).any { it in timeouts }) throw SocketTimeoutException("Read timed out")
            val data = ByteArray(count * 2)
            for (i in 0 until count) {
                val v = regs[register + i] ?: throw ModbusException(2)
                data[2 * i] = (v shr 8).toByte()
                data[2 * i + 1] = v.toByte()
            }
            return RegisterBlock(register, data)
        }
    }

    // ---------------------------------------------------------------- Alfen Eve (socket registers)

    private fun alfen(lowWordFirst: Boolean = true, shift: Int = 0, mode3: String = "C2", power: Double = 7_200.0): FakeRegisters {
        val f = FakeRegisters(lowWordFirst)
        fun at(a: Int) = a + shift
        f.on(200).nan(at(100), 79)
            .text(at(100), 17, "ALF_1000").text(at(117), 5, "Alfen B.V.").u16(at(122), 1).text(at(123), 17, "6.4.0-4210")
            .text(at(140), 17, "NG910").text(at(157), 11, "ACE0012345").u16(at(178), 60)
        f.u32(at(174), 0).u32(at(176), 0).u16(at(174), 0).u16(at(175), 0)
        f.f32(at(1100), 16.0).f32(at(1102), 42.5).u16(at(1104), 1).u16(at(1105), 1)
        f.on(1).nan(at(300), 94).u16(at(300), 3).u16(at(305), 0)
            .f32(at(306), 230.0).f32(at(308), 231.0).f32(at(310), 229.0)
            .f32(at(320), 10.4).f32(at(322), 10.5).f32(at(324), 10.6)
            .f32(at(336), 50.0).f32(at(338), 2_400.0).f32(at(340), 2_400.0).f32(at(342), 2_400.0).f32(at(344), power)
            .f64(at(374), 1_234_567.0).f64(at(390), 1_000.0)
            .nan(at(1200), 16).u16(at(1200), 1).text(at(1201), 5, mode3).f32(at(1206), 16.0).f32(at(1212), 6.0).u16(at(1215), 3)
        return f
    }

    private fun alfenSame(case: String, make: () -> FakeRegisters, unit: Int = 1) =
        same(case, spec("alfen-eve-socket", make(), unit))

    @Test
    fun alfenBothWordOrdersStatesAndNanSum() {
        for (low in listOf(true, false)) alfenSame("alfen low=$low", { alfen(lowWordFirst = low) })
        for (m in listOf("A", "B1", "C1")) alfenSame("alfen $m", { alfen(mode3 = m, power = 0.0) })
        alfenSame("alfen NaN sum", { alfen().on(1).nan(344, 2) })
        alfenSame("alfen meter warning + error", { alfen().on(1).u16(300, 0x0C) })
        alfenSame("alfen socket 2", { alfen().also { it.units[2] = HashMap(it.units[1]!!) } }, unit = 2)
    }

    @Ignore("Format gap: the guide's 'subtract 1' addresses (detected by the old driver from where 'Alfen' sits) can't be expressed; the socket file has no identify to choose by")
    @Test
    fun alfenShiftedAddresses() = alfenSame("alfen shifted", { alfen(lowWordFirst = false, shift = -1) })

    @Test
    fun alfenErrorState() {
        alfenSame("alfen F", { alfen(mode3 = "F", power = 0.0) })
        alfenSame("alfen F + meter", { alfen(mode3 = "F", power = 0.0).on(1).u16(300, 0x0B) })
    }

    // ---------------------------------------------------------------- Bender ECU (MENNEKES AMTRON)

    private fun ecu(ocpp: Int = 6, vehicle: Int = 3, power: Long = 11_000) = FakeRegisters().on(1)
        .text(100, 2, "5.22").u16(104, ocpp).u32(105, 0).u32(107, 0).u32(109, 0).u32(111, 0)
        .text(120, 2, "0.9c").u16(122, vehicle).u16(124, 1).u16(130, 0).u16(131, 6).u16(132, 60).u16(134, 32)
        .text(142, 10, "CC612_2S0R").u16(152, 1)
        .u32(200, 0xFFFFFFFFL).u32(202, 0xFFFFFFFFL).u32(204, 0xFFFFFFFFL)
        .u32(206, power / 3).u32(208, power / 3).u32(210, power / 3)
        .u32(212, 16_000).u32(214, 16_100).u32(216, 15_900)
        .u32(218, 4_321_000).u32(220, power).u32(222, 230).u32(224, 231).u32(226, 229)
        .u16(600, 0)
        .u16(705, 5_000).u16(706, 16).u16(709, 1_800).u16(712, 6).u16(715, 32).u32(716, 5_000).u32(718, 1_800)
        .u16(1000, 32)

    private fun benderSame(case: String, make: () -> FakeRegisters) = same(case, spec("bender-ecu", make(), 1))

    @Test
    fun benderChargingOldFirmwareStatesAndOcppFault() {
        benderSame("bender charging", { ecu() })
        benderSame("bender < 5.22", {
            ecu().remove(218, 10).remove(716, 4).remove(142, 11).remove(134)
                .u32(200, 1_000_000).u32(206, 7_000).u32(208, 0xFFFFFFFFL).u32(210, 0xFFFFFFFFL).u16(705, 1_234)
        })
        benderSame("bender idle", { ecu(ocpp = 0, vehicle = 1, power = 0) })
        benderSame("bender connected", { ecu(ocpp = 5, vehicle = 2, power = 0) })
        benderSame("bender suspended", { ecu(ocpp = 7, vehicle = 2, power = 0) })
        benderSame("bender backend", { ecu(ocpp = 0, vehicle = 1, power = 0).u16(111, 0x8000).u16(112, 0) })
        benderSame("bender OCPP 4", { ecu(ocpp = 4, vehicle = 1, power = 0) })
        benderSame("bender vehicle E", { ecu(ocpp = 0, vehicle = 5, power = 0) })
    }

    @Test
    fun benderErrorBits() = benderSame("bender RCM + welded", { ecu(ocpp = 4, vehicle = 1, power = 0).u16(111, 0x4100).u16(112, 0) })

    @Ignore("Format gap: no FC04 fallback when holding registers answer 'illegal function'")
    @Test
    fun benderInputRegisterFallback() {
        val holding = FakeRegisters().apply { failWith = 1 }
        same("bender FC04", spec("bender-ecu", SpecTables(holding = holding, input = ecu()), 1))
    }

    // ---------------------------------------------------------------- go-eCharger

    private fun goe(car: Int = 2, powerCw: Long = 1_104_000, lowWordFirst: Boolean = false) = FakeRegisters(lowWordFirst).on(1)
        .u16(100, car).u16(101, 32).text(105, 2, "56.8").u16(107, 0)
        .u32(108, 230).u32(110, 231).u32(112, 229)
        .u32(114, 160).u32(116, 161).u32(118, 159)
        .u32(120, powerCw).u32(128, 3_605).u32(132, 360_000)
        .u32(144, 0).u32(146, 37).u32(148, 37).u32(150, 36)
        .u32(152, 99).u32(154, 99).u32(156, 98).u32(158, 0)
        .u16(200, 1).u16(202, 0).u16(203, 0).u16(205, 0b111111)
        .text(304, 6, "012345678901").text(310, 5, "go-e-012345")

    private fun goeSame(case: String, make: () -> FakeRegisters) = same(case, spec("goe-charger", make(), 1))

    @Test
    fun goeChargingWordOrdersStatesAndErrors() {
        goeSame("go-e charging", { goe() })
        goeSame("go-e low word first", { goe(lowWordFirst = true) })
        for (car in 1..4) goeSame("go-e car $car", { goe(car = car, powerCw = 0) })
        goeSame("go-e missing power", { goe().remove(120, 2).remove(146, 6) })
        for (e in listOf(1, 8, 12)) goeSame("go-e error $e", { goe(car = 1, powerCw = 0).u16(107, e) })
        goeSame("go-e car 0", { goe(car = 0, powerCw = 0) })
        goeSame("go-e car 0 + error", { goe(car = 0, powerCw = 0).u16(107, 3) })
    }

    @Test
    fun goeSwappedBytes() = goeSame("go-e bytes swapped", {
        goe().also { f -> for (m in f.units.values) m.replaceAll { _, v -> ((v and 0xFF) shl 8) or (v shr 8) } }
    })

    // ---------------------------------------------------------------- my-PV AC ELWA / AC•THOR

    private fun mypv(serial: String, status: Int, power: Int, setpoint: Int = power): FakeRegisters {
        val f = FakeRegisters().on(1)
        for (a in 1000..1032) f.u16(a, 0)
        for (a in 1041..1088) f.u16(a, 0)
        return f.u16(1000, setpoint).u16(1001, 553).u16(1002, 600).u16(1003, status).u16(1006, 450)
            .u16(1015, 381).u16(1016, 203).u16(1017, 105).u16(1028, 3).text(1018, 8, serial)
            .u16(1082, power).u16(1083, power).u16(1084, 0).u16(1069, (-1_500) and 0xFFFF).u32(1087, -1_500L and 0xFFFFFFFFL)
    }

    private fun mypvSame(case: String, make: () -> FakeRegisters) = same(case, spec("mypv-elwa-acthor", make(), 1))

    @Test
    fun mypvModelsPowerFallbacksAndFaults() {
        mypvSame("AC•THOR heating", { mypv("2001001234567890", status = 9, power = 2_100).u16(1061, 232).u16(1062, 91).u16(1064, 50_010) })
        mypvSame("AC•THOR 9s", { mypv("2003001234567890", status = 9, power = 0).u16(1061, 230).u16(1067, 231).u16(1068, 0).u16(1072, 229).u16(1073, 0) })
        mypvSame("AC•THOR 9s outputs", { mypv("2003001234567890", 9, 0).remove(1082).u16(1074, 1_000).u16(1075, 900).u16(1076, 800) })
        mypvSame("AC•THOR no 1082", { mypv("2001001234567890", 9, 0, setpoint = 1_234).remove(1082).u16(1074, 1_000) })
        mypvSame("ELWA 2 heating", { mypv("1601501234567890", status = 2, power = 1_800).u16(1061, 231) })
        mypvSame("ELWA 2 done", { mypv("1601501234567890", status = 5, power = 0) })
        mypvSame("ELWA 2 standby", { mypv("1601501234567890", status = 3, power = 0) })
        mypvSame("ELWA 2 outputs", { mypv("1601501234567890", status = 2, power = 0).remove(1082).u16(1074, 1_500).u16(1075, 300) })
        mypvSame("ELWA-E setpoint", { mypv("1601241234567890", status = 4, power = 0, setpoint = 2_500).remove(1082).u16(1006, 45) })
        mypvSame("missing temperature", { mypv("2001001234567890", status = 9, power = 0).remove(1001) })
        mypvSame("AC•THOR 201", { mypv("2001001234567890", status = 201, power = 0) })
        mypvSame("ELWA 2 201", { mypv("1601501234567890", status = 201, power = 0) })
        mypvSame("ELWA-E 205", { mypv("1601241234567890", status = 205, power = 0) })
    }

    @Test
    fun mypvIdentify() = runBlocking {
        val info = spec("mypv-elwa-acthor", mypv("2001001234567890", 9, 0), 1).identify()
        assertEquals("my-PV AC•THOR (also CH and i)", info.model)
        assertEquals("2001001234567890", info.serial)
        val noSerial = FakeRegisters().on(1).u16(1003, 2).text(1018, 8, "NOT A SERIAL").u16(1013, 1).u16(1014, 0).u16(1015, 0).u16(1016, 0)
        for (f in listOf(noSerial, FakeRegisters())) {
            try {
                spec("mypv-elwa-acthor", f, 1).identify()
                throw AssertionError("identify should fail")
            } catch (_: java.io.IOException) {
            }
        }
    }

    // ---------------------------------------------------------------- STIEBEL ELTRON ISG (WPM)

    private class Isg {
        val input = Regs()
        val holding = Regs()
        fun inp(doc: Int, v: Int) = apply { input.u16(doc - 1, v) }
        fun hold(doc: Int, v: Int) = apply { holding.u16(doc - 1, v) }
        fun temp(doc: Int, c: Double) = inp(doc, Math.round(c * 10).toInt())
    }

    private fun wpm3(status: Int = (1 shl 0) or (1 shl 4) or (1 shl 6)) = Isg().apply {
        for (a in 501..548) inp(a, 0x8000)
        for (a in 2501..2506) inp(a, 0)
        for (a in 3501..3516) inp(a, 0)
        temp(507, 2.5).temp(515, 35.2).temp(516, 30.1).temp(522, 48.6).temp(523, 50.0).temp(518, 34.0).temp(536, -1.5)
        inp(520, 152)
        inp(2501, status)
        inp(2570, 0).inp(2571, 12).inp(2572, 4).inp(2573, 1)
        inp(5001, 2).inp(5002, 390)
        hold(1501, 2)
        inp(3501, 25).inp(3504, 5).inp(3511, 6).inp(3514, 2)
        inp(3502, 345).inp(3503, 12).inp(3505, 600).inp(3506, 3).inp(3512, 100).inp(3513, 4).inp(3515, 150).inp(3516, 1)
    }

    private fun stiebelSame(case: String, make: () -> Isg) {
        val b = make()
        same(case, spec("stiebel-wpm", SpecTables(holding = b.holding, input = b.input), 1))
    }

    @Test
    fun stiebelStatesFaultsAndMissingRegisters() {
        stiebelSame("WPM 3 heating", { wpm3() })
        stiebelSame("hot water", { wpm3(status = (1 shl 5) or (1 shl 6)) })
        stiebelSame("defrost", { wpm3(status = 1 shl 9).inp(2506, 1) })
        stiebelSame("idle", { wpm3(status = 0) })
        stiebelSame("standby", { wpm3(status = 0).hold(1501, 1) })
        stiebelSame("cooling", { wpm3(status = (1 shl 8) or (1 shl 6)) })
        stiebelSame("fault + CAN", { wpm3().inp(2504, 1).inp(2505, -3) })
        stiebelSame("bus fault", { wpm3().inp(2505, -1) })
        stiebelSame("bus line", { wpm3().inp(2505, -4) })
        stiebelSame("WPM 3i without 542..548", {
            wpm3().inp(5002, 391).also { i -> for (a in 542..548) i.input.regs.remove(a - 1); i.temp(609, 33.3); i.input.regs.remove(515 - 1) }
        })
        stiebelSame("WPMsystem", { wpm3().inp(5002, 449) })
        stiebelSame("no 5002", { wpm3().also { it.input.regs.remove(5002 - 1) } })
    }

    @Test
    fun stiebelIdentify() = runBlocking {
        val i = wpm3()
        assertEquals("STIEBEL ELTRON WPM 3", spec("stiebel-wpm", SpecTables(holding = i.holding, input = i.input), 1).identify().model)
        for (other in listOf(Isg(), wpm3().inp(5002, 103))) {
            try {
                spec("stiebel-wpm", SpecTables(holding = other.holding, input = other.input), 1).identify()
                throw AssertionError("identify should fail")
            } catch (_: java.io.IOException) {
            }
        }
    }

    @Test
    fun stiebelIdentifyWithoutControllerCode() = runBlocking {
        val i = wpm3().also { it.input.regs.remove(5002 - 1) }
        spec("stiebel-wpm", SpecTables(holding = i.holding, input = i.input), 1).identify()
        Unit
    }

    // ---------------------------------------------------------------- NIBE S-series

    /** nibe-s-series has `model_choice: user`: every model the user can pick. */
    private val nibeModels = library.spec("nibe-s-series").models.map { it.id }

    private class Nibe {
        val input = Regs(maxCount = 20)
        val holding = Regs(maxCount = 20)
        fun inp(a: Int, v: Int) = apply { input.u16(a, v) }
        fun temp(a: Int, c: Double) = inp(a, Math.round(c * 10).toInt())
    }

    private fun s1255(priority: Int = 30) = Nibe().apply {
        for (a in 1..20) inp(a, 0x8000)
        temp(1, -3.4).temp(5, 38.5).temp(7, 32.0).temp(8, 51.2).temp(9, 49.0).temp(10, 4.1).temp(11, 1.2)
        temp(26, 21.5).temp(39, 0.0).temp(40, 0.0)
        inp(1017, 390); input.u32lo(1025, 1234); inp(1028, priority).inp(1029, 0)
        inp(1046, 523)
        input.u32lo(1083, 4321).u32lo(1087, 9876)
        inp(1100, 1).inp(1101, 0).inp(1102, 64)
        input.u32lo(1575, 123_456).u32lo(1577, 987_654).u32lo(1581, 0).u32lo(1583, 100_000).u32lo(1585, 800_000)
        inp(1975, 0).inp(2014, 450)
        input.u32lo(2166, 18_500)
        inp(2195, 0).inp(2196, 0)
        holding.u32lo(11, -2_500L and 0xFFFFFFFFL).u16(237, 0)
    }

    private fun nibeSame(case: String, make: () -> Nibe) {
        assertEquals(9, nibeModels.size)
        for (m in nibeModels) {
            val b = make()
            same("$m $case", spec("nibe-s-series", SpecTables(holding = b.holding, input = b.input), 1, model = m))
        }
    }

    @Test
    fun nibeEveryFileStatesAndAlarm() {
        nibeSame("heating", { s1255() })
        for (p in listOf(10, 20, 40, 60)) nibeSame("priority $p", { s1255(priority = p) })
        nibeSame("defrost", { s1255().inp(1803, 450).inp(1805, 1).temp(1621, -5.0) })
        nibeSame("alarm", { s1255().inp(2195, 1).inp(1975, 183) })
        nibeSame("BT1 not connected", { s1255().inp(1, 0x8000).temp(1621, 7.5) })
    }

    @Test
    fun nibeIdentifyWithoutOutdoorSensor() = runBlocking {
        val n = s1255().inp(1, 0x8000)
        spec("nibe-s-series", SpecTables(holding = n.holding, input = n.input), 1, model = "s1155").identify()
        Unit
    }

    // ---------------------------------------------------------------- Hoval TopTronic E

    private fun ultraSource(wfa: Int = 1, generator: Int = 1, sf2TimesOut: Boolean = true) = Regs().apply {
        tenths(1477, -2.5)
        for (a in 1499..1504) u16(a, 0)
        tenths(1499, 50.0).tenths(1500, 47.5).u16(1501, 1).u16(1504, 1)
        for (a in 1513..1519) u16(a, 0)
        tenths(1513, 34.0).u32(1516, 12_345).u32(1518, 6_789)
        u16(1534, 255).tenths(1535, 29.5).u16(1561, 1)
        for (a in 18722..18737) u16(a, 0)
        u16(18722, generator).u16(18723, wfa).tenths(18724, 35.0).tenths(18725, 34.6).u16(18726, 55).u16(18737, 1)
        u32(18731, 70)
        u16(25611, 185).u16(25612, 7).u32(25613, 4_200)
        u32(27484, 12_600).u32(27486, 0).u32(27488, 2_100).u16(27490, 38)
        u16(27467, 41).tenths(27494, 36.0).tenths(31708, 18.5)
        if (sf2TimesOut) timeouts += 27483
    }

    private fun hovalSame(case: String, make: () -> Regs) = same(case, spec("hoval-toptronic-e", make(), 1))

    @Test
    fun hovalStatesAndProblemsWithoutTimeouts() {
        fun u(wfa: Int = 1, generator: Int = 1) = ultraSource(wfa, generator, sf2TimesOut = false)
        hovalSame("heating", { u() })
        for (w in listOf(4, 8, 0)) hovalSame("wfa $w", { u(wfa = w, generator = if (w == 0) 0 else 1) })
        hovalSame("wfa 11 gen 16", { u(wfa = 11, generator = 16) })
        hovalSame("gen 16", { u(wfa = 0, generator = 16) })
        hovalSame("W17", { u().u16(1534, 80) })
        hovalSame("low pressure", { u(wfa = 12, generator = 16).u16(1534, 203).u16(1504, 7) })
        hovalSame("W11", { u().u16(1534, 74) })
        hovalSame("not available", { u().u16(1477, 0x8000).also { it.regs.remove(27490) } })
    }

    @Test
    fun hovalOptionalSensorTimesOut() = hovalSame("heating, SF2 times out", { ultraSource() })

    @Test
    fun hovalUnlistedErrorCode() = hovalSame("gen 16 + code 77", { ultraSource(wfa = 0, generator = 16, sf2TimesOut = false).u16(1534, 77) })
}
