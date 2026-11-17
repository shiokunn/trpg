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

import argparse
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


def iter_raw_from(root: Path | None):
    """bd.iter_raw() equivalent for an explicit root (default keeps old behaviour)."""
    if root is None:
        yield from bd.iter_raw()
        return
    base = Path(root)
    for path in sorted(base.rglob("*.jsonl")):
        with path.open(encoding="utf-8") as fh:
            for lineno, line in enumerate(fh, 1):
                if not line.strip():
                    continue
                try:
                    record = json.loads(line)
                except json.JSONDecodeError as exc:
                    yield None, (path.name, lineno), f"JSON構文エラー: {exc}"
                else:
                    yield record, (path.name, lineno), None


def accepted_records(root: Path | None = None) -> tuple[list[dict], dict]:
    records: list[dict] = []
    rejected: list[dict] = []
    seen_ids: set[str] = set()
    for record, source, parse_error in iter_raw_from(root):
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


def load_accepted_ids(path: Path | None) -> set[str] | None:
    if path is None:
        return None
    ids: set[str] = set()
    for lineno, line in enumerate(path.open(encoding="utf-8"), 1):
        if not line.strip():
            continue
        try:
            obj = json.loads(line)
        except json.JSONDecodeError:
            rid = line.strip()
        else:
            rid = obj.get("id", "") if isinstance(obj, dict) else ""
        if rid:
            ids.add(rid)
        else:
            print(f"IDを読み取れない行をスキップ: {path.name}:{lineno}", file=sys.stderr)
    return ids


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--root", type=Path, default=None,
                    help="raw JSONL のルート。省略時は training/raw（後方互換）")
    ap.add_argument("--accepted-ids", type=Path, default=None,
                    help="PASS ID のみを通すファイル（1行1ID または JSONL の id フィールド）")
    ap.add_argument("--out-dir", type=Path, default=None,
                    help="隔離出力先。省略時は既存の正式SFTファイルへ出力（後方互換）")
    ap.add_argument("--force", action="store_true", help="既存出力ファイルの上書きを明示的に許可")
    args = ap.parse_args()

    accepted_ids = load_accepted_ids(args.accepted_ids)
    accepted, stats = accepted_records(args.root)
    if accepted_ids is not None:
        accepted = [r for r in accepted if r["id"] in accepted_ids]
        stats["filtered_by_accepted_ids"] = len(accepted)
        stats["accepted_ids_not_found"] = sorted(accepted_ids - {r["id"] for r in accepted})

    main_rows: list[dict] = []
    extraction_rows: list[dict] = []
    for record in accepted:
        row = {
            "messages": bd.to_messages(record),
            "metadata": {
                "id": record["id"],
                "task": record["task"],
                "source": "opencode-agent",
                "teacher_model": "deepseek-v4.1-flash-free",
                "license_review": "pending",
            },
        }
        if record["category"] == "extraction":
            extraction_rows.append(row)
        else:
            main_rows.append(row)

    if args.out_dir is None:
        out_main, out_extr = OUT_MAIN, OUT_EXTRACTION
    else:
        args.out_dir.mkdir(parents=True, exist_ok=True)
        out_main = args.out_dir / OUT_MAIN.name
        out_extr = args.out_dir / OUT_EXTRACTION.name
        if not args.force and (out_main.exists() or out_extr.exists()):
            print("隔離出力先に既存ファイルがあります。--force を指定してください。", file=sys.stderr)
            return 2

    for path, rows in ((out_main, main_rows), (out_extr, extraction_rows)):
        tmp = path.with_suffix(".jsonl.tmp")
        with tmp.open("w", encoding="utf-8") as fh:
            for row in rows:
                fh.write(json.dumps(row, ensure_ascii=False) + "\n")
        tmp.replace(path)

    report = {
        "output_files": {
            out_main.name: len(main_rows),
            out_extr.name: len(extraction_rows),
        },
        "output_dir": str(args.out_dir) if args.out_dir else str(ROOT),
        "total_written": len(main_rows) + len(extraction_rows),
        "raw_records": stats["raw_records"],
        "rejected": len(stats["rejected"]),
        "exact_duplicates": len(stats["exact_duplicates"]),
        "near_duplicates": len(stats["near_duplicates"]),
        "accepted": stats["accepted"],
        "filtered_by_accepted_ids": stats.get("filtered_by_accepted_ids"),
        "accepted_ids_not_found": stats.get("accepted_ids_not_found"),
    }
    # 隔離出力時は既存 metadata を一切変更しない
    if args.out_dir is None:
        meta_path = ROOT / "metadata" / "dataset_info.json"
        if meta_path.exists():
            meta = json.loads(meta_path.read_text(encoding="utf-8"))
            meta["final_sft_outputs"] = {
                "sft_synthetic_json.jsonl": {
                    "rows": len(main_rows), "bytes": out_main.stat().st_size,
                    "categories": ["trpg_gm", "world_json"],
                },
                "sft_synthetic_extraction.jsonl": {
                    "rows": len(extraction_rows), "bytes": out_extr.stat().st_size,
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
