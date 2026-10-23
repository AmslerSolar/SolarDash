// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.sunspec

import ch.amslersolar.dashboard.driver.modbus.ModbusException
import ch.amslersolar.dashboard.driver.modbus.RegisterBlock
import org.junit.Assert.assertEquals

/**
 * A register image for driver tests: registers by on-the-wire address. Reading an undefined register throws
 * Modbus exception 2, unless [filler] is set (devices such as the Conext CL answer 0xFF for unused addresses).
 * [rejectAcross]: addresses no read may span (it must end before or start at them), like the Conext Gateway's
 * "blank row" boundaries. Requests are checked for the unit id and the 125-register limit.
 */
class FakeRegisterImage(
    private val unitId: Int,
    private val lowWordFirst: Boolean = false,
    private val filler: Int? = null,
    private val rejectAcross: Set<Int> = emptySet(),
) : RegisterReader {
    val regs = HashMap<Int, Int>()
    val requests = mutableListOf<Pair<Int, Int>>()

    fun u16(a: Int, v: Int) = apply { regs[a] = v and 0xFFFF }
    fun s16(a: Int, v: Int) = u16(a, v)
    fun u32(a: Int, v: Long) = apply {
        val hi = ((v shr 16) and 0xFFFF).toInt()
        val lo = (v and 0xFFFF).toInt()
        if (lowWordFirst) { regs[a] = lo; regs[a + 1] = hi } else { regs[a] = hi; regs[a + 1] = lo }
    }
    fun f32(a: Int, v: Double) = u32(a, v.toFloat().toRawBits().toLong() and 0xFFFFFFFFL)
    fun text(a: Int, n: Int, s: String) = apply {
        val p = s.padEnd(n * 2, '\u0000')
        for (i in 0 until n) regs[a + i] = (p[2 * i].code shl 8) or p[2 * i + 1].code
    }

    /** A SunSpec chain: "SunS" at [base], then the models in order, then the end marker. */
    fun sunSpec(base: Int = 40000, vararg models: Triple<Int, Int, FakeRegisterImage.(Int) -> Unit>) = apply {
        regs[base] = 0x5375; regs[base + 1] = 0x6E53
        var next = base + 2
        for ((id, length, fill) in models) {
            regs[next] = id; regs[next + 1] = length
            for (o in 2 until length + 2) regs[next + o] = 0
            fill(next)
            next += length + 2
        }
        regs[next] = 0xFFFF; regs[next + 1] = 0
    }

    override suspend fun read(unitId: Int, register: Int, count: Int): RegisterBlock {
        assertEquals(this.unitId, unitId)
        check(count in 1..125) { "Request of $count registers" }
        requests += register to count
        if (rejectAcross.any { it > register && it < register + count }) throw ModbusException(2)
        val data = ByteArray(count * 2)
        for (i in 0 until count) {
            val v = regs[register + i] ?: filler ?: throw ModbusException(2)
            data[2 * i] = (v shr 8).toByte(); data[2 * i + 1] = v.toByte()
        }
        return RegisterBlock(register, data)
    }

    companion object {
        fun model(id: Int, length: Int, fill: FakeRegisterImage.(Int) -> Unit = {}) = Triple(id, length, fill)

        /** Common model (1) with [length] 66 (or 65): manufacturer, model, version, serial at their offsets. */
        fun common(maker: String, model: String, version: String = "", serial: String = "", length: Int = 66) =
            model(1, length) { b -> text(b + 2, 16, maker); text(b + 18, 16, model); text(b + 42, 8, version); text(b + 50, 16, serial) }
    }
}
