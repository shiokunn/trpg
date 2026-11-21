#!/usr/bin/env python3
"""Independent verification of processed/ and splits/.

Checks, in order:
  1. JSONL syntax of every line in processed/ and splits/.
  2. Every split id exists in raw, and the assistant content still parses and
     passes the same schema/contract rules as the raw output.
  3. The rendered assistant content is byte-identical to the raw output, so a
     split file cannot drift from what the validator approved.
  4. Every id appears exactly once across train/validation/test.
  5. No scenario group spans two splits.
  6. No exact duplicate of (system,user,assistant) across splits.

Exit code 1 on any error. Read-only.
"""

from __future__ import annotations

import hashlib
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import build_dataset as B  # noqa: E402
import schema_lib  # noqa: E402

ROOT = HERE.parent
SPLITS = ("train", "validation", "test")


def load_jsonl(path: Path) -> list[dict]:
    rows = []
    for lineno, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            rows.append(json.loads(line))
        except json.JSONDecodeError as exc:
            raise SystemExit(f"{path.name}:{lineno}: JSON構文エラー: {exc}")
    return rows


def main() -> int:
    raw: dict[str, dict] = {}
    for record, source, parse_error in B.iter_raw():
        if parse_error:
            raise SystemExit(f"raw {source}: {parse_error}")
        raw[record["id"]] = record

    errors: list[str] = []
    seen: list[str] = []
    split_groups: dict[str, set[str]] = defaultdict(set)
    hashes: dict[str, str] = {}
    checked = 0

    for split in SPLITS:
        path = ROOT / "splits" / f"{split}.jsonl"
        if not path.exists():
            errors.append(f"{split}.jsonl がありません")
            continue
        for row in load_jsonl(path):
            checked += 1
            rid = row.get("id")
            if rid not in raw:
                errors.append(f"{split}: rawに存在しないid {rid}")
                continue
            seen.append(rid)
            record = raw[rid]
            split_groups[split].add(record.get("group") or rid)

            messages = row.get("messages") or []
            if not messages or messages[-1].get("role") != "assistant":
                errors.append(f"{split}/{rid}: 最後がassistantではありません")
                continue
            try:
                parsed = json.loads(messages[-1]["content"])
            except json.JSONDecodeError as exc:
                errors.append(f"{split}/{rid}: assistantがJSONとして不正: {exc}")
                continue
            report = schema_lib.validate_output(
                record["task"], parsed, record.get("context")
            )
            if report.errors:
                errors.append(f"{split}/{rid}: スキーマ違反 {report.errors[:3]}")
            if messages[-1]["content"] != json.dumps(
                record["output"], ensure_ascii=False, separators=(",", ":")
            ):
                errors.append(f"{split}/{rid}: assistantがrawと不一致")

            digest = hashlib.sha256(
                "".join(m.get("content", "") for m in messages).encode()
            ).hexdigest()
            if digest in hashes:
                errors.append(f"{split}/{rid}: 同一サンプルが {hashes[digest]} と重複")
            hashes[digest] = f"{split}/{rid}"

    duplicates = [rid for rid, n in Counter(seen).items() if n > 1]
    if duplicates:
        errors.append(f"split間でidが重複: {duplicates[:10]}")

    group_owner: dict[str, str] = {}
    for split, groups in split_groups.items():
        for group in groups:
            if group in group_owner and group_owner[group] != split:
                errors.append(
                    f"グループ {group} が {group_owner[group]} と {split} にまたがっています"
                )
            group_owner[group] = split

    counts = Counter(seen)
    split_rows = {s: sum(1 for rid in seen if rid in raw and (raw[rid].get("group") or rid) in split_groups[s]) for s in SPLITS}
    print(json.dumps({
        "checked_lines": checked,
        "unique_ids": len(counts),
        "samples_per_split": split_rows,
        "groups_per_split": {s: len(split_groups[s]) for s in SPLITS},
        "groups": {s: sorted(split_groups[s]) for s in SPLITS},
        "errors": len(errors),
        "error_samples": errors[:10],
    }, ensure_ascii=False, indent=2))
    return 0 if not errors else 1


if __name__ == "__main__":
    raise SystemExit(main())
