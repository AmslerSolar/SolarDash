#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Amsler Solar GmbH
"""Runs the web view's tests headless in Chromium: the SRI check, o/test.html (pure functions against
store/tests/vectors.json) and o/index.html end to end against fake_store.py (vectors as they are, fresh envelopes,
German, wrong code, unreachable store, broken link; with fresh envelopes also the history: periods, navigation,
what is fetched, channel picker, zoom, and the fault log). Prints PASS/FAIL lines; exit status 1 on any failure.

  python3 store/web/tools/run_tests.py [--chromium /usr/bin/chromium] [--screenshots DIR]

--screenshots writes phone, tablet and dark-mode screenshots of the fresh plant to DIR for a look by eye, and of
the history (week, month, year, zoomed day), the channel picker and the fault log.
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

    def open(self, url, size=(390, 844), dark=False, lang='en-US', ready='true', timeout=20, touch=False):
        """Opens url in a new tab (touch: as a touch screen, pointer: coarse) and waits until the JavaScript expression ready is true."""
        if self.session:
            self.send('Target.closeTarget', {'targetId': self.target}, session=False)
        self.target = self.send('Target.createTarget', {'url': 'about:blank'}, session=False)['targetId']
        self.session = self.send('Target.attachToTarget', {'targetId': self.target, 'flatten': True}, session=False)['sessionId']
        w, h = size
        self.send('Emulation.setDeviceMetricsOverride', {'width': w, 'height': h, 'deviceScaleFactor': 1, 'mobile': w < 600})
        if touch:
            self.send('Emulation.setTouchEmulationEnabled', {'enabled': True, 'maxTouchPoints': 5})
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

    def wait(self, expression, timeout=10):
        """Waits until the JavaScript expression is true; False after timeout."""
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                if self.eval(expression) is True:
                    return True
            except RuntimeError:
                pass
            time.sleep(0.1)
        return False

    def center(self, selector):
        """Scrolls the element into view; its centre in viewport coordinates."""
        return self.eval("(() => { const e = document.querySelector(%s); e.scrollIntoView({block: 'center'}); "
                         "const r = e.getBoundingClientRect(); return [r.left + r.width / 2, r.top + r.height / 2, r.width]; })()"
                         % json.dumps(selector))

    def mouse(self, kind, x, y, **kw):
        self.send('Input.dispatchMouseEvent', dict({'type': kind, 'x': x, 'y': y}, **kw))

    def touch(self, kind, points):
        self.send('Input.dispatchTouchEvent', {'type': kind, 'touchPoints': [{'x': x, 'y': y, 'id': i} for i, (x, y) in enumerate(points)]})

    def dom(self):
        return self.eval('document.documentElement.outerHTML')

    def screenshot(self, path, element=None):
        """The whole page, or (element: an id) just that element."""
        params = {'format': 'png', 'captureBeyondViewport': True}
        if element:
            r = self.eval("(() => { const r = document.getElementById(%s).getBoundingClientRect(); "
                          "return [r.left + scrollX, r.top + scrollY, r.width, r.height]; })()" % json.dumps(element))
            params['clip'] = {'x': r[0], 'y': r[1], 'width': r[2], 'height': r[3], 'scale': 1}
        data = self.send('Page.captureScreenshot', params)['data']
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

IDLE = "document.getElementById('history').getAttribute('aria-busy') === 'false'"


def fetched(data, since):
    """[[kind, name], …] the page fetched since request number since."""
    return [pair for op, pairs in data.requests[since:] if op == 'fetch' for pair in pairs]


def fetches(data, since):
    return sum(1 for op, _ in data.requests[since:] if op == 'fetch')


def history_cases(b, link, data, shots):
    """The history and fault log with the fresh plant: periods, navigation, fetching, picker, zoom, faults."""
    ready = SHOWN + " && " + IDLE + " && !document.getElementById('chart-wrap').hidden"
    b.eval("localStorage.clear()")
    data.requests.clear()
    report(b.open(link, ready=ready), 'history: day chart shown')
    dom = b.dom()
    check('history, day', dom, [
        'aria-pressed="true">Day<', 'class="line s-pv"', 'class="line s-load"', 'class="line s-soc"',
        # The owner's chart channel (plant chartChannels) is the default selection: the weather sensor's temperature.
        'class="line c-0"', 'External temper… · °C', 'Self-sufficiency', '>88 %<',
    ], ['data-toggle-energy'])
    names = [n for k, n in fetched(data, 0) if k == 1]
    report(bool(names) and all('.' not in n or n.endswith('.sensor1') for n in names),
           'history, day: plant blocks and only the blocks of the device on the chart', str(names[:5]))
    report(all(k != 3 for k, _ in fetched(data, 0)), 'faults: not fetched before they come into view')
    report(len(names) <= 2 * 49, 'history, day: at most the day and the day before, to swipe back to (%d blocks)' % len(names))
    report(b.eval("(() => { const c = document.getElementById('chart'), n = c.querySelector('.now'); "
                  "return n !== null && Math.abs(n.getAttribute('x1') / c.viewBox.baseVal.width - 0.5) < 0.02; })()") is True,
           'history, day: the first view has now in the middle')
    report(b.eval("document.getElementById('next').disabled && document.getElementById('readout').hidden") is True,
           'history, day: no next day, no readout before a tap')
    report(b.eval(NO_SIDEWAYS_SCROLL) is True, 'history: no sideways scroll at 390 px')

    # Hover shows the values at that time.
    x, y, w = b.center('#chart')
    b.mouse('mouseMoved', x, y)
    report(b.wait("document.getElementById('readout').querySelectorAll('li').length >= 3"), 'history: hover shows values')

    # Earlier days, and back to today.
    label = b.eval("document.getElementById('range-label').textContent")
    b.eval("document.getElementById('prev').click()")
    report(b.wait(IDLE + " && document.getElementById('range-label').textContent !== %s && document.querySelector('#chart path.line.s-pv') !== null" % json.dumps(label)),
           'history: the day before, with its curves')
    report(b.eval("!document.getElementById('next').disabled") is True, 'history: next enabled on an earlier day')
    for _ in range(5):
        b.eval("document.getElementById('prev').click()")
    report(b.wait(IDLE + " && !document.getElementById('history-state').hidden"), 'history: a day without samples says so')
    check('history, empty day', b.dom(), ['No samples recorded on this day.'])
    for _ in range(6):
        b.eval("document.getElementById('next').click()")
    report(b.wait(IDLE + " && document.getElementById('range-label').textContent === %s" % json.dumps(label)), 'history: back to today')

    # Week: curves over 7 days, fetched in few requests.
    n = len(data.requests)
    b.eval("document.querySelector('[data-period=week]').click()")
    report(b.wait(IDLE + " && document.querySelector('#chart path.line.s-pv') !== null"), 'history, week: curves shown')
    report(b.eval("document.getElementById('range-label').textContent").count('–') == 1, 'history, week: range label')
    # The days before were seen already (cached): at most what is new is fetched.
    report(fetches(data, n) <= 3, 'history, week: %d fetch requests' % fetches(data, n))
    report(len([1 for k, _ in fetched(data, n) if k == 1]) <= 7 * 24 * 2 + 4, 'history, week: blocks of the plant and one device only')
    if shots:
        b.screenshot(os.path.join(shots, 'history-week.png'), 'history')

    # Month and year: bars from day items only, never blocks.
    for period, bars in (('month', 30), ('year', 1), ('lifetime', 1)):
        n = len(data.requests)
        b.eval("document.querySelector('[data-period=%s]').click()" % period)
        ok = b.wait(IDLE + " && document.querySelectorAll('#chart path.bar.s-pv').length >= %d" % min(bars, 1))
        report(ok, 'history, %s: solar bars shown' % period)
        report(all(k == 2 for k, _ in fetched(data, n)), 'history, %s: only day items fetched' % period)
        if shots and period != 'lifetime':
            b.screenshot(os.path.join(shots, 'history-%s.png' % period), 'history')
    report(b.eval("document.querySelectorAll('#chart path.bar.s-grid').length") == 0, 'history: export bars hidden at first, as in the app')
    b.eval("document.querySelector('[data-period=month]').click()")
    b.wait(IDLE)
    report(b.eval("document.querySelectorAll('#chart path.bar.s-pv').length >= 4 && document.querySelectorAll('#chart path.bar.s-pv').length === "
                  "document.querySelectorAll('#chart path.bar.s-load').length") is True, 'history, month: solar and consumption bars per day')
    b.eval("document.querySelector('[data-toggle-energy=export]').click()")
    report(b.wait("document.querySelectorAll('#chart path.bar.s-grid').length > 0"), 'history, month: export bars shown after a tap')

    # The choice survives a reload (localStorage, per plant).
    b.open(link, ready=SHOWN + " && " + IDLE)
    b.eval("document.querySelector('[data-period=month]').click()")
    report(b.wait(IDLE + " && document.querySelectorAll('#chart path.bar.s-grid').length > 0"), 'history: the shown bars are remembered')

    # Channel picker: add the inverter's temperature, then too many units, then remove.
    b.eval("document.querySelector('[data-period=day]').click()")
    b.wait(IDLE)
    b.eval("document.getElementById('add-channel').click()")
    report(b.wait("document.getElementById('picker').open"), 'picker: opens')
    report(b.eval("document.querySelector('#picker-devices [aria-pressed=true]').dataset.device") == 'inv1', 'picker: the first device at first')
    b.eval("document.querySelector('[data-device=sensor1]').click()")
    check('picker, sensor', b.dom(), ['>Weather sensor<', '>Device<', 'Solar irradiance (W/m²)', 'Wind speed (m/s)', '0 selected · 2 of 3 free'])
    report(b.eval("document.querySelector('#picker-list input[data-key=\"d:Device:External temperature\"]').checked && "
                  "document.querySelector('#picker-list input[data-key=\"d:Device:External temperature\"]').disabled") is True,
           'picker: a channel on the chart is checked and fixed')
    b.eval("document.querySelector('[data-device=inv1]').click()")
    check('picker, inverter', b.dom(), ['>Live values<', '>Battery<', 'Temperature (°C)', 'Solar power (kW)', '>Cycles<'])
    if shots:
        b.screenshot(os.path.join(shots, 'picker.png'))
    # Hz (meter) and m/s (sensor) with the °C on the chart: five units, one too many.
    b.eval("document.querySelector('[data-device=meter1]').click()")
    b.eval("document.querySelector('#picker-list input[data-key=\"r:L1_frequency\"]').click()")
    b.eval("document.querySelector('[data-device=sensor1]').click()")
    b.eval("document.querySelector('#picker-list input[data-key=\"d:Device:Wind speed\"]').click()")
    check('picker, two chosen', b.dom(), ['2 selected · 2 of 3 free'])
    report(b.eval("document.querySelectorAll('#picker-list input:disabled').length") >= 3, 'picker: nothing more once the free slots are taken')
    b.eval("document.getElementById('picker-add').click()")
    check('picker, too many units', b.dom(), ['The chart shows at most 4 different units at once (kW, %, °C, Hz, m/s)'])
    report(b.eval("document.getElementById('picker').open") is True, 'picker: stays open when refused')
    b.eval("document.getElementById('picker-cancel').click()")
    b.eval("document.getElementById('add-channel').click()")
    b.eval("document.querySelector('[data-device=inv1]').click()")
    b.eval("const s = document.getElementById('picker-search'); s.value = 'temp'; s.dispatchEvent(new Event('input'))")
    report(b.eval("[...document.querySelectorAll('#picker-list input')].map(x => x.dataset.key).join()") == 'r:temperature,d:Battery:BMS temperature',
           'picker: search', b.eval("[...document.querySelectorAll('#picker-list input')].map(x => x.dataset.key).join()"))
    b.eval("document.querySelector('#picker-list input[data-key=\"r:temperature\"]').click()")
    check('picker, one chosen', b.dom(), ['1 selected · 2 of 3 free'])
    n = len(data.requests)
    b.eval("document.getElementById('picker-add').click()")
    report(b.wait(IDLE + " && !document.getElementById('picker').open && document.querySelector('#chart path.line.c-1') !== null"),
           'picker: the added channel is drawn')
    report(any(nm.endswith('.inv1') for k, nm in fetched(data, n)), "picker: the device's blocks are fetched when needed")
    check('picker, added', b.dom(), ['Temperature · °C'])
    b.eval("document.getElementById('add-channel').click()")
    b.eval("document.getElementById('picker-cancel').click()")
    report(b.eval("document.getElementById('picker').open") is False, 'picker: cancel closes it')
    opened = len(data.requests)
    b.open(link, ready=ready)
    report(b.wait("document.querySelector('#chart path.line.c-1') !== null"), 'picker: added channels are remembered')
    b.eval("document.querySelector('[data-remove=\"inv1|r:temperature\"]').click()")
    report(b.wait("document.querySelector('#chart path.line.c-1') === null && !document.body.textContent.includes('Temperature · °C')"),
           'picker: a channel can be removed')
    b.eval("document.querySelector('[data-toggle=\"plant:pv\"]').click()")
    report(b.wait("document.querySelector('#chart path.line.s-pv') === null"), 'history: a curve can be hidden')
    b.eval("document.querySelector('[data-toggle=\"plant:pv\"]').click()")

    # Zoom: wheel, pinch, drag, reset.
    first = b.eval("[...document.querySelectorAll('#chart .axis text')].map(t => t.textContent).join('|')")
    x, y, w = b.center('#chart')
    b.mouse('mouseWheel', x, y, deltaX=0, deltaY=-600)
    report(b.wait("!document.getElementById('zoom-reset').hidden"), 'zoom: the wheel zooms in')
    zoomed = b.eval("[...document.querySelectorAll('#chart .axis text')].map(t => t.textContent).join('|')")
    report(zoomed != first, 'zoom: finer time labels')
    b.mouse('mousePressed', x, y, button='left', clickCount=1)
    b.mouse('mouseMoved', x + 80, y, button='left', buttons=1)
    b.mouse('mouseReleased', x + 80, y, button='left', clickCount=1)
    report(b.wait("[...document.querySelectorAll('#chart .axis text')].map(t => t.textContent).join('|') !== %s" % json.dumps(zoomed)),
           'zoom: a drag pans')
    if shots:
        b.screenshot(os.path.join(shots, 'history-day-zoomed.png'), 'history')
    b.eval("document.getElementById('zoom-reset').click()")
    report(b.wait("document.getElementById('zoom-reset').hidden"), 'zoom: reset')
    x, y, w = b.center('#chart')

    # A sideways drag moves the curves on into the day before; let go past the middle and that day is shown.
    label = b.eval("document.getElementById('range-label').textContent")
    n0 = len(data.requests)
    b.mouse('mousePressed', x - w * 0.3, y, button='left', clickCount=1)
    b.mouse('mouseMoved', x, y, button='left', buttons=1)
    b.mouse('mouseMoved', x + w * 0.3, y, button='left', buttons=1)
    report(b.wait("document.getElementById('range-label').textContent !== %s" % json.dumps(label)), 'swipe: the date follows the finger')
    b.mouse('mouseReleased', x + w * 0.3, y, button='left', clickCount=1)
    report(b.wait(IDLE + " && !document.getElementById('next').disabled && document.getElementById('history-state').hidden && "
                  "document.querySelector('#chart path.line.s-pv') !== null"), 'swipe: the day before is shown')
    times = "[...document.querySelectorAll('#chart .axis text')].map(t => t.textContent + '@' + t.getAttribute('x')).join('|')"
    left = b.eval(times)
    time.sleep(0.5)
    report(b.eval(times) == left and b.eval("document.getElementById('zoom-reset').hidden") is True, 'swipe: the window stays where it was let go')
    report(fetches(data, n0) <= 3, 'swipe: %d fetch requests' % fetches(data, n0))
    b.mouse('mousePressed', x + w * 0.3, y, button='left', clickCount=1)
    b.mouse('mouseMoved', x, y, button='left', buttons=1)
    b.mouse('mouseMoved', x - w * 0.3, y, button='left', buttons=1)
    b.mouse('mouseReleased', x - w * 0.3, y, button='left', clickCount=1)
    report(b.wait(IDLE + " && document.getElementById('range-label').textContent === %s" % json.dumps(label)), 'swipe: and back to today')
    b.mouse('mousePressed', x, y, button='left', clickCount=1)
    b.mouse('mouseMoved', x - w * 0.3, y, button='left', buttons=1)
    b.mouse('mouseReleased', x - w * 0.3, y, button='left', clickCount=1)
    report(b.eval("document.getElementById('range-label').textContent") == label, 'swipe: not past today')
    b.touch('touchStart', [(x - 40, y), (x + 40, y)])
    b.touch('touchMove', [(x - 90, y), (x + 90, y)])
    b.touch('touchMove', [(x - 130, y), (x + 130, y)])
    b.touch('touchEnd', [])
    report(b.wait("!document.getElementById('zoom-reset').hidden"), 'zoom: two fingers pinch in')
    b.mouse('mousePressed', x, y, button='left', clickCount=2)
    b.mouse('mouseReleased', x, y, button='left', clickCount=2)
    report(b.wait("document.getElementById('zoom-reset').hidden"), 'zoom: a double-click resets')

    # The fault log: fetched once in view; active and past entries, details without addresses.
    b.eval("document.getElementById('alarms').scrollIntoView()")
    report(b.wait("document.getElementById('alarms').getAttribute('aria-busy') === 'false'"), 'faults: loaded once in view')
    report(sum(1 for op, pairs in data.requests[opened:] if op == 'fetch' and any(k == 3 for k, _ in pairs)) == 1 and sum(1 for k, _ in fetched(data, n) if k == 3) == 14, 'faults: all 14 entries in one request')
    dom = b.dom()
    check('faults', dom, [
        'Battery temperature high', 'still going on', 'No connection · Grid meter', 'Fault · Inverter', 'Isolation fault',
        'SOMETHING_NEW · Weather sensor', 'Asleep · Inverter', 'No internet', '3 d 4 h', '45 min', '2 h 5 min',
    ])
    report(b.eval("document.querySelectorAll('#alarms-active li.event').length") == 1, 'faults: one still going on')
    report(b.eval("document.querySelectorAll('#alarms-past li.event').length") == 13, 'faults: thirteen past ones')
    report(b.eval("[...document.querySelectorAll('#alarms-past .when')].map(e => e.textContent).join('|').startsWith(document.querySelector('#alarms-past .when').textContent)") is True,
           'faults: newest first')
    b.eval("document.querySelector('#alarms-past li.sev-alarm [data-event=\"7\"]').click()")
    dom = b.dom()
    check('faults, details', dom, ['At the start: Reported by the device', 'Isolation resistance', '0.2 MΩ', '41.25 °C',
                                   'GoodWe ET hybrid', 'no answer (TIMEOUT)', 'aria-expanded="true"'],
          ['192.168.1.20', 'GW-SECRET-123', '192.168.1.0/24'])
    if shots:
        b.screenshot(os.path.join(shots, 'faults.png'), 'alarms')
    b.eval("document.querySelector('[data-event=\"1\"]').click()")
    check('faults, end', b.dom(), ['At the end: Error', 'Read timed out after 5000 ms'])
    report(b.eval(NO_SIDEWAYS_SCROLL) is True, 'faults: no sideways scroll with details open')

    # Narrow phone with the picker open, and German.
    b.open(link, size=(320, 640), ready=ready)
    report(b.eval(NO_SIDEWAYS_SCROLL) is True, 'history: no sideways scroll at 320 px')
    b.eval("document.getElementById('add-channel').click()")
    b.eval("document.querySelector('[data-device=inv1]').click()")
    report(b.eval("document.getElementById('picker').getBoundingClientRect().right <= window.innerWidth && " + NO_SIDEWAYS_SCROLL) is True,
           'picker: fits at 320 px')
    if shots:
        b.screenshot(os.path.join(shots, 'picker-320.png'))
    b.open(link, lang='de-CH', ready=ready)
    b.eval("document.getElementById('alarms').scrollIntoView()")
    b.wait("document.getElementById('alarms').getAttribute('aria-busy') === 'false'")
    check('history, German', b.dom(), ['>Verlauf<', '>Woche<', '>Gesamt<', '>Störungen<', 'Autarkie', 'Kanal hinzufügen…',
                                       'dauert noch an', 'Kein Netzstrom', 'Ladestand · %'])
    b.eval("localStorage.clear()")

    # A touch screen: the cursor is fixed in the middle with the values there, and the curves move under it.
    b.open(link, ready=ready, touch=True)
    report(b.eval("matchMedia('(pointer: coarse)').matches") is True, 'touch: the page sees a touch screen')
    middle = ("(() => { const c = document.getElementById('chart'), l = c.querySelector('.cursor'); "
              "return l !== null && Math.abs(l.getAttribute('x1') / c.viewBox.baseVal.width - 0.5) < 0.01; })()")
    report(b.wait(middle + " && document.querySelectorAll('#readout li').length >= 3"), 'touch: on first load the cursor is in the middle, with the latest values')
    title = b.eval("document.querySelector('#readout strong').textContent")
    x, y, w = b.center('#chart')
    b.touch('touchStart', [(x - 60, y)])
    b.touch('touchMove', [(x - 20, y)])
    b.touch('touchMove', [(x + 60, y)])
    report(b.wait(middle + " && document.querySelector('#readout strong').textContent !== %s" % json.dumps(title)),
           'touch: a swipe moves the curves under the cursor, its time follows')
    b.touch('touchEnd', [])
    b.touch('touchStart', [(x - 100, y)])
    b.touch('touchEnd', [])
    time.sleep(0.3)
    report(b.eval(middle) is True, 'touch: a tap leaves the cursor in the middle')
    if shots:
        b.screenshot(os.path.join(shots, 'history-touch.png'), 'history')


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
            history_cases(b, link, data, a.screenshots)
            if a.screenshots:
                os.makedirs(a.screenshots, exist_ok=True)
                shots = (('phone', link, (390, 844), False, 'en-US'), ('phone-dark', link, (390, 844), True, 'en-US'),
                         ('phone-de', link, (390, 844), False, 'de-CH'), ('tablet', link, (1024, 768), False, 'en-US'),
                         ('tablet-dark', link, (1024, 768), True, 'de-CH'),
                         ('error', fake_store.link(port, token='AAAAAAAAAAAAAAAAAAAAAA', data=data), (390, 844), False, 'en-US'))
                for name, url, size, dark, lang in shots:
                    b.open(url, size=size, dark=dark, lang=lang, ready=SHOWN, touch=name.startswith('phone'))
                    # The fault log loads when it comes into view.
                    b.eval("document.getElementById('alarms').scrollIntoView()")
                    b.wait("document.getElementById('alarms').getAttribute('aria-busy') !== 'true'", 5)
                    b.eval("window.scrollTo(0, 0)")
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
