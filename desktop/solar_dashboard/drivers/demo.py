"""Fake live data for exercising the UI across its whole range.

Every 5 s all demo devices switch to a new random scenario in which each power is between 1 W and 99 kW in
magnitude (log-uniform, so watts and tens of kilowatts are equally common). The scenario is shared, so plant
totals stay consistent: solar, battery, grid and the derived home consumption stay in range for any mix of
demo devices.
"""
from __future__ import annotations

import math
import random
import time
from dataclasses import dataclass

from ..model import BatteryState, DeviceInfo, DeviceReading, EnergyTotals, PhaseReading, PvString

MIN_W, MAX_W, PERIOD_S = 1, 99_000, 5


@dataclass(frozen=True)
class Scenario:
    pv: int
    inverter_battery: int
    ac_battery: int
    grid: int


def scenario_at(bucket: int) -> Scenario:
    rng = random.Random(bucket)

    def magnitude():
        return min(MAX_W, max(MIN_W, round(math.exp(rng.random() * math.log(MAX_W)))))

    def signed():
        return magnitude() * rng.choice((1, -1))

    for _ in range(10_000):
        s = Scenario(magnitude(), signed(), signed(), signed())
        home_without = s.pv + s.inverter_battery - s.grid  # plant without the AC battery
        home_with = home_without + s.ac_battery
        if MIN_W <= home_without <= MAX_W and MIN_W <= home_with <= MAX_W \
                and MIN_W <= abs(s.inverter_battery + s.ac_battery) <= MAX_W:
            return s
    return Scenario(5_000, -1_000, 500, 2_000)


def scenario_now() -> Scenario:
    return scenario_at(int(time.time()) // PERIOD_S)


def battery_mode(power_w: int) -> str:
    return "Charging" if power_w < 0 else "Discharging" if power_w > 0 else "Standby"


class DemoInverter:
    """A 29.9 kW hybrid inverter with its own battery."""

    def __init__(self):
        self._soc = 62.0

    def identify(self) -> DeviceInfo:
        return DeviceInfo("GW29.9K-ET (demo)", "DEMO-INV-0001")

    def read(self) -> DeviceReading:
        s = scenario_now()
        strings = []
        for i, share in enumerate((0.4, 0.35, 0.25)):
            p = round(s.pv * share)
            v = 580 + random.random() * 40
            strings.append(PvString(i + 1, v, p / v, p))
        self._soc = min(100.0, max(0.0, self._soc - s.inverter_battery / 20_000))
        output = s.pv + s.inverter_battery
        return DeviceReading(
            pv_power_w=s.pv,
            pv_strings=tuple(strings),
            battery=BatteryState(s.inverter_battery, round(self._soc), 480.0, battery_mode(s.inverter_battery)),
            grid_power_w=s.grid,
            phases=tuple(PhaseReading(n, 229 + random.random() * 4, output / 3 / 230, 50.0, output // 3)
                         for n in ("L1", "L2", "L3")),
            load_power_w=output - s.grid,
            temperature_c=32 + random.random() * 15,
            status="On-grid",
            today=EnergyTotals(pv_kwh=84.3, export_kwh=41.7, import_kwh=3.2, load_kwh=29.8),
            lifetime_pv_kwh=61_420.5,
        )


class DemoBattery:
    """A standalone (AC-coupled) battery."""

    def __init__(self):
        self._soc = 45.0

    def identify(self) -> DeviceInfo:
        return DeviceInfo("AC battery 15 kWh (demo)", "DEMO-BAT-0001")

    def read(self) -> DeviceReading:
        p = scenario_now().ac_battery
        self._soc = min(100.0, max(5.0, self._soc - p / 30_000))
        return DeviceReading(battery=BatteryState(p, round(self._soc), 51.2, battery_mode(p)),
                             temperature_c=24.0, status="Online")


class DemoMeter:
    """A three-phase meter at the grid connection point."""

    def identify(self) -> DeviceInfo:
        return DeviceInfo("3-phase meter (demo)", "DEMO-MTR-0001")

    def read(self) -> DeviceReading:
        g = scenario_now().grid
        return DeviceReading(
            grid_power_w=g,
            phases=tuple(PhaseReading(n, 230 + random.random() * 3, g / 3 / 230, 50.0, g // 3) for n in ("L1", "L2", "L3")),
            status="Online",
            today=EnergyTotals(export_kwh=44.1, import_kwh=5.6),
        )
