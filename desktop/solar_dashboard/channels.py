"""Every numeric value a device reports, as named channels for recording and charting.

Keys are stable across polls: "r:…" for the normalised reading, "d:<section>:<label>" for detail-view rows
(only rows that start with a number; enums, versions, dates and bit fields are skipped).
"""
from __future__ import annotations

import re
from dataclasses import dataclass

from .model import DeviceReading


@dataclass(frozen=True)
class Channel:
    key: str
    label: str
    unit: str
    value: float


_NUMBER = re.compile(r"^([-+]?\d+(?:\.\d+)?)(?:\s+(\D.*))?$")


def parse_number(text: str) -> tuple[float, str] | None:
    """'238.4 V' → (238.4, 'V'); '219.800003' → (219.8, ''); 'Discharging (3)', '10.05.7437', '0x0000' → None."""
    m = _NUMBER.match(text.strip())
    if not m:
        return None
    return float(m.group(1)), (m.group(2) or "").strip()


def extract(reading: DeviceReading) -> list[Channel]:
    out: list[Channel] = []

    def add(key, label, unit, value):
        if value is not None:
            out.append(Channel(key, label, unit, float(value)))

    r = reading
    add("r:pv_power", "Solar power", "W", r.pv_power_w)
    add("r:grid_power", "Grid power (+ export)", "W", r.grid_power_w)
    add("r:load_power", "Load power", "W", r.load_power_w)
    add("r:temperature", "Temperature", "°C", r.temperature_c)
    if r.battery:
        add("r:battery_power", "Battery power (+ discharge)", "W", r.battery.power_w)
        add("r:battery_soc", "Battery state of charge", "%", r.battery.soc_percent)
        add("r:battery_voltage", "Battery voltage", "V", r.battery.voltage_v)
    for s in r.pv_strings:
        add(f"r:pv{s.index}_voltage", f"PV{s.index} voltage", "V", s.voltage_v)
        add(f"r:pv{s.index}_current", f"PV{s.index} current", "A", s.current_a)
        add(f"r:pv{s.index}_power", f"PV{s.index} power", "W", s.power_w)
    for p in r.phases:
        add(f"r:{p.name}_voltage", f"{p.name} voltage", "V", p.voltage_v)
        add(f"r:{p.name}_current", f"{p.name} current", "A", p.current_a)
        add(f"r:{p.name}_power", f"{p.name} power", "W", p.power_w)
        add(f"r:{p.name}_frequency", f"{p.name} frequency", "Hz", p.frequency_hz)
    t = r.today
    add("r:today_pv", "Solar yield today", "kWh", t.pv_kwh)
    add("r:today_export", "Exported today", "kWh", t.export_kwh)
    add("r:today_import", "Imported today", "kWh", t.import_kwh)
    add("r:today_load", "Consumption today", "kWh", t.load_kwh)
    add("r:lifetime_pv", "Lifetime yield", "kWh", r.lifetime_pv_kwh)

    for section in r.details:
        for label, text in section.rows:
            if (parsed := parse_number(text)) is not None:
                value, unit = parsed
                out.append(Channel(f"d:{section.title}:{label}", f"{label} ({section.title})", unit, value))
    return out


# Display units: power on one kW axis, energy in kWh, so mixed W / kW channels share a scale.
_CONVERT = {"W": ("kW", 1 / 1000), "Wh": ("kWh", 1 / 1000), "VA": ("kVA", 1 / 1000), "var": ("kvar", 1 / 1000),
            "Var": ("kvar", 1 / 1000), "mV": ("V", 1 / 1000), "MWh": ("kWh", 1000)}


def display_unit(unit: str) -> tuple[str, float]:
    """(unit shown on the axis, factor to multiply values by)."""
    return _CONVERT.get(unit, (unit or "value", 1.0))
