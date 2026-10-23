// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.sma

import ch.amslersolar.dashboard.driver.modbus.ModbusException
import ch.amslersolar.dashboard.driver.modbus.RegisterBlock
import ch.amslersolar.dashboard.driver.sunspec.RegisterReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SmaDriverTest {
    /**
     * A register image that behaves like the SMA Modbus server: a block with at least one defined register is
     * answered, its undefined registers read as NaN of their type; a block without any defined register is rejected
     * with exception 2.
     */
    private class FakeSma : RegisterReader {
        val regs = HashMap<Int, Int>()
        val requests = mutableListOf<Int>()

        fun u32(a: Int, v: Long) = apply { regs[a] = ((v shr 16) and 0xFFFF).toInt(); regs[a + 1] = (v and 0xFFFF).toInt() }
        fun s32(a: Int, v: Int) = u32(a, v.toLong() and 0xFFFFFFFFL)
        fun u64(a: Int, v: Long) = u32(a, v ushr 32).u32(a + 2, v and 0xFFFFFFFFL)
        fun text(a: Int, n: Int, s: String) = apply {
            val padded = s.padEnd(n * 2, '\u0000')
            for (i in 0 until n) regs[a + i] = (padded[2 * i].code shl 8) or padded[2 * i + 1].code
        }

        override suspend fun read(unitId: Int, register: Int, count: Int): RegisterBlock {
            assertEquals(3, unitId)
            assertTrue(count in 1..125)
            requests += register
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
            /** S32 registers in the SMA sheets (NaN 0x8000 0000 instead of 0xFFFF FFFF). */
            val S32 = setOf(
                30769, 30771, 30773, 30775, 30777, 30779, 30781, 30805, 30807, 30809, 30811, 30813, 30815, 30817,
                30819, 30829, 30831, 30843, 30849, 30857, 30861, 30865, 30867, 30869, 30873, 30909, 30911, 30913,
                30953, 30957, 30959, 30961, 30975, 30977, 30979, 30981, 30985, 30997, 30999, 31159, 31221, 31247,
                31411, 31417, 31419, 31421, 31423, 31425, 31427, 31429, 31431,
            )
        }
    }

    private fun identity(fake: FakeSma, type: Int, serial: Long, cls: Long = 8001) = fake
        .u32(30051, cls).u32(30053, type.toLong()).u32(30057, serial).u32(30059, 0x02510A04) // 2.51.10.R

    /** STP 10000TL-20, two strings, feeding in 6 kW, MPP. */
    private fun tripower() = identity(FakeSma(), 9281, 2_130_012_345)
        .u32(30201, 307).u32(30217, 51).u32(30219, 884).u32(30225, 3_500_000)
        .u64(30513, 45_678_900).u64(30517, 23_450)
        .s32(30769, 8_500).s32(30771, 61_234).s32(30773, 5_200) // DC1 8.5 A, 612.34 V, 5200 W
        .s32(30957, 1_600).s32(30959, 58_000).s32(30961, 930) // DC2
        .s32(30775, 6_000).s32(30777, 2_000).s32(30779, 2_000).s32(30781, 2_000)
        .u32(30783, 23_100).u32(30785, 23_200).u32(30787, 23_000).u32(30803, 5_001)
        .s32(30977, 8_660).s32(30979, 8_620).s32(30981, 8_700)
        .s32(30953, 452) // 45.2 °C
        .u32(30583, 40_000_000)
        .u32(40029, 295) // MPP
        .text(31017, 8, "192.168.1.50")

    /** Sunny Boy Smart Energy 5000SE: PV, integrated battery charging, SMA Energy Meter (exporting 1.2 kW). */
    private fun smartEnergy() = identity(FakeSma(), 9225, 1_900_123_456)
        .u32(30201, 307)
        .u64(30513, 9_000_000).u64(30517, 12_300)
        .s32(30769, 10_000).s32(30771, 40_000).s32(30773, 4_000)
        .s32(30775, 1_800).s32(30777, 1_800).u32(30783, 23_000).u32(30803, 4_998)
        .s32(30843, 5_000).u32(30845, 64).u32(30847, 98).u32(30851, 44_000) // 5 A × 440 V
        .u32(30955, 2292) // battery charging
        .s32(30865, 0).s32(30867, 1_200)
        .u32(30581, 1_000_000).u32(30583, 2_000_000)
        .u32(40187, 6_000) // 6 kWh

    /** Sunny Island 4.4M: battery discharging 1.5 kW, house 2.1 kW, drawing 600 W from the grid. */
    private fun sunnyIsland() = identity(FakeSma(), 9279, 1_800_000_001)
        .u32(30201, 307)
        .u64(30513, 3_000_000)
        .s32(30775, 1_500).s32(30777, 1_500).u32(30783, 23_050).u32(30803, 5_000)
        .s32(30843, -3_000).u32(30845, 81).u32(30851, 50_200).s32(30849, 215)
        .s32(30861, 2_100).s32(30865, 600).s32(30867, 0).s32(30869, 0)
        .u32(30577, 4_200).u32(30579, 1_100)
        .u32(31393, 0).u32(31395, 1_500)
        .u64(31397, 1_234_000).u64(31401, 1_100_000)
        .u32(40031, 100).u32(40037, 48)

    @Test
    fun identifiesTheDeviceType() = runBlocking {
        val info = SmaDriver(tripower(), 3).identify()
        assertEquals("SMA STP 10000TL-20", info.model)
        assertEquals("2130012345", info.serial)
    }

    @Test
    fun unknownTypeStillIdentifies() = runBlocking {
        val info = SmaDriver(identity(FakeSma(), 9999, 42, cls = 8009), 3).identify()
        assertEquals("SMA device type 9999", info.model)
        assertEquals("42", info.serial)
    }

    @Test
    fun nonSmaDevicesAreRejected() {
        // Nothing at 30051: exception 2.
        val empty = FakeSma().u32(100, 1)
        assertTrue(runCatching { runBlocking { SmaDriver(empty, 3).identify() } }.exceptionOrNull() is IOException)
        // Answers, but only NaN for type and serial.
        val nan = FakeSma().u32(30059, 1)
        assertTrue(runCatching { runBlocking { SmaDriver(nan, 3).identify() } }.exceptionOrNull() is IOException)
        // Something that isn't an SMA device class.
        val other = identity(FakeSma(), 9076, 1, cls = 3)
        assertTrue(runCatching { runBlocking { SmaDriver(other, 3).identify() } }.exceptionOrNull() is IOException)
    }

    @Test
    fun pvInverterWithStrings() = runBlocking {
        val r = SmaDriver(tripower(), 3).read()
        assertEquals(6_130, r.pvPowerW) // DC1 + DC2
        assertEquals(2, r.pvStrings.size)
        assertEquals(612.34, r.pvStrings[0].voltageV, 1e-6)
        assertEquals(8.5, r.pvStrings[0].currentA, 1e-6)
        assertEquals(930, r.pvStrings[1].powerW)
        assertNull(r.battery)
        assertNull(r.gridPowerW) // no meter registers
        assertNull(r.loadPowerW)
        assertEquals(listOf("L1", "L2", "L3"), r.phases.map { it.name })
        assertEquals(231.0, r.phases[0].voltageV, 1e-6)
        assertEquals(8.66, r.phases[0].currentA!!, 1e-6)
        assertEquals(50.01, r.phases[0].frequencyHz, 1e-6)
        assertEquals(2_000, r.phases[2].powerW)
        assertEquals(45.2, r.temperatureC!!, 1e-6)
        assertEquals("MPP", r.status)
        assertEquals(23.45, r.today.pvKwh!!, 1e-6)
        assertNull(r.today.exportKwh)
        assertEquals(45_678.9, r.lifetimePvKwh!!, 1e-6)

        val titles = r.details.map { it.title }
        assertTrue(titles.containsAll(listOf("Device", "Status", "AC", "PV", "Energy")))
        assertTrue("Battery" !in titles && "Energy meter" !in titles && "Sunny Island" !in titles)
        val device = r.details.first { it.title == "Device" }.rows.toMap()
        assertEquals("2.51.10.R", device["Software package"])
        assertEquals("192.168.1.50", device["IP address"])
        val status = r.details.first { it.title == "Status" }.rows.toMap()
        assertEquals("Closed (51)", status["Grid relay"])
        assertEquals("Ok (307)", status["Condition"])
        assertEquals("3500 kΩ", r.details.first { it.title == "PV" }.rows.toMap()["Insulation resistance"])
    }

    @Test
    fun hybridWithBatteryAndMeter() = runBlocking {
        val r = SmaDriver(smartEnergy(), 3).read()
        assertEquals(4_000, r.pvPowerW)
        assertEquals(-2_200, r.battery?.powerW) // 5 A × 440 V, 30955 = charging
        assertEquals("Charging", r.battery?.mode)
        assertEquals(64, r.battery?.socPercent)
        assertEquals(440.0, r.battery?.voltageV!!, 1e-6)
        assertEquals(1_200, r.gridPowerW) // feed-in 1200 − reference 0
        assertNull(r.loadPowerW)
        assertEquals("Ok", r.status) // no 40029: condition
        assertEquals(listOf("L1"), r.phases.map { it.name }) // single phase: L2 / L3 voltage NaN
        val titles = r.details.map { it.title }
        assertTrue(titles.containsAll(listOf("Battery", "Energy meter")))
        val battery = r.details.first { it.title == "Battery" }.rows.toMap()
        assertEquals("6.0 kWh", battery["Nominal capacity"])
        assertEquals("Battery charging (2292)", battery["Operating status"])
        assertEquals("1000.0 kWh", r.details.first { it.title == "Energy meter" }.rows.toMap()["Grid reference counter"])
    }

    @Test
    fun batteryDirectionFromStatusOrCurrent() = runBlocking {
        val discharging = smartEnergy().u32(30955, 2293).s32(30865, 300).s32(30867, 0)
        val r = SmaDriver(discharging, 3).read()
        assertEquals(2_200, r.battery?.powerW)
        assertEquals("Discharging", r.battery?.mode)
        assertEquals(-300, r.gridPowerW) // importing

        val standby = smartEnergy().u32(30955, 2291)
        assertEquals(0, SmaDriver(standby, 3).read().battery?.powerW)

        // No usable status: the current's sign decides (unverified: positive = charging).
        val noStatus = smartEnergy().u32(30955, 0xFFFFFD).s32(30843, -2_500)
        assertEquals(1_100, SmaDriver(noStatus, 3).read().battery?.powerW)
    }

    @Test
    fun sunnyIslandBatteryInverter() = runBlocking {
        val r = SmaDriver(sunnyIsland(), 3).read()
        assertNull(r.pvPowerW) // battery inverter: PV comes from the PV inverters
        assertEquals(1_500, r.battery?.powerW)
        assertEquals("Discharging", r.battery?.mode)
        assertEquals(81, r.battery?.socPercent)
        assertEquals(-600, r.gridPowerW)
        assertEquals(2_100, r.loadPowerW)
        assertEquals(4.2, r.today.importKwh!!, 1e-6)
        assertEquals(1.1, r.today.exportKwh!!, 1e-6)
        assertNull(r.today.pvKwh)
        assertNull(r.lifetimePvKwh)
        val titles = r.details.map { it.title }
        assertTrue(titles.containsAll(listOf("Battery", "Energy meter", "Sunny Island")))
        val battery = r.details.first { it.title == "Battery" }.rows.toMap()
        assertEquals("1234.0 kWh", battery["Charged energy"])
        assertEquals("21.5 °C", battery["Temperature"])
        assertEquals("2100 W", r.details.first { it.title == "Sunny Island" }.rows.toMap()["Load power"])
    }

    @Test
    fun missingBlocksAreSkippedUntilTheStaticRefresh() = runBlocking {
        val fake = tripower()
        val driver = SmaDriver(fake, 3)
        driver.read()
        // Nothing defined in the residual current, battery or Sunny Island battery rating blocks on this Tripower.
        assertTrue(fake.requests.containsAll(listOf(31221, 31391, 40031)))
        fake.requests.clear()
        driver.read()
        assertTrue(31221 !in fake.requests && 31391 !in fake.requests)
        assertTrue(fake.requests.containsAll(listOf(30197, 30513, 30769, 30883, 31001, 40029)))
        assertTrue(30051 !in fake.requests) // static data cached
        repeat(SmaDriver.STATIC_REFRESH_POLLS - 2) { driver.read() }
        fake.requests.clear()
        driver.read()
        assertTrue(fake.requests.containsAll(listOf(30051, 31221, 31391, 40031)))
    }

    @Test
    fun nanValuesAreNotReported() = runBlocking {
        // Night: the inverter answers, but DC and AC values are NaN and the status is "information not available".
        val fake = identity(FakeSma(), 9076, 7)
            .u32(30201, 0xFFFFFD).u32(30217, 311)
            .s32(30769, Int.MIN_VALUE).s32(30771, Int.MIN_VALUE).s32(30773, Int.MIN_VALUE)
            .s32(30775, Int.MIN_VALUE).u32(30783, 0xFFFFFFFFL).u32(30803, 0xFFFFFFFFL)
            .s32(30953, Int.MIN_VALUE)
            .u64(30513, -1L).u64(30517, -1L)
        val r = SmaDriver(fake, 3).read()
        assertEquals(0, r.pvPowerW)
        assertTrue(r.pvStrings.isEmpty())
        assertTrue(r.phases.isEmpty())
        assertNull(r.temperatureC)
        assertNull(r.status)
        assertNull(r.today.pvKwh)
        assertNull(r.lifetimePvKwh)
        assertNull(r.battery)
        assertNull(r.gridPowerW)
        val status = r.details.first { it.title == "Status" }.rows.toMap()
        assertNull(status["Condition"])
        assertEquals("Open (311)", status["Grid relay"])
        assertTrue(r.details.none { it.title == "Energy" })
    }

    @Test
    fun otherModbusErrorsFailThePoll() {
        val failing = object : RegisterReader {
            override suspend fun read(unitId: Int, register: Int, count: Int): RegisterBlock {
                if (register == 30051) return tripower().read(unitId, register, count)
                throw ModbusException(6) // server busy
            }
        }
        assertTrue(runCatching { runBlocking { SmaDriver(failing, 3).read() } }.exceptionOrNull() is ModbusException)
    }

    @Test
    fun keepsTenSecondsBetweenTransfers() = runBlocking {
        var requests = 0
        val fake = tripower()
        val counting = RegisterReader { u, r, c -> requests++; fake.read(u, r, c) }
        var now = 0L
        val driver = SmaDriver(counting, 3, SmaDriver.MIN_INTERVAL_MS) { now }
        val first = driver.read()
        val afterFirst = requests
        now = 5_000
        assertTrue(driver.read() === first) // within 10 s: the last reading, no request
        assertEquals(afterFirst, requests)
        now = 10_000
        driver.read()
        assertTrue(requests > afterFirst)
    }
}
