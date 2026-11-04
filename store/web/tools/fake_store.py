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
  --fresh   envelopes made now with the vectors' software key: a plant with devices, today's day item, today's
            blocks up to now and a live envelope with the current time on every request (needs python3-cryptography)

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


class Data:
    def __init__(self, fresh):
        self.vectors = json.load(open(VECTORS))
        self.plant_id = self.vectors['plantId']
        self.token = self.vectors['readToken']
        self.spki = unb64(self.vectors['publicKey'])
        self.items = {}  # (kind, name) -> envelope bytes (not live)
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
        plant = {
            'name': 'Home', 'offGrid': False,
            'devices': [
                {'id': 'inv1', 'name': 'Inverter', 'type': 'LG_ESS', 'meterRole': 'GRID', 'hiddenProblems': ['Fan check']},
                {'id': 'meter1', 'name': 'Grid meter', 'type': 'SDM630', 'meterRole': 'GRID'},
            ],
            'chartChannels': [],
        }
        self.put(5, 'plant', json.dumps(plant).encode())
        self.put(6, 'stores', json.dumps([]).encode())
        now = time.time()
        midnight = datetime.fromtimestamp(now).replace(hour=0, minute=0, second=0, microsecond=0)
        hour = int(midnight.timestamp()) // 3600 * 3600
        # A device block that the web view must ignore.
        self.put(1, 'b%d.inv1' % hour, encode_block(hour, [('r:pv_power', 0, [1.0] * 60)]))
        while hour <= now:
            cols = {k: [] for k in ('pv', 'load', 'battery', 'grid', 'soc')}
            for i in range(60):
                t = hour + i * 60
                s = sample(t) if t <= now else None
                for k, v in zip(cols, s or [None] * 5):
                    cols[k].append(v)
            self.put(1, 'b%d' % hour, encode_block(hour, [(k, 0, v) for k, v in cols.items()]))
            hour += 3600
        self.today = {'pv': 18.4, 'export': 7.9, 'import': 1.2, 'load': 9.6}
        day = datetime.fromtimestamp(now).strftime('%Y-%m-%d')
        self.put(2, 'd' + day, json.dumps({'day': day, 'plant': self.today}).encode())
        yesterday = (datetime.fromtimestamp(now) - timedelta(days=1)).strftime('%Y-%m-%d')
        self.put(2, 'd' + yesterday, json.dumps({'day': yesterday, 'plant': {'pv': 1}}).encode())

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
            return self.answer(204)
        if op == 'fetch':
            try:
                pairs = json.loads(body)
                assert isinstance(pairs, list) and len(pairs) <= 200
            except Exception:
                return self.error(400, 'body')
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
