// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.driver

import ch.amslersolar.solardash.driver.spec.SpecDevices
import ch.amslersolar.solardash.driver.abb.AbbCddDriver
import ch.amslersolar.solardash.driver.abb.AuroraDriver
import ch.amslersolar.solardash.driver.abb.TcpAuroraTransport
import ch.amslersolar.solardash.driver.apsystems.ApsystemsEcuDriver
import ch.amslersolar.solardash.driver.demo.DemoBattery
import ch.amslersolar.solardash.driver.demo.DemoHeatPump
import ch.amslersolar.solardash.driver.demo.DemoInverter
import ch.amslersolar.solardash.driver.demo.DemoMeter
import ch.amslersolar.solardash.driver.enphase.EnphaseGatewayDriver
import ch.amslersolar.solardash.driver.fimer.FimerSunSpecDriver
import ch.amslersolar.solardash.driver.fronius.FroniusGen24Driver
import ch.amslersolar.solardash.driver.kaco.KacoBlueplanetDriver
import ch.amslersolar.solardash.driver.lg.LgEssDriver
import ch.amslersolar.solardash.driver.lg.LgEssSunSpecDriver
import ch.amslersolar.solardash.driver.modbus.ModbusConnections
import ch.amslersolar.solardash.driver.schneider.ConextClDriver
import ch.amslersolar.solardash.driver.sunspec.SunSpecInverterDriver
import ch.amslersolar.solardash.driver.sunspec.SunSpecProfiles
import ch.amslersolar.solardash.driver.voltronic.VoltronicAxpertDriver
import ch.amslersolar.solardash.model.DeviceConfig
import ch.amslersolar.solardash.model.DeviceInfo
import ch.amslersolar.solardash.model.DeviceReading
import ch.amslersolar.solardash.model.DeviceType

/** One implementation per device family. Drivers must be safe to call repeatedly from a polling loop. */
interface DeviceDriver {
    suspend fun identify(): DeviceInfo
    suspend fun read(): DeviceReading

    /** Called when polling stops, e.g. to log out of an HTTPS session. */
    fun close() {}
}

object DeviceDrivers {
    /** Connections come from [connections], so devices behind the same gateway share one TCP connection. */
    fun create(config: DeviceConfig, connections: ModbusConnections): DeviceDriver =
        (if (SpecDevices.handles(config.type)) SpecDevices.create(config.type, connections.get(config), config.unitId, config.model, config.specId) else null)
            ?: handWritten(config, connections)

    /** The hand-written drivers: everything not (yet) read through ModbusSpec. */
    private fun handWritten(config: DeviceConfig, connections: ModbusConnections): DeviceDriver = when (config.type) {
        DeviceType.LG_ESS_SUNSPEC -> LgEssSunSpecDriver(connections.get(config), config.unitId)
        DeviceType.LG_ESS -> LgEssDriver(config.host, config.port, config.password)
        DeviceType.KACO_NX3 -> KacoBlueplanetDriver(connections.get(config), config.unitId)
        DeviceType.FIMER -> FimerSunSpecDriver(connections.get(config), config.unitId)
        DeviceType.APSYSTEMS_ECU -> ApsystemsEcuDriver(connections.get(config), config.unitId)
        DeviceType.CONEXT_CL -> ConextClDriver(connections.get(config), config.unitId)
        DeviceType.DEMO_INVERTER -> DemoInverter()
        DeviceType.DEMO_BATTERY -> DemoBattery()
        DeviceType.DEMO_METER -> DemoMeter()
        DeviceType.DEMO_HEAT_PUMP -> DemoHeatPump()
        DeviceType.FRONIUS -> FroniusGen24Driver(connections.get(config), config.unitId, config.invertGrid)
        DeviceType.SOLAREDGE ->
            SunSpecInverterDriver(connections.get(config), config.unitId, SunSpecProfiles.SOLAREDGE.invertedGrid(config.invertGrid))
        DeviceType.SUNSPEC ->
            SunSpecInverterDriver(connections.get(config), config.unitId, SunSpecProfiles.GENERIC.invertedGrid(config.invertGrid))
        DeviceType.VOLTRONIC -> VoltronicAxpertDriver(config.host, config.port)
        DeviceType.ENPHASE -> EnphaseGatewayDriver(config.host, config.port, config.password)
        DeviceType.ABB_CDD -> AbbCddDriver(config.host, config.port)
        DeviceType.AURORA ->
            AuroraDriver(connections.shared("aurora:${config.host}:${config.port}") { TcpAuroraTransport(config.host, config.port) }, config.unitId)
        else -> error("${config.type} is read through ModbusSpec")
    }
}
