"""Huawei SUN2000 driver against a register image built from the spec's tables (types and gains)."""
import struct

import pytest

from solar_dashboard.drivers import huawei_sun2000 as hw
from solar_dashboard.drivers.huawei_sun2000 import HuaweiSun2000Driver
from solar_dashboard.modbus import ModbusException, RegisterBlock


class FakeSun2000:
    """Holds only the registers the driver's blocks cover; a read touching anything else raises exception 2,
    as the inverter does. `battery=False` / `meter=False` leave those registers out entirely."""

    def __init__(self, *, battery=True, meter=True, meter_status=1, grid_w=-1234, battery_w=2000, status=0x0200):
        self.connect_delay = 0.0
        self.regs: dict[int, int] = {}
        self.reads = []
        blocks = list(hw.IDENTITY + hw.LIVE + hw.DETAIL) + (list(hw.METER) if meter else []) + (list(hw.BATTERY) if battery else [])
        for start, count in blocks:
            for a in range(start, start + count):
                self.regs[a] = 0
        self._text(30000, "SUN2000-10KTL-M1", 15); self._text(30015, "HV2150000001", 10)
        self.regs[30071] = 4; self._u32(30073, 10_000)  # 4 strings, 10 kW rated (gain 1000)
        for i, (v, a) in enumerate(((3805, 812), (3790, 800), (0, 0), (0, 0))):
            self.regs[32016 + 2 * i] = v; self.regs[32017 + 2 * i] = a  # V gain 10, A gain 100
        self._i32(32064, 6_125)  # 6.125 kW input
        self._i32(32080, 5_900)  # 5.9 kW active output
        self.regs[32085] = 5002; self.regs[32087] = 452; self.regs[32089] = status
        self._u32(32106, 1_234_567); self._u32(32114, 2_345)  # 12345.67 kWh, 23.45 kWh
        if meter:
            self.regs[37100] = meter_status
            self._i32(37101, 2311); self._i32(37103, 2320); self._i32(37105, 2298)  # V gain 10
            self._i32(37107, 512); self._i32(37109, 498); self._i32(37111, 505)  # A gain 100
            self._i32(37113, grid_w); self.regs[37118] = 5001
            self._i32(37119, 512_300); self._i32(37121, 98_700)
            self._i32(37132, -400); self._i32(37134, -420); self._i32(37136, -414)
        if battery:
            self._u32(37758, 10_000); self.regs[37760] = 653; self.regs[37762] = 2
            self.regs[37763] = 4501; self._i32(37765, battery_w)

    def _u32(self, a, v):
        self.regs[a], self.regs[a + 1] = v >> 16 & 0xFFFF, v & 0xFFFF

    def _i32(self, a, v):
        self._u32(a, v & 0xFFFFFFFF)

    def _text(self, a, s, n):
        b = s.encode().ljust(2 * n, b"\0")
        for i in range(n):
            self.regs[a + i] = b[2 * i] << 8 | b[2 * i + 1]

    def read_holding_registers(self, unit_id, register, count):
        self.reads.append((register, count))
        if any(a not in self.regs for a in range(register, register + count)):
            raise ModbusException(2)
        return RegisterBlock(register, b"".join(struct.pack(">H", self.regs[a]) for a in range(register, register + count)))


def test_live_values_and_scaling():
    r = HuaweiSun2000Driver(FakeSun2000(), 1).read()
    assert r.pv_power_w == 6125 and r.status == "On-grid" and r.temperature_c == pytest.approx(45.2)
    assert [(p.index, p.voltage_v, p.current_a) for p in r.pv_strings] == [(1, 380.5, 8.12), (2, 379.0, 8.0)]
    assert r.today.pv_kwh == pytest.approx(23.45) and r.lifetime_pv_kwh == pytest.approx(12345.67)


def test_battery_sign_is_flipped_to_ours():
    r = HuaweiSun2000Driver(FakeSun2000(battery_w=2000), 1).read()  # Huawei: > 0 charging
    assert r.battery.power_w == -2000 and r.battery.mode == "Charging" and r.battery.soc_percent == 65
    r = HuaweiSun2000Driver(FakeSun2000(battery_w=-1500), 1).read()
    assert r.battery.power_w == 1500 and r.battery.mode == "Discharging"


def test_meter_grid_and_phases():
    r = HuaweiSun2000Driver(FakeSun2000(grid_w=-1234), 1).read()  # taken as > 0 export
    assert r.grid_power_w == -1234
    assert [(p.name, p.voltage_v, p.power_w) for p in r.phases] == [("L1", 231.1, -400), ("L2", 232.0, -420), ("L3", 229.8, -414)]
    assert r.phases[0].frequency_hz == pytest.approx(50.01)


def test_without_battery_or_meter():
    fake = FakeSun2000(battery=False, meter=False)
    d = HuaweiSun2000Driver(fake, 1)
    r = d.read()
    assert r.battery is None and r.grid_power_w is None and r.pv_power_w == 6125
    before = len(fake.reads)
    d.read()
    assert (37100, 39) not in fake.reads[before:]  # rejected blocks aren't asked again


def test_offline_meter_gives_unknown_grid():
    assert HuaweiSun2000Driver(FakeSun2000(meter_status=0), 1).read().grid_power_w is None


def test_unknown_status_and_identity():
    d = HuaweiSun2000Driver(FakeSun2000(status=0x0BEE), 1)
    assert d.read().status == "Status 0x0BEE"
    info = d.identify()
    assert info.model == "SUN2000-10KTL-M1" and info.serial == "HV2150000001"


def test_details_and_connect_delay():
    fake = FakeSun2000()
    r = HuaweiSun2000Driver(fake, 1).read()
    assert fake.connect_delay == hw.CONNECT_DELAY_S
    titles = [s.title for s in r.details]
    for t in ("Identity", "PV inputs", "Inverter output", "Energy", "Power meter", "Battery (", "Battery unit 1", "Alarms"):
        assert any(x.startswith(t) for x in titles), t
    meter = dict(next(s for s in r.details if s.title.startswith("Power meter")).rows)
    assert meter["Exported energy (positive active)"] == "5123.00 kWh" and meter["Imported energy (reverse active)"] == "987.00 kWh"


def test_blocks_stay_within_125_registers():
    for start, count in hw.IDENTITY + hw.LIVE + hw.METER + hw.BATTERY + hw.DETAIL:
        assert 1 <= count <= 125
