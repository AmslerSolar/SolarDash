// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH
//
// SolarDash observer web view (docs/design/observer-protocol.md, docs/design/remote-viewing.md "Web view").
// Everything comes from location.hash, which never leaves the browser: the read token goes to the stores in the
// X-Observe-Token header, the data key never leaves this page. Every envelope is checked against the plant's
// signature before it is decrypted. No third-party code, no innerHTML: data only ever reaches the page as text.
//
// The pure functions (decoding, crypto, link parsing) are on window.SolarDashObserver for test.html; the page
// itself only starts on index.html (<html data-page="observer">).

(function () {
    'use strict';

    // ------------------------------------------------------------------------------------------------ texts

    // All texts of the page. Keys are used by data-t attributes in index.html and by the code below.
    // {x} are placeholders.
    const TEXTS = {
        en: {
            pageTitle: 'SolarDash',
            loading: 'Loading…',
            solar: 'Solar',
            home: 'Home',
            grid: 'Grid',
            battery: 'Battery',
            import: 'Import',
            export: 'Export',
            charging: 'Charging',
            discharging: 'Discharging',
            idle: 'Idle',
            offGrid: 'Off-grid',
            noValue: '–',
            todayTitle: 'Today',
            consumption: 'Consumption',
            chartTitle: 'Over the day',
            chartA11y: 'Day chart of solar, home, grid and battery power',
            flowTitle: 'Energy flow now',
            chartEmpty: 'No history for this day yet.',
            chartNote: 'Grid above 0: export, below 0: import. Battery above 0: discharging, below 0: charging.',
            chartAxis: 'kW',
            updated: 'Updated {time}',
            noDataFor: 'No new data for {dur} (last: {time})',
            noLive: 'No live values yet.',
            waiting: 'Asking the plant for current values…',
            storeLost: 'The store is not reachable at the moment. Showing the last values.',
            minutes: '{n} min',
            hours: '{n} h',
            days: '{n} days',
            problemsTitle: 'Messages',
            FAULT: 'Fault',
            WARNING: 'Warning',
            MAINS_LOST: 'Grid power cut',
            MAINS_LOST_offGrid: 'No AC input',
            BACKUP_MODE: 'Backup power',
            BACKUP_MODE_offGrid: 'Battery operation',
            noConnection: 'No connection',
            openApp: 'Open in SolarDash',
            privacy: 'Data is encrypted end to end. The code in this link stays in your browser.',
            errorTitle: 'Cannot show the plant',
            errBadLink: 'This link is incomplete or damaged. Please scan the code on the plant\'s tablet again.',
            errWrongCode: 'The code is not valid (any more). The owner may have made a new code: please scan it again.',
            errNoPlant: 'The plant is not known on the store (any more).',
            errNoData: 'There is no data from this plant yet.',
            errUnreachable: 'The store cannot be reached. Please check the internet connection; the page tries again by itself.',
            errBadData: 'The data on the store is not from this plant (signature does not match).',
            errNoCrypto: 'This browser cannot decrypt the data. Please use a current browser.',
            retry: 'Try again',
        },
        de: {
            pageTitle: 'SolarDash',
            loading: 'Wird geladen …',
            solar: 'Solar',
            home: 'Haus',
            grid: 'Netz',
            battery: 'Batterie',
            import: 'Bezug',
            export: 'Einspeisung',
            charging: 'Lädt',
            discharging: 'Entlädt',
            idle: 'Ruht',
            offGrid: 'Inselanlage',
            noValue: '–',
            todayTitle: 'Heute',
            consumption: 'Verbrauch',
            chartTitle: 'Tagesverlauf',
            chartA11y: 'Tagesverlauf von Solar, Haus, Netz und Batterie',
            flowTitle: 'Energiefluss jetzt',
            chartEmpty: 'Für diesen Tag gibt es noch keinen Verlauf.',
            chartNote: 'Netz über 0: Einspeisung, unter 0: Bezug. Batterie über 0: entlädt, unter 0: lädt.',
            chartAxis: 'kW',
            updated: 'Aktualisiert {time}',
            noDataFor: 'Seit {dur} keine neuen Daten (zuletzt: {time})',
            noLive: 'Noch keine aktuellen Werte.',
            waiting: 'Die Anlage wird nach aktuellen Werten gefragt …',
            storeLost: 'Der Speicher ist im Moment nicht erreichbar. Angezeigt werden die letzten Werte.',
            minutes: '{n} Min.',
            hours: '{n} Std.',
            days: '{n} Tagen',
            problemsTitle: 'Meldungen',
            FAULT: 'Störung',
            WARNING: 'Warnung',
            MAINS_LOST: 'Stromausfall',
            MAINS_LOST_offGrid: 'Kein AC-Eingang',
            BACKUP_MODE: 'Notstrom',
            BACKUP_MODE_offGrid: 'Batteriebetrieb',
            noConnection: 'Keine Verbindung',
            openApp: 'In SolarDash öffnen',
            privacy: 'Die Daten sind durchgehend verschlüsselt. Der Code in diesem Link bleibt in Ihrem Browser.',
            errorTitle: 'Die Anlage kann nicht angezeigt werden',
            errBadLink: 'Dieser Link ist unvollständig oder beschädigt. Bitte den Code auf dem Tablet der Anlage nochmals scannen.',
            errWrongCode: 'Der Code ist nicht (mehr) gültig. Vielleicht wurde ein neuer Code erstellt: bitte nochmals scannen.',
            errNoPlant: 'Die Anlage ist auf dem Speicher nicht (mehr) bekannt.',
            errNoData: 'Von dieser Anlage gibt es noch keine Daten.',
            errUnreachable: 'Der Speicher ist nicht erreichbar. Bitte die Internetverbindung prüfen; die Seite versucht es von selbst wieder.',
            errBadData: 'Die Daten auf dem Speicher stammen nicht von dieser Anlage (Signatur passt nicht).',
            errNoCrypto: 'Dieser Browser kann die Daten nicht entschlüsseln. Bitte einen aktuellen Browser verwenden.',
            retry: 'Nochmals versuchen',
        },
    };

    function pickLanguage(langs) {
        for (const l of langs || []) {
            if (typeof l === 'string' && l.toLowerCase().startsWith('de')) return 'de';
            if (typeof l === 'string' && l.toLowerCase().startsWith('en')) return 'en';
        }
        return 'en';
    }

    // ------------------------------------------------------------------------------------------------ bytes

    const KIND = { REGISTER: 0, BLOCK: 1, DAY: 2, EVENT: 3, LIVE: 4, PLANT: 5, STORES: 6, DELETE: 7 };
    const MAX_PAYLOAD = 262144;
    const MAX_CONTENT = 16 << 20;
    const NAME_RE = /^[A-Za-z0-9._-]{1,64}$/;
    const MAGIC = [0x53, 0x44, 0x4f, 0x31]; // SDO1

    class FormatError extends Error {}

    function b64urlDecode(text) {
        if (typeof text !== 'string' || !/^[A-Za-z0-9_-]*$/.test(text) || text.length % 4 === 1) {
            throw new FormatError('Not base64url');
        }
        const b64 = text.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((text.length + 3) % 4);
        let bin;
        try {
            bin = atob(b64);
        } catch (e) {
            throw new FormatError('Not base64url');
        }
        const out = new Uint8Array(bin.length);
        for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
        // Reject non-canonical forms (stray bits in the last character).
        if (b64urlEncode(out) !== text) throw new FormatError('Not canonical base64url');
        return out;
    }

    function b64urlEncode(bytes) {
        let bin = '';
        for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
        return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
    }

    function bytesEqual(a, b) {
        if (a.length !== b.length) return false;
        for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
        return true;
    }

    function concat(...parts) {
        const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
        let o = 0;
        for (const p of parts) { out.set(p, o); o += p.length; }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ envelope

    /** Parses one envelope strictly; throws FormatError. Subarrays share the input's buffer. */
    function parseEnvelope(input) {
        const b = input instanceof Uint8Array ? input : new Uint8Array(input);
        if (b.length < 28) throw new FormatError('Truncated envelope');
        const v = new DataView(b.buffer, b.byteOffset, b.byteLength);
        for (let i = 0; i < 4; i++) if (b[i] !== MAGIC[i]) throw new FormatError('Not an envelope');
        const kind = b[4];
        if (kind > 7) throw new FormatError('Unknown kind ' + kind);
        const seq = v.getBigUint64(5);
        const timeBig = v.getBigUint64(13);
        if (timeBig > BigInt(Number.MAX_SAFE_INTEGER)) throw new FormatError('Bad time');
        const n = b[21];
        if (n < 1 || n > 64 || b.length < 28 + n) throw new FormatError('Bad name');
        let name = '';
        for (let i = 0; i < n; i++) name += String.fromCharCode(b[22 + i]);
        if (!NAME_RE.test(name)) throw new FormatError('Bad name');
        const p = v.getUint32(22 + n);
        if (p > MAX_PAYLOAD) throw new FormatError('Payload of ' + p + ' bytes');
        const sigAt = 26 + n + p;
        if (b.length < sigAt + 2) throw new FormatError('Truncated envelope');
        const s = v.getUint16(sigAt);
        if (b.length !== sigAt + 2 + s) throw new FormatError(b.length < sigAt + 2 + s ? 'Truncated envelope' : 'Trailing bytes');
        return {
            kind, seq, time: Number(timeBig), name,
            header: b.subarray(0, 22 + n),
            payload: b.subarray(26 + n, sigAt),
            signed: b.subarray(0, sigAt),
            signature: b.subarray(sigAt + 2),
        };
    }

    /** Frames: each a 4-byte length, then the envelope. */
    function parseFrames(input) {
        const b = input instanceof Uint8Array ? input : new Uint8Array(input);
        const v = new DataView(b.buffer, b.byteOffset, b.byteLength);
        const out = [];
        let i = 0;
        while (i < b.length) {
            if (b.length - i < 4) throw new FormatError('Truncated frame');
            const n = v.getUint32(i);
            i += 4;
            if (n > b.length - i) throw new FormatError('Truncated frame');
            out.push(parseEnvelope(b.subarray(i, i + n)));
            i += n;
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ crypto

    /**
     * ASN.1 DER ECDSA signature (SEQUENCE { INTEGER r, INTEGER s }) to the raw 64 bytes r ‖ s WebCrypto checks.
     * Strict: minimal lengths and integers, positive, at most 32 bytes each after the sign byte, nothing trailing.
     */
    function derToRaw(der, size = 32) {
        const d = der instanceof Uint8Array ? der : new Uint8Array(der);
        let i = 0;
        if (d.length < 8 || d[i++] !== 0x30) throw new FormatError('Not a DER signature');
        let len = d[i++];
        if (len & 0x80) {
            if (len !== 0x81 || d.length < 3) throw new FormatError('Bad DER length');
            len = d[i++];
            if (len < 0x80) throw new FormatError('Non-minimal DER length');
        }
        if (i + len !== d.length) throw new FormatError('Bad DER length');
        const out = new Uint8Array(2 * size);
        for (let k = 0; k < 2; k++) {
            if (i + 2 > d.length || d[i++] !== 0x02) throw new FormatError('Not a DER integer');
            const l = d[i++];
            if (l === 0 || l > size + 1 || i + l > d.length) throw new FormatError('Bad DER integer length');
            let x = d.subarray(i, i + l);
            i += l;
            if (x[0] & 0x80) throw new FormatError('Negative DER integer');
            if (l > 1 && x[0] === 0 && !(x[1] & 0x80)) throw new FormatError('Non-minimal DER integer');
            if (x[0] === 0 && l > 1) x = x.subarray(1);
            if (x.length > size) throw new FormatError('DER integer too large');
            out.set(x, (k + 1) * size - x.length);
        }
        if (i !== d.length) throw new FormatError('Trailing bytes in DER');
        return out;
    }

    async function sha256(bytes) {
        return new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
    }

    /** The plant id: b64url of the first 16 bytes of SHA-256(SPKI). */
    async function plantIdOf(spki) {
        return b64urlEncode((await sha256(spki)).subarray(0, 16));
    }

    function importPublicKey(spki) {
        return crypto.subtle.importKey('spki', spki, { name: 'ECDSA', namedCurve: 'P-256' }, false, ['verify']);
    }

    function importDataKey(raw) {
        if (raw.length !== 32) throw new FormatError('Data key must be 32 bytes');
        return crypto.subtle.importKey('raw', raw, { name: 'AES-GCM' }, false, ['decrypt']);
    }

    /** True when the envelope's signature is the plant's. */
    async function verifyEnvelope(env, publicKey) {
        let raw;
        try {
            raw = derToRaw(env.signature);
        } catch (e) {
            return false;
        }
        try {
            return await crypto.subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, publicKey, raw, env.signed);
        } catch (e) {
            return false;
        }
    }

    /** zlib (RFC 1950) inflate with an output limit against compression bombs. */
    async function inflate(data, limit = MAX_CONTENT) {
        const stream = new Blob([data]).stream().pipeThrough(new DecompressionStream('deflate'));
        const reader = stream.getReader();
        const parts = [];
        let total = 0;
        try {
            for (;;) {
                const { done, value } = await reader.read();
                if (done) break;
                total += value.length;
                if (total > limit) {
                    reader.cancel().catch(() => {});
                    throw new FormatError('Content too large');
                }
                parts.push(value);
            }
        } catch (e) {
            if (e instanceof FormatError) throw e;
            throw new FormatError('Bad zlib data');
        }
        return concat(...parts);
    }

    const SEALED = new Set([KIND.BLOCK, KIND.DAY, KIND.EVENT, KIND.LIVE, KIND.PLANT, KIND.STORES]);

    /** Decrypts and inflates a sealed envelope: nonce = first 12 bytes, AAD = the envelope header. */
    async function openEnvelope(env, dataKey, limit = MAX_CONTENT) {
        if (!SEALED.has(env.kind)) throw new FormatError('Kind ' + env.kind + ' is not sealed');
        if (env.payload.length < 12 + 16) throw new FormatError('Sealed payload too short');
        let plain;
        try {
            plain = await crypto.subtle.decrypt(
                { name: 'AES-GCM', iv: env.payload.subarray(0, 12), additionalData: env.header, tagLength: 128 },
                dataKey, env.payload.subarray(12));
        } catch (e) {
            throw new FormatError('Cannot decrypt ' + env.name);
        }
        return inflate(new Uint8Array(plain), limit);
    }

    class DataError extends Error {}

    /**
     * Everything an observer checks, in order: the kind and name are the requested ones, the signature is the
     * plant's, the seq is newer than any seen for that kind and name; then it decrypts. Returns the content bytes,
     * or null for an envelope older than (or the same as) one already seen. Throws DataError on a bad envelope.
     */
    async function acceptEnvelope(env, want, ctx) {
        if (env.kind !== want.kind || env.name !== want.name) throw new DataError('Not the requested item');
        if (!(await verifyEnvelope(env, ctx.publicKey))) throw new DataError('Bad signature on ' + env.name);
        if (ctx.seqs && !ctx.seqs.offer(env.kind, env.name, env.seq)) return null;
        try {
            return await openEnvelope(env, ctx.dataKey);
        } catch (e) {
            if (ctx.seqs) ctx.seqs.forget(env.kind, env.name, env.seq);
            throw new DataError(e.message);
        }
    }

    /** Highest seq seen per kind and name (in memory only). */
    class SeqTracker {
        constructor() { this.map = new Map(); }
        get(kind, name) { return this.map.get(kind + ':' + name); }
        /** True (and remembered) when seq is above every seq seen for kind and name. */
        offer(kind, name, seq) {
            const key = kind + ':' + name;
            const seen = this.map.get(key);
            if (seen !== undefined && seq <= seen) return false;
            this.map.set(key, seq);
            return true;
        }
        /** Undo an offer whose envelope then failed to open, so a good copy with that seq is still accepted. */
        forget(kind, name, seq) {
            const key = kind + ':' + name;
            if (this.map.get(key) === seq) this.map.delete(key);
        }
    }

    function parseJson(bytes) {
        const text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
        return JSON.parse(text);
    }

    // ------------------------------------------------------------------------------------------------ block

    /** Decodes a block (observer protocol "Block"): { start, interval, slots, channels: [{name, exponent, values}] }. */
    function decodeBlock(input) {
        const b = input instanceof Uint8Array ? input : new Uint8Array(input);
        const v = new DataView(b.buffer, b.byteOffset, b.byteLength);
        let i = 0;
        const need = (n) => { if (i + n > b.length) throw new FormatError('Truncated block'); };
        need(11);
        if (b[0] !== 1) throw new FormatError('Unknown block version');
        const start = v.getUint32(1);
        const interval = v.getUint16(5);
        const slots = v.getUint16(7);
        const count = v.getUint16(9);
        i = 11;
        if (interval === 0 || slots > 3600) throw new FormatError('Bad block layout');
        const heads = [];
        for (let k = 0; k < count; k++) {
            need(1);
            const n = b[i++];
            need(n + 1);
            const name = new TextDecoder('utf-8', { fatal: true }).decode(b.subarray(i, i + n));
            i += n;
            const exponent = v.getInt8(i++);
            heads.push({ name, exponent });
        }
        const bitmapBytes = (slots + 7) >> 3;
        need(count * bitmapBytes);
        const present = heads.map(() => { const s = b.subarray(i, i + bitmapBytes); i += bitmapBytes; return s; });
        const readVarint = () => {
            let result = 0;
            let mult = 1;
            for (let shift = 0; ; shift += 7) {
                if (shift > 49) throw new FormatError('Varint too long');
                need(1);
                const byte = b[i++];
                result += (byte & 0x7f) * mult;
                if (!(byte & 0x80)) return result;
                mult *= 128;
            }
        };
        const unzigzag = (x) => (x % 2 === 0 ? x / 2 : -(x + 1) / 2);
        const channels = heads.map((h, k) => {
            const values = new Array(slots);
            let previous = null;
            for (let s = 0; s < slots; s++) {
                if (!((present[k][s >> 3] >> (s & 7)) & 1)) { values[s] = null; continue; }
                const d = unzigzag(readVarint());
                const x = previous === null ? d : previous + d;
                previous = x;
                values[s] = h.exponent >= 0 ? x * Math.pow(10, h.exponent) : x / Math.pow(10, -h.exponent);
            }
            return { name: h.name, exponent: h.exponent, values };
        });
        if (i !== b.length) throw new FormatError('Trailing bytes in block');
        return { start, interval, slots, channels };
    }

    // ------------------------------------------------------------------------------------------------ link

    class LinkError extends Error {}

    function isLoopback(host) {
        return host === 'localhost' || host === '127.0.0.1' || host === '[::1]' || host.endsWith('.localhost');
    }

    /** A store URL from the link: https (http only to this machine, for development), no credentials, no fragment. */
    function checkStoreUrl(text) {
        if (typeof text !== 'string' || text.length > 512) return null;
        let u;
        try {
            u = new URL(text);
        } catch (e) {
            return null;
        }
        if (u.protocol !== 'https:' && !(u.protocol === 'http:' && isLoopback(u.hostname))) return null;
        if (u.username || u.password || u.hash) return null;
        return u.href;
    }

    /**
     * The observer link's fragment: <plant id>.<b64url read token>.<b64url data key>.<b64url of the JSON store list>.
     * Returns { plantId, token, dataKey (bytes), stores (URLs), fragment }; throws LinkError.
     */
    function parseLink(hash) {
        if (typeof hash !== 'string') throw new LinkError('No link');
        const fragment = hash.startsWith('#') ? hash.slice(1) : hash;
        if (fragment.length === 0 || fragment.length > 4096) throw new LinkError('No link');
        const parts = fragment.split('.');
        if (parts.length !== 4) throw new LinkError('Link must have 4 parts');
        const [plantId, token, key, storeList] = parts;
        let id, tok, dataKey, list;
        try {
            id = b64urlDecode(plantId);
            tok = b64urlDecode(token);
            dataKey = b64urlDecode(key);
            list = parseJson(b64urlDecode(storeList));
        } catch (e) {
            throw new LinkError('Bad link encoding');
        }
        if (id.length !== 16) throw new LinkError('Bad plant id');
        if (tok.length !== 16) throw new LinkError('Bad read token');
        if (dataKey.length !== 32) throw new LinkError('Bad data key');
        if (!Array.isArray(list) || list.length === 0) throw new LinkError('No stores');
        const stores = [];
        for (const s of list.slice(0, 16)) {
            const url = checkStoreUrl(s);
            if (url && !stores.includes(url)) stores.push(url);
        }
        if (stores.length === 0) throw new LinkError('No usable store');
        return { plantId, token, dataKey, stores, fragment };
    }

    // ------------------------------------------------------------------------------------------------ store

    class StoreError extends Error {
        constructor(message, status) { super(message); this.status = status; }
    }

    /** The observer routes of one store: <store>?r=/v1/observe/<id>/<op>, token in X-Observe-Token. */
    class StoreClient {
        constructor(url, plantId, token, fetchImpl) {
            this.url = url;
            this.plantId = plantId;
            this.token = token;
            this.fetch = fetchImpl || ((...a) => fetch(...a));
        }

        address(op, params) {
            const u = new URL(this.url);
            u.searchParams.set('r', '/v1/observe/' + this.plantId + '/' + op);
            for (const [k, v] of Object.entries(params || {})) u.searchParams.set(k, String(v));
            return u.href;
        }

        async request(method, op, { body, params, json } = {}) {
            const headers = { 'X-Observe-Token': this.token };
            if (body !== undefined) headers['Content-Type'] = 'application/json';
            const controller = new AbortController();
            const timer = setTimeout(() => controller.abort(), 20000);
            let r;
            try {
                r = await this.fetch(this.address(op, params), {
                    method, headers, body: body === undefined ? undefined : JSON.stringify(body),
                    cache: 'no-store', credentials: 'omit', referrerPolicy: 'no-referrer', redirect: 'error',
                    mode: 'cors', signal: controller.signal,
                });
            } catch (e) {
                throw new StoreError('Store not reachable', 0);
            } finally {
                clearTimeout(timer);
            }
            if (r.status === 204) return null;
            if (r.status !== 200) throw new StoreError('Store answered ' + r.status, r.status);
            const buf = new Uint8Array(await r.arrayBuffer());
            if (json) {
                try {
                    return parseJson(buf);
                } catch (e) {
                    throw new StoreError('Bad answer', -1);
                }
            }
            return buf;
        }

        key() { return this.request('GET', 'key'); }
        live() { return this.request('GET', 'live'); }
        watch() { return this.request('POST', 'watch'); }
        index(since) { return this.request('GET', 'index', { params: { since: String(since) }, json: true }); }
        async fetchItems(pairs) {
            const body = await this.request('POST', 'fetch', { body: pairs });
            return body ? parseFrames(body) : [];
        }
    }

    /** All index items above seq 0, page by page (at most maxPages). Calls onItem(kind, name, seq). */
    async function readIndex(client, since, onItem, maxPages = 200) {
        let last = since;
        for (let page = 0; page < maxPages; page++) {
            const r = await client.index(last);
            if (!r || !Array.isArray(r.items)) throw new StoreError('Bad index', -1);
            let advanced = false;
            for (const it of r.items) {
                if (!Array.isArray(it) || it.length < 3) continue;
                const [kind, name, seq] = it;
                if (!Number.isInteger(kind) || typeof name !== 'string' || !NAME_RE.test(name)) continue;
                if (!Number.isSafeInteger(seq) || seq < 0) continue;
                onItem(kind, name, BigInt(seq));
                if (seq > last) { last = seq; advanced = true; }
            }
            if (r.more !== true || !advanced) break;
        }
        return last;
    }

    // ------------------------------------------------------------------------------------------------ values

    const num = (x) => (typeof x === 'number' && Number.isFinite(x) ? x : null);
    const str = (x) => (typeof x === 'string' ? x : null);
    const arr = (x) => (Array.isArray(x) ? x : []);
    const obj = (x) => (x && typeof x === 'object' && !Array.isArray(x) ? x : null);

    /**
     * The house consumption: given, or what solar, grid (+ export) and battery (+ discharging) leave. Without a grid
     * value it can only be worked out on an off-grid plant.
     */
    function homePower(live, offGrid) {
        const load = num(live.load);
        if (load !== null) return load;
        const pv = num(live.pv), grid = num(live.grid), bat = num(obj(live.battery) ? live.battery.w : null);
        if (grid === null && !offGrid) return null;
        if (pv === null && grid === null && bat === null) return null;
        return Math.max(0, (pv || 0) - (grid || 0) + (bat || 0));
    }

    /** Local calendar date YYYY-MM-DD of an epoch-ms time (the browser's time zone). */
    function localDate(ms) {
        const d = new Date(ms);
        const p = (n) => String(n).padStart(2, '0');
        return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate());
    }

    function dayBounds(date) {
        const [y, m, d] = date.split('-').map(Number);
        const start = new Date(y, m - 1, d).getTime();
        const end = new Date(y, m - 1, d + 1).getTime();
        return { start, end };
    }

    /** The device problems to show: per live device its reading's problems (not hidden by the owner) and errors. */
    function problemsOf(live, plant) {
        const devices = new Map(arr(plant && plant.devices).map((d) => [str(d && d.id), d]));
        const out = [];
        for (const d of arr(live && live.devices)) {
            if (!obj(d)) continue;
            const conf = devices.get(str(d.id));
            const name = str(conf && conf.name) || str(d.id) || '';
            const hidden = new Set(arr(conf && conf.hiddenProblems).filter((x) => typeof x === 'string'));
            if (str(d.error)) out.push({ kind: 'NO_CONNECTION', device: name, text: '' });
            for (const p of arr(obj(d.reading) && d.reading.problems)) {
                const kind = str(p && p.kind);
                const text = str(p && p.text) || '';
                if (!['FAULT', 'WARNING', 'MAINS_LOST', 'BACKUP_MODE'].includes(kind)) continue;
                if (hidden.has(text)) continue;
                out.push({ kind, device: name, text });
            }
        }
        const rank = { FAULT: 0, MAINS_LOST: 1, NO_CONNECTION: 2, WARNING: 3, BACKUP_MODE: 4 };
        return out.sort((a, b) => rank[a.kind] - rank[b.kind]);
    }

    /** Series for the day chart from decoded blocks: { pv, load, grid, battery } of [ms, W] with null for gaps. */
    function chartSeries(blocks) {
        const sorted = blocks.slice().sort((a, b) => a.start - b.start);
        const series = { pv: [], load: [], grid: [], battery: [] };
        for (const name of Object.keys(series)) {
            let lastT = null;
            for (const bl of sorted) {
                const ch = bl.channels.find((c) => c.name === name);
                if (!ch) continue;
                ch.values.forEach((v, s) => {
                    if (v === null || !Number.isFinite(v)) return;
                    const t = (bl.start + s * bl.interval) * 1000;
                    if (lastT !== null && t - lastT > 3 * bl.interval * 1000) series[name].push(null);
                    series[name].push([t, v]);
                    lastT = t;
                });
            }
        }
        return series;
    }

    // ------------------------------------------------------------------------------------------------ exports

    const SD = {
        TEXTS, KIND, pickLanguage, FormatError, DataError, LinkError, StoreError,
        b64urlDecode, b64urlEncode, bytesEqual, concat, parseEnvelope, parseFrames, derToRaw, sha256, plantIdOf,
        importPublicKey, importDataKey, verifyEnvelope, inflate, openEnvelope, acceptEnvelope, SeqTracker, parseJson,
        decodeBlock, parseLink, checkStoreUrl, StoreClient, readIndex, homePower, localDate, dayBounds, problemsOf,
        chartSeries,
    };
    window.SolarDashObserver = SD;

    if (document.documentElement.dataset.page !== 'observer') return;

    // ================================================================================================ the page

    const LANG = pickLanguage(navigator.languages && navigator.languages.length ? navigator.languages : [navigator.language]);
    const T = TEXTS[LANG];
    const LOCALE = (() => {
        const l = navigator.language || LANG;
        return l.toLowerCase().startsWith(LANG) ? l : LANG;
    })();

    const LIVE_POLL_MS = 3000;
    const WATCH_MS = 45000;
    const REFRESH_MS = 5 * 60000;
    const RETRY_MS = 60000;
    const FRESH_MS = 2 * 60000;

    const $ = (id) => document.getElementById(id);
    const SVGNS = 'http://www.w3.org/2000/svg';

    function t(key, vars) {
        let s = T[key] !== undefined ? T[key] : TEXTS.en[key] !== undefined ? TEXTS.en[key] : key;
        for (const [k, v] of Object.entries(vars || {})) s = s.split('{' + k + '}').join(v);
        return s;
    }

    const nf0 = new Intl.NumberFormat(LOCALE, { maximumFractionDigits: 0 });
    const nf1 = new Intl.NumberFormat(LOCALE, { minimumFractionDigits: 1, maximumFractionDigits: 1 });
    const nf2 = new Intl.NumberFormat(LOCALE, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    const timeFmt = new Intl.DateTimeFormat(LOCALE, { hour: '2-digit', minute: '2-digit' });
    const dateFmt = new Intl.DateTimeFormat(LOCALE, { day: 'numeric', month: 'short' });
    const yearDateFmt = new Intl.DateTimeFormat(LOCALE, { day: 'numeric', month: 'short', year: 'numeric' });
    const longDateFmt = new Intl.DateTimeFormat(LOCALE, { weekday: 'long', day: 'numeric', month: 'long' });
    const longYearDateFmt = new Intl.DateTimeFormat(LOCALE, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });
    const thisYear = (d) => d.getFullYear() === new Date().getFullYear();

    /** Like the app's formatPower: below 1000 W in W, above in kW with 2 decimals (1 from 10 kW, so it fits). */
    function formatPower(w) {
        if (w === null || w === undefined) return t('noValue');
        const a = Math.abs(Math.round(w));
        if (a >= 10000) return nf1.format(a / 1000) + ' kW';
        return a >= 1000 ? nf2.format(a / 1000) + ' kW' : nf0.format(a) + ' W';
    }
    const formatEnergy = (kwh) => (kwh === null || kwh === undefined ? t('noValue') : nf1.format(kwh) + ' kWh');

    function formatWhen(ms) {
        const d = new Date(ms);
        if (localDate(ms) === localDate(Date.now())) return timeFmt.format(d);
        return (thisYear(d) ? dateFmt : yearDateFmt).format(d) + ', ' + timeFmt.format(d);
    }

    function formatDuration(ms) {
        const min = Math.max(1, Math.floor(ms / 60000));
        if (min < 60) return t('minutes', { n: nf0.format(min) });
        const h = Math.floor(min / 60);
        if (h < 48) return t('hours', { n: nf0.format(h) });
        return t('days', { n: nf0.format(Math.floor(h / 24)) });
    }

    // ------------------------------------------------------------------------------------------------ state

    const state = {
        link: null,
        dataKey: null,
        stores: [],
        client: null,
        publicKey: null,
        seqs: new SeqTracker(),
        plant: null,
        live: null,
        liveAt: 0,
        index: new Map(), // "kind:name" -> seq, for plant blocks, days, plant, stores
        indexSince: 0,
        day: null, // YYYY-MM-DD shown
        dayItem: null,
        blocks: new Map(), // name -> decoded block
        failures: 0,
        lastWatch: 0,
        lastRefresh: 0,
        timers: {},
        busy: false,
    };

    // ------------------------------------------------------------------------------------------------ views

    function show(id, on) { $(id).hidden = !on; }

    function setText(id, text) { const e = $(id); if (e.textContent !== text) e.textContent = text; }

    function showError(key) {
        setText('error-text', t(key));
        show('error', true);
        show('loading', false);
        show('content', state.plant !== null || state.live !== null);
    }

    function hideError() { show('error', false); }

    function setFlow(id, w, idleBelow = 10) {
        // w > 0: towards the hub (or the house for the house line); w < 0: away from it.
        const e = $(id);
        const cls = w === null || Math.abs(w) < idleBelow ? 'flow' : w > 0 ? 'flow fwd' : 'flow rev';
        e.setAttribute('class', cls);
    }

    function renderLive() {
        const live = state.live || {};
        const plant = state.plant || {};
        const offGrid = plant.offGrid === true;
        const pv = num(live.pv);
        const grid = num(live.grid);
        const battery = obj(live.battery);
        const batW = battery ? num(battery.w) : null;
        const soc = battery ? num(battery.soc) : null;
        const home = state.live ? homePower(live, offGrid) : null;

        setText('v-pv', formatPower(pv));
        $('n-pv').setAttribute('aria-label', t('solar') + ' ' + (pv === null ? t('noValue') : Math.round(pv) + ' W'));
        setFlow('f-pv', pv);

        setText('v-home', formatPower(home));
        $('n-home').setAttribute('aria-label', t('home') + ' ' + (home === null ? t('noValue') : Math.round(home) + ' W'));
        setFlow('f-home', home);

        const hasGrid = grid !== null || !offGrid;
        $('n-grid').setAttribute('class', hasGrid ? 'node grid' : 'node grid hidden');
        $('f-grid').parentNode.setAttribute('class', hasGrid ? '' : 'hidden');
        setText('v-grid', formatPower(grid));
        setText('s-grid', grid === null ? (offGrid ? t('offGrid') : '') : Math.abs(grid) < 10 ? '' : grid > 0 ? t('export') : t('import'));
        $('n-grid').setAttribute('aria-label', t('grid') + ' ' + (grid === null ? t('noValue') : Math.round(Math.abs(grid)) + ' W ' + (grid > 0 ? t('export') : t('import'))));
        setFlow('f-grid', grid === null ? null : -grid);
        $('n-grid').classList.toggle('importing', grid !== null && grid <= -10);

        const hasBattery = battery !== null;
        $('n-bat').setAttribute('class', hasBattery ? 'node battery' : 'node battery hidden');
        $('f-bat').parentNode.setAttribute('class', hasBattery ? '' : 'hidden');
        setText('v-bat', formatPower(batW));
        setText('soc', soc === null ? '' : nf0.format(soc) + ' %');
        setText('s-bat', batW === null || Math.abs(batW) < 10 ? t('idle') : batW > 0 ? t('discharging') : t('charging'));
        $('n-bat').setAttribute('aria-label', t('battery') + ' ' + (soc === null ? '' : soc + ' % ') + (batW === null ? '' : Math.round(Math.abs(batW)) + ' W'));
        setFlow('f-bat', batW);
        $('soc-fill').setAttribute('width', String(soc === null ? 0 : Math.max(0, Math.min(100, soc)) * 0.6));

        renderTotals();
        renderProblems();
        renderStatus();
    }

    function renderTotals() {
        const live = state.live;
        let today = null;
        // The collector's own "today" (its local day) while the live values are from the day shown; else the day item.
        if (live && obj(live.today) && localDate(state.liveAt) === state.day) today = live.today;
        if (!today && state.dayItem && obj(state.dayItem.plant)) today = state.dayItem.plant;
        const e = today || {};
        setText('t-pv', formatEnergy(num(e.pv)));
        setText('t-load', formatEnergy(num(e.load)));
        setText('t-export', formatEnergy(num(e.export)));
        setText('t-import', formatEnergy(num(e.import)));
        const isToday = !state.day || state.day === localDate(Date.now());
        const dayStart = new Date(state.day ? dayBounds(state.day).start : Date.now());
        setText('totals-title', isToday ? t('todayTitle') : (thisYear(dayStart) ? longDateFmt : longYearDateFmt).format(dayStart));
        $('t-import-row').hidden = state.plant && state.plant.offGrid === true && num(e.import) === null;
        $('t-export-row').hidden = state.plant && state.plant.offGrid === true && num(e.export) === null;
    }

    function renderProblems() {
        const list = $('problems');
        const offGrid = state.plant && state.plant.offGrid === true;
        const items = state.live ? problemsOf(state.live, state.plant) : [];
        while (list.firstChild) list.removeChild(list.firstChild);
        for (const p of items) {
            const li = document.createElement('li');
            const serious = p.kind === 'FAULT' || (p.kind === 'MAINS_LOST' && !offGrid);
            const info = p.kind === 'BACKUP_MODE' || (p.kind === 'MAINS_LOST' && offGrid);
            li.className = serious ? 'problem bad' : info ? 'problem info' : 'problem warn';
            const kind = document.createElement('strong');
            const key = p.kind === 'NO_CONNECTION' ? 'noConnection' : offGrid && (p.kind === 'MAINS_LOST' || p.kind === 'BACKUP_MODE') ? p.kind + '_offGrid' : p.kind;
            kind.textContent = t(key);
            li.appendChild(kind);
            const rest = [p.device, p.text].filter(Boolean).join(': ');
            if (rest) li.appendChild(document.createTextNode(' – ' + rest));
            list.appendChild(li);
        }
        show('problems-box', items.length > 0);
    }

    function renderStatus() {
        const el = $('status');
        let text, cls;
        if (!state.live) {
            text = t('noLive');
            cls = 'status stale';
        } else {
            const age = Date.now() - state.liveAt;
            if (age < FRESH_MS) {
                text = t('updated', { time: timeFmt.format(new Date(state.liveAt)) });
                cls = 'status ok';
            } else {
                text = t('noDataFor', { dur: formatDuration(age), time: formatWhen(state.liveAt) });
                cls = 'status stale';
            }
        }
        el.className = cls;
        setText('status', text);
        const waiting = (!state.live || Date.now() - state.liveAt >= FRESH_MS) && Date.now() - state.lastWatch < 90000 && state.failures === 0;
        setText('status-note', state.failures >= 2 ? t('storeLost') : waiting ? t('waiting') : '');
        show('status-note', state.failures >= 2 || waiting);
    }

    // ------------------------------------------------------------------------------------------------ chart

    function el(name, attrs, parent) {
        const e = document.createElementNS(SVGNS, name);
        for (const [k, v] of Object.entries(attrs || {})) e.setAttribute(k, String(v));
        if (parent) parent.appendChild(e);
        return e;
    }

    function niceStep(range, ticks) {
        const raw = range / ticks;
        const mag = Math.pow(10, Math.floor(Math.log10(raw)));
        const n = raw / mag;
        return (n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10) * mag;
    }

    function renderChart() {
        const svg = $('chart');
        while (svg.lastChild && svg.lastChild !== svg.firstElementChild) svg.removeChild(svg.lastChild);
        const blocks = [...state.blocks.values()];
        const series = chartSeries(blocks);
        const any = Object.values(series).some((s) => s.length > 0);
        show('chart-empty', !any);
        svg.setAttribute('class', any ? 'chart' : 'chart hidden');
        if (!any || !state.day) return;

        const width = Math.max(280, Math.round(svg.getBoundingClientRect().width || svg.parentNode.clientWidth || 600));
        const height = Math.round(Math.max(220, Math.min(380, width * 0.55)));
        svg.setAttribute('viewBox', '0 0 ' + width + ' ' + height);
        const font = width < 480 ? 14 : 16;
        const pad = { l: font * 3.4, r: 12, t: font * 1.8, b: font * 2.2 };
        const pw = width - pad.l - pad.r, ph = height - pad.t - pad.b;
        const { start, end } = dayBounds(state.day);

        let lo = 0, hi = 0;
        for (const s of Object.values(series)) for (const p of s) if (p) { lo = Math.min(lo, p[1]); hi = Math.max(hi, p[1]); }
        if (hi - lo < 100) hi = lo + 100;
        const step = niceStep((hi - lo) / 1000, height < 300 ? 4 : 5) * 1000;
        lo = Math.floor(lo / step) * step;
        hi = Math.ceil(hi / step) * step;
        const x = (ms) => pad.l + ((ms - start) / (end - start)) * pw;
        const y = (w) => pad.t + (1 - (w - lo) / (hi - lo)) * ph;

        const axis = el('g', { class: 'axis' }, svg);
        const decimals = step >= 1000 ? 0 : step >= 100 ? 1 : 2;
        const tickFmt = new Intl.NumberFormat(LOCALE, { minimumFractionDigits: decimals, maximumFractionDigits: decimals });
        for (let v = lo; v <= hi + step / 2; v += step) {
            el('line', { x1: pad.l, x2: width - pad.r, y1: y(v), y2: y(v), class: Math.abs(v) < step / 2 ? 'zero' : 'gridline' }, axis);
            const label = el('text', { x: pad.l - 6, y: y(v) + font * 0.35, 'text-anchor': 'end', 'font-size': font }, axis);
            label.textContent = tickFmt.format(Math.abs(v) < step / 2 ? 0 : v / 1000);
        }
        const unit = el('text', { x: pad.l - 6, y: font * 1.0, 'text-anchor': 'end', 'font-size': font, class: 'unit' }, axis);
        unit.textContent = t('chartAxis');
        const hourStep = width < 480 ? 6 : 3;
        for (let h = 0; h <= 24; h += hourStep) {
            const ms = new Date(new Date(start).setHours(h, 0, 0, 0)).getTime();
            const xx = h === 24 ? x(end) : x(ms);
            el('line', { x1: xx, x2: xx, y1: pad.t, y2: pad.t + ph, class: 'gridline' }, axis);
            const label = el('text', { x: xx, y: height - font * 0.6, 'text-anchor': h === 0 ? 'start' : h === 24 ? 'end' : 'middle', 'font-size': font }, axis);
            label.textContent = String(h).padStart(2, '0') + ':00';
        }

        for (const name of ['grid', 'battery', 'load', 'pv']) {
            const pts = series[name];
            if (!pts.length) continue;
            let d = '';
            let pen = false;
            for (const p of pts) {
                if (!p) { pen = false; continue; }
                d += (pen ? 'L' : 'M') + x(p[0]).toFixed(1) + ' ' + y(p[1]).toFixed(1);
                pen = true;
            }
            el('path', { d, class: 'line s-' + name }, svg);
        }
        if (localDate(Date.now()) === state.day) {
            const xx = x(Date.now());
            el('line', { x1: xx, x2: xx, y1: pad.t, y2: pad.t + ph, class: 'now' }, svg);
        }
    }

    // ------------------------------------------------------------------------------------------------ data

    function ctx() { return { publicKey: state.publicKey, dataKey: state.dataKey, seqs: state.seqs }; }

    /** Fetches items and returns [{want, content}] for the ones that were new and good. */
    async function fetchChecked(client, pairs) {
        const out = [];
        for (let i = 0; i < pairs.length; i += 200) {
            const chunk = pairs.slice(i, i + 200);
            const wanted = new Map(chunk.map(([kind, name]) => [kind + ':' + name, { kind, name }]));
            const envs = await client.fetchItems(chunk);
            for (const env of envs) {
                const want = wanted.get(env.kind + ':' + env.name);
                if (!want) throw new DataError('Store sent an item that was not asked for');
                wanted.delete(env.kind + ':' + env.name);
                const content = await acceptEnvelope(env, want, ctx());
                if (content) out.push({ want, content, env });
            }
        }
        return out;
    }

    function applyPlant(json) {
        if (!obj(json)) throw new DataError('Bad plant');
        state.plant = json;
        const name = str(json.name) || t('pageTitle');
        setText('plant-name', name);
        document.title = name + ' – SolarDash';
    }

    function applyStores(json) {
        // The plant's signed store list: used for the next attempts; the stores of the link stay as fallback.
        if (!Array.isArray(json)) return;
        const list = [];
        for (const s of json.slice(0, 16)) { const u = checkStoreUrl(s); if (u && !list.includes(u)) list.push(u); }
        for (const u of state.link.stores) if (!list.includes(u)) list.push(u);
        if (list.length) state.stores = list;
    }

    function applyLive(json, env) {
        if (!obj(json)) throw new DataError('Bad live');
        const tt = num(json.t);
        state.live = json;
        state.liveAt = tt !== null && tt > 0 ? tt : env.time;
    }

    /** Opens the plant on one store: its key must be the plant's (id = hash of the key); then plant and stores. */
    async function connect(url) {
        const client = new StoreClient(url, state.link.plantId, state.link.token);
        const spki = await client.key();
        if (!spki) throw new StoreError('No key', 404);
        if ((await plantIdOf(spki)) !== state.link.plantId) throw new DataError('Store sent another plant\'s key');
        const publicKey = await importPublicKey(spki);
        const previous = { publicKey: state.publicKey };
        state.publicKey = publicKey;
        try {
            const got = await fetchChecked(client, [[KIND.PLANT, 'plant'], [KIND.STORES, 'stores']]);
            for (const g of got) {
                const json = parseJson(g.content);
                if (g.want.kind === KIND.PLANT) applyPlant(json);
                else applyStores(json);
            }
        } catch (e) {
            state.publicKey = previous.publicKey;
            throw e;
        }
        return client;
    }

    function errorKey(errors) {
        if (errors.some((e) => e instanceof StoreError && e.status === 403)) return 'errWrongCode';
        if (errors.some((e) => e instanceof DataError || e instanceof FormatError)) return 'errBadData';
        if (errors.some((e) => e instanceof StoreError && e.status === 404)) return 'errNoPlant';
        return 'errUnreachable';
    }

    async function connectAny() {
        const errors = [];
        for (const url of state.stores) {
            try {
                state.client = await connect(url);
                return true;
            } catch (e) {
                errors.push(e);
                if (!(e instanceof StoreError) && !(e instanceof DataError) && !(e instanceof FormatError)) console.error(e);
            }
        }
        state.client = null;
        state.errorKey = errorKey(errors);
        return false;
    }

    async function pollLive() {
        const body = await state.client.live();
        if (!body) return false;
        const env = parseEnvelope(body);
        const content = await acceptEnvelope(env, { kind: KIND.LIVE, name: 'live' }, ctx());
        if (!content) return false;
        applyLive(parseJson(content), env);
        return true;
    }

    /** New index items, then the day shown (the day of the latest live values): its day item and plant blocks. */
    async function refreshDay() {
        state.indexSince = await readIndex(state.client, state.indexSince, (kind, name, seq) => {
            if ((kind === KIND.BLOCK && /^b\d+$/.test(name)) || kind === KIND.DAY || kind === KIND.PLANT || kind === KIND.STORES) {
                const key = kind + ':' + name;
                const old = state.index.get(key);
                if (old === undefined || seq > old) state.index.set(key, seq);
            }
        });
        state.lastRefresh = Date.now();

        const day = localDate(state.live ? state.liveAt : Date.now());
        if (day !== state.day) {
            state.day = day;
            state.dayItem = null;
            state.blocks.clear();
        }
        const { start, end } = dayBounds(day);
        const want = [];
        const seen = (kind, name) => state.seqs.get(kind, name);
        const newer = (kind, name) => { const s = state.index.get(kind + ':' + name); const v = seen(kind, name); return s !== undefined && (v === undefined || s > v); };
        if (newer(KIND.DAY, 'd' + day)) want.push([KIND.DAY, 'd' + day]);
        if (newer(KIND.PLANT, 'plant')) want.push([KIND.PLANT, 'plant']);
        if (newer(KIND.STORES, 'stores')) want.push([KIND.STORES, 'stores']);
        for (const [key] of state.index) {
            if (!key.startsWith(KIND.BLOCK + ':b')) continue;
            const name = key.slice(2);
            const sec = Number(name.slice(1));
            if (sec * 1000 + 3600000 <= start || sec * 1000 >= end) continue;
            if (newer(KIND.BLOCK, name)) want.push([KIND.BLOCK, name]);
        }
        if (!want.length) return;
        const got = await fetchChecked(state.client, want);
        for (const g of got) {
            if (g.want.kind === KIND.BLOCK) {
                try {
                    state.blocks.set(g.want.name, decodeBlock(g.content));
                } catch (e) {
                    console.warn('Bad block', g.want.name, e.message);
                }
            } else {
                const json = parseJson(g.content);
                if (g.want.kind === KIND.DAY) { if (obj(json)) state.dayItem = json; }
                else if (g.want.kind === KIND.PLANT) applyPlant(json);
                else applyStores(json);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ loop

    const visible = () => document.visibilityState === 'visible';

    function stopTimers() {
        for (const k of Object.keys(state.timers)) { clearTimeout(state.timers[k]); delete state.timers[k]; }
    }

    function later(name, ms, fn) {
        clearTimeout(state.timers[name]);
        state.timers[name] = setTimeout(() => { delete state.timers[name]; if (visible()) fn(); }, ms);
    }

    async function watchTick() {
        if (!state.client) return;
        state.lastWatch = Date.now();
        try { await state.client.watch(); } catch (e) { /* the live poll reports trouble */ }
        later('watch', WATCH_MS, watchTick);
    }

    async function liveTick() {
        if (!state.client || state.liveBusy) return;
        state.liveBusy = true;
        try {
            await liveStep();
        } finally {
            state.liveBusy = false;
        }
    }

    async function liveStep() {
        try {
            const fresh = await pollLive();
            state.failures = 0;
            if (fresh) renderLive();
            if (Date.now() - state.lastRefresh > REFRESH_MS || (state.live && localDate(state.liveAt) !== state.day)) {
                await refreshDay();
                renderLive();
                renderChart();
            }
        } catch (e) {
            if (e instanceof DataError || e instanceof FormatError) console.warn('Ignored live values:', e.message);
            else state.failures++;
            if (state.failures >= 3) {
                // Try the other stores (or this one again) from the start.
                state.client = null;
                renderStatus();
                await start(false);
                return;
            }
        }
        renderStatus();
        later('live', LIVE_POLL_MS, liveTick);
    }

    async function start(first) {
        stopTimers();
        if (first) { show('loading', true); }
        const ok = await connectAny();
        if (!ok) {
            showError(state.errorKey);
            later('retry', RETRY_MS, () => start(false));
            return;
        }
        hideError();
        state.failures = 0;
        watchTick();
        try { await pollLive(); } catch (e) { console.warn('Live values:', e.message); }
        try { await refreshDay(); } catch (e) { console.warn('Day:', e.message); }
        if (!state.plant && !state.live) {
            showError('errNoData');
            later('retry', RETRY_MS, () => start(false));
            return;
        }
        show('loading', false);
        show('content', true);
        renderLive();
        renderChart();
        later('live', LIVE_POLL_MS, liveTick);
    }

    function applyTexts() {
        document.documentElement.lang = LANG;
        for (const e of document.querySelectorAll('[data-t]')) e.textContent = t(e.dataset.t);
    }

    async function boot() {
        applyTexts();
        if (!window.crypto || !crypto.subtle || typeof DecompressionStream === 'undefined') {
            showError('errNoCrypto');
            return;
        }
        try {
            state.link = parseLink(location.hash);
        } catch (e) {
            show('open-app', false);
            showError('errBadLink');
            return;
        }
        $('open-app').setAttribute('href', 'solardash://observe#' + state.link.fragment);
        state.stores = state.link.stores.slice();
        state.dataKey = await importDataKey(state.link.dataKey);
        $('retry').addEventListener('click', () => start(true));
        document.addEventListener('visibilitychange', () => {
            if (!visible()) { stopTimers(); return; }
            if (!state.client) { start(false); return; }
            watchTick();
            liveTick();
        });
        let resizeTimer = null;
        window.addEventListener('resize', () => { clearTimeout(resizeTimer); resizeTimer = setTimeout(renderChart, 200); });
        window.addEventListener('hashchange', () => location.reload());
        setInterval(() => { if (visible() && state.live) renderStatus(); }, 15000);
        await start(true);
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
    else boot();
})();
