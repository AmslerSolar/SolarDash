#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Amsler Solar GmbH
"""Copy ModbusSpec device files into the app, as JSON, at a pinned ModbusSpec commit.

    python3 -I tools/modbusspec_sync.py [--spec ~/dev/ModbusSpec]

Reads the device ids from app/modbusspec.txt, takes devices/**/<file>.yaml with that id from a clean ModbusSpec
checkout, checks each with ModbusSpec's validator, and writes app/src/main/assets/modbusspec/<id>.json plus the
vocabulary and a lock file (commit, date, files). The app build never reads ModbusSpec itself.

The copies only hold what the app reads: write registers (`writable: true`) are left out unless a problem rule, a
derived value, a model match or an identify check uses them, or the list names them for a hook
(`<id> keep <key>,<key>` in app/modbusspec.txt); notes, conflicts and the source details are dropped
(a point keeps `sources` as a short list, which is what tells the app it comes from a project). JSON is compact.
"""
import argparse
import json
import subprocess
import sys
from pathlib import Path

APP = Path(__file__).resolve().parent.parent
OUT = APP / "app/src/main/assets/modbusspec"
LIST = APP / "app/modbusspec.txt"


DROP_POINT = ("note", "conflicts", "writable", "verified_by")


def referenced(d: dict) -> set:
    """Point keys other parts of the file use."""
    keys = {pr["when"]["key"] for pr in d.get("problems", [])}
    for dv in d.get("derived", []):
        keys |= {t["key"] for t in dv.get("sum", []) + dv.get("first", [])}
    return keys


def slim(d: dict, hook_keys: set = frozenset()) -> dict:
    """The device file without what the app never reads (see the module doc); [hook_keys] are kept too."""
    keep = referenced(d) | set(hook_keys)
    points = []
    for p in d["points"]:
        if p.get("writable") and p["key"] not in keep:
            continue
        points.append({k: v for k, v in p.items() if k not in DROP_POINT})
    out = {k: v for k, v in d.items() if k not in ("notes", "points")}
    out["points"] = points
    out["sources"] = [{k: v for k, v in s.items() if k in ("id", "title")} for s in d.get("sources", [])]
    for dv in out.get("derived", []):
        dv.pop("note", None)
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--spec", type=Path, default=Path.home() / "dev/ModbusSpec")
    a = ap.parse_args()
    spec = a.spec.resolve()
    if subprocess.run(["git", "-C", str(spec), "status", "--porcelain", "--", "devices", "vocabulary.yaml"],
                      capture_output=True, text=True, check=True).stdout.strip():
        print(f"{spec}: devices/ or vocabulary.yaml has uncommitted changes; commit them first", file=sys.stderr)
        return 1
    commit = subprocess.run(["git", "-C", str(spec), "rev-parse", "HEAD"], capture_output=True, text=True, check=True).stdout.strip()
    date = subprocess.run(["git", "-C", str(spec), "show", "-s", "--format=%cI", "HEAD"], capture_output=True, text=True, check=True).stdout.strip()
    sys.path.insert(0, str(spec / "tools"))
    import msp  # ModbusSpec's own loader and validator

    by_id = {}
    for f in sorted((spec / "devices").rglob("*.yaml")):
        d = msp.load(f)
        by_id[d["id"]] = (f, d)
    wanted, hook_keys = [], {}
    for line in LIST.read_text(encoding="utf-8").splitlines():
        words = line.split("#")[0].split()
        if not words:
            continue
        wanted.append(words[0])
        if len(words) == 3 and words[1] == "keep":
            hook_keys[words[0]] = set(words[2].split(","))
        elif len(words) != 1:
            print(f"app/modbusspec.txt: can't read {line!r} (expected '<id>' or '<id> keep <key>,<key>')", file=sys.stderr)
            return 1
    OUT.mkdir(parents=True, exist_ok=True)
    for old in OUT.glob("*.json"):
        old.unlink()
    files, problems = [], []
    for wid in wanted:
        if wid not in by_id:
            problems.append(f"{wid}: no device file with this id")
            continue
        f, d = by_id[wid]
        problems += msp.validate(d, str(f.relative_to(spec)))
        (OUT / f"{wid}.json").write_text(json.dumps(slim(d, hook_keys.get(wid, set())), ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8")
        files.append(str(f.relative_to(spec)))
    vocab = msp.load(spec / "vocabulary.yaml")
    (OUT / "vocabulary.json").write_text(json.dumps(vocab, ensure_ascii=False, sort_keys=True, indent=1) + "\n", encoding="utf-8")
    (OUT / "LOCK.json").write_text(json.dumps({"modbusspec_commit": commit, "date": date, "files": files}, indent=1) + "\n", encoding="utf-8")
    for p in problems:
        print(p, file=sys.stderr)
    print(f"{len(files)} device files from ModbusSpec {commit[:10]} ({date})")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
