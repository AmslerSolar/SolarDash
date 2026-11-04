<?php
// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH
//
// A SolarDash store: keeps the signed, end-to-end encrypted envelopes of plants and hands them to observers
// (docs/design/observer-protocol.md, version 1). It checks signatures only; it can't read anything it keeps.
//
// Requests: store.php?r=<route> (or store.php/<route> where the host passes PATH_INFO), e.g.
//   POST store.php?r=/v1/plants/<id>/items
// Answers are JSON, errors {"error": "<text>"}.
//
// Settings: below, or in store.config.php next to this file (returns an array with the same keys). The environment
// variable SOLARDASH_STORE_CONFIG may name one more such file, read last (the conformance tests use it).
//
// Runs on PHP 7.4 (amsler-solar.ch has 7.4.33) with the openssl and json extensions.

declare(strict_types=1);

ini_set('display_errors', '0');

$config = [
    // Where the plants' data goes. Best outside the web root (e.g. dirname(__DIR__) . '/solardash-store');
    // the default next to this file gets a deny-all .htaccess. Created if missing.
    'data_dir' => __DIR__ . '/store-data',
    'max_payload' => 262144,                // bytes of one envelope's payload (the protocol's maximum)
    'max_body' => 4 * 1024 * 1024,          // bytes of one request body
    'max_plant_bytes' => 64 * 1024 * 1024,  // stored envelopes of one plant together
    'collector_per_minute' => 120,          // collector requests per plant and minute (0 = no limit)
    'observer_per_minute' => 300,           // observer requests per client address and minute (0 = no limit)
    'new_plants_per_day' => 20,             // new plants per client address and UTC day (0 = no limit)
    'fetch_max' => 200,                     // items in one fetch
    'index_page' => 2000,                   // items in one index answer
    'watch_seconds' => 120,                 // how long an observer's watch counts
    'keep_days' => 730,                     // blocks and days older than this are deleted
    'plant_idle_days' => 365,               // a plant without an upload for this long is deleted
    'retention_chance' => 0.01,             // share of requests that also run the retention (0 to 1)
    'cors_origin' => 'https://amsler-solar.ch', // Access-Control-Allow-Origin ('' = no CORS headers)
];
if (is_file(__DIR__ . '/store.config.php')) {
    $config = array_merge($config, (array) require __DIR__ . '/store.config.php');
}
$extraConfig = getenv('SOLARDASH_STORE_CONFIG');
if (is_string($extraConfig) && $extraConfig !== '' && is_file($extraConfig)) {
    $config = array_merge($config, (array) require $extraConfig);
}

const KIND_REGISTER = 0;
const KIND_LIVE = 4;
const KIND_DELETE = 7;
const ID_PATTERN = '~^[A-Za-z0-9_-]{22}$~D';
// P-256 SubjectPublicKeyInfo with an uncompressed point: this prefix, then 64 bytes of x and y.
const P256_SPKI_PREFIX = '3059301306072a8648ce3d020106082a8648ce3d03010703420004';

/** An answer other than success: status, text, extra headers. */
final class StoreError extends Exception
{
    /** @var array<string, string> */
    public $headers;

    /** @param array<string, string> $headers */
    public function __construct(int $status, string $text, array $headers = [])
    {
        parent::__construct($text, $status);
        $this->headers = $headers;
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Answers

function common_headers(): void
{
    global $config;
    header('X-Content-Type-Options: nosniff');
    header('Cache-Control: no-store');
    header('Referrer-Policy: no-referrer');
    header("Content-Security-Policy: default-src 'none'; frame-ancestors 'none'");
    $origin = (string) $config['cors_origin'];
    if ($origin !== '') {
        header('Access-Control-Allow-Origin: ' . $origin);
        header('Access-Control-Allow-Methods: GET, POST, OPTIONS');
        header('Access-Control-Allow-Headers: X-Observe-Token, Content-Type');
        header('Access-Control-Expose-Headers: Retry-After');
        header('Access-Control-Max-Age: 86400');
        if ($origin !== '*') {
            header('Vary: Origin');
        }
    }
}

/** @param array<string, mixed> $data */
function answer_json(int $status, array $data): void
{
    http_response_code($status);
    header('Content-Type: application/json');
    echo json_encode($data, JSON_UNESCAPED_SLASHES), "\n";
    exit;
}

function answer_bytes(string $bytes): void
{
    http_response_code(200);
    header('Content-Type: application/octet-stream');
    header('Content-Length: ' . strlen($bytes));
    echo $bytes;
    exit;
}

function answer_empty(): void
{
    http_response_code(204);
    exit;
}

function fail(int $status, string $text, array $headers = []): void
{
    throw new StoreError($status, $text, $headers);
}

// ---------------------------------------------------------------------------------------------------------------
// Bytes and envelopes

function b64url(string $bytes): string
{
    return rtrim(strtr(base64_encode($bytes), '+/', '-_'), '=');
}

/** The bytes of canonical base64url text without padding, or null. */
function unb64url(string $text): ?string
{
    if (!preg_match('~^[A-Za-z0-9_-]*$~D', $text) || strlen($text) % 4 === 1) {
        return null;
    }
    $bytes = base64_decode(strtr($text, '-_', '+/'), true);
    if ($bytes === false || b64url($bytes) !== $text) {
        return null;
    }
    return $bytes;
}

function plant_id_of(string $spki): string
{
    return b64url(substr(hash('sha256', $spki, true), 0, 16));
}

function valid_plant_id(string $id): bool
{
    if (!preg_match(ID_PATTERN, $id)) {
        return false;
    }
    $bytes = unb64url($id);
    return $bytes !== null && strlen($bytes) === 16;
}

/** Whether [name] is what the protocol allows for [kind]. */
function valid_name(int $kind, string $name): bool
{
    if (strlen($name) < 1 || strlen($name) > 64 || !preg_match('~^[A-Za-z0-9._-]+$~D', $name)) {
        return false;
    }
    switch ($kind) {
        case 0:
            return $name === 'register';
        case 1: // b<epoch second> or b<epoch second>.<device id>
            return preg_match('~^b[0-9]{1,12}(\.[A-Za-z0-9_-][A-Za-z0-9._-]*)?$~D', $name) === 1;
        case 2:
            return preg_match('~^d[0-9]{4}-[0-9]{2}-[0-9]{2}$~D', $name) === 1;
        case 3:
            return preg_match('~^e[A-Za-z0-9_-][A-Za-z0-9._-]*$~D', $name) === 1;
        case 4:
            return $name === 'live';
        case 5:
            return $name === 'plant';
        case 6:
            return $name === 'stores';
        case 7:
            return $name === 'delete';
        default:
            return false;
    }
}

/**
 * One envelope, parsed: kind, seq, name, payload, signed (the bytes the signature covers), signature, bytes.
 *
 * @return array<string, mixed>
 */
function parse_envelope(string $b): array
{
    global $config;
    $len = strlen($b);
    if ($len < 29 || substr($b, 0, 4) !== 'SDO1') {
        fail(400, 'not an envelope');
    }
    $kind = ord($b[4]);
    if ($kind > 7) {
        fail(400, 'unknown kind');
    }
    if ((ord($b[5]) & 0x80) !== 0) {
        fail(400, 'seq out of range');
    }
    $seq = unpack('J', substr($b, 5, 8))[1];
    $n = ord($b[21]);
    if ($n < 1 || $n > 64 || $len < 26 + $n + 2) {
        fail(400, 'bad name');
    }
    $name = substr($b, 22, $n);
    if (!valid_name($kind, $name)) {
        fail(400, 'bad name');
    }
    $p = unpack('N', substr($b, 22 + $n, 4))[1];
    if ($p > (int) $config['max_payload']) {
        fail(413, 'payload too large');
    }
    if ($len < 26 + $n + $p + 2) {
        fail(400, 'truncated envelope');
    }
    $s = unpack('n', substr($b, 26 + $n + $p, 2))[1];
    if ($len !== 28 + $n + $p + $s) {
        fail(400, 'envelope length mismatch');
    }
    return [
        'kind' => $kind,
        'seq' => $seq,
        'name' => $name,
        'payload' => substr($b, 26 + $n, $p),
        'signed' => substr($b, 0, 26 + $n + $p),
        'signature' => substr($b, 28 + $n + $p, $s),
        'bytes' => $b,
    ];
}

/**
 * Frames: each a 4-byte length, then an envelope.
 *
 * @return array<int, array<string, mixed>>
 */
function parse_frames(string $body): array
{
    $out = [];
    $at = 0;
    $len = strlen($body);
    while ($at < $len) {
        if ($len - $at < 4) {
            fail(400, 'truncated frame');
        }
        $n = unpack('N', substr($body, $at, 4))[1];
        $at += 4;
        if ($n > $len - $at) {
            fail(400, 'truncated frame');
        }
        $out[] = parse_envelope(substr($body, $at, $n));
        $at += $n;
    }
    return $out;
}

function spki_pem(string $spki): string
{
    return "-----BEGIN PUBLIC KEY-----\n" . chunk_split(base64_encode($spki), 64, "\n") . "-----END PUBLIC KEY-----\n";
}

/** Whether [spki] is a P-256 public key in the form the collector makes (uncompressed point, on the curve). */
function valid_spki(string $spki): bool
{
    if (strlen($spki) !== 91 || bin2hex(substr($spki, 0, 27)) !== P256_SPKI_PREFIX) {
        return false;
    }
    return @openssl_pkey_get_public(spki_pem($spki)) !== false;
}

/** @param array<string, mixed> $e */
function signature_ok(array $e, string $spki): bool
{
    return @openssl_verify($e['signed'], $e['signature'], spki_pem($spki), OPENSSL_ALGO_SHA256) === 1;
}

// ---------------------------------------------------------------------------------------------------------------
// Storage
//
// <data>/plants/<id>/key       the public key (SPKI DER)
//                    meta.json {"seq": register seq, "maxSeq": highest seq taken, "token": hex SHA-256(read token),
//                               "bytes": stored envelope bytes}
//                    log       one line "<seq> <kind> <name> <size>" per stored item (not live); the latest line
//                              of a kind and name counts, older ones are dropped when the log is rewritten
//                    uploaded  its time is the last upload
//                    watch     its time is the last observer watch
//                    items/<kind>_<name>  the stored envelopes
// <data>/locks/<id>            per-plant lock files
// <data>/rate/                 rate counters, named by keyed hashes (no addresses on disk)
// <data>/secret                key for those hashes

function data_dir(): string
{
    global $config;
    $dir = rtrim((string) $config['data_dir'], '/');
    if (!is_dir($dir)) {
        if (!@mkdir($dir, 0750, true) && !is_dir($dir)) {
            fail(500, 'storage not available');
        }
    }
    if (!is_file($dir . '/.htaccess')) {
        write_atomic(
            $dir . '/.htaccess',
            "# The store's data: never served.\n<IfModule mod_authz_core.c>\n    Require all denied\n</IfModule>\n"
            . "<IfModule !mod_authz_core.c>\n    Order allow,deny\n    Deny from all\n</IfModule>\n"
        );
    }
    return $dir;
}

function sub_dir(string $name): string
{
    $dir = data_dir() . '/' . $name;
    if (!is_dir($dir) && !@mkdir($dir, 0750, true) && !is_dir($dir)) {
        fail(500, 'storage not available');
    }
    return $dir;
}

/** The directory of a plant; [id] must have passed valid_plant_id. */
function plant_dir(string $id): string
{
    if (!valid_plant_id($id)) {
        fail(400, 'bad plant id');
    }
    return data_dir() . '/plants/' . $id;
}

function item_file(string $id, int $kind, string $name): string
{
    if (!valid_name($kind, $name)) {
        fail(400, 'bad name');
    }
    return plant_dir($id) . '/items/' . $kind . '_' . $name;
}

/** Writes a file whole or not at all: a temporary file in the same directory, then renamed. */
function write_atomic(string $path, string $data): void
{
    $tmp = dirname($path) . '/.tmp-' . bin2hex(random_bytes(8));
    if (@file_put_contents($tmp, $data) !== strlen($data) || !@rename($tmp, $path)) {
        @unlink($tmp);
        fail(500, 'could not write');
    }
    @chmod($path, 0640);
}

/** Holds the plant's lock until the returned handle is released or the request ends. @return resource */
function lock_plant(string $id, bool $shared = false)
{
    if (!valid_plant_id($id)) {
        fail(400, 'bad plant id');
    }
    $h = @fopen(sub_dir('locks') . '/' . $id, 'c');
    if ($h === false || !flock($h, $shared ? LOCK_SH : LOCK_EX)) {
        fail(500, 'could not lock');
    }
    return $h;
}

/** @param resource $h */
function unlock($h): void
{
    flock($h, LOCK_UN);
    fclose($h);
}

/** @return array<string, mixed>|null */
function read_meta(string $id): ?array
{
    $raw = @file_get_contents(plant_dir($id) . '/meta.json');
    if ($raw === false) {
        return null;
    }
    $meta = json_decode($raw, true);
    return is_array($meta) ? $meta : null;
}

/** @param array<string, mixed> $meta */
function write_meta(string $id, array $meta): void
{
    write_atomic(plant_dir($id) . '/meta.json', json_encode($meta, JSON_UNESCAPED_SLASHES));
}

function plant_exists(string $id): bool
{
    return is_file(plant_dir($id) . '/meta.json');
}

function remove_tree(string $dir): void
{
    if (!is_dir($dir) || is_link($dir)) {
        @unlink($dir);
        return;
    }
    foreach (scandir($dir) ?: [] as $f) {
        if ($f === '.' || $f === '..') {
            continue;
        }
        $path = $dir . '/' . $f;
        if (is_dir($path) && !is_link($path)) {
            remove_tree($path);
        } else {
            @unlink($path);
        }
    }
    @rmdir($dir);
}

/**
 * The plant's items (not live) from its log: "<kind> <name>" => [seq, kind, name, size].
 *
 * @return array<string, array<int, mixed>>
 */
function read_log(string $id, int &$lines = 0): array
{
    $items = [];
    $lines = 0;
    $h = @fopen(plant_dir($id) . '/log', 'r');
    if ($h === false) {
        return $items;
    }
    while (($line = fgets($h)) !== false) {
        $lines++;
        $f = explode(' ', rtrim($line, "\n"));
        if (count($f) !== 4) {
            continue;
        }
        $key = $f[1] . ' ' . $f[2];
        $seq = (int) $f[0];
        if (!isset($items[$key]) || $items[$key][0] < $seq) {
            $items[$key] = [$seq, (int) $f[1], $f[2], (int) $f[3]];
        }
    }
    fclose($h);
    return $items;
}

/** @param array<string, array<int, mixed>> $items */
function write_log(string $id, array $items): void
{
    $text = '';
    foreach ($items as $i) {
        $text .= $i[0] . ' ' . $i[1] . ' ' . $i[2] . ' ' . $i[3] . "\n";
    }
    write_atomic(plant_dir($id) . '/log', $text);
}

/** The seq of the stored envelope of [kind] and [name], or -1. */
function stored_seq(string $id, int $kind, string $name): int
{
    $h = @fopen(item_file($id, $kind, $name), 'r');
    if ($h === false) {
        return -1;
    }
    $head = fread($h, 13);
    fclose($h);
    if (!is_string($head) || strlen($head) !== 13) {
        return -1;
    }
    return unpack('J', substr($head, 5, 8))[1];
}

// ---------------------------------------------------------------------------------------------------------------
// Rate limits and retention

function secret(): string
{
    static $secret = null;
    if ($secret === null) {
        $file = data_dir() . '/secret';
        $secret = @file_get_contents($file);
        if (!is_string($secret) || strlen($secret) < 32) {
            $secret = bin2hex(random_bytes(32));
            write_atomic($file, $secret);
        }
    }
    return $secret;
}

/** The client address, only ever used hashed. */
function client(): string
{
    return (string) ($_SERVER['REMOTE_ADDR'] ?? 'unknown');
}

/** Counts one request of [scope]/[key] in the current window; 429 when [limit] are used up. */
function rate_limit(string $scope, string $key, int $limit, int $window): void
{
    if ($limit <= 0) {
        return;
    }
    $now = time();
    $slot = intdiv($now, $window);
    $file = sub_dir('rate') . '/' . hash_hmac('sha256', $scope . '|' . $key . '|' . $slot, secret());
    $h = @fopen($file, 'c+');
    if ($h === false || !flock($h, LOCK_EX)) {
        fail(500, 'could not count');
    }
    $count = (int) stream_get_contents($h);
    if ($count >= $limit) {
        unlock($h);
        fail(429, 'too many requests', ['Retry-After' => (string) max(1, ($slot + 1) * $window - $now)]);
    }
    ftruncate($h, 0);
    rewind($h);
    fwrite($h, (string) ($count + 1));
    fflush($h);
    unlock($h);
}

function retention_due(): bool
{
    global $config;
    $chance = (float) $config['retention_chance'];
    return $chance > 0 && mt_rand() / mt_getrandmax() < $chance;
}

/** Deletes idle plants and old rate counters. Cheap: one stat per plant. */
function sweep_plants(): void
{
    global $config;
    $idle = time() - (int) $config['plant_idle_days'] * 86400;
    foreach (glob(data_dir() . '/plants/*', GLOB_ONLYDIR) ?: [] as $dir) {
        $id = basename($dir);
        if (!valid_plant_id($id)) {
            continue;
        }
        $up = @filemtime($dir . '/uploaded');
        if ($up !== false && $up < $idle) {
            $lock = lock_plant($id);
            clearstatcache();
            $up = @filemtime($dir . '/uploaded');
            if ($up !== false && $up < $idle) {
                remove_tree($dir);
            }
            unlock($lock);
        }
    }
    foreach (glob(data_dir() . '/locks/*') ?: [] as $f) {
        $id = basename($f);
        if (valid_plant_id($id) && !is_dir(data_dir() . '/plants/' . $id) && @filemtime($f) < time() - 3600) {
            @unlink($f);
        }
    }
    foreach (glob(data_dir() . '/rate/*') ?: [] as $f) {
        if (@filemtime($f) < time() - 2 * 86400) {
            @unlink($f);
        }
    }
}

/** Deletes a plant's blocks and days older than keep_days; the caller holds the plant's lock. */
function prune_plant(string $id): void
{
    global $config;
    $cut = time() - (int) $config['keep_days'] * 86400;
    $cutDay = gmdate('Y-m-d', $cut - 86400); // days are the plant's local date: a day of slack
    $lines = 0;
    $items = read_log($id, $lines);
    $changed = $lines > 2 * count($items) + 1000; // lines of replaced items pile up: rewrite the log then
    foreach ($items as $key => $i) {
        $old = false;
        if ($i[1] === 1 && preg_match('~^b([0-9]+)~', $i[2], $m)) {
            $old = (int) $m[1] < $cut;
        } elseif ($i[1] === 2) {
            $old = strcmp(substr($i[2], 1), $cutDay) < 0;
        }
        if ($old) {
            @unlink(item_file($id, $i[1], $i[2]));
            unset($items[$key]);
            $changed = true;
        }
    }
    if ($changed) {
        write_log($id, $items);
    }
    if ($changed || mt_rand(0, 99) === 0) { // now and then recount, so the tally can't drift
        $meta = read_meta($id);
        if ($meta !== null) {
            $bytes = 0;
            foreach ($items as $i) {
                $bytes += $i[3];
            }
            $live = @filesize(item_file($id, KIND_LIVE, 'live'));
            $meta['bytes'] = $bytes + ($live === false ? 0 : $live);
            write_meta($id, $meta);
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Requests

function read_body(): string
{
    global $config;
    $max = (int) $config['max_body'];
    $length = $_SERVER['CONTENT_LENGTH'] ?? $_SERVER['HTTP_CONTENT_LENGTH'] ?? '';
    if ($length !== '' && (int) $length > $max) {
        fail(413, 'body too large');
    }
    $body = file_get_contents('php://input', false, null, 0, $max + 1);
    if (!is_string($body)) {
        fail(400, 'no body');
    }
    if (strlen($body) > $max) {
        fail(413, 'body too large');
    }
    return $body;
}

function collector_limit(string $id): void
{
    global $config;
    rate_limit('collector', $id, (int) $config['collector_per_minute'], 60);
}

/** 404 unless the plant is known. */
function require_plant(string $id): void
{
    if (!plant_exists($id)) {
        fail(404, 'no such plant');
    }
}

function touch_uploaded(string $id): void
{
    @touch(plant_dir($id) . '/uploaded');
}

function route_register(string $id): void
{
    global $config;
    collector_limit($id);
    $e = parse_envelope(read_body());
    if ($e['kind'] !== KIND_REGISTER || strlen($e['payload']) !== 91 + 32) {
        fail(400, 'not a register envelope');
    }
    $spki = substr($e['payload'], 0, 91);
    $tokenHash = bin2hex(substr($e['payload'], 91, 32));
    if (!valid_spki($spki)) {
        fail(400, 'not a P-256 public key');
    }
    if (!hash_equals($id, plant_id_of($spki))) {
        fail(403, 'the id is not this key\'s');
    }
    if (!signature_ok($e, $spki)) {
        fail(403, 'bad signature');
    }
    $lock = lock_plant($id);
    $dir = plant_dir($id);
    $meta = read_meta($id);
    if ($meta !== null) {
        if (!hash_equals((string) @file_get_contents($dir . '/key'), $spki)) {
            fail(409, 'plant id taken by another key');
        }
        if ($e['seq'] > (int) $meta['seq']) { // New code; older or equal: a retry or a replay, changes nothing
            $meta['seq'] = $e['seq'];
            $meta['token'] = $tokenHash;
            $meta['maxSeq'] = max((int) $meta['maxSeq'], $e['seq']);
            write_meta($id, $meta);
        }
    } else {
        rate_limit('new', client(), (int) $config['new_plants_per_day'], 86400);
        if (!is_dir($dir . '/items') && !@mkdir($dir . '/items', 0750, true) && !is_dir($dir . '/items')) {
            fail(500, 'storage not available');
        }
        write_atomic($dir . '/key', $spki);
        write_meta($id, ['seq' => $e['seq'], 'maxSeq' => $e['seq'], 'token' => $tokenHash, 'bytes' => 0]);
    }
    touch_uploaded($id);
    unlock($lock);
    answer_json(200, ['ok' => true]);
}

function route_items(string $id): void
{
    global $config;
    collector_limit($id);
    require_plant($id);
    $envelopes = parse_frames(read_body());
    if (count($envelopes) === 0) {
        fail(400, 'no envelopes');
    }
    foreach ($envelopes as $e) {
        if ($e['kind'] < 1 || $e['kind'] > 6) {
            fail(400, 'kind not allowed here');
        }
    }
    $lock = lock_plant($id);
    $meta = read_meta($id);
    if ($meta === null) {
        fail(404, 'no such plant');
    }
    $spki = (string) @file_get_contents(plant_dir($id) . '/key');
    $pem = spki_pem($spki);
    foreach ($envelopes as $e) {
        if (@openssl_verify($e['signed'], $e['signature'], $pem, OPENSSL_ALGO_SHA256) !== 1) {
            fail(403, 'bad signature');
        }
    }
    // What gets stored: per kind and name the highest seq above the stored one.
    $take = [];
    $skipped = 0;
    $bytes = (int) $meta['bytes'];
    foreach ($envelopes as $e) {
        $key = $e['kind'] . '_' . $e['name'];
        $current = isset($take[$key]) ? $take[$key]['seq'] : stored_seq($id, $e['kind'], $e['name']);
        if ($e['seq'] <= $current) {
            $skipped++;
            continue;
        }
        if (isset($take[$key])) {
            $skipped++; // replaced within this request
            $bytes -= strlen($take[$key]['bytes']);
        } else {
            $old = @filesize(item_file($id, $e['kind'], $e['name']));
            $bytes -= $old === false ? 0 : $old;
        }
        $bytes += strlen($e['bytes']);
        $take[$key] = $e;
    }
    if ($bytes > (int) $config['max_plant_bytes']) {
        fail(413, 'plant storage full');
    }
    $log = '';
    foreach ($take as $e) {
        write_atomic(item_file($id, $e['kind'], $e['name']), $e['bytes']);
        if ($e['kind'] !== KIND_LIVE) {
            $log .= $e['seq'] . ' ' . $e['kind'] . ' ' . $e['name'] . ' ' . strlen($e['bytes']) . "\n";
        }
        $meta['maxSeq'] = max((int) $meta['maxSeq'], $e['seq']);
    }
    if ($log !== '' && @file_put_contents(plant_dir($id) . '/log', $log, FILE_APPEND) !== strlen($log)) {
        fail(500, 'could not write');
    }
    $meta['bytes'] = $bytes;
    write_meta($id, $meta);
    touch_uploaded($id);
    if (retention_due()) {
        prune_plant($id);
    }
    unlock($lock);
    answer_json(200, ['stored' => count($take), 'skipped' => $skipped]);
}

function route_collector_watch(string $id): void
{
    global $config;
    collector_limit($id);
    require_plant($id);
    clearstatcache();
    $t = @filemtime(plant_dir($id) . '/watch');
    answer_json(200, ['watching' => $t !== false && time() - $t < (int) $config['watch_seconds']]);
}

function route_delete(string $id): void
{
    collector_limit($id);
    require_plant($id);
    $e = parse_envelope(read_body());
    if ($e['kind'] !== KIND_DELETE || $e['payload'] !== '') {
        fail(400, 'not a delete envelope');
    }
    $lock = lock_plant($id);
    $meta = read_meta($id);
    if ($meta === null) {
        fail(404, 'no such plant');
    }
    if (!signature_ok($e, (string) @file_get_contents(plant_dir($id) . '/key'))) {
        fail(403, 'bad signature');
    }
    if ($e['seq'] <= (int) $meta['maxSeq']) {
        fail(409, 'stale delete: seq not above the plant\'s latest');
    }
    remove_tree(plant_dir($id));
    unlock($lock);
    answer_json(200, ['ok' => true]);
}

/** Rate limit and read token; an unknown plant and a wrong token fail alike. */
function observer_auth(string $id): void
{
    global $config;
    rate_limit('observer', client(), (int) $config['observer_per_minute'], 60);
    $token = unb64url((string) ($_SERVER['HTTP_X_OBSERVE_TOKEN'] ?? ''));
    $given = hash('sha256', $token === null ? '' : $token);
    $meta = read_meta($id);
    $stored = $meta !== null && is_string($meta['token'] ?? null) ? $meta['token'] : hash('sha256', random_bytes(16));
    $ok = hash_equals($stored, $given);
    if (!$ok || $meta === null || $token === null || strlen($token) !== 16) {
        fail(403, 'forbidden');
    }
}

function route_key(string $id): void
{
    observer_auth($id);
    answer_bytes((string) @file_get_contents(plant_dir($id) . '/key'));
}

function route_index(string $id): void
{
    global $config;
    observer_auth($id);
    $since = (string) ($_GET['since'] ?? '0');
    if (!preg_match('~^[0-9]{1,19}$~D', $since) || (strlen($since) === 19 && strcmp($since, '9223372036854775807') > 0)) {
        fail(400, 'bad since');
    }
    $since = (int) $since;
    $lock = lock_plant($id, true);
    $all = read_log($id);
    unlock($lock);
    $items = [];
    foreach ($all as $i) {
        if ($i[0] > $since) {
            $items[] = $i;
        }
    }
    usort($items, function (array $a, array $b): int {
        return $a[0] <=> $b[0];
    });
    $page = max(1, (int) $config['index_page']);
    $out = [];
    foreach (array_slice($items, 0, $page) as $i) {
        $out[] = [$i[1], $i[2], $i[0]];
    }
    answer_json(200, ['items' => $out, 'more' => count($items) > $page]);
}

function route_fetch(string $id): void
{
    global $config;
    observer_auth($id);
    $body = read_body();
    $list = json_decode($body); // objects stay objects: only JSON arrays pass is_array
    if (!is_array($list)) {
        fail(400, 'expected [[kind, name], ...]');
    }
    if (count($list) > (int) $config['fetch_max']) {
        fail(413, 'too many items');
    }
    $files = [];
    foreach ($list as $entry) {
        if (!is_array($entry) || count($entry) !== 2
            || !is_int($entry[0]) || !is_string($entry[1]) || $entry[0] < 1 || $entry[0] > 6
            || !valid_name($entry[0], $entry[1])) {
            fail(400, 'expected [[kind, name], ...]');
        }
        $files[] = item_file($id, $entry[0], $entry[1]);
    }
    $out = '';
    foreach ($files as $f) {
        $bytes = @file_get_contents($f);
        if (is_string($bytes) && $bytes !== '') {
            $out .= pack('N', strlen($bytes)) . $bytes;
        }
    }
    answer_bytes($out);
}

function route_live(string $id): void
{
    observer_auth($id);
    $bytes = @file_get_contents(item_file($id, KIND_LIVE, 'live'));
    if (!is_string($bytes) || $bytes === '') {
        answer_empty();
    }
    answer_bytes($bytes);
}

function route_observer_watch(string $id): void
{
    observer_auth($id);
    @touch(plant_dir($id) . '/watch');
    answer_empty();
}

function handle(): void
{
    $method = (string) ($_SERVER['REQUEST_METHOD'] ?? 'GET');
    if ($method === 'OPTIONS') {
        answer_empty();
    }
    $route = $_GET['r'] ?? ($_SERVER['PATH_INFO'] ?? '');
    if (!is_string($route) || !preg_match('~^/v1/(plants|observe)/([^/]*)(?:/([a-z]+))?$~D', $route, $m)) {
        fail(404, 'no such route');
    }
    $id = $m[2];
    $action = $m[1] . '/' . ($m[3] ?? '');
    $routes = [
        'plants/' => ['POST', 'route_register'],
        'plants/items' => ['POST', 'route_items'],
        'plants/watch' => ['GET', 'route_collector_watch'],
        'plants/delete' => ['POST', 'route_delete'],
        'observe/key' => ['GET', 'route_key'],
        'observe/index' => ['GET', 'route_index'],
        'observe/fetch' => ['POST', 'route_fetch'],
        'observe/live' => ['GET', 'route_live'],
        'observe/watch' => ['POST', 'route_observer_watch'],
    ];
    if (!isset($routes[$action])) {
        fail(404, 'no such route');
    }
    if (!valid_plant_id($id)) {
        fail(400, 'bad plant id');
    }
    if ($method !== $routes[$action][0]) {
        fail(405, 'method not allowed', ['Allow' => $routes[$action][0] . ', OPTIONS']);
    }
    if (retention_due()) {
        sweep_plants();
    }
    $routes[$action][1]($id);
}

common_headers();
try {
    handle();
} catch (StoreError $e) {
    foreach ($e->headers as $name => $value) {
        header($name . ': ' . $value);
    }
    answer_json($e->getCode(), ['error' => $e->getMessage()]);
} catch (Throwable $e) {
    error_log('store.php: ' . $e->getMessage() . ' at ' . $e->getFile() . ':' . $e->getLine());
    answer_json(500, ['error' => 'internal error']);
}
