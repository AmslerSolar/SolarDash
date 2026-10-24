// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.spec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A ModbusSpec device file (format version 0, see ModbusSpec's FORMAT.md), as the app bundles it
 * (assets/modbusspec/<id>.json, copied by tools/modbusspec_sync.py at a pinned ModbusSpec commit).
 *
 * Only reading is modelled; `writable`, `conflicts` and the source details are kept out. A point taken only from
 * open-source projects (it names `sources` and isn't `verified`) is [Point.trusted] = false: shown as a detail row
 * but never used for the energy flow or the plant status.
 */
class Spec(
    val id: String,
    val manufacturer: String,
    val name: String,
    val kind: String,
    val connection: Connection,
    val encoding: Encoding,
    val models: List<ModelDef>,
    val identify: Identify?,
    val points: List<Point>,
    val derived: List<Derived>,
    val enums: Map<String, Map<String, String>>,
    val flags: Map<String, Map<String, String>>,
    val problems: List<ProblemRule>,
    /** "auto" (detect the model) or "user" (the models can't be told apart: the user picks one). */
    val modelChoice: String = "auto",
) {
    /** [blocks]: ranges (table, unit id or null, address, count) the device only answers as one request; [sleeps]: no answer = asleep. */
    data class Connection(
        val defaultPort: Int, val defaultUnit: Int, val maxRegisters: Int, val evenAddress: Boolean, val maxGap: Int, val minIntervalMs: Long,
        val blocks: List<FixedBlock> = emptyList(), val sleeps: Boolean = false,
    )
    data class FixedBlock(val table: String, val unitId: Int?, val address: Int, val count: Int)
    data class Encoding(val byteOrder: String, val wordOrder: String, val detect: Condition?, val plausible: ClosedFloatingPointRange<Double>?)
    /**
     * [match]: every group must hold, a group when one of its conditions does ("a plausible value or not
     * connected"); [matchAny]: at least one group, a group when all its conditions do.
     */
    data class ModelDef(val id: String, val name: String, val match: List<List<Condition>>, val matchAny: List<List<Condition>>)
    /** [checks] like [ModelDef.match], [any] like [ModelDef.matchAny]; [serials]: the serial register, per model where they differ. */
    data class Identify(val serials: List<Pair<Condition, List<String>?>>, val checks: List<List<Condition>>, val any: List<List<Condition>>)

    /** A register reference with an optional test (model match, identify check, exponent register). */
    data class Condition(
        val table: String, val address: Int, val type: String, val length: Int?, val unitId: Int?,
        val equals: JsonPrimitive?, val inList: List<JsonPrimitive>?, val range: ClosedFloatingPointRange<Double>?,
        val prefix: String?, val printable: Boolean, val bits: IntRange?, val signed: Boolean, val negate: Boolean,
        val not: JsonPrimitive? = null, val notIn: List<JsonPrimitive>? = null,
        /** true / false: the register must (not) answer a read at all; read in a request of its own. */
        val answers: Boolean? = null,
        /** Text registers: "both" bytes, or only the "high" / "low" byte of each register is a character. */
        val chars: String = "both",
        /** The text contains one of these (e.g. model tags in a serial number). */
        val contains: List<String>? = null,
        /** Detect only: the device states its word order in this register, value → "big" / "little". */
        val wordOrders: Map<String, String>? = null,
        /** This register's own word / byte order, when it differs from the device's. */
        val wordOrder: String? = null,
        val byteOrder: String? = null,
    )

    data class DatePart(val register: Int, val bits: IntRange?, val offset: Int, val bcd: Boolean, val div: Long?, val mod: Long?)

    data class Point(
        val key: String, val label: String, val table: String, val address: Int, val type: String, val length: Int?,
        val unit: String?, val scale: Double, val offset: Double, val exponent: Condition?, val unitId: Int?,
        val positive: String?, val enum: String?, val flags: String?, val models: List<String>?, val poll: String,
        val invalid: List<Double>, val verified: Boolean?, val wordOrder: String?, val byteOrder: String?,
        val bits: IntRange?, val signed: Boolean, val chars: String, val datetime: Map<String, DatePart>?,
        val epoch: Pair<String, String>?, val sources: List<String>,
        /** May not be fitted: read alone, no answer (even a timeout) = no value. */
        val optional: Boolean = false,
    ) {
        /** From the manufacturer documents (no `sources`), or checked on a real device. */
        val trusted: Boolean get() = sources.isEmpty() || verified == true
    }

    data class Term(val key: String, val factor: Double, val optional: Boolean = false)
    /** A weighted sum of [terms], or with [first] the first term that is present. */
    data class Derived(val key: String, val label: String, val unit: String?, val positive: String?, val models: List<String>?, val terms: List<Term>, val trusted: Boolean, val first: Boolean = false)
    data class ProblemRule(val key: String, val equals: JsonPrimitive?, val not: JsonPrimitive?, val inList: List<JsonPrimitive>?, val range: ClosedFloatingPointRange<Double>?, val prefix: String?, val bit: Int?, val kind: String, val text: String, val notIn: List<JsonPrimitive>? = null)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): Spec = from(json.parseToJsonElement(text).jsonObject)

        private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString || it !is JsonNull }?.content
        private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull
        private fun JsonObject.dbl(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull
        private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull
        private fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
        private fun JsonObject.arr(k: String): JsonArray? = this[k] as? JsonArray
        private fun JsonObject.strings(k: String): List<String>? = arr(k)?.map { it.jsonPrimitive.content }
        private fun JsonObject.range(k: String): ClosedFloatingPointRange<Double>? =
            arr(k)?.takeIf { it.size == 2 }?.let { it[0].jsonPrimitive.doubleOrNull!!..it[1].jsonPrimitive.doubleOrNull!! }
        private fun JsonObject.bits(): IntRange? = arr("bits")?.takeIf { it.size == 2 }?.let { it[0].jsonPrimitive.intOrNull!!..it[1].jsonPrimitive.intOrNull!! }

        private fun condition(o: JsonObject) = Condition(
            table = o.str("table") ?: "holding", address = o.int("address") ?: 0, type = o.str("type") ?: "u16",
            length = o.int("length"), unitId = o.int("unit_id"),
            equals = o["equals"] as? JsonPrimitive, inList = o.arr("in")?.map { it.jsonPrimitive },
            range = o.range("range") ?: o.range("plausible"), prefix = o.str("prefix"), printable = o.bool("printable") == true,
            bits = o.bits(), signed = o.bool("signed") == true, negate = o.bool("negate") == true,
            not = o["not"] as? JsonPrimitive, notIn = o.arr("not_in")?.map { it.jsonPrimitive }, answers = o.bool("answers"),
            chars = o.str("chars") ?: "both",
            contains = o.strings("contains"), wordOrders = o.obj("word_orders")?.mapValues { (_, v) -> v.jsonPrimitive.content },
            wordOrder = o.str("word_order"), byteOrder = o.str("byte_order"),
        )

        /** A condition, or a list of them whose entries may themselves be lists (groups), as groups. */
        private fun groups(e: JsonElement?): List<List<Condition>> = when (e) {
            is JsonObject -> listOf(listOf(condition(e)))
            is JsonArray -> e.map { item -> if (item is JsonArray) item.map { condition(it.jsonObject) } else listOf(condition(item.jsonObject)) }
            else -> emptyList()
        }

        private fun table(o: JsonObject?): Map<String, Map<String, String>> =
            o?.mapValues { (_, v) -> v.jsonObject.mapValues { (_, t) -> t.jsonPrimitive.content } }.orEmpty()

        private fun from(o: JsonObject): Spec {
            val c = o.obj("connection") ?: JsonObject(emptyMap())
            val limits = c.obj("limits") ?: JsonObject(emptyMap())
            val enc = o.obj("encoding") ?: JsonObject(emptyMap())
            val detect = enc.obj("detect")
            return Spec(
                id = o.str("id")!!, manufacturer = o.str("manufacturer").orEmpty(), name = o.str("name").orEmpty(), kind = o.str("kind") ?: "other",
                connection = Connection(
                    defaultPort = c.int("default_port") ?: 502, defaultUnit = c.int("default_unit") ?: 1,
                    maxRegisters = limits.int("max_registers") ?: 125, evenAddress = limits.bool("even_address") == true,
                    maxGap = limits.int("max_gap") ?: 0, minIntervalMs = (limits.int("min_interval_ms") ?: 0).toLong(),
                    blocks = c.arr("blocks")?.map { it.jsonObject }?.map { b ->
                        FixedBlock(b.str("table") ?: "holding", b.int("unit_id"), b.int("address")!!, b.int("count")!!)
                    }.orEmpty(),
                    sleeps = c.bool("sleeps") == true,
                ),
                encoding = Encoding(
                    byteOrder = enc.str("byte_order") ?: "big", wordOrder = enc.str("word_order") ?: "big",
                    detect = detect?.let(::condition), plausible = detect?.range("plausible") ?: detect?.range("range"),
                ),
                models = o.arr("models")?.map { it.jsonObject }?.map { m ->
                    ModelDef(m.str("id")!!, m.str("name").orEmpty(), groups(m["match"]), groups(m["match_any"]))
                }.orEmpty(),
                identify = o.obj("identify")?.let { i ->
                    val serials = when (val s = i["serial"]) {
                        is JsonObject -> listOf(condition(s) to null)
                        is JsonArray -> s.map { it.jsonObject }.map { condition(it) to it.strings("models") }
                        else -> emptyList()
                    }
                    Identify(serials, groups(i["checks"]), groups(i["any"]))
                },
                modelChoice = o.str("model_choice") ?: "auto",
                points = o.arr("points")?.map { point(it.jsonObject) }.orEmpty(),
                derived = o.arr("derived")?.map { it.jsonObject }?.map { d ->
                    Derived(
                        key = d.str("key")!!, label = d.str("label").orEmpty(), unit = d.str("unit"), positive = d.str("positive"),
                        models = d.strings("models"),
                        terms = (d.arr("sum") ?: d.arr("first"))?.map { t ->
                            t.jsonObject.let { Term(it.str("key")!!, it.dbl("factor") ?: 1.0, it.bool("optional") == true) }
                        }.orEmpty(),
                        trusted = d.arr("sources").isNullOrEmpty() || d.bool("verified") == true,
                        first = d.arr("first") != null,
                    )
                }.orEmpty(),
                enums = table(o.obj("enums")),
                flags = table(o.obj("flags")),
                problems = o.arr("problems")?.map { it.jsonObject }?.map { p ->
                    val w = p.obj("when")!!
                    ProblemRule(
                        key = w.str("key")!!, equals = w["equals"] as? JsonPrimitive, not = w["not"] as? JsonPrimitive,
                        inList = w.arr("in")?.map { it.jsonPrimitive }, range = w.range("range"), prefix = w.str("prefix"),
                        bit = w.int("bit"), kind = p.str("kind")!!, text = p.str("text").orEmpty(), notIn = w.arr("not_in")?.map { it.jsonPrimitive },
                    )
                }.orEmpty(),
            )
        }

        private fun point(p: JsonObject): Point = Point(
            key = p.str("key")!!, label = p.str("label").orEmpty(), table = p.str("table") ?: "holding", address = p.int("address")!!,
            type = p.str("type")!!, length = p.int("length"), unit = p.str("unit"), scale = p.dbl("scale") ?: 1.0,
            offset = p.dbl("offset") ?: 0.0, exponent = p.obj("scale_exponent_from")?.let(::condition), unitId = p.int("unit_id"),
            positive = p.str("positive"), enum = p.str("enum"), flags = p.str("flags"), models = p.strings("models"),
            poll = p.str("poll") ?: "fast", invalid = p.arr("invalid")?.mapNotNull { it.jsonPrimitive.doubleOrNull }.orEmpty(),
            verified = p.bool("verified"), wordOrder = p.str("word_order"), byteOrder = p.str("byte_order"),
            bits = p.bits(), signed = p.bool("signed") == true, chars = p.str("chars") ?: "both",
            datetime = p.obj("datetime")?.mapValues { (_, v) ->
                val d = v.jsonObject
                DatePart(d.int("register") ?: 0, d.bits(), d.int("offset") ?: 0, d.bool("bcd") == true, d.dbl("div")?.toLong(), d.dbl("mod")?.toLong())
            },
            epoch = p.obj("epoch")?.let { (it.str("start") ?: "1970-01-01") to (it.str("unit") ?: "s") },
            sources = p.strings("sources").orEmpty(),
            optional = p.bool("optional") == true,
        )
    }
}

/** The vocabulary's sign directions (assets/modbusspec/vocabulary.json), for normalising directional values. */
class Vocabulary(private val directions: Map<String, String>) {
    /** The key's direction for a device of [kind] ("main" resolved by the kind), or null for keys without one. */
    fun direction(key: String, kind: String): String? = when (val d = directions[key]) {
        "main" -> MAIN_DIRECTION[kind] ?: "consumption"
        else -> d
    }

    companion object {
        val MAIN_DIRECTION = mapOf("meter" to "export", "inverter" to "export", "battery" to "discharge")
        val OPPOSITE = mapOf(
            "export" to "import", "import" to "export", "discharge" to "charge", "charge" to "discharge",
            "consumption" to "generation", "generation" to "consumption",
        )

        fun parse(text: String): Vocabulary {
            val keys = Json.parseToJsonElement(text).jsonObject["keys"]?.jsonObject.orEmpty()
            return Vocabulary(keys.mapNotNull { (k, v) -> (v.jsonObject["positive"] as? JsonPrimitive)?.content?.let { k to it } }.toMap())
        }
    }
}

internal fun JsonElement?.isNumber(): Boolean = this is JsonPrimitive && !isString && doubleOrNull != null
