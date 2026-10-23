// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.consumer

import ch.amslersolar.dashboard.driver.modbus.ModbusException
import ch.amslersolar.dashboard.driver.modbus.RegisterBlock
import ch.amslersolar.dashboard.driver.sunspec.RegisterReader

/**
 * Reads lists of fields (address to register count) for the consumer drivers. Fields are grouped into blocks
 * (at most [maxBlock] registers, bridging gaps of up to [maxGap]); a block the device rejects with a Modbus
 * exception is read field by field from then on, and single fields it rejects are skipped until [forget].
 * Read-only: only the [RegisterReader] (FC03 or FC04) is ever used.
 */
internal class FieldFetcher(
    private val reader: RegisterReader,
    private val unitId: Int,
    private val maxBlock: Int = 125,
    private val maxGap: Int = 16,
) {
    private val split = mutableSetOf<Int>()
    private val missing = mutableSetOf<Int>()

    /** Forget the fields the device rejected, so they are asked again (with the static data). */
    fun forget() = missing.clear()

    suspend fun fetch(fields: List<Pair<Int, Int>>): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        for (block in blocks(fields, maxBlock, maxGap)) {
            val start = block.first().first
            val end = block.maxOf { it.first + it.second }
            if (start !in split) {
                try {
                    out.putAll(reader.read(unitId, start, end - start).values())
                    continue
                } catch (_: ModbusException) {
                    split += start
                }
            }
            for ((addr, size) in block) {
                if (addr in missing) continue
                try {
                    out.putAll(reader.read(unitId, addr, size).values())
                } catch (_: ModbusException) {
                    missing += addr
                }
            }
        }
        return out
    }

    companion object {
        fun blocks(fields: List<Pair<Int, Int>>, maxBlock: Int = 125, maxGap: Int = 16): List<List<Pair<Int, Int>>> {
            val out = mutableListOf<MutableList<Pair<Int, Int>>>()
            for (field in fields.distinct().sortedBy { it.first }) {
                val current = out.lastOrNull()
                if (current != null) {
                    val start = current.first().first
                    val end = current.maxOf { it.first + it.second }
                    if (field.first - end <= maxGap && field.first + field.second - start <= maxBlock) {
                        current += field
                        continue
                    }
                }
                out += mutableListOf(field)
            }
            return out
        }
    }
}

internal fun RegisterBlock.values(): Map<Int, Int> = (start until start + count).associateWith { u16(it) }

/**
 * Register values by address. Multi-register values are decoded high word first unless [lowWordFirst];
 * a value is null when any of its registers wasn't read.
 */
internal class Regs(private val regs: Map<Int, Int>, val lowWordFirst: Boolean = false) {
    fun has(a: Int) = a in regs
    fun u16(a: Int): Int? = regs[a]
    fun s16(a: Int): Int? = u16(a)?.toShort()?.toInt()

    fun u32(a: Int): Long? {
        val first = u16(a) ?: return null
        val second = u16(a + 1) ?: return null
        val (hi, lo) = if (lowWordFirst) second to first else first to second
        return (hi.toLong() shl 16) or lo.toLong()
    }

    fun s32(a: Int): Int? = u32(a)?.toInt()

    fun f32(a: Int): Double? = u32(a)?.let { Float.fromBits(it.toInt()).toDouble() }?.takeIf { it.isFinite() }

    /** 64 bits over 4 registers, in the same word order as the 32-bit values. */
    fun u64(a: Int): Long? {
        val words = (a until a + 4).map { u16(it)?.toLong() ?: return null }
        val ordered = if (lowWordFirst) words.reversed() else words
        return ordered.fold(0L) { acc, w -> (acc shl 16) or w }
    }

    /** IEEE 754 double over 4 registers, in the same word order as the 32-bit values. */
    fun f64(a: Int): Double? = u64(a)?.let { Double.fromBits(it) }?.takeIf { it.isFinite() }

    /** Two ASCII characters per register, high byte first; stops at NUL, drops non-printable bytes. */
    fun text(a: Int, n: Int): String? {
        val sb = StringBuilder()
        for (i in a until a + n) {
            val v = u16(i) ?: return null
            for (c in intArrayOf(v shr 8, v and 0xFF)) {
                if (c == 0) return sb.toString().trim().takeIf { it.isNotEmpty() }
                if (c in 0x20..0x7E) sb.append(c.toChar())
            }
        }
        return sb.toString().trim().takeIf { it.isNotEmpty() }
    }
}
