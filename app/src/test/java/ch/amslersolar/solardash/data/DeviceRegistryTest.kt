// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import ch.amslersolar.solardash.model.BatteryState
import ch.amslersolar.solardash.model.DeviceConfig
import ch.amslersolar.solardash.model.DeviceReading
import ch.amslersolar.solardash.model.DeviceStatus
import ch.amslersolar.solardash.model.DeviceType
import ch.amslersolar.solardash.model.Plant
import ch.amslersolar.solardash.model.PlantAggregator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class) // not SolarApp: no WorkManager here
class DeviceRegistryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var plants: PlantRepository
    private lateinit var history: HistoryRepository
    private lateinit var registry: DeviceRegistry

    private val t0 = Instant.parse("2026-10-21T10:00:00Z")

    @Before
    fun setUp() = runBlocking {
        context.deleteDatabase("history.db")
        plants = PlantRepository(context)
        plants.plants.first().forEach { plants.removePlant(it.id) }
        history = HistoryRepository(context)
        registry = DeviceRegistry(plants, history)
        plants.addPlant(Plant(id = "p", name = "Home"))
    }

    private fun inverter(id: String, serial: String = "GW-123") =
        DeviceConfig(id = id, name = "Inverter", type = DeviceType.GOODWE_ET, host = "192.168.0.10", serial = serial)

    /** Records one minute of the device's channels (PV and battery SOC). */
    private suspend fun record(device: DeviceConfig, at: Instant, pvW: Int) {
        val reading = DeviceReading(pvPowerW = pvW, battery = BatteryState(0, 50, null, ""))
        history.record("p", PlantAggregator.aggregate(listOf(DeviceStatus(device, reading, null)), at))
    }

    private suspend fun pv(deviceId: String) =
        history.channelSeries(deviceId, listOf("r:pv_power"), 0, Long.MAX_VALUE).getValue("r:pv_power").map { it.second }

    @Test
    fun removedDeviceGetsItsDataBackWhenAddedAgain() = runBlocking {
        val first = registry.add("p", inverter("a"))
        record(first, t0, 1_000)
        registry.remove("p", first, deleteData = false)

        val again = registry.add("p", inverter("b")) // a new form, a new random id
        assertEquals("a", again.id)
        assertEquals(listOf("a"), plants.plants.first().single().devices.map { it.id })
        assertEquals(listOf(1_000.0), pv("a"))
    }

    @Test
    fun dataRecordedBeforeTheSerialWasKnownIsMerged() = runBlocking {
        val first = registry.add("p", inverter("a"))
        record(first, t0, 1_000)
        registry.remove("p", first, deleteData = false)

        // Added again by hand, without Test connection: no serial yet, so a new id.
        val again = registry.add("p", inverter("b", serial = ""))
        assertEquals("b", again.id)
        record(again, t0.plusSeconds(3_600), 2_000)
        // The serial is learned while polling.
        registry.claim("p", again.copy(serial = "GW-123"))

        assertEquals(listOf("a"), plants.plants.first().single().devices.map { it.id })
        assertEquals(listOf(1_000.0, 2_000.0), pv("a"))
        assertTrue(pv("b").isEmpty())
        assertEquals(1, history.channelCatalog("a").count { it.first == "r:pv_power" })
    }

    @Test
    fun deletingDataOnRemoveForgetsTheDevice() = runBlocking {
        val first = registry.add("p", inverter("a"))
        record(first, t0, 1_000)
        registry.remove("p", first, deleteData = true)
        assertTrue(pv("a").isEmpty())
        assertNotEquals("a", registry.add("p", inverter("b")).id)
    }

    @Test
    fun withoutSerialTheDataCantBeKept() = runBlocking {
        val first = registry.add("p", inverter("a", serial = ""))
        record(first, t0, 1_000)
        registry.remove("p", first, deleteData = false)
        assertTrue(pv("a").isEmpty())
    }

    @Test
    fun sameSerialOfAnotherTypeIsAnotherDevice() = runBlocking {
        val first = registry.add("p", inverter("a"))
        registry.remove("p", first, deleteData = false)
        val other = registry.add("p", inverter("b").copy(type = DeviceType.HUAWEI))
        assertEquals("b", other.id)
    }

    @Test
    fun removingAPlantKeepsItsDevicesData() = runBlocking {
        val first = registry.add("p", inverter("a"))
        record(first, t0, 1_000)
        registry.removePlant("p", deleteDeviceData = false)
        plants.addPlant(Plant(id = "q", name = "New"))
        assertEquals("a", registry.add("q", inverter("c")).id)
        assertEquals(listOf(1_000.0), pv("a"))
    }
}
