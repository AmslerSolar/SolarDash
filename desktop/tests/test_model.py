from solar_dashboard.model import (BatteryState, DeviceConfig, DeviceReading, DeviceStatus, DeviceType, EnergyTotals,
                                   GridSource, aggregate)


def device(i, t):
    return DeviceConfig(i, i, t)


inv1, inv2 = device("inv1", DeviceType.GOODWE_ET), device("inv2", DeviceType.GOODWE_ET)
acb, meter = device("bat", DeviceType.DEMO_BATTERY), device("meter", DeviceType.DEMO_METER)


def ok(d, r):
    return DeviceStatus(d, r, None)


def bat(p, soc):
    return BatteryState(p, soc, None, "")


def test_sums_inverters_and_uses_their_grid_without_meter():
    s = aggregate([ok(inv1, DeviceReading(pv_power_w=10_000, grid_power_w=6_000, battery=bat(-2_000, 50))),
                   ok(inv2, DeviceReading(pv_power_w=5_000, grid_power_w=4_000))])
    assert (s.pv_power_w, s.grid_source, s.grid_power_w, s.battery.power_w, s.load_power_w) == \
        (15_000, GridSource.INVERTERS, 10_000, -2_000, 3_000)


def test_meter_replaces_inverter_grid_values():
    s = aggregate([ok(inv1, DeviceReading(pv_power_w=10_000, grid_power_w=6_000)),
                   ok(acb, DeviceReading(battery=bat(1_000, 80))),
                   ok(meter, DeviceReading(grid_power_w=-500, today=EnergyTotals(import_kwh=2.0)))])
    assert (s.grid_source, s.grid_power_w, s.load_power_w, s.today.import_kwh) == (GridSource.METERS, -500, 11_500, 2.0)


def test_failed_meter_makes_grid_unknown_instead_of_falling_back():
    s = aggregate([ok(inv1, DeviceReading(pv_power_w=4_000, grid_power_w=1_000, load_power_w=3_000)),
                   DeviceStatus(meter, None, "timeout")])
    assert s.grid_power_w is None and s.load_power_w == 3_000 and len(s.failed_devices) == 1


def test_averages_state_of_charge():
    s = aggregate([ok(inv1, DeviceReading(battery=bat(500, 40))), ok(acb, DeviceReading(battery=bat(-200, 81)))])
    assert s.battery.power_w == 300 and s.battery.soc_percent in (60, 61)


def test_plant_json_round_trip():
    from solar_dashboard.model import Plant
    p = Plant("p", "Roof", (DeviceConfig("d", "Inv", DeviceType.GOODWE_ET, "10.0.0.5", 502, 247),))
    assert Plant.from_json(p.to_json()) == p
