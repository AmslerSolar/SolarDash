// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.driver.spec

import ch.amslersolar.solardash.driver.modbus.ModbusException
import ch.amslersolar.solardash.driver.modbus.RegisterBlock
import ch.amslersolar.solardash.driver.sunspec.RegisterReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * A meter's register image. Reading an undefined register throws Modbus exception 2 (like a strict meter), the
 * unit id and the request size limits of the spec are checked on every request.
 * (Moved here from the meter driver tests for the golden tests.)
 */
internal class FakeMeterImage(
    private val unitId: Int,
    private val maxCount: Int,
    private val evenOnly: Boolean = false,
) : RegisterReader {
    val regs = HashMap<Int, Int>()
    val requests = mutableListOf<Pair<Int, Int>>()

    /** Answers for exact (address, count) requests, e.g. Carlo Gavazzi's 000Bh read with length 1. */
    val exact = HashMap<Pair<Int, Int>, IntArray>()

    fun u16(a: Int, v: Int) = apply { regs[a] = v and 0xFFFF }
    fun words(a: Int, vararg w: Int) = apply { w.forEachIndexed { i, v -> regs[a + i] = v and 0xFFFF } }

    /** 32-bit value, [lswFirst] = least significant word at the lower address. */
    fun u32(a: Int, v: Long, lswFirst: Boolean = false) = apply {
        val hi = ((v shr 16) and 0xFFFF).toInt()
        val lo = (v and 0xFFFF).toInt()
        if (lswFirst) words(a, lo, hi) else words(a, hi, lo)
    }

    fun s32(a: Int, v: Int, lswFirst: Boolean = false) = u32(a, v.toLong() and 0xFFFFFFFFL, lswFirst)
    fun f32(a: Int, v: Double, lswFirst: Boolean = false) = u32(a, v.toFloat().toRawBits().toLong() and 0xFFFFFFFFL, lswFirst)
    fun u64(a: Int, v: Long) = words(a, (v ushr 48).toInt(), (v ushr 32).toInt(), (v ushr 16).toInt(), v.toInt())
    fun ascii(a: Int, n: Int, s: String) = apply {
        val p = s.padEnd(n * 2, '\u0000')
        for (i in 0 until n) regs[a + i] = (p[2 * i].code shl 8) or p[2 * i + 1].code
    }

    override suspend fun read(unitId: Int, register: Int, count: Int): RegisterBlock {
        assertEquals(this.unitId, unitId)
        assertTrue("request of $count registers at ${register.toString(16)} exceeds $maxCount", count in 1..maxCount)
        if (evenOnly) assertTrue("odd request ${register.toString(16)}/$count", register % 2 == 0 && count % 2 == 0)
        requests += register to count
        val values = exact[register to count] ?: IntArray(count) { regs[register + it] ?: throw ModbusException(2) }
        val data = ByteArray(count * 2)
        values.forEachIndexed { i, v ->
            data[2 * i] = (v shr 8).toByte()
            data[2 * i + 1] = v.toByte()
        }
        return RegisterBlock(register, data)
    }
}
