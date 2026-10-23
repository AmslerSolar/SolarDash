"""LG ESS parsing against responses recorded from a real unit (pyess test cassettes, Oct 2019)."""
import pytest

from solar_dashboard.drivers.lg_ess import AUTH_FAILED, LgEssAuthError, LgEssDriver, NotAvailable, parse_reading
from solar_dashboard.drivers.lg_ess import details as _details


def lg_ess_details(_reading):
    return _details(HOME, COMMON, None)
from solar_dashboard.model import DeviceConfig, DeviceType, Plant

HOME = {"statistics": {"pcs_pv_total_power": "1712", "batconv_power": "848", "bat_use": "1", "bat_status": "1",
                       "bat_user_soc": "52.87357", "load_power": "314", "load_today": "0.0", "grid_power": "550",
                       "current_day_self_consumption": "97.5", "current_pv_generation_sum": "4238",
                       "current_grid_feed_in_energy": "104"},
        "direction": {"is_direct_consuming_": "1", "is_battery_charging_": "1", "is_battery_discharging_": "0",
                      "is_grid_selling_": "1", "is_grid_buying_": "0", "is_charging_from_grid_": "0"},
        "operation": {"status": "start", "mode": "1"}, "wintermode": {"winter_status": "off"},
        "pcs_fault": {"pcs_status": "pcs_ok"}}
COMMON = {"PV": {"brand": "LGE-SOLAR", "capacity": "5850", "pv1_voltage": "219.800003", "pv2_voltage": "317.899994",
                 "pv1_power": "129", "pv2_power": "1252", "pv1_current": "0.580000", "pv2_current": "3.930000",
                 "today_pv_generation_sum": "4238", "today_month_pv_generation_sum": "12374"},
          "BATT": {"status": "1", "soc": "52.9", "dc_power": "716"},
          "GRID": {"active_power": "-371.769989", "a_phase": "234.199997", "freq": "50.000000",
                   "today_grid_feed_in_energy": "104", "today_grid_power_purchase_energy": "2140"},
          "LOAD": {"load_power": "231", "today_load_consumption_sum": "13040"},
          "PCS": {"pcs_stauts": "3"}}


def test_live_values_and_signs():
    r = parse_reading(HOME, None)
    assert (r.pv_power_w, r.battery.power_w, r.battery.soc_percent, r.grid_power_w, r.load_power_w) == \
        (1712, -848, 53, 550, 314)
    assert r.battery.mode == "Charging" and r.status == "Running"
    # The snapshot balances: PV = battery charging + home + export
    assert r.pv_power_w + r.battery.power_w - r.grid_power_w == r.load_power_w


def test_discharging_and_importing():
    home = {**HOME, "direction": {**HOME["direction"], "is_battery_charging_": "0", "is_battery_discharging_": "1",
                                  "is_grid_selling_": "0", "is_grid_buying_": "1"}}
    r = parse_reading(home, None)
    assert r.battery.power_w == 848 and r.grid_power_w == -550


def test_details_and_daily_totals_from_common():
    r = parse_reading(HOME, COMMON)
    assert [(s.index, s.power_w) for s in r.pv_strings] == [(1, 129), (2, 1252)]
    # One phase voltage, and only a total power: no per-phase current or power is made up.
    assert [p.name for p in r.phases] == ["L1"] and r.phases[0].voltage_v == pytest.approx(234.2)
    assert r.phases[0].power_w is None and r.phases[0].current_a is None
    assert dict(next(s for s in lg_ess_details(r) if s.title == "Details · GRID").rows)["L1 voltage (a_phase)"] == "234.199997"
    t = r.today
    assert (t.pv_kwh, t.export_kwh, t.import_kwh, t.load_kwh) == pytest.approx((4.238, 0.104, 2.140, 13.040))


def test_fault_and_missing_values():
    r = parse_reading({"statistics": {"bat_use": "0"}, "pcs_fault": {"pcs_status": "pcs_fault_3"}}, None)
    assert r.battery is None and r.pv_power_w is None and r.grid_power_w is None and r.status.startswith("Fault")


class FakeDevice(LgEssDriver):
    """Answers like the real Home 10: login on /v1/user/setting/login only, 405 for a stale key, 404 for
    endpoints the firmware lacks. `expire_with` picks the expiry answer (real unit or LG's document)."""

    def __init__(self, password="001122aabbcc", expire_with=(405, AUTH_FAILED[0])):
        super().__init__("192.0.2.1", 443, password)
        self.calls, self.logins, self.expire_next, self.expire_with = [], 0, False, expire_with

    def _request(self, method, path, body):
        self.calls.append((method, path))
        if path == "/v1/login":
            return 200, {"status": "password_mismatched"}
        if path == "/v1/user/setting/login":
            if body["password"] != "001122aabbcc":
                return 200, {"status": "password_mismatched"}
            self.logins += 1
            return 200, {"status": "success", "auth_key": f"key{self.logins}", "role": "user"}
        responses = {"/v1/user/essinfo/home": HOME, "/v1/user/essinfo/common": COMMON,
                     "/v1/user/setting/systeminfo": {"pms": {"model": "D005KD1N111", "serialno": "S123"},
                                                     "version": {"pms_version": "10.05.6053"}},
                     "/v1/user/operation/faultlist": {"db": "no data", "pv_voltage1": "76.7"},
                     "/v1/user/setting/batt": {"winter_setting": "off", "backup_soc": "30"}}
        if path not in responses:
            raise NotAvailable(path)  # e.g. /v1/logout, /v1/user/setting/time on this fake
        if self.expire_next or body["auth_key"] != f"key{self.logins}":
            self.expire_next = False
            return self.expire_with
        return 200, responses[path]


def test_logs_in_once_and_again_after_expiry():
    d = FakeDevice()
    assert d.read().pv_power_w == 1712 and d.logins == 1
    d.read()
    assert d.logins == 1
    d.expire_next = True
    assert d.read().pv_power_w == 1712 and d.logins == 2
    assert d.identify().serial == "S123"


def test_wrong_password_is_reported():
    with pytest.raises(LgEssAuthError, match="MAC address"):
        FakeDevice("nope").read()


def test_documented_expiry_answer_also_triggers_relogin():
    d = FakeDevice(expire_with=(200, {"status": "fail"}))
    d.read()
    d.expire_next = True
    assert d.read().pv_power_w == 1712 and d.logins == 2


def test_only_reads_and_skips_missing_endpoints():
    d = FakeDevice()
    r = d.read()
    d.read()
    assert all(m == "POST" or p.endswith("/login") for m, p in d.calls)  # no writes besides logging in
    assert not any("timesync" in p for _, p in d.calls)
    titles = [s.title for s in r.details]
    assert "Faults" in titles and "Battery settings" in titles and "Clock" not in titles  # 404 → left out
    assert sum(p == "/v1/user/setting/time" for _, p in d.calls) == 1  # and not asked again


def test_logs_out_when_closed():
    d = FakeDevice()
    d.read()
    d.close()  # /v1/logout is 404 on the real unit: no error
    assert d.calls[-1] == ("POST", "/v1/logout") and d._auth_key is None


def test_password_round_trips_but_is_left_out_when_empty():
    dev = DeviceConfig("d", "LG", DeviceType.LG_ESS, "10.0.0.9", 443, 1, "pw")
    assert Plant.from_json(Plant("p", "P", (dev,)).to_json()).devices[0].password == "pw"
    assert "password" not in DeviceConfig("g", "G", DeviceType.GOODWE_ET).to_json()
    assert "pw" not in repr(dev)
