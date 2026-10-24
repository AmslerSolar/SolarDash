// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.spec

import ch.amslersolar.dashboard.driver.modbus.ModbusException
import ch.amslersolar.dashboard.driver.modbus.RegisterBlock
import ch.amslersolar.dashboard.driver.sunspec.RegisterReader
import org.junit.Assert.assertEquals

/**
 * A meter register image. Like a strict device, it rejects (exception 2) any read that touches a register it
 * doesn't have; with [lenient] it answers undefined registers with 0 instead.
 */
internal class MeterRegisterFake(
    private val unitId: Int,
    private val lowWordFirst: Boolean = false,
    private val lenient: Boolean = false,
) : RegisterReader {
    val regs = HashMap<Int, Int>()
    val reads = mutableListOf<Pair<Int, Int>>()

    fun u16(a: Int, v: Int) = apply { regs[a] = v and 0xFFFF }

    private fun words(a: Int, v: Long, n: Int) = apply {
        val w = (0 until n).map { ((v shr (16 * (n - 1 - it))) and 0xFFFF).toInt() }
        (if (lowWordFirst) w.reversed() else w).forEachIndexed { i, x -> regs[a + i] = x }
    }

    fun u32(a: Int, v: Long) = words(a, v, 2)
    fun s32(a: Int, v: Int) = words(a, v.toLong() and 0xFFFFFFFFL, 2)
    fun u64(a: Int, v: Long) = words(a, v, 4)
    fun f32(a: Int, v: Double) = u32(a, v.toFloat().toRawBits().toLong() and 0xFFFFFFFFL)
    fun f64(a: Int, v: Double) = words(a, v.toRawBits(), 4)
    fun text(a: Int, n: Int, s: String) = apply {
        val padded = s.padEnd(n * 2, '\u0000')
        for (i in 0 until n) regs[a + i] = (padded[2 * i].code shl 8) or padded[2 * i + 1].code
    }
    fun remove(a: Int, n: Int = 2) = apply { for (i in 0 until n) regs.remove(a + i) }

    override suspend fun read(unitId: Int, register: Int, count: Int): RegisterBlock {
        assertEquals(this.unitId, unitId)
        assert(count in 1..125) { "count $count" }
        reads += register to count
        val data = ByteArray(count * 2)
        for (i in 0 until count) {
            val v = regs[register + i] ?: if (lenient) 0 else throw ModbusException(2)
            data[2 * i] = (v shr 8).toByte()
            data[2 * i + 1] = v.toByte()
        }
        return RegisterBlock(register, data)
    }
}

/** A device that answers nothing (every read rejected). */
internal val silentDevice = RegisterReader { _, _, _ -> throw ModbusException(2) }

internal fun rows(reading: ch.amslersolar.dashboard.model.DeviceReading, title: String): Map<String, String> =
    reading.details.first { it.title == title }.rows.toMap()
