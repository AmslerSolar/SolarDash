// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.spec

import ch.amslersolar.dashboard.driver.modbus.ModbusException
import ch.amslersolar.dashboard.driver.sunspec.RegisterReader
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** One decoded value: a number or a text, with its enum text and set flags where the point has them. */
data class SpecValue(val point: Spec.Point?, val number: Double?, val text: String?, val enumText: String? = null, val flags: List<String> = emptyList()) {
    val trusted: Boolean get() = point?.trusted ?: true
}

/**
 * Reads registers from one table ("holding" or "input"); coils and discrete inputs aren't supported yet.
 * [addressOffset] is added to every address sent: for gateways that renumber a device documented with 1-based
 * register numbers (the file's address X is then asked at X + 1).
 */
class SpecTables(internal val holding: RegisterReader, private val input: RegisterReader, val addressOffset: Int = 0) {
    suspend fun read(table: String, unitId: Int, address: Int, count: Int): IntArray {
        val reader = when (table) {
            "holding" -> holding
            "input" -> input
            else -> throw ModbusException(1) // illegal function: the point just stays empty
        }
        val block = reader.read(unitId, address + addressOffset, count)
        return IntArray(count) { block.u16(address + addressOffset + it) }
    }
}

/**
 * Reads a device with its ModbusSpec file: detects the word order and model (once), reads the points of that model
 * in requests that keep to the device's limits, and decodes, scales and sign-normalises them like ModbusSpec's
 * reference tool (tools/msp.py). Slow points (`poll: slow`) are read on the first poll and then every
 * [slowEvery] polls. A register the device rejects (Modbus exception) leaves its point empty and isn't asked again
 * (nor its block as a whole) until [retryRejectedEvery] polls have passed; a broken connection fails the whole read.
 */
class SpecReader(
    val spec: Spec,
    private val vocabulary: Vocabulary,
    private val tables: SpecTables,
    private val unitId: Int,
    private val slowEvery: Int = 12,
    /** Pause between requests; the device's `min_interval_ms` by default (tests pass 0). */
    private val minIntervalMs: Long = spec.connection.minIntervalMs,
    /** Polls after which registers and blocks the device rejected are asked again. */
    private val retryRejectedEvery: Int = 120,
    /** The model the user picked, for files with `model_choice: user` (ignored otherwise). */
    private val chosenModel: String? = null,
) {
    /** Registers read so far, by "table@unit". */
    private val image = HashMap<String, HashMap<Int, Int>>()
    private var wordOrder: String? = null
    private var byteOrder: String = spec.encoding.byteOrder.let { if (it == "detect") "big" else it }
    private val userChoice get() = spec.modelChoice == "user"
    /** Set by [read] when the device answered nothing and its file says it `sleeps` (powered by its panels). */
    var asleep = false
        private set
    private var answered = 0
    var model: Spec.ModelDef? = null
        private set
    private var modelDetected = false
    private var polls = 0
    private var lastRequest = 0L
    /** (table@unit, address, size) the device rejected on its own, and blocks it rejected as a whole. */
    private val rejected = HashSet<Triple<String, Int, Int>>()
    private val rejectedBlocks = HashSet<Triple<String, Int, Int>>()

    private fun tableKey(table: String, unit: Int?) = "$table@${unit ?: unitId}"

    private fun size(type: String, length: Int?): Int = when (type) {
        "str", "datetime" -> length ?: 1
        "u32", "s32", "f32", "sm32", "bcd32" -> 2
        "u64", "s64", "f64", "bcd64" -> 4
        else -> 1
    }

    /** Detection, then the points due this poll; returns every value it could decode, by key. */
    suspend fun read(): Map<String, SpecValue> {
        if (wordOrder == null || !modelDetected) setup()
        if (polls % retryRejectedEvery == 0) {
            rejected.clear()
            rejectedBlocks.clear()
        }
        val due = points().filter { polls % slowEvery == 0 || it.poll != "slow" }
        polls++
        val regs = due.map { Triple(tableKey(it.table, it.unitId), it.address, size(it.type, it.length)) } +
            due.mapNotNull { p -> p.exponent?.let { Triple(tableKey(it.table, it.unitId), it.address, size(it.type, it.length)) } }
        val alone = due.filter { it.optional }.map { Triple(tableKey(it.table, it.unitId), it.address, size(it.type, it.length)) }.toSet()
        answered = 0
        try {
            fetch(regs, alone)
        } catch (e: IOException) {
            // No answer (timeout, refused, or a gateway saying the device didn't respond): asleep if the file says so.
            if ((e is ModbusException && e.code !in NO_ANSWER) || !spec.connection.sleeps) throw e
        }
        asleep = spec.connection.sleeps && answered == 0
        if (asleep) return emptyMap()
        return values()
    }

    /** Word order and model; repeated until they're known (e.g. the meter had no voltage yet). */
    private suspend fun setup() {
        val e = spec.encoding
        val conditions = listOfNotNull(e.detect) + if (userChoice) emptyList() else spec.models.flatMap { (it.match + it.matchAny).flatten() }
        fetchConditions(conditions)
        if (wordOrder == null) detectOrders()
        if (spec.models.isEmpty()) {
            modelDetected = true
        } else if (userChoice) {
            model = spec.models.find { it.id == chosenModel } // none picked yet: only what every model has
            modelDetected = true
        } else {
            model = spec.models.firstOrNull { m ->
                allGroups(m.match) && (m.matchAny.isEmpty() || anyGroup(m.matchAny))
            }
            modelDetected = model != null
        }
    }

    /**
     * Word and byte order: fixed, or the first combination in which the detect register meets its condition (big
     * before little, the word order varied first); big where none does.
     */
    private fun detectOrders() {
        val e = spec.encoding
        e.detect?.wordOrders?.let { orders ->
            // The device states its word order in a register.
            byteOrder = if (e.byteOrder == "detect") "big" else e.byteOrder
            val v = raw(e.detect, "big", byteOrder) ?: return // not read yet: next poll
            wordOrder = orders[(v as? Double)?.toLong()?.toString() ?: v.toString()] ?: "big"
            return
        }
        val words = if (e.wordOrder == "detect") listOf("big", "little") else listOf(e.wordOrder)
        val bytes = if (e.byteOrder == "detect") listOf("big", "little") else listOf(e.byteOrder)
        val d = e.detect
        if (d == null || (e.wordOrder != "detect" && e.byteOrder != "detect")) {
            wordOrder = words.first(); byteOrder = bytes.first()
            return
        }
        for (b in bytes) for (w in words) {
            if (holds(d, w, b)) {
                wordOrder = w; byteOrder = b
                return
            }
        }
        // Can't tell yet (e.g. no voltage): keep the default and try again with the next poll.
        byteOrder = bytes.first()
    }

    private suspend fun fetchConditions(conditions: List<Spec.Condition>) {
        val regs = conditions.map { Triple(tableKey(it.table, it.unitId), it.address, size(it.type, it.length)) }
        val alone = conditions.filter { it.answers != null }.map { Triple(tableKey(it.table, it.unitId), it.address, size(it.type, it.length)) }.toSet()
        fetch(regs, alone)
    }

    fun points(): List<Spec.Point> = spec.points.filter { p ->
        when {
            p.models == null -> true
            model != null -> model!!.id in p.models
            else -> !userChoice // no model detected yet: everything; none picked by the user: only shared points
        }
    }

    /** Checks and serial number for [identify]: null when a check fails. */
    suspend fun identify(): Pair<Spec.ModelDef?, String?>? {
        setup()
        val id = spec.identify ?: return model to null
        fetchConditions(id.checks.flatten() + id.any.flatten() + id.serials.map { it.first })
        if (!allGroups(id.checks)) return null
        if (id.any.isNotEmpty() && !anyGroup(id.any)) return null
        val serialReg = id.serials.firstOrNull { (_, models) -> models == null || (model != null && model!!.id in models) }?.first
        val serial = serialReg?.let { c -> raw(c)?.let { if (it is Double) it.roundToLong().toString() else it.toString() } }
        return model to serial
    }

    // ---------------------------------------------------------------- requests

    /**
     * Reads [all]: registers inside a fixed block (connection.blocks) by reading that whole block once, those in
     * [alone] (optional points, `answers` conditions) each in a request of its own where any failure, even a
     * timeout, just leaves them empty, the rest in merged requests that don't reach across an [alone] register.
     */
    private suspend fun fetch(all: List<Triple<String, Int, Int>>, alone: Set<Triple<String, Int, Int>> = emptySet()) {
        val fixed = spec.connection.blocks.map { Triple(tableKey(it.table, it.unitId), it.address, it.count) }
        fun inside(r: Triple<String, Int, Int>, b: Triple<String, Int, Int>) = r.first == b.first && b.second <= r.second && r.second + r.third <= b.second + b.third
        for (b in fixed.filter { b -> all.any { inside(it, b) } }) {
            try {
                put(b.first, b.second, request(b.first, b.second, b.third))
            } catch (e: ModbusException) {
                if (e.code in NO_ANSWER) throw e // else: not on this device
            }
        }
        val rest = all.filter { r -> fixed.none { inside(r, it) } }
        for (r in rest.filter { it in alone }.distinct()) {
            for ((k2, a2, n2) in blocks(listOf(r), gap = 0)) {
                try {
                    put(k2, a2, request(k2, a2, n2))
                } catch (_: IOException) {
                    // An optional sensor that isn't fitted, or a register only other models have.
                }
            }
        }
        val merged = rest.filter { it !in alone }
        // Requests don't reach across an optional register: group by the optional registers before them.
        val groups = merged.groupBy { r -> r.first to alone.count { it.first == r.first && it.second < r.second } }
        for ((_, regs) in groups.toSortedMap(compareBy({ it.first }, { it.second }))) fetchMerged(regs)
    }

    private suspend fun fetchMerged(all: List<Triple<String, Int, Int>>) {
        val regs = all.filter { it !in rejected }
        for (block in blocks(regs)) {
            val (key, a, n) = block
            var remember = true
            if (block !in rejectedBlocks) {
                try {
                    put(key, a, request(key, a, n))
                    continue
                } catch (e: ModbusException) {
                    if (e.code in NO_ANSWER) throw e // the gateway got no answer: asking point by point won't help
                    remember = e.code in NOT_HERE
                    if (remember) rejectedBlocks += block
                }
            }
            // Something in the block doesn't exist on this model: the points one by one.
            val single = regs.filter { it.first == key && it.second in a until a + n }.distinct()
            if (single.size == 1 && blocks(single, gap = 0).single() == block) {
                if (remember) rejected += single
                continue
            }
            for (r in single) {
                try {
                    for ((k2, a2, n2) in blocks(listOf(r), gap = 0)) put(k2, a2, request(k2, a2, n2))
                } catch (e: ModbusException) {
                    if (e.code in NO_ANSWER) throw e
                    if (e.code in NOT_HERE) rejected += r // not on this device
                }
            }
        }
    }

    private suspend fun request(key: String, address: Int, count: Int): IntArray {
        val (table, unit) = key.split("@").let { it[0] to it[1].toInt() }
        val gap = minIntervalMs
        if (gap > 0) {
            val wait = lastRequest + gap - System.currentTimeMillis()
            if (wait > 0) delay(wait)
        }
        try {
            return tables.read(table, unit, address, count)
        } finally {
            lastRequest = System.currentTimeMillis()
        }
    }

    private fun put(key: String, address: Int, words: IntArray) {
        answered++
        val t = image.getOrPut(key) { HashMap() }
        words.forEachIndexed { i, w -> t[address + i] = w }
    }

    /** Merges (table, address, count) into requests within max_registers, max_gap and even_address. */
    internal fun blocks(regs: List<Triple<String, Int, Int>>, gap: Int = spec.connection.maxGap): List<Triple<String, Int, Int>> {
        val c = spec.connection
        val out = mutableListOf<IntArray>()
        val keys = mutableListOf<String>()
        for ((key, a0, n0) in regs.distinct().sortedWith(compareBy({ it.first }, { it.second }, { it.third }))) {
            var a = a0
            var n = n0
            if (c.evenAddress) {
                if (a % 2 != 0) { a -= 1; n += 1 }
                if (n % 2 != 0) n += 1
            }
            val last = out.lastOrNull()
            if (last != null && keys.last() == key && a - (last[0] + last[1]) <= gap && maxOf(a + n, last[0] + last[1]) - last[0] <= c.maxRegisters) {
                last[1] = maxOf(a + n, last[0] + last[1]) - last[0]
            } else {
                out += intArrayOf(a, n)
                keys += key
            }
        }
        return out.mapIndexed { i, b -> Triple(keys[i], b[0], b[1]) }
    }

    // ---------------------------------------------------------------- decoding

    private fun words(table: String, unit: Int?, address: Int, n: Int): IntArray? {
        val t = image[tableKey(table, unit)] ?: return null
        return IntArray(n) { t[address + it] ?: return null }
    }

    /** A condition's or point's raw value: Double for numbers, String for texts. */
    private fun raw(c: Spec.Condition, word: String = wordOrder ?: "big", byte: String = byteOrder): Any? {
        val w = words(c.table, c.unitId, c.address, size(c.type, c.length)) ?: return null
        val v = decode(w, c.type, c.wordOrder ?: word, c.byteOrder ?: byte, c.chars) ?: return null
        return if (v is Double && c.bits != null) field(v.toLong(), c.bits, c.signed).toDouble() else v
    }

    private fun number(c: Spec.Condition): Double? = raw(c) as? Double

    private fun holds(c: Spec.Condition, word: String = wordOrder ?: "big", byte: String = byteOrder): Boolean {
        val v = raw(c, word, byte)
        if (c.answers != null && (v != null) != c.answers) return false
        if (v == null) return c.answers == false
        if (c.equals != null && !same(v, c.equals)) return false
        if (c.inList != null && c.inList.none { same(v, it) }) return false
        if (c.not != null && same(v, c.not)) return false
        if (c.notIn != null && c.notIn.any { same(v, it) }) return false
        if (c.contains != null && c.contains.none { v.toString().contains(it) }) return false
        if (c.range != null && !(v is Double && v in c.range)) return false
        if (c.prefix != null && !v.toString().startsWith(c.prefix)) return false
        if (c.printable && !(v is String && v.isNotEmpty() && v.all { it.code in 32..126 || it.code >= 160 })) return false
        return true
    }

    /** Every group holds, a group when one of its conditions does (model `match`, identify `checks`). */
    private fun allGroups(groups: List<List<Spec.Condition>>) = groups.all { g -> g.any { holds(it) } }

    /** At least one group holds, a group when all its conditions do (`match_any`, identify `any`). */
    private fun anyGroup(groups: List<List<Spec.Condition>>) = groups.any { g -> g.all { holds(it) } }

    private fun same(v: Any, p: JsonPrimitive): Boolean = when (v) {
        is Double -> p.doubleOrNull == v
        else -> p.content == v.toString()
    }

    private fun field(v: Long, bits: IntRange, signed: Boolean): Long {
        val width = bits.last - bits.first + 1
        var f = (v shr bits.first) and ((1L shl width) - 1)
        if (signed && (f shr (width - 1)) and 1L == 1L) f -= 1L shl width
        return f
    }

    internal fun decode(words0: IntArray, type: String, wordOrder: String, byteOrder: String, chars: String): Any? {
        val words = if (byteOrder == "little") IntArray(words0.size) { ((words0[it] shr 8) or ((words0[it] and 0xFF) shl 8)) and 0xFFFF } else words0
        if (type == "bool") return words[0].toDouble()
        if (type == "str") {
            val bytes = if (chars == "both") words.flatMap { listOf((it shr 8).toByte(), it.toByte()) }
            else words.map { (if (chars == "high") it shr 8 else it and 0xFF).toByte() }
            val s = bytes.takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.ISO_8859_1)
            return s.trim()
        }
        val ws = if (wordOrder == "little") words.reversedArray() else words
        var n = 0L
        for (w in ws) n = (n shl 16) or w.toLong()
        val bits = 16 * ws.size
        return when (type) {
            "u16", "u32" -> n.toDouble()
            "u64" -> if (n < 0) n.toULong().toDouble() else n.toDouble()
            "s16" -> n.toShort().toDouble()
            "s32" -> n.toInt().toDouble()
            "s64" -> n.toDouble()
            "f32" -> java.lang.Float.intBitsToFloat(n.toInt()).toDouble()
            "f64" -> java.lang.Double.longBitsToDouble(n)
            "sm16", "sm32" -> {
                val mag = n and ((1L shl (bits - 1)) - 1)
                (if ((n shr (bits - 1)) and 1L == 1L) -mag else mag).toDouble()
            }
            "bcd16", "bcd32", "bcd64" -> {
                val digits = (0 until bits / 4).map { ((n ushr (4 * it)) and 0xF).toInt() }.reversed()
                if (digits.any { it > 9 }) null else digits.fold(0L) { acc, d -> acc * 10 + d }.toDouble()
            }
            else -> null
        }
    }

    private fun datetime(words: IntArray, layout: Map<String, Spec.DatePart>): String? {
        val parts = HashMap<String, Long>()
        for ((name, d) in layout) {
            var v = words.getOrNull(d.register)?.toLong() ?: return null
            if (d.bits != null) v = field(v, d.bits, false)
            if (d.div != null || d.mod != null) v = (v / (d.div ?: 1)) % (d.mod ?: (1L shl 32))
            if (d.bcd) v = v.toString(16).toLongOrNull() ?: return null
            parts[name] = v + d.offset
        }
        val date = listOf("year", "month", "day").filter { it in parts }.joinToString("-") { k -> parts[k]!!.toString().padStart(if (k == "year") 4 else 2, '0') }
        val time = listOf("hour", "minute", "second").filter { it in parts }.joinToString(":") { k -> parts[k]!!.toString().padStart(2, '0') }
        return listOf(date, time).filter { it.isNotEmpty() }.joinToString("T")
    }

    /** Every point and derived value that can be decoded from the registers read so far. */
    fun values(): Map<String, SpecValue> {
        val order = wordOrder ?: "big"
        val out = LinkedHashMap<String, SpecValue>()
        for (p in points()) {
            val w = words(p.table, p.unitId, p.address, size(p.type, p.length)) ?: continue
            if (p.type == "datetime") {
                datetime(w, p.datetime.orEmpty())?.let { out[p.key] = SpecValue(p, null, it) }
                continue
            }
            val v: Any = decode(w, p.type, p.wordOrder ?: order, p.byteOrder ?: byteOrder, p.chars) ?: continue
            if (v is Double && (v in p.invalid || !v.isFinite())) continue // NaN / ∞ floats: not available
            if (v is String) {
                out[p.key] = SpecValue(p, null, v)
                continue
            }
            var d = v as Double
            if (p.bits != null) d = field(d.toLong(), p.bits, p.signed).toDouble()
            val rawInt = d.toLong()
            val exponent = p.exponent
            if (exponent != null) {
                val e = number(exponent) ?: continue // no exponent, no value
                d *= 10.0.pow(if (exponent.negate) -e else e)
            }
            d = d * p.scale + p.offset
            val want = vocabulary.direction(p.key, spec.kind)
            if (want != null && p.positive == Vocabulary.OPPOSITE[want]) d = -d
            if (p.epoch != null) {
                out[p.key] = SpecValue(p, null, epoch(d, p.epoch))
                continue
            }
            val enumText = p.enum?.let { spec.enums[it]?.get(rawInt.toString()) ?: "Unknown" }
            val flags = p.flags?.let { f -> (0 until 64).filter { (rawInt shr it) and 1L == 1L }.map { b -> spec.flags[f]?.get(b.toString()) ?: "Bit $b" } }.orEmpty()
            out[p.key] = SpecValue(p, d, null, enumText, flags)
        }
        for (dv in spec.derived) {
            if (dv.models != null && (if (model != null) model!!.id !in dv.models else userChoice)) continue
            val terms = dv.terms.map { t -> out[t.key]?.number?.let { it * t.factor } }
            var sum = if (dv.first) {
                terms.firstOrNull { it != null } ?: continue // the first term present
            } else {
                // Missing terms are only allowed where marked optional (they count as 0), and one must be there.
                if (terms.all { it == null } || dv.terms.indices.any { terms[it] == null && !dv.terms[it].optional }) continue
                terms.sumOf { it ?: 0.0 }
            }
            val want = vocabulary.direction(dv.key, spec.kind)
            if (want != null && dv.positive == Vocabulary.OPPOSITE[want]) sum = -sum
            val used = if (dv.first) listOfNotNull(dv.terms.firstOrNull { out[it.key]?.number != null }) else dv.terms
            val trusted = dv.trusted && used.all { out[it.key]?.trusted != false }
            out[dv.key] = SpecValue(
                Spec.Point(dv.key, dv.label, "derived", 0, "f64", null, dv.unit, 1.0, 0.0, null, null, dv.positive, null, null, dv.models, "fast",
                    emptyList(), if (trusted) true else false, null, null, null, false, "both", null, null, if (trusted) emptyList() else listOf("derived")),
                sum, null,
            )
        }
        return out
    }

    private fun epoch(v: Double, e: Pair<String, String>): String {
        val start = LocalDate.parse(e.first.take(10)).atStartOfDay()
        val t: LocalDateTime = when (e.second) {
            "min" -> start.plusMinutes(v.toLong())
            "h" -> start.plusHours(v.toLong())
            "d" -> start.plusDays(v.toLong())
            else -> start.plusSeconds(v.toLong())
        }
        return t.toString()
    }

    /** The problems the device reports (from document and project rules alike). */
    fun problems(values: Map<String, SpecValue>): List<Pair<String, String>> = spec.problems.mapNotNull { r ->
        val v = values[r.key] ?: return@mapNotNull null
        val n = v.number
        val t = v.text
        val hit = (r.equals == null || matches(n, t, r.equals)) &&
            (r.not == null || !matches(n, t, r.not)) &&
            (r.inList == null || r.inList.any { matches(n, t, it) }) &&
            (r.notIn == null || r.notIn.none { matches(n, t, it) }) &&
            (r.range == null || (n != null && n in r.range)) &&
            (r.prefix == null || (t ?: n?.toString().orEmpty()).startsWith(r.prefix)) &&
            (r.bit == null || (n != null && (n.toLong() shr r.bit) and 1L == 1L))
        if (!hit) null else r.kind to format(r.text, n, t, v.enumText)
    }

    private fun matches(n: Double?, t: String?, p: JsonPrimitive) =
        if (n != null) p.doubleOrNull == n else t != null && p.content == t

    /** "{value}", "{value:X}" and "{text}" (the value's name from the point's enum) in a problem text. */
    private fun format(text: String, n: Double?, t: String?, enumText: String? = null): String {
        val value = n?.toLong()
        return text.replace("{text}", enumText ?: t ?: value?.toString().orEmpty())
            .replace("{value:X}", value?.toString(16)?.uppercase() ?: t.orEmpty())
            .replace("{value:x}", value?.toString(16) ?: t.orEmpty())
            .replace("{value}", value?.toString() ?: t.orEmpty())
    }
}

/** Modbus exception codes that mean "not on this device" (illegal function, address, value), not a passing failure. */
private val NOT_HERE = 1..3

/** Gateway exceptions 10 / 11 (path unavailable, target didn't respond): the device behind it isn't answering. */
private val NO_ANSWER = 10..11

/** Raised when the device answers but none of its registers fit its spec. */
class SpecMismatchException(message: String) : IOException(message)
