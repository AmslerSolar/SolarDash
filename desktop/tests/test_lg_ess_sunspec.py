"""LG ESS SunSpec driver against a register image laid out like LG's map ver. 1.3, with the scale-factor
convention and values read from a real Home 10 (LG divides by 10^SF)."""
import struct

import pytest

from solar_dashboard.drivers.lg_ess_sunspec import LgEssSunSpecDriver
from solar_dashboard.modbus import ModbusException, RegisterBlock


class FakeLg:
    """Holding registers from 0: 'SunS', then models 1, 103, 113, 120, 123, 802, 64256, end marker."""

    def __init__(self, *, battery_w=848, cha_st=4, act_power=-55000, soc_x10=529, with_113=True):
        self.regs: dict[int, int] = {}
        self.reads = 0
        self._put(0, [0x5375, 0x6E53])
        addr = 2
        lengths = [(1, 66), (103, 50)] + ([(113, 60)] if with_113 else []) + [(120, 26), (123, 24), (802, 62), (64256, 20)]
        self.addr = {}
        for model_id, length in lengths:
            self.addr[model_id] = addr
            self._put(addr, [model_id, length] + [0xFFFF] * length)
            addr += 2 + length
        self._put(addr, [0xFFFF, 0])

        def at(model_id, offset, values):
            self._put(self.addr[model_id] + offset, values)

        at(1, 2, self._text("LG Electronics", 16)); at(1, 18, self._text("D010KE1N211", 16))
        at(1, 42, self._text("10.05.7230", 8)); at(1, 50, self._text("SN12345", 16))
        # 103 scaled, LG convention: W 2140 (W_SF 0), TmpCab 415 with Tmp_SF 1 = 41.5 °C, St 4
        at(103, 14, [2140, 0]); at(103, 33, [415]); at(103, 37, [1]); at(103, 38, [4])
        if with_113:
            at(113, 22, self._f32(2140.0)); at(113, 40, self._f32(41.5)); at(113, 48, [4])
        # 802 as on the real unit: SoC 529 with SoC_SF 1 = 52.9 %, V reads 2, W in watts, offset 63 = 1 (not W's SF)
        at(802, 11, [soc_x10]); at(802, 16, [cha_st]); at(802, 34, [2]); at(802, 47, [battery_w & 0xFFFF])
        at(802, 56, [1]); at(802, 59, [1]); at(802, 63, [1])
        # 64256 as on the real unit: ActPower with SF 2 (÷100), voltage and frequency with SF 1 (÷10)
        at(64256, 2, list(struct.unpack(">HH", struct.pack(">i", act_power))) + [2])
        at(64256, 5, [2342, 2376, 2385, 1, 500, 1])
        at(64256, 11, list(struct.unpack(">4H", struct.pack(">q", 1_234_000))))
        at(64256, 15, list(struct.unpack(">4H", struct.pack(">q", 5_678_000))) + [0])
        at(64256, 20, list(struct.unpack(">HH", struct.pack(">I", 25_938))))  # today's PV, Wh

    def _put(self, addr, values):
        for i, v in enumerate(values):
            self.regs[addr + i] = v

    @staticmethod
    def _text(s, size):
        b = s.encode().ljust(size * 2, b"\0")
        return list(struct.unpack(f">{size}H", b))

    @staticmethod
    def _f32(v):
        return list(struct.unpack(">HH", struct.pack(">f", v)))

    def read_holding_registers(self, unit_id, register, count):
        self.reads += 1
        if register not in self.regs:
            raise ModbusException(2)
        return RegisterBlock(register, b"".join(struct.pack(">H", self.regs.get(register + i, 0)) for i in range(count)))


def test_finds_models_at_the_documented_addresses():
    fake = FakeLg()
    d = LgEssSunSpecDriver(fake, 1)
    models = d.device.discover()
    assert {k: v[0] for k, v in models.items()} == {1: 2, 103: 70, 113: 122, 120: 184, 123: 212, 802: 238, 64256: 302}


def test_identify():
    info = LgEssSunSpecDriver(FakeLg(), 1).identify()
    assert info.model == "LG Electronics D010KE1N211 (firmware 10.05.7230)" and info.serial == "SN12345"


def test_charging_and_exporting():
    r = LgEssSunSpecDriver(FakeLg(), 1).read()
    assert r.battery.power_w == -848 and r.battery.mode == "Charging" and r.battery.soc_percent == 53
    assert r.battery.voltage_v is None
    assert r.grid_power_w == 550  # meter −55000 / 10^2 (feed-in) → export
    assert r.pv_power_w == 2140 + 848  # AC output + charging
    assert r.load_power_w == 2140 - 550
    # Three meter voltages; the meter has only a total power, so no per-phase current or power.
    assert [(p.name, p.voltage_v) for p in r.phases] == [("L1", pytest.approx(234.2)), ("L2", pytest.approx(237.6)),
                                                         ("L3", pytest.approx(238.5))]
    assert all(p.current_a is None and p.power_w is None for p in r.phases)
    assert r.phases[0].frequency_hz == pytest.approx(50.0)
    assert r.today.pv_kwh == pytest.approx(25.938) and r.lifetime_pv_kwh is None
    assert r.temperature_c == pytest.approx(41.5) and r.status == "Producing"


def test_discharging_and_importing_at_night():
    r = LgEssSunSpecDriver(FakeLg(battery_w=-1200, cha_st=3, act_power=30000), 1).read()
    assert r.battery.power_w == 1200  # direction from ChaSt, whatever W's sign
    assert r.grid_power_w == -300
    assert r.pv_power_w == 2140 - 1200  # AC output partly from the battery
    assert r.load_power_w == 2140 + 300


def test_scaled_model_103_when_113_is_missing():
    r = LgEssSunSpecDriver(FakeLg(with_113=False), 1).read()
    assert r.temperature_c == pytest.approx(41.5) and r.pv_power_w == 2140 + 848


def test_discovers_once():
    fake = FakeLg()
    d = LgEssSunSpecDriver(fake, 1)
    d.read()
    before = fake.reads
    d.read()
    assert fake.reads - before == 3  # 113, 802, 64256 blocks only


def test_values_read_from_the_real_unit():
    """Night snapshot from the real Home 10: 317 W AC from the battery (327 W), 6.06 W purchase, SoC 23.3 %."""
    fake = FakeLg(battery_w=327, cha_st=3, act_power=606, soc_x10=233)
    fake._put(fake.addr[113] + 22, FakeLg._f32(317.0))
    r = LgEssSunSpecDriver(fake, 1).read()
    assert r.battery.soc_percent == 23 and r.battery.power_w == 327
    assert r.grid_power_w == -6  # 606 / 10^2 = 6.06 W purchase
    assert r.pv_power_w == 0 and r.load_power_w == 323
