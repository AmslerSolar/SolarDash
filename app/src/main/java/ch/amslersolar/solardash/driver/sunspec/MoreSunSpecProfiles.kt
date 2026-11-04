// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.driver.sunspec

import ch.amslersolar.solardash.driver.DeviceDriver
import ch.amslersolar.solardash.driver.modbus.ModbusTcpClient
import ch.amslersolar.solardash.model.DeviceInfo
import ch.amslersolar.solardash.model.DeviceReading
import java.io.IOException

/**
 * More makes that speak plain SunSpec, read with [SunSpecInverterDriver]. All of them are PV inverters without a
 * meter of their own in the chain (a SunSpec meter model there is still read, + = drawing from the grid as for any
 * SunSpec device, unverified for these makes), so grid and home figures come from another device of the plant.
 *
 * Discovery: [SunSpecDevice] looks for "SunS" at the on-the-wire addresses 0, 40000 and 50000, which covers every
 * map below (see each entry for how its spec numbers the registers).
 */
object MoreSunSpecProfiles {
    /**
     * Fronius GEN24 / GEN24 Plus (incl. BYD or Fronius Reserva battery and Fronius Smart Meter) over SunSpec Modbus
     * TCP. Spec: "Fronius GEN24 Modbus TCP & RTU" 42,0410,2649 (docs/specs/fronius/). Port 502, inverter unit id 1;
     * the Smart Meter answers through the inverter under unit id 200 (Fronius default, settable 1–247).
     * - "SunSpec Model Type" setting: "float" or "int + SF"; both are read, scale factors with every poll (the spec
     *   says they change with firmware and at runtime).
     * - Battery: the "StCha" / "StDisCha" modules of model 160 (MPPT 3 / 4) and model 124; see [SunSpecInverterDriver].
     * - Grid: at meter location 0 (grid), + = consuming from the grid, − = feeding in (spec, meter table); other
     *   locations (load, generator, sub-load) aren't supported.
     */
    val FRONIUS = SunSpecInverterDriver.Profile(maker = "Fronius", meterUnitId = 200, meterImportPositive = true, meterTitle = "Smart Meter (grid)")

    /**
     * KACO blueplanet 100 / 125 NX3 ("Modbus Protocol blueplanet 100-125 NX3", KACO application note 3016547-03,
     * firmware V5.0.2.4 – V5.1.9.0; docs/specs/kaco/). Modbus TCP (and UDP).
     * - Models: 1, 103 and 113 (both, the driver prefers the float 113), 120–136, 145, 160 (MPPT strings), 7xx
     *   (DER), vendor 64201–64205, 64303, 64310. The NX3 is a PV string inverter: no storage model, no battery.
     *   The vendor models are described in a KACO workbook ("SunSpec-Information-Model-Reference-Kaco.xlsx") that
     *   isn't in docs/specs, so they aren't read.
     * - "Register −1 rule": the note numbers registers from 1 (SunSpec ID at 40001, "use the register address − 1
     *   on the wire"), so "SunS" is at on-the-wire address 40000, where [SunSpecDevice] looks first after 0.
     * - Manufacturer in the Common model: "KACO new energy" (the note's example, which it says "may not reflect the
     *   current implementation"); matched on "KACO". UNVERIFIED: port (502 assumed) and unit id (1 assumed); the
     *   note states neither.
     */
    val KACO = SunSpecInverterDriver.Profile(maker = "KACO", meterUnitId = null, meterImportPositive = true)

    /**
     * FIMER / ABB string inverters (UNO-DM-PLUS, TRIO-TM, PVS) through the VSN300 / VSN700 data logger or their
     * built-in SunSpec Modbus TCP ("VSN300 SunSpec register map", SunSpec model reference generated 2015-04-08;
     * docs/specs/fimer/). The workbook's Common model gives Mn = "ABB", Md = "VSN300"; inverters rebranded FIMER
     * report "FIMER" (UNVERIFIED), so both are accepted ([FIMER_MAKERS], read with [SunSpecMakersDriver]).
     * - Models: 1, 101/103 (int + SF), 111–113 (float), 120–124, 160 (MPPT strings), meters 201–214; whatever the
     *   chain contains is read.
     * - Unit id: UNVERIFIED and not in the workbook. Commonly 247 for the logger's own SunSpec server (VSN300 /
     *   INTERCOM), or the inverter's RS485 "Aurora" address (2 by default) when the logger forwards per inverter.
     *   Port 502 assumed.
     */
    val FIMER = SunSpecInverterDriver.Profile(maker = null, meterUnitId = null, meterImportPositive = true)
    val FIMER_MAKERS = listOf("ABB", "FIMER")

    /**
     * APsystems ECU-R (2160…, firmware 1.3.7+; 2162…, 2.0.2+) and ECU-C (215…, C1.1.3+), not ECU-B ("SunSpec
     * Modbus Rev 3.3", docs/specs/apsystems/). Read through ModbusSpec (apsystems-ecu), whose points of the vendor DC
     * model 114 give the PV inputs (see SpecDevices).
     * - Port 502 ("fixed value"); one unit id per microinverter: the "inverter's modbus address" set for each one
     *   in the EMA Manager app (ECU APP → Workspace → Modbus Configuration). Add one device per microinverter.
     * - The spec's addresses are on-the-wire: the example reads the manufacturer at 0x9C44 = 40004, so "SunS" is
     *   at 40000, Common at 40002 (length 66), 101/103 at 40070, 111/113 at 40122, 123 at 40184, 114 at 40212.
     * - Manufacturer "APsystems" (spec example), model e.g. "YC600".
     */
    val APSYSTEMS = SunSpecInverterDriver.Profile(maker = "APsystems", meterUnitId = null, meterImportPositive = true)

    /**
     * Schneider Electric Conext CL 18/20/25 (NA and E; "Modbus Registers Map – Conext CL", 503-0243-01-01 rev 1.5,
     * docs/specs/schneider/). Port 502, unit ("slave") address 10 by default; addresses are 0-based ("on the
     * wire"), "SunS" at 0x9C40. Manufacturer "SCHNEIDER" in the spec (CL-60: "Schneider Electric"), matched on
     * "Schneider". Read through ModbusSpec (schneider-conext-cl), whose vendor registers give
     * the energy counters and the alarm code; the chain walk takes the Common model as 66 registers (a pad follows).
     */
    val CONEXT_CL = SunSpecInverterDriver.Profile(maker = "Schneider", meterUnitId = null, meterImportPositive = true)
}

/**
 * [SunSpecInverterDriver] for a make that reports one of several manufacturer names in the Common model (FIMER:
 * "ABB" or "FIMER"). identify() throws IOException for any other make.
 */
class SunSpecMakersDriver(
    private val reader: RegisterReader,
    private val unitId: Int,
    private val makers: List<String>,
    profile: SunSpecInverterDriver.Profile = MoreSunSpecProfiles.FIMER,
) : DeviceDriver {
    constructor(client: ModbusTcpClient, unitId: Int, makers: List<String>, profile: SunSpecInverterDriver.Profile = MoreSunSpecProfiles.FIMER) :
        this(RegisterReader { u, r, c -> client.readHoldingRegisters(u, r, c) }, unitId, makers, profile)

    private val inner = SunSpecInverterDriver(reader, unitId, profile.copy(maker = null))

    override suspend fun identify(): DeviceInfo {
        val common = SunSpecDevice(reader, unitId).model(1) ?: throw IOException("SunSpec Common model (1) missing")
        val maker = common.text(2, 16)
        if (makers.none { maker.contains(it, ignoreCase = true) }) throw IOException("Not a ${makers.joinToString(" / ")} device ($maker)")
        return inner.identify()
    }

    override suspend fun read(): DeviceReading = inner.read()
}
