"""Two years of plausible 15-minute history for a 29.9 kW plant with a 20 kWh battery: seasons, cloudy days,
morning/evening load peaks and winter heating. For trying the history views."""
from __future__ import annotations

import math
import random
import time
from datetime import date, datetime

from . import history as hm
from .history import Sample
from .model import EnergyTotals

STEP_S = 900
BATTERY_WH = 20_000.0


def generate(seed: int, days: int = 730) -> tuple[list[Sample], list[tuple[date, EnergyTotals]]]:
    rng = random.Random(seed)
    end = int(time.time()) // STEP_S * STEP_S
    start = end - days * 86_400
    samples: list[Sample] = []
    daily: dict[date, EnergyTotals] = {}
    soc, prev, day, clouds = 50.0, None, None, 1.0
    for t in range(start, end + 1, STEP_S):
        moment = datetime.fromtimestamp(t)
        if moment.date() != day:
            day = moment.date()
            clouds = 0.2 + 0.8 * math.sqrt(rng.random())
        season = 0.5 + 0.5 * math.cos(2 * math.pi * (moment.timetuple().tm_yday - 172) / 365)
        hour = moment.hour + moment.minute / 60
        sun = max(0.0, math.cos((hour - 13) / (4.5 + 3.5 * season) * math.pi / 2))
        pv = round(sun * (9_000 + 17_000 * season) * clouds * (0.85 + 0.3 * rng.random()))
        peaks = 1_500 * math.exp(-((hour - 7.5) / 1.0) ** 2) + 2_500 * math.exp(-((hour - 19) / 1.5) ** 2)
        heating = 1_800 * (1 - season) * (1.0 if 5 <= hour <= 22 else 0.4)
        load = round(450 + peaks + heating + rng.randrange(400))
        surplus = pv - load
        battery = -min(surplus, 8_000) if surplus > 0 and soc < 95 else min(-surplus, 8_000) if surplus < 0 and soc > 10 else 0
        soc = min(100.0, max(0.0, soc - battery * (STEP_S / 3600) / BATTERY_WH * 100))
        s = Sample(t, pv, load, battery, pv + battery - load, round(soc))
        daily[day] = hm.merge(daily.get(day), None, hm.integrate(prev, s))
        samples.append(s)
        prev = s
    return samples, list(daily.items())
