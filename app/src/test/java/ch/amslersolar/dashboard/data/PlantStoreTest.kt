// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.data

import ch.amslersolar.dashboard.model.DeviceConfig
import ch.amslersolar.dashboard.model.DeviceType
import ch.amslersolar.dashboard.model.MeterRole
import ch.amslersolar.dashboard.model.Plant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlantStoreTest {
    /** As saved by the first versions: a bare list, no version. */
    private val version0 = """[{"id":"p1","name":"Home","devices":[
        {"id":"d1","name":"GoodWe","type":"GOODWE_ET","host":"192.168.1.20","port":502,"unitId":247},
        {"id":"d2","name":"Meter","type":"DEMO_METER"}],
        "chartChannels":[{"deviceId":"d1","key":"r:pv_power","slot":0}]}]"""

    @Test
    fun readsTheOriginalBareList() {
        val plants = PlantStore.decode(version0)
        assertEquals(listOf("d1", "d2"), plants.single().devices.map { it.id })
        assertEquals(DeviceType.GOODWE_ET, plants.single().devices[0].type)
        assertEquals(247, plants.single().devices[0].unitId)
        assertEquals("r:pv_power", plants.single().chartChannels.single().key)
    }

    @Test
    fun writesAVersionAndReadsItBack() {
        val plants = PlantStore.decode(version0)
        val saved = PlantStore.encode(plants)
        assertEquals(PlantStore.VERSION, Json.parseToJsonElement(saved).jsonObject["version"]!!.jsonPrimitive.content.toInt())
        assertEquals(plants, PlantStore.decode(saved))
    }

    @Test
    fun aDeviceFromANewerVersionIsKeptNotDropped() {
        val raw = """{"version":1,"plants":[{"id":"p1","name":"Home","devices":[
            {"id":"d1","name":"GoodWe","type":"GOODWE_ET","host":"10.0.0.2"},
            {"id":"d9","name":"Future","type":"SOME_FUTURE_INVERTER","host":"10.0.0.9","newField":true}]}]}"""
        val plant = PlantStore.decode(raw).single()
        assertEquals(listOf("d1"), plant.devices.map { it.id })
        assertEquals(1, plant.unreadableDevices.size)
        // Saving again (e.g. after renaming the plant) writes the unknown device back unchanged.
        val again = Json.parseToJsonElement(PlantStore.encode(listOf(plant.copy(name = "House")))).jsonObject
        val devices = again["plants"]!!.jsonArray[0].jsonObject["devices"]!!.jsonArray
        assertEquals(listOf("d1", "d9"), devices.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals("true", devices[1].jsonObject["newField"]!!.jsonPrimitive.content)
    }

    @Test
    fun unknownFieldsAndEnumValuesFallBackToDefaults() {
        val raw = """{"version":7,"plants":[{"id":"p1","name":"Home","colour":"red","devices":[
            {"id":"m1","name":"Meter","type":"EASTRON","meterRole":"BATTERY_SUBMETER"}]}]}"""
        val device = PlantStore.decode(raw).single().devices.single()
        assertEquals(MeterRole.GRID, device.meterRole)
    }

    @Test(expected = PlantStore.Unreadable::class)
    fun textThatIsntJsonIsReportedNotTreatedAsEmpty() {
        PlantStore.decode("{\"version\":1,\"plants\":[{\"id\"")
    }

    @Test
    fun emptyIsNoPlants() {
        assertTrue(PlantStore.decode(null).isEmpty())
        assertTrue(PlantStore.decode("").isEmpty())
    }

    @Test
    fun everyStoredNameIsStillReadable() {
        fun names(file: String) = javaClass.getResource("/stored/$file")!!.readText().lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
        val types = names("device_types.txt")
        for (name in types) {
            val current = PlantStore.RENAMED_TYPES[name] ?: name
            assertTrue("$name was saved by an earlier version: keep it or add it to RENAMED_TYPES", DeviceType.entries.any { it.name == current })
        }
        for (t in DeviceType.entries) assertTrue("add ${t.name} to stored/device_types.txt", t.name in types)
        val roles = names("meter_roles.txt")
        assertEquals(MeterRole.entries.map { it.name }.toSet(), roles.toSet())
        // And each type round-trips.
        val plant = Plant("p", "P", DeviceType.entries.map { DeviceConfig(it.name, it.name, it) })
        assertEquals(listOf(plant), PlantStore.decode(PlantStore.encode(listOf(plant))))
    }
}
