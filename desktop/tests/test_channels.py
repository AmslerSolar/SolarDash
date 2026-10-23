"""Device channels: extraction from readings, per-minute recording, and reading them back."""
import pytest

from solar_dashboard.channels import display_unit, extract, parse_number
from solar_dashboard.model import (BatteryState, DetailSection, DeviceConfig, DeviceReading, DeviceStatus, DeviceType,
                                   PhaseReading, Plant, aggregate)
from solar_dashboard.storage import DEVICE_SAMPLE_INTERVAL_S, HistoryStore


def test_parse_number_keeps_only_real_numbers():
    assert parse_number("238.4 V") == (238.4, "V")
    assert parse_number("23.3 %") == (23.3, "%")
    assert parse_number("219.800003") == (pytest.approx(219.8), "")
    assert parse_number("100 % of max") == (100.0, "% of max")
    for text in ("Discharging (3)", "10.05.7437", "0x0000", "2026-10-20 22:55:00", "On-grid", ""):
        assert parse_number(text) is None, text


def reading(t=0.0):
    return DeviceReading(pv_power_w=1200, grid_power_w=-300, battery=BatteryState(400, 55, None, "Discharging"),
                         phases=(PhaseReading("L1", 230.0 + t, 5.0, 50.0, -300),), temperature_c=40.5,
                         details=(DetailSection("Battery (SunSpec model 802)", (("State of charge", "55.0 %"),
                                                                                ("Charge status", "Discharging (3)"))),))


def test_extract_covers_reading_and_numeric_detail_rows():
    keys = {c.key: c for c in extract(reading())}
    assert keys["r:pv_power"].value == 1200 and keys["r:battery_soc"].unit == "%"
    assert keys["r:L1_voltage"].value == 230.0
    assert keys["d:Battery (SunSpec model 802):State of charge"].value == 55.0
    assert not any("Charge status" in k for k in keys)  # enum text isn't a channel


def test_display_units_put_power_on_one_axis():
    assert display_unit("W") == ("kW", 0.001) and display_unit("Wh") == ("kWh", 0.001)
    assert display_unit("V") == ("V", 1.0) and display_unit("")[0] == "value"


def test_records_every_minute_and_reads_back(tmp_path):
    store = HistoryStore(tmp_path / "h.db")
    dev = DeviceConfig("d1", "Inverter", DeviceType.DEMO_INVERTER)
    t0 = 1_760_000_000
    for minute in range(5):
        snap = aggregate([DeviceStatus(dev, reading(minute), None)], read_at=t0 + minute * 60)
        store.record("p1", snap)
    store.record("p1", aggregate([DeviceStatus(dev, reading(9), None)], read_at=t0 + 4 * 60 + 20))  # too soon: skipped
    assert DEVICE_SAMPLE_INTERVAL_S == 60
    series = store.channel_series("d1", ["r:L1_voltage", "r:battery_soc"], t0, t0 + 3600)
    assert [v for _, v in series["r:L1_voltage"]] == [230.0, 231.0, 232.0, 233.0, 234.0]
    assert len(series["r:battery_soc"]) == 5
    catalog = {k: (label, unit) for k, label, unit in store.channel_catalog("d1")}
    assert catalog["r:L1_voltage"] == ("L1 voltage", "V")
    store.delete_devices(["d1"])
    assert store.channel_catalog("d1") == []


def test_chart_channels_round_trip_in_plant_json():
    p = Plant("p", "P", (), (("d1", "r:L1_voltage", 0), ("d1", "d:Battery:State of charge", 2)))
    assert Plant.from_json(p.to_json()) == p


def test_chart_names_are_short_with_the_full_name_kept():
    from solar_dashboard.ui.history_tab import channel_name, channel_section, shorten
    assert shorten("State of charge") == "State of charge"  # exactly 15: unchanged
    assert shorten("Max. discharge current (as reported)") == "Max. discharge …"
    key = "d:Battery (SunSpec model 802):Max. charge current (as reported)"
    assert channel_name(key, "ignored") == "Max. charge current (as reported)"
    assert channel_section(key) == "Battery (SunSpec model 802)" and channel_section("r:pv_power") == "Live values"
    assert channel_name("r:L1_voltage", "L1 voltage") == "L1 voltage"
