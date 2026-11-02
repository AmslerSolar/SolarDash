// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.model

import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class FaultLogTest {
    private class MemoryStore : EventStore {
        val rows = mutableListOf<LoggedEvent>()
        override suspend fun openEvents(plantId: String) = rows.filter { it.plantId == plantId && it.ended == null }
        override suspend fun openEvent(e: LoggedEvent) { rows += e.copy(id = rows.size + 1L) }
        override suspend fun closeEvent(id: Long, ended: Long, endDetails: String?) {
            val i = rows.indexOfFirst { it.id == id }
            rows[i] = rows[i].copy(ended = ended, endDetails = endDetails)
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
    fun detailsTellTheInstallerWhatHappened() = runBlocking {
        val store = MemoryStore()
        val log = FaultLog(store, appVersion = "0.4.0 (7)")
        val reading = DeviceReading(
            status = "Fault mode (F)", problems = listOf(overload),
            battery = BatteryState(-800, 41, 51.2, "Charging"),
            details = listOf(DetailSection("Warnings (QPIWS)", listOf("Over load" to "yes"))),
        )
        log.update(plant, PlantAggregator.aggregate(listOf(DeviceStatus(hybrid, reading, null)), Instant.ofEpochSecond(1000)), net, continuous = false)
        val d = Json.parseToJsonElement(store.rows.single().details!!).jsonObject
        assertEquals("Axpert", d["device"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("192.168.1.30", d["device"]!!.jsonObject["host"]!!.jsonPrimitive.content)
        assertEquals("Fault mode (F)", d["reading"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals("Over load (a16)", d["reading"]!!.jsonObject["problems"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("Warnings (QPIWS)", d["reading"]!!.jsonObject["details"]!!.jsonArray[0].jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals("0.4.0 (7)", d["app"]!!.jsonPrimitive.content)
        assertTrue("no password in the details", "password" !in store.rows.single().details!!)

        // A failed read records the error kind and message; the end records the device answering again.
        val failed = DeviceStatus(hybrid, null, "connect timed out", ConnectionError.TIMEOUT)
        log.update(plant, PlantAggregator.aggregate(listOf(failed), Instant.ofEpochSecond(2000)), net, continuous = false)
        val timeout = store.rows.first { it.kind == "NOT_ANSWERING" }
        val e = Json.parseToJsonElement(timeout.details!!).jsonObject["error"]!!.jsonObject
        assertEquals("TIMEOUT", e["kind"]!!.jsonPrimitive.content)
        assertEquals("connect timed out", e["message"]!!.jsonPrimitive.content)
        log.update(plant, at(3000), net, continuous = false)
        val end = Json.parseToJsonElement(store.rows.first { it.kind == "NOT_ANSWERING" }.endDetails!!).jsonObject
        assertEquals(true, end["plant"]!!.jsonObject["devices"]!!.jsonArray[0].jsonObject["answered"]!!.jsonPrimitive.content.toBoolean())
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

    @Test
    fun hiddenProblemsAreStillLogged() = runBlocking {
        val store = MemoryStore()
        val log = FaultLog(store)
        val hiding = plant.copy(devices = listOf(hybrid.copy(hiddenProblems = listOf(overload.text))))
        log.update(hiding, at(1000, overload), net, continuous = false)
        assertEquals("Over load (a16)", store.rows.single().detail)
    }
}
