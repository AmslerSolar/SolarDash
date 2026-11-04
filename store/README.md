# SolarDash store

`store.php` is a store of the observer protocol ([docs/design/observer-protocol.md](../docs/design/observer-protocol.md)):
collectors (plant tablets) upload signed, end-to-end encrypted envelopes; observers (app, web view) fetch them with
the plant's read token. The store checks signatures, seqs, tokens and limits; it can't read any content.

One PHP file, no database: everything is files in a data directory.

## Requirements

- PHP 7.4 or newer (amsler-solar.ch runs 7.4.33) with the `openssl` and `json` extensions (both standard).
- `post_max_size` above `max_body` (4 MiB); PHP's default 8M is fine.
- A writable data directory, preferably outside the web root.

## Settings

Defaults are at the top of `store.php`. To change any, copy `store.config.example.php` to `store.config.php` next
to `store.php` and keep the lines you change. The environment variable `SOLARDASH_STORE_CONFIG` may name one more
settings file, read last (the tests use it; leave it unset in production).

## Upload to amsler-solar.ch

1. Make the data directory outside the web root, e.g. `~/solardash-store` (next to the web root `~/www`).
2. Upload `store.php` to `/solardash/store.php` in the web root.
3. Upload a `store.config.php` next to it with at least the data directory:

   ```php
   <?php
   return ['data_dir' => dirname(__DIR__, 2) . '/solardash-store'];
   ```

   (`dirname(__DIR__, 2)` is the directory above the web root when the script is in `<web root>/solardash/`.)
4. Check: `curl -i 'https://amsler-solar.ch/solardash/store.php?r=/v1/plants/AAAAAAAAAAAAAAAAAAAAAA/watch'`
   answers `404 {"error":"no such plant"}`.

Don't upload `tests/`. If the data directory must stay in the web root, the default `store-data/` next to
`store.php` gets a deny-all `.htaccess`, which protects it on Apache only; check that
`https://…/solardash/store-data/secret` answers 403.

## Conformance tests

Python 3 with the `cryptography` package; `php` on the path for local runs.

```sh
python3 store/tests/conformance.py           # local: php -S on a free port, temporary data, small test limits
python3 store/tests/conformance.py -v        # the same, one line per test
python3 store/tests/conformance.py --url https://amsler-solar.ch/solardash/store.php
```

The local run starts one `php -S` per settings profile (normal, small rate limits, retention on every request),
each with its own temporary data directory, and stops them at the end (`--keep` keeps data and `php.log`).
Against a real store the tests use fresh random keys (plus the fixed key of `vectors.json`, deleted again at the
end), wait 0.2 s between requests (`--delay`), and skip what needs small limits or the data directory (rate limits,
plant quota, retention, watch expiry, PATH_INFO), and delete their plants again at the end. A run registers about
25 new plants, more than the 20 per address and day: raise `new_plants_per_day` in the host's `store.config.php`
for the run (and set it back), or the later tests fail with 429.

`tests/vectors.json` holds envelopes made by the app's Kotlin code (`WireTest`); the tests check that Python makes
the same bytes and that the store takes them and gives them back unchanged.
