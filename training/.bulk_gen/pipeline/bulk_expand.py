#!/usr/bin/env python3
"""Expand compact hand-authored TRPG GM seeds into raw records in the inbox.

The compact seed keeps the world/character/NPC context once and a list of 2-3
turns. This script fills the repeated envelope fields (ids, history chain,
defaults) and validates every derived record with the existing schema_lib.
Outputs go ONLY to training/.bulk_gen/inbox/trpg_gm/.

Usage:
  python3 pipeline/bulk_expand.py --seeds ../inbox/seeds/trpg_gm/b01.seed.jsonl \
      --prefix bgm01- --out bgm01.jsonl
  python3 pipeline/bulk_expand.py ... --dry-run
"""

from __future__ import annotations

import argparse
import copy
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent.parent / "tools"))

import bulk_common as bc  # noqa: E402
import schema_lib  # noqa: E402


def build_context(seed: dict, turn_no: int) -> dict:
    ctx = copy.deepcopy(seed["context"])
    w = ctx.setdefault("world", {})
    # Defaults for keys the compact seed may omit. Hand-written prose is kept.
    for key, default in (
        ("genre", "ファンタジー"), ("era", ""), ("setting", ""), ("atmosphere", ""),
        ("history", ""), ("geography", ""), ("socialStructure", ""), ("techMagic", ""),
        ("objective", ""), ("problem", ""), ("scenarioPremise", ""),
    ):
        w.setdefault(key, default)
    for key in ("factions", "specialSettings", "secrets"):
        w.setdefault(key, [])
    if not w.get("scenarioPremise") and w.get("problem"):
        w["scenarioPremise"] = w["problem"]

    ch = ctx.setdefault("character", {})
    ch.setdefault("name", "冒険者")
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
        for obj in q["objectives"]:
            if isinstance(obj, dict):
                obj.setdefault("completed", False)

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


def expand_seed(seed: dict, prefix: str, start: int) -> list[tuple[str, dict, list[str]]]:
    """Returns [(id, record, errors)]. Nothing is written here."""
    out = []
    group = seed["group"]
    history = []
    for i, turn in enumerate(seed["turns"]):
        rid = f"{prefix}{start + i:04d}"
        ctx = build_context(seed, i + 1)
        output = {
            "narrative": turn["narrative"],
            "choices": copy.deepcopy(turn["choices"]),
        }
        for key in ("sceneTitle", "eventSummary", "stateChanges", "memories", "npcMemories"):
            if key in turn:
                output[key] = copy.deepcopy(turn[key])
        rec = {
            "id": rid,
            "category": "trpg_gm",
            "task": "gm_turn",
            "group": group,
            "notes": seed.get("notes", ""),
            "context": ctx,
            "action": copy.deepcopy(turn.get("action", {"text": ""})),
            "history": copy.deepcopy(history),
            "output": output,
        }
        rep = schema_lib.validate_gm(output, ctx)
        out.append((rid, rec, rep.errors + rep.warnings[:0]))
        history.append({
            "action": copy.deepcopy(rec["action"]),
            "narrative": turn["narrative"],
        })
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--seeds", type=Path, required=True)
    ap.add_argument("--prefix", required=True, help="e.g. bgm01- (unique per agent)")
    ap.add_argument("--out", required=True, help="batch filename written under inbox/trpg_gm/")
    ap.add_argument("--start", type=int, default=1)
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    dest_dir = bc.INBOX / "trpg_gm"
    dest = dest_dir / args.out
    if dest.exists() and not args.dry_run:
        print(f"ERROR: output already exists: {dest}", file=sys.stderr)
        return 2

    records: list[dict] = []
    problems: list[str] = []
    seen_ids: set[str] = set()
    next_start = args.start
    for lineno, line in enumerate(args.seeds.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            seed = json.loads(line)
        except json.JSONDecodeError as exc:
            problems.append(f"seed line {lineno}: JSON error: {exc.msg}")
            continue
        if "group" not in seed or "context" not in seed or "turns" not in seed:
            problems.append(f"seed line {lineno}: missing group/context/turns")
            continue
        for rid, rec, errs in expand_seed(seed, args.prefix, next_start):
            next_start += len(seed["turns"])
            if rid in seen_ids:
                problems.append(f"{rid}: id collision")
                continue
            seen_ids.add(rid)
            if errs:
                problems.append(f"{rid}: {errs[:6]}")
                continue
            records.append(rec)

    print(json.dumps({
        "seeds_file": str(args.seeds),
        "records_valid": len(records),
        "problems": problems[:40],
        "problem_count": len(problems),
        "dest": str(dest),
        "dry_run": args.dry_run,
    }, ensure_ascii=False, indent=2))
    if problems:
        return 1
    if not records:
        print("ERROR: no valid records produced", file=sys.stderr)
        return 1
    if args.dry_run:
        return 0

    dest_dir.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_name("." + dest.name + ".tmp")
    with tmp.open("w", encoding="utf-8") as fh:
        for rec in records:
            fh.write(json.dumps(rec, ensure_ascii=False) + "\n")
    tmp.replace(dest)
    print(f"wrote {len(records)} records -> {dest.relative_to(bc.BASE)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
