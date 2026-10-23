// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.voltronic

import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** The inverter answered `(NAK`: it doesn't know the command (or the CRC we sent was wrong). */
class AxpertNakException(command: String) : IOException("$command not accepted (NAK)")

/** A reply that isn't a valid Voltronic frame, or whose CRC doesn't match. */
class AxpertFrameException(message: String) : IOException(message)

/**
 * Framing, CRC and reply parsing of the Voltronic Power "Axpert" RS232 protocol (protocol ID PI30). Pure functions,
 * no I/O. Section numbers refer to the two Voltronic documents in docs/specs/voltronic/:
 * "Axpert KS&MKS&V Communication Protocol" (2017-08-21, "KS") and "Axpert MAXII/VMIV/MKSIV Communication Protocol"
 * (2021-08-04, "MAX").
 *
 * Framing (KS/MAX §2): the computer sends `<command><CRC><cr>`, the inverter answers `(<data><CRC><cr>`; an unknown
 * command answers `(NAK<CRC><cr>` or `(NAK<cr>` (both spellings appear in the documents).
 *
 * CRC (appendix 4.1, the embedded `CRC.c`, function `cal_crc_half`): a nibble-table CRC-16 with polynomial 0x1021,
 * initial value 0, no reflection and no final XOR, i.e. CRC-16/XMODEM. It's sent high byte first. The function is
 * called with the length including the trailing CR and skips that last byte (`len--`), so the CRC covers the
 * command characters only. Afterwards each CRC byte equal to 0x28 `(`, 0x0D CR or 0x0A LF is incremented by one, so
 * a CRC byte can never be mistaken for a frame start or end. The inverter applies the same rule to its replies; the
 * reply CRC covers the bytes from `(` up to the CRC.
 */
object AxpertProtocol {
    private const val CR = 0x0D
    private const val START = 0x28 // '('

    private val TABLE = intArrayOf(
        0x0000, 0x1021, 0x2042, 0x3063, 0x4084, 0x50a5, 0x60c6, 0x70e7,
        0x8108, 0x9129, 0xa14a, 0xb16b, 0xc18c, 0xd1ad, 0xe1ce, 0xf1ef,
    )

    /** CRC-16/XMODEM of [length] bytes, computed nibble by nibble like `cal_crc_half` (before byte stuffing). */
    fun crc16(data: ByteArray, length: Int = data.size): Int {
        var crc = 0
        for (i in 0 until length) {
            val b = data[i].toInt() and 0xFF
            var da = (crc shr 12) and 0x0F
            crc = ((crc shl 4) and 0xFFFF) xor TABLE[da xor (b shr 4)]
            da = (crc shr 12) and 0x0F
            crc = ((crc shl 4) and 0xFFFF) xor TABLE[da xor (b and 0x0F)]
        }
        return crc
    }

    /** A CRC byte that would read as `(`, CR or LF is sent incremented by one. */
    fun stuff(byte: Int): Int = if (byte == 0x28 || byte == 0x0D || byte == 0x0A) byte + 1 else byte

    /** The two CRC bytes as sent on the line: high byte first, both stuffed. */
    fun crcBytes(data: ByteArray, length: Int = data.size): ByteArray {
        val crc = crc16(data, length)
        return byteArrayOf(stuff(crc shr 8).toByte(), stuff(crc and 0xFF).toByte())
    }

    /** `<command><CRC><cr>` ready to write to the RS232 line. */
    fun frame(command: String): ByteArray {
        val cmd = command.toByteArray(Charsets.ISO_8859_1)
        return cmd + crcBytes(cmd) + byteArrayOf(CR.toByte())
    }

    /**
     * The data between `(` and the CRC of one reply. [raw] is everything received up to the first CR (the CR itself
     * may be included); bytes before the last `(` are leftovers and ignored (a CRC byte can't be 0x28).
     * Throws [AxpertNakException] for `(NAK`, [AxpertFrameException] for anything malformed.
     */
    fun parseReply(command: String, raw: ByteArray): String {
        var end = raw.indexOf(CR.toByte()).let { if (it < 0) raw.size else it }
        val start = (end - 1 downTo 0).firstOrNull { raw[it].toInt() == START }
            ?: throw AxpertFrameException("$command: reply without '(' start byte")
        val frame = raw.copyOfRange(start, end) // "(" + data + CRC
        val text = String(frame, Charsets.ISO_8859_1)
        if (text == "(NAK" || (text.length == 6 && text.startsWith("(NAK"))) throw AxpertNakException(command)
        if (frame.size < 3) throw AxpertFrameException("$command: reply too short")
        end = frame.size - 2
        val expected = crcBytes(frame, end)
        if (frame[end] != expected[0] || frame[end + 1] != expected[1]) {
            throw AxpertFrameException("$command: CRC mismatch")
        }
        return String(frame, 1, end - 1, Charsets.ISO_8859_1)
    }

    // ---------------------------------------------------------------- replies

    private fun tokens(data: String) = data.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** Protocol ID from QPI (MAX §2.1): `PI30` → 30. Null if the reply isn't of that form. */
    fun parseProtocolId(data: String): Int? = Regex("^PI(\\d{2})$").find(data.trim())?.groupValues?.get(1)?.toInt()

    /** Serial number from QID (KS §2.1, MAX §2.2); null if it doesn't look like one. */
    fun parseSerial(data: String): String? = data.trim().takeIf { Regex("^[0-9A-Za-z-]{4,32}$").matches(it) }

    /** Firmware from QVFW (KS §2.3, MAX §2.4): `VERFW:00072.70` → `00072.70`. */
    fun parseFirmware(data: String): String? =
        Regex("^VERFW:\\s*([0-9A-Fa-f]{1,8}\\.[0-9A-Fa-f]{1,4})$").find(data.trim())?.groupValues?.get(1)

    /** Energy counters QET/QED/QLT/QLD (MAX §2.23–2.30): 8 digits in Wh. */
    fun parseEnergyWh(data: String): Long? = data.trim().takeIf { Regex("^\\d{1,10}$").matches(it) }?.toLong()

    private val YMD: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    /** `QEDyyyymmdd` (PV energy of a day, MAX §2.26). */
    fun pvOfDay(date: LocalDate) = "QED" + date.format(YMD)

    /** `QLDyyyymmdd` (output load energy of a day, MAX §2.30). */
    fun loadOfDay(date: LocalDate) = "QLD" + date.format(YMD)

    /**
     * QPIGS (KS §2.7, MAX §2.9), fields by position. The first 17 fields exist on every documented model; [pvPowerW]
     * onwards were added later (KS lists them, very old firmware may not send them). Field 12 is "PV input current
     * for battery" in KS (4 digits) and "PV1 input current" in MAX (EE.E).
     */
    data class Qpigs(
        val gridVoltageV: Double,
        val gridFrequencyHz: Double,
        val outputVoltageV: Double,
        val outputFrequencyHz: Double,
        val outputApparentVa: Int,
        val outputActiveW: Int,
        val loadPercent: Int,
        val busVoltageV: Int,
        val batteryVoltageV: Double,
        val batteryChargeA: Int,
        val batteryCapacityPercent: Int,
        val heatSinkTemperature: Int,
        val pvCurrentA: Double,
        val pvVoltageV: Double,
        val sccBatteryVoltageV: Double,
        val batteryDischargeA: Int,
        /** b7…b0 as sent, e.g. "00010110". */
        val status: String,
        val fanOffset10mV: Int? = null,
        val eepromVersion: String? = null,
        /** "PV charging power" / "PV1 charging power" in W. */
        val pvPowerW: Int? = null,
        /** b10 b9 b8 as sent, e.g. "010". */
        val status2: String? = null,
    ) {
        private fun bit(n: Int) = status.getOrNull(7 - n) == '1'
        private fun bit2(n: Int) = status2?.getOrNull(10 - n)?.let { it == '1' }

        val sbuPriorityVersion get() = bit(7)
        val configurationChanged get() = bit(6)
        val sccFirmwareUpdated get() = bit(5)
        val loadOn get() = bit(4)
        val batteryVoltageSteady get() = bit(3)
        val charging get() = bit(2)
        val sccCharging get() = bit(1)
        val acCharging get() = bit(0)
        val floatCharging get() = bit2(10)
        val switchOn get() = bit2(9)
        val dustproofInstalled get() = bit2(8)

        /** Battery power in our sign convention: V × (discharge − charge current), positive when discharging. */
        val batteryPowerW: Int get() = Math.round(batteryVoltageV * (batteryDischargeA - batteryChargeA)).toInt()
    }

    fun parseQpigs(data: String): Qpigs {
        val t = tokens(data)
        if (t.size < 17 || !Regex("^[01]{8}$").matches(t[16])) throw AxpertFrameException("QPIGS: unexpected reply '$data'")
        fun d(i: Int) = t[i].toDoubleOrNull() ?: throw AxpertFrameException("QPIGS: field ${i + 1} '${t[i]}' isn't a number")
        fun n(i: Int) = d(i).toInt()
        return Qpigs(
            gridVoltageV = d(0), gridFrequencyHz = d(1), outputVoltageV = d(2), outputFrequencyHz = d(3),
            outputApparentVa = n(4), outputActiveW = n(5), loadPercent = n(6), busVoltageV = n(7),
            batteryVoltageV = d(8), batteryChargeA = n(9), batteryCapacityPercent = n(10),
            heatSinkTemperature = n(11), pvCurrentA = d(12), pvVoltageV = d(13), sccBatteryVoltageV = d(14),
            batteryDischargeA = n(15), status = t[16],
            fanOffset10mV = t.getOrNull(17)?.toIntOrNull(),
            eepromVersion = t.getOrNull(18),
            pvPowerW = t.getOrNull(19)?.toIntOrNull(),
            status2 = t.getOrNull(20)?.takeIf { Regex("^[01]{3}$").matches(it) },
        )
    }

    /** QPIGS2 (MAX §2.10, MAXII only): the second PV input. */
    data class Qpigs2(val pvCurrentA: Double, val pvVoltageV: Double, val pvPowerW: Int)

    fun parseQpigs2(data: String): Qpigs2 {
        val t = tokens(data)
        val v = t.take(3).map { it.toDoubleOrNull() }
        if (t.size < 3 || v.any { it == null }) throw AxpertFrameException("QPIGS2: unexpected reply '$data'")
        return Qpigs2(v[0]!!, v[1]!!, v[2]!!.toInt())
    }

    /** QPIRI rated information (KS §2.5, MAX §2.7), fields by position. [operationLogic]/[maxDischargeA]: MAXII. */
    data class Qpiri(
        val gridRatedVoltageV: Double,
        val gridRatedCurrentA: Double,
        val outputRatedVoltageV: Double,
        val outputRatedFrequencyHz: Double,
        val outputRatedCurrentA: Double,
        val outputRatedApparentVa: Int,
        val outputRatedActiveW: Int,
        val batteryRatedVoltageV: Double,
        val batteryRechargeVoltageV: Double,
        val batteryUnderVoltageV: Double,
        val batteryBulkVoltageV: Double,
        val batteryFloatVoltageV: Double,
        val batteryType: Int?,
        val maxAcChargeA: Int?,
        val maxChargeA: Int?,
        val inputVoltageRange: Int?,
        val outputSourcePriority: Int?,
        val chargerSourcePriority: Int?,
        val parallelMax: Int?,
        val machineType: String?,
        val topology: Int?,
        val outputMode: Int?,
        val batteryRedischargeVoltageV: Double?,
        val pvOkCondition: Int?,
        val pvPowerBalance: Int?,
        val maxCvChargeTimeMin: Int?,
        val operationLogic: Int?,
        val maxDischargeA: Int?,
        val fieldCount: Int,
    ) {
        /** MAX §2.7: only the MAXII sends operation logic and max discharging current (28 fields). */
        val looksLikeMaxII get() = fieldCount >= 28
    }

    fun parseQpiri(data: String): Qpiri {
        val t = tokens(data)
        if (t.size < 12) throw AxpertFrameException("QPIRI: unexpected reply '$data'")
        fun d(i: Int) = t[i].toDoubleOrNull() ?: throw AxpertFrameException("QPIRI: field ${i + 1} '${t[i]}' isn't a number")
        fun od(i: Int) = t.getOrNull(i)?.toDoubleOrNull()
        fun oi(i: Int) = t.getOrNull(i)?.toIntOrNull()
        return Qpiri(
            gridRatedVoltageV = d(0), gridRatedCurrentA = d(1), outputRatedVoltageV = d(2),
            outputRatedFrequencyHz = d(3), outputRatedCurrentA = d(4), outputRatedApparentVa = d(5).toInt(),
            outputRatedActiveW = d(6).toInt(), batteryRatedVoltageV = d(7), batteryRechargeVoltageV = d(8),
            batteryUnderVoltageV = d(9), batteryBulkVoltageV = d(10), batteryFloatVoltageV = d(11),
            batteryType = oi(12), maxAcChargeA = oi(13), maxChargeA = oi(14), inputVoltageRange = oi(15),
            outputSourcePriority = oi(16), chargerSourcePriority = oi(17), parallelMax = oi(18),
            machineType = t.getOrNull(19), topology = oi(20), outputMode = oi(21), batteryRedischargeVoltageV = od(22),
            pvOkCondition = oi(23), pvPowerBalance = oi(24), maxCvChargeTimeMin = oi(25), operationLogic = oi(26),
            maxDischargeA = oi(27), fieldCount = t.size,
        )
    }

    // ---------------------------------------------------------------- code tables

    /** QMOD (KS §2.9: P S L B F H; MAX §2.12 adds D C Y E). */
    val MODES = mapOf(
        'P' to "Power on", 'S' to "Standby", 'L' to "Line", 'B' to "Battery", 'F' to "Fault",
        'H' to "Power saving", 'D' to "Shutdown", 'C' to "Charge", 'Y' to "Bypass", 'E' to "ECO",
    )

    fun parseMode(data: String): String? {
        val code = data.trim()
        if (code.length != 1) return null
        return MODES[code[0]] ?: "Unknown ($code)"
    }

    /** QPIRI battery type: KS 0–2, MAX adds 3/5/6/8/9; 4 and 7 from PBT (MAX §3.17). */
    val BATTERY_TYPES = mapOf(
        0 to "AGM", 1 to "Flooded", 2 to "User", 3 to "Pylontech", 4 to "Shinheung", 5 to "Weco", 6 to "Soltaro",
        7 to "BAK", 8 to "Lib", 9 to "Lic",
    )
    val INPUT_RANGES = mapOf(0 to "Appliance", 1 to "UPS")
    val OUTPUT_PRIORITIES = mapOf(0 to "Utility first (USB)", 1 to "Solar first (SUB)", 2 to "SBU (solar, battery, utility)")
    val CHARGER_PRIORITIES = mapOf(0 to "Utility first", 1 to "Solar first", 2 to "Solar + utility", 3 to "Only solar")
    val MACHINE_TYPES = mapOf("00" to "Grid tie", "01" to "Off grid", "10" to "Hybrid")
    val TOPOLOGIES = mapOf(0 to "Transformerless", 1 to "Transformer")
    val OUTPUT_MODES = mapOf(
        0 to "Single", 1 to "Parallel", 2 to "Phase 1 of 3", 3 to "Phase 2 of 3", 4 to "Phase 3 of 3",
        5 to "Phase 1 of 2", 6 to "Phase 2 of 2 (120°)", 7 to "Phase 2 of 2 (180°)",
    )
    val OPERATION_LOGIC = mapOf(0 to "Automatic", 1 to "On-line", 2 to "ECO")

    /** QPIWS bit names (KS §2.10, 32 bits). */
    private val WARNINGS_KS = listOf(
        null, "Inverter fault", "Bus over", "Bus under", "Bus soft fail", "Line fail", "OPV short",
        "Inverter voltage too low", "Inverter voltage too high", "Over temperature", "Fan locked",
        "Battery voltage high", "Battery low alarm", "Overcharge", "Battery under shutdown", "Battery derating",
        "Over load", "EEPROM fault", "Inverter over current", "Inverter soft fail", "Self test fail",
        "OP DC voltage over", "Battery open", "Current sensor fail", "Battery short", "Power limit",
        "PV voltage high", "MPPT overload fault", "MPPT overload warning", "Battery too low to charge", null, null,
    )

    /** QPIWS bit names (MAX §2.13, up to 36 bits a0…a35; on MAXII/MKSIV a32a33, on VMIV a31a32 also carry a fault code, which isn't decoded). */
    private val WARNINGS_MAX = WARNINGS_KS.toMutableList().apply {
        this[0] = "PV loss"; this[13] = null
        for (i in 24..29) this[i] = null
        addAll(listOf(null, "Low PV energy (VMIV)", "High AC input during bus soft start (VMIV)", "Battery equalization (VMIV)"))
        this[31] = "Battery weak"
    }

    /** a9, a10, a11 and a16 are faults when a1 (inverter fault) is set, warnings otherwise. */
    private val FAULT_IF_A1 = setOf(9, 10, 11, 16)

    /**
     * Active warnings and faults from QPIWS. A reply longer than 32 bits uses the MAX table, otherwise the KS table
     * (the two documents name a0, a13 and a24…a35 differently).
     */
    fun parseWarnings(data: String): List<String> {
        val bits = data.trim()
        if (!Regex("^[01]{8,40}$").matches(bits)) throw AxpertFrameException("QPIWS: unexpected reply '$data'")
        val names = if (bits.length > 32) WARNINGS_MAX else WARNINGS_KS
        val fault = bits.getOrNull(1) == '1'
        return bits.indices.filter { bits[it] == '1' }.map { i ->
            val name = names.getOrNull(i) ?: "Bit a$i"
            if (i in FAULT_IF_A1) "$name (${if (fault) "fault" else "warning"})" else name
        }
    }
}
