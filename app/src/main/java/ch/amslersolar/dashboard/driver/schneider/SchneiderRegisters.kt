// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.schneider

import ch.amslersolar.dashboard.driver.modbus.ModbusException
import ch.amslersolar.dashboard.driver.modbus.RegisterBlock
import ch.amslersolar.dashboard.driver.sunspec.RegisterReader

/**
 * Reads lists of (address, size) fields in blocks of at most 125 registers, bridging small gaps. A block the
 * device rejects (Modbus exception, e.g. the Conext Gateway's 02 for a read across a "blank row" boundary or into an
 * undefined register) is read field by field from then on; fields it rejects are left out until [forget].
 */
internal class RegisterFetcher(private val reader: RegisterReader, private val unitId: Int, private val maxGap: Int = 8) {
    private val split = mutableSetOf<Int>()
    private val missing = mutableSetOf<Int>()

    fun forget() = missing.clear()

    suspend fun fetch(fields: List<Pair<Int, Int>>): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        for (block in blocks(fields, maxGap)) {
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

    private fun RegisterBlock.values(): Map<Int, Int> = (start until start + count).associateWith { u16(it) }

    companion object {
        const val MAX_BLOCK = 125

        fun blocks(fields: List<Pair<Int, Int>>, maxGap: Int): List<List<Pair<Int, Int>>> {
            val out = mutableListOf<MutableList<Pair<Int, Int>>>()
            for (field in fields.distinct().sortedBy { it.first }) {
                val current = out.lastOrNull()
                val start = current?.first()?.first
                val end = current?.maxOf { it.first + it.second }
                if (current != null && field.first - end!! <= maxGap && field.first + field.second - start!! <= MAX_BLOCK) current += field
                else out += mutableListOf(field)
            }
            return out
        }
    }
}

/** Register values by address. [lowWordFirst]: 32-bit values have the low word at the lower address (CL-60). */
internal class Registers(private val regs: Map<Int, Int>, private val lowWordFirst: Boolean) {
    fun u16(a: Int): Int? = regs[a]
    fun s16(a: Int): Int? = u16(a)?.toShort()?.toInt()
    fun u32(a: Int): Long? {
        val first = u16(a) ?: return null
        val second = u16(a + 1) ?: return null
        val (hi, lo) = if (lowWordFirst) second to first else first to second
        return (hi.toLong() shl 16) or lo.toLong()
    }
    fun s32(a: Int): Long? = u32(a)?.toInt()?.toLong()

    /** Two characters per register, high byte first; stops at NUL, drops 0xFF filler. */
    fun text(a: Int, n: Int): String? {
        val bytes = (a until a + n).map { u16(it) ?: return null }.flatMap { listOf(it shr 8, it and 0xFF) }
        val used = bytes.takeWhile { it != 0 }.filter { it != 0xFF }.map { it.toByte() }.toByteArray()
        return String(used, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
    }
}
