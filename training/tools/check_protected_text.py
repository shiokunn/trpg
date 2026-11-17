#!/usr/bin/env python3
"""Independent guard that stays when yomiyasu is adopted.

yomiyasu lint/diff/check do not detect simplified Chinese characters, and their
meaning-risk check is limited to fixed patterns. This script keeps the project's
own red lines:

  scan    --files raw/*.jsonl ...   : find simplified characters in Japanese text
  compare --before a.jsonl --after b.jsonl
                                   : verify that only allowed natural-language
                                     fields changed and that numbers, negation,
                                     conditions, IDs and enums are untouched

Usage:
  python3 training/tools/check_protected_text.py scan --files training/raw/*/*.jsonl
  python3 training/tools/check_protected_text.py compare \
      --before trial_before.jsonl --after trial_after.jsonl
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

# Simplified-Chinese forms that must not appear in Japanese training text.
SIMPLIFIED = {
    "测": "測", "历": "歴", "认": "認", "说": "説", "话": "話", "们": "们",
    "违": "違", "记": "記", "忆": "憶", "报": "報", "查": "査", "储": "儲",
    "误": "誤", "录": "録", "采": "採", "铁": "鉄", "众": "衆", "汉": "漢",
    "！": "!",  # placeholder; removed below (we do not police fullwidth punctuation)
}
SIMPLIFIED.pop("！")

# Only these paths may be rewritten by yomiyasu. Everything else is protected.
ALLOWED_FIELDS = {
    "request",
    "output.narrative",
    "output.sceneTitle",
    "output.eventSummary",
    "output.setting",
    "output.atmosphere",
    "output.history",
    "output.geography",
    "output.socialStructure",
    "output.techMagic",
    "output.objective",
    "output.problem",
    "output.scenario.premise",
    "output.scenario.openingHook",
    "output.opening.npcs.description",
    "output.opening.npcs.personality",
    "output.opening.npcs.disposition",
    "output.opening.quests.title",
    "output.opening.quests.description",
    "output.opening.quests.objectives",
    "output.opening.quests.rewards",
    "output.opening.location.description",
}

NUMBER_RE = re.compile(r"\d+|[一二三四五六七八九十百千万零]+")
NEGATION = ("ない", "ず", "まだ", "らしい", "かもしれない", "ではなく", "未", "禁", "せず")
CONDITION = ("なら", "たら", "れば", "とき", "場合", "まで", "だけ", "必ず", "もし", "いずれ")
ID_RE = re.compile(r"\b(?:gm|world|ex|scenario|c|i|n|q|l|s)-\d+\b|\b(?:n|q|l|i|s)\d+\b")


def flatten(obj, prefix="") -> dict[str, object]:
    out: dict[str, object] = {}
    if isinstance(obj, dict):
        for k, v in obj.items():
            out.update(flatten(v, f"{prefix}.{k}" if prefix else str(k)))
    elif isinstance(obj, list):
        for i, v in enumerate(obj):
            out.update(flatten(v, f"{prefix}[{i}]"))
    else:
        out[prefix] = obj
    return out


def field_name(path: str) -> str:
    """Normalise list indices so allowed fields match regardless of position."""
    return re.sub(r"\[\d+\]", "", path)


def tokens(text: str) -> dict[str, set[str]]:
    return {
        "numbers": set(NUMBER_RE.findall(text)),
        "negation": {w for w in NEGATION if w in text},
        "condition": {w for w in CONDITION if w in text},
        "ids": set(ID_RE.findall(text)),
    }


def scan(args) -> int:
    found = []
    for pattern in args.files:
        for path in sorted(Path().glob(pattern)):
            if not path.is_file():
                continue
            for lineno, line in enumerate(path.open(encoding="utf-8"), 1):
                for ch, replacement in SIMPLIFIED.items():
                    if ch in line:
                        found.append({
                            "file": str(path), "line": lineno,
                            "char": ch, "suggested": replacement,
                        })
    print(json.dumps({"simplified_characters": found, "count": len(found)},
                     ensure_ascii=False, indent=2))
    return 1 if found else 0


def compare(args) -> int:
    before = [json.loads(l) for l in args.before.open(encoding="utf-8") if l.strip()]
    after = [json.loads(l) for l in args.after.open(encoding="utf-8") if l.strip()]
    by_id = {r["id"]: r for r in before}
    errors: list[dict] = []
    changed_fields: list[str] = []
    if len(before) != len(after):
        errors.append({"error": "レコード数が異なる", "before": len(before), "after": len(after)})
    for b in before:
        rid = b["id"]
        if rid not in {r["id"] for r in after}:
            errors.append({"id": rid, "error": "afterに存在しない"})
            continue
        a = next(r for r in after if r["id"] == rid)
        fb, fa = flatten(b), flatten(a)
        for key in set(fb) | set(fa):
            if fb.get(key) == fa.get(key):
                continue
            if field_name(key) not in ALLOWED_FIELDS:
                errors.append({"id": rid, "error": "保護フィールドが変化", "field": key})
                continue
            changed_fields.append(f"{rid}:{key}")
            tb, ta = tokens(str(fb.get(key, ""))), tokens(str(fa.get(key, "")))
            for kind in ("numbers", "negation", "condition", "ids"):
                if tb[kind] != ta[kind]:
                    errors.append({
                        "id": rid, "field": key, "error": f"{kind}が変化",
                        "before": sorted(tb[kind]), "after": sorted(ta[kind]),
                    })
            before_text = str(fb.get(key, ""))
            after_text = str(fa.get(key, ""))
            for ch in SIMPLIFIED:
                if ch in after_text and ch not in before_text:
                    errors.append({"id": rid, "field": key, "error": "簡体字が混入", "char": ch})
    print(json.dumps({
        "records": len(before), "changed_allowed_fields": changed_fields,
        "errors": errors, "error_count": len(errors),
    }, ensure_ascii=False, indent=2))
    return 1 if errors else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    sub = ap.add_subparsers(dest="command", required=True)
    p_scan = sub.add_parser("scan")
    p_scan.add_argument("--files", nargs="+", required=True)
    p_scan.set_defaults(func=scan)
    p_cmp = sub.add_parser("compare")
    p_cmp.add_argument("--before", type=Path, required=True)
    p_cmp.add_argument("--after", type=Path, required=True)
    p_cmp.set_defaults(func=compare)
    args = ap.parse_args()
    return int(args.func(args))


if __name__ == "__main__":
    sys.exit(main())
