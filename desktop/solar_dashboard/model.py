"""Plants, devices, readings and plant-wide totals.

Sign conventions everywhere: grid power is positive when exporting, negative when importing;
battery power is positive when discharging, negative when charging.
"""
from __future__ import annotations

import time
from dataclasses import dataclass, field
from enum import Enum


class DeviceKind(Enum):
    INVERTER = "Inverters"
    BATTERY = "Batteries"
    METER = "Power meters"


class DeviceType(Enum):
    # name: (display name, kind, default port, default unit id, supported, needs host, Modbus[, needs password])
    GOODWE_ET = ("GoodWe ET hybrid (Modbus TCP)", DeviceKind.INVERTER, 502, 0xF7, True, True, True)
    LG_ESS_SUNSPEC = ("LG ESS Home 8/10 (SunSpec Modbus TCP)", DeviceKind.INVERTER, 6502, 1, True, True, True)
    LG_ESS = ("LG ESS Home 8/10 (local HTTPS API)", DeviceKind.INVERTER, 443, 1, True, True, False, True)
    FRONIUS = ("Fronius (Solar API)", DeviceKind.INVERTER, 80, 1, False, True, False)
    SMA = ("SMA (Modbus TCP)", DeviceKind.INVERTER, 502, 3, False, True, True)
    HUAWEI = ("Huawei SUN2000 (Modbus TCP)", DeviceKind.INVERTER, 502, 1, True, True, True)
    SOLAREDGE = ("SolarEdge (Modbus TCP)", DeviceKind.INVERTER, 1502, 1, False, True, True)
    DEMO_INVERTER = ("Demo inverter", DeviceKind.INVERTER, 0, 1, True, False, False)
    DEMO_BATTERY = ("Demo battery", DeviceKind.BATTERY, 0, 1, True, False, False)
    DEMO_METER = ("Demo grid meter", DeviceKind.METER, 0, 1, True, False, False)

    def __init__(self, display_name, kind, default_port, default_unit_id, supported, needs_host, modbus,
                 needs_password=False):
        self.display_name = display_name
        self.kind = kind
        self.default_port = default_port
        self.default_unit_id = default_unit_id
        self.supported = supported
        self.needs_host = needs_host
        self.modbus = modbus
        self.needs_password = needs_password


@dataclass(frozen=True)
class DeviceConfig:
    id: str
    name: str
    type: DeviceType
    host: str = ""
    port: int = 0
    unit_id: int = 1
    password: str = field(default="", repr=False)  # LG ESS: the device password

    def to_json(self) -> dict:
        d = {"id": self.id, "name": self.name, "type": self.type.name, "host": self.host,
             "port": self.port, "unit_id": self.unit_id}
        if self.password:
            d["password"] = self.password
        return d

    @staticmethod
    def from_json(d: dict) -> DeviceConfig:
        t = DeviceType[d["type"]]
        return DeviceConfig(d["id"], d["name"], t, d.get("host", ""), d.get("port", t.default_port),
                            d.get("unit_id", t.default_unit_id), d.get("password", ""))

    def address(self) -> str:
        if not self.type.needs_host:
            return self.type.display_name
        if self.type.modbus:
            return f"{self.type.display_name} · {self.host}:{self.port} · unit {self.unit_id}"
        return f"{self.type.display_name} · {self.host}:{self.port}"


@dataclass(frozen=True)
class Plant:
    id: str
    name: str
    devices: tuple[DeviceConfig, ...] = ()
    chart_channels: tuple[tuple[str, str, int], ...] = ()  # (device id, channel key, colour slot) on the chart

    def to_json(self) -> dict:
        d = {"id": self.id, "name": self.name, "devices": [x.to_json() for x in self.devices]}
        if self.chart_channels:
            d["chart_channels"] = [list(c) for c in self.chart_channels]
        return d

    @staticmethod
    def from_json(d: dict) -> Plant:
        return Plant(d["id"], d["name"], tuple(DeviceConfig.from_json(x) for x in d.get("devices", [])),
                     tuple(tuple(c) for c in d.get("chart_channels", [])))

    @property
    def has_demo_devices(self) -> bool:
        return any(not d.type.needs_host for d in self.devices)


@dataclass(frozen=True)
class PvString:
    index: int
    voltage_v: float
    current_a: float
    power_w: int | None  # None when the device only reports voltage and current


@dataclass(frozen=True)
class PhaseReading:
    """One grid phase, named L1, L2 or L3 in every driver. Current and power are None when the device only
    reports the phase voltage (power then exists only as the grid total)."""
    name: str
    voltage_v: float
    current_a: float | None
    frequency_hz: float
    power_w: int | None


@dataclass(frozen=True)
class BatteryState:
    power_w: int
    soc_percent: int | None
    voltage_v: float | None
    mode: str


@dataclass(frozen=True)
class EnergyTotals:
    pv_kwh: float | None = None
    export_kwh: float | None = None
    import_kwh: float | None = None
    load_kwh: float | None = None


@dataclass(frozen=True)
class DeviceInfo:
    model: str
    serial: str


@dataclass(frozen=True)
class DetailSection:
    """Everything else a driver knows about the device, for the detail view: (label, formatted value) rows."""
    title: str
    rows: tuple[tuple[str, str], ...]


@dataclass(frozen=True)
class DeviceReading:
    """What one device measured in one poll. Every field is optional."""
    pv_power_w: int | None = None
    pv_strings: tuple[PvString, ...] = ()
    battery: BatteryState | None = None
    grid_power_w: int | None = None
    phases: tuple[PhaseReading, ...] = ()
    load_power_w: int | None = None
    temperature_c: float | None = None
    status: str | None = None
    today: EnergyTotals = EnergyTotals()
    lifetime_pv_kwh: float | None = None
    details: tuple[DetailSection, ...] = ()


@dataclass(frozen=True)
class DeviceStatus:
    device: DeviceConfig
    reading: DeviceReading | None
    error: str | None


class GridSource(Enum):
    METERS = "meters"
    INVERTERS = "inverters"


@dataclass(frozen=True)
class PlantBattery:
    power_w: int
    soc_percent: int | None


@dataclass(frozen=True)
class PlantSnapshot:
    read_at: float  # epoch seconds
    devices: tuple[DeviceStatus, ...]
    pv_power_w: int | None
    battery: PlantBattery | None
    grid_power_w: int | None
    grid_source: GridSource | None
    load_power_w: int | None
    today: EnergyTotals = field(default_factory=EnergyTotals)

    @property
    def failed_devices(self) -> list[DeviceStatus]:
        return [d for d in self.devices if d.error is not None]


def _sum(values) -> int | float | None:
    present = [v for v in values if v is not None]
    return sum(present) if present else None


def aggregate(statuses: list[DeviceStatus], read_at: float | None = None) -> PlantSnapshot:
    """Plant totals. A configured meter decides the grid value (even when it fails, so it reads as unknown
    instead of silently falling back to the inverters); home is solar + battery − grid."""
    readings = [(s.device, s.reading) for s in statuses if s.reading is not None]
    pv = _sum(r.pv_power_w for _, r in readings)

    batteries = [r.battery for _, r in readings if r.battery is not None]
    battery = None
    if batteries:
        socs = [b.soc_percent for b in batteries if b.soc_percent is not None]
        battery = PlantBattery(sum(b.power_w for b in batteries), round(sum(socs) / len(socs)) if socs else None)

    kinds = {s.device.type.kind for s in statuses}
    grid_source = (GridSource.METERS if DeviceKind.METER in kinds
                   else GridSource.INVERTERS if DeviceKind.INVERTER in kinds else None)
    wanted = {GridSource.METERS: DeviceKind.METER, GridSource.INVERTERS: DeviceKind.INVERTER}.get(grid_source)
    grid_readings = [r for d, r in readings if d.type.kind == wanted]
    grid = _sum(r.grid_power_w for r in grid_readings)

    if grid is not None:
        load = (pv or 0) + (battery.power_w if battery else 0) - grid
    else:
        load = _sum(r.load_power_w for _, r in readings)

    return PlantSnapshot(
        read_at=time.time() if read_at is None else read_at,
        devices=tuple(statuses),
        pv_power_w=pv,
        battery=battery,
        grid_power_w=grid,
        grid_source=grid_source,
        load_power_w=load,
        today=EnergyTotals(
            pv_kwh=_sum(r.today.pv_kwh for _, r in readings),
            export_kwh=_sum(r.today.export_kwh for r in grid_readings),
            import_kwh=_sum(r.today.import_kwh for r in grid_readings),
            load_kwh=_sum(r.today.load_kwh for _, r in readings),
        ),
    )
