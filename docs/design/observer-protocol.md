# Observer protocol, version 1

The wire format between a collector (the plant tablet), stores and observers. The design and its reasons are in
[remote-viewing.md](remote-viewing.md); this document is what the three sides implement. The store program is
`store/store.php`, its conformance tests `store/tests/`.

All integers are big-endian. Text is UTF-8. "b64url" is base64url without padding.

## Keys and ids

| Item | What it is |
|---|---|
| Signing key | ECDSA P-256 key pair of the plant, made on the collector (Android keystore, not extractable) |
| Public key | X.509 SubjectPublicKeyInfo (SPKI), DER |
| Plant id | b64url of the first 16 bytes of SHA-256(public key): 22 characters |
| Data key | 32 random bytes, AES-256-GCM; collector and observers only |
| Read token | 16 random bytes; a store keeps only SHA-256(token) |

Signatures are ECDSA P-256 with SHA-256, encoded as ASN.1 DER (Java's `SHA256withECDSA`). WebCrypto produces and
checks the raw 64-byte form (r ‖ s); an observer in the browser converts.

## Envelope

Everything a collector sends is one envelope:

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | magic `SDO1` |
| 4 | 1 | kind (below) |
| 5 | 8 | seq: unsigned, rises with every envelope a plant makes |
| 13 | 8 | time: when it was made, epoch milliseconds |
| 21 | 1 | n = length of name (1–64) |
| 22 | n | name: `[A-Za-z0-9._-]` |
| 22+n | 4 | p = payload length (at most 262 144) |
| 26+n | p | payload |
| 26+n+p | 2 | s = signature length |
| 28+n+p | s | signature over every byte before it (offset 0 to 26+n+p) |

The header is bytes 0 to 22+n (magic to the end of the name).

| Kind | Name | Payload |
|---|---|---|
| 0 register | `register` | public key (SPKI DER) ‖ SHA-256(read token) (32 bytes); not encrypted |
| 1 block | `b<epoch second of the hour, UTC>` (plant channels), `b<epoch second>.<device id>` (one device's channels) | sealed: one hour of samples ([Block](#block)) |
| 2 day | `d<YYYY-MM-DD>` (the plant's local date) | sealed: JSON [Day](#day) |
| 3 event | `e<event id>` | sealed: JSON [Event](#event) |
| 4 live | `live` | sealed: JSON [Live](#live) |
| 5 plant | `plant` | sealed: JSON [Plant](#plant) |
| 6 stores | `stores` | sealed: JSON array of store URLs |
| 7 delete | `delete` | empty; not encrypted |

**Sealed** payload: `nonce (12 random bytes) ‖ AES-256-GCM(data key, nonce, zlib(content))` with the 16-byte tag
appended (Java's `AES/GCM/NoPadding` output), and the envelope header as additional authenticated data. zlib is
RFC 1950 (Java's `Deflater`, PHP's `gzcompress`, the browser's `DecompressionStream("deflate")`).

An observer checks the signature with the plant's public key, that the kind and name are what it asked for, then
decrypts. A store checks the signature only.

## Store

A store is a URL, e.g. `https://amsler-solar.ch/solardash/store.php`. Requests go to `<store>?r=<route>` (the
route as a query parameter works on every host; `<store>/<route>` may work too). Answers are JSON unless said
otherwise; errors are `{"error": "<text>"}` with status 400 (malformed), 403 (signature, token), 404 (no such plant),
409 (plant id taken by another key), 413 (too large), 429 (rate limit, `Retry-After` in seconds).

Binary bodies are `application/octet-stream`. Several envelopes in one body are **frames**: each a 4-byte length
followed by the envelope.

### Collector

| Request | Body | Answer |
|---|---|---|
| `POST /v1/plants/{id}` | a register envelope | `{"ok": true}`; 409 if the id is another key's |
| `POST /v1/plants/{id}/items` | frames of envelopes (kinds 1–6) | `{"stored": n, "skipped": n}` |
| `GET /v1/plants/{id}/watch` | – | `{"watching": true \| false}` |
| `POST /v1/plants/{id}/delete` | a delete envelope | `{"ok": true}`: the plant and everything it stored is gone |

- **Register** makes the plant known: the store checks that the id is the first 16 bytes of SHA-256(public key),
  base64url, and the signature with that key. Registering again with the same key and a higher seq replaces the
  token hash (the collector's **New code**).
- **Items**: each envelope's signature is checked with the plant's key; one failing envelope fails the request (403)
  and nothing of it is stored. An envelope replaces the stored one of the same kind and name only when its seq is
  higher; otherwise it's skipped (no rollback). `live` is kept as the latest only.
- **Watch** is true while an observer's watch is younger than 120 s.

### Observer

The read token goes in the header `X-Observe-Token: <b64url token>`. An unknown plant and a wrong token give the
same answer (403) after the same work.

| Request | Body | Answer |
|---|---|---|
| `GET /v1/observe/{id}/key` | – | the public key (SPKI DER), octet-stream |
| `GET /v1/observe/{id}/index?since=<seq>` | – | `{"items": [[kind, name, seq], …], "more": false}`: stored items except `live` with a seq above `since` (default 0), by rising seq, at most 2000; `more` is true when there are further ones (ask again with the last seq) |
| `POST /v1/observe/{id}/fetch` | JSON `[[kind, name], …]` (at most 200) | frames of the stored envelopes, in that order; missing ones left out |
| `GET /v1/observe/{id}/live` | – | the latest live envelope, octet-stream; 204 if none |
| `POST /v1/observe/{id}/watch` | – | 204; marks the plant as watched for 120 s |

An observer remembers the highest seq it saw per kind and name and ignores anything older (a store replaying old
envelopes).

### Details every store keeps to

- **Ids and keys:** a plant id must be canonical base64url of 16 bytes (else 400, also on observer routes; only a
  well-formed unknown id gets the shared 403). The public key must be the 91-byte SPKI of a P-256 key with an
  uncompressed point (else 400), so one key can't register under two ids. A seq with the top bit set is 400.
- **Names per kind:** `b<1–12 digits>` optionally `.<device id>`, `d<YYYY-MM-DD>`, `e<id>`, and the fixed names
  `register`, `live`, `plant`, `stores`, `delete`; a name that doesn't fit its kind is 400.
- **Register again** with the same key and a seq at or below the stored one answers `{"ok": true}` and changes
  nothing (a retry or replay is harmless).
- **Delete** needs a seq above every seq the store took from that plant, else 409 (an old delete can't wipe a plant
  that registered again); its payload must be empty.
- **Items:** an empty body, or kind 0 or 7 inside, is 400; a payload over the limit is 413 and nothing of the request
  is stored. **Fetch** with more than 200 entries is 413. A wrong method is 405 with `Allow`; OPTIONS is 204.
- **Watch** (`GET /v1/plants/{id}/watch`) needs no token: it tells only whether someone watches now, to whoever
  knows the plant id. Accepted, since the id alone reveals nothing else.
- **Retention** goes by the date in a block's or day's name (days with a day's slack for local dates), and a plant's
  last register or items upload. **Rate limits** use fixed windows (`Retry-After` = seconds to the window's end),
  count every collector route per plant, and see only `REMOTE_ADDR`; addresses are kept only as HMACs. The quota
  counts every stored envelope, live included; a replaced one frees its size.

### Limits (amsler-solar.ch)

| Limit | Value |
|---|---|
| Envelope | 256 KiB payload |
| Request body | 4 MiB |
| Storage per plant | 64 MiB (413 beyond) |
| Collector requests per plant | 120 per minute |
| Observer requests per address | 300 per minute |
| New plants per address | 20 per day |
| Retention | blocks and days older than 2 years are deleted; a plant without an upload for 1 year is deleted |

## Contents

### Block

One hour of one-minute samples, before zlib:

| Field | Size |
|---|---|
| version (1) | 1 |
| start: epoch second of the hour (UTC) | 4 |
| interval in seconds (60) | 2 |
| slots: samples per channel (60) | 2 |
| c = channel count | 2 |
| per channel: name length, name, exponent (signed byte: value = integer × 10^exponent) | 1 + n + 1 |
| presence: per channel, ⌈slots / 8⌉ bytes, bit i (least significant first) = slot i has a value | c × ⌈slots / 8⌉ |
| values: per channel, the present values in slot order: the first as is, then each the change from the one before, every one zigzag-encoded and written as an unsigned LEB128 varint | – |

Slot i covers `start + i × interval`; a sample at time t goes to slot ⌊(t − start) / interval⌋.
Zigzag: 0 → 0, −1 → 1, 1 → 2, −2 → 3 …

Channels:

| Name | Value | Exponent |
|---|---|---|
In a plant block (`b<start>`):

| Name | Value | Exponent |
|---|---|---|
| `pv`, `load`, `battery`, `grid` | the plant's power in W (battery + = discharging, grid + = export) | 0 |
| `soc` | the plant's state of charge, % | 0 |

In a device block (`b<start>.<device id>`) every channel the app records for that device, named by its key (e.g.
`r:pv_power`, `d:Battery/Voltage`), exponent by unit: W, VA, var, Wh, % 0; V, °C −1; A, Hz, kWh and others −2.
Labels and units are in the [Plant](#plant) item's `channels`.

### Day

```json
{"day": "2026-11-04", "plant": {"pv": 12.3, "export": 4.1, "import": 2.0, "load": 10.2},
 "devices": {"<device id>": {"pv": 6.1, "export": null, "import": null, "load": null}}}
```
kWh; a missing value is `null` or left out.

### Event

A fault log entry, as the app keeps it:

```json
{"id": 42, "key": "…", "kind": "MAINS_LOST", "severity": "…", "deviceId": "…", "deviceName": "…",
 "detail": "…", "percent": null, "started": 1762250000, "ended": null, "details": "…", "endDetails": null}
```
Times in epoch seconds; `ended` stays `null` while the problem lasts (the event is sent again when it ends).

### Live

The plant as the collector sees it now:

```json
{"t": 1762250000123, "pv": 3200, "load": 900, "grid": 1800, "gridSource": "METERS",
 "battery": {"w": -500, "soc": 64},
 "today": {"pv": 12.3, "export": 4.1, "import": 2.0, "load": 10.2},
 "devices": [{"id": "…", "error": null, "errorKind": null, "reading": Reading}],
 "consumers": [{"id": "…", "w": 2100, "status": "Charging"}]}
```

Reading (all fields may be missing):

```json
{"pv": 3200, "grid": null, "load": null, "temp": 41.5, "status": "Producing", "lifetimePv": 12345.6,
 "battery": {"w": -500, "soc": 64, "v": 52.1, "mode": "Charging"},
 "phases": [{"name": "L1", "v": 231.5, "a": 4.2, "hz": 50.01, "w": 970}],
 "strings": [{"i": 1, "v": 420.0, "a": 6.0, "w": 2500}],
 "today": {"pv": 12.3, "export": null, "import": null, "load": null},
 "problems": [{"kind": "FAULT", "text": "…"}],
 "details": [{"title": "…", "rows": [["label", "value"], …]}]}
```

### Plant

```json
{"name": "Home", "offGrid": false,
 "devices": [{"id": "…", "name": "…", "type": "LG_ESS", "meterRole": "GRID", "model": "", "specId": "",
              "hiddenProblems": []}],
 "chartChannels": [{"deviceId": "…", "key": "…", "slot": 0}],
 "channels": {"<device id>": [["<key>", "<label>", "<unit>"], …]}}
```
Never in it: device addresses, ports, unit ids, passwords, serial numbers.

## Observer code

The QR code on the collector holds the link

```
https://amsler-solar.ch/solardash/o#<plant id>.<b64url read token>.<b64url data key>.<b64url of the JSON store list>
```

The part after `#` never reaches a server. The web view at that address reads it; it also offers
`solardash://observe#<the same>`, which the app opens.
