// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.huawei

import ch.amslersolar.dashboard.driver.modbus.ModbusException
import ch.amslersolar.dashboard.driver.modbus.RegisterBlock
import ch.amslersolar.dashboard.driver.sunspec.RegisterReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/** Against a register image built from the spec's tables; reads touching undefined registers fail like the inverter. */
class HuaweiSun2000DriverTest {
    private class FakeSun2000(battery: Boolean = true, meter: Boolean = true, meterStatus: Int = 1,
                              gridW: Int = -1234, batteryW: Int = 2000, status: Int = 0x0200) : RegisterReader {
        val regs = HashMap<Int, Int>()
        val reads = mutableListOf<Pair<Int, Int>>()

        init {
            val c = HuaweiSun2000Driver
            val blocks = c.IDENTITY + c.LIVE + c.DETAIL + (if (meter) c.METER else emptyList()) + (if (battery) c.BATTERY else emptyList())
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

        private fun u32(a: Int, v: Int) { regs[a] = v ushr 16 and 0xFFFF; regs[a + 1] = v and 0xFFFF }
        private fun text(a: Int, s: String, n: Int) {
            val b = s.toByteArray().copyOf(2 * n)
            repeat(n) { regs[a + it] = ((b[2 * it].toInt() and 0xFF) shl 8) or (b[2 * it + 1].toInt() and 0xFF) }
        }

        override suspend fun read(unitId: Int, register: Int, count: Int): RegisterBlock {
            reads += register to count
            if ((register until register + count).any { it !in regs }) throw ModbusException(2)
            val buf = ByteBuffer.allocate(count * 2)
            repeat(count) { buf.putShort(regs.getValue(register + it).toShort()) }
            return RegisterBlock(register, buf.array())
        }
    }

    @Test
    fun liveValuesAndScaling() = runBlocking {
        val r = HuaweiSun2000Driver(FakeSun2000(), 1).read()
        assertEquals(6125, r.pvPowerW)
        assertEquals("On-grid", r.status)
        assertEquals(45.2, r.temperatureC!!, 1e-9)
        assertEquals(listOf(Triple(1, 380.5, 8.12), Triple(2, 379.0, 8.0)), r.pvStrings.map { Triple(it.index, it.voltageV, it.currentA) })
        assertEquals(23.45, r.today.pvKwh!!, 1e-9)
        assertEquals(12345.67, r.lifetimePvKwh!!, 1e-9)
    }

    @Test
    fun batterySignIsFlippedToOurs() = runBlocking {
        val charging = HuaweiSun2000Driver(FakeSun2000(batteryW = 2000), 1).read().battery!!
        assertEquals(-2000, charging.powerW); assertEquals("Charging", charging.mode); assertEquals(65, charging.socPercent)
        val discharging = HuaweiSun2000Driver(FakeSun2000(batteryW = -1500), 1).read().battery!!
        assertEquals(1500, discharging.powerW); assertEquals("Discharging", discharging.mode)
    }

    @Test
    fun meterGridAndPhases() = runBlocking {
        val r = HuaweiSun2000Driver(FakeSun2000(gridW = -1234), 1).read()
        assertEquals(-1234, r.gridPowerW)
        assertEquals(listOf(Triple("L1", 231.1, -400), Triple("L2", 232.0, -420), Triple("L3", 229.8, -414)),
            r.phases.map { Triple(it.name, it.voltageV, it.powerW) })
        assertEquals(50.01, r.phases[0].frequencyHz, 1e-9)
    }

    @Test
    fun withoutBatteryOrMeterAndNoRepeatedRejectedReads() = runBlocking {
        val fake = FakeSun2000(battery = false, meter = false)
        val d = HuaweiSun2000Driver(fake, 1)
        val r = d.read()
        assertNull(r.battery); assertNull(r.gridPowerW); assertEquals(6125, r.pvPowerW)
        val before = fake.reads.size
        d.read()
        assertFalse((37100 to 39) in fake.reads.drop(before))
    }

    @Test
    fun offlineMeterAndUnknownStatus() = runBlocking {
        assertNull(HuaweiSun2000Driver(FakeSun2000(meterStatus = 0), 1).read().gridPowerW)
        assertEquals("Status 0x0BEE", HuaweiSun2000Driver(FakeSun2000(status = 0x0BEE), 1).read().status)
    }

    @Test
    fun detailsUseL1L2L3() = runBlocking {
        val r = HuaweiSun2000Driver(FakeSun2000(), 1).read()
        val titles = r.details.map { it.title }
        for (t in listOf("Identity", "PV inputs", "Inverter output", "Energy", "Power meter", "Battery (", "Battery unit 1", "Alarms")) {
            assertTrue(t, titles.any { it.startsWith(t) })
        }
        val labels = r.details.flatMap { s -> s.rows.map { it.first } }
        assertFalse(labels.any { "Phase A" in it || "A-B" in it })
        val meter = r.details.first { it.title.startsWith("Power meter") }.rows.toMap()
        assertEquals("5123.00 kWh", meter["Exported energy (positive active)"])
    }

    @Test
    fun blocksStayWithin125Registers() {
        val c = HuaweiSun2000Driver
        (c.IDENTITY + c.LIVE + c.METER + c.BATTERY + c.DETAIL).forEach { (_, count) -> assertTrue(count in 1..125) }
    }
}
