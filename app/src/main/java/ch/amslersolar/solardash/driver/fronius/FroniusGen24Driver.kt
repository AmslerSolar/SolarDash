// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.driver.fronius

import ch.amslersolar.solardash.driver.modbus.ModbusTcpClient
import ch.amslersolar.solardash.driver.sunspec.RegisterReader
import ch.amslersolar.solardash.driver.sunspec.SunSpecInverterDriver

/**
 * Fronius GEN24 / GEN24 Plus (incl. BYD or Fronius Reserva battery and Fronius Smart Meter) over SunSpec Modbus TCP.
 *
 * Spec: "Fronius GEN24 Modbus TCP & RTU" 42,0410,2649 (docs/specs/fronius/). Port 502, inverter unit id 1; the Smart
 * Meter answers through the inverter under unit id 200 (Fronius default, settable 1–247).
 * - Register addresses aren't fixed: models are found by walking the SunSpec chain (the spec's "correct procedure").
 * - "SunSpec Model Type" setting: "float" or "int + SF"; both are read, scale factors with every poll (the spec says
 *   they change with firmware and at runtime).
 * - Battery: the "StCha" / "StDisCha" modules of model 160 (MPPT 3 / 4) and model 124; see [SunSpecInverterDriver].
 * - Grid: at meter location 0 (grid), + = consuming from the grid, − = feeding in (spec, meter table); other
 *   locations (load, generator, sub-load) aren't supported.
 */
class FroniusGen24Driver(reader: RegisterReader, unitId: Int, meterUnitId: Int = METER_UNIT_ID, invertGrid: Boolean = false) :
    SunSpecInverterDriver(reader, unitId, PROFILE.copy(meterUnitId = meterUnitId).invertedGrid(invertGrid)) {
    constructor(client: ModbusTcpClient, unitId: Int, invertGrid: Boolean = false) :
        this(RegisterReader { u, r, c -> client.readHoldingRegisters(u, r, c) }, unitId, invertGrid = invertGrid)

    companion object {
        const val METER_UNIT_ID = 200
        val PROFILE = Profile(maker = "Fronius", meterUnitId = METER_UNIT_ID, meterImportPositive = true, meterTitle = "Smart Meter (grid)")
    }
}
