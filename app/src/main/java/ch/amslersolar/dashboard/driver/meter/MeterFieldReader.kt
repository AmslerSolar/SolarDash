// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.meter

import ch.amslersolar.dashboard.driver.modbus.ModbusException
import ch.amslersolar.dashboard.driver.modbus.RegisterBlock
import ch.amslersolar.dashboard.driver.sunspec.RegisterReader

/** One value in a meter's register map: request address and size in registers. */
internal data class MeterField(val address: Int, val size: Int) {
    val end: Int get() = address + size
}

internal fun fields(size: Int, vararg addresses: Int): List<MeterField> = addresses.map { MeterField(it, size) }

/**
 * Reads register maps that have gaps. Fields are read in blocks of at most 125 registers, bridging gaps of up to
 * [maxGap] registers. Many meters reject a read that touches an undefined register (exception 2): a rejected block
 * is read field by field once, and from then on as the contiguous runs of the fields the device answered. Fields
 * the device rejects on their own are left out (null in the reading) until [retryMissing].
 */
internal class MeterFieldReader(
    private val reader: RegisterReader,
    private val unitId: Int,
    private val maxGap: Int = 16,
) {
    private val plans = HashMap<Pair<Int, Int>, List<List<MeterField>>>() // rejected block → runs that work
    private val missing = HashSet<Int>() // single fields the device rejected

    fun retryMissing() = missing.clear()

    suspend fun fetch(fields: List<MeterField>): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        for (block in group(fields, maxGap)) readBlock(block, out)
        return out
    }

    private suspend fun readBlock(block: List<MeterField>, out: MutableMap<Int, Int>) {
        val key = block.first().address to block.maxOf { it.end }
        val plan = plans[key]
        if (plan == null) {
            if (tryRead(block, out)) return
            readSingles(block, out)
            plans[key] = group(block.filter { it.address !in missing }, 0)
            return
        }
        for (run in plan) {
            if (!tryRead(run, out)) {
                readSingles(run, out)
                plans.remove(key) // plan again next time
            }
        }
        val planned = plan.flatten().toSet()
        readSingles(block.filter { it !in planned }, out) // only fields cleared by retryMissing
    }

    private suspend fun readSingles(fields: List<MeterField>, out: MutableMap<Int, Int>) {
        for (f in fields) {
            if (f.address in missing) continue
            if (!tryRead(listOf(f), out)) missing += f.address
        }
    }

    private suspend fun tryRead(fields: List<MeterField>, out: MutableMap<Int, Int>): Boolean {
        val start = fields.first().address
        return try {
            out.putAll(reader.read(unitId, start, fields.maxOf { it.end } - start).values())
            true
        } catch (_: ModbusException) {
            false
        }
    }

    private fun RegisterBlock.values(): Map<Int, Int> = (start until start + count).associateWith { u16(it) }

    companion object {
        const val MAX_BLOCK = 125

        fun group(fields: List<MeterField>, maxGap: Int): List<List<MeterField>> {
            val out = mutableListOf<MutableList<MeterField>>()
            for (f in fields.distinct().sortedBy { it.address }) {
                val current = out.lastOrNull()
                if (current != null) {
                    val start = current.first().address
                    val end = current.maxOf { it.end }
                    if (f.address - end <= maxGap && maxOf(end, f.end) - start <= MAX_BLOCK) {
                        current += f
                        continue
                    }
                }
                out += mutableListOf(f)
            }
            return out
        }
    }
}

/**
 * Register values by address. Multi-register values are decoded high word first unless [lowWordFirst]
 * (word-swapped, "CDAB"); bytes within a register are always big-endian. Non-finite floats read as null.
 */
internal class MeterRegs(private val regs: Map<Int, Int>, private val lowWordFirst: Boolean = false) {
    fun has(a: Int, n: Int = 1): Boolean = (a until a + n).all { it in regs }

    fun u16(a: Int): Int? = regs[a]
    fun s16(a: Int): Int? = u16(a)?.toShort()?.toInt()

    /** [n] registers as one unsigned big number (n ≤ 4), in this image's word order. */
    private fun words(a: Int, n: Int): Long? {
        val w = (0 until n).map { u16(a + it) ?: return null }
        return (if (lowWordFirst) w.reversed() else w).fold(0L) { acc, v -> (acc shl 16) or v.toLong() }
    }

    fun u32(a: Int): Long? = words(a, 2)
    fun s32(a: Int): Int? = u32(a)?.toInt()
    fun u64(a: Int): Long? = words(a, 4) // values above Long.MAX_VALUE don't occur in energy counters
    fun s64(a: Int): Long? = words(a, 4)
    fun f32(a: Int): Double? = u32(a)?.let { Float.fromBits(it.toInt()).toDouble() }?.takeIf { it.isFinite() }
    fun f64(a: Int): Double? = s64(a)?.let { Double.fromBits(it) }?.takeIf { it.isFinite() }

    /** [n] registers of text, two bytes per register (high byte first), trimmed of NUL and spaces. */
    fun text(a: Int, n: Int): String? {
        val bytes = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = u16(a + i) ?: return null
            bytes[2 * i] = (v shr 8).toByte()
            bytes[2 * i + 1] = v.toByte()
        }
        val s = String(bytes, Charsets.UTF_8).substringBefore('\u0000').trim { it <= ' ' }
        return s.takeIf { it.isNotEmpty() }
    }

    /** Text that is plain printable ASCII (to tell a name register from numeric data at the same address). */
    fun printable(a: Int, n: Int): String? = text(a, n)?.takeIf { s -> s.all { it.code in 0x20..0x7E } }
}
