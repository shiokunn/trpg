#!/usr/bin/env python3
"""Apply the specialist review (S04) fixes to hand-authored extraction records.

Idempotent: each fix checks the current value before applying. Only files in
training/raw/extraction/ are touched.

Rationale is recorded per id in FIXES below: remove fabricated details, undo
inferred state changes, keep uncertain information marked uncertain, and do not
treat observable facts as GM-only secrets.
"""

from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DIR = ROOT / "raw" / "extraction"


def fix_0004(o):
    for m in o["memories"]:
        if m.get("category") == "NPC_RELATION":
            m["content"] = "ミラは灯台守の娘だったと打ち明けた。"


def fix_0005(o):
    for it in o["items"]:
        if it["name"] == "星紋の短剣":
            it["power"] = 0
        if it["name"] == "乾いたインク壺":
            it["description"] = "中身が乾いたインク壺。"


def clear_secrets(o):
    for loc in o["locations"]:
        if loc.get("secrets"):
            loc["secrets"] = []


def fix_0010(o):
    o["stateChanges"] = [c for c in o["stateChanges"] if c["type"] != "NPC_RELATION_DELTA"]


def fix_0014(o):
    o["npcs"] = []


def fix_0047(o):
    o["npcMemories"] = []


def fix_0057(o):
    for it in o["items"]:
        if it["name"] in ("鉄の小盾", "皮の腕当て"):
            it["defense"] = 0


def fix_0061(o):
    o["npcMemories"] = []


def fix_0072(o):
    o["npcMemories"] = []


def fix_0077(o):
    for it in o["items"]:
        if it["name"] == "反射の外套":
            it["description"] = "反射の外套。二つある。"
            it["defense"] = 0


def fix_0007(o):
    o["npcs"][0]["disposition"] = ""


def fix_0026(o):
    n = o["npcs"][0]
    n["personality"] = ""
    n["disposition"] = ""


def fix_0029(o):
    for m in o["npcMemories"]:
        m["content"] = m["content"].replace("地下の倉の鍵", "鍵")


def fix_0064(o):
    o["npcs"][0]["secrets"] = []


def normalize_type(o):
    if "items" in o:
        for it in o["items"]:
            if it.get("type") in ("magic", "material"):
                it["type"] = "misc"


FIXES = {
    "ex-0004": fix_0004,
    "ex-0005": fix_0005,
    "ex-0006": clear_secrets,
    "ex-0007": fix_0007,
    "ex-0010": fix_0010,
    "ex-0013": clear_secrets,
    "ex-0014": fix_0014,
    "ex-0025": clear_secrets,
    "ex-0026": fix_0026,
    "ex-0029": fix_0029,
    "ex-0047": fix_0047,
    "ex-0049": clear_secrets,
    "ex-0057": fix_0057,
    "ex-0061": fix_0061,
    "ex-0064": fix_0064,
    "ex-0072": fix_0072,
    "ex-0077": fix_0077,
}


def main() -> int:
    changed = []
    for path in sorted(DIR.glob("*.jsonl")):
        records = [json.loads(line) for line in path.open(encoding="utf-8") if line.strip()]
        dirty = False
        for rec in records:
            before = json.dumps(rec["output"], ensure_ascii=False, sort_keys=True)
            fn = FIXES.get(rec["id"])
            if fn:
                fn(rec["output"])
            normalize_type(rec["output"])
            after = json.dumps(rec["output"], ensure_ascii=False, sort_keys=True)
            if before != after:
                changed.append(rec["id"])
                dirty = True
        if dirty:
            with path.open("w", encoding="utf-8") as fh:
                for rec in records:
                    fh.write(json.dumps(rec, ensure_ascii=False) + "\n")
    print(f"fixed {len(changed)} records: {', '.join(changed)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
