// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.spec

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The registers a sweep tries first: every range the bundled device files read, per table. */
class KnownRegistersTest {
    private val library = SpecLibrary { File("src/main/assets/modbusspec/$it").readText() }

    @Test
    fun coversTheBundledDeviceFiles() {
        val known = library.knownRegisters()
        val holding = known["holding"].orEmpty()
        val input = known["input"].orEmpty()
        assertTrue("${holding.size} holding ranges", holding.size > 1000)
        assertTrue("${input.size} input ranges", input.size > 1000)
        // a u32 point is two registers: Eastron's frequency (input 0x46) and a 16-register text both show up whole
        assertTrue(holding.any { 0x0046 in it } || input.any { it == 0x0046..0x0047 })
        assertTrue(holding.any { it.count() == 2 })
    }
}
