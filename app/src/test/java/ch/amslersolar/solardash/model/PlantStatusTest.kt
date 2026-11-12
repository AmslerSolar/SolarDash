// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.model

import ch.amslersolar.solardash.driver.classifyConnectionError
import ch.amslersolar.solardash.driver.modbus.ModbusException
import ch.amslersolar.solardash.driver.net.Subnet
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlantStatusTest {
    private val inverter = DeviceConfig("inv", "Inverter", DeviceType.POWER_ONE, host = "192.168.1.20")
    private val hybrid = DeviceConfig("hyb", "Axpert", DeviceType.VOLTRONIC, host = "192.168.1.30")
    private val home = NetworkState(localNetwork = true, internet = true, subnets = listOf(Subnet(InetAddress.getByName("192.168.1.5"), 24)))

    private fun plant(vararg devices: DeviceConfig, offGrid: Boolean = false) = Plant("p", "Home", devices.toList(), offGrid = offGrid)

    private fun snapshot(vararg statuses: DeviceStatus) = PlantAggregator.aggregate(statuses.toList(), Instant.EPOCH)

    private fun failed(device: DeviceConfig, kind: ConnectionError) = DeviceStatus(device, null, "boom", kind)

    private fun kinds(s: PlantStatus) = s.issues.map { it.kind }

    @Test
    fun allWellIsOk() {
        val s = PlantStatusRules.evaluate(plant(inverter), snapshot(DeviceStatus(inverter, DeviceReading(pvPowerW = 3000), null)), home)
        assertTrue(s.ok)
        assertEquals(emptyList<IssueKind>(), kinds(s))
    }

    @Test
    fun noWifiIsOneAlarmInsteadOfOnePerDevice() {
        val net = NetworkState(localNetwork = false, internet = false)
        val s = PlantStatusRules.evaluate(plant(inverter, hybrid), snapshot(failed(inverter, ConnectionError.UNREACHABLE), failed(hybrid, ConnectionError.TIMEOUT)), net)
        assertEquals(listOf(IssueKind.NO_LOCAL_NETWORK, IssueKind.NO_INTERNET), kinds(s))
        assertFalse(s.ok)
    }

    @Test
    fun deviceOutsideTheTabletsSubnetIsTheWrongWifi() {
        val elsewhere = inverter.copy(host = "10.10.100.254")
        val s = PlantStatusRules.evaluate(plant(elsewhere), snapshot(failed(elsewhere, ConnectionError.TIMEOUT)), home)
        assertEquals(listOf(IssueKind.WRONG_NETWORK), kinds(s))
    }

    @Test
    fun aPvInverterThatWentQuietWithoutPowerIsAsleepNotBroken() {
        val last = LastReadings()
        last.update(snapshot(DeviceStatus(inverter, DeviceReading(pvPowerW = 12), null)))
        val night = PlantStatusRules.evaluate(plant(inverter), snapshot(failed(inverter, ConnectionError.TIMEOUT)), home, last)
        assertEquals(listOf(IssueKind.ASLEEP), kinds(night))
        assertTrue(night.ok) // information only: no red display every night

        val unknown = PlantStatusRules.evaluate(plant(inverter), snapshot(failed(inverter, ConnectionError.TIMEOUT)), home)
        assertEquals(listOf(IssueKind.NOT_ANSWERING), kinds(unknown))

        // After a restart at night: the last recorded reading counts too, but not over a newer one.
        val seeded = LastReadings()
        seeded.seed(inverter.id, DeviceReading(pvPowerW = 0))
        assertEquals(listOf(IssueKind.ASLEEP), kinds(PlantStatusRules.evaluate(plant(inverter), snapshot(failed(inverter, ConnectionError.TIMEOUT)), home, seeded)))
        seeded.update(snapshot(DeviceStatus(inverter, DeviceReading(pvPowerW = 3000), null)))
        seeded.seed(inverter.id, DeviceReading(pvPowerW = 0))
        assertEquals(listOf(IssueKind.NOT_ANSWERING), kinds(PlantStatusRules.evaluate(plant(inverter), snapshot(failed(inverter, ConnectionError.TIMEOUT)), home, seeded)))
    }

    @Test
    fun theAsleepLimitFollowsTheInvertersSize() {
        assertEquals(50, PlantStatusRules.asleepBelowW(null))
        assertEquals(60, PlantStatusRules.asleepBelowW(3_000))
        assertEquals(500, PlantStatusRules.asleepBelowW(25_000))
        assertEquals(500, PlantStatusRules.asleepBelowW(100_000))
        // A large inverter going quiet at dusk with 300 W left: asleep once its size is known.
        val last = LastReadings()
        last.update(snapshot(DeviceStatus(inverter, DeviceReading(pvPowerW = 25_000), null)))
        last.update(snapshot(DeviceStatus(inverter, DeviceReading(pvPowerW = 300), null)))
        assertEquals(25_000, last.peak(inverter.id))
        assertEquals(listOf(IssueKind.ASLEEP), kinds(PlantStatusRules.evaluate(plant(inverter), snapshot(failed(inverter, ConnectionError.TIMEOUT)), home, last)))
    }

    @Test
    fun connectionKindsGetTheirOwnIssue() {
        fun one(kind: ConnectionError) = kinds(PlantStatusRules.evaluate(plant(hybrid), snapshot(failed(hybrid, kind)), home)).single()
        assertEquals(IssueKind.CONNECTION_REFUSED, one(ConnectionError.REFUSED))
        assertEquals(IssueKind.CONNECTION_REFUSED, one(ConnectionError.CLOSED))
        assertEquals(IssueKind.LOGIN_FAILED, one(ConnectionError.LOGIN))
        assertEquals(IssueKind.NOT_READY, one(ConnectionError.NOT_READY))
        assertEquals(IssueKind.DEVICE_SETTINGS, one(ConnectionError.DEVICE_REPLY))
        assertEquals(IssueKind.NOT_ANSWERING, one(ConnectionError.TIMEOUT))
    }

    @Test
    fun deviceProblemsAndGridLossDependOnOffGrid() {
        val reading = DeviceReading(
            battery = BatteryState(500, 55, 51.0, "Discharging"),
            problems = listOf(
                DeviceProblem(DeviceProblem.Kind.FAULT, "Over load (F07)"),
                DeviceProblem(DeviceProblem.Kind.MAINS_LOST, "Grid lost"),
            ),
        )
        val onGrid = PlantStatusRules.evaluate(plant(hybrid), snapshot(DeviceStatus(hybrid, reading, null)), home)
        assertEquals(listOf(IssueKind.DEVICE_FAULT, IssueKind.MAINS_LOST), kinds(onGrid))
        assertEquals("Over load (F07)", onGrid.issues.first().detail)

        val offGrid = PlantStatusRules.evaluate(plant(hybrid, offGrid = true), snapshot(DeviceStatus(hybrid, reading, null)), home)
        assertEquals(listOf(IssueKind.DEVICE_FAULT, IssueKind.NO_AC_INPUT), kinds(offGrid))
    }

    @Test
    fun backupModeIsItsOwnState() {
        val backup = DeviceReading(problems = listOf(DeviceProblem(DeviceProblem.Kind.BACKUP_MODE, "Off-grid mode (work mode 2)")))
        val onGrid = PlantStatusRules.evaluate(plant(inverter, hybrid), snapshot(DeviceStatus(inverter, backup, null), DeviceStatus(hybrid, backup, null)), home)
        assertEquals(listOf(IssueKind.BACKUP_MODE), kinds(onGrid)) // once for the plant
        assertFalse(onGrid.ok)
        val offGrid = PlantStatusRules.evaluate(plant(hybrid, offGrid = true), snapshot(DeviceStatus(hybrid, backup, null)), home)
        assertEquals(listOf(IssueKind.OFF_GRID_MODE), kinds(offGrid))
        assertTrue(offGrid.ok) // normal on an off-grid plant
    }

    @Test
    fun gridLossShowsOnceForThePlant() {
        val lost = DeviceReading(problems = listOf(DeviceProblem(DeviceProblem.Kind.MAINS_LOST, "Grid lost")))
        val s = PlantStatusRules.evaluate(plant(inverter, hybrid), snapshot(DeviceStatus(inverter, lost, null), DeviceStatus(hybrid, lost, null)), home)
        assertEquals(listOf(IssueKind.MAINS_LOST), kinds(s))
    }

    @Test
    fun theChargeLevelIsNoProblem() {
        for (soc in listOf(0, 5, 9, 15, 50)) {
            val s = PlantStatusRules.evaluate(plant(hybrid), snapshot(DeviceStatus(hybrid, DeviceReading(battery = BatteryState(0, soc, null, "Idle")), null)), home)
            assertEquals(emptyList<IssueKind>(), kinds(s))
        }
    }

    @Test
    fun noInternetIsOnlyInformation() {
        val s = PlantStatusRules.evaluate(plant(inverter), snapshot(DeviceStatus(inverter, DeviceReading(pvPowerW = 100), null)), home.copy(internet = false))
        assertEquals(listOf(IssueKind.NO_INTERNET), kinds(s))
        assertTrue(s.ok)
    }

    @Test
    fun exceptionsAreClassified() {
        assertEquals(ConnectionError.TIMEOUT, classifyConnectionError(SocketTimeoutException("connect timed out")))
        assertEquals(ConnectionError.REFUSED, classifyConnectionError(ConnectException("Connection refused")))
        assertEquals(ConnectionError.UNREACHABLE, classifyConnectionError(ConnectException("Network is unreachable")))
        assertEquals(ConnectionError.DEVICE_REPLY, classifyConnectionError(ModbusException(2)))
        assertEquals(ConnectionError.NOT_READY, classifyConnectionError(IOException("SunSpec map not valid (first model 203, expected 1)")))
        assertEquals(ConnectionError.CLOSED, classifyConnectionError(IOException("Gateway closed the connection")))
        assertEquals(ConnectionError.OTHER, classifyConnectionError(IOException("No Growatt DTC")))
    }

    @Test
    fun hiddenProblemsAreCountedNotShownAndOthersOfTheDeviceStay() {
        val bogus = DeviceProblem(DeviceProblem.Kind.FAULT, "Comm Fault (E501): microinverter xxxxxx")
        val real = DeviceProblem(DeviceProblem.Kind.FAULT, "Comm Fault (E501): microinverter 709997")
        val reading = DeviceStatus(hybrid, DeviceReading(problems = listOf(bogus, real)), null)
        // The plant's (newer) settings count, not the snapshot's copy of the device.
        val configured = hybrid.copy(hiddenProblems = listOf(bogus.text))
        val s = PlantStatusRules.evaluate(plant(configured), snapshot(reading), home)
        assertEquals(listOf(real.text), s.issues.map { it.detail })
        assertEquals(listOf(bogus.text), s.hidden.map { it.detail })
        assertTrue(s.issues.single().hideable)

        val allHidden = PlantStatusRules.evaluate(plant(configured.copy(hiddenProblems = listOf(bogus.text, real.text))), snapshot(reading), home)
        assertTrue(allHidden.ok)
        assertEquals(2, allHidden.hidden.size)

        // The fault log asks without hiding: both are there.
        val unfiltered = PlantStatusRules.evaluate(plant(configured), snapshot(reading), home, applyHidden = false)
        assertEquals(2, unfiltered.issues.size)
        assertTrue(unfiltered.hidden.isEmpty())
    }

    @Test
    fun onlyTheDevicesOwnProblemsCanBeHidden() {
        val s = PlantStatusRules.evaluate(plant(inverter), snapshot(failed(inverter, ConnectionError.TIMEOUT)), home)
        assertFalse(s.issues.single().hideable) // "not answering" is the app's finding, not a device message
    }
}
