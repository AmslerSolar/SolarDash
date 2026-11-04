// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.driver.sunspec

import ch.amslersolar.solardash.driver.modbus.ModbusException
import ch.amslersolar.solardash.driver.modbus.RegisterBlock
import java.io.IOException
import kotlin.math.pow

/** What a SunSpec device needs from a Modbus client, so tests can supply a register image. */
fun interface RegisterReader {
    suspend fun read(unitId: Int, register: Int, count: Int): RegisterBlock
}

/** Walks the SunSpec model chain (ID, length, ID, length, …) once and reads whole model blocks on demand. */
class SunSpecDevice(private val reader: RegisterReader, private val unitId: Int) {
    private var models: Map<Int, Pair<Int, Int>>? = null // model id → (address of its ID register, length)
    /** The Common model says 65 but is read as 66 (see [padded]). */
    private var paddedCommon = false

    suspend fun discover(): Map<Int, Pair<Int, Int>> {
        models?.let { return it }
        for (base in BASES) {
            val head = try {
                reader.read(unitId, base, 2)
            } catch (_: ModbusException) {
                continue
            }
            if (head.u16(base) != SUNS_1 || head.u16(base + 1) != SUNS_2) continue
            val found = linkedMapOf<Int, Pair<Int, Int>>()
            var addr = base + 2
            repeat(MAX_MODELS) {
                val hdr = reader.read(unitId, addr, 2)
                val id = hdr.u16(addr)
                var length = hdr.u16(addr + 1)
                if (id == END || length == 0) return checked(found)
                if (id == COMMON && length == 65 && padded(addr)) { length = 66; paddedCommon = true }
                found.putIfAbsent(id, addr to length)
                addr += 2 + length
            }
            return checked(found)
        }
        throw IOException("No SunSpec map found on this device (looked for 'SunS' at 0, 40000, 50000)")
    }

    /**
     * The SunSpec standard requires the Common model (1) first. A device that's still starting up or confused (seen on
     * an LG ESS after a restart: garbled model ids, lengths intact) doesn't have it there; such a map isn't kept, so
     * the next poll looks again.
     */
    private fun checked(found: LinkedHashMap<Int, Pair<Int, Int>>): Map<Int, Pair<Int, Int>> {
        val first = found.keys.firstOrNull()
        if (first != COMMON) throw IOException("SunSpec map not valid (first model ${first ?: "none"}, expected 1); the device may still be starting")
        models = found
        return found
    }

    /**
     * A Common model that says 65 registers but lays out 66: a pad register (0) follows, then the next model's id
     * (seen on the Conext CL, whose map documents 65 and a pad at the 66th). A walk with 65 would land on the pad.
     */
    private suspend fun padded(addr: Int): Boolean = try {
        val next = reader.read(unitId, addr + 2 + 65, 2)
        next.u16(addr + 2 + 65) == 0 && next.u16(addr + 2 + 66) != 0
    } catch (_: ModbusException) {
        false
    }

    /** Forgets the model map, e.g. after the device rebooted or updated its firmware. */
    fun forget() {
        models = null
    }

    suspend fun model(id: Int): Model? {
        val (addr, length) = discover()[id] ?: return null
        val block = readLong(addr, length + 2)
        // The header must still say what the map said; otherwise the device changed its map (reboot, update).
        val declared = if (id == COMMON && paddedCommon) 65 else length
        if (block.u16(addr) != id || block.u16(addr + 1) != declared) {
            forget()
            throw IOException("SunSpec map changed (model $id); it's discovered again with the next poll")
        }
        return Model(block, addr, length)
    }

    /** First of [ids] the device has (e.g. float 113 before int+SF 103), with the id found. */
    suspend fun firstModel(vararg ids: Int): Pair<Int, Model>? {
        val found = discover()
        val id = ids.firstOrNull { it in found } ?: return null
        return id to model(id)!!
    }

    /** Models can be longer than the 125 registers one request may ask for (e.g. meter model 213: 126). */
    private suspend fun readLong(addr: Int, count: Int): RegisterBlock {
        var block = reader.read(unitId, addr, minOf(count, MAX_READ))
        while (block.count < count) block += reader.read(unitId, addr + block.count, minOf(count - block.count, MAX_READ))
        return block
    }

    companion object {
        private const val SUNS_1 = 0x5375 // "Su"
        private const val SUNS_2 = 0x6E53 // "nS"
        private const val END = 0xFFFF
        private const val COMMON = 1
        private const val MAX_MODELS = 32
        private const val MAX_READ = 125
        /** Where a chain may start ("SunS"). */
        val BASES = listOf(0, 40000, 50000)
    }
}

/** One model block; offsets are relative to the model's ID register, as in the SunSpec tables. "Not implemented" → null. */
class Model(private val b: RegisterBlock, private val addr: Int, private val length: Int) {
    fun has(offset: Int, size: Int = 1) = offset + size <= length + 2

    fun u16(o: Int): Int? = if (has(o)) b.u16(addr + o).takeIf { it != 0xFFFF } else null
    fun s16(o: Int): Int? = if (has(o)) b.s16(addr + o).takeIf { it != Short.MIN_VALUE.toInt() } else null
    fun u32(o: Int): Long? = if (has(o, 2)) b.u32(addr + o).takeIf { it != 0xFFFFFFFFL } else null
    fun s32(o: Int): Int? = if (has(o, 2)) b.s32(addr + o).takeIf { it != Int.MIN_VALUE } else null
    fun s64(o: Int): Long? = if (has(o, 4)) b.s64(addr + o).takeIf { it != Long.MIN_VALUE } else null
    fun f32(o: Int): Float? = if (has(o, 2)) b.f32(addr + o).takeIf { !it.isNaN() } else null
    fun text(o: Int, size: Int): String = if (has(o, size)) b.ascii(addr + o, size).trim { it <= ' ' || it == '\u0000' } else ""

    /** value × 10^sf; a scale factor that's absent or not implemented falls back to [defaultSf]. */
    fun scaled(value: Number?, sfOffset: Int?, defaultSf: Int = 0): Double? {
        value ?: return null
        val sf = sfOffset?.let { s16(it) } ?: defaultSf
        return value.toDouble() * 10.0.pow(sf)
    }
}
