// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.remote

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * One hour of one-minute samples (observer protocol, "Block"): per channel a presence bitmap and the present values
 * as integers at the channel's resolution, the first as is and then their changes, zigzag + varint. [values] maps
 * a channel to its value per slot (null: none).
 */
class Block(val start: Long, val channels: List<Channel>, val interval: Int = INTERVAL_S, val slots: Int = SLOTS) {
    class Channel(val name: String, val exponent: Int, val values: List<Double?>)

    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(ByteBuffer.allocate(11).put(1).putInt(start.toInt()).putShort(interval.toShort()).putShort(slots.toShort())
            .putShort(channels.size.toShort()).array())
        for (c in channels) {
            val n = c.name.toByteArray()
            require(n.size in 1..255) { "Channel name ${c.name}" }
            out.write(n.size); out.write(n); out.write(c.exponent)
        }
        val bitmapBytes = (slots + 7) / 8
        for (c in channels) {
            val bits = ByteArray(bitmapBytes)
            c.values.forEachIndexed { i, v -> if (v != null && v.isFinite()) bits[i / 8] = (bits[i / 8].toInt() or (1 shl (i % 8))).toByte() }
            out.write(bits)
        }
        for (c in channels) {
            val scale = 10.0.pow(-c.exponent)
            var previous: Long? = null
            for (v in c.values) {
                if (v == null || !v.isFinite()) continue
                val x = (v * scale).roundToLong()
                varint(out, zigzag(if (previous == null) x else x - previous))
                previous = x
            }
        }
        return out.toByteArray()
    }

    companion object {
        const val INTERVAL_S = 60
        const val SLOTS = 60

        fun name(start: Long) = "b$start"

        /** The epoch second of the hour [epochSecond] falls in. */
        fun hourOf(epochSecond: Long) = epochSecond - Math.floorMod(epochSecond, 3600L)

        /** Exponent by unit: whole W, Wh, VA, var, %; tenths of V and °C; hundredths otherwise. */
        fun exponent(unit: String): Int = when (unit) {
            "W", "Wh", "VA", "var", "%" -> 0
            "V", "°C" -> -1
            else -> -2
        }

        fun decode(bytes: ByteArray): Block {
            try {
                val b = ByteBuffer.wrap(bytes)
                if (b.get().toInt() != 1) throw IOException("Unknown block version")
                val start = b.int.toLong() and 0xFFFFFFFFL
                val interval = b.short.toInt() and 0xFFFF
                val slots = b.short.toInt() and 0xFFFF
                val count = b.short.toInt() and 0xFFFF
                if (interval == 0 || slots > 3600) throw IOException("Bad block layout")
                val heads = (0 until count).map {
                    val name = String(ByteArray(b.get().toInt() and 0xFF).also { a -> b.get(a) })
                    name to b.get().toInt()
                }
                val bitmapBytes = (slots + 7) / 8
                val present = heads.map { ByteArray(bitmapBytes).also { b.get(it) } }
                val channels = heads.mapIndexed { k, (name, exponent) ->
                    val scale = 10.0.pow(exponent)
                    var previous: Long? = null
                    val values = (0 until slots).map { i ->
                        if ((present[k][i / 8].toInt() shr (i % 8)) and 1 == 0) null
                        else {
                            val d = unzigzag(readVarint(b))
                            val x = if (previous == null) d else previous!! + d
                            previous = x
                            x * scale
                        }
                    }
                    Channel(name, exponent, values)
                }
                return Block(start, channels, interval, slots)
            } catch (e: java.nio.BufferUnderflowException) {
                throw IOException("Truncated block", e)
            }
        }

        private fun zigzag(x: Long): Long = (x shl 1) xor (x shr 63)
        private fun unzigzag(x: Long): Long = (x ushr 1) xor -(x and 1)

        private fun varint(out: ByteArrayOutputStream, value: Long) {
            var v = value
            while (v and 0x7FL.inv() != 0L) {
                out.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
            out.write(v.toInt())
        }

        private fun readVarint(b: ByteBuffer): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val byte = b.get().toInt() and 0xFF
                result = result or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
                if (shift > 63) throw IOException("Varint too long")
            }
        }
    }
}
