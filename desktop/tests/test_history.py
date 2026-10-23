from datetime import date, time

import pytest

from solar_dashboard import history as hm
from solar_dashboard.drivers.demo import MAX_W, MIN_W, scenario_at
from solar_dashboard.history import HistoryPeriod, HistoryRange, Sample
from solar_dashboard.model import EnergyTotals


def s(t, pv=None, load=None, grid=None):
    return Sample(t, pv, load, None, grid, None)


def test_integrates_with_trapezoid_rule():
    assert hm.integrate(s(0, pv=2000), s(900, pv=4000)).pv_kwh == pytest.approx(0.75)
    e = hm.integrate(s(0, pv=2000, load=1000), s(1200, pv=4000, load=1000))
    assert e.pv_kwh == pytest.approx(1.0) and e.load_kwh == pytest.approx(1 / 3)


def test_splits_grid_into_export_and_import():
    assert hm.integrate(s(0, grid=3000), s(600, grid=3000)).export_kwh == pytest.approx(0.5)
    assert hm.integrate(s(0, grid=-1200), s(600, grid=-1200)).import_kwh == pytest.approx(0.2)


def test_does_not_integrate_across_gaps_or_missing_values():
    assert hm.integrate(s(0, pv=1000), s(hm.MAX_GAP_SECONDS + 1, pv=1000)).pv_kwh is None
    assert hm.integrate(s(0), s(60, pv=1000)).pv_kwh is None
    assert hm.integrate(None, s(60, pv=1000)).pv_kwh is None


def test_counter_wins_and_never_decreases():
    day = hm.merge(None, EnergyTotals(pv_kwh=10.0), EnergyTotals(pv_kwh=0.2, load_kwh=0.1))
    assert day.pv_kwh == 10.0 and day.load_kwh == pytest.approx(0.1)
    assert hm.merge(day, EnergyTotals(pv_kwh=6.0), EnergyTotals()).pv_kwh == 10.0
    assert hm.merge(day, None, EnergyTotals(pv_kwh=0.5)).pv_kwh == pytest.approx(10.5)


def test_ignores_counters_just_after_midnight():
    assert not hm.counters_usable(time(0, 5)) and hm.counters_usable(time(0, 15))


def test_self_sufficiency_and_consumption():
    e = EnergyTotals(pv_kwh=40, export_kwh=30, import_kwh=5, load_kwh=20)
    assert hm.self_sufficiency(e) == pytest.approx(0.75) and hm.self_consumption(e) == pytest.approx(0.25)


def test_week_starts_on_monday_and_navigates():
    r = HistoryRange(HistoryPeriod.WEEK, date(2026, 10, 22))
    assert (r.start, r.end, r.shift(-1).start) == (date(2026, 10, 19), date(2026, 10, 25), date(2026, 10, 12))
    assert not r.has_next(date(2026, 10, 22)) and r.shift(-1).has_next(date(2026, 10, 22))


def test_month_and_year_bounds():
    assert HistoryRange(HistoryPeriod.MONTH, date(2028, 2, 14)).end == date(2028, 2, 29)
    assert HistoryRange(HistoryPeriod.MONTH, date(2026, 1, 31)).shift(1).anchor == date(2026, 2, 28)
    y = HistoryRange(HistoryPeriod.YEAR, date(2026, 10, 20))
    assert (y.start, y.end) == (date(2026, 1, 1), date(2026, 12, 31))
    assert HistoryRange(HistoryPeriod.LIFETIME, date(2026, 10, 20)).start is None


def test_demo_scenario_stays_in_range():
    for bucket in range(2000):
        sc = scenario_at(bucket)
        for v in (sc.pv, sc.pv + sc.inverter_battery - sc.grid, sc.pv + sc.inverter_battery + sc.ac_battery - sc.grid):
            assert MIN_W <= v <= MAX_W
        for v in (sc.inverter_battery, sc.ac_battery, sc.grid, sc.inverter_battery + sc.ac_battery):
            assert MIN_W <= abs(v) <= MAX_W
