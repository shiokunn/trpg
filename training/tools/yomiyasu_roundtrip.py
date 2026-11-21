#!/usr/bin/env python3
"""Prepare yomiyasu input from raw records and apply revised text back to copies.

Only the natural-language fields listed in check_protected_text.ALLOWED_FIELDS
are extracted/written. The original raw files are never modified.

Usage:
  # 1. extract selected fields to a numbered Markdown file
  python3 training/tools/yomiyasu_roundtrip.py prepare \
      --ids gm-f-0001 gm-f-0002 --out training/experiments/x/source.md

  # 2. an agent rewrites source.md into final.md following yomiyasu SKILL.md

  # 3. write revised text back into copied records, then run the guards
  python3 training/tools/yomiyasu_roundtrip.py apply \
      --source source.md --final final.md \
      --before trial_before.jsonl --after trial_after.jsonl
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(HERE))
from check_protected_text import ALLOWED_FIELDS, field_name  # noqa: E402


def load_raw() -> dict[str, dict]:
    records: dict[str, dict] = {}
    for path in sorted((ROOT / "raw").rglob("*.jsonl")):
        for line in path.open(encoding="utf-8"):
            if line.strip():
                rec = json.loads(line)
                records[rec["id"]] = rec
    return records


def allowed_pairs(rec: dict) -> list[tuple[str, str]]:
    def walk(obj, prefix=""):
        if isinstance(obj, dict):
            for k, v in obj.items():
                yield from walk(v, f"{prefix}.{k}" if prefix else str(k))
        elif isinstance(obj, list):
            for i, v in enumerate(obj):
                yield from walk(v, f"{prefix}[{i}]")
        else:
            yield prefix, obj
    return [
        (path, text) for path, text in walk(rec)
        if field_name(path) in ALLOWED_FIELDS and isinstance(text, str) and text.strip()
    ]


def set_field(rec: dict, path: str, value: str) -> None:
    parts = re.findall(r"[^.\[\]]+|\[\d+\]", path)
    cur = rec
    for part in parts[:-1]:
        if part.startswith("["):
            cur = cur[int(part[1:-1])]
        else:
            cur = cur[part]
    last = parts[-1]
    if last.startswith("["):
        cur[int(last[1:-1])] = value
    else:
        cur[last] = value


def parse_markdown(path: Path) -> list[dict]:
    out: list[dict] = []
    current = None
    for line in path.open(encoding="utf-8"):
        line = line.strip()
        if line.startswith("## "):
            if current and current.get("text"):
                out.append(current)
            m = re.match(r"## (\d+) (\S+) / (.+)", line)
            if m:
                current = {"no": int(m.group(1)), "id": m.group(2), "field": m.group(3), "text": ""}
        elif line and current is not None:
            current["text"] = (current["text"] + "\n" + line).strip()
    if current and current.get("text"):
        out.append(current)
    return out


def prepare(args) -> int:
    raw = load_raw()
    entries = []
    for rid in args.ids:
        if rid not in raw:
            print(f"id が見つかりません: {rid}", file=sys.stderr)
            return 1
        pairs = allowed_pairs(raw[rid])
        if args.field:
            pairs = [p for p in pairs if field_name(p[0]) == args.field]
        for path, text in pairs:
            entries.append({"id": rid, "path": path, "text": text})
    out = args.out
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8") as fh:
        fh.write("# yomiyasu 推敲対象（自然言語フィールドのみ）\n\n")
        for i, e in enumerate(entries, 1):
            fh.write(f"## {i:02d} {e['id']} / {e['path']}\n\n{e['text']}\n\n")
    Path(str(out) + ".json").write_text(json.dumps(entries, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {len(entries)} fields to {out}")
    return 0


def apply_revisions(args) -> int:
    source = parse_markdown(args.source)
    final = parse_markdown(args.final)
    if len(source) != len(final):
        print(f"件数が異なる: source={len(source)} final={len(final)}", file=sys.stderr)
        return 1
    before = [json.loads(l) for l in args.before.open(encoding="utf-8") if l.strip()]
    by_id = {r["id"]: json.loads(json.dumps(r)) for r in before}
    for s, f in zip(source, final):
        if (s["id"], s["field"]) != (f["id"], f["field"]):
            print(f"見出しが不一致: {s} / {f}", file=sys.stderr)
            return 1
        rec = by_id.get(s["id"])
        if rec is None:
            print(f"id が見つかりません: {s['id']}", file=sys.stderr)
            return 1
        if field_name(s["field"]) not in ALLOWED_FIELDS:
            print(f"保護対象のフィールドです: {s['field']}", file=sys.stderr)
            return 1
        set_field(rec, s["field"], f["text"])
    with args.after.open("w", encoding="utf-8") as fh:
        for rec in by_id.values():
            fh.write(json.dumps(rec, ensure_ascii=False) + "\n")
    print(f"wrote {len(by_id)} records to {args.after}")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    sub = ap.add_subparsers(dest="command", required=True)
    p = sub.add_parser("prepare")
    p.add_argument("--ids", nargs="+", required=True)
    p.add_argument("--field", default="")
    p.add_argument("--out", type=Path, required=True)
    p.set_defaults(func=prepare)
    a = sub.add_parser("apply")
    a.add_argument("--source", type=Path, required=True)
    a.add_argument("--final", type=Path, required=True)
    a.add_argument("--before", type=Path, required=True)
    a.add_argument("--after", type=Path, required=True)
    a.set_defaults(func=apply_revisions)
    args = ap.parse_args()
    return int(args.func(args))


if __name__ == "__main__":
    sys.exit(main())
