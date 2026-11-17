#!/usr/bin/env python3
"""Progress report for the isolated bulk generation (target ~10,000)."""

from __future__ import annotations

import json
import sys
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import bulk_common as bc  # noqa: E402


def count_records(path: Path) -> int:
    with path.open(encoding="utf-8") as fh:
        return sum(1 for line in fh if line.strip())


def main() -> int:
    config = json.loads((bc.BASE / "config.json").read_text(encoding="utf-8"))
    target = config["target_records"]

    inbox_rows = []
    for batch in bc.iter_batch_files():
        marker = bc.load_marker(batch)
        inbox_rows.append({
            "file": str(batch.relative_to(bc.BASE)),
            "records": count_records(batch),
            "sealed": marker is not None,
            "category": batch.parent.name,
        })

    snaps = []
    for snap in sorted((bc.SNAPSHOTS.iterdir() if bc.SNAPSHOTS.exists() else []),
                       key=lambda p: p.name):
        if not snap.is_dir() or not (snap / "MANIFEST.json").exists():
            continue
        m = bc.load_manifest(snap)
        ag_path = bc.AUDIT / snap.name / "AGGREGATE.json"
        ag = json.loads(ag_path.read_text(encoding="utf-8")) if ag_path.exists() else None
        snaps.append({
            "snapshot_id": snap.name,
            "records": m.get("total_records"),
            "by_category": m.get("by_category"),
            "audited": ag is not None,
            "scored": ag["audit"]["scored"] if ag else None,
            "fail": ag["audit"]["verdict_counts"].get("fail", 0) if ag else None,
        })

    sealed_records = sum(r["records"] for r in inbox_rows if r["sealed"])
    snapshotted = sum(s["records"] or 0 for s in snaps)
    audited = sum(s["scored"] or 0 for s in snaps)
    inbox_counts = Counter(r["category"] for r in inbox_rows)

    report = {
        "target_records": target,
        "inbox": {
            "batches": len(inbox_rows),
            "records": sum(r["records"] for r in inbox_rows),
            "sealed_records": sealed_records,
            "unsealed_records": sum(r["records"] for r in inbox_rows) - sealed_records,
            "by_category": dict(inbox_counts),
        },
        "snapshots": snaps,
        "snapshotted_records": snapshotted,
        "audited_records": audited,
        "remaining_to_target": max(0, target - snapshotted),
        "progress_pct": round(100 * snapshotted / target, 2),
        "note": "正式データへの統合は生成・監査完了後。raw/SFT/metadata/学習データは未変更。",
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
