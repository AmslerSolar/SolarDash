// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.driver.victron

import ch.amslersolar.solardash.driver.modbus.ModbusException
import ch.amslersolar.solardash.driver.modbus.RegisterBlock
import ch.amslersolar.solardash.driver.sunspec.RegisterReader
import ch.amslersolar.solardash.model.DeviceProblem
import ch.amslersolar.solardash.model.DeviceProblem.Kind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class VictronGxDriverTest {
    /** A register image; reads touching an address not in it fail with [code] (2 = illegal address, 0x0B = gateway). */
    private class FakeGx(private val code: Int = 2) : RegisterReader {
        val regs = HashMap<Int, Int>()
        val requests = mutableListOf<Pair<Int, Int>>()

        /** A current Venus OS: every system register, reserved ones included, answers (0 unless set). */
        fun fullRange() = apply { for (a in (800..937) + (5400..5407)) regs[a] = 0 }

        fun u16(a: Int, v: Int) = apply { regs[a] = v and 0xFFFF }
        fun s32(a: Int, v: Int) = apply { regs[a] = (v ushr 16) and 0xFFFF; regs[a + 1] = v and 0xFFFF }
        fun text(a: Int, n: Int, s: String) = apply {
            val padded = s.padEnd(n * 2, '\u0000')
            for (i in 0 until n) regs[a + i] = (padded[2 * i].code shl 8) or padded[2 * i + 1].code
        }
        fun remove(vararg a: Int) = apply { a.forEach { regs.remove(it) } }

        override suspend fun read(unitId: Int, register: Int, count: Int): RegisterBlock {
            assertEquals(100, unitId)
            assertTrue("request of $count registers", count in 1..125)
            requests += register to count
            val data = ByteArray(count * 2)
            for (i in 0 until count) {
                val v = regs[register + i] ?: throw ModbusException(code)
                data[2 * i] = (v shr 8).toByte()
                data[2 * i + 1] = v.toByte()
            }
            return RegisterBlock(register, data)
        }
    }

    private fun identity(fake: FakeGx) = fake.text(800, 6, "c0619ab1f2e3").u16(834, 0x0331).u16(835, 0)

    /**
     * Three-phase ESS: AC-coupled PV 1.5 kW on the output (L1) + 0.9 kW on the input (L2), 2 kW DC-coupled; battery
     * charging 1.2 kW; loads 1.0 kW; exporting 2.2 kW over L1–L3.
     */
    private fun threePhase() = identity(FakeGx().fullRange())
        .s32(884, 1_500).s32(892, 900).u16(850, 2_000).u16(851, 385)
        .s32(902, 400).s32(904, 300).s32(906, 300)
        .s32(908, -1_000).s32(910, -700).s32(912, -500)
        .u16(808, 1_500).u16(812, 900).u16(817, 400).u16(818, 300).u16(819, 300)
        .u16(820, -1_000).u16(821, -700).u16(822, -500)
        .u16(840, 532).u16(841, 225).u16(842, 1_200).u16(843, 67).u16(844, 1).u16(845, 123).u16(846, 0)
        .u16(826, 1).u16(930, 3)
        .u16(5400, 1).u16(5402, 1).u16(5406, 1).u16(5407, 80)

    @Test
    fun identify() = runBlocking {
        val info = VictronGxDriver(threePhase(), 100).identify()
        assertEquals("Victron Energy GX v3.31", info.model)
        assertEquals("c0619ab1f2e3", info.serial)
    }

    @Test
    fun identifyShowsBeta() = runBlocking {
        val info = VictronGxDriver(threePhase().u16(834, 0x0340).u16(835, 7), 100).identify()
        assertEquals("Victron Energy GX v3.40~7", info.model)
    }

    @Test
    fun threePhaseSystemWithAcAndDcPvChargingAndExporting() = runBlocking {
        val fake = threePhase()
        val reading = VictronGxDriver(fake, 100).read()
        assertEquals(4_400, reading.pvPowerW)
        assertEquals(2_200, reading.gridPowerW) // > 0 export
        assertEquals(1_000, reading.loadPowerW)
        val battery = reading.battery!!
        assertEquals(-1_200, battery.powerW) // < 0 charging
        assertEquals(67, battery.socPercent)
        assertEquals(53.2, battery.voltageV!!, 1e-9)
        assertEquals("Charging", battery.mode)
        assertEquals("Bulk charging", reading.status)
        assertTrue(reading.phases.isEmpty())
        assertNull(reading.today.pvKwh)
        assertNull(reading.lifetimePvKwh)

        // One request per block: static (serial, firmware, Dynamic ESS) and live (806–930 incl. reserved, 931–937).
        assertEquals(listOf(800 to 6, 834 to 2, 5400 to 8, 806 to 125, 931 to 7), fake.requests)

        val rows = reading.details.associate { s -> s.title to s.rows.toMap() }
        assertEquals("-1000 W", rows["Grid"]!!["Power (+ import) L1"])
        assertEquals("-2200 W", rows["Grid"]!!["Total (+ import)"])
        assertEquals("1500 W", rows["Solar"]!!["AC-coupled on output L1"])
        assertEquals("900 W", rows["Solar"]!!["AC-coupled on input L2"])
        assertEquals("38.5 A", rows["Solar"]!!["DC-coupled current"])
        assertEquals("22.5 A", rows["Battery"]!!["Current (+ charging)"])
        assertEquals("-12.3 Ah", rows["Battery"]!!["Consumed"])
        assertEquals("Grid (1)", rows["GX device"]!!["Active AC input"])
        assertEquals("Self-consumption (1)", rows["Dynamic ESS"]!!["Strategy"])
        assertNull(rows["Generator"])
    }

    @Test
    fun problemsAreEmptyWhenAllIsFine() = runBlocking {
        assertEquals(emptyList<DeviceProblem>(), VictronGxDriver(threePhase(), 100).read().problems)
    }

    @Test
    fun veBusFaultNoAcInputAndDynamicEssErrorAreReported() = runBlocking {
        val fake = threePhase().u16(930, 2).u16(826, 240).u16(5404, 4)
        assertEquals(
            listOf(
                DeviceProblem(Kind.FAULT, "VE.Bus fault condition (state 2)"),
                DeviceProblem(Kind.MAINS_LOST, "AC input not connected"),
                DeviceProblem(Kind.WARNING, "Dynamic ESS: SOC low (4)"),
            ),
            VictronGxDriver(fake, 100).read().problems,
        )
        // Inverting from battery while the grid is connected isn't a problem; a Dynamic ESS error while it's off neither.
        val inverting = threePhase().u16(930, 9).u16(5400, 0).u16(5404, 3)
        assertEquals(emptyList<DeviceProblem>(), VictronGxDriver(inverting, 100).read().problems)
    }

    @Test
    fun staticDataIsReadAboutOnceAMinute() = runBlocking {
        val fake = threePhase()
        val driver = VictronGxDriver(fake, 100)
        repeat(VictronGxDriver.STATIC_REFRESH_POLLS) { driver.read() }
        assertEquals(1, fake.requests.count { it.first == 800 })
        driver.read()
        assertEquals(2, fake.requests.count { it.first == 800 })
    }

    /**
     * Single phase, older firmware without the 32-bit registers (868–918) and without reserved registers; L2/L3 and
     * everything else the installation lacks are rejected with a gateway exception. DC PV only, battery discharging,
     * importing 300 W.
     */
    private fun singlePhaseOldFirmware() = identity(FakeGx(code = 0x0B))
        .u16(806, 0).u16(807, 0)
        .u16(808, 0).u16(811, 0).u16(817, 1_100).u16(820, 300).u16(826, 1)
        .u16(840, 262).u16(841, (-300).toShort().toInt()).u16(842, -800).u16(843, 45).u16(844, 2)
        .u16(850, 0)

    @Test
    fun singlePhaseSystem() = runBlocking {
        val fake = singlePhaseOldFirmware()
        val driver = VictronGxDriver(fake, 100)
        val reading = driver.read()
        assertEquals(0, reading.pvPowerW)
        assertEquals(-300, reading.gridPowerW) // importing
        assertEquals(1_100, reading.loadPowerW)
        assertEquals(800, reading.battery!!.powerW) // discharging
        assertEquals("Discharging", reading.battery!!.mode)
        assertEquals(26.2, reading.battery!!.voltageV!!, 1e-9)
        assertNull(reading.status)
        val grid = reading.details.first { it.title == "Grid" }.rows.toMap()
        assertEquals("300 W", grid["Power (+ import) L1"])
        assertNull(grid["Power (+ import) L2"])

        // Rejected blocks and fields are remembered: the next poll asks only what answered.
        val before = fake.requests.size
        driver.read()
        val second = fake.requests.drop(before)
        assertTrue(second.all { (a, n) -> (a until a + n).all { it in fake.regs } })
        assertEquals(reading, driver.read())
    }

    @Test
    fun missingValues() = runBlocking {
        // Current firmware, but no battery monitor (battery registers not available) and nothing else measured.
        val fake = identity(FakeGx().fullRange()).remove(840, 841, 842, 843, 844, 845, 846)
            .u16(851, 0x8000).u16(5402, 0)
        fake.s32(908, Int.MIN_VALUE).u16(820, 0x8000).u16(817, 0xFFFF).s32(902, -1)
        val reading = VictronGxDriver(fake, 100).read()
        assertNull(reading.battery)
        assertEquals(0, reading.pvPowerW)
        assertEquals(0, reading.gridPowerW) // L2, L3 read 0; L1 not available
        assertEquals(0, reading.loadPowerW) // L1 not available in either width
        val titles = reading.details.map { it.title }
        assertTrue("Dynamic ESS" !in titles)
        assertNull(reading.details.first { it.title == "Solar" }.rows.toMap()["DC-coupled current"])
        assertNull(reading.details.first { it.title == "Grid" }.rows.toMap()["Power (+ import) L1"])
    }

    @Test
    fun noSystemValuesIsAnError() = runBlocking {
        val fake = identity(FakeGx(code = 0x0A))
        try {
            VictronGxDriver(fake, 100).read()
            fail("expected IOException")
        } catch (_: IOException) {
        }
    }

    @Test
    fun nonVictronRejected() = runBlocking {
        // Nothing at 800.
        try {
            VictronGxDriver(FakeGx(), 100).identify()
            fail("expected IOException")
        } catch (_: IOException) {
        }
        // Something answers at 800, but it isn't a MAC address.
        val other = FakeGx().text(800, 6, "SUN2000-10KTL").u16(834, 1)
        try {
            VictronGxDriver(other, 100).identify()
            fail("expected IOException")
        } catch (_: IOException) {
        }
    }
}
