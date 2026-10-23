// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver.fimer

import ch.amslersolar.dashboard.driver.DeviceDriver
import ch.amslersolar.dashboard.driver.modbus.ModbusTcpClient
import ch.amslersolar.dashboard.driver.sunspec.MoreSunSpecProfiles
import ch.amslersolar.dashboard.driver.sunspec.RegisterReader
import ch.amslersolar.dashboard.driver.sunspec.SunSpecMakersDriver

/**
 * FIMER / ABB inverters (UNO-DM-PLUS, TRIO-TM, PVS) via the VSN300 / VSN700 logger or built-in SunSpec Modbus TCP:
 * the shared SunSpec driver, accepting "ABB" or "FIMER" as manufacturer. Unit id and port quirks: see
 * [MoreSunSpecProfiles.FIMER] (247 for the logger, else the inverter's Aurora address; unverified).
 */
class FimerSunSpecDriver(reader: RegisterReader, unitId: Int) :
    DeviceDriver by SunSpecMakersDriver(reader, unitId, MoreSunSpecProfiles.FIMER_MAKERS, MoreSunSpecProfiles.FIMER) {
    constructor(client: ModbusTcpClient, unitId: Int) : this(RegisterReader { u, r, c -> client.readHoldingRegisters(u, r, c) }, unitId)
}
