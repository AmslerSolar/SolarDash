"""Saved plants (JSON) and history (SQLite), in the user's XDG config and data directories."""
from __future__ import annotations

import json
import os
import sqlite3
import zlib
import threading
import time
from datetime import date, datetime
from pathlib import Path

from . import history as hm
from .history import Sample
from .channels import extract
from .model import EnergyTotals, Plant, PlantSnapshot


def config_dir() -> Path:
    return Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config")) / "solar-dashboard"


def data_dir() -> Path:
    return Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local" / "share")) / "solar-dashboard"


class PlantStore:
    def __init__(self, path: Path | None = None):
        self.path = path or config_dir() / "plants.json"

    def load(self) -> list[Plant]:
        try:
            return [Plant.from_json(p) for p in json.loads(self.path.read_text())]
        except (FileNotFoundError, ValueError, KeyError):
            return []

    def save(self, plants: list[Plant]):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps([p.to_json() for p in plants], indent=2))
        tmp.chmod(0o600)  # may contain device passwords
        tmp.replace(self.path)


_SCHEMA = """
CREATE TABLE IF NOT EXISTS samples (
    plant_id TEXT NOT NULL, ts INTEGER NOT NULL,
    pv INTEGER, load INTEGER, battery INTEGER, grid INTEGER, soc INTEGER,
    PRIMARY KEY (plant_id, ts));
-- day is ISO yyyy-mm-dd, so months and years group by prefix.
CREATE TABLE IF NOT EXISTS daily (
    plant_id TEXT NOT NULL, day TEXT NOT NULL,
    pv REAL, load REAL, export REAL, import REAL,
    PRIMARY KEY (plant_id, day));
-- Per-device channels (see channels.py): a catalogue, and one compressed row of values per device and sample.
CREATE TABLE IF NOT EXISTS channels (
    id INTEGER PRIMARY KEY, device_id TEXT NOT NULL, key TEXT NOT NULL, label TEXT NOT NULL, unit TEXT NOT NULL,
    UNIQUE (device_id, key));
CREATE TABLE IF NOT EXISTS device_samples (
    device_id TEXT NOT NULL, ts INTEGER NOT NULL, data BLOB NOT NULL,
    PRIMARY KEY (device_id, ts));
"""

DEVICE_SAMPLE_INTERVAL_S = 60  # all channels of a device, every minute (~300–500 MB per device and year)


def _energy(row) -> EnergyTotals:
    pv, load, export, import_ = row
    return EnergyTotals(pv_kwh=pv, load_kwh=load, export_kwh=export, import_kwh=import_)


class HistoryStore:
    """Plant-level power samples and daily energy totals. Safe to use from several threads."""

    def __init__(self, path: Path | None = None):
        self.path = path or data_dir() / "history.db"
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._db = sqlite3.connect(self.path, check_same_thread=False, timeout=10)
        self._db.executescript(_SCHEMA)
        self._lock = threading.Lock()

    def record(self, plant_id: str, snapshot: PlantSnapshot):
        if all(s.reading is None for s in snapshot.devices):
            return
        b = snapshot.battery
        sample = Sample(int(snapshot.read_at), snapshot.pv_power_w, snapshot.load_power_w,
                        b.power_w if b else None, snapshot.grid_power_w, b.soc_percent if b else None)
        moment = datetime.fromtimestamp(snapshot.read_at)
        counters = snapshot.today if hm.counters_usable(moment.time()) else None
        with self._lock, self._db:
            prev = self._db.execute(
                "SELECT ts, pv, load, battery, grid, soc FROM samples WHERE plant_id=? AND ts<? ORDER BY ts DESC LIMIT 1",
                (plant_id, sample.epoch)).fetchone()
            day = moment.date()
            merged = hm.merge(self._daily(plant_id, day), counters, hm.integrate(Sample(*prev) if prev else None, sample))
            self._insert_sample(plant_id, sample)
            self._upsert_daily(plant_id, day, merged)
            self._record_devices(snapshot)

    def _record_devices(self, snapshot: PlantSnapshot):
        """Every channel of every responding device, at most every DEVICE_SAMPLE_INTERVAL_S (checked against
        the database, so the app and the background recorder don't double up)."""
        ts = int(snapshot.read_at)
        for status in snapshot.devices:
            if status.reading is None:
                continue
            device_id = status.device.id
            last = self._db.execute("SELECT MAX(ts) FROM device_samples WHERE device_id=?", (device_id,)).fetchone()[0]
            if last is not None and ts - last < DEVICE_SAMPLE_INTERVAL_S - 10:
                continue
            values = {}
            for ch in extract(status.reading):
                row = self._db.execute("SELECT id, label, unit FROM channels WHERE device_id=? AND key=?",
                                       (device_id, ch.key)).fetchone()
                if row is None:
                    cid = self._db.execute("INSERT INTO channels (device_id, key, label, unit) VALUES (?,?,?,?)",
                                           (device_id, ch.key, ch.label, ch.unit)).lastrowid
                else:
                    cid = row[0]
                    if (row[1], row[2]) != (ch.label, ch.unit):
                        self._db.execute("UPDATE channels SET label=?, unit=? WHERE id=?", (ch.label, ch.unit, cid))
                values[str(cid)] = round(ch.value, 6)
            blob = zlib.compress(json.dumps(values, separators=(",", ":")).encode())
            self._db.execute("INSERT OR REPLACE INTO device_samples VALUES (?,?,?)", (device_id, ts, blob))

    def channel_catalog(self, device_id: str) -> list[tuple[str, str, str]]:
        """(key, label, unit) of every channel recorded for the device."""
        with self._lock:
            return self._db.execute("SELECT key, label, unit FROM channels WHERE device_id=? ORDER BY id",
                                    (device_id,)).fetchall()

    def channel_series(self, device_id: str, keys: list[str], start: int, end: int) -> dict[str, list[tuple[int, float]]]:
        """Values of the given channels between start and end (epoch seconds), per key."""
        with self._lock:
            ids = dict(self._db.execute(
                f"SELECT id, key FROM channels WHERE device_id=? AND key IN ({','.join('?' * len(keys))})",
                (device_id, *keys)).fetchall()) if keys else {}
            rows = self._db.execute("SELECT ts, data FROM device_samples WHERE device_id=? AND ts>=? AND ts<? ORDER BY ts",
                                    (device_id, start, end)).fetchall() if ids else []
        out: dict[str, list[tuple[int, float]]] = {k: [] for k in keys}
        for ts, blob in rows:
            values = json.loads(zlib.decompress(blob))
            for cid, key in ids.items():
                if (v := values.get(str(cid))) is not None:
                    out[key].append((ts, v))
        return out

    def delete_devices(self, device_ids):
        with self._lock, self._db:
            for d in device_ids:
                self._db.execute("DELETE FROM device_samples WHERE device_id=?", (d,))
                self._db.execute("DELETE FROM channels WHERE device_id=?", (d,))

    def insert(self, plant_id: str, samples: list[Sample], days: list[tuple[date, EnergyTotals]]):
        with self._lock, self._db:
            for s in samples:
                self._insert_sample(plant_id, s)
            for d, e in days:
                self._upsert_daily(plant_id, d, e)

    def delete_plant(self, plant_id: str):
        with self._lock, self._db:
            self._db.execute("DELETE FROM samples WHERE plant_id=?", (plant_id,))
            self._db.execute("DELETE FROM daily WHERE plant_id=?", (plant_id,))

    def samples(self, plant_id: str, start: int, end: int) -> list[Sample]:
        with self._lock:
            rows = self._db.execute(
                "SELECT ts, pv, load, battery, grid, soc FROM samples WHERE plant_id=? AND ts>=? AND ts<? ORDER BY ts",
                (plant_id, start, end)).fetchall()
        return [Sample(*r) for r in rows]

    def days(self, plant_id: str, start: date, end: date) -> dict[date, EnergyTotals]:
        with self._lock:
            rows = self._db.execute(
                "SELECT day, pv, load, export, import FROM daily WHERE plant_id=? AND day BETWEEN ? AND ? ORDER BY day",
                (plant_id, start.isoformat(), end.isoformat())).fetchall()
        return {date.fromisoformat(r[0]): _energy(r[1:]) for r in rows}

    def months(self, plant_id: str, year: int) -> dict[int, EnergyTotals]:
        with self._lock:
            rows = self._db.execute(
                "SELECT substr(day, 6, 2) m, SUM(pv), SUM(load), SUM(export), SUM(import) FROM daily "
                "WHERE plant_id=? AND day LIKE ? GROUP BY m", (plant_id, f"{year}-%")).fetchall()
        return {int(r[0]): _energy(r[1:]) for r in rows}

    def years(self, plant_id: str) -> dict[int, EnergyTotals]:
        with self._lock:
            rows = self._db.execute(
                "SELECT substr(day, 1, 4) y, SUM(pv), SUM(load), SUM(export), SUM(import) FROM daily "
                "WHERE plant_id=? GROUP BY y", (plant_id,)).fetchall()
        return {int(r[0]): _energy(r[1:]) for r in rows}

    def first_day(self, plant_id: str) -> date | None:
        with self._lock:
            row = self._db.execute("SELECT MIN(day) FROM daily WHERE plant_id=?", (plant_id,)).fetchone()
        return date.fromisoformat(row[0]) if row and row[0] else None

    def _daily(self, plant_id: str, day: date) -> EnergyTotals | None:
        row = self._db.execute("SELECT pv, load, export, import FROM daily WHERE plant_id=? AND day=?",
                               (plant_id, day.isoformat())).fetchone()
        return _energy(row) if row else None

    def _insert_sample(self, plant_id: str, s: Sample):
        self._db.execute("INSERT OR REPLACE INTO samples VALUES (?,?,?,?,?,?,?)",
                         (plant_id, s.epoch, s.pv_w, s.load_w, s.battery_w, s.grid_w, s.soc_percent))

    def _upsert_daily(self, plant_id: str, day: date, e: EnergyTotals):
        self._db.execute("INSERT OR REPLACE INTO daily VALUES (?,?,?,?,?,?)",
                         (plant_id, day.isoformat(), e.pv_kwh, e.load_kwh, e.export_kwh, e.import_kwh))


class ActivePolling:
    """Marks plants the GUI is polling right now (a heartbeat file per plant), so the background recorder
    in another process doesn't open a second connection to a dongle that accepts only one."""

    STALE_S = 30

    def __init__(self, directory: Path | None = None):
        self.dir = directory or data_dir() / "active"

    def beat(self, plant_id: str):
        self.dir.mkdir(parents=True, exist_ok=True)
        (self.dir / plant_id).touch()

    def stop(self, plant_id: str):
        (self.dir / plant_id).unlink(missing_ok=True)

    def is_active(self, plant_id: str) -> bool:
        try:
            return time.time() - (self.dir / plant_id).stat().st_mtime < self.STALE_S
        except FileNotFoundError:
            return False
