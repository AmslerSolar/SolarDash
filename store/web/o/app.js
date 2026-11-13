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
            flowTitle: 'Energy flow now',
            // History (the app's History tab)
            historyTitle: 'History',
            periods: 'Period',
            period_day: 'Day',
            period_week: 'Week',
            period_month: 'Month',
            period_year: 'Year',
            period_lifetime: 'Lifetime',
            allTime: 'All time',
            previous: 'Previous',
            next: 'Next',
            exported: 'Exported',
            imported: 'Imported',
            soc: 'Battery charge',
            chartA11y: 'History chart',
            noSamplesDay: 'No samples recorded on this day.',
            noSamplesWeek: 'No samples recorded in this week.',
            noDays: 'No totals recorded in this period.',
            historyLoading: 'Loading the history…',
            historyFailed: 'Part of the history could not be loaded; it is tried again shortly.',
            zoomReset: 'Reset zoom',
            totals: 'Totals',
            solarYield: 'Solar yield',
            selfSufficiency: 'Self-sufficiency',
            selfConsumption: 'Self-consumption',
            addChannel: 'Add channel…',
            removeChannel: 'Remove {x}',
            pickerTitle: 'Add channels',
            pickerDevices: 'Device',
            pickerSearch: 'Search',
            pickerStatus: '{n} selected · {free} of {max} free',
            pickerEmpty: 'Nothing recorded for this device yet.',
            pickerNoDevices: 'This plant shares no device channels.',
            sectionLive: 'Live values',
            add: 'Add',
            cancel: 'Cancel',
            refusedChannels: 'At most {max} added channels at a time, so the chart stays readable. Remove one first.',
            refusedUnits: 'The chart shows at most {max} different units at once ({units}). Remove a series with another unit first.',
            // Fault log (the app's Faults tab)
            alarmsTitle: 'Faults',
            logActive: 'Active',
            logPast: 'History',
            logEmpty: 'No problems recorded yet.',
            logNoActive: 'No problem is going on.',
            logMore: 'Show older entries',
            logOngoing: 'still going on',
            logMinutes: '{m} min',
            logHours: '{h} h {m} min',
            logDays: '{d} d {h} h',
            logStarted: 'Started',
            logEnded: 'Ended',
            logDuration: 'Duration',
            logDevice: 'Device',
            logType: 'Type',
            logReported: 'Reported by the device',
            logAtStart: 'At the start',
            logAtEnd: 'At the end',
            logError: 'Error',
            logErrorKind: 'Kind',
            logMessage: 'Message',
            logStatus: 'Status',
            logPlant: 'Plant',
            logNetwork: 'Network',
            logLocal: 'Local network',
            logInternet: 'Internet',
            logApp: 'App version',
            logYes: 'yes',
            logNo: 'no',
            logAnswered: 'answered',
            logNotAnswered: 'no answer',
            temperature: 'Temperature',
            sev_ALARM: 'Alarm',
            sev_WARNING: 'Warning',
            sev_INFO: 'Information',
            issue_NO_LOCAL_NETWORK: 'No Wi-Fi',
            issue_WRONG_NETWORK: 'Wrong Wi-Fi',
            issue_NOT_ANSWERING: 'No connection',
            issue_ASLEEP: 'Asleep',
            issue_CONNECTION_REFUSED: 'Connection refused',
            issue_LOGIN_FAILED: 'Login failed',
            issue_DEVICE_SETTINGS: 'Check device settings',
            issue_NOT_READY: 'Starting up',
            issue_DEVICE_FAULT: 'Fault',
            issue_DEVICE_WARNING: 'Warning',
            issue_MAINS_LOST: 'No mains power',
            issue_NO_AC_INPUT: 'No AC input',
            issue_BACKUP_MODE: 'Backup mode',
            issue_OFF_GRID_MODE: 'Off-grid mode',
            issue_NO_INTERNET: 'No internet',
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
            flowTitle: 'Energiefluss jetzt',
            historyTitle: 'Verlauf',
            periods: 'Zeitraum',
            period_day: 'Tag',
            period_week: 'Woche',
            period_month: 'Monat',
            period_year: 'Jahr',
            period_lifetime: 'Gesamt',
            allTime: 'Gesamte Laufzeit',
            previous: 'Zurück',
            next: 'Weiter',
            exported: 'Eingespeist',
            imported: 'Bezogen',
            soc: 'Ladestand',
            chartA11y: 'Verlaufsdiagramm',
            noSamplesDay: 'An diesem Tag wurden keine Werte aufgezeichnet.',
            noSamplesWeek: 'In dieser Woche wurden keine Werte aufgezeichnet.',
            noDays: 'In diesem Zeitraum wurden keine Summen aufgezeichnet.',
            historyLoading: 'Der Verlauf wird geladen …',
            historyFailed: 'Ein Teil des Verlaufs konnte nicht geladen werden; die Seite versucht es gleich wieder.',
            zoomReset: 'Zoom zurücksetzen',
            totals: 'Summen',
            solarYield: 'Solarertrag',
            selfSufficiency: 'Autarkie',
            selfConsumption: 'Eigenverbrauch',
            addChannel: 'Kanal hinzufügen…',
            removeChannel: '{x} entfernen',
            pickerTitle: 'Kanäle hinzufügen',
            pickerDevices: 'Gerät',
            pickerSearch: 'Suchen',
            pickerStatus: '{n} ausgewählt · {free} von {max} frei',
            pickerEmpty: 'Für dieses Gerät ist noch nichts aufgezeichnet.',
            pickerNoDevices: 'Diese Anlage teilt keine Gerätekanäle.',
            sectionLive: 'Live-Werte',
            add: 'Hinzufügen',
            cancel: 'Abbrechen',
            refusedChannels: 'Höchstens {max} hinzugefügte Kanäle gleichzeitig, damit das Diagramm lesbar bleibt. Zuerst einen entfernen.',
            refusedUnits: 'Das Diagramm zeigt höchstens {max} verschiedene Einheiten gleichzeitig ({units}). Zuerst eine Kurve mit einer anderen Einheit entfernen.',
            alarmsTitle: 'Störungen',
            logActive: 'Aktiv',
            logPast: 'Verlauf',
            logEmpty: 'Noch keine Probleme aufgezeichnet.',
            logNoActive: 'Im Moment liegt kein Problem vor.',
            logMore: 'Ältere Einträge anzeigen',
            logOngoing: 'dauert noch an',
            logMinutes: '{m} Min.',
            logHours: '{h} Std. {m} Min.',
            logDays: '{d} T. {h} Std.',
            logStarted: 'Begonnen',
            logEnded: 'Beendet',
            logDuration: 'Dauer',
            logDevice: 'Gerät',
            logType: 'Typ',
            logReported: 'Vom Gerät gemeldet',
            logAtStart: 'Zu Beginn',
            logAtEnd: 'Am Ende',
            logError: 'Fehler',
            logErrorKind: 'Art',
            logMessage: 'Meldung',
            logStatus: 'Status',
            logPlant: 'Anlage',
            logNetwork: 'Netzwerk',
            logLocal: 'Lokales Netzwerk',
            logInternet: 'Internet',
            logApp: 'App-Version',
            logYes: 'ja',
            logNo: 'nein',
            logAnswered: 'hat geantwortet',
            logNotAnswered: 'keine Antwort',
            temperature: 'Temperatur',
            sev_ALARM: 'Alarm',
            sev_WARNING: 'Warnung',
            sev_INFO: 'Information',
            issue_NO_LOCAL_NETWORK: 'Kein WLAN',
            issue_WRONG_NETWORK: 'Falsches WLAN',
            issue_NOT_ANSWERING: 'Keine Verbindung',
            issue_ASLEEP: 'Schläft',
            issue_CONNECTION_REFUSED: 'Verbindung abgelehnt',
            issue_LOGIN_FAILED: 'Anmeldung fehlgeschlagen',
            issue_DEVICE_SETTINGS: 'Geräteeinstellungen prüfen',
            issue_NOT_READY: 'Startet',
            issue_DEVICE_FAULT: 'Störung',
            issue_DEVICE_WARNING: 'Warnung',
            issue_MAINS_LOST: 'Kein Netzstrom',
            issue_NO_AC_INPUT: 'Kein AC-Eingang',
            issue_BACKUP_MODE: 'Notstrombetrieb',
            issue_OFF_GRID_MODE: 'Inselbetrieb',
            issue_NO_INTERNET: 'Kein Internet',
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

    // Channel names and detail sections stay English on the wire ("r:pv_power", "d:<section>:<label>"); these are
    // the app's translations (assets/i18n/details_de.tsv) of the names every device has. Driver-specific detail
    // rows stay English here (the app's full dictionary is not part of the page).
    const CHANNEL_TEXTS = {
        de: {
            exact: {
                'Solar power': 'Solarleistung', 'Grid power (+ export)': 'Netzleistung (+ Einspeisung)',
                'Load power': 'Lastleistung', 'Temperature': 'Temperatur',
                'Battery power (+ discharge)': 'Batterieleistung (+ Entladen)', 'Battery state of charge': 'Ladestand Batterie',
                'Battery voltage': 'Batteriespannung', 'Solar yield today': 'Solarertrag heute',
                'Exported today': 'Heute eingespeist', 'Imported today': 'Heute bezogen',
                'Consumption today': 'Verbrauch heute', 'Lifetime yield': 'Gesamtertrag',
                'Live values': 'Live-Werte', 'Measurements': 'Messwerte', 'Device': 'Gerät',
                'Other sources (unverified)': 'Andere Quellen (nicht verifiziert)', 'Specification': 'Spezifikation',
            },
            templates: [
                [/^(.+) voltage$/, '$1 Spannung'], [/^(.+) current$/, '$1 Strom'], [/^(.+) power$/, '$1 Leistung'],
                [/^(.+) frequency$/, '$1 Frequenz'],
            ],
        },
    };

    function translateChannel(text, lang) {
        const d = CHANNEL_TEXTS[lang];
        if (!d || typeof text !== 'string') return text;
        if (Object.prototype.hasOwnProperty.call(d.exact, text)) return d.exact[text];
        for (const [re, tr] of d.templates) if (re.test(text)) return text.replace(re, tr);
        return text;
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
     * want.again: the item was dropped from the page's cache, so the very seq seen before is welcome again (an older
     * one still never is).
     */
    async function acceptEnvelope(env, want, ctx) {
        if (env.kind !== want.kind || env.name !== want.name) throw new DataError('Not the requested item');
        if (!(await verifyEnvelope(env, ctx.publicKey))) throw new DataError('Bad signature on ' + env.name);
        if (ctx.seqs && !ctx.seqs.offer(env.kind, env.name, env.seq, want.again === true)) return null;
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
        /** True (and remembered) when seq is above every seq seen for kind and name (or equal to it, with again). */
        offer(kind, name, seq, again = false) {
            const key = kind + ':' + name;
            const seen = this.map.get(key);
            if (seen !== undefined && (again ? seq < seen : seq <= seen)) return false;
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

    // ------------------------------------------------------------------------------------------------ history

    // The app's History tab (ui/history/, model/History.kt): periods, energy sums, chart channels, zoom. Dates are
    // "YYYY-MM-DD" strings (the plant's local dates, as day items are named), worked on in UTC so no DST shift hits.

    const PERIODS = ['day', 'week', 'month', 'year', 'lifetime'];
    /** Samples further apart break a line (HistoryMath.MAX_GAP_SECONDS); the readout looks this far too. */
    const MAX_GAP_MS = 20 * 60000;
    /** Added channels at most; with the plant's two units (kW, %) the chart keeps to MAX_AXES scales. */
    const MAX_CHANNELS = 3;
    const MAX_AXES = 4;
    const PLANT_UNITS = ['kW', '%'];
    const LIVE_SECTION = 'Live values';
    const ENERGY_KEYS = ['pv', 'export', 'import', 'load'];
    const DAY_RE = /^(\d{4})-(\d{2})-(\d{2})$/;

    const clamp = (x, lo, hi) => Math.min(hi, Math.max(lo, x));

    function isoDay(y, m, d) {
        const t = new Date(Date.UTC(y, m - 1, d));
        const p = (n) => String(n).padStart(2, '0');
        return String(t.getUTCFullYear()).padStart(4, '0') + '-' + p(t.getUTCMonth() + 1) + '-' + p(t.getUTCDate());
    }

    function splitDay(day) {
        const m = DAY_RE.exec(day);
        if (!m) throw new FormatError('Bad date ' + day);
        return [Number(m[1]), Number(m[2]), Number(m[3])];
    }

    function addDays(day, n) {
        const [y, m, d] = splitDay(day);
        return isoDay(y, m, d + n);
    }

    /** Like LocalDate.plusMonths: the day of the month stays, or becomes the month's last where it has fewer. */
    function addMonths(day, n) {
        const [y, m, d] = splitDay(day);
        const last = new Date(Date.UTC(y, m + n, 0)).getUTCDate();
        return isoDay(y, m + n, Math.min(d, last));
    }

    /** First and last day (inclusive) of the period around anchor (HistoryRange); weeks start on Monday. Lifetime: null. */
    function periodBounds(period, anchor) {
        const [y, m, d] = splitDay(anchor);
        if (period === 'day') return { first: anchor, last: anchor };
        if (period === 'week') {
            const first = addDays(anchor, -((new Date(Date.UTC(y, m - 1, d)).getUTCDay() + 6) % 7));
            return { first, last: addDays(first, 6) };
        }
        if (period === 'month') {
            const first = isoDay(y, m, 1);
            return { first, last: addDays(addMonths(first, 1), -1) };
        }
        if (period === 'year') return { first: isoDay(y, 1, 1), last: isoDay(y, 12, 31) };
        return null;
    }

    function shiftAnchor(period, anchor, by) {
        if (period === 'day') return addDays(anchor, by);
        if (period === 'week') return addDays(anchor, 7 * by);
        if (period === 'month') return addMonths(anchor, by);
        if (period === 'year') return addMonths(anchor, 12 * by);
        return anchor;
    }

    /** There is a next period while this one ends before today. */
    function hasNext(period, anchor, today) {
        const b = periodBounds(period, anchor);
        return b !== null && b.last < today;
    }

    function inPeriod(period, anchor, day) {
        const b = periodBounds(period, anchor);
        return b === null || (day >= b.first && day <= b.last);
    }

    function daysOf(first, last) {
        const out = [];
        for (let d = first; d <= last && out.length < 400; d = addDays(d, 1)) out.push(d);
        return out;
    }

    /** A day item's (or live's) totals as { pv, export, import, load }, each kWh or null. */
    function energyOf(x) {
        const o = obj(x) || {};
        const e = {};
        for (const k of ENERGY_KEYS) e[k] = num(o[k]);
        return e;
    }

    /** HistoryMath.sum: per value the sum of the known ones; null where none is known. */
    function sumEnergy(list) {
        const out = {};
        for (const k of ENERGY_KEYS) {
            let s = null;
            for (const e of list) {
                const v = e ? num(e[k]) : null;
                if (v !== null) s = (s || 0) + v;
            }
            out[k] = s;
        }
        return out;
    }

    /** Share of consumption not bought from the grid. */
    function selfSufficiency(e) {
        if (num(e.load) === null || num(e.import) === null) return null;
        return e.load > 0 ? clamp(1 - e.import / e.load, 0, 1) : null;
    }

    /** Share of solar production used on site rather than exported. */
    function selfConsumption(e) {
        if (num(e.pv) === null || num(e.export) === null) return null;
        return e.pv > 0 ? clamp(1 - e.export / e.pv, 0, 1) : null;
    }

    /**
     * The bars of the longer periods (HistoryViewModel): a month one per day (a week too, for its table), a year one
     * per month, the lifetime one per year from the first year with data to today's. days: Map date → energy.
     * Returns [{ key, energy }], key the date, "YYYY-MM" or "YYYY".
     */
    function energySlots(period, anchor, days, today) {
        if (period === 'week' || period === 'month') {
            const b = periodBounds(period, anchor);
            return daysOf(b.first, b.last).map((d) => ({ key: d, energy: days.get(d) || energyOf(null) }));
        }
        const year = anchor.slice(0, 4);
        const groups = new Map();
        for (const [d, e] of days) {
            if (period === 'year' && !d.startsWith(year + '-')) continue;
            const key = period === 'year' ? d.slice(0, 7) : d.slice(0, 4);
            if (!groups.has(key)) groups.set(key, []);
            groups.get(key).push(e);
        }
        const out = [];
        if (period === 'year') {
            for (let m = 1; m <= 12; m++) {
                const key = year + '-' + String(m).padStart(2, '0');
                out.push({ key, energy: sumEnergy(groups.get(key) || []) });
            }
            return out;
        }
        const last = Number(today.slice(0, 4));
        let first = last;
        for (const key of groups.keys()) first = Math.min(first, Number(key));
        for (let y = first; y <= last; y++) out.push({ key: String(y), energy: sumEnergy(groups.get(String(y)) || []) });
        return out;
    }

    /** Display units (Channels.displayUnit): power on one kW axis, energy in kWh. [unit, factor]. */
    function displayUnit(unit) {
        switch (unit) {
            case 'W': return ['kW', 1e-3];
            case 'Wh': return ['kWh', 1e-3];
            case 'VA': return ['kVA', 1e-3];
            case 'var': case 'Var': return ['kvar', 1e-3];
            case 'mV': return ['V', 1e-3];
            case 'MWh': return ['kWh', 1e3];
            case '': return ['value', 1];
            default: return [unit, 1];
        }
    }

    /** "d:<section>:<label>" → [section, label]; the other keys (the normalised reading, "r:…") are live values. */
    function splitKey(key) {
        if (!key.startsWith('d:')) return [LIVE_SECTION, null];
        const i = key.indexOf(':', 2);
        return i < 0 ? [key.slice(2), ''] : [key.slice(2, i), key.slice(i + 1)];
    }

    const channelSection = (key) => splitKey(key)[0];

    /** The channel's own name: for detail-view channels the row label without its section (Channels.name). */
    function channelName(key, label) {
        const n = splitKey(key)[1];
        return n === null ? label : n;
    }

    /** Chart labels stay short; the full name is in the tooltip (Channels.shorten). */
    const shorten = (name, max = 15) => (name.length > max ? name.slice(0, max).trimEnd() + '…' : name);

    /** A device's recorded channels from the plant item: [[key, label, unit]] in the app's order, bad entries left out. */
    function catalogOf(plant, deviceId) {
        const all = obj(plant && plant.channels);
        if (!all || !Object.prototype.hasOwnProperty.call(all, deviceId)) return [];
        return arr(all[deviceId])
            .filter((c) => Array.isArray(c) && c.length >= 3 && c.slice(0, 3).every((x) => typeof x === 'string') && c[0] !== '')
            .map((c) => c.slice(0, 3));
    }

    /**
     * The picker's list (ChannelPicker): the channels matching query (label, shown name or key), grouped by section,
     * the live values first and the detail sections in their order. name(key, label) is the shown name.
     */
    function pickerSections(catalog, query, name) {
        const q = (query || '').trim().toLowerCase();
        const groups = new Map();
        for (const c of catalog) {
            const [key, label] = c;
            if (q && !label.toLowerCase().includes(q) && !name(key, label).toLowerCase().includes(q) && !key.toLowerCase().includes(q)) continue;
            const s = channelSection(key);
            if (!groups.has(s)) groups.set(s, []);
            groups.get(s).push(c);
        }
        const out = [...groups].map(([section, rows]) => ({ section, rows }));
        return out.filter((g) => g.section === LIVE_SECTION).concat(out.filter((g) => g.section !== LIVE_SECTION));
    }

    /**
     * Adds channels in free colour slots (HistoryViewModel.addChannels): { channels }, or { refused: 'channels' |
     * 'units', max, units } when the chart would get too many. unitOf(deviceId, key) is the channel's recorded unit.
     */
    function addChannels(current, selection, unitOf) {
        if (current.length + selection.length > MAX_CHANNELS) return { refused: 'channels', max: MAX_CHANNELS };
        const used = new Set(current.map((c) => c.slot));
        const channels = current.slice();
        for (const [deviceId, key] of selection) {
            let slot = 0;
            while (used.has(slot)) slot++;
            used.add(slot);
            channels.push({ deviceId, key, slot });
        }
        const units = PLANT_UNITS.slice();
        for (const c of channels) {
            const u = displayUnit(unitOf(c.deviceId, c.key) || '')[0];
            if (!units.includes(u)) units.push(u);
        }
        if (units.length > MAX_AXES) return { refused: 'units', max: MAX_AXES, units };
        return { channels };
    }

    const SELECTION_KEY = 'solardash.chart.';

    /** Chart channels as { deviceId, key, slot }: well-formed, no duplicates, at most MAX_CHANNELS, distinct slots. */
    function cleanChannels(list) {
        const out = [];
        for (const c of arr(list)) {
            if (out.length >= MAX_CHANNELS) break;
            if (!obj(c) || typeof c.deviceId !== 'string' || typeof c.key !== 'string' || !c.deviceId || !c.key) continue;
            if (out.some((o) => o.deviceId === c.deviceId && o.key === c.key)) continue;
            const ok = Number.isInteger(c.slot) && c.slot >= 0 && c.slot < MAX_CHANNELS && !out.some((o) => o.slot === c.slot);
            out.push({ deviceId: c.deviceId, key: c.key, slot: ok ? c.slot : null });
        }
        for (const c of out) {
            if (c.slot !== null) continue;
            let s = 0;
            while (out.some((o) => o.slot === s)) s++;
            c.slot = s;
        }
        return out;
    }

    /**
     * What the chart shows at first, as in the app: the owner's chart channels (the plant's chartChannels), every
     * plant curve, and of the energy bars solar and consumption (export and import hidden).
     */
    function defaultSelection(plant) {
        return { channels: cleanChannels(plant && plant.chartChannels), hidden: [], hiddenEnergy: ['export', 'import'], stored: false };
    }

    /** The viewer's own selection for the plant (localStorage), else the default. storage may be null or throw. */
    function loadSelection(storage, plantId, plant) {
        try {
            const raw = storage ? storage.getItem(SELECTION_KEY + plantId) : null;
            const s = raw ? JSON.parse(raw) : null;
            if (obj(s) && s.v === 1) {
                return {
                    channels: cleanChannels(s.channels),
                    hidden: arr(s.hidden).filter((k) => typeof k === 'string').slice(0, 20),
                    hiddenEnergy: arr(s.hiddenEnergy).filter((k) => ENERGY_KEYS.includes(k)),
                    stored: true,
                };
            }
        } catch (e) { /* unreadable or blocked: the default */ }
        return defaultSelection(plant);
    }

    function saveSelection(storage, plantId, sel) {
        try {
            storage.setItem(SELECTION_KEY + plantId, JSON.stringify({ v: 1, channels: sel.channels, hidden: sel.hidden, hiddenEnergy: sel.hiddenEnergy }));
            return true;
        } catch (e) {
            return false;
        }
    }

    /** The visible part of a chart's x range as fractions 0..1 (ChartViewport); minSpan is the deepest zoom. */
    function viewport(minSpan) {
        return { start: 0, end: 1, minSpan: clamp(minSpan, 1e-6, 1) };
    }

    /**
     * Zooms by zoom around anchor (a fraction of the plot width) and pans by pan (a fraction of it too). The window
     * stays within lo..hi: 0..1 is the period itself, beyond are its neighbours (a day or week swiped over).
     */
    function zoomViewport(vp, anchor, zoom, pan, lo = 0, hi = 1) {
        const span = vp.end - vp.start;
        const next = clamp(span / (zoom > 0 && Number.isFinite(zoom) ? zoom : 1), vp.minSpan, 1);
        const a = clamp(anchor, 0, 1);
        const start = clamp(vp.start + a * span - a * next - pan * next, lo, hi - next);
        return { start, end: start + next, minSpan: vp.minSpan };
    }

    const isZoomed = (vp) => vp.end - vp.start < 0.999;

    /** One channel's values from blocks as [epoch ms, value × factor] by time; empty slots (and values keep refuses) left out. */
    function seriesOf(blocks, name, factor = 1, keep = null) {
        const out = [];
        for (const bl of blocks.slice().sort((a, b) => a.start - b.start)) {
            const ch = bl.channels.find((c) => c.name === name);
            if (!ch) continue;
            ch.values.forEach((v, s) => {
                if (v === null || !Number.isFinite(v) || (keep && !keep(v))) return;
                out.push([(bl.start + s * bl.interval) * 1000, v * factor]);
            });
        }
        return out;
    }

    /** Index of the first point at or after t (points by time). */
    function firstAt(points, t) {
        let a = 0, b = points.length;
        while (a < b) {
            const m = (a + b) >> 1;
            if (points[m][0] < t) a = m + 1; else b = m;
        }
        return a;
    }

    /**
     * The points to draw between from and to (and one either side): null where samples are more than maxGap apart;
     * with more than two per bucket, each bucket's lowest and highest only (so peaks stay, at any zoom).
     */
    function thinPoints(points, from, to, buckets, maxGap = MAX_GAP_MS) {
        const lo = Math.max(0, firstAt(points, from) - 1);
        const hi = Math.min(points.length, firstAt(points, to + 1) + 1);
        const thin = hi - lo > 2 * buckets;
        const width = (to - from) / Math.max(1, buckets);
        const out = [];
        let bucket = null, min = null, max = null, prev = null;
        const flush = () => {
            if (min !== null) {
                if (min === max) out.push(min);
                else if (min[0] < max[0]) out.push(min, max);
                else out.push(max, min);
            }
            min = max = null;
        };
        for (let i = lo; i < hi; i++) {
            const p = points[i];
            if (prev && p[0] - prev[0] > maxGap) { flush(); out.push(null); }
            prev = p;
            if (!thin) { out.push(p); continue; }
            const k = Math.floor((p[0] - from) / width);
            if (min === null || k !== bucket) { flush(); bucket = k; min = max = p; continue; }
            if (p[1] < min[1]) min = p;
            if (p[1] > max[1]) max = p;
        }
        flush();
        return out;
    }

    /** An SVG path through points, broken at nulls; a point alone between two breaks draws no line, so it is a dot. */
    function linePath(points, x, y) {
        let d = '';
        const dots = [];
        for (let i = 0; i < points.length; i++) {
            const p = points[i];
            if (!p) continue;
            const before = i > 0 && points[i - 1] !== null;
            const after = i + 1 < points.length && points[i + 1] !== null;
            const px = x(p[0]).toFixed(1), py = y(p[1]).toFixed(1);
            d += (before ? 'L' : 'M') + px + ' ' + py;
            if (!before && !after) dots.push([px, py]);
        }
        return { d, dots };
    }

    /** The point nearest to t, or null when none is within maxGap. */
    function nearestPoint(points, t, maxGap = MAX_GAP_MS) {
        const i = firstAt(points, t);
        let best = null;
        for (const p of [points[i - 1], points[i]]) if (p && (best === null || Math.abs(p[0] - t) < Math.abs(best[0] - t))) best = p;
        return best && Math.abs(best[0] - t) <= maxGap ? best : null;
    }

    /**
     * An axis over lo..hi with 1/2/2.5/5 × 10ⁿ steps: { min, max, step, ticks }. includeZero keeps 0 in view (power,
     * energy); without it the axis fits the data's own range (voltages, temperatures).
     */
    function niceScale(lo, hi, target = 4, includeZero = true) {
        let a = includeZero ? Math.min(lo, 0) : lo;
        let b = includeZero ? Math.max(hi, 0) : hi;
        if (!(b - a > 1e-6)) {
            if (includeZero) b = a + 1;
            else { a -= 1; b += 1; }
        }
        const raw = (b - a) / target;
        const mag = Math.pow(10, Math.floor(Math.log10(raw)));
        const step = [1, 2, 2.5, 5, 10].map((k) => k * mag).find((s) => s >= raw * 0.999);
        const min = Math.floor(a / step + 1e-9) * step;
        const max = Math.ceil(b / step - 1e-9) * step;
        const ticks = [];
        for (let i = 0; min + i * step <= max + step / 2 && i < 50; i++) ticks.push(Number((min + i * step).toPrecision(12)));
        return { min, max, step, ticks };
    }

    /** A % axis always shows 0–100 %. */
    const PERCENT_SCALE = { min: 0, max: 100, step: 25, ticks: [0, 25, 50, 75, 100] };

    const TICK_MINUTES = [15, 30, 60, 120, 180, 240, 360, 720, 1440];

    /** Minutes between time labels: the finest step giving at most maxLabels (and never more than 7) over spanMinutes. */
    function tickMinutes(spanMinutes, maxLabels) {
        const most = Math.max(1, Math.min(7, maxLabels));
        return TICK_MINUTES.find((m) => spanMinutes / m <= most) || 1440;
    }

    /** A fault log entry (observer protocol "Event"), or null when it lacks what the list needs. */
    function eventOf(json) {
        const o = obj(json);
        if (!o || num(o.id) === null || !str(o.kind) || num(o.started) === null) return null;
        return {
            id: o.id, kind: o.kind, severity: ['ALARM', 'WARNING', 'INFO'].includes(o.severity) ? o.severity : 'WARNING',
            deviceName: str(o.deviceName), detail: str(o.detail), started: o.started, ended: num(o.ended),
            details: str(o.details), endDetails: str(o.endDetails),
        };
    }

    /** The fault log as the app shows it: what is still going on, and what ended; each newest first (start, then id). */
    function splitEvents(events) {
        const sorted = events.filter(Boolean).sort((a, b) => b.started - a.started || b.id - a.id);
        return { active: sorted.filter((e) => e.ended === null), past: sorted.filter((e) => e.ended !== null) };
    }

    /** A duration as the fault log writes it: ['min', m], ['hours', h, m] or ['days', d, h]; minutes rounded up. */
    function durationParts(seconds) {
        const minutes = Math.floor((Math.max(0, seconds) + 59) / 60);
        if (minutes < 60) return ['min', minutes];
        if (minutes < 24 * 60) return ['hours', Math.floor(minutes / 60), minutes % 60];
        return ['days', Math.floor(minutes / 1440), Math.floor(minutes / 60) % 24];
    }

    // ------------------------------------------------------------------------------------------------ exports

    const SD = {
        TEXTS, KIND, pickLanguage, translateChannel, FormatError, DataError, LinkError, StoreError,
        b64urlDecode, b64urlEncode, bytesEqual, concat, parseEnvelope, parseFrames, derToRaw, sha256, plantIdOf,
        importPublicKey, importDataKey, verifyEnvelope, inflate, openEnvelope, acceptEnvelope, SeqTracker, parseJson,
        decodeBlock, parseLink, checkStoreUrl, StoreClient, readIndex, homePower, localDate, dayBounds, problemsOf,
        PERIODS, MAX_CHANNELS, MAX_AXES, addDays, addMonths, periodBounds, shiftAnchor, hasNext, inPeriod, daysOf,
        energyOf, sumEnergy, selfSufficiency, selfConsumption, energySlots, displayUnit, channelSection, channelName,
        shorten, catalogOf, pickerSections, addChannels, cleanChannels, defaultSelection, loadSelection, saveSelection,
        viewport, zoomViewport, isZoomed, seriesOf, thinPoints, linePath, nearestPoint, niceScale, tickMinutes,
        eventOf, splitEvents, durationParts,
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
    const HISTORY_RETRY_MS = 30000;
    /** Decoded blocks kept in memory; beyond, the oldest outside the period shown are dropped (and fetched again if needed). */
    const MAX_BLOCKS = 2500;
    /** Fault log entries fetched at most (the collector sends its newest 1000), and list rows shown per step. */
    const MAX_EVENTS = 1000;
    const EVENTS_STEP = 30;

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
    const nfd = [nf0, nf1, nf2];
    /** Times always on the 24-hour clock, whatever the browser's language would pick. */
    const H24 = { hourCycle: 'h23' };
    const timeFmt = new Intl.DateTimeFormat(LOCALE, { hour: '2-digit', minute: '2-digit', ...H24 });
    const dateFmt = new Intl.DateTimeFormat(LOCALE, { day: 'numeric', month: 'short' });
    const yearDateFmt = new Intl.DateTimeFormat(LOCALE, { day: 'numeric', month: 'short', year: 'numeric' });
    const longDateFmt = new Intl.DateTimeFormat(LOCALE, { weekday: 'long', day: 'numeric', month: 'long' });
    const longYearDateFmt = new Intl.DateTimeFormat(LOCALE, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });
    const dayLabelFmt = new Intl.DateTimeFormat(LOCALE, { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' });
    const weekdayDateFmt = new Intl.DateTimeFormat(LOCALE, { weekday: 'short', day: 'numeric', month: 'short' });
    const weekdayFmt = new Intl.DateTimeFormat(LOCALE, { weekday: 'short' });
    const dayNumFmt = new Intl.DateTimeFormat(LOCALE, { day: 'numeric' });
    const monthYearFmt = new Intl.DateTimeFormat(LOCALE, { month: 'long', year: 'numeric' });
    const monthFmt = new Intl.DateTimeFormat(LOCALE, { month: 'short' });
    const dayTimeFmt = new Intl.DateTimeFormat(LOCALE, { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', ...H24 });
    const dateTimeFmt = new Intl.DateTimeFormat(LOCALE, { dateStyle: 'short', timeStyle: 'short', ...H24 });
    const shortTimeFmt = new Intl.DateTimeFormat(LOCALE, { timeStyle: 'short', ...H24 });
    const thisYear = (d) => d.getFullYear() === new Date().getFullYear();
    /** A local date as a Date at noon (clear of DST changes), for formatting. */
    const dateOf = (day) => { const [y, m, d] = splitDay(day); return new Date(y, m - 1, d, 12); };

    /** Like the app's formatPower: below 1000 W in W, above in kW with 2 decimals (1 from 10 kW, so it fits). */
    function formatPower(w) {
        if (w === null || w === undefined) return t('noValue');
        const a = Math.abs(Math.round(w));
        if (a >= 10000) return nf1.format(a / 1000) + ' kW';
        return a >= 1000 ? nf2.format(a / 1000) + ' kW' : nf0.format(a) + ' W';
    }
    const formatEnergy = (kwh) => (kwh === null || kwh === undefined ? t('noValue') : nf1.format(kwh) + ' kWh');
    const formatShare = (v) => (v === null ? t('noValue') : nf0.format(Math.round(v * 100)) + ' %');

    /** A chart value as the app's readout writes it: % without decimals, from 100 one, else two. */
    function formatValue(v, unit) {
        const n = nfd[unit === '%' ? 0 : Math.abs(v) >= 100 ? 1 : 2].format(v);
        return unit === 'value' ? n : n + ' ' + unit;
    }

    /** Axis labels with as many decimals as the step needs. */
    function axisTick(v, step) {
        const decimals = Number.isInteger(Number(step.toPrecision(6))) ? 0 : Number.isInteger(Number((step * 10).toPrecision(6))) ? 1 : 2;
        return nfd[decimals].format(v);
    }

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

    function storage() {
        try {
            return window.localStorage;
        } catch (e) {
            return null;
        }
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
        index: new Map(), // "kind:name" -> seq of every stored item but live
        indexSince: 0,
        dayNames: new Set(), // day items in the index
        eventNames: new Map(), // event name -> seq
        day: null, // YYYY-MM-DD of the latest live values: the totals at the top
        blocks: new Map(), // block name -> decoded block (plant and device blocks)
        days: new Map(), // YYYY-MM-DD -> energy of its day item
        events: new Map(), // event name -> entry
        evicted: new Set(), // "kind:name" dropped from the caches: welcome again with the seq seen before
        eventsWanted: false, // the fault log came into view: fetch it (once, then what is new)
        eventsLoaded: false,
        eventsShown: EVENTS_STEP,
        expanded: new Set(), // fault log entries (ids) with their details open
        hist: {
            period: 'day',
            anchor: null, // a date in the period shown
            follow: true, // the period holds the latest live values: it moves on with them
            reach: [-1, 0], // neighbouring periods drawn and fetched too (-1 before, 1 after): baseReach, and what a swipe reached
            dragging: false, // a gesture is moving the time window: only the chart follows until it ends
            vp: viewport(1),
            vpKey: '',
            selected: null, // epoch ms (day, week) or slot index (bars)
            loading: 0,
            failed: false,
            failedAt: 0,
            sel: null, // the chart selection (channels, hidden curves and bars)
        },
        model: null,
        geom: null,
        picker: null,
        failures: 0,
        lastWatch: 0,
        lastRefresh: 0,
        timers: {},
    };

    // ------------------------------------------------------------------------------------------------ views

    function show(id, on) { $(id).hidden = !on; }

    function setText(id, text) { const e = $(id); if (e.textContent !== text) e.textContent = text; }

    function clear(e) { while (e.firstChild) e.removeChild(e.firstChild); return e; }

    /** An HTML element with a class and text. */
    function h(name, cls, text, parent) {
        const e = document.createElement(name);
        if (cls) e.className = cls;
        if (text !== undefined && text !== null) e.textContent = text;
        if (parent) parent.appendChild(e);
        return e;
    }

    function button(cls, text, parent) {
        const b = h('button', cls, text, parent);
        b.type = 'button';
        return b;
    }

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
        // The ring's length (2π · 63 ≈ 396) filled as far as the charge.
        const ring = 2 * Math.PI * 63;
        $('soc-ring').setAttribute('stroke-dasharray', (soc === null ? 0 : Math.max(0, Math.min(100, soc)) / 100 * ring).toFixed(1) + ' ' + ring.toFixed(1));

        renderTotals();
        renderProblems();
        renderStatus();
    }

    /** A day's totals: the collector's own "today" (its local day) while the live values are from that day, else the day item. */
    function dayTotals(day) {
        if (state.live && obj(state.live.today) && localDate(state.liveAt) === day) return energyOf(state.live.today);
        return state.days.get(day) || null;
    }

    function renderTotals() {
        const e = (state.day && dayTotals(state.day)) || energyOf(null);
        setText('t-pv', formatEnergy(e.pv));
        setText('t-load', formatEnergy(e.load));
        setText('t-export', formatEnergy(e.export));
        setText('t-import', formatEnergy(e.import));
        const isToday = !state.day || state.day === localDate(Date.now());
        const dayStart = new Date(state.day ? dayBounds(state.day).start : Date.now());
        setText('totals-title', isToday ? t('todayTitle') : (thisYear(dayStart) ? longDateFmt : longYearDateFmt).format(dayStart));
        $('t-import-row').hidden = state.plant && state.plant.offGrid === true && e.import === null;
        $('t-export-row').hidden = state.plant && state.plant.offGrid === true && e.export === null;
    }

    function renderProblems() {
        const list = $('problems');
        const offGrid = state.plant && state.plant.offGrid === true;
        const items = state.live ? problemsOf(state.live, state.plant) : [];
        clear(list);
        for (const p of items) {
            const serious = p.kind === 'FAULT' || (p.kind === 'MAINS_LOST' && !offGrid);
            const info = p.kind === 'BACKUP_MODE' || (p.kind === 'MAINS_LOST' && offGrid);
            const li = h('li', serious ? 'problem bad' : info ? 'problem info' : 'problem warn', null, list);
            const key = p.kind === 'NO_CONNECTION' ? 'noConnection' : offGrid && (p.kind === 'MAINS_LOST' || p.kind === 'BACKUP_MODE') ? p.kind + '_offGrid' : p.kind;
            h('strong', null, t(key), li);
            const rest = [p.device, p.text].filter(Boolean).join(': ');
            if (rest) li.appendChild(document.createTextNode(' – ' + rest));
        }
        show('problems-box', items.length > 0);
    }

    function renderStatus() {
        const e = $('status');
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
        e.className = cls;
        setText('status', text);
        const waiting = (!state.live || Date.now() - state.liveAt >= FRESH_MS) && Date.now() - state.lastWatch < 90000 && state.failures === 0;
        setText('status-note', state.failures >= 2 ? t('storeLost') : waiting ? t('waiting') : '');
        show('status-note', state.failures >= 2 || waiting);
    }

    // ------------------------------------------------------------------------------------------------ history

    const PLANT_LINES = [
        { name: 'pv', text: 'solar', cls: 's-pv' }, { name: 'load', text: 'consumption', cls: 's-load' },
        { name: 'battery', text: 'battery', cls: 's-battery' }, { name: 'grid', text: 'grid', cls: 's-grid' },
    ];
    const ENERGY_BARS = [
        { key: 'pv', text: 'solar', cls: 's-pv' }, { key: 'load', text: 'consumption', cls: 's-load' },
        { key: 'export', text: 'exported', cls: 's-grid' }, { key: 'import', text: 'imported', cls: 's-import' },
    ];
    /** Every curve is a thin solid line; the colour alone tells them apart (battery charge and each channel have their own). */
    const lineClass = (l) => 'line ' + l.cls;

    const devicesOf = () => arr(state.plant && state.plant.devices).filter((d) => obj(d) && str(d.id));

    function deviceName(id) {
        const d = devicesOf().find((x) => x.id === id);
        return str(d && d.name) || id;
    }

    /** The selection's channels whose device is still part of the plant. */
    function chartChannels() {
        const ids = new Set(devicesOf().map((d) => d.id));
        return state.hist.sel ? state.hist.sel.channels.filter((c) => ids.has(c.deviceId)) : [];
    }

    function unitOf(deviceId, key) {
        const e = catalogOf(state.plant, deviceId).find((c) => c[0] === key);
        return e ? e[2] : '';
    }

    const shownName = (key, label) => translateChannel(channelName(key, label), LANG);
    const sectionName = (s) => (s === LIVE_SECTION ? t('sectionLive') : translateChannel(s, LANG));

    /** The period's epoch-ms bounds and the UTC hours (block starts, epoch s) that overlap it. */
    function hoursOf(period, anchor) {
        const b = periodBounds(period, anchor);
        const start = dayBounds(b.first).start;
        const end = dayBounds(b.last).end;
        const hours = [];
        for (let s = Math.floor(start / 3600000) * 3600; s * 1000 < end; s += 3600) hours.push(s);
        return { start, end, hours };
    }

    /** A day comes with the day before, so a swipe back has its curves at once; a week's neighbours wait for the swipe. */
    const baseReach = (period) => (period === 'day' ? [-1, 0] : [0, 0]);

    /** The hours whose blocks the chart draws: the period shown and its neighbours within reach. */
    function reachHours() {
        const hs = state.hist;
        const out = new Set();
        for (let k = hs.reach[0]; k <= hs.reach[1]; k++) {
            for (const s of hoursOf(hs.period, k ? shiftAnchor(hs.period, hs.anchor, k) : hs.anchor).hours) out.add(s);
        }
        return [...out];
    }

    /** Block names the period shown needs: the plant's and those of the devices with a channel on the chart. */
    function rangeBlockNames() {
        const hs = state.hist;
        if (hs.period !== 'day' && hs.period !== 'week') return [];
        const devices = [...new Set(chartChannels().map((c) => c.deviceId))];
        const out = [];
        for (const s of reachHours()) {
            out.push('b' + s);
            for (const d of devices) out.push('b' + s + '.' + d);
        }
        return out;
    }

    function rangeLabel(period, anchor) {
        if (period === 'day') return dayLabelFmt.format(dateOf(anchor));
        if (period === 'week') {
            const b = periodBounds(period, anchor);
            const s = dateOf(b.first), e = dateOf(b.last);
            return (s.getFullYear() === e.getFullYear() ? dateFmt : yearDateFmt).format(s) + ' – ' + yearDateFmt.format(e);
        }
        if (period === 'month') return monthYearFmt.format(dateOf(anchor));
        if (period === 'year') return anchor.slice(0, 4);
        return t('allTime');
    }

    /** [label, short label] of a bar or table row. */
    function slotLabels(period, key) {
        if (period === 'week' || period === 'month') {
            const d = dateOf(key);
            return [weekdayDateFmt.format(d), (period === 'week' ? weekdayFmt : dayNumFmt).format(d)];
        }
        if (period === 'year') {
            const d = dateOf(key + '-01');
            return [monthYearFmt.format(d), monthFmt.format(d)];
        }
        return [key, key];
    }

    /** Day and week: curves of the plant (kW, %) and the added channels, each in its display unit. */
    function powerModel() {
        const hs = state.hist;
        const { start, end } = hoursOf(hs.period, hs.anchor);
        const hours = reachHours();
        const plantBlocks = hours.map((s) => state.blocks.get('b' + s)).filter(Boolean);
        const lines = PLANT_LINES.map((p) => ({
            key: 'plant:' + p.name, label: t(p.text), full: t(p.text), unit: 'kW', cls: p.cls,
            points: seriesOf(plantBlocks, p.name, 1e-3),
        }));
        // Some devices report nonsense outside 0–100 % (seen on LG before the scale fix); it is left out, as in the app.
        lines.push({ key: 'plant:soc', label: t('soc'), full: t('soc'), unit: '%', cls: 's-soc', points: seriesOf(plantBlocks, 'soc', 1, (v) => v >= 0 && v <= 100) });
        for (const c of chartChannels()) {
            const entry = catalogOf(state.plant, c.deviceId).find((e) => e[0] === c.key);
            const [unit, factor] = displayUnit(entry ? entry[2] : '');
            const name = shownName(c.key, entry ? entry[1] : c.key);
            const section = c.key.startsWith('d:') ? ' (' + sectionName(channelSection(c.key)) + ')' : '';
            const blocks = hours.map((s) => state.blocks.get('b' + s + '.' + c.deviceId)).filter(Boolean);
            lines.push({
                key: c.deviceId + '|' + c.key, channel: c, label: shorten(name), full: name + ' — ' + deviceName(c.deviceId) + section,
                unit, cls: 'c-' + (c.slot % 3), points: seriesOf(blocks, c.key, factor),
            });
        }
        const b = periodBounds(hs.period, hs.anchor);
        const slots = hs.period === 'week' ? daysOf(b.first, b.last).map((d) => ({ key: d, energy: dayTotals(d) || energyOf(null) })) : [];
        return {
            kind: 'power', start, end, lines, slots, totals: periodTotals(),
            empty: lines.every((l) => !l.points.some((p) => p[0] >= start && p[0] < end)), emptyKey: hs.period === 'day' ? 'noSamplesDay' : 'noSamplesWeek',
            minSpan: 1800000 / (end - start), // half an hour at most, whatever the period
        };
    }

    /** The Totals card: the day's, or the sum of the period's days (today's from live while it is the live day). */
    function periodTotals() {
        const hs = state.hist;
        if (hs.period === 'day') return dayTotals(hs.anchor) || energyOf(null);
        if (hs.period === 'week') {
            const b = periodBounds(hs.period, hs.anchor);
            return sumEnergy(daysOf(b.first, b.last).map(dayTotals));
        }
        return energyModel().totals;
    }

    /** Month, year, lifetime: energy bars from the day items. */
    function energyModel() {
        const hs = state.hist;
        const days = new Map(state.days);
        if (state.live && obj(state.live.today)) days.set(localDate(state.liveAt), energyOf(state.live.today));
        const slots = energySlots(hs.period, hs.anchor, days, localDate(Date.now())).map((s) => {
            const [label, short] = slotLabels(hs.period, s.key);
            return { key: s.key, energy: s.energy, label, short };
        });
        return {
            kind: 'energy', slots, totals: sumEnergy(slots.map((s) => s.energy)),
            empty: slots.every((s) => ENERGY_KEYS.every((k) => s.energy[k] === null)), emptyKey: 'noDays',
            minSpan: 3 / Math.max(1, slots.length),
        };
    }

    /** Bars without data leave no chart; curves keep theirs, so a swipe can go on over a day without samples. */
    const blank = (m) => m.empty && m.kind !== 'power';

    function renderHistory() {
        const hs = state.hist;
        if (!hs.anchor || !hs.sel) return;
        if (hs.dragging) { renderDrag(); return; } // e.g. a resize in the middle of a swipe
        const today = localDate(Date.now());
        for (const b of document.querySelectorAll('#periods button')) b.setAttribute('aria-pressed', String(b.dataset.period === hs.period));
        setText('range-label', rangeLabel(hs.period, hs.anchor));
        $('prev').disabled = hs.period === 'lifetime';
        $('next').disabled = !hasNext(hs.period, hs.anchor, today);
        const m = hs.period === 'day' || hs.period === 'week' ? powerModel() : energyModel();
        const key = hs.period + ':' + hs.anchor;
        if (hs.vpKey !== key) {
            const first = hs.vpKey === '';
            hs.vpKey = key;
            hs.vp = viewport(m.minSpan);
            hs.selected = null;
            // The first view has the latest values in the middle: the last 12 hours to their left, on into the day before.
            const latest = state.live ? state.liveAt : Date.now();
            if (first && hs.period === 'day' && latest >= m.start && latest < m.end) {
                const start = (latest - m.start) / (m.end - m.start) - 0.5;
                hs.vp = { start, end: start + 1, minSpan: hs.vp.minSpan };
            }
        }
        state.model = m;
        $('history').setAttribute('aria-busy', String(hs.loading > 0));
        renderLegend(m);
        const note = hs.loading > 0 ? t('historyLoading') : hs.failed ? t('historyFailed') : m.empty ? t(m.emptyKey) : '';
        setText('history-state', note);
        show('history-state', note !== '');
        show('chart-wrap', !blank(m));
        show('chart-tools', !blank(m));
        if (!blank(m)) renderChart();
        renderReadout();
        renderPeriodTotals(m.totals);
    }

    /** A short sample of a curve in its colour, so the legend matches the chart. */
    function lineSample(line) {
        const svg = el('svg', { class: 'sample', viewBox: '0 0 22 10', width: 22, height: 10, 'aria-hidden': 'true' });
        el('line', { x1: 1, x2: 21, y1: 5, y2: 5, class: lineClass(line) }, svg);
        return svg;
    }

    /** Series toggles, which are the legend too: every colour has its name next to it. Added channels can be removed. */
    function renderLegend(m) {
        const ul = clear($('legend'));
        const sel = state.hist.sel;
        if (m.kind === 'power') {
            for (const line of m.lines) {
                const li = h('li', 'chip-item', null, ul);
                const b = button('chip', null, li);
                b.dataset.toggle = line.key;
                b.setAttribute('aria-pressed', String(!sel.hidden.includes(line.key)));
                b.title = line.full + ' (' + line.unit + ')';
                b.appendChild(lineSample(line));
                h('span', null, line.unit === 'kW' && !line.channel ? line.label : line.label + ' · ' + line.unit, b);
                if (line.channel) {
                    const x = button('chip-remove', '×', li);
                    x.dataset.remove = line.key;
                    x.setAttribute('aria-label', t('removeChannel', { x: line.full }));
                    x.title = x.getAttribute('aria-label');
                }
            }
            const add = button('chip add', t('addChannel'), h('li', 'chip-item', null, ul));
            add.id = 'add-channel';
            add.disabled = chartChannels().length >= MAX_CHANNELS;
        } else {
            for (const bar of ENERGY_BARS) {
                const b = button('chip', null, h('li', 'chip-item', null, ul));
                b.dataset.toggleEnergy = bar.key;
                b.setAttribute('aria-pressed', String(!sel.hiddenEnergy.includes(bar.key)));
                h('span', 'dot ' + bar.cls, null, b).setAttribute('aria-hidden', 'true');
                h('span', null, t(bar.text), b);
            }
        }
    }

    /** Touch screens (phones, tablets): the cursor of the curves is fixed in the middle and the curves move under it. */
    const centredCursor = () => !!(window.matchMedia && matchMedia('(pointer: coarse)').matches);

    /** The time the curves' cursor is at: the middle of the window on touch screens, else where the mouse or keys put it (or null). */
    function cursorTime(m) {
        const hs = state.hist;
        return centredCursor() ? m.start + ((hs.vp.start + hs.vp.end) / 2) * (m.end - m.start) : hs.selected;
    }

    function renderReadout() {
        const box = clear($('readout'));
        const hs = state.hist, m = state.model;
        // Below the chart, so it never moves the chart under the finger; nothing until a time or bar is picked.
        const at = m && m.kind === 'power' ? cursorTime(m) : hs.selected;
        box.hidden = !m || blank(m) || at === null;
        if (box.hidden) return;
        const values = [];
        let title;
        if (m.kind === 'power') {
            title = (hs.period === 'week' ? dayTimeFmt : timeFmt).format(new Date(at));
            for (const l of m.lines) {
                if (hs.sel.hidden.includes(l.key)) continue;
                const p = nearestPoint(l.points, at);
                if (p) values.push([l.cls, l.label, formatValue(p[1], l.unit)]);
            }
        } else {
            const s = m.slots[hs.selected];
            title = s ? s.label : '';
            for (const b of ENERGY_BARS) {
                if (!s || hs.sel.hiddenEnergy.includes(b.key) || s.energy[b.key] === null) continue;
                values.push([b.cls, t(b.text), formatEnergy(s.energy[b.key])]);
            }
        }
        h('strong', null, title, box);
        if (!values.length) return;
        const ul = h('ul', null, null, box);
        for (const [cls, label, value] of values) {
            const li = h('li', null, null, ul);
            h('span', 'dot ' + cls, null, li).setAttribute('aria-hidden', 'true');
            li.appendChild(document.createTextNode(label + ' ' + value));
        }
    }

    function renderPeriodTotals(e) {
        const dl = clear($('period-totals'));
        const rows = [
            [t('solarYield'), formatEnergy(e.pv)], [t('consumption'), formatEnergy(e.load)],
            [t('exported'), formatEnergy(e.export)], [t('imported'), formatEnergy(e.import)],
            [t('selfSufficiency'), formatShare(selfSufficiency(e))], [t('selfConsumption'), formatShare(selfConsumption(e))],
        ];
        for (const [k, v] of rows) {
            const row = h('div', 'row', null, dl);
            h('dt', null, k, row);
            h('dd', null, v, row);
        }
    }

    // ------------------------------------------------------------------------------------------------ chart

    function el(name, attrs, parent) {
        const e = document.createElementNS(SVGNS, name);
        for (const [k, v] of Object.entries(attrs || {})) e.setAttribute(k, String(v));
        if (parent) parent.appendChild(e);
        return e;
    }

    function text(parent, x, y, s, attrs) {
        const e = el('text', Object.assign({ x, y }, attrs), parent);
        e.textContent = s;
        return e;
    }

    function chartSize(svg) {
        const width = Math.max(240, Math.round(svg.getBoundingClientRect().width || svg.parentNode.clientWidth || 600));
        const height = Math.min(width, 480); // a square on phones; wide screens stop at 480
        return { width, height, font: width < 480 ? 13 : 15 };
    }

    /** Width of a label in the chart's font, for the patch behind it (canvas measureText; estimated without). */
    function textMeasure(svg, font) {
        const ctx = (textMeasure.canvas ||= document.createElement('canvas')).getContext('2d');
        if (!ctx) return (s) => s.length * font * 0.6;
        const cs = getComputedStyle(svg);
        ctx.font = font + 'px ' + cs.fontFamily;
        return (s) => ctx.measureText(s).width;
    }

    let frame = 0;
    /** Redraws the chart and readout on the next frame (gestures and hover come faster than that). */
    function scheduleChart() {
        if (frame) return;
        frame = requestAnimationFrame(() => {
            frame = 0;
            if (!state.model || blank(state.model)) return;
            renderChart();
            renderReadout();
        });
    }

    /** While a gesture moves the time window only the date and the chart follow; the rest comes when it ends. */
    function renderDrag() {
        const hs = state.hist;
        setText('range-label', rangeLabel(hs.period, hs.anchor));
        $('next').disabled = !hasNext(hs.period, hs.anchor, localDate(Date.now()));
        state.model = powerModel();
        scheduleChart();
    }

    let loadTimer = 0;
    /**
     * After the window of a day or week moved: the period under its middle becomes the one shown (date, totals; the
     * window keeps its place in time), and a neighbour coming into view is drawn from the cache and fetched.
     */
    function slideWindow() {
        const hs = state.hist, m = state.model;
        const mid = (hs.vp.start + hs.vp.end) / 2;
        const by = mid < 0 ? -1 : mid > 1 ? 1 : 0;
        if (by) {
            const dur = m.end - m.start, from = m.start + hs.vp.start * dur, spanMs = (hs.vp.end - hs.vp.start) * dur;
            hs.anchor = shiftAnchor(hs.period, hs.anchor, by);
            hs.follow = inPeriod(hs.period, hs.anchor, state.day || localDate(Date.now()));
            hs.reach = [Math.min(baseReach(hs.period)[0], clamp(hs.reach[0] - by, -1, 0)), clamp(hs.reach[1] - by, 0, 1)];
            const b = hoursOf(hs.period, hs.anchor), len = b.end - b.start;
            const start = (from - b.start) / len;
            hs.vp = { start, end: start + spanMs / len, minSpan: clamp(1800000 / len, 1e-6, 1) };
            hs.vpKey = hs.period + ':' + hs.anchor;
        }
        const lo = hs.vp.start < -1e-6 ? -1 : 0, hi = hs.vp.end > 1 + 1e-6 ? 1 : 0;
        const wider = lo < hs.reach[0] || hi > hs.reach[1];
        if (wider) hs.reach = [Math.min(lo, hs.reach[0]), Math.max(hi, hs.reach[1])];
        if (!by && !wider) { scheduleChart(); return; }
        renderDrag();
        clearTimeout(loadTimer);
        loadTimer = setTimeout(() => loadHistory(true), 120);
    }

    /** The gesture ended: the window stays where it is, and everything else catches up (totals, legend, notes). */
    function settle() {
        const hs = state.hist;
        hs.dragging = false;
        renderHistory();
        loadHistory(true);
    }

    function renderChart() {
        const svg = $('chart');
        while (svg.lastChild && svg.lastChild !== svg.firstElementChild) svg.removeChild(svg.lastChild);
        if (state.model.kind === 'power') drawPower(svg, state.model);
        else drawEnergy(svg, state.model);
        show('zoom-reset', isZoomed(state.hist.vp));
    }

    /**
     * Curves over a day or a week with one y-axis per unit (up to four, PowerChart): the first unit on the left, the
     * second on the right, then a second left and a second right axis. Each axis fits its visible data; % is 0–100.
     * The curves use the whole width; the scales are drawn over them, each label on a patch of the background.
     */
    function drawPower(svg, m) {
        const hs = state.hist, vp = hs.vp;
        const { width, height, font } = chartSize(svg);
        svg.setAttribute('viewBox', '0 0 ' + width + ' ' + height);
        const lines = m.lines.filter((l) => !hs.sel.hidden.includes(l.key));
        const units = [];
        for (const l of lines) if (l.points.length && !units.includes(l.unit) && units.length < MAX_AXES) units.push(l.unit);
        const top = Math.round(font * 1.7), bottom = height - Math.round(font * 1.9);
        const dur = m.end - m.start, span = vp.end - vp.start;
        const from = m.start + vp.start * dur, to = m.start + vp.end * dur;

        const drawn = new Map(lines.map((l) => [l, thinPoints(l.points, from, to, width)]));
        const scales = new Map();
        for (const u of units) {
            if (u === '%') { scales.set(u, PERCENT_SCALE); continue; }
            let lo = Infinity, hi = -Infinity;
            for (const l of lines) {
                if (l.unit !== u) continue;
                for (const p of drawn.get(l)) if (p && p[0] >= from && p[0] <= to) { lo = Math.min(lo, p[1]); hi = Math.max(hi, p[1]); }
            }
            if (lo === Infinity) { lo = 0; hi = 1; }
            // Power and energy keep zero in view; other quantities (V, °C, Hz) fit their own range.
            scales.set(u, niceScale(lo, hi, 4, u.startsWith('k') || u === 'W' || lo <= 0));
        }
        const left = 0, right = width;
        const x = (ms) => left + ((ms - m.start) / dur - vp.start) / span * (right - left);
        const y = (sc, v) => bottom - ((v - sc.min) / (sc.max - sc.min)) * (bottom - top);

        const axis = el('g', { class: 'axis' }, svg);
        if (units.length) {
            const sc = scales.get(units[0]);
            for (const v of sc.ticks) el('line', { x1: left, x2: right, y1: y(sc, v), y2: y(sc, v), class: Math.abs(v) < sc.step / 2 ? 'zero' : 'gridline' }, axis);
        }
        drawTimeAxis(axis, m, x, { left, right, top, bottom, font, from, to });

        const clip = el('clipPath', { id: 'plot-clip' }, svg);
        el('rect', { x: left, y: 0, width: right - left, height }, clip);
        const g = el('g', { 'clip-path': 'url(#plot-clip)' }, svg);
        const now = Date.now();
        if (now > from && now < to) el('line', { x1: x(now), x2: x(now), y1: top, y2: bottom, class: 'now' }, g);
        for (const l of lines) {
            const sc = scales.get(l.unit);
            if (!sc) continue;
            const { d, dots } = linePath(drawn.get(l), x, (v) => y(sc, v));
            if (d) el('path', { d, class: lineClass(l) }, g);
            for (const [cx, cy] of dots) el('circle', { cx, cy, r: 1.8, class: 'dot ' + l.cls }, g);
        }
        const at = cursorTime(m);
        if (at !== null) {
            const xx = x(at);
            el('line', { x1: xx, x2: xx, y1: top, y2: bottom, class: 'cursor' }, g);
            if (centredCursor()) {
                // The fixed cursor's marks: above the plot and on its foot (clear of the time labels below).
                const a = Math.round(font * 0.45), f = (v) => v.toFixed(1);
                el('path', { d: 'M' + f(xx - a) + ' ' + f(top - 2 * a) + 'H' + f(xx + a) + 'L' + f(xx) + ' ' + f(top) + 'Z', class: 'cursor-mark' }, g);
                el('path', { d: 'M' + f(xx - a) + ' ' + f(bottom) + 'H' + f(xx + a) + 'L' + f(xx) + ' ' + f(bottom - 2 * a) + 'Z', class: 'cursor-mark' }, g);
            }
        }

        // The scales, over the curves: first unit at the left edge, second at the right, the third and fourth inside those.
        const scale = el('g', { class: 'axis' }, svg);
        const measure = textMeasure(svg, font);
        const pad = 4, patchH = Math.round(font * 1.35);
        const columnW = units.map((u) => Math.max(u === 'value' ? 0 : measure(u) * 1.1, ...scales.get(u).ticks.map((v) => measure(axisTick(v, scales.get(u).step)))) + 2 * pad); // the unit is bold
        units.forEach((u, i) => {
            const sc = scales.get(u);
            const onLeft = i % 2 === 0;
            const inset = 3 + (i >= 2 ? columnW[i - 2] + 3 : 0);
            const edge = onLeft ? inset : width - inset;
            const tx = onLeft ? edge + pad : edge - pad;
            const anchor = onLeft ? 'start' : 'end';
            for (const v of sc.ticks) {
                const s = axisTick(v, sc.step);
                const w = measure(s) + 2 * pad;
                // The lowest and highest label stay inside the plot, clear of the times below and the unit above.
                const cy = clamp(y(sc, v), top + patchH / 2, bottom - patchH / 2);
                el('rect', { x: onLeft ? edge : edge - w, y: cy - patchH / 2, width: w, height: patchH, rx: patchH / 2, class: 'tick-bg' }, scale);
                text(scale, tx, cy + font * 0.35, s, { 'text-anchor': anchor, 'font-size': font });
            }
            if (u !== 'value') text(scale, tx, font, u, { 'text-anchor': anchor, 'font-size': font, class: 'unit' });
        });
        state.geom = { left, right, width };
    }

    /** Time labels: clock times (offsets from the period's start, as in the app), or weekdays under each day's middle. */
    function drawTimeAxis(axis, m, x, b) {
        const tick = tickMinutes((b.to - b.from) / 60000, Math.floor((b.right - b.left) / (b.font * 3.6)));
        const labels = [];
        if (tick >= 1440) {
            for (const d of daysOf(localDate(b.from), localDate(b.to - 1))) {
                const { start, end } = dayBounds(d);
                if (start > b.from) el('line', { x1: x(start), x2: x(start), y1: b.top, y2: b.bottom, class: 'gridline' }, axis);
                labels.push([(start + end) / 2, weekdayFmt.format(dateOf(d))]);
            }
        } else {
            // From the midnight before the window, so a window swiped past the day's ends has its labels too.
            for (let ms = dayBounds(localDate(b.from)).start; ms <= b.to; ms += tick * 60000) {
                const xx = x(ms);
                // Midnight stands out and carries the date: the window may lie across two days.
                const midnight = new Date(ms).getHours() === 0 && new Date(ms).getMinutes() === 0;
                if (xx >= b.left - 1 && xx <= b.right + 1) el('line', { x1: xx, x2: xx, y1: b.top, y2: b.bottom, class: midnight ? 'midnight' : 'gridline' }, axis);
                labels.push([ms, midnight ? dateFmt.format(new Date(ms)) : timeFmt.format(new Date(ms)), midnight]);
            }
        }
        let lastRight = -Infinity;
        for (const [ms, s, strong] of labels) {
            const xx = x(ms);
            if (xx < b.left - 1 || xx > b.right + 1) continue;
            const w = s.length * b.font * 0.6;
            const lx = clamp(xx - w / 2, b.left + 3, b.right - 3 - w);
            // Labels pushed inward at the edges could otherwise collide with their neighbour.
            if (lx < lastRight + 6) continue;
            text(axis, lx + w / 2, b.bottom + b.font * 1.3, s, Object.assign({ 'text-anchor': 'middle', 'font-size': b.font }, strong ? { class: 'unit' } : {}));
            lastRight = lx + w;
        }
    }

    /** Energy per day / month / year as grouped bars, one per shown metric (EnergyChart). */
    function drawEnergy(svg, m) {
        const hs = state.hist, vp = hs.vp;
        const { width, height, font } = chartSize(svg);
        svg.setAttribute('viewBox', '0 0 ' + width + ' ' + height);
        const bars = ENERGY_BARS.filter((b) => !hs.sel.hiddenEnergy.includes(b.key));
        const n = Math.max(1, m.slots.length);
        const first = clamp(Math.floor(vp.start * n), 0, n - 1);
        const last = clamp(Math.ceil(vp.end * n), first + 1, n);
        let most = 0;
        for (let i = first; i < last && i < m.slots.length; i++) for (const b of bars) most = Math.max(most, m.slots[i].energy[b.key] || 0);
        const sc = niceScale(0, most);
        const mwh = sc.step >= 1000;
        const yLabel = (v) => (mwh ? axisTick(v / 1000, sc.step / 1000) + ' MWh' : axisTick(v, sc.step) + ' kWh');
        // Room for the longest axis label.
        const left = Math.round(10 + font * 0.62 * Math.max(...sc.ticks.map((v) => yLabel(v).length))), right = width - 8, top = Math.round(font * 0.8), bottom = height - Math.round(font * 1.9);
        const span = vp.end - vp.start;
        const slotW = (right - left) / (span * n);
        const x = (f) => left + ((f - vp.start) / span) * (right - left);
        const y = (v) => bottom - ((v - sc.min) / (sc.max - sc.min)) * (bottom - top);

        const axis = el('g', { class: 'axis' }, svg);
        for (const v of sc.ticks) {
            const yy = y(v);
            el('line', { x1: left, x2: right, y1: yy, y2: yy, class: v === 0 ? 'zero' : 'gridline' }, axis);
            text(axis, left - 6, yy + font * 0.35, yLabel(v), { 'text-anchor': 'end', 'font-size': font });
        }
        // Thin out labels so they never collide.
        const widest = m.slots.reduce((w, s) => Math.max(w, s.short.length * font * 0.6), 0);
        const every = Math.max(1, Math.ceil((widest + 8) / slotW));
        m.slots.forEach((s, i) => {
            if (i % every !== 0) return;
            const xx = x((i + 0.5) / n);
            if (xx < left - 1 || xx > right + 1) return;
            text(axis, xx, bottom + font * 1.3, s.short, { 'text-anchor': 'middle', 'font-size': font });
        });

        const clip = el('clipPath', { id: 'plot-clip' }, svg);
        el('rect', { x: left, y: 0, width: right - left, height }, clip);
        const g = el('g', { 'clip-path': 'url(#plot-clip)' }, svg);
        if (hs.selected !== null) el('rect', { x: x(hs.selected / n), y: top, width: slotW, height: bottom - top, class: 'selected' }, g);
        const gap = Math.min(2, slotW * 0.04);
        const groupW = slotW * 0.72;
        const barW = Math.max(1, (groupW - gap * (bars.length - 1)) / Math.max(1, bars.length));
        for (let i = first; i < last && i < m.slots.length; i++) {
            const x0 = x(i / n) + (slotW - groupW) / 2;
            bars.forEach((b, k) => {
                const v = m.slots[i].energy[b.key];
                if (v === null || v <= 0) return;
                const l = x0 + k * (barW + gap), r = l + barW, yt = y(v), yb = y(0);
                const rr = Math.min(4, barW / 2, yb - yt);
                const f = (z) => z.toFixed(1);
                // Rounded at the data end, square on the baseline.
                const d = 'M' + f(l) + ' ' + f(yb) + 'V' + f(yt + rr) + 'Q' + f(l) + ' ' + f(yt) + ' ' + f(l + rr) + ' ' + f(yt) +
                    'H' + f(r - rr) + 'Q' + f(r) + ' ' + f(yt) + ' ' + f(r) + ' ' + f(yt + rr) + 'V' + f(yb) + 'Z';
                el('path', { d, class: 'bar ' + b.cls }, g);
            });
        }
        state.geom = { left, right, width };
    }

    /**
     * The chart's gestures (the app's chartGestures): the mouse wheel or two fingers zoom around the pointer, a
     * horizontal drag moves the chart (curves on into the days before and after, bars once zoomed; vertical drags
     * still scroll the page), hover or tap shows the values,
     * double-click or double-tap resets the zoom. Keys: arrows move the cursor, + and − zoom, 0 resets.
     */
    function setupChartGestures() {
        const svg = $('chart');
        const pointers = new Map();
        let down = null, moved = false, lastTap = 0, lastTapX = 0;
        const ready = () => state.model && state.geom && !blank(state.model);
        const plotPx = () => {
            const r = svg.getBoundingClientRect();
            return ((state.geom.right - state.geom.left) * r.width) / state.geom.width || 1;
        };
        const fraction = (clientX) => {
            const r = svg.getBoundingClientRect();
            const px = ((clientX - r.left) * state.geom.width) / (r.width || 1);
            return clamp((px - state.geom.left) / (state.geom.right - state.geom.left), 0, 1);
        };
        const selectAt = (f) => {
            const hs = state.hist, m = state.model;
            if (m.kind === 'power' && centredCursor()) return; // the cursor stays in the middle
            const at = hs.vp.start + f * (hs.vp.end - hs.vp.start);
            hs.selected = m.kind === 'power' ? m.start + at * (m.end - m.start) : clamp(Math.floor(at * m.slots.length), 0, m.slots.length - 1);
            scheduleChart();
        };
        // Curves (day, week) move past the period's ends into the days around, up to today; bars stay in their period.
        const zoom = (anchor, factor, pan) => {
            const hs = state.hist, before = hs.vp, power = state.model.kind === 'power';
            // Forward as far as the end of today, or now in the middle of the window.
            const m = state.model, span = before.end - before.start;
            const hi = !power ? 1 : hasNext(hs.period, hs.anchor, localDate(Date.now())) ? 2 : Math.max(1, (Date.now() - m.start) / (m.end - m.start) + span / 2);
            hs.vp = zoomViewport(before, anchor, factor, pan, power ? -1 : 0, hi);
            const changed = Math.abs(before.start - hs.vp.start) > 1e-9 || Math.abs(before.end - hs.vp.end) > 1e-9;
            if (!changed) return false;
            if (power) { hs.dragging = true; slideWindow(); } else scheduleChart();
            return true;
        };
        let settleTimer = 0;
        const settleSoon = () => { clearTimeout(settleTimer); settleTimer = setTimeout(() => { if (!pointers.size && state.hist.dragging) settle(); }, 250); };

        svg.addEventListener('pointerdown', (e) => {
            if (!ready() || (e.pointerType === 'mouse' && e.button !== 0)) return;
            try { svg.setPointerCapture(e.pointerId); } catch (err) { /* already gone */ }
            clearTimeout(settleTimer);
            pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
            if (pointers.size === 1) { down = { x: e.clientX, y: e.clientY }; moved = false; } else moved = true; // a pinch is no tap
        });
        svg.addEventListener('pointermove', (e) => {
            if (!ready()) return;
            const p = pointers.get(e.pointerId);
            if (!p) {
                if (e.pointerType === 'mouse') selectAt(fraction(e.clientX)); // hover
                return;
            }
            if (pointers.size >= 2) {
                const all = [...pointers.values()];
                const cx0 = all.reduce((s, q) => s + q.x, 0) / all.length;
                const d0 = Math.hypot(all[0].x - all[1].x, all[0].y - all[1].y);
                p.x = e.clientX;
                p.y = e.clientY;
                const cx1 = all.reduce((s, q) => s + q.x, 0) / all.length;
                const d1 = Math.hypot(all[0].x - all[1].x, all[0].y - all[1].y);
                zoom(fraction(cx1), d0 > 0 ? d1 / d0 : 1, (cx1 - cx0) / plotPx());
                return;
            }
            const dx = e.clientX - p.x, dy = e.clientY - p.y;
            p.x = e.clientX;
            p.y = e.clientY;
            if (Math.abs(e.clientX - down.x) > 6 || Math.abs(e.clientY - down.y) > 6) moved = true;
            // A sideways drag moves the chart with the finger; up and down the page scrolls (touch-action: pan-y).
            if (moved && Math.abs(dx) >= Math.abs(dy) && zoom(0.5, 1, dx / plotPx())) return;
            if (e.pointerType === 'mouse') selectAt(fraction(e.clientX));
        });
        const end = (e) => {
            if (!pointers.delete(e.pointerId)) return;
            if (!pointers.size && state.hist.dragging) settle();
            if (pointers.size || moved || e.type !== 'pointerup' || !ready()) return;
            selectAt(fraction(e.clientX));
            if (e.pointerType === 'mouse') return; // the mouse has dblclick
            const now = Date.now();
            if (now - lastTap < 350 && Math.abs(e.clientX - lastTapX) < 30) { resetZoom(); lastTap = 0; } else { lastTap = now; lastTapX = e.clientX; }
        };
        svg.addEventListener('pointerup', end);
        svg.addEventListener('pointercancel', end);
        svg.addEventListener('dblclick', (e) => { e.preventDefault(); resetZoom(); });
        svg.addEventListener('wheel', (e) => {
            if (!ready()) return;
            const unit = e.deltaMode === 1 ? 16 : e.deltaMode === 2 ? 400 : 1;
            const dx = e.deltaX * unit, dy = e.deltaY * unit;
            // Fully zoomed out, a wheel turn outwards scrolls the page as usual.
            const changed = Math.abs(dx) > Math.abs(dy)
                ? zoom(0.5, 1, -dx / plotPx())
                : zoom(fraction(e.clientX), Math.exp(-dy * (e.ctrlKey ? 0.01 : 0.002)), 0);
            if (changed) e.preventDefault();
            if (state.hist.dragging) settleSoon();
        }, { passive: false });
        svg.addEventListener('keydown', (e) => {
            if (!ready()) return;
            const hs = state.hist, m = state.model;
            if (e.key === '+' || e.key === '=') { zoom(0.5, 1.5, 0); if (hs.dragging) settle(); }
            else if (e.key === '-') { zoom(0.5, 1 / 1.5, 0); if (hs.dragging) settle(); }
            else if (e.key === '0' || e.key === 'Escape') resetZoom();
            else if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') {
                const by = e.key === 'ArrowLeft' ? -1 : 1;
                if (m.kind === 'power') hs.selected = clamp((hs.selected === null ? m.start + (m.end - m.start) / 2 : hs.selected) + by * 15 * 60000, m.start, m.end);
                else hs.selected = clamp(hs.selected === null ? 0 : hs.selected + by, 0, m.slots.length - 1);
                scheduleChart();
            } else return;
            e.preventDefault();
        });
    }

    function resetZoom() {
        state.hist.vp = viewport(state.hist.vp.minSpan);
        scheduleChart();
        show('zoom-reset', false);
    }

    // ------------------------------------------------------------------------------------------------ channel picker

    function openPicker() {
        const devices = devicesOf();
        state.picker = { deviceId: devices.length ? devices[0].id : null, query: '', selection: [], refused: '' };
        $('picker-search').value = '';
        renderPicker();
        const dlg = $('picker');
        if (typeof dlg.showModal === 'function') dlg.showModal(); else dlg.setAttribute('open', '');
    }

    function closePicker() {
        const dlg = $('picker');
        if (dlg.open) { if (typeof dlg.close === 'function') dlg.close(); else dlg.removeAttribute('open'); }
        state.picker = null;
    }

    /** The picker for the chosen device: grouped like the device detail view, channels on the chart checked and fixed. */
    function renderPicker() {
        const p = state.picker;
        if (!p) return;
        const devices = devicesOf();
        const chips = clear($('picker-devices'));
        for (const d of devices) {
            const b = button('chip', str(d.name) || d.id, chips);
            b.dataset.device = d.id;
            b.setAttribute('aria-pressed', String(d.id === p.deviceId));
        }
        show('picker-devices', devices.length > 0);
        show('picker-search-row', devices.length > 0);
        const list = clear($('picker-list'));
        if (!devices.length) {
            h('p', 'note', t('pickerNoDevices'), list);
        } else {
            const catalog = catalogOf(state.plant, p.deviceId);
            if (!catalog.length) h('p', 'note', t('pickerEmpty'), list);
            for (const g of pickerSections(catalog, p.query, shownName)) {
                h('h3', null, sectionName(g.section), list);
                for (const [key, label, unit] of g.rows) {
                    const row = h('label', 'pick-row', null, list);
                    const box = h('input', null, null, row);
                    box.type = 'checkbox';
                    box.dataset.key = key;
                    h('span', null, shownName(key, label) + (unit ? ' (' + displayUnit(unit)[0] + ')' : ''), row);
                }
            }
        }
        updatePicker();
    }

    /** Checked, fixed and free channels, the count and the add button, without rebuilding the list (keeps the focus). */
    function updatePicker() {
        const p = state.picker;
        const added = chartChannels();
        const free = MAX_CHANNELS - added.length;
        for (const box of $('picker-list').querySelectorAll('input[data-key]')) {
            const key = box.dataset.key;
            const already = added.some((c) => c.deviceId === p.deviceId && c.key === key);
            const picked = p.selection.some(([d, k]) => d === p.deviceId && k === key);
            box.checked = already || picked;
            box.disabled = already || (!picked && p.selection.length >= free);
            box.parentNode.classList.toggle('off', box.disabled && !already);
        }
        setText('picker-status', t('pickerStatus', { n: nf0.format(p.selection.length), free: nf0.format(Math.max(0, free)), max: nf0.format(MAX_CHANNELS) }));
        $('picker-add').disabled = p.selection.length === 0;
        setText('picker-refused', p.refused);
        show('picker-refused', p.refused !== '');
    }

    function pickerAdd() {
        const p = state.picker;
        const r = addChannels(chartChannels(), p.selection, unitOf);
        if (r.refused) {
            p.refused = r.refused === 'channels' ? t('refusedChannels', { max: r.max }) : t('refusedUnits', { max: r.max, units: r.units.join(', ') });
            updatePicker();
            return;
        }
        state.hist.sel.channels = r.channels;
        saveChoice();
        closePicker();
        renderHistory();
        loadHistory();
    }

    function saveChoice() {
        state.hist.sel.stored = true;
        const s = storage();
        if (s) saveSelection(s, state.link.plantId, state.hist.sel);
    }

    function setupPicker() {
        $('picker-devices').addEventListener('click', (e) => {
            const b = e.target.closest('[data-device]');
            if (!b || !state.picker) return;
            state.picker.deviceId = b.dataset.device;
            state.picker.refused = '';
            renderPicker();
        });
        $('picker-search').addEventListener('input', (e) => {
            if (!state.picker) return;
            state.picker.query = e.target.value;
            renderPicker();
        });
        $('picker-list').addEventListener('change', (e) => {
            const box = e.target.closest('input[data-key]');
            const p = state.picker;
            if (!box || !p) return;
            const id = [p.deviceId, box.dataset.key];
            p.selection = p.selection.filter(([d, k]) => !(d === id[0] && k === id[1]));
            if (box.checked) p.selection.push(id);
            p.refused = '';
            updatePicker();
        });
        $('picker-add').addEventListener('click', pickerAdd);
        $('picker-cancel').addEventListener('click', closePicker);
        // Esc closes the dialog too; the event comes later, so a picker opened again meanwhile stays.
        $('picker').addEventListener('close', () => { if (!$('picker').open) state.picker = null; });
    }

    // ------------------------------------------------------------------------------------------------ fault log

    function eventTitle(e) { return TEXTS.en['issue_' + e.kind] !== undefined ? t('issue_' + e.kind) : e.kind; }

    function logDuration(seconds) {
        const [k, a, b] = durationParts(seconds);
        return k === 'min' ? t('logMinutes', { m: a }) : k === 'hours' ? t('logHours', { h: a, m: b }) : t('logDays', { d: a, h: b });
    }

    /** "23.10.26, 14:02 – 14:35 · 33 min", or "23.10.26, 14:02 · still going on". */
    function eventTime(e) {
        const start = new Date(e.started * 1000);
        if (e.ended === null) return dateTimeFmt.format(start) + ' · ' + t('logOngoing');
        const end = new Date(e.ended * 1000);
        const endText = localDate(end.getTime()) === localDate(start.getTime()) ? shortTimeFmt.format(end) : dateTimeFmt.format(end);
        return dateTimeFmt.format(start) + ' – ' + endText + ' · ' + logDuration(e.ended - e.started);
    }

    /**
     * The situation the app recorded at the start or end (FaultDetails) as titled rows: device, error, the device's
     * own report and values, the plant, the network. The device's address, serial number and the tablet's address
     * range are left out here.
     */
    function detailBlocks(json, prefix) {
        let root = null;
        try { root = obj(JSON.parse(json)); } catch (e) { return []; }
        if (!root) return [];
        const s = (o, k) => (o[k] === null || o[k] === undefined ? null : typeof o[k] === 'object' ? JSON.stringify(o[k]) : String(o[k]));
        const w = (o, k) => (num(o[k]) !== null ? nf0.format(o[k]) + ' W' : null);
        const n = (o, k, unit) => (num(o[k]) !== null ? String(Number(o[k].toFixed(3))) + ' ' + unit : null);
        const yesNo = (v) => (v === true ? t('logYes') : v === false ? t('logNo') : null);
        const join = (...parts) => parts.filter(Boolean).join(' · ') || null;
        const out = [];
        const add = (title, rows) => {
            const kept = rows.filter((r) => r[1] !== null && r[1] !== '');
            if (kept.length) out.push([prefix + ': ' + title, kept]);
        };
        const dev = obj(root.device);
        if (dev) add(t('logDevice'), [[t('logDevice'), s(dev, 'name')], [t('logType'), s(dev, 'type_name')]]);
        const err = obj(root.error);
        if (err) add(t('logError'), [[t('logErrorKind'), s(err, 'kind')], [t('logMessage'), s(err, 'message')]]);
        const r = obj(root.reading);
        if (r) {
            const rows = [[t('logStatus'), s(r, 'status') && translateChannel(s(r, 'status'), LANG)]];
            for (const p of arr(r.problems)) if (obj(p)) rows.push([s(p, 'kind') || '', s(p, 'text')]);
            rows.push([t('solar'), w(r, 'pv_w')], [t('grid'), w(r, 'grid_w')], [t('home'), w(r, 'load_w')]);
            const b = obj(r.battery);
            if (b) rows.push([t('battery'), join(w(b, 'w'), num(b.soc) !== null ? b.soc + ' %' : null, n(b, 'v', 'V'), s(b, 'mode'))]);
            rows.push([t('temperature'), n(r, 'temperature_c', '°C')]);
            for (const p of arr(r.phases)) if (obj(p)) rows.push([s(p, 'name') || '', join(n(p, 'v', 'V'), n(p, 'a', 'A'), w(p, 'w'), n(p, 'hz', 'Hz'))]);
            for (const p of arr(r.pv_strings)) if (obj(p)) rows.push(['PV' + (s(p, 'index') || ''), join(n(p, 'v', 'V'), n(p, 'a', 'A'), w(p, 'w'))]);
            add(t('logReported'), rows);
            for (const sec of arr(r.details)) {
                if (!obj(sec)) continue;
                add(translateChannel(s(sec, 'title') || '', LANG), arr(sec.rows).filter((x) => Array.isArray(x) && x.length >= 2)
                    .map((x) => [translateChannel(String(x[0]), LANG), String(x[1])]));
            }
        }
        const p = obj(root.plant);
        if (p) {
            const rows = [[t('solar'), w(p, 'pv_w')], [t('grid'), w(p, 'grid_w')], [t('home'), w(p, 'load_w')],
                [t('battery'), join(w(p, 'battery_w'), num(p.battery_soc) !== null ? p.battery_soc + ' %' : null)]];
            for (const d of arr(p.devices)) {
                if (!obj(d)) continue;
                rows.push([s(d, 'name') || '', d.answered === true ? t('logAnswered') : t('logNotAnswered') + (s(d, 'error') ? ' (' + s(d, 'error') + ')' : '')]);
            }
            add(t('logPlant'), rows);
        }
        const net = obj(root.network);
        if (net) add(t('logNetwork'), [[t('logLocal'), yesNo(net.local_network)], [t('logInternet'), yesNo(net.internet)], [t('logApp'), s(root, 'app')]]);
        return out;
    }

    function eventDetails(e) {
        const box = h('div', 'event-details');
        const blocks = [[eventTitle(e), [
            [t('logStarted'), dateTimeFmt.format(new Date(e.started * 1000))],
            [t('logEnded'), e.ended === null ? t('logOngoing') : dateTimeFmt.format(new Date(e.ended * 1000))],
            [t('logDuration'), e.ended === null ? null : logDuration(e.ended - e.started)],
            [t('logDevice'), e.deviceName],
            [t('logReported'), e.detail && (translateChannel(e.detail, LANG) + (translateChannel(e.detail, LANG) !== e.detail ? ' (' + e.detail + ')' : ''))],
        ].filter((r) => r[1])]];
        if (e.details) blocks.push(...detailBlocks(e.details, t('logAtStart')));
        if (e.ended !== null && e.endDetails) blocks.push(...detailBlocks(e.endDetails, t('logAtEnd')));
        for (const [title, rows] of blocks) {
            h('h4', null, title, box);
            const dl = h('dl', 'rows', null, box);
            for (const [k, v] of rows) {
                const row = h('div', 'row', null, dl);
                h('dt', null, k, row);
                h('dd', null, v, row);
            }
        }
        return box;
    }

    function eventItem(e) {
        const li = h('li', 'event sev-' + e.severity.toLowerCase());
        const open = state.expanded.has(String(e.id));
        const head = button('event-head', null, li);
        head.dataset.event = String(e.id);
        head.setAttribute('aria-expanded', String(open));
        h('span', 'sev-icon', e.severity === 'INFO' ? 'i' : '!', head).setAttribute('aria-hidden', 'true');
        const body = h('span', 'event-body', null, head);
        h('span', 'sr', t('sev_' + e.severity) + ': ', body);
        h('span', 'when', eventTime(e), body);
        h('span', 'what', eventTitle(e) + (e.deviceName ? ' · ' + e.deviceName : ''), body);
        if (e.detail) h('span', 'detail', translateChannel(e.detail, LANG), body);
        if (open) li.appendChild(eventDetails(e));
        return li;
    }

    /** The fault log (FaultLogTab): the entries still going on, then the finished ones, newest first. */
    function renderAlarms() {
        const { active, past } = splitEvents([...state.events.values()]);
        show('alarms-loading', !state.eventsLoaded);
        $('alarms').setAttribute('aria-busy', String(!state.eventsLoaded));
        for (const [id, list, emptyKey] of [['alarms-active', active, 'logNoActive'], ['alarms-past', past.slice(0, state.eventsShown), 'logEmpty']]) {
            const ul = clear($(id));
            if (state.eventsLoaded && !list.length) h('li', 'empty', t(emptyKey), ul);
            for (const e of list) ul.appendChild(eventItem(e));
        }
        show('alarms-more', past.length > state.eventsShown);
    }

    function setupAlarms() {
        $('alarms').addEventListener('click', (e) => {
            const b = e.target.closest('[data-event]');
            if (!b) return;
            const id = b.dataset.event;
            if (state.expanded.has(id)) state.expanded.delete(id); else state.expanded.add(id);
            renderAlarms();
            const again = $('alarms').querySelector('[data-event="' + CSS.escape(id) + '"]');
            if (again) again.focus();
        });
        $('alarms-more').addEventListener('click', () => {
            state.eventsShown += EVENTS_STEP;
            renderAlarms();
        });
        // The fault log is fetched once it comes near the screen.
        const want = () => {
            if (state.eventsWanted) return;
            state.eventsWanted = true;
            loadEvents();
        };
        if ('IntersectionObserver' in window) {
            const io = new IntersectionObserver((entries) => {
                if (entries.some((x) => x.isIntersecting)) { io.disconnect(); want(); }
            }, { rootMargin: '400px 0px' });
            io.observe($('alarms'));
        } else {
            want();
        }
    }

    // ------------------------------------------------------------------------------------------------ data

    function ctx() { return { publicKey: state.publicKey, dataKey: state.dataKey, seqs: state.seqs }; }

    /** Fetches items and returns [{want, content}] for the ones that were new and good. */
    async function fetchChecked(client, pairs) {
        const out = [];
        for (let i = 0; i < pairs.length; i += 200) {
            const chunk = pairs.slice(i, i + 200);
            const wanted = new Map(chunk.map(([kind, name]) => [kind + ':' + name, { kind, name, again: state.evicted.has(kind + ':' + name) }]));
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
        // The owner's chart channels are the default until the viewer chose for themselves.
        if (!state.hist.sel || !state.hist.sel.stored) state.hist.sel = loadSelection(storage(), state.link.plantId, json);
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

    /** Puts fetched (and checked) items into the caches. */
    function applyItems(got) {
        for (const g of got) {
            const { kind, name } = g.want;
            state.evicted.delete(kind + ':' + name);
            try {
                if (kind === KIND.BLOCK) {
                    state.blocks.set(name, decodeBlock(g.content));
                    continue;
                }
                const json = parseJson(g.content);
                if (kind === KIND.DAY) { if (obj(json)) state.days.set(name.slice(1), energyOf(json.plant)); }
                else if (kind === KIND.EVENT) { const e = eventOf(json); if (e) state.events.set(name, e); }
                else if (kind === KIND.PLANT) applyPlant(json);
                else if (kind === KIND.STORES) applyStores(json);
            } catch (e) {
                console.warn('Bad item', name, e.message);
            }
        }
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

    /** New index items: every stored item's name and seq (not their contents, which come only when shown). */
    async function readNewIndex() {
        state.indexSince = await readIndex(state.client, state.indexSince, (kind, name, seq) => {
            if (kind === KIND.LIVE || kind === KIND.REGISTER || kind === KIND.DELETE) return;
            const key = kind + ':' + name;
            const old = state.index.get(key);
            if (old !== undefined && seq <= old) return;
            state.index.set(key, seq);
            if (kind === KIND.DAY && /^d\d{4}-\d{2}-\d{2}$/.test(name)) state.dayNames.add(name);
            if (kind === KIND.EVENT) state.eventNames.set(name, seq);
        });
        state.lastRefresh = Date.now();
    }

    /** True when the index has the item in a version the page hasn't got (or dropped from its cache). */
    function newer(kind, name) {
        const key = kind + ':' + name;
        const s = state.index.get(key);
        if (s === undefined) return false;
        const v = state.seqs.get(kind, name);
        return v === undefined || s > v || state.evicted.has(key);
    }

    /**
     * What the page needs now: plant and stores when changed, the day item of the totals at the top, and for the
     * period shown its day items and (day, week) the hour blocks: the plant's and those of the devices with a
     * channel on the chart. Month and year need only day items, never blocks.
     */
    function wanted() {
        const hs = state.hist;
        const out = [];
        const add = (kind, name) => { if (newer(kind, name)) out.push([kind, name]); };
        add(KIND.PLANT, 'plant');
        add(KIND.STORES, 'stores');
        if (state.day) add(KIND.DAY, 'd' + state.day);
        if (!hs.anchor) return out;
        if (hs.period === 'lifetime') {
            for (const name of state.dayNames) add(KIND.DAY, name);
        } else {
            const b = periodBounds(hs.period, hs.anchor);
            for (const d of daysOf(b.first, b.last)) add(KIND.DAY, 'd' + d);
        }
        for (const name of rangeBlockNames()) add(KIND.BLOCK, name);
        // Several identical pairs would make the store answer twice.
        const seen = new Set();
        return out.filter(([k, n]) => !seen.has(k + ':' + n) && seen.add(k + ':' + n));
    }

    /** Keeps the block cache bounded: the oldest fetched blocks outside the period shown go first. */
    function trimBlocks() {
        if (state.blocks.size <= MAX_BLOCKS) return;
        const keep = new Set(rangeBlockNames());
        for (const name of [...state.blocks.keys()]) {
            if (state.blocks.size <= MAX_BLOCKS * 0.8) break;
            if (keep.has(name)) continue;
            state.blocks.delete(name);
            state.evicted.add(KIND.BLOCK + ':' + name);
        }
    }

    /** Fetches what the period shown lacks (only what the index has and the page hasn't), then draws it. */
    async function loadHistory(quiet = false) {
        if (!state.client) return;
        const hs = state.hist;
        // Quiet (and while a gesture moves the chart): no "Loading…" note, which would push the chart from under the finger.
        quiet = quiet || hs.dragging;
        const want = wanted();
        if (want.length) {
            if (!quiet) {
                hs.loading++;
                renderHistory();
            }
            try {
                applyItems(await fetchChecked(state.client, want));
                hs.failed = false;
            } catch (e) {
                hs.failed = true;
                hs.failedAt = Date.now();
                console.warn('History:', e.message);
            } finally {
                if (!quiet) hs.loading--;
            }
            trimBlocks();
        }
        if (hs.dragging) { renderDrag(); return; }
        renderTotals();
        renderHistory();
    }

    /** The fault log: the newest MAX_EVENTS entries the page hasn't got. */
    async function loadEvents() {
        if (!state.client || !state.eventsWanted) return;
        const names = [...state.eventNames].sort((a, b) => (a[1] < b[1] ? 1 : a[1] > b[1] ? -1 : 0)).slice(0, MAX_EVENTS);
        const want = names.filter(([n]) => newer(KIND.EVENT, n)).map(([n]) => [KIND.EVENT, n]);
        if (want.length) {
            try {
                applyItems(await fetchChecked(state.client, want));
            } catch (e) {
                console.warn('Faults:', e.message);
            }
        }
        state.eventsLoaded = true;
        renderAlarms();
    }

    /** New index items; the day of the latest live values (the period shown follows it until navigated away); its data. */
    async function refresh() {
        await readNewIndex();
        state.day = localDate(state.live ? state.liveAt : Date.now());
        const hs = state.hist;
        if (!hs.anchor || hs.follow) hs.anchor = state.day;
        await loadHistory();
        if (state.eventsWanted) await loadEvents();
    }

    // ------------------------------------------------------------------------------------------------ navigation

    function goTo(period, anchor) {
        const hs = state.hist;
        hs.period = period;
        hs.anchor = anchor;
        hs.reach = baseReach(period);
        hs.follow = inPeriod(period, anchor, state.day || localDate(Date.now()));
        renderHistory();
        loadHistory();
    }

    function setupHistory() {
        const hs = state.hist;
        $('periods').addEventListener('click', (e) => {
            const b = e.target.closest('[data-period]');
            if (b && hs.anchor) goTo(b.dataset.period, hs.anchor);
        });
        $('prev').addEventListener('click', () => { if (hs.anchor) goTo(hs.period, shiftAnchor(hs.period, hs.anchor, -1)); });
        $('next').addEventListener('click', () => {
            if (hs.anchor && hasNext(hs.period, hs.anchor, localDate(Date.now()))) goTo(hs.period, shiftAnchor(hs.period, hs.anchor, 1));
        });
        $('zoom-reset').addEventListener('click', resetZoom);
        $('legend').addEventListener('click', (e) => {
            const b = e.target.closest('button');
            if (!b || !hs.sel) return;
            if (b.id === 'add-channel') { openPicker(); return; }
            const toggle = (list, key) => (list.includes(key) ? list.filter((k) => k !== key) : list.concat([key]));
            if (b.dataset.toggle) hs.sel.hidden = toggle(hs.sel.hidden, b.dataset.toggle);
            else if (b.dataset.toggleEnergy) hs.sel.hiddenEnergy = toggle(hs.sel.hiddenEnergy, b.dataset.toggleEnergy);
            else if (b.dataset.remove) {
                hs.sel.channels = hs.sel.channels.filter((c) => c.deviceId + '|' + c.key !== b.dataset.remove);
                hs.sel.hidden = hs.sel.hidden.filter((k) => k !== b.dataset.remove);
            } else return;
            saveChoice();
            renderHistory();
            const again = b.dataset.toggle || b.dataset.toggleEnergy;
            if (again) {
                const same = $('legend').querySelector(b.dataset.toggle ? '[data-toggle="' + CSS.escape(again) + '"]' : '[data-toggle-energy="' + CSS.escape(again) + '"]');
                if (same) same.focus();
            }
        });
        setupChartGestures();
        setupPicker();
        setupAlarms();
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
            if (fresh) {
                renderLive();
                // The live values carry today's totals: the Totals card of a period holding today follows them.
                if (state.model && inPeriod(state.hist.period, state.hist.anchor, localDate(state.liveAt))) renderPeriodTotals(periodTotals());
            }
            if (Date.now() - state.lastRefresh > REFRESH_MS || (state.live && localDate(state.liveAt) !== state.day)) {
                await refresh();
                renderLive();
            } else if (state.hist.failed && Date.now() - state.hist.failedAt > HISTORY_RETRY_MS) {
                await loadHistory();
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
        try { await refresh(); } catch (e) { console.warn('History:', e.message); }
        if (!state.plant && !state.live) {
            showError('errNoData');
            later('retry', RETRY_MS, () => start(false));
            return;
        }
        if (!state.hist.sel) state.hist.sel = loadSelection(storage(), state.link.plantId, state.plant);
        show('loading', false);
        show('content', true);
        renderLive();
        renderHistory();
        renderAlarms();
        later('live', LIVE_POLL_MS, liveTick);
    }

    function applyTexts() {
        document.documentElement.lang = LANG;
        for (const e of document.querySelectorAll('[data-t]')) e.textContent = t(e.dataset.t);
        for (const e of document.querySelectorAll('[data-t-label]')) e.setAttribute('aria-label', t(e.dataset.tLabel));
        for (const e of document.querySelectorAll('[data-t-placeholder]')) e.setAttribute('placeholder', t(e.dataset.tPlaceholder));
    }

    async function boot() {
        applyTexts();
        window.addEventListener('hashchange', () => location.reload());
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
        setupHistory();
        document.addEventListener('visibilitychange', () => {
            if (!visible()) { stopTimers(); return; }
            if (!state.client) { start(false); return; }
            watchTick();
            liveTick();
        });
        let resizeTimer = null;
        window.addEventListener('resize', () => { clearTimeout(resizeTimer); resizeTimer = setTimeout(renderHistory, 200); });
        setInterval(() => { if (visible() && state.live) renderStatus(); }, 15000);
        await start(true);
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
    else boot();
})();
