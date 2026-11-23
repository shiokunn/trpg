#!/usr/bin/env python3
"""Fix two generation defects in training/raw/trpg_gm/*.jsonl.

Defect 1: two different scenarios shared a `group` name across batch B and C
         (scenario-ember-post, scenario-glass-orchard). Groups drive split
         grouping, so a collision merges unrelated scenarios. Renamed in C.

Defect 2: a continuing turn used a free-text `action.text` while carrying a
         resolved `check`. In the app, free text goes through
         ViewModel.act(text) -> PlayerTurnAction(text) with no CheckRequest,
         so the model must never see a check result attached to free text.
         Fix: make the action available as a choice of the previous turn with
         the same check request, so the sample is reachable at inference time.

Idempotent: running twice changes nothing.

Usage: python3 training/tools/fix_raw_consistency.py [--dry-run]
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import tempfile
from collections import defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
RAW = ROOT / "raw" / "trpg_gm"

GROUP_RENAMES = {
    # (file, old) -> new
    ("gm_c_01.jsonl", "scenario-ember-post"): "scenario-ash-post-office",
    ("gm_c_02.jsonl", "scenario-ember-post"): "scenario-ash-post-office",
    ("gm_c_03.jsonl", "scenario-ember-post"): "scenario-ash-post-office",
    ("gm_c_01.jsonl", "scenario-glass-orchard"): "scenario-isle-greenhouse",
    ("gm_c_02.jsonl", "scenario-glass-orchard"): "scenario-isle-greenhouse",
    ("gm_c_03.jsonl", "scenario-glass-orchard"): "scenario-isle-greenhouse",
}


def load():
    files = {}
    for path in sorted(RAW.glob("*.jsonl")):
        files[path.name] = [json.loads(line) for line in path.open(encoding="utf-8") if line.strip()]
    return files


def dump(path: Path, records) -> None:
    fd, tmp = tempfile.mkstemp(dir=str(path.parent), suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            for rec in records:
                fh.write(json.dumps(rec, ensure_ascii=False) + "\n")
        os.replace(tmp, path)
    except BaseException:
        if os.path.exists(tmp):
            os.unlink(tmp)
        raise


def choice_check(check: dict) -> dict:
    out = {"attribute": check["attribute"], "difficulty": check["difficulty"]}
    if check.get("rollMode"):
        out["rollMode"] = check["rollMode"]
    if check.get("skillId"):
        out["skillId"] = check["skillId"]
    return out


def similarity(a: str, b: str) -> float:
    sa, sb = set(a), set(b)
    if not sa or not sb:
        return 0.0
    return len(sa & sb) / len(sa | sb)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    files = load()
    changed = defaultdict(bool)
    log = []

    # --- defect 1: group collisions -------------------------------------
    for (fname, old), new in GROUP_RENAMES.items():
        for rec in files.get(fname, []):
            if rec["group"] == old:
                rec["group"] = new
                changed[fname] = True
                log.append(f"rename {rec['id']} {old} -> {new}")

    # --- defect 2: free text with a resolved check -----------------------
    by_group = defaultdict(list)
    for fname, recs in files.items():
        for rec in recs:
            by_group[rec["group"]].append((fname, rec))

    # index every record's narrative -> (fname, rec) inside its group
    fixed = 0
    for fname, recs in list(files.items()):
        for rec in recs:
            check = rec.get("action", {}).get("check")
            history = rec.get("history") or []
            if not check or not history:
                continue
            prev_narrative = history[-1]["narrative"]
            candidates = [r for _, r in by_group[rec["group"]] if r["output"]["narrative"] == prev_narrative]
            if not candidates:
                log.append(f"SKIP {rec['id']}: previous turn not found")
                continue
            prev = candidates[0]
            choices = prev["output"]["choices"]
            if rec["action"]["text"] in [c["text"] for c in choices]:
                continue  # already reachable

            wanted = rec["action"]["text"]
            new_choice = {"id": "", "text": wanted, "check": choice_check(check)}

            if len(choices) < 4:
                new_choice["id"] = f"c{len(choices) + 1}"
                choices.append(new_choice)
                action = "append"
            else:
                # Replace the most similar choice so the option list stays coherent.
                idx = max(range(len(choices)), key=lambda i: similarity(choices[i]["text"], wanted))
                new_choice["id"] = choices[idx]["id"]
                log.append(f"replace {prev['id']} choice {choices[idx]['text']} -> {wanted}")
                choices[idx] = new_choice
                action = "replace"

            changed[fname_of(prev, files)] = True
            fixed += 1
            log.append(f"fix {rec['id']} ({action}): {wanted}")

    print(f"records fixed: {fixed}")
    for line in log:
        print(" ", line)

    if args.dry_run:
        print("dry-run: nothing written")
        return 0

    for fname, dirty in changed.items():
        if dirty:
            dump(RAW / fname, files[fname])
            print(f"wrote {fname}")
    return 0


def fname_of(rec: dict, files: dict) -> str:
    for fname, recs in files.items():
        if any(r is rec for r in recs):
            return fname
    raise KeyError(rec.get("id"))


if __name__ == "__main__":
    sys.exit(main())
