// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.ui

import ch.amslersolar.solardash.model.DeviceConfig
import ch.amslersolar.solardash.model.DeviceProblem
import ch.amslersolar.solardash.model.DeviceReading
import ch.amslersolar.solardash.model.DeviceStatus
import ch.amslersolar.solardash.model.DeviceType
import org.junit.Assert.assertEquals
import org.junit.Test

/** What a device's card says: its connection first, then what the device reports, worst first. */
class DeviceCardStateTest {
    private val inverter = DeviceConfig(id = "i", name = "GoodWe", type = DeviceType.GOODWE_ET, host = "192.168.0.10")
    private val meter = DeviceConfig(id = "m", name = "Meter", type = DeviceType.EASTRON, host = "192.168.0.11")

    private fun read(vararg problems: DeviceProblem, status: String? = null) = DeviceStatus(inverter, DeviceReading(pvPowerW = 1000, status = status, problems = problems.toList()), null)

    @Test
    fun statesWorstFirst() {
        assertEquals(CardState.WAITING, cardState(inverter, null))
        assertEquals(CardState.NO_CONNECTION, cardState(inverter, DeviceStatus(inverter, null, "timeout")))
        assertEquals(CardState.FAULT, cardState(inverter, read(DeviceProblem(DeviceProblem.Kind.WARNING, "w"), DeviceProblem(DeviceProblem.Kind.FAULT, "f"))))
        assertEquals(CardState.WARNING, cardState(inverter, read(DeviceProblem(DeviceProblem.Kind.WARNING, "w"))))
        assertEquals(CardState.SLEEPING, cardState(inverter, read(status = "Sleeping (no sun)")))
        assertEquals(CardState.RUNNING, cardState(inverter, read(status = "On-grid")))
        assertEquals(CardState.ONLINE, cardState(meter, DeviceStatus(meter, DeviceReading(gridPowerW = -300), null)))
    }
}
