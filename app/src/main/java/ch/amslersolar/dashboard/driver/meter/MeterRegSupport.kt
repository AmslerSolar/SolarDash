// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.meter

import ch.amslersolar.dashboard.driver.Details.fmt
import ch.amslersolar.dashboard.driver.Details.section
import ch.amslersolar.dashboard.driver.modbus.ModbusException
import ch.amslersolar.dashboard.driver.modbus.RegisterBlock
import ch.amslersolar.dashboard.driver.sunspec.RegisterReader
import ch.amslersolar.dashboard.model.DetailSection
import ch.amslersolar.dashboard.model.PhaseReading
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One value in a meter's register map: [size] registers from [addr]. [alone] fields must be read in a request of
 * their own (e.g. Carlo Gavazzi registers documented as "limited to a word at a time").
 */
internal data class RegField(val addr: Int, val size: Int, val alone: Boolean = false)

internal fun regFields(size: Int, vararg addrs: Int): List<RegField> = addrs.map { RegField(it, size) }

/**
 * Reads [RegField]s in as few requests as the meter allows and remembers what it rejects.
 *
 * Fields are grouped into blocks of at most `maxRegs` registers; a gap of up to `maxGap` registers between two fields
 * is read along. If the meter rejects a block with a Modbus exception, its gap-free runs are read instead, and if a
 * run is rejected too, its fields one by one; rejected requests are remembered and fields the meter rejects are left
 * out until [forgetMissing] (static refresh).
 * Other I/O errors (timeouts, connection loss) propagate: the meter is offline.
 */
internal class RegFieldReader(private val reader: RegisterReader, private val unitId: Int) {
    private val split = mutableSetOf<Int>()
    private val missing = mutableSetOf<Int>()

    fun forgetMissing() {
        missing.clear()
        split.clear()
    }

    suspend fun fetch(fields: List<RegField>, maxRegs: Int, maxGap: Int = 0): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        for (block in blocks(fields, maxRegs, maxGap)) {
            if (readBlock(block, out)) continue
            // Rejected (e.g. an undocumented gap): gap-free runs, then single fields.
            for (run in blocks(block, maxRegs, 0)) {
                if (run.size > 1 && readBlock(run, out)) continue
                for (f in run) {
                    if (f.addr in missing) continue
                    try {
                        out.putAll(reader.read(unitId, f.addr, f.size).values())
                    } catch (_: ModbusException) {
                        missing += f.addr
                    }
                }
            }
        }
        return out
    }

    /** One request for [block] unless it was rejected before; false when (now or earlier) rejected. */
    private suspend fun readBlock(block: List<RegField>, out: MutableMap<Int, Int>): Boolean {
        val start = block.first().addr
        val end = block.maxOf { it.addr + it.size }
        val key = start * 0x10000 + end
        if (key in split) return false
        return try {
            out.putAll(reader.read(unitId, start, end - start).values())
            true
        } catch (_: ModbusException) {
            split += key
            false
        }
    }

    /** One request; null when the device answers with a Modbus exception. */
    suspend fun tryRead(addr: Int, size: Int): Map<Int, Int>? = try {
        reader.read(unitId, addr, size).values()
    } catch (_: ModbusException) {
        null
    }

    private fun RegisterBlock.values(): Map<Int, Int> = (start until start + count).associateWith { u16(it) }

    companion object {
        fun blocks(fields: List<RegField>, maxRegs: Int, maxGap: Int): List<List<RegField>> {
            val out = mutableListOf<MutableList<RegField>>()
            for (f in fields.sortedBy { it.addr }) {
                val cur = out.lastOrNull()
                val curEnd = cur?.maxOf { it.addr + it.size }
                if (cur == null || f.alone || cur.first().alone || f.addr - curEnd!! > maxGap ||
                    f.addr + f.size - cur.first().addr > maxRegs
                ) {
                    out += mutableListOf(f)
                } else {
                    cur += f
                }
            }
            return out
        }
    }
}

/**
 * Register values by address. Multi-register values are decoded with the meter's word order: [lswFirst] = the
 * least significant 16-bit word comes first (Carlo Gavazzi "LSW->MSW"); bytes within a word are always MSB first.
 * Any register missing → null.
 */
internal class RegImage(private val regs: Map<Int, Int>, private val lswFirst: Boolean = false) {
    operator fun plus(other: Map<Int, Int>) = RegImage(regs + other, lswFirst)

    fun has(a: Int) = a in regs
    fun u16(a: Int): Int? = regs[a]
    fun s16(a: Int): Int? = u16(a)?.toShort()?.toInt()

    /** Words of an n-register value, most significant first. */
    private fun words(a: Int, n: Int): List<Int>? {
        val w = (a until a + n).map { regs[it] ?: return null }
        return if (lswFirst) w.reversed() else w
    }

    fun u32(a: Int): Long? = words(a, 2)?.let { (hi, lo) -> (hi.toLong() shl 16) or lo.toLong() }
    fun s32(a: Int): Int? = u32(a)?.toInt()
    fun u64(a: Int): Long? = words(a, 4)?.fold(0L) { acc, w -> (acc shl 16) or w.toLong() }
    fun f32(a: Int): Double? = u32(a)?.let { Float.fromBits(it.toInt()).toDouble() }?.takeIf { it.isFinite() }

    /** Most significant word of a 32-bit value, for overflow markers (e.g. 7FFFh). */
    fun msw32(a: Int): Int? = words(a, 2)?.first()

    /** Two ASCII characters per register, MSB first; NULs and padding trimmed. */
    fun ascii(a: Int, n: Int): String? {
        val chars = (a until a + n).map { regs[it] ?: return null }.flatMap { listOf(it shr 8, it and 0xFF) }
        return chars.filter { it in 0x20..0x7E }.map { it.toChar() }.joinToString("").trim().takeIf { it.isNotEmpty() }
    }
}

internal object MeterFmt {
    fun w(v: Double?) = fmt(v?.let { Math.round(it).toInt() }, "W")
    fun va(v: Double?) = fmt(v?.let { Math.round(it).toInt() }, "VA")
    fun vars(v: Double?) = fmt(v?.let { Math.round(it).toInt() }, "var")
    fun v(v: Double?) = fmt(v, "V", 1)
    fun a(v: Double?) = fmt(v, "A", 2)
    fun pf(v: Double?) = fmt(v, "", 3)
    fun hz(v: Double?) = fmt(v, "Hz", 2)
    fun kwh(v: Double?, decimals: Int = 2) = fmt(v, "kWh", decimals)
    fun kvarh(v: Double?, decimals: Int = 2) = fmt(v, "kvarh", decimals)

    /** Meter-native power (+ import) to the app's grid convention (+ export, − import). */
    fun exportW(importPositiveW: Double?): Int? = importPositiveW?.let { -it.roundToInt() }

    fun phase(name: String, voltageV: Double?, currentA: Double?, frequencyHz: Double?, importPositiveW: Double?) =
        voltageV?.let {
            PhaseReading(
                name = name,
                voltageV = it,
                currentA = currentA?.let(::abs),
                frequencyHz = frequencyHz ?: 0.0,
                powerW = exportW(importPositiveW),
            )
        }

    /** One detail section per phase, values in the meter's own sign (+ import). */
    fun phaseSection(
        name: String,
        vln: Double?, vll: Double? = null, a: Double?, w: Double?, va: Double? = null, vars: Double? = null,
        pf: Double? = null, extra: List<Pair<String, String?>> = emptyList(),
    ): DetailSection = section(
        name,
        "Voltage L-N" to v(vln),
        "Voltage L-L" to v(vll),
        "Current" to a(a),
        "Active power (+ import)" to w(w),
        "Apparent power" to va(va),
        "Reactive power" to vars(vars),
        "Power factor" to pf(pf),
        *extra.toTypedArray(),
    )
}
