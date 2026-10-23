// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject

enum class DeviceKind(val displayName: String) {
    INVERTER("Inverters"),
    BATTERY("Batteries"),
    METER("Power meters"),
    /** Heat pumps, heating rods, wallboxes: parts of the home load, shown separately, not added to the totals. */
    CONSUMER("Consumers"),
}

/** What a power meter measures. */
@Serializable
enum class MeterRole(val displayName: String) {
    GRID("Grid connection"),
    PV("PV production (e.g. an inverter without communication)"),
    CONSUMER("Consumer sub-meter (e.g. a heat pump)"),
}

enum class DeviceType(
    val displayName: String,
    val kind: DeviceKind,
    val defaultPort: Int = 502,
    val defaultUnitId: Int = 1,
    val supported: Boolean = false,
    val needsHost: Boolean = true,
    val modbus: Boolean = true,
    val needsPassword: Boolean = false,
    /** SunSpec meters: the grid sign isn't standardised, so the device form offers to invert it. */
    val gridSignOption: Boolean = false,
) {
    GOODWE_ET("GoodWe ET hybrid (Modbus TCP)", DeviceKind.INVERTER, defaultUnitId = 0xF7, supported = true),
    LG_ESS_SUNSPEC("LG ESS Home 8/10 (SunSpec Modbus TCP)", DeviceKind.INVERTER, defaultPort = 6502, supported = true),
    LG_ESS("LG ESS Home 8/10 (local HTTPS API)", DeviceKind.INVERTER, defaultPort = 443, supported = true, modbus = false, needsPassword = true),
    FRONIUS("Fronius GEN24 (Modbus TCP)", DeviceKind.INVERTER, supported = true, gridSignOption = true),
    SMA("SMA Sunny Boy / Tripower / Smart Energy / Sunny Island (Modbus TCP)", DeviceKind.INVERTER, defaultUnitId = 3, supported = true),
    HUAWEI("Huawei SUN2000 (Modbus TCP)", DeviceKind.INVERTER, supported = true),
    KOSTAL_PLENTICORE("Kostal Plenticore (Modbus TCP)", DeviceKind.INVERTER, defaultPort = 1502, defaultUnitId = 71, supported = true),
    POWER_ONE("Power-One / ABB TRIO (via RS485 Modbus TCP gateway)", DeviceKind.INVERTER, defaultUnitId = 2, supported = true),
    GROWATT("Growatt SPH/SPA/MIN/MOD (VPP protocol, via RS485 Modbus TCP gateway)", DeviceKind.INVERTER, supported = true),
    VICTRON("Victron Energy GX (Cerbo GX, Venus OS)", DeviceKind.INVERTER, defaultUnitId = 100, supported = true),
    VOLTRONIC(
        "Voltronic Axpert & rebrands: MPP Solar, EASUN, Mecer, ACDC Dynamics (RS232 via TCP gateway)",
        DeviceKind.INVERTER, defaultPort = 8899, supported = true, modbus = false,
    ),
    SUNGROW("Sungrow SH hybrid / SG string (Modbus TCP)", DeviceKind.INVERTER, supported = true),
    FENECON("FENECON FEMS / OpenEMS (Modbus TCP)", DeviceKind.INVERTER, supported = true),
    ENPHASE(
        "Enphase IQ Gateway / Envoy (local HTTPS API, token)", DeviceKind.INVERTER,
        defaultPort = 443, supported = true, modbus = false, needsPassword = true,
    ),
    INGETEAM("Ingeteam INGECON SUN 1Play / 3Play (Modbus TCP)", DeviceKind.INVERTER, supported = true),
    CPS("CPS / Chint 403X string & central inverters (via RS485 Modbus TCP gateway)", DeviceKind.INVERTER, supported = true),
    RIELLO_CENTRAL("Riello Solartech central inverter (Modbus TCP)", DeviceKind.INVERTER, supported = true),
    KACO_NX3("KACO blueplanet 100/125 NX3 (SunSpec Modbus TCP)", DeviceKind.INVERTER, supported = true),
    FIMER("FIMER / ABB via VSN300 / VSN700 (SunSpec Modbus TCP)", DeviceKind.INVERTER, defaultUnitId = 247, supported = true),
    APSYSTEMS_ECU("APsystems microinverter via ECU-R / ECU-C (SunSpec; one device per microinverter)", DeviceKind.INVERTER, supported = true),
    CONEXT_CL("Schneider Conext CL 18/20/25 (SunSpec Modbus TCP)", DeviceKind.INVERTER, defaultUnitId = 10, supported = true),
    CONEXT_CL60("Schneider Conext CL-60 (Modbus TCP)", DeviceKind.INVERTER, supported = true),
    CONEXT_GATEWAY("Schneider Conext XW system via Conext Gateway / Insight Home", DeviceKind.INVERTER, defaultPort = 503, supported = true),
    KOSTAL_PIKO_CI("Kostal PIKO CI 30/50/60/100 (Modbus TCP)", DeviceKind.INVERTER, defaultPort = 1502, defaultUnitId = 71, supported = true),
    SOLAREDGE("SolarEdge (SunSpec Modbus TCP)", DeviceKind.INVERTER, defaultPort = 1502, supported = true, gridSignOption = true),
    SUNSPEC("Other SunSpec inverter (Modbus TCP)", DeviceKind.INVERTER, supported = true, gridSignOption = true),
    CARLO_GAVAZZI("Carlo Gavazzi EM24 / EM300 / EM210 meter (Modbus TCP or RS485 gateway)", DeviceKind.METER, supported = true),
    EASTRON("Eastron SDM630 / SDM120 meter (via RS485 Modbus TCP gateway)", DeviceKind.METER, supported = true),
    INEPRO_PRO380("inepro PRO380-Mod meter (via RS485 Modbus TCP gateway)", DeviceKind.METER, supported = true),
    ABB_B2X("ABB B23 / B24 meter (via RS485 Modbus TCP gateway)", DeviceKind.METER, supported = true),
    JANITZA_UMG("Janitza UMG 604-PRO / 96RM-E / 96RM meter (Modbus TCP)", DeviceKind.METER, supported = true),
    SCHNEIDER_IEM("Schneider iEM3155 / 3255 / 3355 meter (via RS485 Modbus TCP gateway)", DeviceKind.METER, supported = true),
    SIEMENS_PAC("Siemens SENTRON PAC2200 meter (Modbus TCP)", DeviceKind.METER, supported = true),
    KOSTAL_KSEM("KOSTAL Smart Energy Meter KSEM (Modbus TCP)", DeviceKind.METER, supported = true),
    SHELLY_PRO_3EM("Shelly Pro 3EM meter (Modbus TCP)", DeviceKind.METER, supported = true),
    KEBA_P30("KEBA KeContact P30 wallbox (Modbus TCP)", DeviceKind.CONSUMER, defaultUnitId = 255, supported = true),
    ALFEN_EVE("Alfen Eve wallbox (Modbus TCP, unit id = socket 1 / 2)", DeviceKind.CONSUMER, supported = true),
    MENNEKES_AMTRON("MENNEKES AMTRON Professional / Charge Control (Bender ECU, Modbus TCP)", DeviceKind.CONSUMER, supported = true),
    GO_E("go-eCharger wallbox (Modbus TCP)", DeviceKind.CONSUMER, supported = true),
    MY_PV("my-PV AC ELWA-E / ELWA 2 / AC THOR heating rod (Modbus TCP)", DeviceKind.CONSUMER, supported = true),
    STIEBEL_ELTRON_ISG("Stiebel Eltron heat pump WPM 3 / 3i / WPMsystem (ISG Modbus TCP)", DeviceKind.CONSUMER, supported = true),
    NIBE_S("NIBE S-series heat pump (Modbus TCP)", DeviceKind.CONSUMER, supported = true),
    HOVAL_TOPTRONIC("Hoval heat pump (TopTronic E Modbus gateway)", DeviceKind.CONSUMER, supported = true),
    DEMO_INVERTER("Demo inverter", DeviceKind.INVERTER, supported = true, needsHost = false, modbus = false),
    DEMO_BATTERY("Demo battery", DeviceKind.BATTERY, supported = true, needsHost = false, modbus = false),
    DEMO_METER("Demo grid meter", DeviceKind.METER, supported = true, needsHost = false, modbus = false),
    DEMO_HEAT_PUMP("Demo heat pump", DeviceKind.CONSUMER, supported = true, needsHost = false, modbus = false),
}

@Serializable
data class DeviceConfig(
    val id: String,
    val name: String,
    val type: DeviceType,
    val host: String = "",
    val port: Int = type.defaultPort,
    val unitId: Int = type.defaultUnitId,
    /** LG ESS: the device password; Enphase: the owner access token. Stored in the app's private storage. */
    val password: String = "",
    /** The device's serial number, to find it again when its IP address changes (DHCP). Empty until known. */
    val serial: String = "",
    /** SunSpec meters: flip the grid sign (+ export / + import) where the make's convention isn't the default. */
    val invertGrid: Boolean = false,
    /** Power meters only: what the meter measures. */
    val meterRole: MeterRole = MeterRole.GRID,
) {
    override fun toString() = "DeviceConfig(id=$id, name=$name, type=$type, host=$host, port=$port, unitId=$unitId, serial=$serial)"
}

/** A device channel added to the history chart, with its fixed colour slot. */
@Serializable
data class ChartChannel(val deviceId: String, val key: String, val slot: Int)

@Serializable
data class Plant(
    val id: String,
    val name: String,
    val devices: List<DeviceConfig> = emptyList(),
    val chartChannels: List<ChartChannel> = emptyList(),
    /**
     * Saved devices this version can't read (e.g. a device type from a newer version), kept as they were and
     * written back unchanged by [ch.amslersolar.dashboard.data.PlantStore], so going back to an older APK loses nothing.
     */
    @Transient val unreadableDevices: List<JsonObject> = emptyList(),
)
