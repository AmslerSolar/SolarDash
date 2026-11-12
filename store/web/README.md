# SolarDash web view (observer mode)

A static page that shows a plant's live values, today's totals, its messages, its history (the app's History tab)
and its fault log (the app's Faults tab) from a store, for people without the app. It implements the observer side of
[docs/design/observer-protocol.md](../../docs/design/observer-protocol.md) ("Web view" and "Live view" in
[remote-viewing.md](../../docs/design/remote-viewing.md)). No third-party code, no build step.

## What is where

| Path | What |
|---|---|
| `o/index.html` | The page. Opened as `https://amsler-solar.ch/solardash/o#<plant id>.<token>.<key>.<stores>` |
| `o/app.js` | Everything: link parsing, store requests, signature checks, decryption, block decoding, display. The pure functions are on `window.SolarDashObserver`; texts (English, German) in `TEXTS` at the top |
| `o/style.css` | Layout, light and dark colours (the app's series and channel colours) |
| `o/.htaccess` | Security headers for Apache (CSP incl. `frame-ancestors`, nosniff, no referrer, noindex) |
| `o/test.html`, `o/test.js` | Self-test of the pure functions against `store/tests/vectors.json`; not for the host |
| `tools/run_tests.py` | Runs all tests headless in Chromium (self-test and the page end to end) |
| `tools/fake_store.py` | A fake store serving the vectors (or a fresh plant with devices, 40 days, events) for local tests; counts requests; development only |
| `tools/sri.py` | Checks / writes the subresource-integrity hashes in `o/index.html` |

How the page works: it reads the link from `location.hash` (never sent to a server), tries the stores in order and
uses the first one whose `key` hashes to the plant id. It fetches `plant` and `stores`, the latest `live`, then
pages through the `index` (names and seqs only) and fetches what the period shown needs, and only that. Every
envelope is checked for the requested kind and name, the plant's signature and a seq above the highest seen, then
decrypted. While the page is visible it sends `watch` every 45 s and polls `live` every 3 s, and reads new index
items every 5 minutes (then fetches only items with a newer seq); when hidden it stops.

History, as in the app (`ui/history/`):

| Period | Chart | Fetched |
|---|---|---|
| Day | minute curves: plant (kW), battery charge (%), added device channels, one y-axis per unit (at most 4) | the day's plant blocks `b<hour>`, the blocks `b<hour>.<device>` of devices with a channel on the chart, the day item |
| Week | the same over 7 days (Monday first) | the same for 7 days (≤ 4 fetch requests with 3 devices) |
| Month | energy bars per day (solar, consumption; export and import hidden at first) | the month's day items |
| Year, Lifetime | energy bars per month / year, summed from day items (like `HistoryRepository.months/years`) | day items only, never blocks |

Previous / next navigate (no next past today); the period follows the live values until the viewer navigates
away. On phones the channel chips sit in one row that scrolls sideways and the chart runs nearly edge to edge. Below
the chart: the values at the tapped time or bar, and the Totals card (yield, consumption, export, import,
self-sufficiency, self-consumption).
Fetched items stay in memory; beyond 2500 decoded blocks the oldest outside the period are dropped and fetched again
when needed (the seq tracker then takes the same seq again, never an older one).

Channels: the legend chips show and hide curves (or bars). "Add channel…" opens the picker: the plant's devices,
each device's channels from the `plant` item's `channels` catalog grouped like the app (live values first, then
the detail sections), with search; at most 3 added channels and 4 units, as in the app. Sensors (e.g. a ModbusSpec
weather station: irradiance W/m², wind m/s, temperatures °C) are channels like any other, each unit on its own axis
fitted to its own range. The default is the owner's `chartChannels`; the viewer's choice is kept per plant in
`localStorage` (`solardash.chart.<plant id>`; any storage error just means the default).

Zoom: mouse wheel or pinch around the pointer, drag to pan once zoomed, double-click / double-tap or "Reset zoom";
hover or tap shows the values at that time (or of that bar); keys: arrows, + / −, 0.

Fault log (`FaultLogActivity`): the `e<id>` events, fetched once the section comes into view (newest 1000, in
chunks of 200), then only changed ones. Active (not ended) and past entries, newest first, with severity, times,
duration, device and the device's text; a tap opens what the app recorded at the start and end. The device's
address, port, unit id, serial number and the tablet's address ranges, which the event details carry, are not shown.

Not as in the app: there is no "whole plant / one device" switch (device energy per day is not in the day items
the collector sends, and working it out would mean fetching every device block of a month or year); driver
detail labels are translated only where the page knows them (the common channel names and sections), the rest
stays English; there are no guide pages for active problems.

## Upload

1. After any change to `app.js` or `style.css`: `python3 store/web/tools/sri.py --write` (the browser refuses
   files whose hash doesn't match).
2. Copy `o/index.html`, `o/app.js`, `o/style.css` and `o/.htaccess` to `/solardash/o/` on the host. Don't upload
   `test.html`, `test.js` or `tools/`.
3. Check: `https://amsler-solar.ch/solardash/o/` shows "This link is incomplete…"; the response headers include
   `Content-Security-Policy` with `frame-ancestors 'none'` (if not, mod_headers or `.htaccess` is off).

The link is `/solardash/o#…` without a slash; Apache redirects it to `/solardash/o/#…` and browsers keep the
fragment across that redirect. Stores on another origin than the page must answer CORS preflights (`GET`/`POST`
with the `X-Observe-Token` and `Content-Type` headers); `amsler-solar.ch/solardash/store.php` is the page's own
origin and needs none.

## Tests

```
python3 store/web/tools/run_tests.py [--screenshots DIR]
```

Needs Chromium (`/usr/bin/chromium`, or `--chromium PATH`); the "fresh" cases need python3-cryptography to sign
envelopes with the vectors' software key and are skipped without it. It checks the SRI hashes, runs `o/test.html`
and loads `o/index.html` against the fake store: the vectors as they are (index paging one item per page), a fresh
plant (live, day, blocks, messages; also in German and at 320 px), a wrong code, an unreachable store and broken
links, and the history with the fresh plant: periods and navigation, which items each period fetches (no blocks for
month / year, faults only once in view), the picker (default from `chartChannels`, adding, the unit limit, removing,
remembered after a reload), hiding curves and bars, wheel / drag / pinch / double-click zoom, the table and the fault
log with its details (and without the device address or serial). `--screenshots DIR` saves phone, tablet, dark,
error, history (week, month, year, zoomed day, table), picker and fault log screenshots.

By hand: `python3 store/web/tools/fake_store.py --fresh` prints a link to open in a browser
(`http://127.0.0.1:8765/…`, a secure context, so WebCrypto works) and the self-test address.
