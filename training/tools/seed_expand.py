#!/usr/bin/env python3
"""Expand compact hand-authored scenario seeds into full training/raw records.

A seed holds the context once and a list of 2-3 hand-written turns. The expander
fills the repeated fields (history chain, ids, envelope) and validates every
derived record with schema_lib before writing. Seeds live in training/seeds/.

Usage:
  python3 training/tools/seed_expand.py --dry-run training/seeds/gm_f.jsonl
  python3 training/tools/seed_expand.py training/seeds/gm_f.jsonl
"""

from __future__ import annotations

import argparse
import copy
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(HERE))

import schema_lib  # noqa: E402


def build_context(seed: dict, turn_no: int) -> dict:
    ctx = copy.deepcopy(seed["context"])
    w = ctx.setdefault("world", {})
    for key, default in (
        ("genre", "ファンタジー"), ("era", ""), ("setting", ""), ("atmosphere", ""),
        ("history", ""), ("geography", ""), ("socialStructure", ""), ("techMagic", ""),
        ("objective", ""), ("problem", ""), ("scenarioPremise", ""),
    ):
        w.setdefault(key, default)
    for key in ("factions", "specialSettings", "secrets"):
        w.setdefault(key, [])

    ch = ctx.setdefault("character", {})
    ch.setdefault("species", "人間")
    ch.setdefault("occupation", "冒険者")
    ch.setdefault("maxHp", ch.get("hp", 10))
    ch.setdefault("hp", ch["maxHp"])
    ch.setdefault("experience", 0)
    ch.setdefault("attributes", {"str": 3, "dex": 3, "int": 3, "wil": 3, "per": 3, "cha": 3})
    ch.setdefault("skills", [])
    ch.setdefault("inventory", [])

    for n in ctx.setdefault("npcs", []):
        n.setdefault("description", "")
        n.setdefault("personality", "")
        n.setdefault("occupation", "")
        n.setdefault("relationship", 0)
        n.setdefault("disposition", "")
        n.setdefault("secrets", [])
        n.setdefault("isAlive", True)
        n.setdefault("isDiscovered", True)
        n.setdefault("combat", None)

    for q in ctx.setdefault("quests", []):
        q.setdefault("description", "")
        q.setdefault("status", "ACTIVE")
        q.setdefault("objectives", [])
        q.setdefault("rewards", [])

    for loc in ctx.setdefault("locations", []):
        loc.setdefault("description", "")
        loc.setdefault("secrets", [])
        loc.setdefault("isDiscovered", True)

    for key, default in (
        ("flags", {}), ("currentLocationId", None), ("memories", []),
        ("npcMemories", []), ("activeCombat", None), ("summary", ""),
    ):
        ctx.setdefault(key, default)
    if not ctx["currentLocationId"] and ctx["locations"]:
        ctx["currentLocationId"] = ctx["locations"][0]["id"]
    ctx["turn"] = turn_no
    return ctx


def expand(seed: dict, prefix: str, dest: Path) -> list[tuple[str, dict, list[str]]]:
    """Returns [(id, record, errors)]. Nothing is written by this function."""
    out = []
    group = seed["group"]
    start = int(seed.get("start", 1))
    turns = seed["turns"]
    history = []
    for i, turn in enumerate(turns):
        rid = f"{prefix}{start + i:04d}"
        rec = {
            "id": rid,
            "category": "trpg_gm",
            "task": "gm_turn",
            "group": group,
            "notes": seed.get("notes", ""),
            "context": build_context(seed, i + 1),
            "action": copy.deepcopy(turn["action"]),
            "history": copy.deepcopy(history),
            "output": {
                "narrative": turn["narrative"],
                "choices": copy.deepcopy(turn["choices"]),
                "stateChanges": copy.deepcopy(turn.get("stateChanges", [])),
                "memories": copy.deepcopy(turn.get("memories", [])),
                "npcMemories": copy.deepcopy(turn.get("npcMemories", [])),
            },
        }
        if "sceneTitle" in turn:
            rec["output"]["sceneTitle"] = turn["sceneTitle"]
        if "eventSummary" in turn:
            rec["output"]["eventSummary"] = turn["eventSummary"]

        rep = schema_lib.validate_gm(rec["output"], rec["context"])
        out.append((rid, rec, rep.errors))

        history.append({
            "action": copy.deepcopy(turn["action"]),
            "narrative": turn["narrative"],
        })
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("seeds", type=Path)
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--prefix", default="gm-f-")
    ap.add_argument("--dest", default="gm_f_01.jsonl")
    args = ap.parse_args()

    dest = ROOT / "raw" / "trpg_gm" / args.dest
    existing = set()
    if dest.exists():
        for line in dest.open(encoding="utf-8"):
            if line.strip():
                existing.add(json.loads(line)["id"])

    records = []
    errors = []
    for n, line in enumerate(args.seeds.open(encoding="utf-8"), 1):
        if not line.strip():
            continue
        seed = json.loads(line)
        for rid, rec, errs in expand(seed, args.prefix, dest):
            if rid in existing:
                continue
            if errs:
                errors.append((rid, errs))
            records.append(rec)

    print(f"seeds expanded: {len(records)} records, {len(errors)} invalid")
    for rid, errs in errors[:20]:
        print(" ", rid, errs)
    if errors:
        return 1
    if args.dry_run:
        print("dry-run: no file written")
        return 0

    with dest.open("a", encoding="utf-8") as fh:
        for rec in records:
            fh.write(json.dumps(rec, ensure_ascii=False) + "\n")
    print(f"appended {len(records)} records to {dest.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
