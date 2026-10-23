"""LG ESS Home 8 / Home 10 over SunSpec Modbus TCP (port 6502).

Register map: "Home 8/10 LG ESS SunSpec ver. 1.3" (docs/specs/lg ess/), firmware 10.05.7213 and later.
Models: 1 Common, 103 inverter (scaled) / 113 (float), 802 battery, 64256 LG power meter.

- Grid: model 64256 ActPower, >0 purchase / <0 feed-in (documented) → our sign is the opposite.
- Battery: model 802 W, direction from ChaSt (3 discharging, 4 charging), so its sign doesn't matter.
- Solar: not a register. Derived from the DC-bus balance, AC output − battery (+ = discharging); it ignores
  conversion losses, so it reads a few percent low, but home consumption (AC output + purchase) stays exact.
- Daily energy isn't in the map, only lifetime counters; the history adds up days from the samples.

Checked against a real Home 10 (firmware with map 1.3):
- LG's scale factors are the inverse of the SunSpec standard: value ÷ 10^SF, not × 10^SF. Voltage 2384 with
  SF 1 is 238.4 V (model 113 reports 238.4 as a float), SoC 233 / SF 1 = 23.3 %, meter power 606 / SF 2 = 6.06 W.
- Battery W (802 offset 47) has no scale factor and is already in watts (327 W discharging next to 317 W AC).
- PV generation energy (64256 offset 20) is *today's* PV yield in Wh: it equals the HTTPS API's
  today_pv_generation_sum (25938 Wh on the same day). Purchase and feed-in energy are lifetime counters.
- Battery voltage and currents (802 offsets 34, 44–46) aren't real readings (2 → 0.2 V, 0 A while discharging),
  so they're left out of the battery figures and marked "as reported" in the detail view.
- Inverter lifetime energy (113 offset 32 / 103 offset 24) is in kWh, like the PV counter.
- Power factor ratings (120) come out as percentages (900, SF 1 → 90.0 %).
- Model 120's "rated apparent power" holds the live apparent power (same as model 113 VA).
"""
from __future__ import annotations

from dataclasses import replace

from ..model import BatteryState, DetailSection, DeviceInfo, DeviceReading, EnergyTotals, PhaseReading
from ..modbus import ModbusTcpClient
from .details import bits, enum, fmt, section
from .sunspec import Model, SunSpecDevice

INVERTER_STATES = {1: "Off", 2: "Sleeping", 3: "Starting", 4: "Producing", 5: "Throttled",
                   6: "Shutting down", 7: "Fault", 8: "Standby"}
CHARGE_STATES = {1: "Off", 2: "Empty", 3: "Discharging", 4: "Charging", 5: "Full", 6: "Holding", 7: "Testing"}
DER_TYPES = {4: "PV", 82: "PV with storage"}
CONNECTED = {0: "Disconnected", 1: "Connected"}
ENABLED = {0: "Disabled", 1: "Enabled"}
VAR_MODES = {0: "None", 1: "% of max W", 2: "% of max VAr", 3: "% of available VAr"}
CONTROL_MODES = {0: "Remote", 1: "Local"}
BATTERY_TYPES = {0: "Not applicable", 1: "Lead-acid", 2: "NiMH", 3: "NiCd", 4: "Li-ion", 5: "Carbon-zinc",
                 6: "Zinc chloride", 7: "Alkaline", 8: "Rechargeable alkaline", 9: "Sodium-sulfur", 10: "Flow",
                 99: "Other"}
BANK_STATES = {1: "Disconnected", 2: "Initializing", 3: "Connected", 4: "Standby", 5: "SoC protection",
               6: "Suspending", 99: "Fault"}
STATIC_REFRESH_POLLS = 12  # identity, nameplate and controls: re-read about once a minute


def _lg(m: Model, value: int | None, sf_offset: int) -> float | None:
    """LG scales the other way round from SunSpec: value ÷ 10^SF. A missing SF counts as 0."""
    if value is None:
        return None
    sf = m.s16(sf_offset) or 0
    return value / 10 ** sf


def _inverter(m103: Model | None, m113: Model | None):
    """(AC W, temperature °C, state) from the float model when present, else the scaled one."""
    if m113 is not None:
        return m113.f32(22), m113.f32(40), m113.u16(48)
    if m103 is not None:
        return _lg(m103, m103.s16(14), 15), _lg(m103, m103.s16(33), 37), m103.u16(38)
    return None, None, None


def parse(m103: Model | None, m113: Model | None, m802: Model | None, meter: Model | None) -> DeviceReading:
    ac_w, temp, state = _inverter(m103, m113)

    battery = None
    if m802 is not None:
        w = m802.s16(47)  # watts, no scale factor
        cha = m802.u16(16)
        mag = abs(w) if w is not None else 0
        power = mag if cha == 3 else -mag if cha == 4 else 0
        soc = _lg(m802, m802.u16(11), 56)
        battery = BatteryState(power, None if soc is None else round(soc), None, CHARGE_STATES.get(cha, "Standby"))

    grid = phases = None
    pv_today = None
    if meter is not None:
        act = _lg(meter, meter.s32(2), 4)
        grid = None if act is None else -round(act)  # meter: >0 purchase; ours: >0 export
        hz = _lg(meter, meter.u16(9), 10) or 0.0
        # The meter model has a voltage per phase but only a total power: no per-phase current or power.
        phases = tuple(PhaseReading(name, v, None, hz, None)
                       for name, offset in (("L1", 5), ("L2", 6), ("L3", 7))
                       if (v := _lg(meter, meter.u16(offset), 8)))
        pv_wh = _lg(meter, meter.u32(20), 19)  # today's PV yield in Wh; added in map 1.3
        pv_today = None if pv_wh is None else pv_wh / 1000

    pv = load = None
    if ac_w is not None:
        pv = max(0, round(ac_w - (battery.power_w if battery else 0)))
        if grid is not None:
            load = max(0, round(ac_w - grid))

    return DeviceReading(
        pv_power_w=pv,
        battery=battery,
        grid_power_w=grid,
        phases=phases or (),
        load_power_w=load,
        temperature_c=temp,
        status=INVERTER_STATES.get(state, f"State {state}" if state is not None else None),
        today=EnergyTotals(pv_kwh=pv_today),
    )


class LgEssSunSpecDriver:
    def __init__(self, client: ModbusTcpClient, unit_id: int):
        self.device = SunSpecDevice(client, unit_id)
        self._static: tuple[DetailSection, ...] = ()
        self._polls = 0

    def identify(self) -> DeviceInfo:
        common = self.device.model(1)
        if common is None:
            raise IOError("SunSpec Common model (1) missing")
        model = f"{common.text(2, 16)} {common.text(18, 16)}".strip()
        version = common.text(42, 8)
        return DeviceInfo(model=model + (f" (firmware {version})" if version else ""), serial=common.text(50, 16))

    def read(self) -> DeviceReading:
        d = self.device
        m113 = d.model(113)
        m103 = None if m113 else d.model(103)
        m802, meter = d.model(802), d.model(64256)
        reading = parse(m103, m113, m802, meter)

        if self._polls % STATIC_REFRESH_POLLS == 0:
            static = []
            for model_id, build in ((1, common_details), (120, nameplate_details), (123, controls_details)):
                try:
                    if (m := d.model(model_id)) is not None:
                        static.append(build(m))
                except OSError:
                    pass  # extras: keep the live values
            self._static = tuple(static)
        self._polls += 1

        live = [s for s in (inverter_details(m103, m113), m802 and battery_details(m802), meter and meter_details(meter)) if s]
        # Identity first, then the live models, then nameplate and controls.
        identity = [x for x in self._static if x.title.startswith("Identity")]
        rest = [x for x in self._static if not x.title.startswith("Identity")]
        return replace(reading, details=tuple(identity + live + rest))


# --- detail view: every point of LG's map ------------------------------------------------------------------------
def _f(m: Model, o: int) -> float | None:
    return m.f32(o)


def common_details(m: Model) -> DetailSection:
    return section("Identity (SunSpec model 1)",
                   ("Manufacturer", m.text(2, 16)), ("Model", m.text(18, 16)),
                   ("Version", m.text(42, 8)), ("Serial number", m.text(50, 16)))


def inverter_details(m103: Model | None, m113: Model | None) -> DetailSection | None:
    if m113 is not None:
        m, t = m113, "Inverter (SunSpec model 113)"
        return section(t,
                       ("Current", fmt(_f(m, 2), "A")), ("L1 current", fmt(_f(m, 4), "A")),
                       ("L2 current", fmt(_f(m, 6), "A")), ("L3 current", fmt(_f(m, 8), "A")),
                       ("L1 voltage", fmt(_f(m, 16), "V", 1)), ("L2 voltage", fmt(_f(m, 18), "V", 1)),
                       ("L3 voltage", fmt(_f(m, 20), "V", 1)), ("AC power", fmt(_f(m, 22), "W")),
                       ("Frequency", fmt(_f(m, 24), "Hz", 2)), ("Apparent power", fmt(_f(m, 26), "VA")),
                       ("Energy (lifetime)", fmt(_f(m, 32), "kWh", 0)),  # kWh on the real unit, not Wh
                       ("DC current", fmt(_f(m, 34), "A")), ("DC voltage", fmt(_f(m, 36), "V", 1)),
                       ("DC power", fmt(_f(m, 38), "W")), ("Cabinet temperature", fmt(_f(m, 40), "°C", 1)),
                       ("Transformer temperature", fmt(_f(m, 44), "°C", 1)),
                       ("Operating state", enum(m.u16(48), INVERTER_STATES)), ("Events", bits(m.u32(50))))
    if m103 is not None:
        m = m103
        kwh = _lg(m, m.u32(24), 26)  # kWh on the real unit (model 113 shows the same), not Wh
        return section("Inverter (SunSpec model 103)",
                       ("Current", fmt(_lg(m, m.u16(2), 6), "A")), ("L1 current", fmt(_lg(m, m.u16(3), 6), "A")),
                       ("L2 current", fmt(_lg(m, m.u16(4), 6), "A")), ("L3 current", fmt(_lg(m, m.u16(5), 6), "A")),
                       ("L1 voltage", fmt(_lg(m, m.u16(10), 13), "V", 1)),
                       ("L2 voltage", fmt(_lg(m, m.u16(11), 13), "V", 1)),
                       ("L3 voltage", fmt(_lg(m, m.u16(12), 13), "V", 1)), ("AC power", fmt(_lg(m, m.s16(14), 15), "W")),
                       ("Frequency", fmt(_lg(m, m.u16(16), 17), "Hz", 2)),
                       ("Apparent power", fmt(_lg(m, m.s16(18), 19), "VA")),
                       ("Energy (lifetime)", fmt(kwh, "kWh", 0)),
                       ("DC current", fmt(_lg(m, m.u16(27), 28), "A")), ("DC voltage", fmt(_lg(m, m.u16(29), 30), "V", 1)),
                       ("DC power", fmt(_lg(m, m.s16(31), 32), "W")),
                       ("Cabinet temperature", fmt(_lg(m, m.s16(33), 37), "°C", 1)),
                       ("Transformer temperature", fmt(_lg(m, m.s16(35), 37), "°C", 1)),
                       ("Operating state", enum(m.u16(38), INVERTER_STATES)), ("Events", bits(m.u32(40))))
    return None


def nameplate_details(m: Model) -> DetailSection:
    return section("Nameplate ratings (SunSpec model 120)",
                   ("Type", enum(m.u16(2), DER_TYPES)), ("Rated power", fmt(_lg(m, m.u16(3), 4), "W")),
                   ("Rated apparent power", fmt(_lg(m, m.u16(5), 6), "VA")),
                   *((f"Rated reactive power Q{i}", fmt(_lg(m, m.s16(6 + i), 11), "var")) for i in range(1, 5)),
                   ("Rated current", fmt(_lg(m, m.u16(12), 13), "A")),
                   *((f"Min. power factor Q{i}", fmt(_lg(m, m.s16(13 + i), 18), "%")) for i in range(1, 5)),
                   ("Rated storage energy", fmt(_lg(m, m.u16(19), 20), "Wh")),
                   ("Max. charge rate", fmt(_lg(m, m.u16(23), 24), "W")),
                   ("Max. discharge rate", fmt(_lg(m, m.u16(25), 26), "W")))


def controls_details(m: Model) -> DetailSection:
    return section("Inverter controls (SunSpec model 123)",
                   ("Grid connection", enum(m.u16(4), CONNECTED)),
                   ("Power limit", fmt(_lg(m, m.u16(5), 23), "% of max")),
                   ("Power limit active", enum(m.u16(9), ENABLED)),
                   ("Power factor setpoint", fmt(_lg(m, m.s16(10), 24))),
                   ("Power factor setpoint active", enum(m.u16(14), ENABLED)),
                   ("Reactive power mode", enum(m.u16(21), VAR_MODES)),
                   ("Reactive power control active", enum(m.u16(22), ENABLED)))


def battery_details(m: Model) -> DetailSection:
    return section("Battery (SunSpec model 802)",
                   ("Capacity", fmt(_lg(m, m.u16(2), 52), "Ah")), ("Energy capacity", fmt(_lg(m, m.u16(3), 53), "Wh")),
                   ("Max. charge rate", fmt(_lg(m, m.u16(4), 54), "W")),
                   ("Max. discharge rate", fmt(_lg(m, m.u16(5), 54), "W")),
                   ("State of charge", fmt(_lg(m, m.u16(11), 56), "%", 1)),
                   ("Depth of discharge", fmt(_lg(m, m.u16(12), 57), "%", 1)),
                   ("Charge status", enum(m.u16(16), CHARGE_STATES)), ("Control mode", enum(m.u16(17), CONTROL_MODES)),
                   ("Battery type", enum(m.u16(21), BATTERY_TYPES)), ("Bank state", enum(m.u16(22), BANK_STATES)),
                   ("Events 1", bits(m.u32(26))), ("Events 2", bits(m.u32(28))),
                   ("Vendor events 1", bits(m.u32(30))), ("Vendor events 2", bits(m.u32(32))),
                   ("Voltage (as reported)", fmt(_lg(m, m.u16(34), 59), "V")),
                   # Not real readings on the unit (0 A while discharging, 1.2 A max), so labelled as such.
                   ("Current (as reported)", fmt(_lg(m, m.s16(44), 61), "A")),
                   ("Max. charge current (as reported)", fmt(_lg(m, m.u16(45), 62), "A")),
                   ("Max. discharge current (as reported)", fmt(_lg(m, m.u16(46), 62), "A")),
                   ("Power", fmt(m.s16(47), "W")))


def meter_details(m: Model) -> DetailSection:
    def kwh(o):
        wh = _lg(m, m.s64(o), 19)
        return None if wh is None else wh / 1000

    act = _lg(m, m.s32(2), 4)
    return section("Power meter (LG model 64256)",
                   ("Active power (+ purchase, − feed-in)", fmt(act, "W", 1)),
                   ("L1 voltage", fmt(_lg(m, m.u16(5), 8), "V", 1)), ("L2 voltage", fmt(_lg(m, m.u16(6), 8), "V", 1)),
                   ("L3 voltage", fmt(_lg(m, m.u16(7), 8), "V", 1)), ("Frequency", fmt(_lg(m, m.u16(9), 10), "Hz", 2)),
                   ("Purchased energy (lifetime)", fmt(kwh(11), "kWh", 1)),
                   ("Fed-in energy (lifetime)", fmt(kwh(15), "kWh", 1)),
                   ("PV generation (today)", fmt(None if (pv := _lg(m, m.u32(20), 19)) is None else pv / 1000, "kWh", 1)))
