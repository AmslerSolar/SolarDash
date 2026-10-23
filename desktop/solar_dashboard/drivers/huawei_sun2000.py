"""Huawei SUN2000 inverters (SUN2000MA residential, incl. hybrid with LUNA2000 battery) over Modbus TCP.

Register map: "SUN2000MA V200R024C00SPC106 Modbus Interface Definitions (V3.0)" (docs/specs/huawei/).
- Port 502. Over TCP, unit 0 is the directly connected device (the inverter's own WLAN/FE port); devices behind
  an SDongle / SmartLogger have their own address, usually 1.
- At most 125 registers per read, and reads must not touch undefined registers (exception 2), so the driver
  reads only contiguous blocks of defined registers. The inverter ignores requests sent straight after
  connecting, hence a short connect delay.
- Battery: 37765 charge/discharge power, > 0 charging (as stated for the per-unit registers) → our sign is
  the opposite. Grid meter: 37113 active power. The spec doesn't state its sign; like the community
  `huawei-solar` library it's taken as > 0 export, with 37119 exported and 37121 imported energy.
  To confirm on a real installation.
- Device status codes (32089) refer to a table that isn't in this document; the codes below are Huawei's
  published list as used by `huawei-solar`. Unknown codes are shown in hex.
"""
from __future__ import annotations

from dataclasses import replace
from datetime import datetime

from ..model import BatteryState, DetailSection, DeviceInfo, DeviceReading, EnergyTotals, PhaseReading, PvString
from ..modbus import ModbusException, ModbusTcpClient, RegisterBlock
from .details import bits, enum, fmt, section

CONNECT_DELAY_S = 1.0
STATIC_REFRESH_POLLS = 60  # identity and ratings: about every 5 minutes
DETAIL_REFRESH_POLLS = 12  # alarms and per-unit battery data: about once a minute

# (start, count): contiguous runs of defined registers, checked against the spec's tables.
IDENTITY = ((30000, 35), (30070, 17))
LIVE = ((32016, 12), (32064, 31), (32106, 14))
METER = ((37100, 39),)
BATTERY = ((37758, 3), (37762, 5), (37780, 8))
DETAIL = ((32008, 4), (37000, 5), (37006, 5), (37014, 5), (37021, 2), (37025, 1), (37046, 4), (37066, 4))

DEVICE_STATUS = {
    0x0000: "Standby: initializing", 0x0001: "Standby: detecting insulation resistance",
    0x0002: "Standby: detecting irradiation", 0x0003: "Standby: grid detecting", 0x0100: "Starting",
    0x0200: "On-grid", 0x0201: "On-grid: power limited", 0x0202: "On-grid: self-derating",
    0x0203: "Off-grid", 0x0300: "Shutdown: fault", 0x0301: "Shutdown: command", 0x0302: "Shutdown: OVGR",
    0x0303: "Shutdown: communication disconnected", 0x0304: "Shutdown: power limited",
    0x0305: "Shutdown: manual startup required", 0x0306: "Shutdown: DC switches disconnected",
    0x0307: "Shutdown: rapid cutoff", 0x0308: "Shutdown: input underpower",
    0x0401: "Grid scheduling: cosφ-P curve", 0x0402: "Grid scheduling: Q-U curve",
    0x0403: "Grid scheduling: PF-U curve", 0x0404: "Grid scheduling: dry contact",
    0x0405: "Grid scheduling: Q-P curve", 0x0500: "Spot-check ready", 0x0501: "Spot-checking",
    0x0600: "Inspecting", 0x0700: "AFCI self-check", 0x0800: "I-V scanning", 0x0900: "DC input detection",
    0x0A00: "Running: off-grid charging", 0xA000: "Standby: no irradiation",
}
BATTERY_STATUS = {0: "Offline", 1: "Standby", 2: "Running", 3: "Fault", 4: "Sleep mode"}  # spec
BATTERY_WORKING_MODES = {0: "None", 1: "Forcible charge/discharge", 2: "Time of use", 3: "Fixed charge/discharge",
                         4: "Maximise self-consumption", 5: "Fully fed to grid"}
METER_STATUS = {0: "Offline", 1: "Normal"}


class Registers:
    """Several register blocks read in one poll, addressed by absolute register number."""

    def __init__(self, blocks: list[RegisterBlock]):
        self.blocks = blocks

    def _block(self, register: int, size: int = 1) -> RegisterBlock | None:
        for b in self.blocks:
            if b.start <= register and register + size <= b.start + len(b.data) // 2:
                return b
        return None

    def has(self, register: int, size: int = 1) -> bool:
        return self._block(register, size) is not None

    def u16(self, r):
        b = self._block(r)
        return None if b is None else b.u16(r)

    def s16(self, r):
        b = self._block(r)
        return None if b is None else b.s16(r)

    def u32(self, r):
        b = self._block(r, 2)
        return None if b is None else b.u32(r)

    def s32(self, r):
        b = self._block(r, 2)
        return None if b is None else b.s32(r)

    def text(self, r, size):
        b = self._block(r, size)
        return "" if b is None else b.ascii(r, size)

    def scaled(self, value, gain: float) -> float | None:
        return None if value is None else value / gain


class HuaweiSun2000Driver:
    def __init__(self, client: ModbusTcpClient, unit_id: int):
        self.client = client
        self.unit_id = unit_id
        client.connect_delay = max(client.connect_delay, CONNECT_DELAY_S)
        self._unsupported: set[int] = set()
        self._identity: Registers | None = None
        self._detail = Registers([])
        self._polls = 0

    def _read(self, segments, optional: bool) -> Registers:
        blocks = []
        for start, count in segments:
            if start in self._unsupported:
                continue
            try:
                blocks.append(self.client.read_holding_registers(self.unit_id, start, count))
            except ModbusException:
                if not optional:
                    raise
                self._unsupported.add(start)  # e.g. no battery or meter on this installation
            except OSError:
                if not optional:
                    raise
        return Registers(blocks)

    def identify(self) -> DeviceInfo:
        r = self._read(IDENTITY, optional=False)
        return DeviceInfo(model=r.text(30000, 15), serial=r.text(30015, 10))

    def read(self) -> DeviceReading:
        if self._identity is None or self._polls % STATIC_REFRESH_POLLS == 0:
            self._identity = self._read(IDENTITY, optional=True)
        live = self._read(LIVE, optional=False)
        meter = self._read(METER, optional=True)
        battery = self._read(BATTERY, optional=True)
        if self._polls % DETAIL_REFRESH_POLLS == 0:
            self._detail = self._read(DETAIL, optional=True)
        self._polls += 1
        return replace(parse(self._identity, live, meter, battery),
                       details=details(self._identity, live, meter, battery, self._detail))


def parse(ident: Registers, live: Registers, meter: Registers, bat: Registers) -> DeviceReading:
    n_strings = ident.u16(30071) or 6
    strings = []
    for i in range(min(n_strings, 6)):  # this map defines PV1–PV6
        v, a = live.scaled(live.s16(32016 + 2 * i), 10), live.scaled(live.s16(32017 + 2 * i), 100)
        if v is not None and (v > 0 or (a or 0) > 0):
            strings.append(PvString(i + 1, v, a or 0.0, None))  # the map has no per-string power

    pv_kw = live.scaled(live.s32(32064), 1000)

    battery = None
    if bat.has(37762) and (bat.u32(37758) or 0) > 0:  # rated capacity 0 → no battery
        raw = bat.s32(37765)  # > 0 charging
        power = 0 if raw is None else -raw
        status = bat.u16(37762)
        mode = "Discharging" if power > 0 else "Charging" if power < 0 else BATTERY_STATUS.get(status, "Standby")
        soc = bat.scaled(bat.u16(37760), 10)
        battery = BatteryState(power, None if soc is None else round(soc), bat.scaled(bat.u16(37763), 10), mode)

    grid = None
    phases = ()
    if meter.has(37100) and meter.u16(37100) == 1:  # meter online
        grid = meter.s32(37113)  # taken as > 0 export (see module notes)
        hz = meter.scaled(meter.s16(37118), 100) or 0.0
        phases = tuple(
            PhaseReading(name, v, abs(meter.scaled(meter.s32(37107 + 2 * i), 100) or 0.0), hz, meter.s32(37132 + 2 * i) or 0)
            for i, name in enumerate(("L1", "L2", "L3"))
            if (v := meter.scaled(meter.s32(37101 + 2 * i), 10))
        )

    status_code = live.u16(32089)
    status = None if status_code is None else DEVICE_STATUS.get(status_code, f"Status 0x{status_code:04X}")
    return DeviceReading(
        pv_power_w=None if pv_kw is None else round(pv_kw * 1000),
        pv_strings=tuple(strings),
        battery=battery,
        grid_power_w=grid,
        phases=phases,
        temperature_c=live.scaled(live.s16(32087), 10),
        status=status,
        today=EnergyTotals(pv_kwh=live.scaled(live.u32(32114), 100)),
        lifetime_pv_kwh=live.scaled(live.u32(32106), 100),
    )


def _time(epoch: int | None) -> str | None:
    if not epoch:
        return None
    return datetime.fromtimestamp(epoch).strftime("%Y-%m-%d %H:%M:%S")


def details(ident: Registers, live: Registers, meter: Registers, bat: Registers, extra: Registers) -> tuple[DetailSection, ...]:
    s = []
    i = ident
    s.append(section("Identity (30000–30086)",
                     ("Model", i.text(30000, 15)), ("Serial number", i.text(30015, 10)), ("Product code", i.text(30025, 10)),
                     ("Firmware version", i.text(30035, 15)), ("Software version", i.text(30050, 15)),
                     ("Model ID", fmt(i.u16(30070))), ("PV strings", fmt(i.u16(30071))), ("MPPTs", fmt(i.u16(30072))),
                     ("Rated power", fmt(i.scaled(i.u32(30073), 1000), "kW", 3)),
                     ("Max. active power", fmt(i.scaled(i.u32(30075), 1000), "kW", 3)),
                     ("Max. apparent power", fmt(i.scaled(i.u32(30077), 1000), "kVA", 3)),
                     ("Max. active power (real)", fmt(i.scaled(i.u32(30083), 1000), "kW", 3)),
                     ("Max. apparent power (real)", fmt(i.scaled(i.u32(30085), 1000), "kVA", 3))))
    l = live
    n = min(ident.u16(30071) or 6, 6)
    s.append(section("PV inputs (32016–32027)",
                     *[row for k in range(n) for row in (
                         (f"PV{k + 1} voltage", fmt(l.scaled(l.s16(32016 + 2 * k), 10), "V", 1)),
                         (f"PV{k + 1} current", fmt(l.scaled(l.s16(32017 + 2 * k), 100), "A", 2)))],
                     ("Total input power", fmt(l.scaled(l.s32(32064), 1000), "kW", 3))))
    s.append(section("Inverter output (32066–32094)",
                     ("Line voltage L1-L2 (or grid voltage)", fmt(l.scaled(l.u16(32066), 10), "V", 1)),
                     ("Line voltage L2-L3", fmt(l.scaled(l.u16(32067), 10), "V", 1)),
                     ("Line voltage L3-L1", fmt(l.scaled(l.u16(32068), 10), "V", 1)),
                     ("L1 voltage", fmt(l.scaled(l.u16(32069), 10), "V", 1)),
                     ("L2 voltage", fmt(l.scaled(l.u16(32070), 10), "V", 1)),
                     ("L3 voltage", fmt(l.scaled(l.u16(32071), 10), "V", 1)),
                     ("L1 current", fmt(l.scaled(l.s32(32072), 1000), "A", 3)),
                     ("L2 current", fmt(l.scaled(l.s32(32074), 1000), "A", 3)),
                     ("L3 current", fmt(l.scaled(l.s32(32076), 1000), "A", 3)),
                     ("Peak active power today", fmt(l.scaled(l.s32(32078), 1000), "kW", 3)),
                     ("Active power", fmt(l.scaled(l.s32(32080), 1000), "kW", 3)),
                     ("Reactive power", fmt(l.scaled(l.s32(32082), 1000), "kvar", 3)),
                     ("Power factor", fmt(l.scaled(l.s16(32084), 1000), "", 3)),
                     ("Grid frequency", fmt(l.scaled(l.u16(32085), 100), "Hz", 2)),
                     ("Efficiency", fmt(l.scaled(l.u16(32086), 100), "%", 2)),
                     ("Internal temperature", fmt(l.scaled(l.s16(32087), 10), "°C", 1)),
                     ("Insulation resistance", fmt(l.scaled(l.u16(32088), 1000), "MΩ", 3)),
                     ("Device status", None if l.u16(32089) is None else
                      f"{DEVICE_STATUS.get(l.u16(32089), 'unknown')} (0x{l.u16(32089):04X})"),
                     ("Error code", fmt(l.u16(32090))),
                     ("Startup time", _time(l.u32(32091))), ("Shutdown time", _time(l.u32(32093)))))
    s.append(section("Energy (32106–32119)",
                     ("Total yield", fmt(l.scaled(l.u32(32106), 100), "kWh", 2)),
                     ("Total DC input energy", fmt(l.scaled(l.u32(32108), 100), "kWh", 2)),
                     ("Yield this hour", fmt(l.scaled(l.u32(32112), 100), "kWh", 2)),
                     ("Yield today", fmt(l.scaled(l.u32(32114), 100), "kWh", 2)),
                     ("Yield this month", fmt(l.scaled(l.u32(32116), 100), "kWh", 2)),
                     ("Yield this year", fmt(l.scaled(l.u32(32118), 100), "kWh", 2))))
    if meter.has(37100):
        m = meter
        s.append(section("Power meter (37100–37138)",
                         ("Status", enum(m.u16(37100), METER_STATUS)), ("Meter type", fmt(m.u16(37125))),
                         *[(f"{p} voltage", fmt(m.scaled(m.s32(37101 + 2 * k), 10), "V", 1)) for k, p in enumerate(("L1", "L2", "L3"))],
                         *[(f"{p} current", fmt(m.scaled(m.s32(37107 + 2 * k), 100), "A", 2)) for k, p in enumerate(("L1", "L2", "L3"))],
                         ("Active power (+ export)", fmt(m.s32(37113), "W")), ("Reactive power", fmt(m.s32(37115), "var")),
                         ("Power factor", fmt(m.scaled(m.s16(37117), 1000), "", 3)),
                         ("Frequency", fmt(m.scaled(m.s16(37118), 100), "Hz", 2)),
                         ("Exported energy (positive active)", fmt(m.scaled(m.s32(37119), 100), "kWh", 2)),
                         ("Imported energy (reverse active)", fmt(m.scaled(m.s32(37121), 100), "kWh", 2)),
                         ("Reactive energy", fmt(m.scaled(m.s32(37123), 100), "kvarh", 2)),
                         *[(f"Line voltage {p}", fmt(m.scaled(m.s32(37126 + 2 * k), 10), "V", 1)) for k, p in enumerate(("L1-L2", "L2-L3", "L3-L1"))],
                         *[(f"{p} active power", fmt(m.s32(37132 + 2 * k), "W")) for k, p in enumerate(("L1", "L2", "L3"))]))
    if bat.has(37762):
        b = bat
        s.append(section("Battery (37758–37787)",
                         ("Rated capacity", fmt(b.u32(37758), "Wh")), ("State of charge", fmt(b.scaled(b.u16(37760), 10), "%", 1)),
                         ("Status", enum(b.u16(37762), BATTERY_STATUS)),
                         ("Bus voltage", fmt(b.scaled(b.u16(37763), 10), "V", 1)),
                         ("Bus current", fmt(b.scaled(b.s16(37764), 10), "A", 1)),
                         ("Charge/discharge power (+ charging)", fmt(b.s32(37765), "W")),
                         ("Charged total", fmt(b.scaled(b.u32(37780), 100), "kWh", 2)),
                         ("Discharged total", fmt(b.scaled(b.u32(37782), 100), "kWh", 2)),
                         ("Charged today", fmt(b.scaled(b.u32(37784), 100), "kWh", 2)),
                         ("Discharged today", fmt(b.scaled(b.u32(37786), 100), "kWh", 2))))
    e = extra
    if e.has(37000):
        s.append(section("Battery unit 1 (37000–37069)",
                         ("Status", enum(e.u16(37000), BATTERY_STATUS)),
                         ("Charge/discharge power (+ charging)", fmt(e.s32(37001), "W")),
                         ("Bus voltage", fmt(e.scaled(e.u16(37003), 10), "V", 1)),
                         ("State of charge", fmt(e.scaled(e.u16(37004), 10), "%", 1)),
                         ("Working mode", enum(e.u16(37006), BATTERY_WORKING_MODES)),
                         ("Rated charge power", fmt(e.u32(37007), "W")), ("Rated discharge power", fmt(e.u32(37009), "W")),
                         ("Fault ID", fmt(e.u16(37014))),
                         ("Charged today", fmt(e.scaled(e.u32(37015), 100), "kWh", 2)),
                         ("Discharged today", fmt(e.scaled(e.u32(37017), 100), "kWh", 2)),
                         ("Bus current", fmt(e.scaled(e.s16(37021), 10), "A", 1)),
                         ("Temperature", fmt(e.scaled(e.s16(37022), 10), "°C", 1)),
                         ("Remaining charge/discharge time", fmt(e.u16(37025), "min")),
                         ("Max. charge power (system)", fmt(e.u32(37046), "W")),
                         ("Max. discharge power (system)", fmt(e.u32(37048), "W")),
                         ("Charged total", fmt(e.scaled(e.u32(37066), 100), "kWh", 2)),
                         ("Discharged total", fmt(e.scaled(e.u32(37068), 100), "kWh", 2))))
    if e.has(32008):
        s.append(section("Alarms (32008–32011)", *[(f"Alarm {k + 1}", bits(e.u16(32008 + k), 4)) for k in range(4)]))
    return tuple(x for x in s if x.rows)
