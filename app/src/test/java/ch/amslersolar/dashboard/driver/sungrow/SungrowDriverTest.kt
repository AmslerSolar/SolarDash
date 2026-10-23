// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.sungrow

import ch.amslersolar.dashboard.driver.modbus.ModbusException
import ch.amslersolar.dashboard.driver.modbus.RegisterBlock
import ch.amslersolar.dashboard.driver.sunspec.RegisterReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class SungrowDriverTest {
    /**
     * Register image by the specs' 1-based register numbers. Requests arrive at number − 1 (as the specs require);
     * any request touching a register outside [inputDefined] / [holdingDefined] fails with exception 2, so the tests
     * also check that the driver only reads documented runs. 32-bit values are stored low word first.
     */
    private class FakeSungrow(private val inputDefined: List<IntRange>, private val holdingDefined: List<IntRange>) {
        val inp = HashMap<Int, Int>()
        val hold = HashMap<Int, Int>()
        val requests = mutableListOf<Triple<String, Int, Int>>() // (fc, wire address, count)

        fun u16(r: Int, v: Int, regs: HashMap<Int, Int> = inp) = apply { regs[r] = v and 0xFFFF }
        fun s16(r: Int, v: Int) = u16(r, v)
        fun u32(r: Int, v: Long) = apply { u16(r, (v and 0xFFFF).toInt()); u16(r + 1, (v shr 16).toInt()) }
        fun s32(r: Int, v: Int) = u32(r, v.toLong() and 0xFFFFFFFFL)
        fun text(r: Int, n: Int, s: String) = apply {
            val b = s.toByteArray(Charsets.US_ASCII).copyOf(n * 2)
            for (i in 0 until n) u16(r + i, ((b[2 * i].toInt() and 0xFF) shl 8) or (b[2 * i + 1].toInt() and 0xFF))
        }

        private fun serve(fc: String, regs: Map<Int, Int>, defined: List<IntRange>, unitId: Int, wire: Int, count: Int): RegisterBlock {
            assertEquals(1, unitId)
            assertTrue("max 125 registers", count in 1..125)
            requests += Triple(fc, wire, count)
            val data = ByteArray(count * 2)
            for (i in 0 until count) {
                val r = wire + 1 + i // back to the spec's numbering
                if (defined.none { r in it }) throw ModbusException(2)
                val v = regs[r] ?: 0
                data[2 * i] = (v shr 8).toByte(); data[2 * i + 1] = v.toByte()
            }
            return RegisterBlock(wire, data)
        }

        val input = RegisterReader { u, r, c -> serve("04", inp, inputDefined, u, r, c) }
        val holding = RegisterReader { u, r, c -> serve("03", hold, holdingDefined, u, r, c) }
        fun driver() = SungrowDriver(input, holding, 1)
    }

    private val hybridInput = listOf(
        4950..5030, 5033..5036, 5115..5133, 5214..5215, 5242..5242, 5601..5608, 5622..5623, 5628..5628, 5631..5631,
        5635..5636, 5639..5639, 5720..5727, 5731..5734, 13000..13014, 13017..13079, 13200..13207, 13250..13369,
    )
    private val hybridHolding = listOf(13050..13052, 13058..13059, 13074..13075, 13086..13090, 13100..13100)
    private val stringInput = listOf(4950..5151, 7013..7036)
    private val stringHolding = listOf(5000..5042)

    /** SH10RT with a battery and a smart meter, 3P4L. */
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

    @Test
    fun identifiesHybrid() = runBlocking {
        val dev = sh10rt()
        val info = dev.driver().identify()
        assertEquals("SH10RT", info.model)
        assertEquals("A2231234567", info.serial)
        assertEquals(Triple("04", 4989, 41), dev.requests.single()) // 4990 sent as 4989
    }

    @Test
    fun identifiesStringInverter() = runBlocking {
        val info = sg10rt().driver().identify()
        assertEquals("SG10RT-20 / SG10RT", info.model)
        assertEquals("B2219876543", info.serial)
    }

    @Test
    fun rejectsDevicesThatAreNotSungrow() = runBlocking {
        // Answers, but with a type code that isn't in either spec.
        val other = FakeSungrow(stringInput, stringHolding).text(4990, 10, "XYZ1234567").u16(5000, 0x1234)
        assertThrows { other.driver().identify() }
        // Known code but no readable serial number.
        val noSerial = FakeSungrow(stringInput, stringHolding).u16(5000, 0x2433)
        assertThrows { noSerial.driver().identify() }
        // No such input registers at all (e.g. SunSpec-only device).
        val none = FakeSungrow(emptyList(), emptyList())
        assertThrows { none.driver().identify() }
    }

    @Test
    fun acceptsUnlistedCodeInSungrowHybridRange() = runBlocking {
        val dev = FakeSungrow(hybridInput, hybridHolding).text(4990, 10, "A9999999999").u16(5000, 0xE99)
        assertEquals("Sungrow hybrid (type 0x0E99)", dev.driver().identify().model)
    }

    @Test
    fun hybridChargingAndExporting() = runBlocking {
        val dev = sh10rt().apply {
            u16(13001, 0x1B) // PV, charging, load, feed-in
            s32(5214, 2000)
            s32(5601, -1500) // < 0 sell to grid
            s32(5603, -500); s32(5605, -500); s32(5607, -500)
            s32(13008, 2500)
        }
        val r = dev.driver().read()
        assertEquals(6000, r.pvPowerW)
        assertEquals(listOf(350.0, 340.0), r.pvStrings.map { it.voltageV })
        assertEquals(9.2, r.pvStrings[0].currentA, 1e-9)
        val bat = r.battery!!
        assertEquals(-2000, bat.powerW) // charging → negative
        assertEquals("Charging", bat.mode)
        assertEquals(66, bat.socPercent) // 65.5 %
        assertEquals(401.2, bat.voltageV!!, 1e-9)
        assertEquals(1500, r.gridPowerW) // exporting → positive
        assertEquals(2500, r.loadPowerW)
        assertEquals(42.5, r.temperatureC!!, 1e-9)
        assertEquals("Running (on-grid)", r.status)
        assertEquals(21.5, r.today.pvKwh!!, 1e-9)
        assertEquals(3.4, r.today.exportKwh!!, 1e-9)
        assertEquals(1.2, r.today.importKwh!!, 1e-9)
        assertNull(r.today.loadKwh)
        assertEquals(12345.6, r.lifetimePvKwh!!, 1e-6) // 32-bit, low word first
        assertEquals(listOf("L1", "L2", "L3"), r.phases.map { it.name })
        assertEquals(230.1, r.phases[0].voltageV, 1e-9)
        assertEquals(8.8, r.phases[2].currentA!!, 1e-9)
        assertEquals(50.01, r.phases[0].frequencyHz, 1e-9)
        val inverter = r.details.first { it.title == "Inverter" }.rows.toMap()
        assertEquals("0x0E03", inverter["Device type code (5000)"])
        assertEquals("PV generating, Battery charging, Load (positive), Feeding in (0x001B)", inverter["Power flow (13001)"])
        val ac = r.details.first { it.title == "AC output" }.rows.toMap()
        assertEquals("73215.0 kWh", ac["Output energy total"]) // the hybrid spec's own example §4.2
        val settings = r.details.first { it.title == "Settings" }.rows.toMap()
        assertEquals("Self-consumption (0)", settings["EMS mode"])
        assertEquals("100.0 %", settings["Max. SOC"])
    }

    @Test
    fun hybridDischargingAndImporting() = runBlocking {
        val dev = sh10rt().apply {
            u16(13001, 0x2D) // PV, discharging, load, import
            s32(5214, -1800) // sign of 5214 is undocumented: only its magnitude is used
            s32(5601, 700) // > 0 buy from grid
            s32(13008, 3100)
        }
        val r = dev.driver().read()
        assertEquals(1800, r.battery!!.powerW)
        assertEquals("Discharging", r.battery!!.mode)
        assertEquals(-700, r.gridPowerW)
        assertEquals(3100, r.loadPowerW)
    }

    @Test
    fun hybridWithFourMppts() = runBlocking {
        val dev = sh10rt().apply {
            u16(5000, 0xD1A) // SH8.0RS: 4 MPPTs
            u16(5015, 3300); u16(5016, 50); u16(5115, 3200); u16(5116, 40)
        }
        val r = dev.driver().read()
        assertEquals(listOf(1, 2, 3, 4), r.pvStrings.map { it.index })
        assertEquals(320.0, r.pvStrings[3].voltageV, 1e-9)
        assertTrue(dev.requests.any { it == Triple("04", 5114, 19) })
    }

    @Test
    fun hybridWithoutMeterAndNotAvailableValues() = runBlocking {
        val dev = sh10rt(withMeter = false).apply {
            u16(13001, 0x01)
            s16(5008, 0x7FFF) // S16 not available
            u16(13023, 0xFFFF) // U16 not available
            u32(13003, 0xFFFFFFFFL) // U32 not available
        }
        val r = dev.driver().read()
        assertNull(r.gridPowerW)
        assertNull(r.loadPowerW)
        assertNull(r.today.exportKwh)
        assertNull(r.today.importKwh)
        assertNull(r.temperatureC)
        assertNull(r.lifetimePvKwh)
        assertEquals(0, r.battery!!.powerW)
        assertEquals("Idle", r.battery!!.mode)
        assertNull(r.battery!!.socPercent)

        // A meter that is present in the map but not connected reports the S32 marker.
        val markers = sh10rt().apply { for (reg in listOf(5601, 5603, 5605, 5607)) s32(reg, Int.MAX_VALUE) }
        val m = markers.driver().read()
        assertNull(m.gridPowerW)
        assertNull(m.loadPowerW)
    }

    @Test
    fun hybridPollsStayWithinDocumentedRegistersAndFewRequests() = runBlocking {
        val dev = sh10rt().apply { u16(13001, 0x01) }
        val driver = dev.driver()
        driver.read()
        assertTrue(dev.requests.any { it.first == "03" }) // settings once at start
        repeat(3) {
            dev.requests.clear()
            driver.read()
            assertTrue("requests per poll: ${dev.requests.size}", dev.requests.size <= 7)
            assertTrue(dev.requests.none { it.first == "03" }) // RW registers are not polled
        }
    }

    /** SG10RT, 3P4L, with smart meter. */
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

    @Test
    fun stringInverterWithMeter() = runBlocking {
        val dev = sg10rt().apply { s32(5083, -1000); s32(5091, 3200) } // load = 4200 − 1000 export
        val driver = dev.driver()
        val r = driver.read()
        assertEquals(4200, r.pvPowerW)
        assertEquals(listOf(600.0, 590.0), r.pvStrings.map { it.voltageV })
        assertEquals(1000, r.gridPowerW)
        assertEquals(3200, r.loadPowerW)
        assertNull(r.battery)
        assertEquals("Run", r.status)
        assertEquals(31.2, r.today.pvKwh!!, 1e-9)
        assertEquals(8.5, r.today.exportKwh!!, 1e-9)
        assertEquals(2.0, r.today.importKwh!!, 1e-9)
        assertEquals(98765.4, r.lifetimePvKwh!!, 1e-6)
        assertEquals(3, r.phases.size)
        assertEquals(6.2, r.phases[2].currentA!!, 1e-9)
        assertEquals(49.98, r.phases[0].frequencyHz, 1e-9)
        val inverter = r.details.first { it.title == "Inverter" }.rows.toMap()
        assertEquals("Run, Total run (0x00020001)", inverter["Work state 2 (5081)"])
        val settings = r.details.first { it.title == "Settings" }.rows.toMap()
        assertEquals("Enabled", settings["Power limitation"])
        assertEquals("70.0 %", settings["Power limitation setting"])

        // Two requests per poll (4990–5114, 5115–5151) plus at most one detail block; no RW reads.
        dev.requests.clear()
        driver.read()
        assertTrue(dev.requests.size <= 3)
        assertEquals(Triple("04", 4989, 125), dev.requests[0])
        assertEquals(Triple("04", 5114, 37), dev.requests[1])
        assertTrue(dev.requests.none { it.first == "03" })
    }

    @Test
    fun stringInverterMeterSignFollowsEnergyBalance() = runBlocking {
        // 5083's sign is undocumented: here the device reports +1000 while exporting (load = 4200 − 1000).
        val dev = sg10rt().apply { s32(5083, 1000); s32(5091, 3200) }
        val r = dev.driver().read()
        assertEquals(1000, r.gridPowerW)
        assertEquals("> 0 export", r.details.first { it.title.startsWith("Grid meter") }.rows.toMap()["Sign used for 5083 (undocumented)"])
    }

    @Test
    fun stringInverterWithoutMeter() = runBlocking {
        val dev = sg10rt().apply {
            u16(5000, 0x2606); u16(5002, 0) // SG5.0RS, single phase
            for (reg in listOf(5083, 5085, 5087, 5089, 5091)) s32(reg, Int.MAX_VALUE)
            u32(5093, 0xFFFFFFFFL); u32(5097, 0xFFFFFFFFL)
            u32(5144, 0xFFFFFFFFL) // → total from 5004 (kWh)
        }
        val r = dev.driver().read()
        assertNull(r.gridPowerW)
        assertNull(r.loadPowerW)
        assertNull(r.today.exportKwh)
        assertNull(r.today.importKwh)
        assertEquals(9876.0, r.lifetimePvKwh!!, 1e-9)
        assertEquals(listOf("L1"), r.phases.map { it.name })
        assertEquals(6.1, r.phases[0].currentA!!, 1e-9)
        assertEquals("SG5.0RS", dev.driver().identify().model)
    }

    @Test
    fun stringInverterFaultCode() = runBlocking {
        val dev = sg10rt().apply {
            u16(5038, 0x5500); u16(5039, 2026); u16(5040, 10); u16(5041, 21); u16(5042, 12); u16(5043, 30); u16(5044, 5)
            u16(5045, 2)
        }
        val r = dev.driver().read()
        assertEquals("Fault: Grid overvoltage (2)", r.status)
        val inverter = r.details.first { it.title == "Inverter" }.rows.toMap()
        assertEquals("2026-10-21 12:30:05", inverter["Fault/alarm time"])
        assertEquals("System fault", SungrowDriver.faultName(1100))
        assertEquals("Unknown code", SungrowDriver.faultName(9999))
    }

    @Test
    fun supportedTypeCodesSeparateTheFamilies() {
        val codes = SungrowDriver.SUPPORTED_TYPE_CODES
        assertTrue(0xE03 in codes && 0x2433 in codes)
        assertEquals(SungrowDriver.Family.HYBRID, SungrowDriver.modelFor(0xE28)!!.family)
        assertEquals("SH25T", SungrowDriver.modelFor(0xE28)!!.name)
        assertEquals(SungrowDriver.Family.STRING, SungrowDriver.modelFor(0x2C02)!!.family)
        assertNotNull(SungrowDriver.modelFor(0x0132)) // SG60KU-M, the grid-connected spec's example
        assertNull(SungrowDriver.modelFor(0x1234))
    }

    private suspend fun assertThrows(block: suspend () -> Unit) {
        try {
            block()
            fail("expected IOException")
        } catch (_: IOException) {
        }
    }
}
