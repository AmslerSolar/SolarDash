// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH
//
// Self-test of app.js's pure functions against store/tests/vectors.json. Writes PASS/FAIL lines into #out and
// "ALL PASS n" or "FAILED k of n" into #summary. Needs a secure context (http://127.0.0.1 or https).

(function () {
    'use strict';
    const SD = window.SolarDashObserver;
    const out = document.getElementById('out');
    const results = [];

    function line(text) { out.appendChild(document.createTextNode(text + '\n')); }

    async function test(name, fn) {
        try {
            await fn();
            results.push(true);
            line('PASS ' + name);
        } catch (e) {
            results.push(false);
            line('FAIL ' + name + ': ' + (e && e.message ? e.message : e));
        }
    }

    function assert(cond, msg) { if (!cond) throw new Error(msg || 'assertion failed'); }
    function eq(a, b, msg) { if (a !== b) throw new Error((msg || 'expected equal') + ': ' + String(a) + ' !== ' + String(b)); }
    async function rejects(p, type, msg) {
        try { await p; } catch (e) {
            if (type && !(e instanceof type)) throw new Error((msg || 'wrong error') + ': ' + e);
            return;
        }
        throw new Error(msg || 'expected a failure');
    }
    function throws(fn, type, msg) {
        try { fn(); } catch (e) {
            if (type && !(e instanceof type)) throw new Error((msg || 'wrong error') + ': ' + e);
            return;
        }
        throw new Error(msg || 'expected a failure');
    }
    const hex = (b) => Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
    const utf8 = (s) => new TextEncoder().encode(s);

    // An independent raw (r ‖ s) → DER encoder for the round trips.
    function rawToDer(raw) {
        const int = (x) => {
            let i = 0;
            while (i < x.length - 1 && x[i] === 0) i++;
            let v = x.subarray(i);
            if (v[0] & 0x80) v = SD.concat(new Uint8Array([0]), v);
            return SD.concat(new Uint8Array([0x02, v.length]), v);
        };
        const body = SD.concat(int(raw.subarray(0, 32)), int(raw.subarray(32)));
        const len = body.length < 0x80 ? new Uint8Array([body.length]) : new Uint8Array([0x81, body.length]);
        return SD.concat(new Uint8Array([0x30]), len, body);
    }

    async function deflate(bytes) {
        const s = new Blob([bytes]).stream().pipeThrough(new CompressionStream('deflate'));
        return new Uint8Array(await new Response(s).arrayBuffer());
    }

    function fakeResponse(status, body, type) {
        return new Response(status === 204 ? null : body, { status, headers: { 'Content-Type': type || 'application/octet-stream' } });
    }

    function frames(envs) {
        const parts = [];
        for (const e of envs) {
            const len = new Uint8Array(4);
            new DataView(len.buffer).setUint32(0, e.length);
            parts.push(len, e);
        }
        return SD.concat(...parts);
    }

    async function loadVectors() {
        for (const url of ['../../tests/vectors.json', 'test-vectors.json']) {
            try {
                const r = await fetch(url, { cache: 'no-store' });
                if (r.ok) return await r.json();
            } catch (e) { /* next */ }
        }
        throw new Error('vectors.json not found');
    }

    async function run() {
        if (!SD) { line('FAIL app.js did not load'); results.push(false); return; }
        if (!window.isSecureContext || !crypto.subtle) {
            line('FAIL not a secure context: WebCrypto unavailable');
            results.push(false);
            return;
        }
        const V = await loadVectors();
        const spki = SD.b64urlDecode(V.publicKey);
        const publicKey = await SD.importPublicKey(spki);
        const dataKey = await SD.importDataKey(SD.b64urlDecode(V.dataKey));
        const byName = {};
        for (const e of V.envelopes) byName[e.name] = { ...e, bytes: SD.b64urlDecode(e.envelope) };

        // -------------------------------------------------------------------------------- base64url
        await test('b64url round trip of every length 0..40', () => {
            for (let n = 0; n <= 40; n++) {
                const b = crypto.getRandomValues(new Uint8Array(n));
                const s = SD.b64urlEncode(b);
                assert(!/[=+/]/.test(s), 'padding or non-url characters in ' + s);
                assert(SD.bytesEqual(SD.b64urlDecode(s), b), 'round trip ' + n);
            }
        });
        await test('b64url rejects padding, +/, bad length, non-canonical', () => {
            throws(() => SD.b64urlDecode('QQ=='), SD.FormatError);
            throws(() => SD.b64urlDecode('a+b/'), SD.FormatError);
            throws(() => SD.b64urlDecode('abcde'), SD.FormatError);
            throws(() => SD.b64urlDecode('QR'), SD.FormatError); // 'QR' has stray bits: canonical is 'QQ'
            throws(() => SD.b64urlDecode('ab cd'), SD.FormatError);
            eq(hex(SD.b64urlDecode('_-8')), 'ffef', 'url alphabet');
        });

        // -------------------------------------------------------------------------------- ids
        await test('plant id = b64url(SHA-256(SPKI)[0..16])', async () => {
            eq(await SD.plantIdOf(spki), V.plantId);
            eq(V.plantId.length, 22);
        });
        await test('plant id of another key differs', async () => {
            const other = spki.slice();
            other[other.length - 1] ^= 1;
            assert((await SD.plantIdOf(other)) !== V.plantId);
        });

        // -------------------------------------------------------------------------------- envelopes
        await test('parse every vector envelope (kind, name, seq, time)', () => {
            for (const e of V.envelopes) {
                const env = SD.parseEnvelope(SD.b64urlDecode(e.envelope));
                eq(env.kind, e.kind, e.name + ' kind');
                eq(env.name, e.name, e.name + ' name');
                eq(env.seq, BigInt(e.seq), e.name + ' seq');
                eq(env.time, 1762250000000, e.name + ' time');
                eq(env.header.length, 22 + e.name.length, e.name + ' header length');
                eq(env.signed.length + 2 + env.signature.length, env.header.length + 4 + env.payload.length + 2 + env.signature.length);
            }
        });
        await test('register payload = SPKI ‖ SHA-256(read token)', async () => {
            const env = SD.parseEnvelope(byName.register.bytes);
            const th = await SD.sha256(SD.b64urlDecode(V.readToken));
            assert(SD.bytesEqual(env.payload, SD.concat(spki, th)));
        });
        await test('parse rejects bad magic, kind, name, truncation, trailing bytes', () => {
            const b = byName.plant.bytes;
            const m = b.slice(); m[0] = 0x58;
            throws(() => SD.parseEnvelope(m), SD.FormatError, 'magic');
            const k = b.slice(); k[4] = 9;
            throws(() => SD.parseEnvelope(k), SD.FormatError, 'kind');
            const n = b.slice(); n[22] = 0x2f; // '/' in the name
            throws(() => SD.parseEnvelope(n), SD.FormatError, 'name');
            const z = b.slice(); z[21] = 0;
            throws(() => SD.parseEnvelope(z), SD.FormatError, 'empty name');
            for (const cut of [0, 10, 27, 30, b.length - 1]) throws(() => SD.parseEnvelope(b.subarray(0, cut)), SD.FormatError, 'truncated at ' + cut);
            throws(() => SD.parseEnvelope(SD.concat(b, new Uint8Array([0]))), SD.FormatError, 'trailing');
            const big = b.slice(); new DataView(big.buffer).setUint32(22 + 5, 262145);
            throws(() => SD.parseEnvelope(big), SD.FormatError, 'payload limit');
        });
        await test('frames: two envelopes, truncated frame rejected', () => {
            const f = frames([byName.plant.bytes, byName.live.bytes]);
            const envs = SD.parseFrames(f);
            eq(envs.length, 2);
            eq(envs[0].name, 'plant');
            eq(envs[1].name, 'live');
            eq(SD.parseFrames(new Uint8Array(0)).length, 0);
            throws(() => SD.parseFrames(f.subarray(0, f.length - 1)), SD.FormatError);
            throws(() => SD.parseFrames(SD.concat(f, new Uint8Array([0, 0]))), SD.FormatError);
        });

        // -------------------------------------------------------------------------------- signatures
        await test('DER → raw for every vector signature (lengths 70–72)', () => {
            for (const e of V.envelopes) {
                const env = SD.parseEnvelope(SD.b64urlDecode(e.envelope));
                const raw = SD.derToRaw(env.signature);
                eq(raw.length, 64);
                assert(SD.bytesEqual(rawToDer(raw), env.signature), e.name + ': re-encoding differs');
            }
        });
        await test('verify every vector envelope', async () => {
            for (const e of V.envelopes) {
                const env = SD.parseEnvelope(SD.b64urlDecode(e.envelope));
                assert(await SD.verifyEnvelope(env, publicKey), e.name + ' does not verify');
            }
        });
        await test('verify fails on a changed byte in header, payload or signature', async () => {
            const b = byName.live.bytes;
            const env = SD.parseEnvelope(b);
            const spots = [5, 15, 23, 22 + 4 + 4 + 2, env.signed.length - 1, b.length - 1, b.length - 20];
            for (const at of spots) {
                const c = b.slice();
                c[at] ^= 0x01;
                let ok;
                try { ok = await SD.verifyEnvelope(SD.parseEnvelope(c), publicKey); } catch (x) { ok = false; }
                assert(!ok, 'still verifies with byte ' + at + ' changed');
            }
        });
        await test('verify fails with another key', async () => {
            const k = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']);
            assert(!(await SD.verifyEnvelope(SD.parseEnvelope(byName.plant.bytes), k.publicKey)));
        });
        await test('DER ↔ raw: 300 WebCrypto signatures incl. short and high-bit r/s', async () => {
            const k = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign', 'verify']);
            let short = 0, high = 0;
            for (let i = 0; i < 300; i++) {
                const msg = crypto.getRandomValues(new Uint8Array(20));
                const raw = new Uint8Array(await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, k.privateKey, msg));
                const der = rawToDer(raw);
                if (raw[0] & 0x80 || raw[32] & 0x80) high++;
                if (raw[0] === 0 || raw[32] === 0) short++;
                const back = SD.derToRaw(der);
                assert(SD.bytesEqual(back, raw), 'round trip ' + i);
                assert(await crypto.subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, k.publicKey, back, msg), 'verify ' + i);
            }
            assert(high > 0, 'no high-bit case seen');
            line('     (' + high + ' with a sign byte, ' + short + ' with a short integer)');
        });
        await test('DER → raw: constructed short and padded integers', () => {
            const raw = new Uint8Array(64);
            raw[31] = 1;                 // r = 1: one byte
            raw.fill(0xff, 32);          // s = all ones: needs the 0x00 sign byte
            const der = rawToDer(raw);
            eq(hex(der.subarray(0, 8)), '3026020101022100', 'encoding');
            assert(SD.bytesEqual(SD.derToRaw(der), raw));
            const raw2 = new Uint8Array(64);
            raw2.set([0, 0, 0x7f], 0); raw2.fill(0x11, 3, 32); raw2.fill(0x22, 32);
            assert(SD.bytesEqual(SD.derToRaw(rawToDer(raw2)), raw2), 'leading zero bytes in r');
        });
        await test('DER → raw rejects malformed signatures', () => {
            const good = rawToDer(new Uint8Array(64).fill(0x42));
            const bad = [
                ['wrong sequence tag', (d) => { d[0] = 0x31; }],
                ['wrong length', (d) => { d[1]++; }],
                ['wrong integer tag', (d) => { d[2] = 0x03; }],
                ['negative integer', (d) => { d[4] = 0x82; }],
            ];
            for (const [name, f] of bad) { const d = good.slice(); f(d); throws(() => SD.derToRaw(d), SD.FormatError, name); }
            throws(() => SD.derToRaw(SD.concat(good, new Uint8Array([0]))), SD.FormatError, 'trailing');
            // 33 bytes without a sign byte: r too large
            const r = SD.concat(new Uint8Array([0x02, 33, 0x01]), new Uint8Array(32).fill(1));
            const s = new Uint8Array([0x02, 1, 1]);
            throws(() => SD.derToRaw(SD.concat(new Uint8Array([0x30, r.length + s.length]), r, s)), SD.FormatError, 'too large');
            // non-minimal: 0x00 before a byte without the high bit
            const r2 = new Uint8Array([0x02, 2, 0x00, 0x01]);
            throws(() => SD.derToRaw(SD.concat(new Uint8Array([0x30, r2.length + s.length]), r2, s)), SD.FormatError, 'non-minimal');
            throws(() => SD.derToRaw(new Uint8Array(0)), SD.FormatError, 'empty');
        });

        // -------------------------------------------------------------------------------- sealing
        await test('open plant: content as in the vectors', async () => {
            const c = await SD.openEnvelope(SD.parseEnvelope(byName.plant.bytes), dataKey);
            eq(new TextDecoder().decode(c), byName.plant.content);
            eq(SD.parseJson(c).name, 'Home');
        });
        await test('open live: content as in the vectors', async () => {
            const c = await SD.openEnvelope(SD.parseEnvelope(byName.live.bytes), dataKey);
            eq(new TextDecoder().decode(c), byName.live.content);
            eq(SD.parseJson(c).pv, 3200);
        });
        await test('open block: bytes as in the vectors', async () => {
            const c = await SD.openEnvelope(SD.parseEnvelope(byName.b1762250400.bytes), dataKey);
            eq(hex(c), hex(SD.b64urlDecode(byName.b1762250400.block)));
        });
        await test('open fails with another data key', async () => {
            const other = await SD.importDataKey(crypto.getRandomValues(new Uint8Array(32)));
            await rejects(SD.openEnvelope(SD.parseEnvelope(byName.plant.bytes), other), SD.FormatError);
        });
        await test('open fails when the header (AAD) differs', async () => {
            const env = SD.parseEnvelope(byName.plant.bytes);
            const header = env.header.slice();
            header[12] ^= 1; // seq
            await rejects(SD.openEnvelope({ ...env, header }, dataKey), SD.FormatError, 'seq changed');
            const header2 = env.header.slice();
            header2[4] = 4; // kind
            await rejects(SD.openEnvelope({ ...env, kind: 4, header: header2 }, dataKey), SD.FormatError, 'kind changed');
        });
        await test('open refuses unsealed kinds and short payloads', async () => {
            await rejects(SD.openEnvelope(SD.parseEnvelope(byName.register.bytes), dataKey), SD.FormatError, 'register');
            await rejects(SD.openEnvelope(SD.parseEnvelope(byName.delete.bytes), dataKey), SD.FormatError, 'delete');
            const env = SD.parseEnvelope(byName.plant.bytes);
            await rejects(SD.openEnvelope({ ...env, payload: env.payload.subarray(0, 27) }, dataKey), SD.FormatError, 'short');
        });
        await test('inflate: zlib round trip, size limit, broken data', async () => {
            const data = utf8('x'.repeat(5000) + 'SolarDash');
            const z = await deflate(data);
            assert(SD.bytesEqual(await SD.inflate(z), data), 'round trip');
            await rejects(SD.inflate(z, 4000), SD.FormatError, 'limit');
            await rejects(SD.inflate(z.subarray(0, z.length - 3)), SD.FormatError, 'truncated');
            await rejects(SD.inflate(utf8('not zlib at all')), SD.FormatError, 'garbage');
        });

        // -------------------------------------------------------------------------------- observer checks
        const ctx = () => ({ publicKey, dataKey, seqs: new SD.SeqTracker() });
        await test('accept: requested kind and name, signature, then content', async () => {
            const c = await SD.acceptEnvelope(SD.parseEnvelope(byName.live.bytes), { kind: 4, name: 'live' }, ctx());
            eq(SD.parseJson(c).t, 1762250000000);
        });
        await test('accept rejects an envelope that was not asked for', async () => {
            await rejects(SD.acceptEnvelope(SD.parseEnvelope(byName.live.bytes), { kind: 5, name: 'plant' }, ctx()), SD.DataError, 'kind');
            await rejects(SD.acceptEnvelope(SD.parseEnvelope(byName.b1762250400.bytes), { kind: 1, name: 'b1762254000' }, ctx()), SD.DataError, 'name');
        });
        await test('accept rejects a bad signature', async () => {
            const b = byName.plant.bytes.slice();
            b[b.length - 5] ^= 4;
            await rejects(SD.acceptEnvelope(SD.parseEnvelope(b), { kind: 5, name: 'plant' }, ctx()), SD.DataError);
        });
        await test('seq: same or older envelope ignored, newer accepted', async () => {
            const c = ctx();
            const env = SD.parseEnvelope(byName.live.bytes);
            assert(await SD.acceptEnvelope(env, { kind: 4, name: 'live' }, c), 'first');
            eq(await SD.acceptEnvelope(env, { kind: 4, name: 'live' }, c), null, 'replay');
            const t = new SD.SeqTracker();
            assert(t.offer(4, 'live', 5n));
            assert(!t.offer(4, 'live', 4n), 'older');
            assert(!t.offer(4, 'live', 5n), 'same');
            assert(t.offer(4, 'live', 6n), 'newer');
            assert(t.offer(5, 'live', 1n), 'per kind');
            assert(t.offer(4, 'b1', 1n), 'per name');
        });

        // -------------------------------------------------------------------------------- block
        await test('block decode: header, pv 1000..1059 W, soc 64 % for 30 slots', async () => {
            const bl = SD.decodeBlock(SD.b64urlDecode(byName.b1762250400.block));
            eq(bl.start, 1762250400);
            eq(bl.interval, 60);
            eq(bl.slots, 60);
            eq(bl.channels.length, 2);
            const [pv, soc] = bl.channels;
            eq(pv.name, 'pv'); eq(pv.exponent, 0);
            eq(soc.name, 'soc');
            for (let i = 0; i < 60; i++) eq(pv.values[i], 1000 + i, 'pv slot ' + i);
            for (let i = 0; i < 60; i++) eq(soc.values[i], i < 30 ? 64 : null, 'soc slot ' + i);
        });
        await test('block decode: negative exponent, negative deltas, gaps', () => {
            // version 1, start 3600, interval 60, slots 4, one channel "v" exponent -1, presence 0b1011, values
            // 230.5, 229.9, (gap), 231.0 → 2305, -6, +11 → zigzag 4610, 11, 22
            const b = SD.concat(
                new Uint8Array([1, 0, 0, 0x0e, 0x10, 0, 60, 0, 4, 0, 1, 1, 0x76, 0xff, 0b1011]),
                new Uint8Array([0x82, 0x24, 11, 22]));
            const bl = SD.decodeBlock(b);
            const v = bl.channels[0].values;
            eq(v[0], 230.5); eq(v[1], 229.9); eq(v[2], null); eq(v[3], 231);
        });
        await test('block decode rejects bad version, truncation, trailing bytes', () => {
            const good = SD.b64urlDecode(byName.b1762250400.block);
            const v = good.slice(); v[0] = 2;
            throws(() => SD.decodeBlock(v), SD.FormatError, 'version');
            throws(() => SD.decodeBlock(good.subarray(0, good.length - 1)), SD.FormatError, 'truncated');
            throws(() => SD.decodeBlock(SD.concat(good, new Uint8Array([0]))), SD.FormatError, 'trailing');
        });

        // -------------------------------------------------------------------------------- link
        const storesB64 = (list) => SD.b64urlEncode(utf8(JSON.stringify(list)));
        const store = 'https://amsler-solar.ch/solardash/store.php';
        const fragment = [V.plantId, V.readToken, V.dataKey, storesB64([store, 'https://example.org/s/store.php'])].join('.');
        await test('link: parses the four parts', () => {
            const l = SD.parseLink('#' + fragment);
            eq(l.plantId, V.plantId);
            eq(l.token, V.readToken);
            eq(hex(l.dataKey), hex(SD.b64urlDecode(V.dataKey)));
            eq(l.stores.length, 2);
            eq(l.stores[0], store);
            eq(l.fragment, fragment);
        });
        await test('link: rejects wrong part count, sizes and encodings', () => {
            const bad = [
                '', '#', V.plantId, [V.plantId, V.readToken, V.dataKey].join('.'),
                fragment + '.x',
                ['AAAA', V.readToken, V.dataKey, storesB64([store])].join('.'),
                [V.plantId, 'AAAA', V.dataKey, storesB64([store])].join('.'),
                [V.plantId, V.readToken, V.readToken, storesB64([store])].join('.'),
                [V.plantId, V.readToken, V.dataKey, 'notjson'].join('.'),
                [V.plantId, V.readToken, V.dataKey, storesB64({ a: store })].join('.'),
                [V.plantId, V.readToken, V.dataKey, storesB64([])].join('.'),
                [V.plantId, V.readToken + '=', V.dataKey, storesB64([store])].join('.'),
            ];
            for (const b of bad) throws(() => SD.parseLink('#' + b), SD.LinkError, 'accepted ' + b.slice(0, 40));
        });
        await test('link: stores must be https (http only to this machine), no credentials', () => {
            const only = (list) => SD.parseLink('#' + [V.plantId, V.readToken, V.dataKey, storesB64(list)].join('.')).stores;
            throws(() => only(['http://amsler-solar.ch/store.php']), SD.LinkError, 'plain http');
            throws(() => only(['javascript:alert(1)']), SD.LinkError, 'javascript:');
            throws(() => only(['https://user:pw@amsler-solar.ch/store.php']), SD.LinkError, 'credentials');
            throws(() => only([42]), SD.LinkError, 'not a string');
            eq(only(['http://127.0.0.1:8765/store.php'])[0], 'http://127.0.0.1:8765/store.php');
            eq(only(['ftp://x', store, store]).length, 1, 'bad ones dropped, duplicates merged');
        });

        // -------------------------------------------------------------------------------- store client
        await test('store client: routes, token header, no credentials or referrer', async () => {
            const calls = [];
            const f = async (url, init) => { calls.push({ url, init }); return fakeResponse(200, spki); };
            const c = new SD.StoreClient(store, V.plantId, V.readToken, f);
            const key = await c.key();
            assert(SD.bytesEqual(key, spki));
            const u = new URL(calls[0].url);
            eq(u.origin + u.pathname, store);
            eq(u.searchParams.get('r'), '/v1/observe/' + V.plantId + '/key');
            eq(calls[0].init.headers['X-Observe-Token'], V.readToken);
            eq(calls[0].init.credentials, 'omit');
            eq(calls[0].init.referrerPolicy, 'no-referrer');
            assert(!calls[0].url.includes(V.dataKey), 'data key in a URL');
        });
        await test('store client: fetch posts [[kind, name]] and splits frames', async () => {
            let body = null;
            const f = async (url, init) => { body = init.body; return fakeResponse(200, frames([byName.plant.bytes])); };
            const c = new SD.StoreClient(store, V.plantId, V.readToken, f);
            const envs = await c.fetchItems([[5, 'plant'], [6, 'stores']]);
            eq(body, '[[5,"plant"],[6,"stores"]]');
            eq(envs.length, 1);
            eq(envs[0].name, 'plant');
        });
        await test('store client: 204 → null, 403 → StoreError(403), network → StoreError(0)', async () => {
            const c204 = new SD.StoreClient(store, V.plantId, V.readToken, async () => fakeResponse(204));
            eq(await c204.live(), null);
            const c403 = new SD.StoreClient(store, V.plantId, V.readToken, async () => fakeResponse(403, '{"error":"token"}'));
            try { await c403.key(); throw new Error('no error'); } catch (e) { assert(e instanceof SD.StoreError); eq(e.status, 403); }
            const cNet = new SD.StoreClient(store, V.plantId, V.readToken, async () => { throw new TypeError('Failed to fetch'); });
            try { await cNet.key(); throw new Error('no error'); } catch (e) { assert(e instanceof SD.StoreError); eq(e.status, 0); }
        });
        await test('index: pages with since until more is false', async () => {
            const seen = [];
            const f = async (url) => {
                const since = Number(new URL(url).searchParams.get('since'));
                seen.push(since);
                const items = since === 0 ? [[1, 'b3600', 3], [2, 'd2026-11-04', 4]] : since === 4 ? [[1, 'b7200', 9]] : [];
                return fakeResponse(200, JSON.stringify({ items, more: since === 0 }), 'application/json');
            };
            const c = new SD.StoreClient(store, V.plantId, V.readToken, f);
            const got = [];
            const last = await SD.readIndex(c, 0, (k, n, s) => got.push(n + '@' + s));
            eq(seen.join(','), '0,4');
            eq(got.join(','), 'b3600@3,d2026-11-04@4,b7200@9');
            eq(last, 9);
        });

        // -------------------------------------------------------------------------------- values
        await test('home power: given, or pv − grid + battery', () => {
            eq(SD.homePower({ load: 900, pv: 1 }), 900);
            eq(SD.homePower({ pv: 3200, grid: 1800, battery: { w: -500 } }), 900);
            eq(SD.homePower({ pv: 0, grid: -700 }), 700);
            eq(SD.homePower({}), null);
            eq(SD.homePower({ pv: 3200 }), null, 'no grid value on a grid-tied plant');
            eq(SD.homePower({ pv: 3200, battery: { w: -500 } }, true), 2700, 'off-grid');
        });
        await test('problems: kinds, hidden ones, device names, connection errors', () => {
            const live = { devices: [
                { id: 'a', reading: { problems: [{ kind: 'WARNING', text: 'Fan' }, { kind: 'FAULT', text: 'F04' }, { kind: 'OTHER', text: 'x' }, { kind: 'MAINS_LOST', text: 'Hidden one' }] } },
                { id: 'b', error: 'timeout', errorKind: 'TIMEOUT' },
            ] };
            const plant = { devices: [{ id: 'a', name: 'Inverter', hiddenProblems: ['Hidden one'] }, { id: 'b', name: 'Meter' }] };
            const p = SD.problemsOf(live, plant);
            eq(p.map((x) => x.kind + ':' + x.device + ':' + x.text).join('|'), 'FAULT:Inverter:F04|NO_CONNECTION:Meter:|WARNING:Inverter:Fan');
        });
        await test('series from the vector block; lines break at gaps of over 20 min', () => {
            const bl = SD.decodeBlock(SD.b64urlDecode(byName.b1762250400.block));
            const pv = SD.seriesOf([bl], 'pv');
            eq(pv.length, 60);
            eq(pv[0][0], 1762250400000);
            eq(pv[59][1], 1059);
            eq(SD.seriesOf([bl], 'pv', 1e-3)[59][1], 1.059, 'W to kW');
            eq(SD.seriesOf([bl], 'load').length, 0);
            eq(SD.seriesOf([bl], 'pv', 1, (v) => v < 1000).length < 60, true, 'values left out');
            const later = { ...bl, start: bl.start + 7200 };
            const both = SD.seriesOf([later, bl], 'pv');
            eq(both.length, 120);
            eq(both[60][0], (bl.start + 7200) * 1000, 'sorted by time');
            const drawn = SD.thinPoints(both, both[0][0], both[119][0], 1000);
            eq(drawn.length, 121, 'a break between the hours');
            eq(drawn[60], null);
            const { d, dots } = SD.linePath([[0, 1], null, [60000, 2], null, [120000, 3], [180000, 4]], (x) => x / 1000, (y) => y);
            eq(d, 'M0.0 1.0M60.0 2.0M120.0 3.0L180.0 4.0');
            eq(dots.length, 2, 'lone samples are dots');
        });
        await test('thinning keeps each bucket\'s lowest and highest, and the gaps; nearest point', () => {
            const pts = [];
            for (let i = 0; i < 10000; i++) pts.push([i * 60000, i === 5000 ? 999 : Math.sin(i / 50)]);
            const out = SD.thinPoints(pts, 0, 9999 * 60000, 100);
            assert(out.length <= 202, 'thinned to ' + out.length);
            assert(out.some((p) => p && p[1] === 999), 'the peak stays');
            for (let i = 1; i < out.length; i++) assert(out[i][0] > out[i - 1][0], 'in time order');
            const gap = pts.slice(0, 100).concat(pts.slice(200, 300));
            eq(SD.thinPoints(gap, 0, 300 * 60000, 2).filter((p) => p === null).length, 1, 'a 100-minute gap breaks the line');
            const part = SD.thinPoints(pts, 100 * 60000, 110 * 60000, 1000);
            eq(part[0][0], 99 * 60000, 'one point before the window');
            eq(part[part.length - 1][0], 111 * 60000, 'one after');
            eq(SD.nearestPoint(pts, 100 * 60000 + 20000)[0], 100 * 60000);
            eq(SD.nearestPoint(gap, 150 * 60000), null, 'nothing within 20 min');
            eq(SD.nearestPoint([], 0), null);
        });
        await test('periods: bounds (weeks from Monday), shifting, next only up to today', () => {
            const b = (p, a) => { const x = SD.periodBounds(p, a); return x.first + '..' + x.last; };
            eq(b('day', '2026-11-04'), '2026-11-04..2026-11-04');
            eq(b('week', '2026-11-04'), '2026-11-02..2026-11-08', 'a Wednesday');
            eq(b('week', '2026-11-02'), '2026-11-02..2026-11-08', 'a Monday');
            eq(b('week', '2026-11-08'), '2026-11-02..2026-11-08', 'a Sunday');
            eq(b('week', '2026-12-30'), '2026-12-28..2027-01-03', 'across the year');
            eq(b('month', '2028-02-10'), '2028-02-01..2028-02-29', 'a leap February');
            eq(b('month', '2026-12-31'), '2026-12-01..2026-12-31');
            eq(b('year', '2026-11-04'), '2026-01-01..2026-12-31');
            eq(SD.periodBounds('lifetime', '2026-11-04'), null);
            eq(SD.shiftAnchor('month', '2026-01-31', 1), '2026-02-28', 'the day kept within the month');
            eq(SD.shiftAnchor('month', '2026-01-15', -1), '2025-12-15');
            eq(SD.shiftAnchor('year', '2028-02-29', 1), '2029-02-28');
            eq(SD.shiftAnchor('week', '2026-11-04', -1), '2026-10-28');
            eq(SD.shiftAnchor('day', '2026-03-01', -1), '2026-02-28');
            eq(SD.shiftAnchor('lifetime', '2026-03-01', -1), '2026-03-01');
            eq(SD.addDays('2026-10-25', 1), '2026-10-26', 'over a DST change');
            eq(SD.hasNext('day', '2026-11-03', '2026-11-04'), true);
            eq(SD.hasNext('day', '2026-11-04', '2026-11-04'), false);
            eq(SD.hasNext('week', '2026-11-04', '2026-11-05'), false, 'this week');
            eq(SD.hasNext('lifetime', '2026-11-04', '2027-01-01'), false);
            eq(SD.inPeriod('month', '2026-11-04', '2026-11-30'), true);
            eq(SD.inPeriod('week', '2026-11-04', '2026-11-09'), false);
            eq(SD.daysOf('2026-02-27', '2026-03-02').join(','), '2026-02-27,2026-02-28,2026-03-01,2026-03-02');
            eq(SD.PERIODS.join(), 'day,week,month,year,lifetime');
            throws(() => SD.addDays('2026-1-1', 1), SD.FormatError);
        });
        await test('energy: sums of the known values, per month, per year, self-sufficiency', () => {
            const e = (pv, exp, imp, load) => ({ pv, export: exp, import: imp, load });
            const s = SD.sumEnergy([e(1, null, 2, null), e(2.5, null, null, null), null]);
            eq(JSON.stringify(s), '{"pv":3.5,"export":null,"import":2,"load":null}');
            eq(JSON.stringify(SD.energyOf({ pv: 1, export: 'x', load: 2, junk: 4 })), '{"pv":1,"export":null,"import":null,"load":2}');
            eq(JSON.stringify(SD.energyOf(null)), '{"pv":null,"export":null,"import":null,"load":null}');
            const days = new Map([['2025-12-31', e(1, 0, 0, 1)], ['2026-01-05', e(2, 1, 0, 3)], ['2026-01-20', e(3, 1, 1, 3)], ['2026-03-01', e(4, 2, 1, null)]]);
            const year = SD.energySlots('year', '2026-06-15', days, '2026-11-04');
            eq(year.length, 12);
            eq(year[0].key, '2026-01');
            eq(JSON.stringify(year[0].energy), '{"pv":5,"export":2,"import":1,"load":6}');
            eq(year[1].energy.pv, null, 'February: nothing');
            eq(year[2].energy.load, null, 'March: no consumption known');
            const life = SD.energySlots('lifetime', '2026-11-04', days, '2026-11-04');
            eq(life.map((x) => x.key + ':' + x.energy.pv).join(','), '2025:1,2026:9');
            eq(SD.energySlots('lifetime', '2026-11-04', new Map(), '2026-11-04').map((x) => x.key).join(), '2026', 'no data: this year');
            const month = SD.energySlots('month', '2026-01-10', days, '2026-11-04');
            eq(month.length, 31);
            eq(month[4].energy.pv, 2);
            eq(month[5].energy.pv, null);
            eq(SD.energySlots('week', '2026-01-07', days, '2026-11-04').map((x) => x.key).join(), '2026-01-05,2026-01-06,2026-01-07,2026-01-08,2026-01-09,2026-01-10,2026-01-11');
            eq(SD.selfSufficiency(e(5, 2, 2, 8)), 0.75);
            eq(SD.selfSufficiency(e(5, 2, null, 8)), null);
            eq(SD.selfSufficiency(e(5, 2, 9, 8)), 0, 'never below 0');
            eq(SD.selfConsumption(e(10, 4, 0, 8)), 0.6);
            eq(SD.selfConsumption(e(0, 0, 0, 8)), null, 'no solar');
        });
        await test('channels: display units, names, sections, German, catalog, picker groups and search', () => {
            eq(SD.displayUnit('W').join(), 'kW,0.001');
            eq(SD.displayUnit('MWh').join(), 'kWh,1000');
            eq(SD.displayUnit('').join(), 'value,1');
            eq(SD.displayUnit('°C').join(), '°C,1');
            eq(SD.channelName('d:Battery:Cycles', 'Cycles (Battery)'), 'Cycles');
            eq(SD.channelName('d:A:b:c', 'x'), 'b:c', 'a label with a colon');
            eq(SD.channelName('r:pv_power', 'Solar power'), 'Solar power');
            eq(SD.channelSection('d:Battery:Cycles'), 'Battery');
            eq(SD.channelSection('r:pv_power'), 'Live values');
            eq(SD.shorten('External temperature'), 'External temper…');
            eq(SD.shorten('Short'), 'Short');
            eq(SD.translateChannel('PV1 voltage', 'de'), 'PV1 Spannung');
            eq(SD.translateChannel('Solar power', 'de'), 'Solarleistung');
            eq(SD.translateChannel('Solar power', 'en'), 'Solar power');
            eq(SD.translateChannel('Cycles', 'de'), 'Cycles', 'unknown: English');
            const plant = { channels: { a: [['r:pv_power', 'Solar power', 'W'], ['d:Battery:Cycles', 'Cycles (Battery)', ''], ['bad'], [1, 2, 3], ['d:Meter:Volts', 'Volts (Meter)', 'V'], ['r:temperature', 'Temperature', '°C']] } };
            const cat = SD.catalogOf(plant, 'a');
            eq(cat.length, 4, 'malformed entries left out');
            eq(SD.catalogOf(plant, 'b').length, 0);
            eq(SD.catalogOf(plant, 'constructor').length, 0, 'own entries only');
            eq(SD.catalogOf(null, 'a').length, 0);
            const name = (k, l) => SD.translateChannel(SD.channelName(k, l), 'de');
            eq(SD.pickerSections(cat, '', name).map((x) => x.section + ':' + x.rows.length).join(), 'Live values:2,Battery:1,Meter:1', 'live values first');
            eq(SD.pickerSections(cat, ' TEMP', name).map((x) => x.rows[0][0]).join(), 'r:temperature');
            eq(SD.pickerSections(cat, 'solarleistung', name).map((x) => x.rows[0][0]).join(), 'r:pv_power', 'the shown name is searched');
            eq(SD.pickerSections(cat, 'cycles', name)[0].section, 'Battery');
            eq(SD.pickerSections(cat, 'nothing like it', name).length, 0);
        });
        await test('chart channels: free colour slots, at most 3 channels and 4 units', () => {
            const units = { t: '°C', v: 'V', w: 'W', h: 'Hz', p: '%' };
            const unitOf = (d, k) => units[k];
            let r = SD.addChannels([], [['a', 't'], ['a', 'w']], unitOf);
            eq(r.channels.map((c) => c.key + c.slot).join(), 't0,w1');
            r = SD.addChannels([{ deviceId: 'a', key: 't', slot: 1 }], [['a', 'w'], ['a', 'p']], unitOf);
            eq(r.channels.map((c) => c.key + c.slot).join(), 't1,w0,p2', 'the first free slot');
            eq(SD.addChannels(r.channels, [['a', 'v']], unitOf).refused, 'channels');
            r = SD.addChannels([], [['a', 't'], ['a', 'v'], ['a', 'h']], unitOf);
            eq(r.refused, 'units');
            eq(r.units.join(), 'kW,%,°C,V,Hz');
            eq(SD.addChannels([], [['a', 't'], ['a', 'v'], ['a', 'w']], unitOf).channels.length, 3, 'W shares the kW axis');
        });
        await test('selection: default from the plant, stored per plant, broken storage is harmless', () => {
            const plant = { chartChannels: [{ deviceId: 's', key: 'd:Device:T', slot: 0 }, { deviceId: 's', key: 'd:Device:T', slot: 1 }, { deviceId: 'x', key: 'k', slot: 9 }, 'junk'] };
            const d = SD.defaultSelection(plant);
            eq(d.channels.map((c) => c.deviceId + c.slot).join(), 's0,x1', 'duplicates dropped, a bad slot replaced');
            eq(d.hiddenEnergy.join(), 'export,import', 'export and import bars hidden at first, as in the app');
            eq(d.hidden.length, 0);
            eq(d.stored, false);
            const mem = new Map();
            const storage = { getItem: (k) => (mem.has(k) ? mem.get(k) : null), setItem: (k, v) => mem.set(k, String(v)) };
            eq(SD.loadSelection(storage, 'P1', plant).stored, false);
            assert(SD.saveSelection(storage, 'P1', { channels: [{ deviceId: 'a', key: 'r:x', slot: 2 }], hidden: ['plant:pv'], hiddenEnergy: ['load', 'nonsense'] }));
            const back = SD.loadSelection(storage, 'P1', plant);
            eq(back.stored, true);
            eq(back.channels[0].slot, 2);
            eq(back.hidden.join(), 'plant:pv');
            eq(back.hiddenEnergy.join(), 'load');
            eq(SD.loadSelection(storage, 'P2', plant).channels[0].deviceId, 's', 'per plant');
            mem.set('solardash.chart.P3', '{not json');
            eq(SD.loadSelection(storage, 'P3', plant).stored, false);
            const broken = { getItem: () => { throw new Error('SecurityError'); }, setItem: () => { throw new Error('QuotaExceededError'); } };
            eq(SD.loadSelection(broken, 'P1', plant).stored, false);
            eq(SD.saveSelection(broken, 'P1', d), false);
            eq(SD.saveSelection(null, 'P1', d), false);
            eq(SD.loadSelection(null, 'P1', null).channels.length, 0);
        });
        await test('zoom: around the anchor, panning, limits', () => {
            let vp = SD.viewport(0.1);
            eq(SD.isZoomed(vp), false);
            vp = SD.zoomViewport(vp, 0.5, 2, 0);
            eq(vp.start, 0.25);
            eq(vp.end, 0.75);
            eq(SD.isZoomed(vp), true);
            vp = SD.zoomViewport(vp, 0, 2, 0);
            eq(vp.start + ',' + vp.end, '0.25,0.5', 'anchored at the left edge');
            vp = SD.zoomViewport(vp, 0.5, 100, 0);
            assert(Math.abs(vp.end - vp.start - 0.1) < 1e-9, 'not past minSpan');
            const panned = SD.zoomViewport(vp, 0.5, 1, 0.5);
            assert(Math.abs(panned.start - (vp.start - 0.05)) < 1e-9, 'a drag to the right shows earlier times');
            eq(SD.zoomViewport(vp, 0.5, 1, 100).start, 0, 'not before the start');
            eq(SD.zoomViewport(vp, 0.5, 1, -100).end, 1, 'not after the end');
            const out = SD.zoomViewport(vp, 0.3, 0.001, 0);
            eq(out.start + ',' + out.end, '0,1', 'zoomed out fully');
            eq(SD.zoomViewport(SD.viewport(0.1), 0.5, NaN, 0).end, 1);
        });
        await test('axes: nice steps, zero kept for power, own range for others; time label steps', () => {
            const s = SD.niceScale(-1.2, 4.3);
            eq(s.ticks.join(), '-2,0,2,4,6');
            const v = SD.niceScale(228.4, 236.1, 4, false);
            eq(v.min + '..' + v.max + '/' + v.step, '228..238/2');
            const flat = SD.niceScale(5, 5, 4, false);
            assert(flat.min < 5 && flat.max > 5, 'a flat line gets room');
            const zero = SD.niceScale(0, 0);
            assert(zero.min === 0 && zero.max > 0);
            eq(SD.niceScale(0, 0.37).ticks.join(), '0,0.1,0.2,0.3,0.4');
            eq(SD.tickMinutes(1440, 10), 240, 'a day: 4 h');
            eq(SD.tickMinutes(1440, 3), 720, 'a narrow day: 12 h');
            eq(SD.tickMinutes(60, 10), 15);
            eq(SD.tickMinutes(7 * 1440, 10), 1440, 'a week: days');
        });
        await test('fault log: entries checked, active and past newest first, durations', () => {
            const ev = (id, started, ended, extra) => SD.eventOf(Object.assign({ id, key: 'k', kind: 'DEVICE_FAULT', severity: 'ALARM', started, ended }, extra));
            const list = [ev(1, 100, 200), ev(2, 300, null), ev(3, 300, 400), ev(4, 50, null), SD.eventOf({ id: 5 }), ev(6, 500, 600, { severity: 'BOGUS' }), ev(7, 300, 350)];
            eq(list[4], null, 'no kind or start');
            eq(list[5].severity, 'WARNING', 'an unknown severity reads as a warning');
            const { active, past } = SD.splitEvents(list);
            eq(active.map((e) => e.id).join(), '2,4');
            eq(past.map((e) => e.id).join(), '6,7,3,1', 'by start, then id');
            eq(SD.durationParts(0).join(), 'min,0');
            eq(SD.durationParts(61).join(), 'min,2', 'minutes rounded up');
            eq(SD.durationParts(3600).join(), 'hours,1,0');
            eq(SD.durationParts(2 * 3600 + 300).join(), 'hours,2,5');
            eq(SD.durationParts(3 * 86400 + 4 * 3600).join(), 'days,3,4');
            eq(SD.eventOf('nonsense'), null);
            eq(SD.eventOf({ id: 1, kind: 'X', started: 5, ended: 'soon' }).ended, null);
        });
        await test('seq: an item dropped from the cache takes its own seq again, never an older one', () => {
            const s = new SD.SeqTracker();
            assert(s.offer(1, 'b1', 5n));
            assert(!s.offer(1, 'b1', 5n), 'a replay');
            assert(s.offer(1, 'b1', 5n, true), 'again');
            assert(!s.offer(1, 'b1', 4n, true), 'older, even again');
        });
        await test('texts: English and German have the same keys; language pick', () => {
            const en = Object.keys(SD.TEXTS.en).sort().join(','), de = Object.keys(SD.TEXTS.de).sort().join(',');
            eq(en, de);
            eq(SD.pickLanguage(['de-CH', 'en']), 'de');
            eq(SD.pickLanguage(['fr-CH', 'de']), 'de');
            eq(SD.pickLanguage(['en-GB', 'de']), 'en');
            eq(SD.pickLanguage(['fr']), 'en');
        });
    }

    run().catch((e) => { results.push(false); line('FAIL test run: ' + (e && e.message ? e.message : e)); }).finally(() => {
        const failed = results.filter((r) => !r).length;
        document.getElementById('summary').textContent = failed ? 'FAILED ' + failed + ' of ' + results.length : 'ALL PASS ' + results.length;
    });
})();
