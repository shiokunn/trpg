#!/usr/bin/env python3
"""Create a derived, ID-normalised audit root from an immutable snapshot.

The existing classify_qa.py only accepts legacy ID forms (gm-a-0001 /
world-0001 / ex-0001). Generated batches use wave prefixes (bgm01-..., etc.).
This script writes a NEW derived copy under .bulk_gen/audit_runs/ with legacy
IDs, leaving the original snapshot and inbox untouched. Every original ID is
recorded in id_map.json so the mapping is fully traceable.

All downstream tools (quality_system, semantic engine, classify_qa) run on this
derived root, so Python hashes, semantic hashes and raw hashes are consistent.

Usage:
  python3 audit_make_derived.py --snapshot snap-0003-... --run-dir ../audit_runs/wave1
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import bulk_common as bc  # noqa: E402

PREFIX = {"trpg_gm": "gm-a-", "world_json": "world-", "extraction": "ex-"}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--snapshot", required=True)
    ap.add_argument("--run-dir", type=Path, required=True)
    args = ap.parse_args()

    snap = bc.SNAPSHOTS / args.snapshot
    if not snap.is_dir() or not (snap / "MANIFEST.json").exists():
        print(f"snapshot not found: {args.snapshot}", file=sys.stderr)
        return 2
    run = Path(args.run_dir)
    raw_out = run / "raw"
    if (run / "id_map.json").exists():
        print(f"derived root already exists: {run}", file=sys.stderr)
        return 2
    raw_out.mkdir(parents=True, exist_ok=True)

    counters = {"trpg_gm": 0, "world_json": 0, "extraction": 0}
    id_map: dict[str, str] = {}
    files = []
    for src in sorted((snap / "raw").rglob("*.jsonl")):
        category = src.parent.name
        if category not in PREFIX:
            continue
        records = []
        with src.open(encoding="utf-8") as fh:
            for line in fh:
                if not line.strip():
                    continue
                rec = json.loads(line)
                old = rec["id"]
                if old in id_map:
                    print(f"duplicate original id {old}", file=sys.stderr)
                    return 2
                counters[category] += 1
                new = f"{PREFIX[category]}{counters[category]:04d}"
                id_map[old] = new
                rec["id"] = new
                records.append(rec)
        dst = raw_out / category / src.name
        dst.parent.mkdir(parents=True, exist_ok=True)
        with dst.open("w", encoding="utf-8") as fh:
            for rec in records:
                fh.write(json.dumps(rec, ensure_ascii=False) + "\n")
        files.append({
            "source": str(src.relative_to(bc.BASE)),
            "derived": str(dst.relative_to(run)),
            "records": len(records),
            "sha256": bc.sha256_file(dst),
        })

    manifest = bc.load_manifest(snap)
    derived = {
        "run_dir": str(run),
        "source_snapshot": snap.name,
        "snapshot_manifest": {
            "total_records": manifest.get("total_records"),
            "by_category": manifest.get("by_category"),
            "created_at": manifest.get("created_at"),
        },
        "purpose": "ID正規化した派生監査ルート。元データ・スナップショットは不変。",
        "notation": "classify_qa.py の ID_RE に適合させるための外側IDの変換のみ。内容・根拠・判定は変更しない。",
        "counters": counters,
        "total_derived_records": sum(counters.values()),
        "id_map": id_map,
        "files": files,
        "derived_at": bc.now(),
    }
    bc.write_json(run / "id_map.json", derived)
    print(json.dumps({
        "run_dir": str(run), "source_snapshot": snap.name,
        "total_derived_records": derived["total_derived_records"],
        "counters": counters,
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
