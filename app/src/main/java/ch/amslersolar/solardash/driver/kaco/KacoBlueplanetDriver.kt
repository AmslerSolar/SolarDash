// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.driver.kaco

import ch.amslersolar.solardash.driver.DeviceDriver
import ch.amslersolar.solardash.driver.modbus.ModbusTcpClient
import ch.amslersolar.solardash.driver.sunspec.MoreSunSpecProfiles
import ch.amslersolar.solardash.driver.sunspec.RegisterReader
import ch.amslersolar.solardash.driver.sunspec.SunSpecInverterDriver

/**
 * KACO blueplanet 100 / 125 NX3 over SunSpec Modbus TCP: the shared [SunSpecInverterDriver] with the
 * [MoreSunSpecProfiles.KACO] profile (see there for the spec, models and the register −1 rule). PV only.
 */
class KacoBlueplanetDriver(reader: RegisterReader, unitId: Int) :
    DeviceDriver by SunSpecInverterDriver(reader, unitId, MoreSunSpecProfiles.KACO) {
    constructor(client: ModbusTcpClient, unitId: Int) : this(RegisterReader { u, r, c -> client.readHoldingRegisters(u, r, c) }, unitId)
}
