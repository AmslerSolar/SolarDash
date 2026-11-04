#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Amsler Solar GmbH
"""Runs the web view's tests headless in Chromium: the SRI check, o/test.html (pure functions against
store/tests/vectors.json) and o/index.html end to end against fake_store.py (vectors as they are, fresh envelopes,
German, wrong code, unreachable store, broken link). Prints PASS/FAIL lines; exit status 1 on any failure.

  python3 store/web/tools/run_tests.py [--chromium /usr/bin/chromium] [--screenshots DIR]

--screenshots writes phone, tablet and dark-mode screenshots of the fresh plant to DIR for a look by eye.
The fresh cases need python3-cryptography; without it they are skipped.
"""

import argparse
import base64
import fcntl
import html
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import select

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fake_store  # noqa: E402
import sri  # noqa: E402

results = []


def report(ok, name, detail=''):
    results.append(ok)
    print(('PASS ' if ok else 'FAIL ') + name + (': ' + detail if detail and not ok else ''))


def free_port():
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]


def start(fresh, page_size=2000):
    port = free_port()
    server = fake_store.serve(port, fresh, page_size)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return port, server, fake_store.Handler.data


class Browser:
    """Headless Chromium driven over the DevTools protocol on a pipe (--remote-debugging-pipe, standard library only),
    so the tests wait for the page itself rather than a fixed time."""

    def __init__(self, exe):
        self.profile = tempfile.mkdtemp(prefix='sd-chromium-')
        to_chrome_r, self.to_chrome = os.pipe()
        self.from_chrome, from_chrome_w = os.pipe()

        def fds():  # Chromium reads commands on fd 3 and writes answers to fd 4.
            a = fcntl.fcntl(to_chrome_r, fcntl.F_DUPFD, 10)
            b = fcntl.fcntl(from_chrome_w, fcntl.F_DUPFD, 10)
            os.dup2(a, 3)
            os.dup2(b, 4)

        self.proc = subprocess.Popen(
            [exe, '--headless=new', '--disable-gpu', '--no-sandbox', '--no-first-run', '--remote-debugging-pipe',
             '--user-data-dir=' + self.profile, 'about:blank'],
            stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            preexec_fn=fds, close_fds=False)
        os.close(to_chrome_r)
        os.close(from_chrome_w)
        self.buf = b''
        self.next_id = 0
        self.session = None

    def send(self, method, params=None, session=True, timeout=30):
        self.next_id += 1
        msg = {'id': self.next_id, 'method': method, 'params': params or {}}
        if session and self.session:
            msg['sessionId'] = self.session
        os.write(self.to_chrome, json.dumps(msg).encode() + b'\0')
        deadline = time.time() + timeout
        while True:
            while b'\0' not in self.buf:
                left = deadline - time.time()
                if left <= 0 or not select.select([self.from_chrome], [], [], left)[0]:
                    raise TimeoutError(method)
                chunk = os.read(self.from_chrome, 1 << 20)
                if not chunk:
                    raise EOFError('Chromium exited')
                self.buf += chunk
            raw, self.buf = self.buf.split(b'\0', 1)
            answer = json.loads(raw)
            if answer.get('id') == self.next_id:
                if 'error' in answer:
                    raise RuntimeError('%s: %s' % (method, answer['error']))
                return answer.get('result', {})

    def open(self, url, size=(390, 844), dark=False, lang='en-US', ready='true', timeout=20):
        """Opens url in a new tab and waits until the JavaScript expression ready is true."""
        if self.session:
            self.send('Target.closeTarget', {'targetId': self.target}, session=False)
        self.target = self.send('Target.createTarget', {'url': 'about:blank'}, session=False)['targetId']
        self.session = self.send('Target.attachToTarget', {'targetId': self.target, 'flatten': True}, session=False)['sessionId']
        w, h = size
        self.send('Emulation.setDeviceMetricsOverride', {'width': w, 'height': h, 'deviceScaleFactor': 1, 'mobile': w < 600})
        self.send('Emulation.setEmulatedMedia', {'features': [{'name': 'prefers-color-scheme', 'value': 'dark' if dark else 'light'}]})
        self.send('Emulation.setLocaleOverride', {'locale': lang})
        ua = self.send('Browser.getVersion', session=False)['userAgent'].replace('HeadlessChrome', 'Chrome')
        self.send('Emulation.setUserAgentOverride', {'userAgent': ua, 'acceptLanguage': lang})
        self.send('Page.navigate', {'url': url})
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                if self.eval(ready) is True:
                    return True
            except RuntimeError:
                pass
            time.sleep(0.2)
        return False

    def eval(self, expression):
        r = self.send('Runtime.evaluate', {'expression': expression, 'returnByValue': True})
        if 'exceptionDetails' in r:
            raise RuntimeError(r['exceptionDetails'].get('text'))
        return r['result'].get('value')

    def dom(self):
        return self.eval('document.documentElement.outerHTML')

    def screenshot(self, path):
        data = self.send('Page.captureScreenshot', {'format': 'png', 'captureBeyondViewport': True})['data']
        with open(path, 'wb') as f:
            f.write(base64.b64decode(data))

    def close(self):
        try:
            self.send('Browser.close', session=False, timeout=5)
        except Exception:
            pass
        try:
            self.proc.wait(10)
        except subprocess.TimeoutExpired:
            self.proc.kill()
        shutil.rmtree(self.profile, ignore_errors=True)


# The page is done loading: content or an error shown, and (for content) the live values arrived.
SHOWN = ("document.getElementById('loading').hidden && (!document.getElementById('error').hidden || "
         "!document.getElementById('content').hidden)")
NO_SIDEWAYS_SCROLL = 'document.documentElement.scrollWidth <= window.innerWidth'


def text_of(dom, element_id):
    m = re.search(r'id="%s"[^>]*>(.*?)</' % re.escape(element_id), dom, re.S)
    return html.unescape(m.group(1)).strip() if m else None


def check(case, dom, wants, unwanted=()):
    for w in wants:
        report(w in dom, '%s: has %s' % (case, w), 'not in the page')
    for u in unwanted:
        report(u not in dom, '%s: has no %s' % (case, u), 'found in the page')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--chromium', default=shutil.which('chromium') or '/usr/bin/chromium')
    ap.add_argument('--screenshots')
    a = ap.parse_args()

    report(sri.main() == 0, 'SRI hashes')
    b = Browser(a.chromium)
    try:
        # Vectors as they are; index pages of 1 item to exercise paging.
        port, _, data = start(False, page_size=1)

        b.open('http://127.0.0.1:%d/web/o/test.html' % port,
               ready="document.getElementById('summary').textContent !== 'RUNNING'", timeout=60)
        dom = b.dom()
        out = text_of(dom, 'out') or ''
        for ln in out.splitlines():
            if ln.startswith('PASS ') or ln.startswith('FAIL '):
                report(ln.startswith('PASS '), 'test.html: ' + ln[5:])
        summary = text_of(dom, 'summary')
        report(bool(summary) and summary.startswith('ALL PASS'), 'test.html summary: %s' % summary)

        link = fake_store.link(port, data=data)
        frag = link.split('#', 1)[1]
        report(b.open(link, ready=SHOWN), 'vectors: page loads')
        dom = b.dom()
        check('vectors', dom, [
            '<h1 id="plant-name">Home</h1>', 'aria-label="Solar 3200 W"', '>3.20 kW<', 'class="line s-pv"',
            'href="solardash://observe#%s"' % frag, 'id="status" class="status stale"', '2025',
        ], ['id="content" hidden', 'Home 3200 W'])
        report(b.eval(NO_SIDEWAYS_SCROLL) is True, 'vectors: no sideways scroll at 390 px')

        wrong = fake_store.link(port, token='AAAAAAAAAAAAAAAAAAAAAA', data=data)
        report(b.open(wrong, ready=SHOWN), 'wrong code: page loads')
        check('wrong code', b.dom(), ['The code is not valid'], ['<section id="error" class="error" role="alert" hidden'])

        # The page from this server, the store list pointing at a port where nothing listens.
        dead = 'http://127.0.0.1:%d/web/o/#%s' % (port, fake_store.link(free_port(), data=data).split('#', 1)[1])
        report(b.open(dead, ready=SHOWN), 'unreachable: page loads')
        check('unreachable', b.dom(), ['The store cannot be reached'])

        for name, h in (('broken link', '#abc.def'), ('no link', '')):
            report(b.open('http://127.0.0.1:%d/web/o/%s' % (port, h), ready=SHOWN), name + ': page loads')
            check(name, b.dom(), ['This link is incomplete or damaged'], ['href="solardash://observe#'])

        try:
            import cryptography  # noqa: F401
            have_crypto = True
        except ImportError:
            have_crypto = False
            print('SKIP fresh cases: python3-cryptography is missing')
        if have_crypto:
            port, _, data = start(True)
            link = fake_store.link(port, data=data)
            live_shown = SHOWN + " && document.getElementById('status').className === 'status ok'"
            report(b.open(link, ready=live_shown), 'fresh: page loads')
            check('fresh', b.dom(), [
                '<h1 id="plant-name">Home</h1>', 'aria-label="Solar 3200 W"', 'Updated ', 'id="status" class="status ok"',
                '>1.80 kW<', 'id="s-grid" class="sub" x="72" y="302" text-anchor="middle">Export<',
                '>64 %<', '>Charging<', 'aria-label="Home 900 W"', '>18.4 kWh<', '>9.6 kWh<', '>7.9 kWh<', '>1.2 kWh<',
                'Battery temperature high', '<strong>Warning</strong>', '>Today<',
                'class="line s-pv"', 'class="line s-load"', 'class="line s-grid"', 'class="line s-battery"',
            ], ['Fan check', 'id="problems-box" class="problems" hidden'])
            report(b.eval(NO_SIDEWAYS_SCROLL) is True, 'fresh: no sideways scroll at 390 px')
            seq = b.eval("document.getElementById('status').textContent")
            time.sleep(3.5)
            report(b.eval("document.getElementById('status').className") == 'status ok', 'fresh: live poll keeps it up to date (%s)' % seq)
            b.open(link, size=(320, 640), ready=live_shown)
            report(b.eval(NO_SIDEWAYS_SCROLL) is True, 'fresh: no sideways scroll at 320 px')
            report(b.open(link, lang='de-CH', ready=live_shown), 'fresh, German: page loads')
            check('fresh, German', b.dom(), ['lang="de"', '>Haus<', '>Einspeisung<', 'Aktualisiert ', '>Heute<', '>Lädt<',
                                             'In SolarDash öffnen', '>3.20 kW<'])
            if a.screenshots:
                os.makedirs(a.screenshots, exist_ok=True)
                shots = (('phone', link, (390, 844), False, 'en-US'), ('phone-dark', link, (390, 844), True, 'en-US'),
                         ('phone-de', link, (390, 844), False, 'de-CH'), ('tablet', link, (1024, 768), False, 'en-US'),
                         ('tablet-dark', link, (1024, 768), True, 'de-CH'),
                         ('error', fake_store.link(port, token='AAAAAAAAAAAAAAAAAAAAAA', data=data), (390, 844), False, 'en-US'))
                for name, url, size, dark, lang in shots:
                    b.open(url, size=size, dark=dark, lang=lang, ready=SHOWN)
                    time.sleep(0.5)
                    path = os.path.join(a.screenshots, name + '.png')
                    b.screenshot(path)
                    print('Screenshot ' + path)
    finally:
        b.close()

    failed = results.count(False)
    print('%s: %d checks, %d failed' % ('ALL PASS' if not failed else 'FAILED', len(results), failed))
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
