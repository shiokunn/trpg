#!/usr/bin/env python3
"""Build isolated SFT JSONL files from PASS ids only (never touches official files).

Uses the existing build_dataset.to_messages() renderer so the format matches the
app contract. Output goes to a dedicated isolated directory under audit_runs/.

Usage:
  python3 audit_build_isolated_sft.py --raw ../audit_runs/wave1/raw \
      --pass-ids ../audit_runs/wave1/classified/pass.jsonl \
      --out-dir ../audit_runs/wave1/sft_isolated
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
TOOLS = HERE.parent.parent / "tools"
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(TOOLS))

import build_dataset as bd  # noqa: E402


def load_pass_ids(path: Path) -> set[str]:
    ids = set()
    for line in Path(path).open(encoding="utf-8"):
        if not line.strip():
            continue
        row = json.loads(line)
        if row.get("verdict") == "PASS":
            ids.add(row["id"])
    return ids


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--raw", type=Path, required=True)
    ap.add_argument("--pass-ids", type=Path, required=True)
    ap.add_argument("--out-dir", type=Path, required=True)
    args = ap.parse_args()

    pass_ids = load_pass_ids(args.pass_ids)
    out_dir = Path(args.out_dir)
    if out_dir.exists() and any(out_dir.glob("*.jsonl")):
        print(f"isolated SFT dir already has jsonl files: {out_dir}", file=sys.stderr)
        return 2
    out_dir.mkdir(parents=True, exist_ok=True)

    main_rows, extraction_rows = [], []
    written_ids: set[str] = set()
    skipped_not_pass: list[str] = []
    for src in sorted(Path(args.raw).rglob("*.jsonl")):
        for line in src.open(encoding="utf-8"):
            if not line.strip():
                continue
            rec = json.loads(line)
            rid = rec["id"]
            if rid not in pass_ids:
                skipped_not_pass.append(rid)
                continue
            row = {
                "messages": bd.to_messages(rec),
                "metadata": {
                    "task": rec["task"],
                    "source": "opencode-agent-bulk",
                    "teacher_model": "hy4-flash-free",
                    "license_review": "pending",
                },
            }
            written_ids.add(rid)
            if rec["category"] == "extraction":
                extraction_rows.append(row)
            else:
                main_rows.append(row)

    paths = {
        "sft_synthetic_json.jsonl": main_rows,
        "sft_synthetic_extraction.jsonl": extraction_rows,
    }
    for name, rows in paths.items():
        tmp = out_dir / (name + ".tmp")
        with tmp.open("w", encoding="utf-8") as fh:
            for row in rows:
                fh.write(json.dumps(row, ensure_ascii=False) + "\n")
        tmp.replace(out_dir / name)

    missing_pass = sorted(pass_ids - written_ids)
    report = {
        "pass_ids": len(pass_ids),
        "rows_written": sum(len(v) for v in paths.values()),
        "by_file": {k: len(v) for k, v in paths.items()},
        "written_ids_all_in_pass": written_ids <= pass_ids,
        "pass_ids_not_found_in_raw": missing_pass[:50],
        "non_pass_records_excluded": len(skipped_not_pass),
        "official_files_touched": False,
    }
    (out_dir / "isolated_sft_report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if not missing_pass and report["written_ids_all_in_pass"] else 1


if __name__ == "__main__":
    sys.exit(main())
