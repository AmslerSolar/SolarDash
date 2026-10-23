# Solar Dashboard (desktop)

Python / Qt desktop version of the dashboard, with the same features as the Android app in the parent folder:
plants with several inverters, batteries and power meters; a live energy flow diagram; zoomable history
(day and week as power curves, month, year and lifetime as energy bars); and a Devices tab with Test connection.

## Run

```bash
cd desktop
python3 -m venv .venv
.venv/bin/pip install -e ".[test]"
.venv/bin/python -m solar_dashboard
```

With exactly one plant saved, the app opens it straight away; the ← button leads to the plant list.

Tests: `.venv/bin/python -m pytest`

## Devices

| Device | Connection | State |
|---|---|---|
| GoodWe ET (GW25K / 29.9K / 30K-ET, also EH/BT/BH) | Modbus TCP 502, unit id 247 | Implemented, **not yet tested on hardware** |
| LG ESS Home 8 / Home 10 | SunSpec Modbus TCP, port 6502, unit id 1 (firmware 10.05.7213+) | Implemented, checked on a real Home 10 |
| LG ESS Home 8 / Home 10 | Local HTTPS API, port 443, password = MAC address | Implemented, checked on a real Home 10 |
| Demo inverter / battery / grid meter | Simulated, values from 1 W to 99 kW changing every 5 s | Implemented |
| Huawei SUN2000 (SUN2000MA, incl. LUNA2000 battery and power meter) | Modbus TCP 502, unit id 1 (SDongle) or 0 (direct) | Implemented, **not yet tested on hardware** |
| Fronius, SMA, SolarEdge | | Planned |

The GoodWe register map follows `docs/specs/goodwe/` (Modbus protocol Map V1.2). Power signs: grid + = export,
battery + = discharge (not stated by the spec; confirm on the real inverter). Modbus TCP must be enabled on the
inverter (SolarGo app, or register 47040). Devices behind the same gateway share one TCP connection.

## LG ESS Home 8/10

Two ways in; both were checked against a real Home 10 (firmware 10.05.7437).

**SunSpec Modbus TCP** (port 6502, unit id 1, no password), per LG's "Home 8/10 LG ESS SunSpec ver. 1.3"
(`docs/specs/lg ess/`). The driver walks the SunSpec model chain and reads models 1 (identity), 113 or 103
(inverter), 802 (battery), 64256 (LG's grid meter), and 120/123 (ratings, controls) for the detail view.
What the real unit does differently from the table:

- Scale factors work the other way round from SunSpec: value ÷ 10^SF (voltage 2384, SF 1 → 238.4 V).
- Battery power has no scale factor (already W); battery voltage and currents aren't real readings.
- The meter's "PV generation energy" is *today's* PV yield in Wh; purchase and feed-in are lifetime totals.
- Solar power isn't a register: it's derived as AC output − battery power, so it reads a few percent low
  (conversion losses); home consumption (AC output + grid purchase) stays exact.

**Local HTTPS API** (port 443), per LG's "LG ESS Inverter — Local HTTPS API" (`docs/specs/lg ess/`). Gives the
same live values plus PV strings, today's export / import / consumption, and much more in the detail view
(system and battery info, fault list, battery settings, …). The driver only reads.

- **Password:** the user password is the unit's **MAC address**, lowercase without colons (e.g. `001122aabbcc`).
  In the add-device dialog, enter the IP address and press **Use MAC address** to look it up. By hand:
  `ping <ip>`, then `ip neigh show <ip>` (or `arp -a`).
- Every unit shares the same self-signed certificate, so it can't be verified; checks are off for this device only.
- The unit accepts the password on `/v1/user/setting/login`; the documented `/v1/login` refuses it on this
  firmware, so the driver tries both. A stale key answers 405 `auth_key failed` (the document says 200
  `{"status":"fail"}`); either triggers a new login.
- The password is stored in `plants.json`, which is readable only by your user.

## Huawei SUN2000

Per "SUN2000MA V200R024C00SPC106 Modbus Interface Definitions (V3.0)" (`docs/specs/huawei/`). The driver reads
only contiguous runs of defined registers (Huawei rejects reads that touch undefined ones) of at most 125
registers, and waits a second after connecting before the first request.

- Live: PV1–PV6 voltage/current, total input power, AC output, temperature, device status, today's and total
  yield; battery (LUNA2000) power, charge, status; grid meter power, phases, frequency.
- Detail view: identity and ratings, every inverter output value, energy counters, the power meter, the battery
  and battery unit 1, and the alarm words.
- **To confirm on a real installation:** the meter's power sign. The spec doesn't state it; it's taken as
  > 0 export, as the community `huawei-solar` library does. The battery sign is documented (> 0 charging).
- Device status names (32089) come from Huawei's published code list; the table the spec refers to isn't in
  this document.

## Data

- Plants: `~/.config/solar-dashboard/plants.json`
- History: `~/.local/share/solar-dashboard/history.db` (SQLite)

Both follow `XDG_CONFIG_HOME` / `XDG_DATA_HOME`. The desktop app keeps its own data; it doesn't share it with the
phone app.

## History chart

- **Day and Week** show plant power (solar, consumption, battery, grid in kW) and the **battery charge** (%, dashed
  in the battery colour) as curves. **Month, Year and Lifetime** show energy bars.
- **Add channel…** puts any recorded value of any device of the plant on the Day/Week chart: pick the device,
  search, tick channels. Up to three added channels; each keeps its colour and its own dash pattern (dash-dot,
  dotted, dash-dot-dot), so they're told apart from the solid plant curves and from each other without relying on
  colour alone. ✕ removes one; the selection is saved with the plant.
- **One y-axis per unit**, up to four: first unit on the left, second on the right, then a second left and a second
  right axis. W/Wh/VA/var are shown as kW/kWh/kVA/kvar so power shares one scale. Each axis fits its own data as
  you zoom; gridlines follow the first axis. A series whose unit would be a fifth axis is refused.
- Hover lists every visible series' value at that time.

**Recording:** every numeric value a device reports (the live reading plus every number in the detail view) is
stored once a minute per device, compressed, in `history.db`. That's roughly 300–500 MB per device and year,
depending on how much the device reports. Channel history starts when this version first runs.

## History recording

While a plant is open, the app stores one sample per minute. To keep recording while the app is closed, run the
recorder from a systemd user timer (every minute):

```bash
mkdir -p ~/.config/systemd/user
cp systemd/solar-dashboard-record.* ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now solar-dashboard-record.timer
```

Edit the paths in the `.service` file if this folder isn't at `~/dev/apk-dashboard/desktop`. The recorder skips
plants that the open app is polling, so a dongle that accepts only one Modbus client isn't connected to twice.
Daily energy comes from the devices' "today" counters when present, otherwise from the samples; gaps longer than
20 minutes aren't bridged.

## Structure

```
solar_dashboard/
  model.py         Plants, devices, readings, plant totals (aggregate)
  history.py       Energy integration, daily merge, calendar periods
  modbus.py        Modbus TCP framing, reconnecting client, shared connections
  drivers/         GoodWe ET and demo devices
  reader.py        Reads a plant's devices in parallel
  storage.py       plants.json, history.db, "app is polling" markers
  demo_history.py  Two years of generated history for demo plants
  ui/              Qt windows: plant list, Dashboard / History / Devices tabs, dialogs
```

To add a device type: add a `DeviceType` entry, write a driver with `identify()` and `read()` returning a
`DeviceReading`, and wire it in `drivers/create_driver()`.
