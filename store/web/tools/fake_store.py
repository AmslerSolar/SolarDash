#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Amsler Solar GmbH
"""A fake store for testing the web view end to end. Development tool only: never deploy it.

Serves, on 127.0.0.1 only:
  /web/...                  the web view (store/web/), e.g. /web/o/ and /web/o/test.html
  /tests/vectors.json       the test vectors (for test.html)
  /store.php?r=/v1/observe/<id>/{key,index,fetch,live,watch}
                            the observer routes of docs/design/observer-protocol.md

Modes:
  default   the envelopes of store/tests/vectors.json, as they are (standard library only)
  --fresh   envelopes made now with the vectors' software key (needs python3-cryptography): a plant with four
            devices (hybrid inverter, grid meter, heat pump, weather sensor) and their channel catalogs, day items of
            the last 40 days, plant and device hour blocks of the last 3 days up to now, a fault log of 14 entries
            (one still going on), and a live envelope with the current time on every request

Every observer request is counted in Data.requests as (op, [[kind, name], …] for fetch), so tests can check how
much the web view asks for.

The read token is the vectors' one; any other gives 403. On start it prints the observer link.
"""

import argparse
import base64
import http.server
import json
import math
import os
import random
import struct
import sys
import threading
import time
import urllib.parse
import zlib
from datetime import datetime, timedelta

HERE = os.path.dirname(os.path.abspath(__file__))
STORE_DIR = os.path.dirname(os.path.dirname(HERE))  # store/
VECTORS = os.path.join(STORE_DIR, 'tests', 'vectors.json')


def b64(b):
    return base64.urlsafe_b64encode(b).rstrip(b'=').decode()


def unb64(s):
    return base64.urlsafe_b64decode(s + '=' * (-len(s) % 4))


def frames(envelopes):
    return b''.join(struct.pack('>I', len(e)) + e for e in envelopes)


def parse_name(envelope):
    kind = envelope[4]
    seq = struct.unpack('>Q', envelope[5:13])[0]
    n = envelope[21]
    return kind, envelope[22:22 + n].decode(), seq


class Signer:
    """Makes sealed, signed envelopes with the vectors' software key (python3-cryptography)."""

    def __init__(self, vectors):
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import ec
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
        self.key = serialization.load_der_private_key(unb64(vectors['privateKeyPkcs8']), None)
        self.ecdsa = ec.ECDSA(hashes.SHA256())
        self.aes = AESGCM(unb64(vectors['dataKey']))
        self.seq = 100
        self.lock = threading.Lock()

    def envelope(self, kind, name, content, t_ms=None):
        with self.lock:
            self.seq += 1
            seq = self.seq
        t_ms = int(time.time() * 1000) if t_ms is None else t_ms
        n = name.encode()
        header = b'SDO1' + bytes([kind]) + struct.pack('>QQ', seq, t_ms) + bytes([len(n)]) + n
        nonce = os.urandom(12)
        payload = nonce + self.aes.encrypt(nonce, zlib.compress(content, 9), header)
        signed = header + struct.pack('>I', len(payload)) + payload
        sig = self.key.sign(signed, self.ecdsa)
        return signed + struct.pack('>H', len(sig)) + sig


def zigzag(x):
    return (x << 1) ^ (x >> 63)


def varint(v):
    out = bytearray()
    while v & ~0x7F:
        out.append((v & 0x7F) | 0x80)
        v >>= 7
    out.append(v)
    return bytes(out)


def encode_block(start, channels, interval=60, slots=60):
    """channels: [(name, exponent, [value or None] * slots)]."""
    out = bytearray(struct.pack('>BIHHH', 1, start, interval, slots, len(channels)))
    for name, exp, _ in channels:
        n = name.encode()
        out += bytes([len(n)]) + n + struct.pack('>b', exp)
    for _, _, values in channels:
        bits = bytearray((slots + 7) // 8)
        for i, v in enumerate(values):
            if v is not None:
                bits[i // 8] |= 1 << (i % 8)
        out += bits
    for _, exp, values in channels:
        prev = None
        for v in values:
            if v is None:
                continue
            x = round(v * 10 ** -exp)
            out += varint(zigzag(x if prev is None else x - prev))
            prev = x
    return bytes(out)


def sample(t):
    """A plausible plant at epoch second t (local time): solar bell, house base load, battery, grid."""
    lt = datetime.fromtimestamp(t)
    h = lt.hour + lt.minute / 60
    pv = max(0.0, math.sin(math.pi * (h - 6.5) / 13)) ** 1.5 * 5200 if 6.5 < h < 19.5 else 0.0
    load = 450 + (1800 if 11.5 < h < 12.5 else 0) + (900 if 18 < h < 21 else 0) + 150 * math.sin(t / 900)
    battery = 0.0
    if pv > load:
        battery = -min(2500, pv - load) if h < 15 else 0.0
    elif h > 19 or h < 7:
        battery = min(1500, load) if h > 19 else 0.0
    grid = pv + battery - load
    soc = max(10, min(100, 20 + 6 * max(0, min(h, 15) - 8.5) - 4 * max(0, h - 19)))
    return round(pv), round(load), round(battery), round(grid), round(soc)


def device_values(t):
    """Device channel values at t, consistent with sample(t)."""
    pv, load, battery, grid, soc = sample(t)
    h = datetime.fromtimestamp(t).hour + datetime.fromtimestamp(t).minute / 60
    outside = 4 + 6 * math.sin(math.pi * (h - 9) / 12)
    return {'pv': pv, 'load': load, 'battery': battery, 'grid': grid, 'soc': soc, 'outside': outside,
            'irradiance': max(0.0, pv / 5.2), 'wind': 2 + 1.5 * math.sin(t / 1300),
            'heat': 900 + 300 * math.sin(t / 2000) if 6 < h < 22 else 0.0}


def ch(fn):
    return lambda t: fn(device_values(t))


# Per device: (key, label, unit, exponent, value at t or None for catalog-only channels), as Collector sends them.
CATALOGS = {
    'inv1': [
        ('r:pv_power', 'Solar power', 'W', 0, ch(lambda v: v['pv'])),
        ('r:load_power', 'Load power', 'W', 0, ch(lambda v: v['load'])),
        ('r:temperature', 'Temperature', '°C', -1, ch(lambda v: 30 + v['pv'] / 400)),
        ('r:battery_power', 'Battery power (+ discharge)', 'W', 0, ch(lambda v: v['battery'])),
        ('r:battery_soc', 'Battery state of charge', '%', 0, ch(lambda v: v['soc'])),
        ('r:battery_voltage', 'Battery voltage', 'V', -1, ch(lambda v: 48 + v['soc'] / 20)),
        ('r:pv1_voltage', 'PV1 voltage', 'V', -1, ch(lambda v: 380 + v['pv'] / 100 if v['pv'] else 0)),
        ('r:pv1_power', 'PV1 power', 'W', 0, ch(lambda v: v['pv'] * 0.55)),
        ('r:pv2_power', 'PV2 power', 'W', 0, ch(lambda v: v['pv'] * 0.45)),
        ('r:today_pv', 'Solar yield today', 'kWh', -2, None),
        ('d:Battery:BMS temperature', 'BMS temperature (Battery)', '°C', -1, ch(lambda v: 22 + v['soc'] / 20)),
        ('d:Battery:Cycles', 'Cycles (Battery)', '', -2, None),
    ],
    'meter1': [
        ('r:grid_power', 'Grid power (+ export)', 'W', 0, ch(lambda v: v['grid'])),
        ('r:L1_voltage', 'L1 voltage', 'V', -1, ch(lambda v: 231 + v['grid'] / 2000)),
        ('r:L1_current', 'L1 current', 'A', -2, ch(lambda v: abs(v['grid']) / 3 / 231)),
        ('r:L1_frequency', 'L1 frequency', 'Hz', -2, ch(lambda v: 50 + math.sin(v['wind']) / 50)),
    ],
    'hp1': [
        ('r:load_power', 'Load power', 'W', 0, ch(lambda v: v['heat'])),
        ('d:Heat pump:Flow temperature', 'Flow temperature (Heat pump)', '°C', -1, ch(lambda v: 30 + v['heat'] / 100)),
        ('d:Heat pump:Return temperature', 'Return temperature (Heat pump)', '°C', -1, ch(lambda v: 26 + v['heat'] / 120)),
    ],
    # A sensor (ModbusSpec "other" kind): no power at all, only measurements on their own axes.
    'sensor1': [
        ('d:Device:Solar irradiance', 'Solar irradiance (Device)', 'W/m²', -2, ch(lambda v: v['irradiance'])),
        ('d:Device:Wind speed', 'Wind speed (Device)', 'm/s', -2, ch(lambda v: v['wind'])),
        ('d:Device:Cell temperature of sensor', 'Cell temperature of sensor (Device)', '°C', -1, ch(lambda v: v['outside'] + v['irradiance'] / 60)),
        ('d:Device:External temperature', 'External temperature (Device)', '°C', -1, ch(lambda v: v['outside'])),
    ],
}


def events(now):
    """A fault log like the app keeps it (observer protocol "Event"): kinds, severities, devices, situations."""
    n = int(now)
    reading = json.dumps({
        'time': n - 3 * 86400, 'app': '1.0 (test)',
        'device': {'name': 'Inverter', 'type': 'GOODWE_ET', 'type_name': 'GoodWe ET hybrid', 'host': '192.168.1.20',
                   'port': 502, 'unit_id': 247, 'serial': 'GW-SECRET-123'},
        'reading': {'status': 'Fault', 'problems': [{'kind': 'FAULT', 'text': 'Isolation fault'}], 'pv_w': 0, 'grid_w': -400,
                    'load_w': 400, 'temperature_c': 41.25, 'battery': {'w': 0, 'soc': 55, 'v': 51.2, 'mode': 'Idle'},
                    'phases': [{'name': 'L1', 'v': 231.4, 'a': 1.7, 'hz': 50.01, 'w': 400}],
                    'details': [{'title': 'Measurements', 'rows': [['Isolation resistance', '0.2 MΩ']]}]},
        'plant': {'off_grid': False, 'pv_w': 0, 'grid_w': -400, 'load_w': 400, 'battery_w': 0, 'battery_soc': 55,
                  'devices': [{'name': 'Inverter', 'answered': True}, {'name': 'Grid meter', 'answered': False, 'error': 'TIMEOUT'}]},
        'network': {'local_network': True, 'internet': True, 'subnets': ['192.168.1.0/24']},
    })
    lost = json.dumps({'error': {'kind': 'TIMEOUT', 'message': 'Read timed out after 5000 ms'},
                       'device': {'name': 'Grid meter', 'type_name': 'Eastron SDM630'}, 'network': {'local_network': True, 'internet': False}})
    plan = [  # id, kind, severity, device, detail, started (seconds ago), duration (s, None: still going on)
        (1, 'NOT_ANSWERING', 'ALARM', 'Grid meter', None, 30 * 86400, 600),
        (2, 'MAINS_LOST', 'WARNING', 'Inverter', 'Grid loss', 26 * 86400, 2 * 3600 + 300),
        (3, 'BACKUP_MODE', 'WARNING', 'Inverter', 'Off-grid mode', 26 * 86400 + 60, 2 * 3600),
        (4, 'DEVICE_WARNING', 'WARNING', 'Inverter', 'Battery temperature high', 21 * 86400, 1800),
        (5, 'ASLEEP', 'INFO', 'Inverter', None, 20 * 86400, 10 * 3600),
        (6, 'NO_INTERNET', 'INFO', None, None, 18 * 86400, 3 * 86400 + 4 * 3600),
        (7, 'DEVICE_FAULT', 'ALARM', 'Inverter', 'Isolation fault', 3 * 86400, 45 * 60),
        (8, 'NOT_ANSWERING', 'ALARM', 'Heat pump', None, 2 * 86400, 120),
        (9, 'LOGIN_FAILED', 'ALARM', 'Heat pump', None, 2 * 86400 - 3600, 300),
        (10, 'DEVICE_WARNING', 'WARNING', 'Heat pump', 'Compressor start delay', 36 * 3600, 900),
        (11, 'NOT_READY', 'WARNING', 'Weather sensor', None, 20 * 3600, 240),
        (12, 'SOMETHING_NEW', 'WARNING', 'Weather sensor', 'A kind this page does not know', 10 * 3600, 60),
        (13, 'DEVICE_WARNING', 'WARNING', 'Inverter', 'Battery temperature high', 3 * 3600, None),
        (14, 'ASLEEP', 'INFO', 'Inverter', None, 40 * 3600, 9 * 3600),
    ]
    out = []
    for i, kind, severity, device, detail, ago, dur in plan:
        started = n - ago
        out.append({'id': i, 'key': '%s:%s' % (kind, device or ''), 'kind': kind, 'severity': severity,
                    'deviceId': None, 'deviceName': device, 'detail': detail, 'percent': None, 'started': started,
                    'ended': None if dur is None else started + dur,
                    'details': reading if i == 7 else lost if i in (1, 8) else None,
                    'endDetails': lost if i == 1 else None})
    return out


class Data:
    def __init__(self, fresh):
        self.vectors = json.load(open(VECTORS))
        self.plant_id = self.vectors['plantId']
        self.token = self.vectors['readToken']
        self.spki = unb64(self.vectors['publicKey'])
        self.items = {}  # (kind, name) -> envelope bytes (not live)
        self.requests = []  # (op, pairs or None) of every observer request
        self.live = None
        self.signer = None
        if not fresh:
            for e in self.vectors['envelopes']:
                env = unb64(e['envelope'])
                if e['kind'] == 4:
                    self.live = env
                elif e['kind'] in (1, 2, 3, 5, 6):
                    self.items[(e['kind'], e['name'])] = env
            return
        self.signer = Signer(self.vectors)
        self.fresh_items(time.time())

    def fresh_items(self, now):
        plant = {
            'name': 'Home', 'offGrid': False,
            'devices': [
                {'id': 'inv1', 'name': 'Inverter', 'type': 'GOODWE_ET', 'meterRole': 'GRID', 'hiddenProblems': ['Fan check']},
                {'id': 'meter1', 'name': 'Grid meter', 'type': 'EASTRON', 'meterRole': 'GRID'},
                {'id': 'hp1', 'name': 'Heat pump', 'type': 'NIBE_S', 'meterRole': 'GRID'},
                {'id': 'sensor1', 'name': 'Weather sensor', 'type': 'MODBUS_SPEC_CONSUMER', 'specId': 'victron/gx-meteo'},
            ],
            # The owner's chart channel: the web view's default selection.
            'chartChannels': [{'deviceId': 'sensor1', 'key': 'd:Device:External temperature', 'slot': 0}],
            'channels': {k: [list(c[:3]) for c in v] for k, v in CATALOGS.items()},
        }
        self.put(5, 'plant', json.dumps(plant).encode())
        self.put(6, 'stores', json.dumps([]).encode())
        today = datetime.fromtimestamp(now).replace(hour=0, minute=0, second=0, microsecond=0)
        rnd = random.Random(42)
        # Day items: the last 40 days (today's as the live values have it).
        self.today = {'pv': 18.4, 'export': 7.9, 'import': 1.2, 'load': 9.6}
        for back in range(40):
            day = (today - timedelta(days=back)).strftime('%Y-%m-%d')
            if back == 0:
                totals = self.today
            elif back == 12:
                continue  # a day the tablet was off
            else:
                pv = round(rnd.uniform(4, 24), 2)
                load = round(rnd.uniform(8, 14), 2)
                totals = {'pv': pv, 'export': round(max(0.0, pv - load * 0.6) * 0.7, 2),
                          'import': round(max(0.5, load - pv * 0.5) * 0.6, 2), 'load': load}
            self.put(2, 'd' + day, json.dumps({'day': day, 'plant': totals}).encode())
        # Hour blocks: the plant and every device, for the last 3 days up to now.
        hour = int((today - timedelta(days=2)).timestamp()) // 3600 * 3600
        while hour <= now:
            ts = [hour + i * 60 for i in range(60)]
            rows = [sample(t) if t <= now else None for t in ts]
            cols = list(zip(*[r or (None,) * 5 for r in rows]))
            self.put(1, 'b%d' % hour, encode_block(hour, [(k, 0, list(v)) for k, v in zip(('pv', 'load', 'battery', 'grid', 'soc'), cols)]))
            for device, catalog in CATALOGS.items():
                chans = [(key, exp, [f(t) if t <= now else None for t in ts]) for key, _, _, exp, f in catalog if f]
                self.put(1, 'b%d.%s' % (hour, device), encode_block(hour, chans))
            hour += 3600
        # The fault log: entries over the last weeks, one still going on.
        for e in events(now):
            self.put(3, 'e%d' % e['id'], json.dumps(e).encode())

    def put(self, kind, name, content):
        self.items[(kind, name)] = self.signer.envelope(kind, name, content)

    def live_envelope(self):
        if not self.signer:
            return self.live
        live = {
            't': int(time.time() * 1000), 'pv': 3200, 'load': 900, 'grid': 1800, 'gridSource': 'METERS',
            'battery': {'w': -500, 'soc': 64}, 'today': self.today,
            'devices': [
                {'id': 'inv1', 'reading': {'pv': 3200, 'problems': [
                    {'kind': 'WARNING', 'text': 'Battery temperature high'},
                    {'kind': 'WARNING', 'text': 'Fan check'},
                ]}},
                {'id': 'meter1', 'reading': {'grid': 1800}},
            ],
            'consumers': [],
        }
        return self.signer.envelope(4, 'live', json.dumps(live).encode())

    def index(self):
        out = [[k, n, parse_name(e)[2]] for (k, n), e in self.items.items()]
        return sorted(out, key=lambda x: x[2])


class Handler(http.server.SimpleHTTPRequestHandler):
    data = None
    page_size = 2000
    log = False

    def __init__(self, *a, **kw):
        super().__init__(*a, directory=STORE_DIR, **kw)

    def log_message(self, fmt, *args):
        if self.log:
            sys.stderr.write('%s\n' % (fmt % args))

    def end_headers(self):
        self.send_header('Cache-Control', 'no-store')
        super().end_headers()

    def answer(self, status, body=b'', ctype='application/octet-stream'):
        self.send_response(status)
        if status != 204:
            self.send_header('Content-Type', ctype)
            self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        if status != 204:
            self.wfile.write(body)

    def error(self, status, text):
        self.answer(status, json.dumps({'error': text}).encode(), 'application/json')

    def route(self):
        u = urllib.parse.urlsplit(self.path)
        if u.path != '/store.php':
            return None
        q = urllib.parse.parse_qs(u.query)
        r = q.get('r', [''])[0]
        parts = r.split('/')
        if len(parts) != 5 or parts[:3] != ['', 'v1', 'observe']:
            self.error(400, 'route')
            return True
        if parts[3] != self.data.plant_id or self.headers.get('X-Observe-Token') != self.data.token:
            self.error(403, 'token')
            return True
        return parts[4], q

    def do_GET(self):
        r = self.route()
        if r is None:
            path = urllib.parse.urlsplit(self.path).path
            if path.startswith('/web/') or path == '/tests/vectors.json':
                return super().do_GET()
            return self.error(404, 'not found')
        if r is True:
            return
        op, q = r
        self.data.requests.append((op, None))
        if op == 'key':
            return self.answer(200, self.data.spki)
        if op == 'live':
            env = self.data.live_envelope()
            return self.answer(200, env) if env else self.answer(204)
        if op == 'index':
            since = int(q.get('since', ['0'])[0] or 0)
            items = [i for i in self.data.index() if i[2] > since]
            page = items[:self.page_size]
            body = {'items': page, 'more': len(items) > len(page)}
            return self.answer(200, json.dumps(body).encode(), 'application/json')
        return self.error(400, 'route')

    def do_POST(self):
        r = self.route()
        if r is None:
            return self.error(404, 'not found')
        if r is True:
            return
        op, _ = r
        length = int(self.headers.get('Content-Length') or 0)
        body = self.rfile.read(length) if length else b''
        if op == 'watch':
            self.data.requests.append(('watch', None))
            return self.answer(204)
        if op == 'fetch':
            try:
                pairs = json.loads(body)
                assert isinstance(pairs, list) and len(pairs) <= 200
            except Exception:
                return self.error(400, 'body')
            self.data.requests.append(('fetch', pairs))
            envs = [self.data.items[(k, n)] for k, n in pairs if (k, n) in self.data.items]
            return self.answer(200, frames(envs))
        return self.error(400, 'route')

    def do_HEAD(self):
        self.error(405, 'method')


def serve(port, fresh, page_size=2000, log=False):
    Handler.data = Data(fresh)
    Handler.page_size = page_size
    Handler.log = log
    server = http.server.ThreadingHTTPServer(('127.0.0.1', port), Handler)
    return server


def link(port, token=None, data=None):
    v = data.vectors
    stores = b64(json.dumps(['http://127.0.0.1:%d/store.php' % port]).encode())
    return 'http://127.0.0.1:%d/web/o/#%s.%s.%s.%s' % (port, v['plantId'], token or v['readToken'], v['dataKey'], stores)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--port', type=int, default=8765)
    ap.add_argument('--fresh', action='store_true', help='make plant, day, blocks and live now (python3-cryptography)')
    ap.add_argument('--page-size', type=int, default=2000, help='index page size (small values test paging)')
    ap.add_argument('--log', action='store_true')
    a = ap.parse_args()
    server = serve(a.port, a.fresh, a.page_size, a.log)
    print('Web view:  ' + link(a.port, data=Handler.data))
    print('Self-test: http://127.0.0.1:%d/web/o/test.html' % a.port)
    sys.stdout.flush()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == '__main__':
    main()
