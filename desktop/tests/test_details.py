"""The detail view's sections: every driver lists what it knows, with the right scaling."""
import struct

from solar_dashboard.drivers import lg_ess
from solar_dashboard.drivers.goodwe_et import GoodWeEtDriver
from solar_dashboard.drivers.lg_ess_sunspec import LgEssSunSpecDriver
from solar_dashboard.modbus import ModbusException, RegisterBlock
from tests.test_lg_ess import COMMON, HOME
from tests.test_lg_ess_sunspec import FakeLg


def rows(reading, title_start):
    section = next(s for s in reading.details if s.title.startswith(title_start))
    return dict(section.rows)


def test_lg_sunspec_lists_every_model():
    fake = FakeLg(battery_w=327, cha_st=3, act_power=606, soc_x10=233)
    r = LgEssSunSpecDriver(fake, 1).read()
    titles = [s.title for s in r.details]
    assert titles[0].startswith("Identity") and any("model 802" in t for t in titles) and any("64256" in t for t in titles)
    assert rows(r, "Identity")["Serial number"] == "SN12345"
    battery = rows(r, "Battery")
    assert battery["State of charge"] == "23.3 %" and battery["Charge status"] == "Discharging (3)" and battery["Power"] == "327 W"
    meter = rows(r, "Power meter")
    assert meter["Active power (+ purchase, − feed-in)"] == "6.1 W" and meter["L1 voltage"] == "234.2 V"
    assert meter["Frequency"] == "50.00 Hz" and meter["PV generation (today)"] == "25.9 kWh"
    assert meter["L2 voltage"] == "237.6 V" and meter["L3 voltage"] == "238.5 V"
    # Points the device marks "not implemented" are left out rather than shown as nonsense.
    assert "Transformer temperature" not in rows(r, "Inverter")


class FakeGoodWe:
    """Every register reads as a small counting value; the BMS block raises like old firmware might."""

    def read_holding_registers(self, unit_id, register, count):
        if register == 37000:
            raise ModbusException(2)
        return RegisterBlock(register, b"".join(struct.pack(">H", (register + i) % 200) for i in range(count)))


def test_goodwe_lists_every_block_it_reads():
    r = GoodWeEtDriver(FakeGoodWe(), 247).read()
    titles = [s.title for s in r.details]
    for expected in ("Device info", "Inverter", "PV inputs", "Grid side", "Back-up output", "Battery 1", "Energy", "PV extended"):
        assert any(t.startswith(expected) for t in titles), expected
    assert not any(t.startswith("Battery management") for t in titles)  # BMS block failed → simply absent
    assert len(rows(r, "Grid side")) == 17 and rows(r, "Energy")["PV total"].endswith("kWh")


def test_lg_https_shows_every_field_as_reported():
    sections = lg_ess.details(HOME, COMMON, {"pms": {"model": "D005KD1N111"}})
    by_title = {s.title: dict(s.rows) for s in sections}
    assert by_title["System info · pms"]["Model"] == "D005KD1N111"
    assert by_title["Home · direction"]["Is battery charging"] == "1"
    assert by_title["Details · PV"]["Pv1 voltage"] == "219.800003"


def test_phases_are_named_l1_l2_l3_in_every_driver():
    from solar_dashboard.drivers.huawei_sun2000 import HuaweiSun2000Driver
    from tests.test_huawei import FakeSun2000
    readings = {"GoodWe": GoodWeEtDriver(FakeGoodWe(), 247).read(), "Huawei": HuaweiSun2000Driver(FakeSun2000(), 1).read(),
                "LG SunSpec": LgEssSunSpecDriver(FakeLg(), 1).read()}
    for name, r in readings.items():
        assert {p.name for p in r.phases} <= {"L1", "L2", "L3"}, name
        labels = [label for s in r.details for label, _ in s.rows]
        old = [l for l in labels if any(t in l for t in ("Phase A", "Phase B", "Phase C", "phase A", "R phase",
                                                         "S phase", "T phase", "Voltage AN", "A-B", "B-C", "C-A"))]
        assert not old, (name, old)
        assert any(l.startswith("L1 ") for l in labels), name
