// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.i18n

import android.content.Context
import ch.amslersolar.solardash.model.Channels
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Translates driver texts (detail section titles, row labels, states) at display time, so drivers and the stored
 * history channel keys ("d:<section>:<label>") stay English.
 *
 * Dictionary: one line per English text, `English<TAB>translation`. `{n}` stands for a number and `{x}` for any
 * text (a Kotlin template part in the driver, e.g. "MPPT {x} power"); the translation uses the same placeholders,
 * filled in the same order. Lookup: exact text, then the text with its numbers as `{n}`, then the `{x}` templates,
 * then "<text> (<suffix>)" with only the text translated. Anything else stays English.
 */
class DetailTranslator(entries: Map<String, String>) {
    private val exact = HashMap<String, String>()
    private val templates = ArrayList<Pair<Regex, String>>()
    private val cache = ConcurrentHashMap<String, String>()

    init {
        for ((en, tr) in entries) {
            if (tr.isBlank()) continue
            if ("{x}" in en) templates += templateRegex(en) to tr else exact[en] = tr
        }
        // Most specific first: more literal text wins over a looser template ("L{x} power" before "{x} power").
        templates.sortByDescending { (re, _) -> re.pattern.replace("(.+?)", "").replace("(\\d+)", "").length }
    }

    fun translate(text: String): String {
        if (text.isBlank() || exact.isEmpty() && templates.isEmpty()) return text
        cache[text]?.let { return it }
        val out = lookup(text, depth = 0) ?: text
        if (cache.size < 10_000) cache[text] = out
        return out
    }

    private fun lookup(text: String, depth: Int): String? {
        exact[text]?.let { return it }
        if (text.first().isDigit() || text.first() == '-' && text.length > 1 && text[1].isDigit()) return null // a value
        val numbers = NUMBER.findAll(text).map { it.value }.toList()
        if (numbers.isNotEmpty()) exact[NUMBER.replace(text, "{n}")]?.let { return fill(it, numbers, emptyList()) }
        for ((re, tr) in templates) {
            val m = re.matchEntire(text) ?: continue
            val groups = m.groupValues.drop(1)
            val kinds = re.pattern.windowed(4).filter { it == "(.+?" || it == "(\\d+" } // capture order
            val xs = groups.filterIndexed { i, _ -> kinds.getOrNull(i) == "(.+?" }
                .map { if (depth < 1) lookup(it, depth + 1) ?: it else it }
            val ns = groups.filterIndexed { i, _ -> kinds.getOrNull(i) == "(\\d+" }
            return fill(tr, ns, xs)
        }
        SUFFIX.matchEntire(text)?.let { m -> lookup(m.groupValues[1], depth)?.let { return "$it (${m.groupValues[2]})" } }
        // Several states or flags in one value ("Fan fault, Overheating"): each on its own.
        if (depth == 0 && ", " in text) {
            val parts = text.split(", ")
            val translated = parts.map { lookup(it, depth + 1) }
            if (translated.any { it != null }) return parts.indices.joinToString(", ") { translated[it] ?: parts[it] }
        }
        return null
    }

    companion object {
        private val NUMBER = Regex("""\d+""")
        private val SUFFIX = Regex("""^(.+?) \(([^()]*)\)$""")

        fun parse(tsv: String): Map<String, String> = tsv.lineSequence()
            .mapNotNull { line -> line.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1].trim() } }
            .toMap()

        private fun templateRegex(en: String): Regex {
            val parts = Regex("""\{x}|\{n}""").split(en).map(Regex::escape)
            val holes = Regex("""\{x}|\{n}""").findAll(en).map { if (it.value == "{x}") "(.+?)" else "(\\d+)" }.toList()
            return Regex(buildString { parts.forEachIndexed { i, p -> append(p); holes.getOrNull(i)?.let(::append) } })
        }

        /** Fills `{n}` and `{x}` in [template] in order; a translation with fewer placeholders drops the rest. */
        private fun fill(template: String, ns: List<String>, xs: List<String>): String {
            val n = ns.iterator(); val x = xs.iterator()
            return Regex("""\{n}|\{x}""").replace(template) { m ->
                if (m.value == "{n}") (if (n.hasNext()) n.next() else "") else (if (x.hasNext()) x.next() else "")
            }
        }
    }
}

/** The app-wide translator for the phone's language; dictionaries are in assets/i18n/details_<lang>.tsv. */
object DetailTexts {
    private val identity = DetailTranslator(emptyMap())
    private val loaded = ConcurrentHashMap<String, DetailTranslator>()
    @Volatile private var context: Context? = null

    fun init(context: Context) { this.context = context.applicationContext }

    fun translator(language: String = Locale.getDefault().language): DetailTranslator {
        val ctx = context ?: return identity
        if (language == "en") return identity
        return loaded.getOrPut(language) {
            runCatching { ctx.assets.open("i18n/details_$language.tsv").bufferedReader().use { DetailTranslator(DetailTranslator.parse(it.readText())) } }
                .getOrDefault(identity)
        }
    }

    fun tr(text: String): String = translator().translate(text)
}

/** A history channel's name for display: the stored (English) name, translated. */
fun channelName(key: String, label: String): String = DetailTexts.tr(Channels.name(key, label))
