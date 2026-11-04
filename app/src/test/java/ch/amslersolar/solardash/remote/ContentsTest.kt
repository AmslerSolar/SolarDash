// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.remote

import ch.amslersolar.solardash.model.DetailSection
import ch.amslersolar.solardash.model.DeviceReading
import ch.amslersolar.solardash.model.LoggedEvent
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What leaves the plant (the stores are encrypted, but every observer reads it): no addresses or serial numbers. */
class ContentsTest {
    private val details = """
        {"time":1,"app":"1.0","device":{"name":"Inverter","type":"GOODWE_ET","host":"192.168.0.10","port":502,"unit_id":247,
        "serial":"GW-SECRET","meter_role":"GRID"},"error":{"kind":"TIMEOUT","message":"connect to /192.168.0.10:502 timed out"},
        "reading":{"status":"Fault","details":[{"title":"Device","rows":[["Model","GW10K-ET"],["Serial number","GW-SECRET"],
        ["MAC","aa:bb:cc:dd:ee:ff"]]}]},"plant":{"pv_w":0,"devices":[{"name":"Meter","answered":false}]},
        "network":{"local_network":true,"internet":false,"subnets":["192.168.0.0/24"]}}
    """.trimIndent()

    @Test
    fun theFaultLogSituationLeavesWithoutAddressesOrSerials() {
        val out = Contents.details(details, listOf("192.168.0.39"))!!
        for (secret in listOf("192.168.0.10", "GW-SECRET", "aa:bb:cc", "192.168.0.0/24", "\"port\"", "unit_id")) {
            assertFalse(secret, secret in out)
        }
        val o = Contents.json.parseToJsonElement(out).jsonObject
        assertEquals("\"Inverter\"", o["device"]!!.jsonObject["name"].toString())
        assertTrue(out, "GW10K-ET" in out)
        assertTrue(out, "connect to /…:502 timed out" in out)
        assertTrue(out, "\"internet\":false" in out)
    }

    @Test
    fun otherDevicesAddressesAreCutOutOfTexts() {
        val e = LoggedEvent(
            plantId = "home", key = "k", kind = "OFFLINE", severity = "ALARM", deviceId = "m", deviceName = "Meter",
            detail = "No answer from 192.168.0.39", percent = null, started = 1, details = details,
        )
        val sent = Contents.event(e, listOf("192.168.0.39", ""))
        assertEquals("No answer from …", sent.detail)
        assertFalse(sent.details!!, "192.168" in sent.details!!)
    }

    @Test
    fun liveReadingsDropIdentifyingRows() {
        val r = DeviceReading(details = listOf(DetailSection("Device", listOf("Model" to "X", "Serial number" to "S1", "S/N" to "S2", "SN" to "S3"))))
        assertEquals(listOf(listOf("Model", "X")), Contents.reading(r).details.single().rows)
    }

    @Test
    fun brokenDetailsAreNotSent() {
        assertEquals(null, Contents.details("not json"))
    }
}
