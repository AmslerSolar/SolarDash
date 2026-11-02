// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.driver.spec

import ch.amslersolar.solardash.driver.modbus.ModbusException
import ch.amslersolar.solardash.driver.modbus.RegisterBlock
import ch.amslersolar.solardash.driver.sunspec.RegisterReader

/**
 * A register image per unit id that, like a strict device, rejects any read touching an undefined register.
 * (Moved here from the consumer driver tests for the golden tests.)
 */
class FakeRegisters(private val lowWordFirst: Boolean = false) : RegisterReader {
    val units = HashMap<Int, HashMap<Int, Int>>()
    val requests = mutableListOf<Triple<Int, Int, Int>>()
    var unit = 1
    /** Fails every read with this Modbus exception code (e.g. 1 = illegal function). */
    var failWith: Int? = null

    fun on(unit: Int) = apply { this.unit = unit }
    private val regs get() = units.getOrPut(unit) { HashMap() }

    fun u16(a: Int, v: Int) = apply { regs[a] = v and 0xFFFF }
    fun u32(a: Int, v: Long) = apply {
        val hi = ((v shr 16) and 0xFFFF).toInt()
        val lo = (v and 0xFFFF).toInt()
        if (lowWordFirst) { regs[a] = lo; regs[a + 1] = hi } else { regs[a] = hi; regs[a + 1] = lo }
    }
    fun f32(a: Int, v: Double) = u32(a, v.toFloat().toRawBits().toLong() and 0xFFFFFFFFL)
    fun f64(a: Int, v: Double) = apply {
        val bits = v.toRawBits()
        val words = (0 until 4).map { ((bits shr (48 - 16 * it)) and 0xFFFF).toInt() }
        val ordered = if (lowWordFirst) words.reversed() else words
        ordered.forEachIndexed { i, w -> regs[a + i] = w }
    }
    fun text(a: Int, n: Int, s: String) = apply {
        val padded = s.padEnd(n * 2, '\u0000')
        for (i in 0 until n) regs[a + i] = (padded[2 * i].code shl 8) or padded[2 * i + 1].code
    }
    fun nan(a: Int, n: Int) = apply { for (i in 0 until n) regs[a + i] = 0xFFFF }
    fun remove(a: Int, n: Int = 1) = apply { for (i in 0 until n) regs.remove(a + i) }

    override suspend fun read(unitId: Int, register: Int, count: Int): RegisterBlock {
        requests += Triple(unitId, register, count)
        failWith?.let { throw ModbusException(it) }
        val image = units[unitId] ?: throw ModbusException(11)
        val data = ByteArray(count * 2)
        for (i in 0 until count) {
            val v = image[register + i] ?: throw ModbusException(2)
            data[2 * i] = (v shr 8).toByte()
            data[2 * i + 1] = v.toByte()
        }
        return RegisterBlock(register, data)
    }
}
