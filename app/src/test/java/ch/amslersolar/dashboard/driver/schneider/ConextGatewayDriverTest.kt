// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.schneider

import ch.amslersolar.dashboard.driver.sunspec.FakeRegisterImage
import ch.amslersolar.dashboard.model.DeviceProblem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ConextGatewayDriverTest {
    /**
     * Gateway (unit 1, port 503) of a split-phase XW Pro system: 4.2 kW PV, battery charging 1.5 kW at 81 %,
     * selling 1.1 kW, home 1.6 kW. Reads may not cross 0x0050 or 0x03F8 (stand-ins for "blank row" boundaries).
     */
    private fun gateway(address: Int = 1) = FakeRegisterImage(unitId = 1, rejectAcross = setOf(0x0050, 0x03F8)).apply {
        text(0x0000, 8, "Conext Gateway"); text(0x000A, 8, "865-0329"); text(0x001E, 10, "V1.07 BN 6"); u16(0x0028, address)
        text(0x002B, 8, "00502ABCD")
        u16(0x0040, (1 shl 6) or (1 shl 7)); u16(0x0041, 0); u16(0x0042, 0); u16(0x0043, 1)
        for (a in 0x0044..0x006D) u16(a, 0)
        u32(0x0044, 4_150); u32(0x0046, 1_480); u32(0x004A, 0)
        u32(0x004C, 240_100); u32(0x004E, 6_001)
        u32(0x0052, 0); u32(0x0058, 1_100) // buying 0, selling 1.1 kW
        u32(0x0066, 1_600)
        u32(0x009C, 52_480); u32(0x009E, 29_820) // 52.48 V, 25.2 °C
        u32(0x0158, -1_500L and 0xFFFFFFFFL)
        u32(0x00A4, 1_200); u32(0x00BC, 6_400); u32(0x00D4, 2_100); u32(0x00E4, 12_345_678); u32(0x00EC, 9_870)
        u32(0x00FC, 23_456_789); u32(0x0104, 11_200); u32(0x0114, 34_567_890); u32(0x0134, 18_000); u32(0x0144, 45_000_000)
        u32(0x03A8, 120_200); u32(0x03AA, 4_500); u32(0x03AC, 120_100); u32(0x03AE, 4_600); u32(0x03B0, 0); u32(0x03B2, 0)
        u32(0x03C6, -1_500L and 0xFFFFFFFFL); u32(0x03C8, 81)
        u32(0x03F8, 1_100); u32(0x03FA, 4_200); u32(0x03FC, 0); u32(0x03FE, 1_500)
        u32(0x0414, 18_250); u32(0x041C, 45_100_000); u32(0x041E, 97)
    }

    @Test
    fun systemTotalsWithOurSigns() = runBlocking {
        val image = gateway()
        val driver = ConextGatewayDriver(image, 1)
        val info = driver.identify()
        assertEquals("Conext Gateway (865-0329)", info.model)
        assertEquals("00502ABCD", info.serial)
        val r = driver.read()
        assertEquals(4_200, r.pvPowerW) // PV total power
        assertEquals(1_100, r.gridPowerW) // selling − buying: + export
        assertEquals(-1_500, r.battery?.powerW) // inverting − charging: + discharging
        assertEquals(81, r.battery?.socPercent)
        assertEquals(52.48, r.battery?.voltageV!!, 1e-9)
        assertEquals("Charging", r.battery?.mode)
        assertEquals(1_600, r.loadPowerW)
        assertEquals(listOf("L1", "L2"), r.phases.map { it.name }) // split phase: no L3 voltage
        assertEquals(120.2, r.phases[0].voltageV, 1e-9)
        assertEquals(60.01, r.phases[0].frequencyHz, 1e-9)
        assertEquals(18.25, r.today.pvKwh!!, 1e-9)
        assertEquals(9.87, r.today.exportKwh!!, 1e-9)
        assertEquals(2.1, r.today.importKwh!!, 1e-9)
        assertEquals(11.2, r.today.loadKwh!!, 1e-9)
        assertEquals(45_100.0, r.lifetimePvKwh!!, 1e-9)
        assertEquals("Active warnings: 1", r.status)
        val battery = r.details.first { it.title == "Battery" }.rows.toMap()
        assertEquals("25.2 °C", battery["Temperature"])
        assertTrue(r.details.first { it.title == "Gateway" }.rows.toMap()["Power flow"]!!.startsWith("PV to battery, PV to grid"))
        image.requests.clear()
        assertEquals(4_200, driver.read().pvPowerW)
        assertTrue(image.requests.none { (start, count) -> 0x0050 in (start + 1) until start + count }) // rejected block not retried
    }

    @Test
    fun buyingGivesNegativeGrid() = runBlocking {
        val image = gateway().apply { u32(0x0052, 2_300); u32(0x0058, 0) }
        assertEquals(-2_300, ConextGatewayDriver(image, 1).read().gridPowerW)
    }

    @Test
    fun rejectsADeviceThatIsNotTheGateway() = runBlocking {
        val xw = runCatching { ConextGatewayDriver(gateway(address = 10), 1).identify() }.exceptionOrNull()
        assertTrue(xw is IOException)
        val empty = runCatching { ConextGatewayDriver(FakeRegisterImage(unitId = 1), 1).identify() }.exceptionOrNull()
        assertTrue(empty is IOException)
    }

    @Test
    fun withoutBatteryRegistersThereIsNoBattery() = runBlocking {
        val image = gateway().apply { listOf(0x03C8, 0x03FC, 0x03FE, 0x0046, 0x004A).forEach { regs.remove(it); regs.remove(it + 1) } }
        assertNull(ConextGatewayDriver(image, 1).read().battery)
    }

    @Test
    fun activeFaultAndWarningCountsBecomeProblems() = runBlocking {
        val ok = ConextGatewayDriver(gateway().apply { u16(0x0043, 0) }, 1).read()
        assertEquals(emptyList<DeviceProblem>(), ok.problems)
        val bad = ConextGatewayDriver(gateway().apply { u16(0x0041, 2) }, 1).read()
        assertEquals(
            listOf(DeviceProblem(DeviceProblem.Kind.FAULT, "2 active faults"), DeviceProblem(DeviceProblem.Kind.WARNING, "1 active warning")),
            bad.problems,
        )
    }
}
