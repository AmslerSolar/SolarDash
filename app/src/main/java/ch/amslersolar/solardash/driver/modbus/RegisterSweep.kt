// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.driver.modbus

import kotlinx.coroutines.delay
import java.io.IOException

/**
 * Reads the registers of a range with one read function (03 or 04), for documenting a device's register map, in
 * passes from coarse to fine so that stopping early still leaves the most useful part:
 * 1. [known]: the registers known from device files, merged into blocks of at most [LARGE] (for the device's own
 *    file, a refused block is read again range by range);
 * 2. [blocks] of [LARGE] over the whole range, skipping what already answered;
 * 3. [blocks] of [SMALL] where something is still unanswered;
 * 4. [probes]: the first register of every [SMALL] block that hasn't answered at all, alone;
 * 5. [edges]: from every answered stretch outwards, as far as the device answers (at most [SMALL] each side).
 * Stretches narrower than [SMALL] that don't touch a block start may stay unfound (not wanted here).
 *
 * The sweep stops, with what it has, after [MAX_SILENT] requests in a row without any answer from a device that
 * hasn't answered at all yet (unreachable, wrong unit id); once it has, after [GONE_CHECK] silent requests in a row
 * it reads a register that answered before, and stops if that gets no answer either (the device has gone).
 */
class RegisterSweep(val from: Int = 0, val to: Int = 0xFFFF) {
    init {
        require(from in 0..0xFFFF && to in from..0xFFFF) { "Register range $from..$to" }
    }

    /** Answered registers → raw value (for planning the passes and the counts on screen; the log has everything). */
    val values = sortedMapOf<Int, Int>()
    /** Why the sweep ended early; null while it runs and when every pass got through. */
    var stopped: String? = null
        private set
    /** The pass running (1…), set by whoever runs the passes, for the display. */
    var pass = 0
    /** Steps in the current pass (requests, blocks or stretches), done and planned. */
    var passDone = 0
        private set
    var passTotal = 0
        private set

    private var silentInRow = 0
    private var answered = false
    private var known: Int? = null

    private class Stop : Exception()

    /** One request; the registers, or null when refused or unanswered. */
    private suspend fun attempt(read: suspend (Int, Int) -> RegisterBlock, start: Int, count: Int, pauseMs: Long): RegisterBlock? {
        val block = try {
            read(start, count).also {
                silentInRow = 0
                answered = true
                if (known == null) known = start
                for (r in start until start + count) values[r] = it.u16(r)
            }
        } catch (e: ModbusException) {
            silentInRow = 0
            answered = true
            null
        } catch (e: IOException) {
            silentInRow++
            if (!answered && silentInRow >= MAX_SILENT) stop(e)
            val k = known
            if (k != null && silentInRow >= GONE_CHECK) {
                silentInRow = 0
                try {
                    read(k, 1)
                } catch (gone: IOException) {
                    stop(gone)
                } catch (_: ModbusException) {
                }
            }
            null
        }
        if (pauseMs > 0) delay(pauseMs)
        return block
    }

    private fun stop(e: Exception): Nothing {
        stopped = e.message ?: e::class.simpleName
        throw Stop()
    }

    /** Runs [body] as one pass of [total] requests; false when the sweep has stopped. */
    private suspend fun pass(total: Int, body: suspend () -> Unit): Boolean {
        if (stopped != null) return false
        passDone = 0
        passTotal = total
        try {
            body()
        } catch (_: Stop) {
            return false
        }
        return true
    }

    private suspend fun each(plan: List<Pair<Int, Int>>, read: suspend (Int, Int) -> RegisterBlock, pauseMs: Long, progress: () -> Unit) =
        pass(plan.size) {
            for ((start, count) in plan) {
                attempt(read, start, count, pauseMs)
                passDone++
                progress()
            }
        }

    /**
     * Pass 1: [ranges] (registers known from device files), merged where they touch, cut to blocks of [LARGE]. With
     * [split] (the ranges are the device's own, from its file), a refused block is read again one range at a time,
     * so each documented register gets an answer or a refusal of its own.
     */
    suspend fun known(
        ranges: Collection<IntRange>,
        read: suspend (Int, Int) -> RegisterBlock,
        split: Boolean = false,
        pauseMs: Long = 0,
        progress: () -> Unit = {},
    ): Boolean {
        val regs = sortedSetOf<Int>()
        for (r in ranges) for (a in r) if (a in from..to) regs += a
        val plan = mutableListOf<Pair<Int, Int>>()
        var start = -1
        var last = -1
        for (a in regs) {
            if (start >= 0 && a == last + 1 && a - start < LARGE) {
                last = a
                continue
            }
            if (start >= 0) plan += start to last - start + 1
            start = a
            last = a
        }
        if (start >= 0) plan += start to last - start + 1
        if (!split) return each(plan, read, pauseMs, progress)
        val own = ranges.filter { it.first >= from && it.last <= to }.distinct().sortedBy { it.first }
        return pass(plan.size) {
            for ((start, count) in plan) {
                if (attempt(read, start, count, pauseMs) == null) {
                    for (r in own) if (r.first >= start && r.last < start + count && (r.first..r.last).any { it !in values }) {
                        attempt(read, r.first, r.last - r.first + 1, pauseMs)
                    }
                }
                passDone++
                progress()
            }
        }
    }

    /** Passes 2 and 3: aligned blocks of [size] that still hold an unanswered register. */
    suspend fun blocks(size: Int, read: suspend (Int, Int) -> RegisterBlock, pauseMs: Long = 0, progress: () -> Unit = {}): Boolean =
        each(unanswered(size).map { it.first to it.last - it.first + 1 }, read, pauseMs, progress)

    /** The aligned blocks of [size], cut to the range, that still hold an unanswered register. */
    private fun unanswered(size: Int): List<IntRange> {
        val plan = mutableListOf<IntRange>()
        var start = from - from % size
        while (start <= to) {
            val first = maxOf(start, from)
            val last = minOf(start + size - 1, to)
            if ((first..last).any { it !in values }) plan += first..last
            start += size
        }
        return plan
    }

    /** Pass 4: the first register of every block of [SMALL] without any answer, alone. */
    suspend fun probes(read: suspend (Int, Int) -> RegisterBlock, pauseMs: Long = 0, progress: () -> Unit = {}): Boolean {
        val plan = mutableListOf<Pair<Int, Int>>()
        var start = from - from % SMALL
        while (start <= to) {
            val first = maxOf(start, from)
            if ((first..minOf(start + SMALL - 1, to)).none { it in values }) plan += first to 1
            start += SMALL
        }
        return each(plan, read, pauseMs, progress)
    }

    /**
     * Pass 5: from each answered stretch outwards, the furthest register that still answers, found by halving (a
     * read reaching into a gap, or starting in one, is refused), at most [SMALL] registers each side.
     */
    suspend fun edges(read: suspend (Int, Int) -> RegisterBlock, pauseMs: Long = 0, progress: () -> Unit = {}): Boolean {
        val stretches = mutableListOf<IntRange>()
        for (r in values.keys) {
            val last = stretches.lastOrNull()
            if (last != null && r == last.last + 1) stretches[stretches.size - 1] = last.first..r else stretches += r..r
        }
        return pass(stretches.size) {
            for (s in stretches) {
                // right: the largest e in s.last+1 .. s.last+SMALL with s.last+1..e answering
                val a = s.last + 1
                if (a <= to && a !in values) {
                    var good = a - 1
                    var bad = minOf(to, s.last + SMALL) + 1
                    while (bad - good > 1) {
                        val mid = (good + bad) / 2
                        if (attempt(read, a, mid - a + 1, pauseMs) != null) good = mid else bad = mid
                    }
                }
                // left: the smallest b in s.first-SMALL .. s.first-1 with b..s.first-1 answering
                val z = s.first - 1
                if (z >= from && z !in values) {
                    var good = z + 1
                    var bad = maxOf(from, s.first - SMALL) - 1
                    while (good - bad > 1) {
                        val mid = (good + bad + 1) / 2
                        if (attempt(read, mid, z - mid + 1, pauseMs) != null) good = mid else bad = mid
                    }
                }
                passDone++
                progress()
            }
        }
    }

    companion object {
        const val LARGE = 64
        const val SMALL = 16
        /** Requests in a row without an answer, before any answer, after which the device counts as unreachable. */
        const val MAX_SILENT = 64
        /** Silent requests in a row after which a register that answered before is read again. */
        const val GONE_CHECK = 32
        /**
         * The most a device's file can make the sweep wait between requests (its min_interval_ms, meant for polling);
         * other traffic is paused during a sweep, so devices without one get none.
         */
        const val MAX_GAP_MS = 30L
        /** Timeout per request while sweeping, at most (and until the device has answered); see [AnswerTimeout]. */
        const val TIMEOUT_MS = 1_000
    }
}

/**
 * The timeout per request while sweeping, from how long the device takes to answer: [FACTOR] times the slowest of
 * its last [WINDOW] answers (refusals included), at least [MIN_MS] and at most [RegisterSweep.TIMEOUT_MS].
 */
class AnswerTimeout {
    private val recent = ArrayDeque<Long>()

    @Volatile
    var ms = RegisterSweep.TIMEOUT_MS
        private set

    /** Takes an answer that came after [elapsedMs]; the new timeout. */
    @Synchronized
    fun answered(elapsedMs: Long): Int {
        recent.addLast(elapsedMs)
        if (recent.size > WINDOW) recent.removeFirst()
        ms = (recent.max() * FACTOR).coerceIn(MIN_MS.toLong(), RegisterSweep.TIMEOUT_MS.toLong()).toInt()
        return ms
    }

    companion object {
        const val FACTOR = 3
        const val WINDOW = 32
        /** Wi-Fi hiccups, and a block of 64 registers takes ~140 ms to come back over RS485 at 9600 baud. */
        const val MIN_MS = 300
    }
}
