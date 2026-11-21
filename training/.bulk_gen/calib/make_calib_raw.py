#!/usr/bin/env python3
"""Build a *raw* calibration text (no role labels) from the official SFT data.

This variant renders only the message contents, joined by blank lines, so the
text looks like a plain TRPG log. Useful as an alternative to the labelled
calib.txt when you want the importance matrix to be computed on bare text.

Usage:
    python3 make_calib_raw.py
Output:
    calib_raw.txt  (UTF-8, no BOM)
"""

from __future__ import annotations

import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
SFT_DIR = HERE.parent.parent  # training/
SFT_FILES = [
    SFT_DIR / "sft_synthetic_json.jsonl",
    SFT_DIR / "sft_synthetic_extraction.jsonl",
]
OUT = HERE / "calib_raw.txt"


def main() -> None:
    records: list[str] = []
    total_records = 0
    total_chars = 0

    for path in SFT_FILES:
        if not path.exists():
            raise SystemExit(f"missing SFT file: {path}")
        with path.open(encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if not line:
                    continue
                rec = json.loads(line)
                contents = [
                    m.get("content", "").strip()
                    for m in rec.get("messages", [])
                    if m.get("content", "").strip()
                ]
                if not contents:
                    continue
                block = "\n\n".join(contents)
                records.append(block)
                total_records += 1
                total_chars += len(block)

    text = "\n\n".join(records) + "\n"
    OUT.write_text(text, encoding="utf-8")

    print(json.dumps({
        "output": str(OUT),
        "records": total_records,
        "content_chars": total_chars,
        "file_bytes": OUT.stat().st_size,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
