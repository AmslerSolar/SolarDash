# Remote viewing: collectors, stores and observers

Status: implemented 2026-11-04 (wire format: [observer-protocol.md](observer-protocol.md); store: `store/`; web view:
`store/web/`). Designed 2026-11-02.

SolarDash reads a plant's devices on the local network and shows them on a tablet at the plant. This design lets the
same plant be watched from elsewhere — a relative's tablet, a phone, a browser — without accounts, without the plant
depending on a server, and without any server being able to read the data.

## Goals

- **The plant never depends on a server.** Reading, recording, the fault log and the kiosk display work as today; a
  server outage only stops remote viewing.
- **No login.** Watching a plant takes one scan of a QR code on the plant's tablet.
- **Servers are replaceable.** Any number of interchangeable data stores; moving to a new one needs neither the old
  one nor an export from it.
- **Servers can't read the data** (end-to-end encryption) and can't fake it (signatures).
- **Works for off-grid plants** that are online only now and then: data is buffered on the plant and sent later.
- **Small:** about 10–25 MB per plant and year.

Not in scope: remote control of devices (writes), accounts, data across plants on the server (statistics), alarms by
e-mail or push (a later addition that fits the same design).

## Roles

| Role | What it is | What it does |
|---|---|---|
| **Collector** | The SolarDash tablet at the plant | Reads the devices (as today), keeps the full history, owns the plant's keys, encrypts and signs, sends to the stores |
| **Store** | A small server, any number of them | Keeps sealed blocks and the latest live snapshot, relays live values to watchers; reads nothing |
| **Observer** | SolarDash on another device, or the web view in a browser | Fetches, verifies, decrypts and shows; keeps its own cache |

```
 devices ──Modbus/HTTP──> collector ──HTTPS, encrypted + signed──> store A ┐
 (local network)          (plant tablet)                    └────> store B ┤──> observers (app, kiosk, web view)
                               └──────────── same network: directly, no internet ─────────────┘
```

## Plant identity and keys

All keys are made on the collector when the owner switches on "Share with observers" (off by default):

| Key | Where it lives | Used for |
|---|---|---|
| Signing key pair (ECDSA P-256) | Private key in the collector's Android keystore | Signing every block, live snapshot and store list |
| Plant id | Everywhere; = hash of the public signing key | Naming the plant on stores; can't be claimed by anyone else |
| Data key (256 bit) | Collector and observers only (in the QR code) | Encrypting blocks and snapshots (AES-256-GCM) |
| Read token (128 bit, random) | Collector and observers; stores keep only its hash | Asking a store for a plant's data |

Stores hold no secrets: they learn the plant's public key from its first upload (trust on first use, safe because the id
is the key's hash) and the read token's hash from the collector.

**New code** (on the collector) makes a new data key and read token: every existing observer stops seeing new data,
stores drop the old blocks after the collector has re-sent them under the new key.

## What is sent

What the app already records:

- **History blocks:** the plant's samples (solar, grid, house, battery power, state of charge) and optionally chosen
  device channels, every 10–15 s, in blocks of one hour, sent once a minute (or in batches when the plant was offline).
- **Day totals** (solar, consumption, export, import).
- **Fault log entries** with their details (texts, codes, times; no device addresses or serial numbers).
- **Live snapshots** (about 100 bytes) only while someone watches, see [Live view](#live-view).

Device addresses, serial numbers, passwords and settings are never sent.

## Block format

One block holds one hour of samples of a plant, packed column by column:

1. **Integers at a fixed resolution per channel:** W in 1, V in 0.1, A in 0.01, % in 1, kWh in 0.01. Lossless at
   the resolution devices deliver.
2. **Deltas:** per channel the first value, then the change from the previous one; for counters the change of the
   change.
3. **Zigzag + varint:** signed changes folded to small unsigned numbers (0→0, −1→1, +1→2 …) and written in 7-bit
   groups: −64…63 in one byte, ±8191 in two.
4. **Presence bitmap:** one bit per sample and channel; missing or "not available" readings aren't stored.
5. **General compression** (deflate or zstd) over the block: runs of zero changes (night, full battery) cost
   almost nothing.

```
header  format version, start time, interval, channel count, per channel: id + resolution
bitmap  1 bit per sample and channel
data    channel 1: first value, changes …   channel 2: …
→ compress → encrypt (nonce + tag, ~40 bytes) → sign → send
```

About 5–12 bytes per sample of 10 channels: 10–25 MB a year at 15 s, against ~50 MB for 4-byte values at 28 s or JSON
at ~95 s. Each block decodes on its own.

## Store protocol (version 1)

Written up as its own document with conformance tests; all bodies are encrypted and signed by the plant.

| Request | Who | What |
|---|---|---|
| `POST /v1/plants/{id}` | collector | first upload: public key, read-token hash (trust on first use) |
| `POST /v1/plants/{id}/blocks` | collector | one or more history blocks, each with sequence number and time |
| `POST /v1/plants/{id}/days`, `/events` | collector | day totals, fault log entries |
| `POST /v1/plants/{id}/live` | collector | latest live snapshot (kept, not added to the history) |
| `GET /v1/plants/{id}/watch` | collector | long poll / WebSocket: is someone watching? |
| `POST /v1/plants/{id}/stores` | collector | the signed list of stores |
| `GET /v1/observe/…` | observer | `live` (stream), `blocks?from=&to=`, `days`, `events`, `stores`; read token in `X-Observe-Token` |
| `POST /v1/observe/watch` | observer | "I'm watching", valid 2 minutes |

Every block carries a sequence number and a signed time; observers remember the last one they saw, so a store
replaying old blocks or holding back new ones is noticed.

## Observers

### Joining with a QR code

The collector's plant menu gets **Show observer code**: a QR code for

```
https://amsler-solar.ch/solardash/o#<plant id>.<read token>.<data key>.<store list>
```

- In SolarDash: **Add plant → Scan code**, or the phone's camera opens the link and Android hands it to the app. The
  plant is added as an observed plant: same screens, no device setup.
- Without the app: the link opens the [web view](#web-view).
- For people who can't scan: the same as a short code to type.
- Optional per plant: a 4-digit PIN asked once, so a photo of the code alone isn't enough.

### In the app

A plant gets a source: **local devices** (as today) or **observed** (stores + token + key).

- The live poll fetches the live snapshot instead of reading devices and turns it into the same plant snapshot the
  dashboard, kiosk and status card use: every screen works unchanged.
- History and fault log come from the stores, kept in the local history database, so an observer works offline with
  what it has seen.
- A status for observed plants: "Data from 14:32, collector not reachable for 2 h".
- In the same network an observer finds the collector over mDNS and reads its live values directly, without the
  internet.

### Web view

A static page, the same for every plant, served by any store:

1. The token, key and store list come from the part after `#`, which browsers never send to a server (no access
   logs, no `Referer`).
2. The page fetches the data with the token in a header, verifies the signatures and decrypts with WebCrypto.
3. It shows the energy flow, today's totals, the status and a day chart; live values every 2–5 s.
4. "Add to home screen" keeps the link as an icon.

Served with `noindex`, `Referrer-Policy: no-referrer`, a strict Content Security Policy (own script only, no
third-party content), `frame-ancestors 'none'`, HSTS and subresource integrity.

## Live view

No button: live view runs while someone has the plant on screen.

1. The observer shows the last known state with its age at once.
2. It tells the store "I'm watching" every 30–60 s (valid 2 minutes).
3. The collector, waiting on the store with a long poll or WebSocket, learns this within seconds and sends a live
   snapshot every 2–5 s.
4. The store keeps only the latest snapshot and relays it to all watchers (one stream from the collector, however many
   watch).
5. When nobody renews the request, the collector falls back to its normal rhythm after 2 minutes.

Kiosk observers watch all the time; stores may cap them to one snapshot every 10–15 s. A per-plant limit protects
metered links (e.g. at most one snapshot every 10 s, or one hour a day). An hour of live view at 5 s is about 72 KB.

## Independence from any one store

- **The collector keeps everything it sent.** No store holds anything the plant doesn't have.
- **Several stores per plant.** The collector sends to all of them; observers use whichever answers.
- **Moving:** enter a new store's address on the collector; it re-sends its whole history from its own database.
  Neither the old store nor an export is needed.
- **Observers follow:** the store list is signed by the plant and published to every store; observers pick it up on
  their next visit. If every store is gone, the new code is scanned once.
- **Open:** the protocol is documented and the store program is in this repository (GPL), as a Docker image and
  possibly a PHP variant. Because the data is encrypted, a store can run anywhere: a web host, the Docker server, a
  Raspberry Pi, a customer's NAS.
- **Offline path:** "Export plant data" writes the whole history to a file; another collector or a store imports it.

## Scaling

With 10 channels at 15 s, blocks sent once a minute and an hour of live view a day:

| | per plant | 1,000 plants | 10,000 plants | 100,000 plants |
|---|---|---|---|---|
| Storage per year | 10–25 MB | 10–25 GB | 100–250 GB | 1–2.5 TB |
| Uploads | 1/min | ~17/s | ~170/s | ~1,700/s |
| Open collector connections | 1 | 1,000 | 10,000 | 100,000 |

- Sending blocks every 5 minutes instead of every minute divides uploads by five at no loss.
- The limit is open connections: PHP on shared hosting suits a few hundred plants with polling instead of push; an
  event-driven service holds 10,000–50,000 on a small VM.
- Blocks are files under `plant/day/hour` on disk, later in S3-compatible object storage; a small database only knows
  which blocks exist.
- Beyond one server: shard by plant id (no state shared between plants), stateless front servers, the web view on a
  CDN. Plants on several stores, run by installers or customers, spread the load by themselves.

Rough stages: PHP on the existing web host for the first few hundred plants; one VM with an event-driven store up to
tens of thousands; then sharding and object storage. The protocol stays the same.

## Security

| Threat | Protection |
|---|---|
| Reading the data | TLS plus end-to-end encryption; stores see ids, times and sizes only; fixed sending schedule and padded sizes |
| Faking data | Every block, snapshot and store list signed by the plant; checked by stores and by observers |
| Hijacking a plant id | Id = hash of the public key; uploads need the private key |
| Replay, rollback, holding back | Sequence numbers and signed times; observers remember the last one; several stores compared |
| Redirecting observers | Store lists are signed by the plant |
| Reaching the plant | Collector opens no ports, only outgoing connections; it accepts nothing but "someone is watching" (rate-capped); no device writes through this channel |
| Stealing the plant's key | Android keystore, not extractable; a lost tablet is handled with **New code** and a new key |
| Guessing observer access | 128-bit tokens, stored hashed, compared in constant time; same answer for unknown and wrong; no plant list; rate limits |
| A leaked link | **New code** renews token and data key |
| Filling or overloading a store | Quotas per plant, maximum block size and rate, expiry, new plants per address and day; optional: only plants with a key signed by an installer key; reverse proxy with connection limits; other stores take over |
| Malicious data to observers | Strict parsing, fixed format version, size limits, no executable content |
| Web view | Strict CSP, no third-party content, no HTML insertion, `frame-ancestors 'none'`, HSTS, subresource integrity |
| The software | Signed APK releases, store images built from the public repository, pinned dependencies |

What remains by design: whoever holds a valid code can watch until it is renewed. The owner controls that.

## Privacy

- Off by default; the owner switches sharing on per plant and is told what is sent, where, and that the energy
  pattern shows when people are at home.
- Stores keep IP addresses only briefly for rate limits, and have a retention period for blocks.
- Deleting a plant on the collector asks its stores to delete everything; a store's own retention removes the rest.

## Decisions (2026-11-04)

- Crypto built into Android and browsers, no library: ECDSA P-256 and AES-256-GCM instead of Ed25519 and
  XChaCha20-Poly1305.
- The first store is PHP on amsler-solar.ch (files on disk; collectors ask "is someone watching?" every 15 s instead
  of holding a connection). It accepts any plant, with quotas.
- Everything the app records is sent: plant values, every device channel, day totals, the fault log. Samples every
  minute (the app's own rate), live values every 3 s while watched, retention 2 years.
- Joining: the phone's camera reads the QR code and opens the web view, which offers "Open in SolarDash"
  (`solardash://observe#…`); in the app the link or code can be pasted. No camera scanner in the app.

## Effort (rough)

| Part | Days |
|---|---|
| Store (protocol, storage, live relay, limits) | 1–2 |
| Collector: keys, block format, upload with offline queue | 1–2 |
| Observer mode in the app, QR code | 2–3 |
| Web view | 1–2 |
| Protocol document and conformance tests | 1 |
| Testing with a real plant without internet | 1 |
