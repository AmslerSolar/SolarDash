// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.model

import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FaultLogTest {
    private class MemoryStore : EventStore {
        val rows = mutableListOf<LoggedEvent>()
        override suspend fun openEvents(plantId: String) = rows.filter { it.plantId == plantId && it.ended == null }
        override suspend fun openEvent(e: LoggedEvent) { rows += e.copy(id = rows.size + 1L) }
        override suspend fun closeEvent(id: Long, ended: Long) {
            val i = rows.indexOfFirst { it.id == id }
            rows[i] = rows[i].copy(ended = ended)
        }
    }

    private val hybrid = DeviceConfig("hyb", "Axpert", DeviceType.VOLTRONIC, host = "192.168.1.30")
    private val plant = Plant("p", "Home", listOf(hybrid))
    private val net = NetworkState(localNetwork = true, internet = false)

    private fun at(t: Long, vararg problems: DeviceProblem) = PlantAggregator.aggregate(
        listOf(DeviceStatus(hybrid, DeviceReading(problems = problems.toList()), null)), Instant.ofEpochSecond(t),
    )

    private val overload = DeviceProblem(DeviceProblem.Kind.FAULT, "Over load (a16)")

    @Test
    fun aProblemIsLoggedOnceItLastsAndClosedOnceItsGone() = runBlocking {
        val store = MemoryStore()
        val log = FaultLog(store, settleS = 30)
        log.update(plant, at(1000, overload), net)
        log.update(plant, at(1020, overload), net)
        assertEquals(0, store.rows.size) // not yet 30 s
        log.update(plant, at(1040, overload), net)
        assertEquals(1, store.rows.size)
        assertEquals(1000L, store.rows[0].started) // from when it was first seen
        assertEquals("DEVICE_FAULT", store.rows[0].kind)
        assertEquals("Over load (a16)", store.rows[0].detail)
        assertEquals("Axpert", store.rows[0].deviceName)

        log.update(plant, at(1100), net) // gone
        assertNull(store.rows[0].ended)
        log.update(plant, at(1140), net)
        assertEquals(1100L, store.rows[0].ended) // from when it was first missing
    }

    @Test
    fun aShortHiccupIsNotLogged() = runBlocking {
        val store = MemoryStore()
        val log = FaultLog(store)
        log.update(plant, at(1000, overload), net)
        log.update(plant, at(1005), net)
        log.update(plant, at(1100), net)
        assertEquals(0, store.rows.size)
    }

    @Test
    fun singleReadsLogAndCloseAtOnce() = runBlocking {
        val store = MemoryStore()
        val log = FaultLog(store)
        log.update(plant, at(1000, overload), net, continuous = false)
        assertEquals(1, store.rows.size)
        log.update(plant, at(1900), net, continuous = false)
        assertEquals(1900L, store.rows[0].ended)
    }

    @Test
    fun informationThatComesAndGoesStaysOut() = runBlocking {
        val store = MemoryStore()
        val log = FaultLog(store)
        log.update(plant, at(1000), net, continuous = false) // "no internet" only
        assertEquals(0, store.rows.size)
    }

    @Test
    fun differentFaultCodesAreSeparateEntries() = runBlocking {
        val store = MemoryStore()
        val log = FaultLog(store)
        log.update(plant, at(1000, overload), net, continuous = false)
        log.update(plant, at(1900, DeviceProblem(DeviceProblem.Kind.FAULT, "Bus over (a2)")), net, continuous = false)
        assertEquals(listOf("Over load (a16)", "Bus over (a2)"), store.rows.map { it.detail })
        assertEquals(1900L, store.rows[0].ended)
        assertNull(store.rows[1].ended)
    }
}
