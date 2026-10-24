// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.spec

import org.junit.Assert.assertEquals

/**
 * What the replaced hand-written inverter drivers read, recorded while they still existed, for one test method:
 * `src/test/resources/golden/<testClass>/<method>.txt`, entries `[<method>/<case>]` followed by the recorded lines.
 * [take] hands them out in order; [finish] checks that every recorded comparison was made.
 */
internal class InvertersGoldenRecords(private val testClass: String, private val method: String) {
    private val path = "golden/$testClass/$method.txt"
    private val seen = HashMap<String, Int>()
    private val taken = HashSet<String>()

    private val entries: Map<String, String> by lazy {
        val text = javaClass.classLoader.getResource(path)?.readText() ?: throw AssertionError("no recorded data $path")
        val map = LinkedHashMap<String, String>()
        var key: String? = null
        val lines = mutableListOf<String>()
        fun flush() { key?.let { map[it] = lines.joinToString("\n").trimEnd('\n') }; lines.clear() }
        for (line in text.lines()) {
            if (line.startsWith("[") && line.endsWith("]")) { flush(); key = line.substring(1, line.length - 1) } else lines += line
        }
        flush()
        map
    }

    /** The key for the next entry of [case] (a repeated case gets " #2", " #3", …). */
    private fun key(case: String): String {
        val n = (seen[case] ?: 0) + 1
        seen[case] = n
        return "$method/$case" + if (n > 1) " #$n" else ""
    }

    fun take(case: String): String {
        val k = key(case)
        taken += k
        return entries[k] ?: throw AssertionError("no recorded entry [$k] in $path")
    }

    /** Recorded lines `name=value` of an entry, the rest (the essentials) under "". */
    fun fields(value: String): Map<String, String> {
        val (named, rest) = value.lines().partition { Regex("^[a-zA-Z]+=").containsMatchIn(it) }
        return named.associate { it.substringBefore('=') to it.substringAfter('=') } + ("" to rest.joinToString("\n"))
    }

    fun finish() {
        if (javaClass.classLoader.getResource(path) != null || taken.isNotEmpty()) {
            assertEquals("recorded entries not compared in $path", emptySet<String>(), entries.keys - taken)
        }
    }
}
