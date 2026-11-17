#!/usr/bin/env python3
"""Seal completed batches and take immutable snapshots for auditing.

Writing and auditing are strictly separated:

  inbox/<category>/*.jsonl   <- subagents write here (in progress)
        |  seal (validate + marker with sha256)
        v
  inbox/<category>/*.jsonl.done.json   <- completion markers
        |  take (verify hash still matches, copy via staging + rename)
        v
  snapshots/snap-NNNN-<ts>/raw/...     <- immutable audit input
        |  audit (quality_system.py reads ONLY this snapshot)
        v
  audit/<snap>/...

A file whose hash changed after sealing (or that has no marker) is never copied.
The audit never reads the inbox. Nothing under training/raw, processed, splits,
metadata or the final SFT files is read or written by this script.

Usage:
  python3 pipeline/bulk_snapshot.py seal
  python3 pipeline/bulk_snapshot.py take [--note TEXT]
  python3 pipeline/bulk_snapshot.py list
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import bulk_common as bc  # noqa: E402


def cmd_seal(args) -> int:
    sealed, skipped = [], []
    for batch in bc.iter_batch_files():
        marker = bc.marker_path(batch)
        records, problems = bc.batch_problems(batch)
        if problems:
            skipped.append({"file": str(batch.relative_to(bc.BASE)),
                            "errors": problems[:5], "error_count": len(problems)})
            continue
        if marker.exists():
            existing = bc.load_marker(batch)
            if existing is not None:
                skipped.append({"file": str(batch.relative_to(bc.BASE)),
                                "reason": "already sealed"})
                continue
        row = {
            "file": batch.name,
            "category": batch.parent.name,
            "records": len(records),
            "sha256": bc.sha256_file(batch),
            "bytes": batch.stat().st_size,
            "sealed_at": bc.now(),
        }
        bc.write_json(marker, row)
        sealed.append(row)
    report = {
        "sealed": sealed,
        "sealed_batches": len(sealed),
        "sealed_records": sum(r["records"] for r in sealed),
        "skipped": skipped,
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))
    bc.append_jsonl(bc.STATE / "seal_events.jsonl",
                    {"at": bc.now(), "sealed_batches": len(sealed),
                     "sealed_records": report["sealed_records"]})
    return 0


def cmd_take(args) -> int:
    eligible: list[tuple[Path, dict]] = []
    rejected: list[dict] = []
    for batch in bc.iter_batch_files():
        marker = bc.load_marker(batch)
        if marker is None:
            rejected.append({"file": str(batch.relative_to(bc.BASE)),
                             "reason": "no valid marker or hash changed after seal "
                                       "(possible in-progress write)"})
            continue
        eligible.append((batch, marker))

    if not eligible:
        print(json.dumps({"snapshot": None, "reason": "no sealed batches",
                          "rejected": rejected}, ensure_ascii=False, indent=2))
        return 1

    idx = 1
    if bc.SNAPSHOTS.exists():
        existing = [d for d in bc.SNAPSHOTS.iterdir()
                    if d.is_dir() and d.name.startswith("snap-")]
        idx = len(existing) + 1
    snap_id = f"snap-{idx:04d}-{bc.stamp()}"
    final = bc.SNAPSHOTS / snap_id
    staging = bc.STAGING / snap_id
    if final.exists() or staging.exists():
        print(f"ERROR: snapshot path already exists: {snap_id}", file=sys.stderr)
        return 2

    try:
        raw_root = staging / "raw"
        files = []
        for batch, marker in eligible:
            dst = raw_root / batch.parent.name / batch.name
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(batch, dst)
            if bc.sha256_file(dst) != marker["sha256"]:
                raise RuntimeError(f"copy hash mismatch: {batch.name}")
            files.append({
                "file": f"raw/{batch.parent.name}/{batch.name}",
                "category": batch.parent.name,
                "records": marker["records"],
                "sha256": marker["sha256"],
                "bytes": marker["bytes"],
                "sealed_at": marker["sealed_at"],
            })
        manifest = {
            "snapshot_id": snap_id,
            "created_at": bc.now(),
            "note": args.note,
            "source": "training/.bulk_gen/inbox (sealed batches only)",
            "audit_reads": f"snapshots/{snap_id}/raw",
            "files": sorted(files, key=lambda x: x["file"]),
            "total_files": len(files),
            "total_records": sum(f["records"] for f in files),
            "by_category": {
                c: sum(f["records"] for f in files if f["category"] == c)
                for c in bc.CATEGORIES
            },
        }
        bc.write_json(staging / "MANIFEST.json", manifest)
        bc.SNAPSHOTS.mkdir(parents=True, exist_ok=True)
        os.replace(staging, final)
    finally:
        if staging.exists():
            shutil.rmtree(staging, ignore_errors=True)

    print(json.dumps({
        "snapshot": snap_id,
        "snapshot_path": str(final),
        "total_records": manifest["total_records"],
        "by_category": manifest["by_category"],
        "files": len(files),
        "rejected": rejected,
    }, ensure_ascii=False, indent=2))
    return 0


def cmd_list(args) -> int:
    rows = []
    if bc.SNAPSHOTS.exists():
        for snap in sorted(d for d in bc.SNAPSHOTS.iterdir()
                           if d.is_dir() and d.name.startswith("snap-")):
            try:
                m = bc.load_manifest(snap)
            except (OSError, json.JSONDecodeError, KeyError):
                rows.append({"snapshot": snap.name, "error": "unreadable manifest"})
                continue
            rows.append({
                "snapshot": snap.name,
                "created_at": m.get("created_at"),
                "total_records": m.get("total_records"),
                "by_category": m.get("by_category"),
                "files": m.get("total_files"),
                "audited": (bc.AUDIT / snap.name / "AGGREGATE.json").exists(),
            })
    print(json.dumps({"snapshots": rows, "count": len(rows)}, ensure_ascii=False, indent=2))
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    sub = ap.add_subparsers(dest="command", required=True)
    s = sub.add_parser("seal"); s.set_defaults(func=cmd_seal)
    t = sub.add_parser("take"); t.add_argument("--note", default=""); t.set_defaults(func=cmd_take)
    l = sub.add_parser("list"); l.set_defaults(func=cmd_list)
    args = ap.parse_args()
    return int(args.func(args))


if __name__ == "__main__":
    sys.exit(main())
