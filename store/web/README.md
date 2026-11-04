# SolarDash web view (observer mode)

A static page that shows a plant's live values, today's totals, its messages and a day chart from a store, for
people without the app. It implements the observer side of
[docs/design/observer-protocol.md](../../docs/design/observer-protocol.md) ("Web view" and "Live view" in
[remote-viewing.md](../../docs/design/remote-viewing.md)). No third-party code, no build step.

## What is where

| Path | What |
|---|---|
| `o/index.html` | The page. Opened as `https://amsler-solar.ch/solardash/o#<plant id>.<token>.<key>.<stores>` |
| `o/app.js` | Everything: link parsing, store requests, signature checks, decryption, block decoding, display. The pure functions are on `window.SolarDashObserver`; texts (English, German) in `TEXTS` at the top |
| `o/style.css` | Layout, light and dark colours (the app's series colours) |
| `o/.htaccess` | Security headers for Apache (CSP incl. `frame-ancestors`, nosniff, no referrer, noindex) |
| `o/test.html`, `o/test.js` | Self-test of the pure functions against `store/tests/vectors.json`; not for the host |
| `tools/run_tests.py` | Runs all tests headless in Chromium (self-test and the page end to end) |
| `tools/fake_store.py` | A fake store serving the vectors (or fresh envelopes) for local tests; development only |
| `tools/sri.py` | Checks / writes the subresource-integrity hashes in `o/index.html` |

How the page works: it reads the link from `location.hash` (never sent to a server), tries the stores in order and
uses the first one whose `key` hashes to the plant id. It fetches `plant` and `stores`, the latest `live`, then
pages through the `index` and fetches the day item and the plant blocks (`b<start>`, not device blocks) of the day
of the latest live values. Every envelope is checked for the requested kind and name, the plant's signature and a
seq above the highest seen, then decrypted. While the page is visible it sends `watch` every 45 s and polls `live`
every 3 s, and reads new index items every 5 minutes; when hidden it stops.

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
links. `--screenshots DIR` saves phone, tablet, dark and error screenshots.

By hand: `python3 store/web/tools/fake_store.py --fresh` prints a link to open in a browser
(`http://127.0.0.1:8765/…`, a secure context, so WebCrypto works) and the self-test address.
