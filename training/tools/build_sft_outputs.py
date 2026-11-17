#!/usr/bin/env python3
"""Aggregate only validated raw records into the two requested SFT files.

Outputs (created new, never overwrite in place):
  training/sft_synthetic_json.jsonl        -> trpg_gm + world_json
  training/sft_synthetic_extraction.jsonl  -> extraction

Every included line has already passed schema_lib validation against the Kotlin
contract, exact-duplicate removal and near-duplicate removal. Invalid raw records
are never copied into the outputs.
"""

from __future__ import annotations

import hashlib
import json
import sys
from collections import defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(HERE))

import build_dataset as bd  # noqa: E402
import schema_lib  # noqa: E402

OUT_MAIN = ROOT / "sft_synthetic_json.jsonl"
OUT_EXTRACTION = ROOT / "sft_synthetic_extraction.jsonl"


def accepted_records() -> tuple[list[dict], dict]:
    records: list[dict] = []
    rejected: list[dict] = []
    seen_ids: set[str] = set()
    for record, source, parse_error in bd.iter_raw():
        if parse_error:
            rejected.append({"source": source, "error": parse_error})
            continue
        errors, warnings = bd.record_problems(record)
        if not errors:
            if record["id"] in seen_ids:
                errors.append(f"id重複: {record['id']}")
            else:
                seen_ids.add(record["id"])
            errors.extend(
                schema_lib.validate_output(
                    record["task"], record["output"], record.get("context")
                ).errors
            )
        if errors:
            rejected.append({"id": record.get("id"), "errors": errors[:8]})
            continue
        records.append(record)

    exact_seen: dict[str, str] = {}
    unique: list[dict] = []
    exact_dupes: list[tuple[str, str]] = []
    for record in records:
        key = hashlib.sha256(
            json.dumps(
                {
                    "task": record["task"],
                    "input": [record.get("context"), record.get("action"), record.get("request")],
                    "output": record["output"],
                },
                ensure_ascii=False,
                sort_keys=True,
            ).encode()
        ).hexdigest()
        if key in exact_seen:
            exact_dupes.append((record["id"], exact_seen[key]))
            continue
        exact_seen[key] = record["id"]
        unique.append(record)

    near: list[tuple[str, str, float]] = []
    by_task: dict[str, list[dict]] = defaultdict(list)
    for record in unique:
        text = (
            record["output"].get("narrative", "")
            if record["task"] == "gm_turn"
            else record.get("request", "")
        )
        by_task[record["task"]].append({"id": record["id"], "text": text})
    for items in by_task.values():
        if len(items) > 1:
            near.extend(bd.near_duplicates(items))
    near_drop = {pair[1] for pair in near}
    accepted = [r for r in unique if r["id"] not in near_drop]
    stats = {
        "raw_records": len(records) + len(rejected),
        "rejected": rejected,
        "exact_duplicates": exact_dupes,
        "near_duplicates": near,
        "accepted": len(accepted),
    }
    return accepted, stats


def main() -> int:
    accepted, stats = accepted_records()
    main_rows: list[dict] = []
    extraction_rows: list[dict] = []
    for record in accepted:
        row = {"messages": bd.to_messages(record)}
        if record["category"] == "extraction":
            extraction_rows.append(row)
        else:
            main_rows.append(row)

    for path, rows in ((OUT_MAIN, main_rows), (OUT_EXTRACTION, extraction_rows)):
        tmp = path.with_suffix(".jsonl.tmp")
        with tmp.open("w", encoding="utf-8") as fh:
            for row in rows:
                fh.write(json.dumps(row, ensure_ascii=False) + "\n")
        tmp.replace(path)

    report = {
        "output_files": {
            OUT_MAIN.name: len(main_rows),
            OUT_EXTRACTION.name: len(extraction_rows),
        },
        "total_written": len(main_rows) + len(extraction_rows),
        "raw_records": stats["raw_records"],
        "rejected": len(stats["rejected"]),
        "exact_duplicates": len(stats["exact_duplicates"]),
        "near_duplicates": len(stats["near_duplicates"]),
        "accepted": stats["accepted"],
    }
    meta_path = ROOT / "metadata" / "dataset_info.json"
    if meta_path.exists():
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
        meta["final_sft_outputs"] = {
            "sft_synthetic_json.jsonl": {
                "rows": len(main_rows),
                "bytes": OUT_MAIN.stat().st_size,
                "categories": ["trpg_gm", "world_json"],
            },
            "sft_synthetic_extraction.jsonl": {
                "rows": len(extraction_rows),
                "bytes": OUT_EXTRACTION.stat().st_size,
                "categories": ["extraction"],
            },
            "total_rows": len(main_rows) + len(extraction_rows),
            "note": "合格したrawのみを集約。verify_sft_outputs.py で独立検証する。",
        }
        meta_path.write_text(
            json.dumps(meta, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )

    print(json.dumps(report, ensure_ascii=False, indent=2))
    if stats["rejected"]:
        print("rejected samples:", json.dumps(stats["rejected"][:10], ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
