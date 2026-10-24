// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.spec

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecLibraryTest {
    private val dir = File("src/main/assets/modbusspec")

    @Test
    fun everyListedSpecIsBundledAndParses() {
        val listed = File("modbusspec.txt").readLines().map { it.substringBefore("#").trim().substringBefore(" ") }.filter { it.isNotEmpty() }
        val library = SpecLibrary { File(dir, it).readText() }
        for (id in listed) {
            val spec = library.spec(id)
            assertEquals(id, spec.id)
            assertTrue("$id has points", spec.points.isNotEmpty())
        }
        assertTrue(File(dir, "LOCK.json").readText().contains("modbusspec_commit"))
    }

    @Test
    fun everyMappedSpecIsListed() {
        val listed = File("modbusspec.txt").readLines().map { it.substringBefore("#").trim().substringBefore(" ") }.toSet()
        for ((type, ids) in SpecDevices.SPEC_IDS) for (id in ids) assertTrue("$type → $id is in modbusspec.txt", id in listed)
    }

    @Test
    fun blocksKeepToTheLimits() {
        val library = SpecLibrary { File(dir, it).readText() }
        val r = SpecReader(library.spec("eastron-sdm"), library.vocabulary, SpecTables({ _, _, _ -> error("unused") }, { _, _, _ -> error("unused") }), 1)
        val b = r.blocks(listOf(Triple("input@1", 0, 2), Triple("input@1", 2, 2), Triple("input@1", 0x48, 2), Triple("input@1", 3, 1)))
        assertTrue(b.all { it.second % 2 == 0 && it.third % 2 == 0 && it.third <= 80 })
    }
}
