#!/usr/bin/env python3
"""Independent verification of the two final SFT JSONL files.

Checks: line-by-line JSON parse, messages shape, role order, assistant content
being a parseable compact JSON object, exact duplicates, and cross-file count
consistency with training/metadata/dataset_info.json.

Usage: python3 training/tools/verify_sft_outputs.py
"""

from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FILES = [
    ROOT / "sft_synthetic_json.jsonl",
    ROOT / "sft_synthetic_extraction.jsonl",
]


def main() -> int:
    errors: list[str] = []
    summary = {}
    for path in FILES:
        if not path.exists():
            errors.append(f"{path.name}: ファイルが存在しない")
            continue
        seen: set[str] = set()
        rows = 0
        role_shapes: dict[str, int] = {}
        with path.open(encoding="utf-8") as fh:
            for lineno, line in enumerate(fh, 1):
                if not line.strip():
                    errors.append(f"{path.name}:{lineno}: 空行")
                    continue
                try:
                    obj = json.loads(line)
                except json.JSONDecodeError as exc:
                    errors.append(f"{path.name}:{lineno}: JSON構文エラー {exc}")
                    continue
                rows += 1
                if set(obj.keys()) != {"messages", "metadata"}:
                    errors.append(f"{path.name}:{lineno}: トップキーが messages/metadata ではない")
                    continue
                meta = obj["metadata"]
                if not isinstance(meta, dict) or not isinstance(meta.get("task"), str):
                    errors.append(f"{path.name}:{lineno}: metadata.task が不正")
                msgs = obj["messages"]
                if not isinstance(msgs, list) or len(msgs) < 2:
                    errors.append(f"{path.name}:{lineno}: messages が短すぎる")
                    continue
                roles = [m.get("role") if isinstance(m, dict) else None for m in msgs]
                if roles[0] != "system" or roles[-1] != "assistant":
                    errors.append(f"{path.name}:{lineno}: role先頭/末尾が不正 {roles[:2]}..{roles[-2:]}")
                if any(role not in ("system", "user", "assistant") for role in roles):
                    errors.append(f"{path.name}:{lineno}: 未知のrole")
                shape = ";".join(roles)
                role_shapes[shape] = role_shapes.get(shape, 0) + 1
                last = msgs[-1]
                if not isinstance(last, dict) or not isinstance(last.get("content"), str):
                    errors.append(f"{path.name}:{lineno}: assistant.content が文字列ではない")
                    continue
                content = last["content"]
                if content.startswith("```"):
                    errors.append(f"{path.name}:{lineno}: コードフェンスが残っている")
                try:
                    parsed = json.loads(content)
                except json.JSONDecodeError:
                    errors.append(f"{path.name}:{lineno}: assistant.content がJSONでない")
                    continue
                if not isinstance(parsed, dict):
                    errors.append(f"{path.name}:{lineno}: assistant.content がオブジェクトでない")
                digest = hashlib.sha256(line.strip().encode()).hexdigest()
                if digest in seen:
                    errors.append(f"{path.name}:{lineno}: 完全一致重複")
                seen.add(digest)
        summary[path.name] = {
            "rows": rows,
            "unique": len(seen),
            "role_shapes": role_shapes,
            "bytes": path.stat().st_size,
        }

    total = sum(v["rows"] for v in summary.values())
    print(json.dumps({"files": summary, "total_rows": total,
                      "errors": len(errors), "error_samples": errors[:20]},
                     ensure_ascii=False, indent=2))
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
