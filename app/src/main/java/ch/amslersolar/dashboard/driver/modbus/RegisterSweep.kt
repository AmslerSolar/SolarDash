// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.modbus

import kotlinx.coroutines.delay
import java.io.IOException

/**
 * Reads every register of a range, only with a read function (03 or 04): for documenting a device's register map.
 *
 * Blocks of [BLOCK] registers; a block the device refuses (Modbus exception, or no answer) is read again one register
 * at a time, so the answered ones are found between the missing ones. (Not halved: most of a full 0..65535 sweep is
 * empty, and a refused block costs 1 + 32 requests that way instead of 63.) Many meters don't answer registers
 * they don't have, so a silent stretch is normal; the sweep only ends early, with what it has, after [MAX_SILENT]
 * requests in a row without an answer from a device that hasn't answered at all yet (unreachable, wrong unit id),
 * or [MAX_SILENT_REGISTERS] single registers in a row once it has.
 */
class RegisterSweep(val from: Int = 0, val to: Int = 0xFFFF) {
    init {
        require(from in 0..0xFFFF && to in from..0xFFFF) { "Register range $from..$to" }
    }

    val values = sortedMapOf<Int, Int>()
    /** Register → Modbus exception code, for single registers the device refused. */
    val exceptions = sortedMapOf<Int, Int>()
    /** Single registers without an answer (timeout or a broken answer). */
    val silent = sortedSetOf<Int>()
    /** Registers done so far (answered or not). */
    var done = 0
        private set
    /** Why the sweep ended before [to]; null while it runs and when it got through the whole range. */
    var stopped: String? = null
        private set
    val complete: Boolean get() = done == to - from + 1

    private var silentInRow = 0
    private var silentRegistersInRow = 0
    /** The device answered at least once (values or an exception). */
    private var answered = false

    /**
     * [read] is one request (start, count). Cancelling the coroutine ends the sweep; what was read until then stays
     * here. [progress] is called after each finished piece.
     */
    suspend fun run(
        read: suspend (start: Int, count: Int) -> RegisterBlock,
        pauseMs: Long = PAUSE_MS,
        progress: () -> Unit = {},
    ) {
        var start = from
        while (start <= to) {
            val count = minOf(BLOCK, to - start + 1)
            if (!sweep(start, count, read, pauseMs, progress)) return
            start += count
        }
    }

    private suspend fun sweep(
        start: Int,
        count: Int,
        read: suspend (Int, Int) -> RegisterBlock,
        pauseMs: Long,
        progress: () -> Unit,
    ): Boolean {
        val failure = try {
            val block = read(start, count)
            for (r in start until start + count) values[r] = block.u16(r)
            silentInRow = 0
            silentRegistersInRow = 0
            answered = true
            null
        } catch (e: ModbusException) {
            silentInRow = 0
            silentRegistersInRow = 0
            answered = true
            e
        } catch (e: IOException) {
            silentInRow++
            if (count == 1) silentRegistersInRow++
            e
        }
        if (pauseMs > 0) delay(pauseMs)
        if (failure != null) {
            if (if (answered) silentRegistersInRow >= MAX_SILENT_REGISTERS else silentInRow >= MAX_SILENT) {
                stopped = failure.message ?: failure::class.simpleName
                return false
            }
            if (count > 1) {
                for (r in start until start + count) if (!sweep(r, 1, read, pauseMs, progress)) return false
                return true
            }
            if (failure is ModbusException) exceptions[start] = failure.code else silent += start
        }
        done += count
        progress()
        return true
    }

    companion object {
        const val BLOCK = 32
        const val MAX_SILENT = 8
        const val MAX_SILENT_REGISTERS = 32
        /** Pause between requests: meters behind an RS485 gateway are slow, and may be polled at the same time. */
        const val PAUSE_MS = 30L
    }
}
