#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Amsler Solar GmbH
"""Conformance tests for a SolarDash store (docs/design/observer-protocol.md, version 1).

    python3 store/tests/conformance.py                       # starts store/store.php with `php -S`, temp data
    python3 store/tests/conformance.py --url https://amsler-solar.ch/solardash/store.php

Without --url the tests run against local PHP servers (one per settings profile, each with its own temporary data
directory, set through SOLARDASH_STORE_CONFIG). With --url they run against that store with fresh random keys;
tests that need small limits or the data directory are skipped there.

Needs Python 3 and the `cryptography` package.
"""

import argparse
import base64
import hashlib
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.error
import urllib.request
import uuid
import zlib
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

HERE = Path(__file__).resolve().parent
STORE_DIR = HERE.parent
VECTORS = json.loads((HERE / "vectors.json").read_text())

REGISTER, BLOCK, DAY, EVENT, LIVE, PLANT, STORES, DELETE = range(8)
MAX_PAYLOAD = 262144

# Settings of the local servers: each profile is one `php -S` with its own data directory.
PROFILES = {
    # Most tests: no rate limits, small body and plant limits, small index pages, no retention.
    "main": {"max_body": 1024 * 1024, "max_plant_bytes": 2 * 1024 * 1024, "collector_per_minute": 0,
             "observer_per_minute": 0, "new_plants_per_day": 0, "index_page": 5, "retention_chance": 0},
    # Rate limits.
    "limits": {"collector_per_minute": 4, "observer_per_minute": 4, "new_plants_per_day": 3,
               "retention_chance": 0},
    # Retention on every request.
    "retention": {"collector_per_minute": 0, "observer_per_minute": 0, "new_plants_per_day": 0,
                  "retention_chance": 1},
}
# The spec's limits, for a remote store.
REMOTE = {"max_body": 4 * 1024 * 1024, "index_page": 2000}

ARGS = None  # set in main()


def b64(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def unb64(s: str) -> bytes:
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def frames(envelopes) -> bytes:
    return b"".join(len(e).to_bytes(4, "big") + e for e in envelopes)


def unframe(body: bytes):
    out, at = [], 0
    while at < len(body):
        n = int.from_bytes(body[at:at + 4], "big")
        out.append(body[at + 4:at + 4 + n])
        at += 4 + n
    return out


def header(kind: int, seq: int, t: int, name: str) -> bytes:
    n = name.encode()
    return b"SDO1" + bytes([kind]) + seq.to_bytes(8, "big") + t.to_bytes(8, "big") + bytes([len(n)]) + n


def envelope_seq(env: bytes) -> int:
    return int.from_bytes(env[5:13], "big")


class Plant:
    """A collector's side: key, id, token, data key, rising seq."""

    def __init__(self, server, key=None, token=None, data_key=None):
        self.server = server
        self.key = key or ec.generate_private_key(ec.SECP256R1())
        self.spki = self.key.public_key().public_bytes(serialization.Encoding.DER,
                                                       serialization.PublicFormat.SubjectPublicKeyInfo)
        self.id = b64(hashlib.sha256(self.spki).digest()[:16])
        self.token = token or os.urandom(16)
        self.data_key = data_key or os.urandom(32)
        self.seq = 0

    def next_seq(self) -> int:
        self.seq += 1
        return self.seq

    def raw(self, kind, name, payload, seq=None, sign_with=None, t=None) -> bytes:
        seq = self.next_seq() if seq is None else seq
        t = int(time.time() * 1000) if t is None else t
        signed = header(kind, seq, t, name) + len(payload).to_bytes(4, "big") + payload
        sig = (sign_with or self.key).sign(signed, ec.ECDSA(hashes.SHA256()))
        return signed + len(sig).to_bytes(2, "big") + sig

    def sealed(self, kind, name, content: bytes, seq=None, t=None) -> bytes:
        seq = self.next_seq() if seq is None else seq
        t = int(time.time() * 1000) if t is None else t
        nonce = os.urandom(12)
        payload = nonce + AESGCM(self.data_key).encrypt(nonce, zlib.compress(content, 9), header(kind, seq, t, name))
        return self.raw(kind, name, payload, seq=seq, t=t)

    def register_envelope(self, seq=None, token=None) -> bytes:
        return self.raw(REGISTER, "register", self.spki + hashlib.sha256(token or self.token).digest(), seq=seq)

    def delete_envelope(self, seq=None) -> bytes:
        return self.raw(DELETE, "delete", b"", seq=seq)

    # Requests

    def register(self, **kw):
        answer = self.server.post(f"/v1/plants/{self.id}", self.register_envelope(**kw))
        if answer[0] == 200 and self not in self.server.plants:
            self.server.plants.append(self)
        return answer

    def items(self, envelopes):
        return self.server.post(f"/v1/plants/{self.id}/items", frames(envelopes))

    def observe(self, what, method="GET", body=None, token=None, query=""):
        t = self.token if token is None else token
        return self.server.request(method, f"/v1/observe/{self.id}/{what}", body, query=query,
                                   headers={"X-Observe-Token": b64(t) if isinstance(t, bytes) else t})

    def index(self, since=None):
        status, _, body = self.observe("index", query="" if since is None else f"&since={since}")
        assert status == 200, (status, body)
        return json.loads(body)

    def index_all(self):
        """Every page of the index."""
        items, since = [], 0
        while True:
            answer = self.index(since)
            items += answer["items"]
            if not answer["more"]:
                return items
            since = answer["items"][-1][2]

    def fetch(self, pairs):
        return self.observe("fetch", "POST", json.dumps(pairs).encode())


class Server:
    """A store: a local `php -S` with its own settings, or a remote URL."""

    def __init__(self, url, settings, process=None, data_dir=None):
        self.url = url
        self.settings = settings
        self.process = process
        self.data_dir = data_dir
        self.plants = []  # registered by the tests: deleted at the end

    @classmethod
    def local(cls, name, overrides):
        tmp = Path(tempfile.mkdtemp(prefix=f"solardash-store-{name}-"))
        data = tmp / "data"
        settings = {"data_dir": str(data), "max_body": 4 * 1024 * 1024, "index_page": 2000}
        settings.update(overrides)
        conf = tmp / "config.php"
        (tmp / "settings.json").write_text(json.dumps(settings))
        conf.write_text("<?php\nreturn json_decode(file_get_contents(__DIR__ . '/settings.json'), true);\n")
        with socket.socket() as s:
            s.bind(("127.0.0.1", 0))
            port = s.getsockname()[1]
        env = dict(os.environ, SOLARDASH_STORE_CONFIG=str(conf))
        process = subprocess.Popen(["php", "-S", f"127.0.0.1:{port}", "-t", str(STORE_DIR)], env=env,
                                   stdout=subprocess.DEVNULL, stderr=open(tmp / "php.log", "w"))
        for _ in range(100):
            try:
                socket.create_connection(("127.0.0.1", port), timeout=0.2).close()
                break
            except OSError:
                time.sleep(0.05)
        return cls(f"http://127.0.0.1:{port}/store.php", settings, process, data)

    def stop(self):
        if not self.process:  # a remote store: take away what the tests left
            for p in self.plants:
                self.post(f"/v1/plants/{p.id}/delete", p.delete_envelope(seq=p.seq + 1000))
        if self.process:
            self.process.terminate()
            self.process.wait(5)
            if ARGS.keep:
                print(f"kept {self.data_dir.parent}", file=sys.stderr)
            else:
                shutil.rmtree(self.data_dir.parent, ignore_errors=True)

    def request(self, method, route, body=None, headers=None, query="", raw_url=None):
        url = raw_url or (self.url + "?r=" + urllib.request.quote(route, safe="/") + query)
        h = {"Content-Type": "application/octet-stream"} if body is not None else {}
        h.update(headers or {})
        req = urllib.request.Request(url, data=body, method=method, headers=h)
        if ARGS and ARGS.delay:
            time.sleep(ARGS.delay)
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                return r.status, r.headers, r.read()
        except urllib.error.HTTPError as e:
            return e.code, e.headers, e.read()

    def get(self, route, **kw):
        return self.request("GET", route, **kw)

    def post(self, route, body=b"", **kw):
        return self.request("POST", route, body, **kw)

    def plant(self, register=True) -> Plant:
        p = Plant(self)
        if register:
            status, _, body = p.register()
            assert status == 200, (status, body)
        return p


SERVERS = {}


def server(profile="main") -> Server:
    if profile not in SERVERS:
        if ARGS.url:
            if profile != "main":
                raise unittest.SkipTest("needs a local store with its own settings")
            SERVERS[profile] = Server(ARGS.url, dict(REMOTE))
        else:
            SERVERS[profile] = Server.local(profile, PROFILES[profile])
    return SERVERS[profile]


def needs_local():
    if ARGS.url:
        raise unittest.SkipTest("needs a local store")


class StoreTest(unittest.TestCase):
    def setUp(self):
        self.s = server()

    def assertStatus(self, answer, status):
        self.assertEqual(answer[0], status, answer[2][:300])

    def assertError(self, answer, status):
        self.assertStatus(answer, status)
        self.assertIn("error", json.loads(answer[2]))

    def assertJson(self, answer, expected):
        self.assertStatus(answer, 200)
        self.assertEqual(json.loads(answer[2]), expected)


class Vectors(StoreTest):
    """The envelopes the app's Kotlin code made."""

    def setUp(self):
        super().setUp()
        self.key = serialization.load_der_private_key(unb64(VECTORS["privateKeyPkcs8"]), None)
        self.plant = Plant(self.s, key=self.key, token=unb64(VECTORS["readToken"]),
                           data_key=unb64(VECTORS["dataKey"]))
        self.env = {v["kind"]: unb64(v["envelope"]) for v in VECTORS["envelopes"]}
        if ARGS.url:  # a run that stopped half way may have left the plant behind
            self.s.post(f"/v1/plants/{self.plant.id}/delete", self.env[DELETE])

    def test_vectors_are_what_python_makes(self):
        self.assertEqual(self.plant.id, VECTORS["plantId"])
        self.assertEqual(self.plant.spki, unb64(VECTORS["publicKey"]))
        for v in VECTORS["envelopes"]:
            e = unb64(v["envelope"])
            n = e[21]
            p = int.from_bytes(e[22 + n:26 + n], "big")
            signed = e[:26 + n + p]
            s = int.from_bytes(e[26 + n + p:28 + n + p], "big")
            self.assertEqual(len(e), 28 + n + p + s)
            self.key.public_key().verify(e[28 + n + p:], signed, ec.ECDSA(hashes.SHA256()))
            self.assertEqual((e[4], e[22:22 + n].decode(), envelope_seq(e)), (v["kind"], v["name"], v["seq"]))
            if "content" in v:
                payload = e[26 + n:26 + n + p]
                plain = AESGCM(self.plant.data_key).decrypt(payload[:12], payload[12:], e[:22 + n])
                self.assertEqual(zlib.decompress(plain).decode(), v["content"])

    def test_register_store_fetch_delete(self):
        p, env = self.plant, self.env
        self.assertJson(self.s.post(f"/v1/plants/{p.id}", env[REGISTER]), {"ok": True})
        self.assertJson(p.items([env[PLANT], env[BLOCK], env[LIVE]]), {"stored": 3, "skipped": 0})
        status, headers, body = p.fetch([[PLANT, "plant"], [BLOCK, "b1762250400"]])
        self.assertEqual(status, 200)
        self.assertEqual(headers["Content-Type"], "application/octet-stream")
        self.assertEqual(unframe(body), [env[PLANT], env[BLOCK]])
        self.assertEqual(p.observe("live")[2], env[LIVE])
        self.assertEqual(p.observe("key")[2], p.spki)
        self.assertEqual(p.index(), {"items": [[PLANT, "plant", 2], [BLOCK, "b1762250400", 3]], "more": False})
        self.assertJson(self.s.post(f"/v1/plants/{p.id}/delete", env[DELETE]), {"ok": True})
        self.assertError(p.observe("key"), 403)
        self.assertError(p.items([env[PLANT]]), 404)


class Register(StoreTest):
    def test_register_and_repeat(self):
        p = self.s.plant()
        self.assertJson(p.register(seq=p.seq), {"ok": True})  # a retry: same seq, changes nothing

    def test_id_of_another_key(self):
        a, b = Plant(self.s), Plant(self.s)
        self.assertError(self.s.post(f"/v1/plants/{b.id}", a.register_envelope()), 403)
        self.assertError(b.observe("key"), 403)

    def test_bad_signature(self):
        p = Plant(self.s)
        e = bytearray(p.register_envelope())
        e[-5] ^= 1
        self.assertError(self.s.post(f"/v1/plants/{p.id}", bytes(e)), 403)
        e = p.raw(REGISTER, "register", p.spki + hashlib.sha256(p.token).digest(),
                  sign_with=ec.generate_private_key(ec.SECP256R1()))
        self.assertError(self.s.post(f"/v1/plants/{p.id}", e), 403)

    def test_not_a_register_envelope(self):
        p = Plant(self.s)
        self.assertError(self.s.post(f"/v1/plants/{p.id}", p.raw(REGISTER, "register", p.spki)), 400)
        self.assertError(self.s.post(f"/v1/plants/{p.id}", p.raw(PLANT, "plant", p.spki + bytes(32))), 400)
        self.assertError(self.s.post(f"/v1/plants/{p.id}", b"garbage"), 400)
        self.assertError(self.s.post(f"/v1/plants/{p.id}", b""), 400)
        # A compressed point: the same key, but not the form collectors make (and another id).
        point = p.key.public_key().public_bytes(serialization.Encoding.X962,
                                                serialization.PublicFormat.CompressedPoint)
        spki = bytes.fromhex("3039301306072a8648ce3d020106082a8648ce3d030107032200") + point
        e = p.raw(REGISTER, "register", spki + hashlib.sha256(p.token).digest())
        self.assertError(self.s.post(f"/v1/plants/{b64(hashlib.sha256(spki).digest()[:16])}", e), 400)
        # Not a P-256 key at all.
        rsa_like = bytes(91)
        e = p.raw(REGISTER, "register", rsa_like + bytes(32))
        self.assertError(self.s.post(f"/v1/plants/{b64(hashlib.sha256(rsa_like).digest()[:16])}", e), 400)

    def test_id_taken_by_another_key(self):
        needs_local()
        p = self.s.plant()
        other = Plant(self.s)
        (self.s.data_dir / "plants" / p.id / "key").write_bytes(other.spki)  # as if the hash collided
        self.assertError(p.register(), 409)

    def test_new_code(self):
        p = self.s.plant()
        old = p.token
        self.assertStatus(p.observe("index"), 200)
        p.token = os.urandom(16)
        self.assertJson(p.register(), {"ok": True})
        self.assertError(p.observe("index", token=old), 403)
        self.assertStatus(p.observe("index"), 200)
        # Replaying an older register (lower seq) changes nothing.
        replay = p.register_envelope(seq=1, token=old)
        self.assertJson(self.s.post(f"/v1/plants/{p.id}", replay), {"ok": True})
        self.assertError(p.observe("index", token=old), 403)
        self.assertStatus(p.observe("index"), 200)


class Items(StoreTest):
    def test_unknown_plant(self):
        p = Plant(self.s)
        self.assertError(p.items([p.sealed(PLANT, "plant", b"{}")]), 404)
        self.assertError(self.s.get(f"/v1/plants/{p.id}/watch"), 404)

    def test_bad_signature_stores_nothing(self):
        p = self.s.plant()
        bad = bytearray(p.sealed(DAY, "d2026-11-02", b"{}"))
        bad[40] ^= 1  # in the payload
        batch = [p.sealed(DAY, "d2026-11-01", b"{}"), bytes(bad), p.sealed(DAY, "d2026-11-03", b"{}"),
                 p.sealed(LIVE, "live", b"{}")]
        self.assertError(p.items(batch), 403)
        other = p.raw(DAY, "d2026-11-04", b"x" * 40, sign_with=ec.generate_private_key(ec.SECP256R1()))
        self.assertError(p.items([p.sealed(DAY, "d2026-11-05", b"{}"), other]), 403)
        self.assertEqual(p.index(), {"items": [], "more": False})
        self.assertStatus(p.observe("live"), 204)

    def test_seq(self):
        p = self.s.plant()
        first = p.sealed(PLANT, "plant", b'{"name":"A"}', seq=10)
        self.assertJson(p.items([first]), {"stored": 1, "skipped": 0})
        same = p.sealed(PLANT, "plant", b'{"name":"B"}', seq=10)
        lower = p.sealed(PLANT, "plant", b'{"name":"C"}', seq=9)
        self.assertJson(p.items([same, lower]), {"stored": 0, "skipped": 2})
        self.assertEqual(unframe(p.fetch([[PLANT, "plant"]])[2]), [first])
        higher = p.sealed(PLANT, "plant", b'{"name":"D"}', seq=11)
        self.assertJson(p.items([higher]), {"stored": 1, "skipped": 0})
        self.assertEqual(unframe(p.fetch([[PLANT, "plant"]])[2]), [higher])
        self.assertEqual(p.index()["items"], [[PLANT, "plant", 11]])
        # Within one request the highest wins.
        a, b = p.sealed(STORES, "stores", b"[]", seq=13), p.sealed(STORES, "stores", b"[]", seq=12)
        self.assertJson(p.items([a, b]), {"stored": 1, "skipped": 1})
        self.assertEqual(unframe(p.fetch([[STORES, "stores"]])[2]), [a])

    def test_live(self):
        p = self.s.plant()
        self.assertStatus(p.observe("live"), 204)
        a = p.sealed(LIVE, "live", b'{"pv":1}')
        b = p.sealed(LIVE, "live", b'{"pv":2}')
        self.assertJson(p.items([a]), {"stored": 1, "skipped": 0})
        self.assertJson(p.items([b, a]), {"stored": 1, "skipped": 1})
        status, headers, body = p.observe("live")
        self.assertEqual((status, headers["Content-Type"], body), (200, "application/octet-stream", b))
        self.assertEqual(p.index(), {"items": [], "more": False})

    def test_all_kinds(self):
        p = self.s.plant()
        device = str(uuid.uuid4())
        batch = [p.sealed(BLOCK, "b1762250400", b"x"), p.sealed(BLOCK, f"b1762250400.{device}", b"y"),
                 p.sealed(DAY, "d2026-11-04", b"{}"), p.sealed(EVENT, "e42", b"{}"),
                 p.sealed(LIVE, "live", b"{}"), p.sealed(PLANT, "plant", b"{}"),
                 p.sealed(STORES, "stores", b"[]")]
        self.assertJson(p.items(batch), {"stored": 7, "skipped": 0})
        names = [[k, n] for k, n, _ in p.index_all()]
        self.assertEqual(names, [[BLOCK, "b1762250400"], [BLOCK, f"b1762250400.{device}"], [DAY, "d2026-11-04"],
                                 [EVENT, "e42"], [PLANT, "plant"], [STORES, "stores"]])

    def test_kinds_not_allowed(self):
        p = self.s.plant()
        for e in [p.register_envelope(), p.delete_envelope()]:
            self.assertError(p.items([p.sealed(PLANT, "plant", b"{}"), e]), 400)
        self.assertEqual(p.index()["items"], [])


class Index(StoreTest):
    def test_paging_and_since(self):
        p = self.s.plant()
        page = self.s.settings["index_page"]
        if page > 50:
            self.skipTest("paging needs a store with small index pages")
        device = str(uuid.uuid4())
        envelopes = []
        for i in range(page * 2 + 2):
            name = f"b{1762250400 + 3600 * (i // 2)}" + (f".{device}" if i % 2 else "")
            envelopes.append(p.sealed(BLOCK, name, b"block"))
        # Sent newest first: the index still goes by rising seq.
        self.assertJson(p.items(list(reversed(envelopes))), {"stored": len(envelopes), "skipped": 0})
        expected = [[BLOCK, e[22:22 + e[21]].decode(), envelope_seq(e)] for e in envelopes]
        got, since, rounds = [], 0, 0
        while True:
            answer = p.index(since)
            self.assertLessEqual(len(answer["items"]), page)
            got += answer["items"]
            rounds += 1
            if not answer["more"]:
                break
            since = answer["items"][-1][2]
        self.assertEqual(got, expected)
        self.assertEqual(rounds, 3)
        self.assertTrue(p.index()["more"])
        # A replaced item moves to the end; since filters by seq.
        newer = p.sealed(BLOCK, expected[0][1], b"again")
        self.assertJson(p.items([newer]), {"stored": 1, "skipped": 0})
        self.assertEqual(p.index(p.seq - 1), {"items": [[BLOCK, expected[0][1], p.seq]], "more": False})
        self.assertEqual(p.index(p.seq), {"items": [], "more": False})
        self.assertEqual(p.index(expected[-2][2])["items"], [expected[-1], [BLOCK, expected[0][1], p.seq]])

    def test_since_filter(self):
        p = self.s.plant()
        a, b, c = (p.sealed(DAY, f"d2026-11-0{i}", b"{}") for i in (1, 2, 3))
        p.items([a, b, c])
        self.assertEqual([i[1] for i in p.index(envelope_seq(a))["items"]], ["d2026-11-02", "d2026-11-03"])
        for bad in ["-1", "x", "1.5", "99999999999999999999"]:
            self.assertError(p.observe("index", query=f"&since={bad}"), 400)


class Fetch(StoreTest):
    def test_order_and_missing(self):
        p = self.s.plant()
        a, b, c = p.sealed(DAY, "d2026-11-01", b"a"), p.sealed(EVENT, "e7", b"b"), p.sealed(PLANT, "plant", b"c")
        p.items([a, b, c])
        status, _, body = p.fetch([[PLANT, "plant"], [DAY, "d2025-01-01"], [DAY, "d2026-11-01"], [EVENT, "e8"],
                                   [EVENT, "e7"]])
        self.assertEqual(status, 200)
        self.assertEqual(unframe(body), [c, a, b])
        self.assertEqual(p.fetch([])[2], b"")

    def test_limits_and_malformed(self):
        p = self.s.plant()
        self.assertStatus(p.fetch([[DAY, "d2026-11-01"]] * 200), 200)
        self.assertError(p.fetch([[DAY, "d2026-11-01"]] * 201), 413)
        for bad in [b"", b"{", b'{"a": 1}', b"[1]", b'[[1]]', b'[["1", "b1"]]', b'[[1, 2]]', b'[[1, "b1", 3]]',
                    b'[[0, "register"]]', b'[[7, "delete"]]', b'[[1, "plant"]]', b'[[1, "../key"]]',
                    b'[[5, "plant/../../x"]]', b'[{"0": 1, "1": "b1"}]', b'{"0": [1, "b1"]}']:
            self.assertError(p.observe("fetch", "POST", bad), 400)


class Observer(StoreTest):
    ROUTES = [("key", "GET"), ("index", "GET"), ("fetch", "POST"), ("live", "GET"), ("watch", "POST")]

    def test_wrong_token_and_unknown_plant_alike(self):
        p = self.s.plant()
        unknown = Plant(self.s)
        for route, method in self.ROUTES:
            body = b"[]" if method == "POST" else None
            answers = [p.observe(route, method, body, token=os.urandom(16)),
                       unknown.observe(route, method, body),
                       p.observe(route, method, body, token=""),
                       p.observe(route, method, body, token="not base64!"),
                       p.observe(route, method, body, token=p.token[:15]),
                       p.observe(route, method, body, token=b64(p.token) + "A")]
            for a in answers:
                self.assertEqual((a[0], a[2]), (answers[0][0], answers[0][2]), route)
            self.assertEqual(answers[0][0], 403)
            self.assertEqual(p.observe(route, method, body)[0], 204 if route in ("live", "watch") else 200)

    def test_watch(self):
        p = self.s.plant()
        self.assertJson(self.s.get(f"/v1/plants/{p.id}/watch"), {"watching": False})
        self.assertStatus(p.observe("watch", "POST", b""), 204)
        self.assertJson(self.s.get(f"/v1/plants/{p.id}/watch"), {"watching": True})

    def test_watch_expires(self):
        needs_local()
        p = self.s.plant()
        p.observe("watch", "POST", b"")
        old = time.time() - 121
        os.utime(self.s.data_dir / "plants" / p.id / "watch", (old, old))
        self.assertJson(self.s.get(f"/v1/plants/{p.id}/watch"), {"watching": False})


class Delete(StoreTest):
    def test_delete_removes_everything(self):
        p = self.s.plant()
        p.items([p.sealed(PLANT, "plant", b"{}"), p.sealed(LIVE, "live", b"{}"), p.sealed(DAY, "d2026-11-01", b"")])
        bad = bytearray(p.delete_envelope())
        bad[-3] ^= 1
        self.assertError(self.s.post(f"/v1/plants/{p.id}/delete", bytes(bad)), 403)
        self.assertError(self.s.post(f"/v1/plants/{p.id}/delete", p.delete_envelope(seq=2)), 409)  # stale
        self.assertError(self.s.post(f"/v1/plants/{p.id}/delete", p.raw(DELETE, "delete", b"x")), 400)
        self.assertError(self.s.post(f"/v1/plants/{p.id}/delete", p.sealed(PLANT, "plant", b"{}")), 400)
        self.assertStatus(p.observe("index"), 200)
        self.assertJson(self.s.post(f"/v1/plants/{p.id}/delete", p.delete_envelope()), {"ok": True})
        for route, method in Observer.ROUTES:
            self.assertError(p.observe(route, method, b"[]" if method == "POST" else None), 403)
        self.assertError(self.s.get(f"/v1/plants/{p.id}/watch"), 404)
        self.assertError(self.s.post(f"/v1/plants/{p.id}/delete", p.delete_envelope()), 404)
        if not ARGS.url:
            self.assertFalse((self.s.data_dir / "plants" / p.id).exists())
        # The collector may come back: a fresh plant, nothing of the old one.
        self.assertJson(p.register(), {"ok": True})
        self.assertEqual(p.index(), {"items": [], "more": False})
        self.assertStatus(p.observe("live"), 204)


class Limits(StoreTest):
    def test_payload_too_large(self):
        p = self.s.plant()
        self.assertError(p.items([p.raw(DAY, "d2026-11-01", b"x" * (MAX_PAYLOAD + 1))]), 413)
        self.assertJson(p.items([p.raw(DAY, "d2026-11-01", b"x" * MAX_PAYLOAD)]), {"stored": 1, "skipped": 0})

    def test_body_too_large(self):
        p = self.s.plant()
        self.assertError(self.s.post(f"/v1/plants/{p.id}/items", b"\0" * (self.s.settings["max_body"] + 1)), 413)

    def test_plant_storage(self):
        needs_local()
        p = self.s.plant()
        limit = self.s.settings["max_plant_bytes"]
        sent, n = 0, 0
        while True:
            batch = [p.raw(DAY, f"d2020-01-{n + i + 1:02d}", os.urandom(200_000)) for i in range(4)]
            answer = p.items(batch)
            if answer[0] == 413:
                break
            self.assertStatus(answer, 200)
            n += 4
            sent += sum(len(e) for e in batch)
            self.assertLess(sent, limit)
        self.assertEqual(len(p.index_all()), n)
        # Replacing stored items doesn't grow the plant.
        again = [p.raw(DAY, f"d2020-01-{i + 1:02d}", os.urandom(200_000)) for i in range(4)]
        self.assertJson(p.items(again), {"stored": 4, "skipped": 0})

    def test_malformed_frames(self):
        p = self.s.plant()
        good = p.sealed(PLANT, "plant", b"{}")
        cases = [
            b"",                                   # nothing
            b"\0\0",                               # truncated length
            (len(good) + 1).to_bytes(4, "big") + good,  # length beyond the body
            frames([good]) + b"\0",                # trailing bytes
            frames([good[:-1]]),                   # truncated signature
            frames([good + b"\0"]),                # trailing byte in the envelope
            frames([b"XDO1" + good[4:]]),          # magic
            frames([good[:4] + b"\x09" + good[5:]]),  # unknown kind
            frames([good[:5] + b"\x80" + good[6:]]),  # seq beyond 2^63
        ]
        for i, body in enumerate(cases):
            self.assertError(self.s.post(f"/v1/plants/{p.id}/items", body), 400)
        self.assertEqual(p.index()["items"], [])

    def test_names(self):
        p = self.s.plant()
        for kind, name in [(BLOCK, "plant"), (BLOCK, "b"), (BLOCK, "bx"), (BLOCK, "b1."), (BLOCK, "b1.."),
                           (BLOCK, "b1..x"), (DAY, "d2026-1-1"), (DAY, "dnow"), (EVENT, "e"), (EVENT, "e.."),
                           (LIVE, "live2"), (PLANT, "Plant"), (STORES, "store"), (PLANT, ".."), (PLANT, "."),
                           (EVENT, "e/../../key"), (EVENT, "e\\x"), (BLOCK, "b1" + "0" * 63)]:
            self.assertError(p.items([p.raw(kind, name, b"x")]), 400)
        # Raw bytes no name rule allows: a slash, a NUL, a length of 0.
        e = p.raw(EVENT, "eAAAA", b"x")
        for bad in [b"e/../", b"e\0\0\0\0"]:
            self.assertError(p.items([e[:22] + bad + e[27:]]), 400)
        self.assertError(p.items([e[:21] + b"\0" + e[22:]]), 400)
        self.assertEqual(p.index()["items"], [])

    def test_routes_and_ids(self):
        p = self.s.plant()
        for route in [f"/v1/plants/{p.id}/../../store.php", "/v1/plants/../items", f"/v1/plants/{p.id}/",
                      f"/v1/observe/{p.id}/key/x", "/v2/plants/x", "", "/", f"/v1/plants/{p.id}/register"]:
            for answer in [self.s.get(route), self.s.post(route, b"x")]:
                self.assertIn(answer[0], (400, 404), route)
                self.assertIn("error", json.loads(answer[2]))
        # The last: 22 characters, but not canonical base64url of 16 bytes (the last character carries 2 bits).
        for bad in ["..", "." * 22, p.id[:21], p.id + "A", p.id[:21] + "%", "a" * 21 + ".", p.id[:21] + "B"]:
            self.assertError(self.s.get(f"/v1/plants/{bad}/watch"), 400)
            self.assertError(self.s.get(f"/v1/observe/{bad}/key", headers={"X-Observe-Token": b64(p.token)}), 400)
        # A newline after the id.
        self.assertIn(self.s.get(f"/v1/plants/{p.id}\n/watch")[0], (400, 404))
        self.assertIn(self.s.get(f"/v1/plants/{p.id}/watch\n")[0], (400, 404))
        status, _, body = self.s.request("GET", "", raw_url=self.s.url + "?r[]=x")
        self.assertEqual(status, 404)

    def test_methods(self):
        p = self.s.plant()
        status, headers, _ = self.s.get(f"/v1/plants/{p.id}/items")
        self.assertEqual(status, 405)
        self.assertIn("POST", headers["Allow"])
        self.assertError(self.s.post(f"/v1/plants/{p.id}/watch", b""), 405)
        self.assertError(self.s.get(f"/v1/observe/{p.id}/fetch"), 405)

    def test_path_info(self):
        needs_local()  # whether a host passes PATH_INFO is up to it
        p = self.s.plant()
        status, _, body = self.s.request("GET", "", raw_url=self.s.url + f"/v1/plants/{p.id}/watch")
        self.assertEqual((status, json.loads(body)), (200, {"watching": False}))

    def test_headers_and_cors(self):
        p = self.s.plant()
        status, h, _ = self.s.get(f"/v1/plants/{p.id}/watch")
        self.assertEqual(h["X-Content-Type-Options"], "nosniff")
        self.assertEqual(h["Cache-Control"], "no-store")
        self.assertEqual(h["Referrer-Policy"], "no-referrer")
        self.assertEqual(h["Content-Type"], "application/json")
        self.assertEqual(h["Access-Control-Allow-Origin"], "https://amsler-solar.ch")
        status, h, _ = self.s.request("OPTIONS", f"/v1/observe/{p.id}/index",
                                      headers={"Origin": "https://amsler-solar.ch",
                                               "Access-Control-Request-Method": "GET",
                                               "Access-Control-Request-Headers": "x-observe-token"})
        self.assertEqual(status, 204)
        self.assertIn("X-Observe-Token", h["Access-Control-Allow-Headers"])
        self.assertIn("POST", h["Access-Control-Allow-Methods"])


class RateLimits(StoreTest):
    """One client address: the plants these tests need are made first, using up the new plants of the day."""

    @classmethod
    def setUpClass(cls):
        s = server("limits")
        cls.plants = [s.plant() for _ in range(PROFILES["limits"]["new_plants_per_day"])]

    def setUp(self):
        self.s = server("limits")

    def until_429(self, call, limit):
        for _ in range(2 * limit + 2):  # a minute may turn over once
            answer = call()
            if answer[0] == 429:
                retry = int(answer[1]["Retry-After"])
                self.assertTrue(1 <= retry <= 86400, retry)
                self.assertIn("error", json.loads(answer[2]))
                return retry
            self.assertIn(answer[0], (200, 204), answer[2])
        self.fail("no 429")

    def test_collector(self):
        p = self.plants[0]
        retry = self.until_429(lambda: self.s.get(f"/v1/plants/{p.id}/watch"), 4)
        self.assertLessEqual(retry, 60)
        self.until_429(lambda: p.items([p.sealed(LIVE, "live", b"{}")]), 4)
        # Another plant has its own count.
        q = self.plants[1]
        self.assertStatus(self.s.get(f"/v1/plants/{q.id}/watch"), 200)

    def test_observer(self):
        p = self.plants[2]
        retry = self.until_429(lambda: p.observe("live"), 4)
        self.assertLessEqual(retry, 60)

    def test_new_plants(self):
        retry = self.until_429(lambda: Plant(self.s).register(), 3)
        self.assertLessEqual(retry, 86400)


class Retention(StoreTest):
    def setUp(self):
        self.s = server("retention")

    def test_old_blocks_and_days(self):
        p = self.s.plant()
        now = int(time.time())
        hour = now - now % 3600
        old_hour = hour - 3 * 365 * 86400
        device = str(uuid.uuid4())
        old_day = time.strftime("%Y-%m-%d", time.gmtime(now - 3 * 365 * 86400))
        today = time.strftime("%Y-%m-%d", time.gmtime(now))
        p.items([p.sealed(BLOCK, f"b{old_hour}", b""), p.sealed(BLOCK, f"b{old_hour}.{device}", b""),
                 p.sealed(DAY, f"d{old_day}", b"{}"), p.sealed(BLOCK, f"b{hour}", b""),
                 p.sealed(BLOCK, f"b{hour}.{device}", b""), p.sealed(DAY, f"d{today}", b"{}"),
                 p.sealed(EVENT, "e1", b"{}"), p.sealed(PLANT, "plant", b"{}")])
        names = sorted(i[1] for i in p.index()["items"])
        self.assertEqual(names, sorted([f"b{hour}", f"b{hour}.{device}", f"d{today}", "e1", "plant"]))
        self.assertEqual(unframe(p.fetch([[BLOCK, f"b{old_hour}"], [DAY, f"d{old_day}"]])[2]), [])

    def test_idle_plant(self):
        needs_local()
        p, q = self.s.plant(), self.s.plant()
        old = time.time() - 366 * 86400
        os.utime(self.s.data_dir / "plants" / p.id / "uploaded", (old, old))
        self.assertStatus(self.s.get(f"/v1/plants/{q.id}/watch"), 200)  # any request sweeps
        self.assertError(self.s.get(f"/v1/plants/{p.id}/watch"), 404)
        self.assertFalse((self.s.data_dir / "plants" / p.id).exists())


def main():
    global ARGS
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--url", help="a store to test, e.g. https://amsler-solar.ch/solardash/store.php")
    parser.add_argument("--delay", type=float, default=None, help="seconds between requests (default: 0.2 with --url)")
    parser.add_argument("--keep", action="store_true", help="keep the local servers' data and php.log")
    parser.add_argument("-v", "--verbose", action="store_true")
    ARGS, rest = parser.parse_known_args()
    if ARGS.delay is None:
        ARGS.delay = 0.2 if ARGS.url else 0
    try:
        program = unittest.main(argv=[sys.argv[0]] + rest, verbosity=2 if ARGS.verbose else 1, exit=False)
    finally:
        for s in SERVERS.values():
            s.stop()
    sys.exit(0 if program.result.wasSuccessful() else 1)


if __name__ == "__main__":
    main()
