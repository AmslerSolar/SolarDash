// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.spec

/**
 * What the hand-written drivers (since deleted) read on the golden tests' register images, recorded while they still
 * existed: `src/test/resources/golden/<TestClass>/old-drivers.txt`. Each entry starts with a line `=== <key>`
 * (`<testMethod>/<case label or index>`) and holds every following line up to the next entry.
 */
internal class OldDriverRecords(private val testClass: String) {
    private val entries: Map<String, String> by lazy {
        val text = javaClass.getResource("/golden/$testClass/old-drivers.txt")?.readText()
            ?: error("no golden/$testClass/old-drivers.txt on the test classpath")
        val map = LinkedHashMap<String, String>()
        var key: String? = null
        val lines = mutableListOf<String>()
        fun flush() {
            key?.let { k -> check(map.put(k, lines.joinToString("\n")) == null) { "duplicate entry $k" } }
            lines.clear()
        }
        for (line in text.removeSuffix("\n").split("\n")) {
            if (line.startsWith("=== ")) {
                flush()
                key = line.removePrefix("=== ")
            } else {
                lines += line
            }
        }
        flush()
        map
    }

    /** The recorded value of [key]; fails if there is none (a case without a recording isn't a comparison). */
    operator fun get(key: String): String = entries[key] ?: throw AssertionError("no recorded old-driver result for $key")
}
