#!/usr/bin/env python3
"""Build a calibration text for llama-imatrix from the official SFT data.

The calibration text is plain Japanese TRPG text (system/user/assistant
messages rendered as a natural dialogue/log) so that the importance matrix
is computed on the same distribution the model sees at inference time.

Usage:
    python3 make_calib.py
Output:
    calib.txt  (UTF-8, no BOM)
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
OUT = HERE / "calib.txt"

ROLE_LABEL = {
    "system": "【system】",
    "user": "【user】",
    "assistant": "【assistant】",
}


def render_record(messages: list[dict]) -> list[str]:
    """Render one SFT record as a readable TRPG dialogue block."""
    lines: list[str] = []
    for msg in messages:
        role = msg.get("role", "")
        content = msg.get("content", "").strip()
        if not content:
            continue
        label = ROLE_LABEL.get(role, f"【{role}】")
        lines.append(f"{label}\n{content}")
    return lines


def main() -> None:
    blocks: list[str] = []
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
                rendered = render_record(rec.get("messages", []))
                if not rendered:
                    continue
                blocks.append("\n\n".join(rendered))
                total_records += 1
                total_chars += sum(len(b) for b in rendered)

    text = "\n\n" + ("=" * 40) + "\n\n".join(blocks) + "\n"
    OUT.write_text(text, encoding="utf-8")

    print(json.dumps({
        "output": str(OUT),
        "records": total_records,
        "content_chars": total_chars,
        "file_chars": len(text),
        "file_bytes": OUT.stat().st_size,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
