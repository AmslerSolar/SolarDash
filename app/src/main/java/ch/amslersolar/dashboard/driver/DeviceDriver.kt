// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.driver

import ch.amslersolar.dashboard.driver.apsystems.ApsystemsEcuDriver
import ch.amslersolar.dashboard.driver.consumer.AlfenEveDriver
import ch.amslersolar.dashboard.driver.consumer.BenderEcuDriver
import ch.amslersolar.dashboard.driver.consumer.GoEChargerDriver
import ch.amslersolar.dashboard.driver.consumer.HovalTopTronicDriver
import ch.amslersolar.dashboard.driver.consumer.KebaP30Driver
import ch.amslersolar.dashboard.driver.consumer.MyPvDriver
import ch.amslersolar.dashboard.driver.consumer.NibeSDriver
import ch.amslersolar.dashboard.driver.consumer.StiebelEltronIsgDriver
import ch.amslersolar.dashboard.driver.cps.CpsDriver
import ch.amslersolar.dashboard.driver.demo.DemoBattery
import ch.amslersolar.dashboard.driver.demo.DemoHeatPump
import ch.amslersolar.dashboard.driver.demo.DemoInverter
import ch.amslersolar.dashboard.driver.demo.DemoMeter
import ch.amslersolar.dashboard.driver.enphase.EnphaseGatewayDriver
import ch.amslersolar.dashboard.driver.fenecon.FeneconFemsDriver
import ch.amslersolar.dashboard.driver.fimer.FimerSunSpecDriver
import ch.amslersolar.dashboard.driver.fronius.FroniusGen24Driver
import ch.amslersolar.dashboard.driver.goodwe.GoodWeEtDriver
import ch.amslersolar.dashboard.driver.growatt.GrowattVppDriver
import ch.amslersolar.dashboard.driver.huawei.HuaweiSun2000Driver
import ch.amslersolar.dashboard.driver.ingeteam.IngeteamDriver
import ch.amslersolar.dashboard.driver.kaco.KacoBlueplanetDriver
import ch.amslersolar.dashboard.driver.kostal.KostalPikoCiDriver
import ch.amslersolar.dashboard.driver.kostal.KostalPlenticoreDriver
import ch.amslersolar.dashboard.driver.lg.LgEssDriver
import ch.amslersolar.dashboard.driver.lg.LgEssSunSpecDriver
import ch.amslersolar.dashboard.driver.meter.AbbMeterDriver
import ch.amslersolar.dashboard.driver.meter.CarloGavazziMeterDriver
import ch.amslersolar.dashboard.driver.meter.EastronMeterDriver
import ch.amslersolar.dashboard.driver.meter.IneproPro380Driver
import ch.amslersolar.dashboard.driver.meter.JanitzaUmgDriver
import ch.amslersolar.dashboard.driver.meter.KostalKsemDriver
import ch.amslersolar.dashboard.driver.meter.SchneiderIemDriver
import ch.amslersolar.dashboard.driver.meter.ShellyPro3EmDriver
import ch.amslersolar.dashboard.driver.meter.SiemensPacDriver
import ch.amslersolar.dashboard.driver.modbus.ModbusConnections
import ch.amslersolar.dashboard.driver.powerone.PowerOneTrioDriver
import ch.amslersolar.dashboard.driver.riello.RielloCentralDriver
import ch.amslersolar.dashboard.driver.schneider.ConextCl60Driver
import ch.amslersolar.dashboard.driver.schneider.ConextClDriver
import ch.amslersolar.dashboard.driver.schneider.ConextGatewayDriver
import ch.amslersolar.dashboard.driver.sma.SmaDriver
import ch.amslersolar.dashboard.driver.sungrow.SungrowDriver
import ch.amslersolar.dashboard.driver.sunspec.SunSpecInverterDriver
import ch.amslersolar.dashboard.driver.sunspec.SunSpecProfiles
import ch.amslersolar.dashboard.driver.victron.VictronGxDriver
import ch.amslersolar.dashboard.driver.voltronic.VoltronicAxpertDriver
import ch.amslersolar.dashboard.model.DeviceConfig
import ch.amslersolar.dashboard.model.DeviceInfo
import ch.amslersolar.dashboard.model.DeviceReading
import ch.amslersolar.dashboard.model.DeviceType

/** One implementation per device family. Drivers must be safe to call repeatedly from a polling loop. */
interface DeviceDriver {
    suspend fun identify(): DeviceInfo
    suspend fun read(): DeviceReading

    /** Called when polling stops, e.g. to log out of an HTTPS session. */
    fun close() {}
}

object DeviceDrivers {
    /** Connections come from [connections], so devices behind the same gateway share one TCP connection. */
    fun create(config: DeviceConfig, connections: ModbusConnections): DeviceDriver = when (config.type) {
        DeviceType.GOODWE_ET -> GoodWeEtDriver(connections.get(config.host, config.port), config.unitId)
        DeviceType.HUAWEI -> HuaweiSun2000Driver(connections.get(config.host, config.port), config.unitId)
        DeviceType.KOSTAL_PLENTICORE -> KostalPlenticoreDriver(connections.get(config.host, config.port), config.unitId)
        DeviceType.LG_ESS_SUNSPEC -> LgEssSunSpecDriver(connections.get(config.host, config.port), config.unitId)
        DeviceType.LG_ESS -> LgEssDriver(config.host, config.port, config.password)
        DeviceType.KACO_NX3 -> KacoBlueplanetDriver(connections.get(config.host, config.port), config.unitId)
        DeviceType.FIMER -> FimerSunSpecDriver(connections.get(config.host, config.port), config.unitId)
        DeviceType.APSYSTEMS_ECU -> ApsystemsEcuDriver(connections.get(config.host, config.port), config.unitId)
        DeviceType.CONEXT_CL -> ConextClDriver(connections.get(config.host, config.port), config.unitId)
        DeviceType.CONEXT_CL60 -> ConextCl60Driver(connections.get(config.host, config.port), config.unitId)
        DeviceType.CONEXT_GATEWAY -> ConextGatewayDriver(connections.get(config.host, config.port), config.unitId)
        DeviceType.KOSTAL_PIKO_CI -> KostalPikoCiDriver(connections.get(config.host, config.port), config.unitId)
        DeviceType.INGETEAM -> IngeteamDriver(connections.get(config.host, config.port), config.unitId)
        DeviceType.CPS -> CpsDriver(connections.get(config.host, config.port), config.unitId)
        DeviceType.RIELLO_CENTRAL -> RielloCentralDriver(connections.get(config.host, config.port), config.unitId)
        DeviceType.CARLO_GAVAZZI -> CarloGavazziMeterDriver(connections.get(config.host, config.port), config.unitId)
        DeviceType.EASTRON -> EastronMeterDriver(connections.get(config.host, config.port), config.unitId)
        DeviceType.INEPRO_PRO380 -> IneproPro380Driver(connections.get(config.host, config.port), config.unitId)
        DeviceType.ABB_B2X -> AbbMeterDriver(connections.get(config.host, config.port), config.unitId)
        DeviceType.JANITZA_UMG -> JanitzaUmgDriver(connections.get(config.host, config.port), config.unitId)
        DeviceType.SCHNEIDER_IEM -> SchneiderIemDriver(connections.get(config.host, config.port), config.unitId)
        DeviceType.SIEMENS_PAC -> SiemensPacDriver(connections.get(config.host, config.port), config.unitId)
        DeviceType.KOSTAL_KSEM -> KostalKsemDriver(connections.get(config.host, config.port), config.unitId)
        DeviceType.SHELLY_PRO_3EM -> ShellyPro3EmDriver(connections.get(config.host, config.port), config.unitId)
        DeviceType.KEBA_P30 -> KebaP30Driver(connections.get(config.host, config.port), config.unitId)
        DeviceType.ALFEN_EVE -> AlfenEveDriver(connections.get(config.host, config.port), config.unitId)
        DeviceType.MENNEKES_AMTRON -> BenderEcuDriver(connections.get(config.host, config.port), config.unitId)
        DeviceType.GO_E -> GoEChargerDriver(connections.get(config.host, config.port), config.unitId)
        DeviceType.MY_PV -> MyPvDriver(connections.get(config.host, config.port), config.unitId)
        DeviceType.STIEBEL_ELTRON_ISG -> StiebelEltronIsgDriver(connections.get(config.host, config.port), config.unitId)
        DeviceType.NIBE_S -> NibeSDriver(connections.get(config.host, config.port), config.unitId)
        DeviceType.HOVAL_TOPTRONIC -> HovalTopTronicDriver(connections.get(config.host, config.port), config.unitId)
        DeviceType.DEMO_INVERTER -> DemoInverter()
        DeviceType.DEMO_BATTERY -> DemoBattery()
        DeviceType.DEMO_METER -> DemoMeter()
        DeviceType.DEMO_HEAT_PUMP -> DemoHeatPump()
        DeviceType.FRONIUS -> FroniusGen24Driver(connections.get(config.host, config.port), config.unitId, config.invertGrid)
        DeviceType.SOLAREDGE ->
            SunSpecInverterDriver(connections.get(config.host, config.port), config.unitId, SunSpecProfiles.SOLAREDGE.invertedGrid(config.invertGrid))
        DeviceType.SUNSPEC ->
            SunSpecInverterDriver(connections.get(config.host, config.port), config.unitId, SunSpecProfiles.GENERIC.invertedGrid(config.invertGrid))
        DeviceType.GROWATT -> GrowattVppDriver(connections.get(config.host, config.port), config.unitId)
        DeviceType.VICTRON -> VictronGxDriver(connections.get(config.host, config.port), config.unitId)
        DeviceType.VOLTRONIC -> VoltronicAxpertDriver(config.host, config.port)
        DeviceType.POWER_ONE -> PowerOneTrioDriver(connections.get(config.host, config.port), config.unitId)
        DeviceType.SUNGROW -> SungrowDriver(connections.get(config.host, config.port), config.unitId)
        DeviceType.ENPHASE -> EnphaseGatewayDriver(config.host, config.port, config.password)
        DeviceType.FENECON -> FeneconFemsDriver(connections.get(config.host, config.port), config.unitId)
        DeviceType.SMA -> SmaDriver(connections.get(config.host, config.port), config.unitId)
    }
}
