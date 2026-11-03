// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.driver.abb

import java.io.IOException
import java.nio.ByteBuffer

/** The device answered with a transmission state other than 0 (e.g. 51: command not implemented). */
class AuroraRefusedException(val transmissionState: Int, message: String) : IOException(message)

/**
 * Framing, CRC and code tables of the Power-One / ABB "Aurora" RS485 protocol (PVI, UNO, TRIO string inverters).
 *
 * Request: 10 bytes, `address, command, b2…b7, crc low, crc high`. Reply: 8 bytes, `b0…b5, crc low, crc high`;
 * for most commands b0 is the transmission state (0 = OK) and b1 the global state, the data in b2…b5. Serial
 * number (63) and part number (52) replies are 6 ASCII characters without states. CRC: CRC-16/X-25 (reflected
 * 0x1021, init and final XOR 0xFFFF) over the 8 (request) or 6 (reply) bytes before it.
 *
 * The state and alarm texts are those of the TRIO Modbus register map (assets/modbusspec/powerone-trio.json): the
 * Modbus registers mirror the Aurora codes, so the same texts and translations apply. The model code (command 58,
 * first character) is the register map's model identifier.
 */
object AuroraProtocol {
    const val STATE = 50
    const val PART_NUMBER = 52
    const val VERSION = 58
    const val MEASURE = 59
    const val SERIAL = 63
    const val MANUFACTURED = 65
    const val TIME = 70
    const val FIRMWARE = 72
    const val ENERGY = 78
    const val LAST_ALARMS = 86

    /** Command 59 measurement types (all verified on a TRIO-27.6-TL-OUTD, 2026-11-03). */
    object Measure {
        const val GRID_VOLTAGE = 1
        const val GRID_CURRENT = 2
        const val GRID_POWER = 3
        const val FREQUENCY = 4
        const val BULK_VOLTAGE = 5
        const val LEAK_CURRENT_DCDC = 6
        const val LEAK_CURRENT_INVERTER = 7
        const val INPUT1_POWER = 8
        const val INPUT2_POWER = 9
        const val INVERTER_TEMPERATURE = 21
        const val BOOSTER_TEMPERATURE = 22
        const val INPUT1_VOLTAGE = 23
        const val INPUT1_CURRENT = 25
        const val INPUT2_VOLTAGE = 26
        const val INPUT2_CURRENT = 27
        const val ISOLATION_RESISTANCE = 30
        const val POWER_PEAK = 34
        const val POWER_PEAK_TODAY = 35
        const val CURRENT_L1 = 39
        const val CURRENT_L2 = 40
        const val CURRENT_L3 = 41
        const val FREQUENCY_L1 = 42
        const val FREQUENCY_L2 = 43
        const val FREQUENCY_L3 = 44
        const val VOLTAGE_L1 = 61
        const val VOLTAGE_L2 = 62
        const val VOLTAGE_L3 = 63
    }

    /** Command 78 periods. 2 isn't a period (it answered a small, unrelated number on both TRIOs tested). */
    object Period {
        const val DAY = 0
        const val WEEK = 1
        const val MONTH = 3
        const val YEAR = 4
        const val TOTAL = 5
    }

    /** Seconds from 1970 to 2000-01-01 00:00, the epoch of the inverter's clock (command 70; the clock is local time). */
    const val EPOCH_2000 = 946_684_800L

    fun crc(data: ByteArray, length: Int = data.size): Int {
        var c = 0xFFFF
        for (i in 0 until length) {
            c = c xor (data[i].toInt() and 0xFF)
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0x8408 else c ushr 1 }
        }
        return c xor 0xFFFF
    }

    fun request(address: Int, command: Int, vararg args: Int): ByteArray {
        require(args.size <= 6)
        val frame = ByteArray(10)
        frame[0] = address.toByte()
        frame[1] = command.toByte()
        args.forEachIndexed { i, a -> frame[2 + i] = a.toByte() }
        val c = crc(frame, 8)
        frame[8] = c.toByte()
        frame[9] = (c ushr 8).toByte()
        return frame
    }

    /** True when the last 8 bytes of [buf] (of [size]) are a reply with a valid CRC. */
    fun isReply(buf: ByteArray, size: Int): Boolean {
        if (size < 8) return false
        val start = size - 8
        val c = crc(buf.copyOfRange(start, start + 6))
        return (buf[start + 6].toInt() and 0xFF) == (c and 0xFF) && (buf[start + 7].toInt() and 0xFF) == (c ushr 8)
    }

    /** The 6 data bytes of a reply whose first byte is the transmission state; throws if it isn't 0. */
    fun checked(command: Int, reply: ByteArray): ByteArray {
        val ts = reply[0].toInt() and 0xFF
        if (ts != 0) throw AuroraRefusedException(ts, "Command $command refused: ${TRANSMISSION_STATES[ts] ?: "transmission state $ts"}")
        return reply
    }

    fun ascii(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): String =
        String(bytes, from, to - from, Charsets.ISO_8859_1).trim { it <= ' ' || it == '\u0000' }

    fun float(reply: ByteArray): Float = ByteBuffer.wrap(reply, 2, 4).float

    fun uint32(reply: ByteArray): Long = ByteBuffer.wrap(reply, 2, 4).int.toLong() and 0xFFFF_FFFFL

    fun model(code: Char): String? = MODELS[code.code]

    val TRANSMISSION_STATES = mapOf(
        51 to "command not implemented", 52 to "variable does not exist", 53 to "variable value out of range",
        54 to "EEPROM not accessible", 55 to "service mode not toggled", 56 to "can't send the command to the internal micro",
        57 to "command not executed", 58 to "variable not available, retry",
    )

    /** Global states that are a fault (the TRIO register map's problem list). */
    val FAULT_STATES = setOf(9, 10, 13, 15, 16, 17, 18, 19, 20, 21, 23, 24, 26, 31, 32, 33, 35, 36, 51, 53, 115, 118)

    /** Inverter states meaning the grid is missing. */
    val GRID_LOST_INVERTER_STATES = setOf(43, 46)

    /** Alarm codes by kind; the others (sun low, input UV, bulk UV, remote off, input UC, zero power) are normal operation. */
    val FAULT_ALARMS = setOf(
        2, 4, 6, 7, 8, 9, 10, 12, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 37, 38, 39, 40,
        41, 42, 43, 44, 45, 48, 49, 51, 56, 57, 58, 59, 60, 61, 65, 66, 79, 82, 83,
    )
    val GRID_ALARMS = setOf(13, 32, 33, 34, 35, 62)
    val WARNING_ALARMS = setOf(36, 46, 47, 52, 53, 63, 64)

    val GLOBAL_STATES: Map<Int, String> = mapOf(
        0 to "Sending parameters", 1 to "Waiting for sun/grid", 2 to "Checking grid", 3 to "Measuring Riso",
        4 to "DC/DC start", 5 to "Inverter start", 6 to "Run", 7 to "Recovery", 8 to "Pause",
        9 to "Ground fault", 10 to "Over-temperature fault", 11 to "Address setting", 12 to "Self-test",
        13 to "Self-test fail", 14 to "Sensor test + Riso", 15 to "Leak fault", 16 to "Waiting for manual reset",
        17 to "Internal error E026", 18 to "Internal error E027", 19 to "Internal error E028",
        20 to "Internal error E029", 21 to "Internal error E030", 22 to "Sending wind table",
        23 to "Failed sending table", 24 to "Under-temperature fault", 25 to "Remote off",
        26 to "Interlock fail", 27 to "Executing autotest", 30 to "Waiting sun", 31 to "Temperature fault",
        32 to "Fan stuck", 33 to "Internal communication fault", 34 to "Slave insertion", 35 to "DC switch open",
        36 to "TRAS switch open", 37 to "Master exclusion", 38 to "Auto exclusion", 51 to "Arc fault",
        53 to "Arc fault self-test fail", 98 to "Erasing internal EEPROM", 99 to "Erasing external EEPROM",
        100 to "Counting EEPROM", 101 to "Freeze", 115 to "Communication loss with communication board",
        118 to "Arc fault wrong configuration", 200 to "DSP programming",
    )

    val INVERTER_STATES: Map<Int, String> = mapOf(
        0 to "Stand by", 1 to "Checking grid", 2 to "Run", 3 to "Bulk OV", 4 to "Out OC", 5 to "IGBT sat",
        6 to "Bulk UV", 7 to "Degauss error", 8 to "No parameters", 9 to "Bulk low", 10 to "Grid OV",
        11 to "Communication error", 12 to "Degaussing", 13 to "Starting", 14 to "Bulk cap fail",
        15 to "Leak fail", 16 to "DC/DC fail", 17 to "Ileak sensor fail", 30 to "Internal error",
        31 to "Internal error", 40 to "Forbidden state", 41 to "Input UC", 42 to "Zero power",
        43 to "Grid not present", 44 to "Waiting start", 45 to "MPPT", 46 to "Grid fail", 47 to "Input OC",
        255 to "Inverter DSP not programmed",
    )

    val DCDC_STATES: Map<Int, String> = mapOf(
        0 to "DC/DC off", 1 to "Ramp start", 2 to "MPPT", 3 to "Not used", 4 to "Input OC", 5 to "Input UV",
        6 to "Input OV", 7 to "Input low", 8 to "No parameters", 9 to "Bulk OV", 10 to "Communication error",
        11 to "Ramp fail", 12 to "Internal error", 13 to "Input mode error", 14 to "Ground fault",
        15 to "Inverter fail", 16 to "DC/DC IGBT sat", 17 to "DC/DC ILEAK fail", 18 to "DC/DC grid fail",
        19 to "DC/DC comm. error", 255 to "DC/DC DSP not programmed",
    )

    val ALARMS: Map<Int, String> = mapOf(
        0 to "No alarm", 1 to "Sun low (W001)", 2 to "Input OC (E001)", 3 to "Input UV (W002)",
        4 to "Input OV (E002)", 5 to "Sun low (W001)", 6 to "No parameters (E003)", 7 to "Bulk OV (E004)",
        8 to "Comm. error (E005)", 9 to "Output OC (E006)", 10 to "IGBT sat (E007)", 11 to "Bulk UV (W011)",
        12 to "Internal error (E009)", 13 to "Grid fail (W003)", 14 to "Bulk low (E010)",
        15 to "Ramp fail (E011)", 16 to "DC/DC fail (E012)", 17 to "Wrong mode (E013)", 18 to "Ground fault",
        19 to "Over temp. (E014)", 20 to "Bulk cap fail (E015)", 21 to "Inverter fail (E016)",
        22 to "Start timeout (E017)", 23 to "Ground fault (E018)", 24 to "Degauss error",
        25 to "Ileak sens. fail (E019)", 26 to "DC/DC fail (E012)", 27 to "Self-test error 1 (E020)",
        28 to "Self-test error 2 (E021)", 29 to "Self-test error 3 (E019)", 30 to "Self-test error 4 (E022)",
        31 to "DC inj error (E023)", 32 to "Grid OV (W004)", 33 to "Grid UV (W005)", 34 to "Grid OF (W006)",
        35 to "Grid UF (W007)", 36 to "Z grid Hi (W008)", 37 to "Internal error (E024)", 38 to "Riso low (E025)",
        39 to "Vref error (E026)", 40 to "Error meas V (E027)", 41 to "Error meas F (E028)",
        42 to "Error meas Z (E029)", 43 to "Error meas Ileak (E030)", 44 to "Error read V (E031)",
        45 to "Error read I (E032)", 46 to "Table fail (W009)", 47 to "Fan fail (W010)", 48 to "UTH (E033)",
        49 to "Interlock fail (E034)", 50 to "Remote off (E035)", 51 to "Vout avg error (E036)",
        52 to "Battery low (W012)", 53 to "Clk fail (W013)", 54 to "Input UC (E037)", 55 to "Zero power (W014)",
        56 to "Fan stuck (E038)", 57 to "DC switch open (E039)", 58 to "TRAS switch open (E040)",
        59 to "AC switch open (E041)", 60 to "Bulk UV (E042)", 61 to "Autoexclusion (E043)",
        62 to "Grid df/dt (W015)", 63 to "Den switch open (W016)", 64 to "Jbox fail (W017)",
        65 to "DC door open (E044)", 66 to "AC door open (E045)", 79 to "Arc fault (E050)",
        82 to "Arc fault self-test fail (E053)", 83 to "Communication loss with communication board (E054)",
    )

    val MODELS: Map<Int, String> = mapOf(
        67 to "TRIO-5.8/8.5-TL-OUTD", 70 to "TRIO-27.6-TL-OUTD-480", 89 to "TRIO-27.6-TL-OUTD-400",
        102 to "TRIO-20-TL-OUTD-480", 121 to "TRIO-20-TL-OUTD-400",
    )
}
