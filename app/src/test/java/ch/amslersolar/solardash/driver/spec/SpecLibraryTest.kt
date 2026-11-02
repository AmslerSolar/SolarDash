// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.driver.spec

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecLibraryTest {
    private val dir = File("src/main/assets/modbusspec")

    @Test
    fun everyListedSpecIsBundledAndParses() {
        val library = SpecLibrary { File(dir, it).readText() }
        // Every bundled file: the named ones and those of the listed kinds (all in the index).
        val listed = library.index.map { it.id }
        assertTrue("energy devices are bundled", listed.size > 350)
        for (id in listed) {
            val spec = library.spec(id)
            assertEquals(id, spec.id)
            assertTrue("$id has points", spec.points.isNotEmpty())
        }
        assertTrue(File(dir, "LOCK.json").readText().contains("modbusspec_commit"))
        assertEquals(listed.size, dir.listFiles()!!.count { it.name.endsWith(".json") && it.name !in setOf("index.json", "vocabulary.json", "LOCK.json") })
    }

    @Test
    fun everyIndexEntryHasAGenericType() {
        val library = SpecLibrary { File(dir, it).readText() }
        for (e in library.index) assertTrue(e.id, SpecDevices.genericType(e.kind) in SpecDevices.GENERIC)
    }

    @Test
    fun everyMappedSpecIsListed() {
        val listed = SpecLibrary { File(dir, it).readText() }.index.map { it.id }.toSet()
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
