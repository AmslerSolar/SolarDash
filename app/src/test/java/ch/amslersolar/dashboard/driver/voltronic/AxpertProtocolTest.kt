// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.voltronic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AxpertProtocolTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    private fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    /** A reply as the inverter frames it: "(" + data + CRC over "(" + data (stuffed) + CR. */
    private fun reply(data: String): ByteArray {
        val body = ascii("($data")
        return body + AxpertProtocol.crcBytes(body) + bytes(0x0D)
    }

    @Test
    fun crcIsCrc16Xmodem() {
        // The documents have no worked CRC example. cal_crc_half (appendix 4.1) is CRC-16 poly 0x1021, init 0,
        // unreflected: the catalogue check value of CRC-16/XMODEM for "123456789" is 0x31C3.
        assertEquals(0x31C3, AxpertProtocol.crc16(ascii("123456789")))
        assertEquals(0, AxpertProtocol.crc16(ByteArray(0)))
    }

    @Test
    fun commandFrames() {
        // Computed with the table algorithm; QPIGS B7 A9 and QMOD 49 C1 are the frames widely used by Axpert tools.
        assertArrayEquals(ascii("QPIGS") + bytes(0xB7, 0xA9, 0x0D), AxpertProtocol.frame("QPIGS"))
        assertArrayEquals(ascii("QMOD") + bytes(0x49, 0xC1, 0x0D), AxpertProtocol.frame("QMOD"))
        assertArrayEquals(ascii("QPIRI") + bytes(0xF8, 0x54, 0x0D), AxpertProtocol.frame("QPIRI"))
        assertArrayEquals(ascii("QID") + bytes(0xD6, 0xEA, 0x0D), AxpertProtocol.frame("QID"))
    }

    @Test
    fun crcBytesEqualToParenCrOrLfAreIncremented() {
        // Raw CRCs (no stuffing): QED20240218 0x0DFD, QLD20240218 0x28F3, QLD20240225 0xAC0D, QLD20240402 0x0828.
        assertEquals(0x0DFD, AxpertProtocol.crc16(ascii("QED20240218")))
        assertArrayEquals(bytes(0x0E, 0xFD), AxpertProtocol.crcBytes(ascii("QED20240218")))
        assertEquals(0x28F3, AxpertProtocol.crc16(ascii("QLD20240218")))
        assertArrayEquals(bytes(0x29, 0xF3), AxpertProtocol.crcBytes(ascii("QLD20240218")))
        assertEquals(0xAC0D, AxpertProtocol.crc16(ascii("QLD20240225")))
        assertArrayEquals(bytes(0xAC, 0x0E), AxpertProtocol.crcBytes(ascii("QLD20240225")))
        assertArrayEquals(bytes(0x08, 0x29), AxpertProtocol.crcBytes(ascii("QLD20240402")))
        assertEquals(0x0B, AxpertProtocol.stuff(0x0A))
        assertEquals(0x41, AxpertProtocol.stuff(0x41))
        // The frame of such a command contains exactly one CR, at the end.
        val f = AxpertProtocol.frame("QLD20240225")
        assertEquals(1, f.count { it == 0x0D.toByte() })
    }

    @Test
    fun replyIsUnwrapped() {
        assertEquals("L", AxpertProtocol.parseReply("QMOD", reply("L")))
        assertEquals("PI30", AxpertProtocol.parseReply("QPI", reply("PI30")))
        // Leftover bytes before the frame are ignored; the CR may be missing from the buffer.
        assertEquals("B", AxpertProtocol.parseReply("QMOD", bytes(0x12, 0x00) + reply("B")))
        assertEquals("B", AxpertProtocol.parseReply("QMOD", reply("B").copyOf(4)))
    }

    @Test
    fun nakWithAndWithoutCrc() {
        // "(NAK" has CRC 0x7373 ("ss"), the familiar "(NAKss" reply.
        assertArrayEquals(bytes(0x73, 0x73), AxpertProtocol.crcBytes(ascii("(NAK")))
        assertThrows(AxpertNakException::class.java) { AxpertProtocol.parseReply("QPIGS2", ascii("(NAKss\r")) }
        assertThrows(AxpertNakException::class.java) { AxpertProtocol.parseReply("QET", ascii("(NAK\r")) }
    }

    @Test
    fun badFramesAreRejected() {
        val r = reply("230.0 50.0")
        r[r.size - 2] = (r[r.size - 2] + 1).toByte()
        assertThrows(AxpertFrameException::class.java) { AxpertProtocol.parseReply("QPIGS", r) }
        assertThrows(AxpertFrameException::class.java) { AxpertProtocol.parseReply("QPIGS", ascii("230.0 50.0\r")) }
        assertThrows(AxpertFrameException::class.java) { AxpertProtocol.parseReply("QMOD", ascii("(\r")) }
    }

    @Test
    fun qpigsFullReply() {
        val g = AxpertProtocol.parseQpigs(
            "231.4 49.9 230.1 50.0 0920 0850 018 380 52.10 012 078 0035 08.5 310.2 52.08 00000 00010110 00 00 02650 010 0 00 0000 00.0",
        )
        assertEquals(231.4, g.gridVoltageV, 1e-9)
        assertEquals(49.9, g.gridFrequencyHz, 1e-9)
        assertEquals(850, g.outputActiveW)
        assertEquals(920, g.outputApparentVa)
        assertEquals(78, g.batteryCapacityPercent)
        assertEquals(8.5, g.pvCurrentA, 1e-9)
        assertEquals(310.2, g.pvVoltageV, 1e-9)
        assertEquals(2650, g.pvPowerW)
        assertEquals(35, g.heatSinkTemperature)
        assertTrue(g.loadOn)
        assertTrue(g.charging)
        assertTrue(g.sccCharging)
        assertFalse(g.acCharging)
        assertEquals(true, g.switchOn)
        assertEquals(false, g.floatCharging)
        // 52.10 V × (0 − 12 A) = −625 W: charging is negative in our convention.
        assertEquals(-625, g.batteryPowerW)
    }

    @Test
    fun qpigsDischargingAndShortReply() {
        // Older firmware: only up to the status bits (KS field list without the later fields).
        val g = AxpertProtocol.parseQpigs("000.0 00.0 230.0 50.0 1150 1100 022 360 50.40 000 064 0040 0000 000.0 00.00 00023 00010000")
        assertEquals(1159, g.batteryPowerW) // 50.40 V × 23 A
        assertNull(g.pvPowerW)
        assertNull(g.switchOn)
        assertFalse(g.charging)
        assertThrows(AxpertFrameException::class.java) { AxpertProtocol.parseQpigs("231.4 49.9 230.1") }
        assertThrows(AxpertFrameException::class.java) { AxpertProtocol.parseQpigs("PI30") }
    }

    @Test
    fun qpigs2() {
        val p = AxpertProtocol.parseQpigs2("05.2 285.3 01480")
        assertEquals(5.2, p.pvCurrentA, 1e-9)
        assertEquals(285.3, p.pvVoltageV, 1e-9)
        assertEquals(1480, p.pvPowerW)
    }

    @Test
    fun qpiriKsAndMaxII() {
        val ks = AxpertProtocol.parseQpiri(
            "230.0 21.7 230.0 50.0 21.7 5000 4000 48.0 46.0 42.0 56.4 54.0 0 30 060 0 2 3 9 01 0 0 54.0 0 1 000",
        )
        assertEquals(5000, ks.outputRatedApparentVa)
        assertEquals(4000, ks.outputRatedActiveW)
        assertEquals(48.0, ks.batteryRatedVoltageV, 1e-9)
        assertEquals(60, ks.maxChargeA)
        assertEquals(2, ks.outputSourcePriority)
        assertEquals("01", ks.machineType)
        assertEquals(26, ks.fieldCount)
        assertFalse(ks.looksLikeMaxII)
        assertNull(ks.maxDischargeA)

        val max = AxpertProtocol.parseQpiri(
            "230.0 34.7 230.0 50.0 34.7 8000 8000 48.0 48.0 45.0 56.4 54.0 8 040 120 0 2 1 9 01 0 0 52.0 0 1 480 0 150",
        )
        assertTrue(max.looksLikeMaxII)
        assertEquals(150, max.maxDischargeA)
        assertEquals(8, max.batteryType)
        assertEquals(0, max.operationLogic)
        assertThrows(AxpertFrameException::class.java) { AxpertProtocol.parseQpiri("NAK") }
    }

    @Test
    fun modes() {
        assertEquals("Line", AxpertProtocol.parseMode("L"))
        assertEquals("Battery", AxpertProtocol.parseMode("B"))
        assertEquals("Power saving", AxpertProtocol.parseMode("H"))
        assertEquals("Fault", AxpertProtocol.parseMode("F"))
        assertEquals("ECO", AxpertProtocol.parseMode("E"))
        assertEquals("Unknown (X)", AxpertProtocol.parseMode("X"))
        assertNull(AxpertProtocol.parseMode("LB"))
    }

    @Test
    fun warnings() {
        assertEquals(emptyList<String>(), AxpertProtocol.parseWarnings("00000000000000000000000000000000"))
        // a5 line fail, a10 fan locked (a1 not set: warning), a12 battery low.
        assertEquals(
            listOf("Line fail", "Fan locked (warning)", "Battery low alarm"),
            AxpertProtocol.parseWarnings("00000100001010000000000000000000"),
        )
        // a1 inverter fault + a9 over temperature → fault.
        assertEquals(listOf("Inverter fault", "Over temperature (fault)"), AxpertProtocol.parseWarnings("01000000010000000000000000000000"))
        // 36 bits: MAX table, a0 is PV loss.
        assertEquals(listOf("PV loss"), AxpertProtocol.parseWarnings("100000000000000000000000000000000000"))
        assertThrows(AxpertFrameException::class.java) { AxpertProtocol.parseWarnings("NAK") }
    }

    @Test
    fun smallReplies() {
        assertEquals(30, AxpertProtocol.parseProtocolId("PI30"))
        assertNull(AxpertProtocol.parseProtocolId("230.0 50.0"))
        assertEquals("92931509101901", AxpertProtocol.parseSerial("92931509101901"))
        assertNull(AxpertProtocol.parseSerial("230.0 50.0"))
        assertEquals("00072.70", AxpertProtocol.parseFirmware("VERFW:00072.70"))
        assertEquals(12_345_678L, AxpertProtocol.parseEnergyWh("12345678"))
        assertNull(AxpertProtocol.parseEnergyWh("NAK"))
        assertEquals("QED20261021", AxpertProtocol.pvOfDay(LocalDate.of(2026, 10, 21)))
        assertEquals("QLD20260102", AxpertProtocol.loadOfDay(LocalDate.of(2026, 1, 2)))
    }
}
