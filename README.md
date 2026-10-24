# Solar Dashboard (Android)

> A Python / Qt desktop version with the same features lives in [`desktop/`](desktop/README.md).

Live dashboard for solar plants, built in Kotlin and Jetpack Compose. A plant holds any number of
inverters, batteries and power meters; the app reads each device directly on the local network and
shows plant totals plus per-device detail.

## Status

| Device | Kind | Connection | State |
|---|---|---|---|
| GoodWe ET (GW25K / 29.9K / 30K-ET, also EH/BT/BH) | Inverter | Modbus TCP 502 | Implemented, **not yet tested on hardware** |
| LG ESS Home 8 / Home 10 | Inverter + battery | SunSpec Modbus TCP 6502 (firmware 10.05.7213+) | Implemented; same logic as the desktop driver, which was checked on a real Home 10 |
| LG ESS Home 8 / Home 10 | Inverter + battery | Local HTTPS 443, password = MAC address | Implemented; same logic as the desktop driver, checked on a real Home 10. Getting the password: see [desktop/README.md](desktop/README.md#lg-ess-home-810) |
| Huawei SUN2000 (SUN2000MA, incl. LUNA2000 battery and power meter) | Inverter | Modbus TCP 502, unit id 1 (SDongle) or 0 (direct) | Implemented, **not yet tested on hardware**. Notes: see [desktop/README.md](desktop/README.md#huawei-sun2000) |
| KOSTAL PLENTICORE (plus G2, G3), also master/slave with battery | Inverter + battery | Modbus TCP 1502, unit id 71 | Implemented, **not yet tested on hardware** |
| Power-One / ABB TRIO-5.8/8.5, -20.0/27.6, -50.0/60.0 | Inverter (PV only) | Modbus RTU via an RS485 ↔ Modbus TCP gateway, unit id = RS485 address | Implemented, **not yet tested on hardware** |
| FENECON FEMS / OpenEMS | Whole system | Modbus TCP 502, unit id 1 (pre-installed read access) | Implemented, **not yet tested on hardware** |
| Enphase IQ Gateway / Envoy (software 7+) | Microinverters + CTs (+ battery via live data) | Local HTTPS 443 with owner token | Implemented, **not yet tested on hardware** |
| KACO blueplanet 100/125 NX3 | Inverter | SunSpec Modbus TCP 502 | Implemented, **not yet tested on hardware** |
| FIMER / ABB via VSN300 / VSN700 | Inverter | SunSpec Modbus TCP 502, unit id 247 or Aurora address | Implemented, **not yet tested on hardware** |
| APsystems microinverters via ECU-R / ECU-C | Inverter (one device per microinverter) | SunSpec Modbus TCP 502 | Implemented, **not yet tested on hardware** |
| Schneider Conext CL 18/20/25, CL-60, XW system (Conext Gateway / Insight Home) | Inverter (+ battery, grid for XW) | Modbus TCP 502 (CL: unit 10), Gateway port 503 | Implemented, **not yet tested on hardware** |
| Kostal PIKO CI 30/50/60/100 | Inverter (+ meter) | Modbus TCP 1502, unit id 71 | Implemented, **not yet tested on hardware** |
| Ingeteam INGECON SUN 1Play / 3Play | Inverter | Modbus TCP 502, unit id 1 | Implemented, **not yet tested on hardware** |
| CPS / Chint 403X (25 – 350 kW) | Inverter | Modbus RTU via gateway (275/350 kW: TCP 502) | Implemented, **not yet tested on hardware** |
| Riello Solartech central inverters | Inverter | Modbus TCP 502 or RTU via gateway | Implemented, **not yet tested on hardware** |
| Meters: Carlo Gavazzi EM24/EM300/EM210, Eastron SDM630/SDM120, inepro PRO380, ABB B23/B24, Janitza UMG 604-PRO/96RM, Schneider iEM3x55, Siemens PAC2200, KOSTAL KSEM, Shelly Pro 3EM | Meter (grid, PV or consumer role) | Modbus TCP, or RTU via an RS485 gateway | Implemented, **not yet tested on hardware** |
| Heat pumps: Stiebel Eltron (ISG), NIBE S-series, Hoval TopTronic E | Consumer | Modbus TCP 502 | Implemented, read only, **not yet tested on hardware** |
| Wallboxes: KEBA P30, Alfen Eve, MENNEKES AMTRON (Bender ECU), go-eCharger; my-PV AC ELWA / AC THOR heating rods | Consumer | Modbus TCP 502 | Implemented, read only, **not yet tested on hardware** |
| Demo inverter / battery / grid meter / heat pump | all | Simulated | Implemented |
| Fronius GEN24 / GEN24 Plus, with battery and Smart Meter | Inverter + battery | SunSpec Modbus TCP 502, unit id 1 (meter 200) | Implemented, **not yet tested on hardware** |
| SMA Sunny Boy / Tripower / Smart Energy / Sunny Island | Inverter + battery | Modbus TCP 502, unit id 3 | Implemented, **not yet tested on hardware** |
| SolarEdge | Inverter + meter | SunSpec Modbus TCP 1502, unit id 1 | Implemented, **not yet tested on hardware** |
| Other SunSpec inverters | Inverter (+ battery 802, meters) | SunSpec Modbus TCP | Implemented, **not yet tested on hardware** |
| Sungrow SH hybrid / SG string | Inverter + battery | Modbus TCP 502 (LAN or WiNet-S), unit id 1 | Implemented, **not yet tested on hardware** |
| Victron Energy GX (Cerbo GX, Venus OS) | Whole system | Modbus TCP 502, unit id 100 | Implemented, **not yet tested on hardware** |
| Growatt SPH/SPA/MIN/MOD/WIT (VPP protocol V2.01) | Inverter + battery | Modbus RTU via RS485 ↔ TCP gateway, unit id 1 | Implemented, **not yet tested on hardware** |
| Voltronic Axpert and rebrands (MPP Solar, EASUN, Mecer, ACDC Dynamics) | Inverter-charger | ASCII protocol over RS232 via a transparent TCP gateway, port 8899 | Implemented, **not yet tested on hardware** |

No standalone battery or meter drivers exist yet besides the demos.

## Build

1. Open the folder in Android Studio (Koala or newer). Android Studio sets up the Gradle wrapper and SDK.
2. Run the `app` configuration on a phone that is on the **same network as the devices**.
3. Add a plant, then add its devices. For a GoodWe ET: IP address, port `502`, unit id `247`, then
   **Test connection**. Modbus TCP must be enabled on the inverter first (SolarGo app, or register 47040).

Unit tests (drivers, Modbus framing, plant totals, history, discovery; database tests run on Robolectric): `./gradlew test`, or right-click the `test` folder in Android Studio.

**Search network** on the add-device screen looks at the phone's Wi-Fi (its own /24) in two steps: first every
address is tried on Modbus TCP port 502, and each open port is listed at once ("Identifying…"); then each one is
identified, first with the named drivers, then with the bundled ModbusSpec files that have an identify check for
their port (each unit's registers are read once and shared by all files). Open ports nothing recognises stay listed
as "Modbus TCP device, not recognised". **Extended search** also tries the other ports devices use: 503, 1502, 6502,
8899 (RS485/Wi-Fi gateways) and the ports of the ModbusSpec files (4701, 4702, 5555, 8080, 10001, 30502); it takes
longer. Tap a recognised result to fill in the form. Devices already in a plant are listed but not contacted, since
some accept only one Modbus client. The LG HTTPS API (port 443) isn't searched for; its unit shows up via SunSpec.
Devices without an identify check (many meters, heat pumps and wallboxes) are added by hand. A device the generic SunSpec driver
recognises is named by its ModbusSpec file when one identifies it. The search also listens for mDNS announcements (`_sunspec._tcp`, `_modbus._tcp`) for its first 8 seconds; those
devices show up straight away, marked "found via mDNS". The LG ESS Home 10 announces itself as
`LGE_ESS-<serial>` (`_sunspec._tcp`, port 6502); whether GoodWe, Huawei or KOSTAL announce anything is untested.

**SunSpec devices from ModbusSpec:** files marked `sunspec: true` (Fronius, KACO, REFU, Schneider Conext CL,
SolarEdge, APsystems, LG ESS, FranklinWH) aren't read at the file's fixed addresses: the app walks the device's own
SunSpec chain (from "SunS" at 0, 40000 or 50000, whatever models come first) and reads the inverter, meter or
battery models from it. The file adds what SunSpec doesn't say: identification, the sign of the grid power
(`positive` of its grid_power point), and its vendor registers outside the chain (e.g. the Conext CL's energy
counters at 2050), which fill in what the SunSpec models leave empty and are listed with the details.

**Changed IP addresses (DHCP):** each device keeps its serial number (from the search, Test connection, or read
once while polling). If a Modbus device hasn't answered for 2 minutes, the app searches the subnet on its port
for that serial, at most every 15 minutes, and updates its IP when found. mDNS comes first: an announcement
carrying the serial is taken without connecting, other announcements on the port are asked next. Other configured devices aren't
contacted. The LG HTTPS API isn't searched for; a DHCP reservation in the router avoids the issue altogether.
The edit icon on a device opens its settings (name, IP, port, unit id, password); its history stays.

## Device kinds, meter roles and consumers

- **Inverters / batteries** make up the plant totals as before.
- **Meters** have a role: *grid connection* (replaces the inverters' grid values), *PV production* (added to
  solar, e.g. an old inverter without communication) or *consumer sub-meter* (shown as one load in the home). Every
  meter has an "Invert meter direction" option for meters wired the other way round. Meter drivers report power
  flowing away from the site as positive; the role decides what that means.
- **Consumers** (heat pumps, heating rods, wallboxes) are read only and shown in the dashboard's "In the home"
  card with their power and state, plus "Rest of the home". They don't change the totals: the grid meter or the
  balance already includes them.

## Plant totals

- **Solar**: sum of all devices' PV power.
- **Battery**: sum of all battery power, both hybrid-inverter batteries and standalone ones. SOC is a plain
  average across batteries (no capacities yet). Don't add a battery that is already read through its inverter.
- **Grid**: from the plant's power meters if it has any, otherwise the sum of the inverters' own grid values.
  If a configured meter doesn't respond, grid shows as unknown rather than silently using the inverters.
- **Home**: the inverters' own load measurement when the plant is only inverters (no meter, no standalone
  battery) and all report one; otherwise solar + battery − grid, never below zero.
- If any device doesn't respond, the plant screen says the totals are incomplete.

## History

- **Recording:** one plant-level sample (solar, home, battery, grid, battery charge) per minute while a plant is
  open, and about every 15 minutes in the background via WorkManager. Android may defer background work in
  battery saver or Doze. Background recording skips a plant the app is polling right now, so a dongle that allows
  only one Modbus TCP client isn't opened twice.
- **Daily energy:** taken from the devices' "today" counters when they report them; otherwise integrated from the
  samples. Gaps longer than 20 minutes aren't bridged. Counters are ignored for the first 15 minutes after
  midnight, in case a device's clock resets later than the phone's.
- **Device channels:** every numeric value a device shows (live values and detail-view rows) is stored once a
  minute while the plant is open, compressed per device. The background job records plant samples only, so
  channel history has gaps while the app is closed (Android runs background work at most every 15 minutes).
- **Chart:** Day and Week show solar, consumption, battery and grid in kW plus battery charge in % (dashed).
  **Add channel…** adds up to three device channels, picked per device and grouped like the detail view. Each
  unit gets its own y-axis (at most four: left, right, second left, second right); % is always 0–100. Long
  names are shortened in the legend; long-press a chip for the full name, tap its × to remove it.
- **Views:** Day and Week show power curves; Month shows daily energy bars; Year shows monthly bars; Lifetime
  shows yearly bars. Pinch to zoom, drag to pan, tap for values, double-tap to reset. The table button shows the
  same data as text.
- **Storage:** SQLite on the phone (`history.db`). Device data belongs to the device, recognised by type and
  serial number: removing a device keeps its data, and adding the same device again (to any plant) brings it
  back. If it was added before its serial was known, what was recorded meanwhile is merged in once the serial is
  read. The remove dialog can delete the data as well; a device without a known serial can't be recognised, so its
  data goes with it. Removing a plant deletes its plant history (solar, home, battery, grid); its devices' data is
  kept the same way.
- **Demo:** a plant with demo devices offers "Fill with two years of demo history" on its empty history screen.

## Languages

English, German (Swiss spelling), French, Italian and Spanish, following the phone's language. Screen texts are
Android string resources (`res/values-xx/strings_*.xml`). Driver texts (detail sections, rows, states) stay English
in the code and in the stored history keys, and are translated when shown: `i18n/DetailTexts.kt` with the
dictionaries `assets/i18n/details_<lang>.tsv` (`{n}` = a number, `{x}` = inserted text). A text without an entry is
shown in English, so new driver rows need a dictionary line in each language.

## Stored data across updates

- Plants and devices: DataStore, written by `data/PlantStore.kt` as `{"version": n, "plants": [...]}`. Older
  formats are converted step by step; a device this version can't read (e.g. a type from a newer version) is kept
  and written back unchanged; text that can't be read at all is copied to `plants_json_unreadable` before anything
  new is saved.
- Stored enum names are part of the format: `test/resources/stored/device_types.txt` lists every saved
  `DeviceType`. Never remove a line; a rename goes into `PlantStore.RENAMED_TYPES`.
- History: `history.db` (SQLite) with numbered upgrade steps; `HistoryUpgradeTest` opens a schema-1 database.
- An update keeps the data only if it's signed with the same key as the installed app (see Build).

## Drivers from ModbusSpec

Register maps come more and more from [ModbusSpec](~/dev/ModbusSpec), one YAML file per device family. The app
bundles the files it uses as JSON (`app/src/main/assets/modbusspec/`, list in `app/modbusspec.txt`), copied at a
pinned ModbusSpec commit by `python3 -I tools/modbusspec_sync.py` (it reads the files from ModbusSpec's last commit, so
unfinished work there is ignored, and runs ModbusSpec's validator; `LOCK.json` records the commit). The build never reads ModbusSpec itself.

- `driver/spec/SpecReader.kt` reads and decodes a device with its file (a port of ModbusSpec's `tools/msp.py`):
  word order and model detection, requests within the device's limits, scaling, sign normalisation, enums, flags,
  derived values and fault rules.
- `driver/spec/SpecDriver.kt` turns the vocabulary keys into a `DeviceReading` and lists every value as a detail
  row. Points only open-source projects name (not checked against the maker's documents) appear under
  "Other sources (unverified)" and never feed the energy flow or the plant status.
- `driver/spec/SpecDevices.kt` says which device type reads through which file(s) and holds small per-device
  hooks for what the format can't express. The golden tests (`test/…/driver/spec/*GoldenTest.kt`) check each
  spec driver against what the replaced hand-written driver read on the same register images, recorded before those
  drivers were removed (`test/resources/golden/`). Modbus devices that need logic (SunSpec chain,
  Victron's unit ids) and non-Modbus devices (Voltronic, LG, Enphase) stay hand-written.
- Switched (35 device types): Eastron SDM, KEBA P30, Carlo Gavazzi (EM24-E1, EM300, EM210, EM24-DIN), inepro
  PRO380, ABB B23/B24, Janitza UMG, Schneider iEM3000, Siemens PAC2200, KOSTAL KSEM, Shelly Pro 3EM, my-PV,
  Stiebel Eltron ISG, Alfen Eve, MENNEKES/Bender ECU, go-e, NIBE S series (model picked in the device form:
  `model_choice: user`), Hoval TopTronic E, FENECON, Huawei SUN2000, Growatt (SPH, TL-X, WIT), GoodWe ET, SMA,
  Sungrow (SHx, SG), KOSTAL Plenticore (word order from register 5), KOSTAL PIKO CI, Schneider Conext CL-60 and
  Gateway, CPS 403X, Power-One TRIO (asleep at night: `connection.sleeps`), Ingeteam INGECON SUN, Riello central.
- Still hand-written: the SunSpec family (Fronius, SolarEdge, KACO, FIMER, APsystems, Conext CL, LG SunSpec; the
  SunSpec chain is discovered at runtime), Victron (several unit ids), and the non-Modbus Voltronic, LG ESS and
  Enphase.
- Every other energy device in ModbusSpec (inverters, batteries, meters, wallboxes, heat pumps, heating rods,
  UPSs, boilers: `kinds:` in `app/modbusspec.txt`, 438 files) is available through four generic device types
  (`MODBUS_SPEC_INVERTER`, `…_BATTERY`, `…_METER`, `…_CONSUMER`, by the file's kind) with the file's id in
  `DeviceConfig.specId`. The device form lists them under "More devices from ModbusSpec…", searchable by maker and
  model (`assets/modbusspec/index.json`); files without values from the maker's documents are marked as community
  data. All values count, also those only open-source projects name (ModbusSpec keeps the documents' reading where
  both describe a register); the detail view lists them under "Other sources (unverified)".
- The bundled copies leave out write registers (unless a hook reads one: `keep` in `app/modbusspec.txt`), notes and
  source details: 1.5 MB for 40 files.
- History channels of switched devices get new detail row names (the detail rows follow the spec's labels), and
  their detail labels and fault texts are English until they're added to the translations.

## Plant status and help guides

The dashboard and the kiosk show the plant's status in plain words: a green "Everything OK", or what's wrong,
each with **How to fix** opening a built-in help page (works offline, in the tablet's language). On the kiosk,
a problem wakes the screen and keeps it on; a tap on the status line lists the details.

| Status | When | Severity |
|---|---|---|
| No Wi-Fi | The tablet has no Wi-Fi/Ethernet and the plant has network devices (one alarm, not one per device) | alarm |
| Wrong Wi-Fi | A device doesn't answer and its address is outside the tablet's network | alarm |
| No connection | A device doesn't answer (timeout) | alarm |
| Asleep | A PV inverter without battery stopped answering after its power fell below 50 W (night) | info |
| Connection refused | Port closed, Modbus TCP off, or another client connected (single-client dongles) | alarm |
| Login failed | LG ESS password, Enphase token | alarm |
| Check device settings | The device answered with an error or unexpected data (unit id, type) | alarm |
| Starting up | E.g. an invalid SunSpec map while the device boots | warning |
| Fault / Warning | The device's own fault and warning codes (`DeviceReading.problems`) | alarm / warning |
| No mains power | A device reports grid loss; on an **off-grid plant** (plant menu ⋮) it's "No AC input" (generator off), info | warning |
| Backup mode / Off-grid mode | The inverter runs in off-grid / backup / island / emergency-power mode (`DeviceProblem.Kind.BACKUP_MODE`): "Backup mode" on a grid-tied plant, "Off-grid mode" on an off-grid plant | warning / info |
| No internet | Not needed; shown as information | info |

**Fault log:** every problem is recorded with its start and end time (`events` table in history.db, version 4),
from live polling and from the background recorder. A problem must last 30 s to be logged and be gone 30 s to be
closed, so a single dropped poll doesn't fill the log; the background recorder (every 15 min) logs and closes at
once. "No internet", "Asleep" and "Off-grid mode" aren't logged. Open it from the plant screen's overflow menu ⋮
→ "Fault log": a plain list, newest first. Tapping an entry shows what was recorded for the installer
(`model/FaultDetails.kt`, JSON in history.db version 5), both when the problem started and when it ended: the
device (type, address, port, unit id, serial; never the password), the kind of failure and the exact error
message, the device's status, all its fault and warning texts, main values and every detail row (fault registers,
states), the plant's values and which devices answered, the tablet's network and the app version. "Share" sends it
as text (e-mail, messenger). Logic: `model/FaultLog.kt` (tested in `FaultLogTest`).

Rules: `model/PlantStatus.kt` (tested in `PlantStatusTest`); failed reads are classified in
`driver/ConnectionErrors.kt`; guides are `assets/guide/<language>/<id>.md` (a small Markdown subset: `#`, `##`,
`1.`, `-`, `!` for safety notes, `**bold**`).

## Wi-Fi without internet (off-grid)

Off-grid plants often have Wi-Fi without internet, sometimes only the RS232/RS485 gateway's own access point.
Android then shows "no internet" and, if the tablet has mobile data, makes that the default network. The app
requests Wi-Fi and Ethernet without the internet capability (so Android keeps them while the app runs) and binds
every device connection to the local network whose subnet holds the device's address (`net/LocalNetworks.kt`,
`driver/net/DeviceNetwork.kt`); addresses outside it, e.g. a remote site over a VPN, use the normal routing.

On the tablet, once per network: when Android asks "This network has no internet access. Stay connected?",
choose **Yes** and **Don't ask again**. On tablets without a SIM card there's nothing else to set. With a SIM card,
switching off "Switch to mobile data automatically" / "Adaptive Wi-Fi" (name depends on the maker) avoids
Android leaving the Wi-Fi when the app isn't running.

## Kiosk mode (wall-mounted tablet)

The full-screen button on a plant's Dashboard tab shows the energy flow full screen, with today's totals.

- **Dark after 30 s:** after 30 s without a touch the screen turns black and the backlight drops to its minimum.
  The screen is kept on, so Android never locks it. A touch brings the energy flow back; that first touch
  only wakes the screen and doesn't press anything.
- **Recording:** a foreground service (notification "Kiosk mode", with an *End kiosk mode* button) keeps the
  plant polled and recorded once a minute. It holds a wake lock and a Wi-Fi lock, so this continues even when
  the screen really goes off (power button). The kiosk screen and the service share one poll, so devices that
  accept only one Modbus client get a single connection.
- **Lock screen:** if the screen did go off, the kiosk shows over the lock screen when it comes back on.
- **Restart (power outage, update):** kiosk mode is remembered. After the tablet starts (and after an app
  update), `BootReceiver` starts recording again in the kiosk service. Android usually doesn't let an app open
  its own screen after a start, so the screen comes back by itself only when the app is the tablet's **home
  app**: `HomeActivity` then opens the kiosk after every start and on the Home button. The first kiosk start
  asks once to choose it (Settings → Apps → Default apps → Home app; changed back there too). Without it, the
  "Kiosk mode" notification opens the display with a tap; recording runs either way.
- **For a restart without anyone there:** set the screen lock to *None* (with a PIN, Android waits for the PIN
  before any app starts). Many tablets don't switch on by themselves when power comes back after their own
  battery ran flat: keep the tablet on a supply that stays on (e.g. the inverter's AC output or a 12/24 V → USB
  converter on the battery), and check the tablet's "power on when charging" behaviour before installing.
- **Ending it:** the × at the top right, Back, or the notification.
- **Tips:** keep the tablet on its charger; Android's Doze only restricts apps on battery. To stop passers-by
  from leaving the app, use Android's *App pinning* (Settings → Security). The backlight at its minimum is
  still faintly visible on LCD tablets in a dark room.

## Structure

```
model/            Plant and device config, DeviceReading, plant totals, history math and periods
driver/           DeviceDriver interface and factory
driver/modbus/    Modbus TCP framing and client; one connection per host:port, shared across unit ids
driver/goodwe/    GoodWe ET register map and detail view
driver/lg/        LG ESS: SunSpec (port 6502) and local HTTPS API
driver/huawei/    Huawei SUN2000 register map
driver/kostal/    KOSTAL PLENTICORE register map
driver/fronius/   Fronius GEN24 (SunSpec models 1xx, 120, 124, 160, 2xx)
driver/powerone/  Power-One / ABB TRIO (input registers, maps GT and Gt03)
driver/sunspec/   SunSpec discovery and the shared SunSpec inverter driver (Fronius, SolarEdge, generic)
driver/sma/       SMA native Modbus (unit 3)
driver/sungrow/   Sungrow SH / SG
driver/victron/   Victron GX system service
driver/growatt/   Growatt VPP protocol
driver/voltronic/ Voltronic Axpert ASCII protocol over a transparent TCP gateway
driver/meter/     Energy meters (Gavazzi, Eastron, inepro, ABB, Janitza, Schneider, Siemens, KSEM, Shelly)
driver/consumer/  Heat pumps, heating rods, wallboxes (read only)
driver/fenecon/, driver/enphase/, driver/kaco/, driver/fimer/, driver/apsystems/, driver/schneider/,
driver/ingeteam/, driver/cps/, driver/riello/   further inverters and systems
discovery/        Network search for inverters: port scan and mDNS (add-device screen, moved devices)
driver/sunspec/   SunSpec model discovery
driver/demo/      Simulated inverter, battery and meter
data/             Saved plants (DataStore), history (SQLite)
work/             Background history recording
ui/               Compose screens and view models; LivePlants polls each plant once for all screens
kiosk/            Kiosk mode: full-screen activity and recording service
```

To add a device type, add a `DeviceType` entry, implement `DeviceDriver` mapping its values to
`DeviceReading`, wire it in `DeviceDrivers.create()` and set `supported = true`.

## GoodWe ET notes

- Modbus TCP on port 502, unit id `0xF7` (the spec's factory default address 247).
  The app keeps one connection open while the dashboard is visible and closes it when you leave, because
  dongles often allow only one Modbus TCP client at a time. Registers the inverter rejects are not asked again.
- Register map: GoodWe "Modbus protocol Map V1.2" in `docs/specs/goodwe/`. Reads per poll:
  running data 35100 (125 registers), PV total and PV5/PV6 at 35301, battery 2 at 35262, battery 1 SOC at 37007.
  The extra blocks are optional, so older firmware without them still works.
- Signs: grid power is positive when exporting, battery power is positive when discharging. The spec doesn't
  state this, so it follows the open-source [`goodwe`](https://github.com/marcelblijleven/goodwe) library.
  The battery direction was confirmed on the real ET29.9 (2026-10-21: charging shows as charging).
- Where the spec's English and Chinese texts disagree, the Chinese is followed. 35199/35202 ("Energy-Day-Sell/Buy")
  are, per the Chinese, the inverter's own AC output/input of the day, not grid export/import; they're shown as
  such in the detail view. Grid export and import today come from the history, integrated from the grid power.
- Home consumption is read from the inverter: on-grid load (35171) plus back-up load (35169).
- With two batteries, battery power is the sum of both, but SOC is battery 1 only (37007).
- The app polls every 5 seconds while the dashboard is open. Polling stops when you leave the screen.

## KOSTAL PLENTICORE notes

- Register map: "BA KOSTAL Interface MODBUS-TCP SunSpec with Control" in `docs/specs/kostal/` (KOSTAL's own map,
  not the SunSpec models). Port 1502, unit id 71; enable it in the inverter's web interface under
  Settings → Modbus / SunSpec (TCP).
- Byte order: floats are word-swapped ("little-endian CDAB") by default, or "big-endian ABCD" if set so. The driver
  reads register 5 and decodes accordingly; nothing to configure in the app.
- Master / slave (Multi Device Control host / client): add every inverter as its own device of the same plant.
  The driver detects the battery (588 battery type) and the energy meter (1082 installed sensor), so only the
  inverter with the meter reports grid power and home consumption. Solar and battery are summed over all
  inverters; home is then the plant balance. The host's own plant totals are in its detail view only.
- Grid sign: the meter is assumed at the grid connection (sensor position 2: + drawn from the grid). A meter at
  the home-consumption position (sensor position 1) isn't supported.
- Battery direction comes from the battery current (200: charge −, discharge +); 582's sign isn't documented.
- Solar: 1066 (total DC power of the PV inputs) where the firmware has it, otherwise the PV strings; register 34
  says how many there are, as DC3 is used by the battery.
- The map has gaps. Blocks the inverter rejects are read one register at a time; registers it doesn't have are
  skipped and asked again about once a minute.

## Fronius GEN24 notes

- Spec: "Fronius GEN24 Modbus TCP & RTU" 42,0410,2649 in `docs/specs/fronius/`. Enable Modbus TCP in the inverter's
  web interface (Communication → Modbus). Port 502, inverter unit id 1; a Fronius Smart Meter is read through the
  inverter under unit id 200 (the spec's default).
- Register addresses aren't fixed: models are located by walking the SunSpec chain, as the spec requires. Both
  "SunSpec Model Type" settings work ("float": 111–113 / 211–213, "int + SF": 101–103 / 201–203). Scale factors
  are read with every poll, since the spec says they change with firmware and at runtime.
- Solar: the PV inputs of model 160. With a battery, model 160 has two extra modules, "StCha" (charging) and
  "StDisCha" (discharging); battery power is discharge − charge on the DC side. Charge level and state: model 124.
- Grid: Smart Meter power, assumed at the grid connection (meter location 0: + consuming from the grid, per the
  spec's table). Home: inverter AC power − grid. Without a Smart Meter there's no grid or home figure.
- SunSpec has no daily energy counters; daily figures come from the history.
- Models longer than 125 registers (meter model 213) are read in two requests.

## Power-One / ABB TRIO notes

- Specs: the three "Modbus RTU register map" PDFs in `docs/specs/power one/`. TRIO-5.8/8.5 and TRIO-20.0/27.6 use
  map "GT" (input register 100 = 4370, IEEE floats); TRIO-50.0/60.0 use "Gt03" (1000 = 261, U32 energies,
  word-swapped floats). The driver detects the map.
- The TRIO only has RS485. Use an RS485 ↔ Ethernet gateway in "Modbus TCP to RTU" mode (the specs' default line
  settings: 19200 baud, 8N1); enter the gateway's IP and port, and the inverter's RS485 address as unit id
  (2 in the specs' examples). Several TRIOs on one bus share the gateway, each with its own unit id.
- Measurements are input registers (function 04). The specs number registers from 1; whether the gateway passes
  addresses 1-based or 0-based is detected from the map's fixed first value. GT's float word order isn't stated
  and is detected from the grid frequency.
- Solar is the AC output (what the inverter delivers); DC inputs are in the strings. A TRIO doesn't measure the
  grid, so a plant needs a meter (or another inverter that has one) for grid and home figures.
- The inverter is powered by its panels and stops answering at night. After a reading of 500 W or less, no answer
  is shown as "Sleeping (no sun)" with 0 W and retried once a minute; with no earlier reading it's an error.

## Spec documents

All in `docs/specs/<make>/`; each folder has a `SOURCES.md` with the download date, address, size and checksum of every file. Downloaded from the manufacturer (or the SunSpec Alliance / Victron GitHub):
SMA (Modbus TIs and register lists V25), Sungrow (hybrid V1.1.17, grid-connected V1.1.80; not to be redistributed),
Growatt VPP V2.01, Victron register list 3.81, SunSpec models workbook, Voltronic Axpert protocols (2017, 2021),
plus the earlier GoodWe, Huawei, Kostal, LG, Fronius and Power-One documents, and (second batch) meters from
Carlo Gavazzi, Eastron, Janitza, ABB, Schneider, Siemens, inepro, KOSTAL KSEM and Shelly; inverters from KACO,
Ingeteam, FIMER, Schneider Conext, CPS, Kostal PIKO CI, Riello, APsystems, Enphase; FENECON; my-PV, Stiebel Eltron,
NIBE, Hoval, KEBA, Alfen, MENNEKES/Bender and go-e. my-PV, Alfen and Sungrow documents must not be passed on.
The Solar-Log component manual (`docs/other/`) was used as the list of devices to look for; it has no register
maps. Not available officially: E3/DC, VARTA, Alpha ESS (login or removed), sonnen (on the device only), RCT
(forum copy only), Delta, SolarMax, Hoymiles, SAJ, Sofar, Solis, Kstar, Webasto, IDM, EGO, ABL (unofficial copies
only); Siemens SINVERT, Danfoss TLX, Easee and Tesla have no Modbus. Not available officially: SolarEdge
(blocked for automated download; get the SunSpec technical note in a browser), Solis (login/NDA), Deye/Sunsynk,
SolaX, FoxESS, Sofar and the classic Growatt protocol (only unofficial copies exist).

## SunSpec notes (Fronius, SolarEdge, other SunSpec devices)

- One shared driver walks the SunSpec chain and reads inverter 101–103/111–113, MPPT 160, storage 124, battery 802
  and meters 201–204/211–214. Fronius has its meter under unit id 200, SolarEdge chains its meters after the
  inverter on unit id 1.
- SunSpec doesn't define the meter's sign: Fronius documents + = from the grid, SolarEdge is assumed + = to the grid
  (unverified). Each SunSpec device has an "Invert grid sign" option in its settings.
- Battery 802: the direction comes from ChaSt (3 discharging, 4 charging), as W itself has no defined sign.

## SMA notes

- SMA's own register map, unit id 3, port 502, read with function 04. Enable Modbus in the web interface as
  Installer (External Communication → Modbus → TCP Server). For SunSpec on unit id 126 use "Other SunSpec".
- Older Speedwire devices need ≥ 10 s between transfers, so the driver reads at most every 10 s.
- Grid needs an SMA energy meter on the device; a Sunny Island reports house load but not solar.

## Sungrow notes

- Hybrid (SH) and string (SG) models are told apart by the device type code; input registers, addresses sent as
  spec number − 1, 32-bit values low word first. Settings registers are read only every few minutes (the
  WiNet/Logger doesn't tolerate frequent reads of them).
- Hybrid grid sign documented (5601 > 0 buy from grid); battery direction from the power-flow bits. The string
  inverters' meter sign isn't documented and is checked against load = AC + import.

## Victron notes

- The GX's system service (unit id 100) gives the whole installation: AC-coupled and DC PV, consumption, grid per
  phase, battery. Signs are taken from the register list text (grid "supplied by grid to system", battery
  "positive: being charged"). Daily yield exists only per solar charger, so the history integrates.

## Growatt notes

- VPP protocol V2.01 over RS485 via a Modbus TCP gateway (9600 8N1, unit id 1). Reads stay within the spec's
  register groups. No daily PV yield in this protocol; grid and home need the Growatt meter.

## Voltronic Axpert notes

- Not Modbus: ASCII commands (QPIGS, QMOD, QPIRI, …) with CRC-16/XMODEM, 2400 baud over RS232, via an RS232 ↔ TCP
  gateway in transparent mode (port 8899). Only query commands are sent. These units don't measure grid power.
  PI30 firmware; other protocol families (PI16/17/18) aren't covered.

## License

Copyright (C) 2026 Amsler Solar GmbH

This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public
License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later
version. It is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See [LICENSE](LICENSE) for the full text.

Every source file carries `SPDX-License-Identifier: GPL-3.0-or-later`. Libraries and their licences:
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). The manufacturer documents in `docs/specs/` are excluded from
publishing (`.gitignore`); each folder's `SOURCES.md` says where to get them.
