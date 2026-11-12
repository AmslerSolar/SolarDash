#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Amsler Solar GmbH
"""Checks (default) or updates (--write) the subresource-integrity hashes of app.js and style.css in o/index.html.

Run with --write after every change to app.js or style.css, before uploading; the browser refuses a file whose
hash doesn't match. Each link also carries ?v=<start of the hash>, so a browser that kept an older copy without
asking the server (heuristic caching) fetches the new file instead of refusing the page.
"""

import base64
import hashlib
import os
import re
import sys

O = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'o')
INDEX = os.path.join(O, 'index.html')


def digest(name):
    with open(os.path.join(O, name), 'rb') as f:
        return hashlib.sha384(f.read()).digest()


def fix(html):
    for name in ('app.js', 'style.css'):
        attr = 'src' if name.endswith('.js') else 'href'
        d = digest(name)
        url = '%s?v=%s' % (name, d[:6].hex())
        sri = 'sha384-' + base64.b64encode(d).decode()
        pattern = re.compile(r'%s="%s(?:\?v=[0-9a-f]*)?"([^>]*?integrity=")[^"]*(")' % (attr, re.escape(name)))
        html, n = pattern.subn(lambda m: '%s="%s"%s%s%s' % (attr, url, m.group(1), sri, m.group(2)), html)
        if n != 1:
            sys.exit('index.html: no single integrity attribute for ' + name)
    return html


def main():
    with open(INDEX, encoding='utf-8') as f:
        html = f.read()
    fixed = fix(html)
    if '--write' in sys.argv:
        if fixed != html:
            with open(INDEX, 'w', encoding='utf-8') as f:
                f.write(fixed)
        print('SRI written')
        return 0
    if fixed != html:
        print('FAIL SRI hashes in index.html are out of date: run tools/sri.py --write')
        return 1
    print('PASS SRI hashes in index.html match app.js and style.css')
    return 0


if __name__ == '__main__':
    sys.exit(main())
