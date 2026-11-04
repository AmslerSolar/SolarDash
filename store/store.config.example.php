<?php
// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH
//
// Settings of store.php. Copy to store.config.php next to store.php and keep only what you change; anything left
// out keeps the default shown here. The values are the limits of docs/design/observer-protocol.md.

return [
    // Where the plants' data goes; created if missing. Best outside the web root. Left at the default
    // (a directory "store-data" next to store.php) it gets a deny-all .htaccess, which only Apache honours.
    'data_dir' => dirname(__DIR__) . '/solardash-store',

    // Sizes in bytes.
    'max_payload' => 262144,                // one envelope's payload; the protocol allows no more
    'max_body' => 4 * 1024 * 1024,          // one request body (PHP's post_max_size must be larger)
    'max_plant_bytes' => 64 * 1024 * 1024,  // all stored envelopes of one plant; beyond: 413

    // Rate limits (0 = none). Beyond: 429 with Retry-After.
    'collector_per_minute' => 120,          // collector requests per plant and minute
    'observer_per_minute' => 300,           // observer requests per client address and minute
    'new_plants_per_day' => 20,             // new plants per client address and UTC day

    'fetch_max' => 200,                     // items one fetch may ask for; beyond: 413
    'index_page' => 2000,                   // items in one index answer ("more": true beyond)
    'watch_seconds' => 120,                 // an observer's watch counts this long

    // Retention, done on the side of ordinary requests.
    'keep_days' => 730,                     // blocks and days older than this (by their names) are deleted
    'plant_idle_days' => 365,               // a plant without an upload for this long is deleted
    'retention_chance' => 0.01,             // share of requests that also run it (0 = never, 1 = every one)

    // Access-Control-Allow-Origin for the web view; '' sends no CORS headers, '*' allows any page.
    'cors_origin' => 'https://amsler-solar.ch',
];
