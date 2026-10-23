"""LG ESS Home 8 / Home 10 hybrid inverters with battery, over their local HTTPS API.

Reference: "LG ESS Inverter — Local HTTPS API" (docs/specs/lg ess/), checked against a real Home 10
(firmware 10.05.7437), which differs from the document in places:

- HTTPS on port 443. Every unit shares the same self-signed certificate, so it can't be verified; checks are
  off for this device only.
- The user password is the inverter's MAC address, lowercase hex without separators. The unit accepts it on
  `PUT /v1/user/setting/login`; the documented `/v1/login` answers "password_mismatched" there, so both are
  tried. Every read is a `POST` with `{"auth_key"}` in the body.
- An invalid or expired key answers 405 `{"auth": "auth_key failed"}` on the unit, 200 `{"status": "fail"}`
  per the document; both mean log in again. 404 means the endpoint doesn't exist on this model / firmware.
- `/v1/logout` is documented but returns 404 on the unit; it's tried once when polling stops.
- This driver only reads. (Earlier versions copied pyess's clock sync after login; that's a write the
  document doesn't list, so it's gone.)
- Values are JSON strings. Power in W, daily energy in Wh; power magnitudes are unsigned and the direction
  comes from the 0/1 flags in `home.direction`.
"""
from __future__ import annotations

import json
import ssl
import threading
import urllib.error
import urllib.request

from dataclasses import replace

from ..model import BatteryState, DetailSection, DeviceInfo, DeviceReading, EnergyTotals, PhaseReading, PvString

LOGIN_PATHS = ("/v1/user/setting/login", "/v1/login")
LOGOUT = "/v1/logout"
HOME = "/v1/user/essinfo/home"
COMMON = "/v1/user/essinfo/common"
SYSTEMINFO = "/v1/user/setting/systeminfo"
# Read-only extras for the detail view, refreshed about once a minute; missing ones (404) are skipped.
EXTRA_READS = (("Faults", "/v1/user/operation/faultlist"), ("Battery settings", "/v1/user/setting/batt"),
               ("Clock", "/v1/user/setting/time"), ("PCS settings", "/v1/user/setting/pcssetting"),
               ("Overload", "/v1/user/essinfo/overloadfaultview"), ("Firmware update", "/v1/user/imageupdate/status"))
EXTRA_REFRESH_POLLS = 12
AUTH_FAILED = ({"auth": "auth_key failed"}, {"auth": "auth failed"}, {"status": "fail"})


def auth_failed(data: dict) -> bool:
    """405 {"auth": "auth_key failed"} on the real unit, 200 {"status": "fail"} per LG's document."""
    return data.get("auth") in ("auth_key failed", "auth failed") or data.get("status") == "fail"


class NotAvailable(IOError):
    """HTTP 404: the endpoint doesn't exist on this model or firmware."""


class LgEssAuthError(IOError):
    pass


def _num(section: dict | None, key: str) -> float | None:
    """Values arrive as strings like "1712" or "52.87357"; missing or malformed → None."""
    try:
        return float(section[key])
    except (TypeError, KeyError, ValueError):
        return None


def _int(section, key) -> int | None:
    v = _num(section, key)
    return None if v is None else round(v)


def _kwh(section, key) -> float | None:
    v = _num(section, key)
    return None if v is None else v / 1000


def _flag(section, key) -> bool:
    return (section or {}).get(key) == "1"


def parse_reading(home: dict, common: dict | None) -> DeviceReading:
    """Map the `home` (live values, direction flags) and optional `common` (strings, grid, daily totals)
    responses onto a DeviceReading, using our signs: grid + export, battery + discharge."""
    stats = home.get("statistics", {})
    direction = home.get("direction", {})

    battery = None
    if stats.get("bat_use", "1") == "1":
        magnitude = _int(stats, "batconv_power") or 0
        power = magnitude if _flag(direction, "is_battery_discharging_") else \
            -magnitude if _flag(direction, "is_battery_charging_") else 0
        mode = "Discharging" if power > 0 else "Charging" if power < 0 else "Standby"
        if _flag(direction, "is_charging_from_grid_"):
            mode += " (from grid)"
        battery = BatteryState(power, _int(stats, "bat_user_soc"), None, mode)

    grid_mag = _int(stats, "grid_power")
    grid = None
    if grid_mag is not None:
        grid = grid_mag if _flag(direction, "is_grid_selling_") else \
            -grid_mag if _flag(direction, "is_grid_buying_") else 0

    operation = home.get("operation", {}).get("status")
    fault = home.get("pcs_fault", {}).get("pcs_status")
    status = {"start": "Running", "stop": "Stopped"}.get(operation, operation or "Unknown")
    if fault and fault != "pcs_ok":
        status = f"Fault ({fault})"

    strings, phases, today = (), (), EnergyTotals()
    if common:
        pv = common.get("PV", {})
        strings = tuple(
            PvString(i, _num(pv, f"pv{i}_voltage") or 0.0, _num(pv, f"pv{i}_current") or 0.0, _int(pv, f"pv{i}_power"))
            for i in (1, 2) if (_num(pv, f"pv{i}_voltage") or 0) > 0 or (_int(pv, f"pv{i}_power") or 0) > 0)
        g = common.get("GRID", {})
        volts = _num(g, "a_phase")
        if volts:
            # The API reports one phase voltage and only a total power: no per-phase current or power.
            phases = (PhaseReading("L1", volts, None, _num(g, "freq") or 0.0, None),)
        today = EnergyTotals(
            pv_kwh=_kwh(pv, "today_pv_generation_sum"),
            export_kwh=_kwh(g, "today_grid_feed_in_energy"),
            import_kwh=_kwh(g, "today_grid_power_purchase_energy"),
            load_kwh=_kwh(common.get("LOAD", {}), "today_load_consumption_sum"),
        )

    return DeviceReading(
        pv_power_w=_int(stats, "pcs_pv_total_power"),
        pv_strings=strings,
        battery=battery,
        grid_power_w=grid,
        phases=phases,
        load_power_w=_int(stats, "load_power"),
        status=status,
        today=today,
    )


# Raw fields named after a phase get the L1/L2/L3 names used everywhere else.
PHASE_LABELS = {"a_phase": "L1 voltage (a_phase)", "b_phase": "L2 voltage (b_phase)", "c_phase": "L3 voltage (c_phase)"}


def _label(key: str) -> str:
    """'is_battery_charging_' → 'Is battery charging'; the device's own spellings (e.g. 'enery') are kept."""
    if key in PHASE_LABELS:
        return PHASE_LABELS[key]
    text = key.strip("_").replace("_", " ")
    return text[:1].upper() + text[1:]


def _flatten(prefix: str, obj: dict) -> list[DetailSection]:
    """One section per JSON object, with every field the unit sent (values as reported)."""
    out, rows = [], []
    for k, v in obj.items():
        if isinstance(v, dict):
            out += _flatten(f"{prefix} · {k}", v)
        else:
            rows.append((_label(k), str(v)))
    return ([DetailSection(prefix, tuple(rows))] if rows else []) + out


def details(home: dict, common: dict | None, systeminfo: dict | None,
            extras: dict[str, dict] | None = None) -> tuple[DetailSection, ...]:
    """Raw fields as the unit reports them. Units: power W, energies Wh, SoC and self-consumption %."""
    sections = []
    if systeminfo:
        sections += _flatten("System info", systeminfo)
    sections += _flatten("Home", home)
    if common:
        sections += _flatten("Details", common)
    for title, body in (extras or {}).items():
        sections += _flatten(title, body)
    return tuple(sections)


def mac_address(host: str, port: int = 443) -> str | None:
    """The unit's user password: its MAC, lowercase without separators, from the system's ARP table. A quick
    TCP connection first makes sure the entry is there. Linux (/proc/net/arp, `ip neigh`) only."""
    import re
    import socket
    import subprocess

    try:
        socket.create_connection((host, port or 443), timeout=3).close()
    except OSError:
        return None
    try:
        for line in open("/proc/net/arp").read().splitlines()[1:]:
            fields = line.split()
            if fields and fields[0] == host and fields[3] != "00:00:00:00:00:00":
                return fields[3].replace(":", "").lower()
    except OSError:
        pass
    try:
        out = subprocess.run(["ip", "neigh", "show", host], capture_output=True, text=True, timeout=3).stdout
        if m := re.search(r"lladdr ([0-9a-f:]{17})", out):
            return m.group(1).replace(":", "")
    except (OSError, subprocess.SubprocessError):
        pass
    return None


class LgEssDriver:
    def __init__(self, host: str, port: int, password: str, timeout: float = 5.0):
        self.base = f"https://{host}:{port}" if port and port != 443 else f"https://{host}"
        self.password = password
        self.timeout = timeout
        self._auth_key: str | None = None
        self._lock = threading.Lock()
        # The device uses a self-signed certificate; this context is used for its requests only.
        self._ssl = ssl.create_default_context()
        self._ssl.check_hostname = False
        self._ssl.verify_mode = ssl.CERT_NONE
        self._systeminfo: dict | None = None  # read once for the detail view
        self._extras: dict[str, dict] = {}
        self._missing: set[str] = set()  # endpoints that answered 404
        self._polls = 0

    def _request(self, method: str, path: str, body: dict) -> tuple[int, dict]:
        req = urllib.request.Request(self.base + path, data=json.dumps(body).encode(), method=method,
                                     headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=self.timeout, context=self._ssl) as r:
                return r.status, json.loads(r.read() or b"{}")
        except urllib.error.HTTPError as e:  # an expired key comes back as 405 with a JSON body
            if e.code == 404:
                raise NotAvailable(f"{path} not available on this unit") from None
            try:
                return e.code, json.loads(e.read() or b"{}")
            except ValueError:
                return e.code, {}

    def _login(self):
        if not self.password:
            raise LgEssAuthError("No device password set")
        mismatched = False
        for path in LOGIN_PATHS:
            try:
                _, data = self._request("PUT", path, {"password": self.password})
            except NotAvailable:
                continue
            if data.get("status") == "success" and "auth_key" in data:
                self._auth_key = data["auth_key"]
                return
            mismatched |= "mismatch" in str(data.get("status"))
        raise LgEssAuthError("Wrong device password (it's the inverter's MAC address, lowercase, no colons)"
                             if mismatched else "Login failed")

    def _post(self, path: str) -> dict:
        with self._lock:
            for attempt in range(2):
                if self._auth_key is None:
                    self._login()
                status, data = self._request("POST", path, {"auth_key": self._auth_key})
                if auth_failed(data):
                    self._auth_key = None  # expired: log in again and retry once
                    continue
                if status != 200:
                    raise IOError(f"HTTP {status} from {path}")
                return data
            raise LgEssAuthError("Device rejected the login")

    def identify(self) -> DeviceInfo:
        info = self._post(SYSTEMINFO)
        pms = info.get("pms", {})
        version = info.get("version", {}).get("pms_version")
        model = pms.get("model", "LG ESS") + (f" (firmware {version})" if version else "")
        return DeviceInfo(model=model, serial=pms.get("serialno", "?"))

    def _optional(self, path: str) -> dict | None:
        if path in self._missing:
            return None
        try:
            return self._post(path)
        except NotAvailable:
            self._missing.add(path)
        except (OSError, ValueError):
            pass
        return None

    def read(self) -> DeviceReading:
        home = self._post(HOME)
        common = self._optional(COMMON)  # strings, grid voltage and daily totals are extras
        if self._systeminfo is None:
            self._systeminfo = self._optional(SYSTEMINFO) or {}
        if self._polls % EXTRA_REFRESH_POLLS == 0:
            self._extras = {title: body for title, path in EXTRA_READS if (body := self._optional(path))}
        self._polls += 1
        return replace(parse_reading(home, common), details=details(home, common, self._systeminfo, self._extras))

    def close(self):
        """End the session; the unit may not have the endpoint (404), which is fine."""
        with self._lock:
            if self._auth_key:
                try:
                    self._request("POST", LOGOUT, {"auth_key": self._auth_key})
                except OSError:
                    pass
                self._auth_key = None
