"""History maths and calendar periods (pure, no storage)."""
from __future__ import annotations

from dataclasses import dataclass, replace
from datetime import date, time as dtime, timedelta
from enum import Enum

from .model import EnergyTotals

MAX_GAP_SECONDS = 20 * 60  # longer gaps between samples aren't integrated across
_COUNTERS_FROM = dtime(0, 15)  # device "today" counters may reset later than our midnight


@dataclass(frozen=True)
class Sample:
    epoch: int
    pv_w: int | None
    load_w: int | None
    battery_w: int | None
    grid_w: int | None
    soc_percent: int | None


def counters_usable(t: dtime) -> bool:
    return t >= _COUNTERS_FROM


def integrate(prev: Sample | None, cur: Sample) -> EnergyTotals:
    """Energy between two consecutive samples (trapezoid rule); None per metric when not computable."""
    if prev is None:
        return EnergyTotals()
    seconds = cur.epoch - prev.epoch
    if seconds <= 0 or seconds > MAX_GAP_SECONDS:
        return EnergyTotals()
    hours = seconds / 3600

    def kwh(a, b, part):
        return None if a is None or b is None else (part(a) + part(b)) / 2 * hours / 1000

    pos = lambda v: max(v, 0)
    return EnergyTotals(
        pv_kwh=kwh(prev.pv_w, cur.pv_w, pos),
        load_kwh=kwh(prev.load_w, cur.load_w, pos),
        export_kwh=kwh(prev.grid_w, cur.grid_w, pos),
        import_kwh=kwh(prev.grid_w, cur.grid_w, lambda v: max(-v, 0)),
    )


def merge(existing: EnergyTotals | None, counters: EnergyTotals | None, delta: EnergyTotals) -> EnergyTotals:
    """A device counter wins (max, so a missed poll can't pull the day down); else the delta is added."""
    def pick(name):
        old = getattr(existing, name) if existing else None
        counter = getattr(counters, name) if counters else None
        d = getattr(delta, name)
        if counter is not None:
            return max(old or 0.0, counter)
        if d is not None:
            return (old or 0.0) + d
        return old

    return EnergyTotals(**{n: pick(n) for n in ("pv_kwh", "export_kwh", "import_kwh", "load_kwh")})


def sum_totals(values: list[EnergyTotals]) -> EnergyTotals:
    def total(name):
        present = [getattr(v, name) for v in values if getattr(v, name) is not None]
        return sum(present) if present else None

    return EnergyTotals(**{n: total(n) for n in ("pv_kwh", "export_kwh", "import_kwh", "load_kwh")})


def self_sufficiency(e: EnergyTotals) -> float | None:
    """Share of consumption not bought from the grid."""
    if e.load_kwh is None or e.import_kwh is None or e.load_kwh <= 0:
        return None
    return min(max(1 - e.import_kwh / e.load_kwh, 0.0), 1.0)


def self_consumption(e: EnergyTotals) -> float | None:
    """Share of solar production used on site rather than exported."""
    if e.pv_kwh is None or e.export_kwh is None or e.pv_kwh <= 0:
        return None
    return min(max(1 - e.export_kwh / e.pv_kwh, 0.0), 1.0)


class HistoryPeriod(Enum):
    DAY = "Day"
    WEEK = "Week"
    MONTH = "Month"
    YEAR = "Year"
    LIFETIME = "Lifetime"


def _add_months(d: date, months: int) -> date:
    m = d.month - 1 + months
    y = d.year + m // 12
    m = m % 12 + 1
    last = (date(y + (m == 12), m % 12 + 1, 1) - timedelta(days=1)).day
    return date(y, m, min(d.day, last))


@dataclass(frozen=True)
class HistoryRange:
    """The calendar period shown, anchored on any date inside it. Weeks start on Monday."""
    period: HistoryPeriod
    anchor: date

    @property
    def start(self) -> date | None:
        a = self.anchor
        return {
            HistoryPeriod.DAY: a,
            HistoryPeriod.WEEK: a - timedelta(days=a.weekday()),
            HistoryPeriod.MONTH: a.replace(day=1),
            HistoryPeriod.YEAR: a.replace(month=1, day=1),
        }.get(self.period)

    @property
    def end(self) -> date | None:
        s = self.start
        if s is None:
            return None
        return {
            HistoryPeriod.DAY: s,
            HistoryPeriod.WEEK: s + timedelta(days=6),
            HistoryPeriod.MONTH: _add_months(s, 1) - timedelta(days=1),
            HistoryPeriod.YEAR: s.replace(year=s.year + 1) - timedelta(days=1),
        }[self.period]

    def shift(self, by: int) -> HistoryRange:
        a = self.anchor
        new = {
            HistoryPeriod.DAY: lambda: a + timedelta(days=by),
            HistoryPeriod.WEEK: lambda: a + timedelta(weeks=by),
            HistoryPeriod.MONTH: lambda: _add_months(a, by),
            HistoryPeriod.YEAR: lambda: _add_months(a, 12 * by),
            HistoryPeriod.LIFETIME: lambda: a,
        }[self.period]()
        return replace(self, anchor=new)

    def has_next(self, today: date) -> bool:
        return self.end is not None and self.end < today

    def label(self) -> str:
        if self.period is HistoryPeriod.DAY:
            return self.anchor.strftime("%a, %d %b %Y")
        if self.period is HistoryPeriod.WEEK:
            s, e = self.start, self.end
            left = s.strftime("%d %b") if s.year == e.year else s.strftime("%d %b %Y")
            return f"{left} – {e.strftime('%d %b %Y')}"
        if self.period is HistoryPeriod.MONTH:
            return self.anchor.strftime("%B %Y")
        if self.period is HistoryPeriod.YEAR:
            return str(self.anchor.year)
        return "All time"
