// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.driver

import ch.amslersolar.solardash.model.DeviceConfig
import ch.amslersolar.solardash.model.DeviceType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlantReaderTest {
    @Test
    fun learnsMissingSerialsOnceOverTheRunningConnection() = runBlocking {
        val known = DeviceConfig(id = "a", name = "Inverter", type = DeviceType.DEMO_INVERTER, serial = "KEEP")
        val unknown = DeviceConfig(id = "b", name = "Battery", type = DeviceType.DEMO_BATTERY)
        PlantReader(listOf(known, unknown)).use { reader ->
            reader.read()
            assertEquals(mapOf("b" to "DEMO-BAT-0001"), reader.learnSerials())
            assertTrue(reader.learnSerials().isEmpty()) // asked only once
        }
    }
}
