// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.driver.modbus

import java.io.DataInputStream
import java.io.IOException

/**
 * Function 43 / MEI 14, Read Device Identification: text objects such as the vendor, product code and version.
 * Read only. Objects 0x00..0x02 are basic, 0x03..0x7F regular, 0x80..0xFF extended (vendor-specific); a device
 * streams them a few at a time ("more follows" with the next object id).
 */
object ModbusDeviceId {
    const val FUNCTION = 0x2B
    const val MEI = 0x0E

    /** The read codes (basic, regular, extended) with the first object id of their category. */
    val LEVELS = listOf(1 to 0x00, 2 to 0x03, 3 to 0x80)

    val NAMES = mapOf(
        0x00 to "VendorName", 0x01 to "ProductCode", 0x02 to "MajorMinorRevision", 0x03 to "VendorUrl",
        0x04 to "ProductName", 0x05 to "ModelName", 0x06 to "UserApplicationName",
    )

    /** One answer: the device's conformity level, whether more objects follow (from [nextObjectId]), the objects. */
    class Answer(val conformity: Int, val moreFollows: Boolean, val nextObjectId: Int, val objects: List<Pair<Int, ByteArray>>)

    /** What a device told about itself: its conformity level (0 when it never answered) and the objects by id. */
    class Identification(val conformity: Int, val objects: Map<Int, ByteArray>)

    fun pdu(readCode: Int, objectId: Int): ByteArray = byteArrayOf(FUNCTION.toByte(), MEI.toByte(), readCode.toByte(), objectId.toByte())

    /** Parses an answer PDU (function code first); a Modbus exception throws [ModbusException]. */
    fun parse(pdu: ByteArray): Answer {
        if (pdu.size >= 2 && pdu[0].toInt() and 0xFF == FUNCTION or 0x80) throw ModbusException(pdu[1].toInt() and 0xFF)
        if (pdu.size < 7) throw ProtocolException("Device identification too short (${pdu.size} bytes)")
        if (pdu[0].toInt() and 0xFF != FUNCTION || pdu[1].toInt() and 0xFF != MEI) throw ProtocolException("Not a device identification answer")
        val objects = mutableListOf<Pair<Int, ByteArray>>()
        var i = 7
        repeat(pdu[6].toInt() and 0xFF) {
            if (i + 2 > pdu.size) throw ProtocolException("Device identification cut short")
            val len = pdu[i + 1].toInt() and 0xFF
            if (i + 2 + len > pdu.size) throw ProtocolException("Device identification cut short")
            objects += (pdu[i].toInt() and 0xFF) to pdu.copyOfRange(i + 2, i + 2 + len)
            i += 2 + len
        }
        return Answer(pdu[3].toInt() and 0xFF, pdu[4].toInt() and 0xFF == 0xFF, pdu[5].toInt() and 0xFF, objects)
    }

    /** Reads an RTU answer frame (unit … CRC); it has no byte count, so it is read object by object. */
    fun readRtuFrame(input: DataInputStream): ByteArray {
        val head = ByteArray(2).also(input::readFully)
        if (head[1].toInt() and 0x80 != 0) return head + ByteArray(3).also(input::readFully)
        val fixed = ByteArray(6).also(input::readFully) // MEI, read code, conformity, more follows, next id, count
        if (fixed[0].toInt() and 0xFF != MEI) throw ProtocolException("Not a device identification answer")
        var frame = head + fixed
        repeat(fixed[5].toInt() and 0xFF) {
            val idLen = ByteArray(2).also(input::readFully)
            frame += idLen + ByteArray(idLen[1].toInt() and 0xFF).also(input::readFully)
        }
        return frame + ByteArray(2).also(input::readFully) // CRC
    }

    /**
     * Reads every object the device has, level by level up to its conformity level, following "more follows".
     * A level the device refuses, or doesn't answer, ends the reading with what it has.
     */
    suspend fun readAll(request: suspend (readCode: Int, objectId: Int) -> Answer): Identification {
        val objects = sortedMapOf<Int, ByteArray>()
        var conformity = 0
        for ((code, first) in LEVELS) {
            if (conformity != 0 && code > conformity and 0x7F) break
            var next = first
            try {
                for (step in 0 until 64) { // a device repeating its "next object id" can't keep us here
                    val a = request(code, next)
                    conformity = a.conformity
                    for ((id, value) in a.objects) objects[id] = value
                    if (!a.moreFollows || a.nextObjectId <= next) break
                    next = a.nextObjectId
                }
            } catch (_: IOException) { // ModbusException included: the level isn't supported
                break
            }
        }
        return Identification(conformity, objects)
    }

    /** An object's value as text when it is printable ASCII, else as hex. */
    fun text(value: ByteArray): String =
        if (value.all { it in 0x20..0x7E }) "\"" + String(value, Charsets.US_ASCII) + "\""
        else value.joinToString(" ") { "%02X".format(it) }
}
