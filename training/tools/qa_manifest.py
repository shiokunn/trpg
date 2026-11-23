#!/usr/bin/env python3
"""Common QA stage manifest (append-only JSONL).

Fields per entry:
  stage, id, content_hash, verifier_version, status, timestamp

The manifest is the single hand-off record between pipeline stages. It is
append-only: reprocessing the same content appends a new entry, and `latest()`
returns the most recent state. Corrupted lines are skipped, so a crash during
an append cannot corrupt the whole ledger.

Usage:
  python3 training/tools/qa_manifest.py show --path FILE [--stage STAGE]
  python3 training/tools/qa_manifest.py import-quality --ledger FILE --out FILE
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from datetime import datetime, timezone
from pathlib import Path

FIELDS = ("stage", "id", "content_hash", "verifier_version", "status", "timestamp")


def now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def content_hash(*objs) -> str:
    """Deterministic hash across heterogeneous inputs."""
    payload = json.dumps(objs, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


class Manifest:
    def __init__(self, path: Path):
        self.path = Path(path)
        self.entries: list[dict] = []
        self.skipped = 0
        if self.path.exists():
            with self.path.open(encoding="utf-8") as fh:
                for line in fh:
                    if not line.strip():
                        continue
                    try:
                        row = json.loads(line)
                    except json.JSONDecodeError:
                        self.skipped += 1
                        continue
                    if not all(k in row for k in FIELDS):
                        self.skipped += 1
                        continue
                    self.entries.append(row)

    def append(self, stage: str, rid: str, digest: str, version: str, status: str,
               timestamp: str | None = None) -> dict:
        row = {
            "stage": stage,
            "id": rid,
            "content_hash": digest,
            "verifier_version": version,
            "status": status,
            "timestamp": timestamp or now(),
        }
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.path.open("a", encoding="utf-8") as fh:
            fh.write(json.dumps(row, ensure_ascii=False) + "\n")
            fh.flush()
            try:
                os.fsync(fh.fileno())
            except OSError:
                pass
        self.entries.append(row)
        return row

    def latest(self, stage: str, rid: str) -> dict | None:
        found = None
        for entry in self.entries:
            if entry["stage"] == stage and entry["id"] == rid:
                found = entry
        return found

    def latest_all(self, stage: str) -> dict[str, dict]:
        out: dict[str, dict] = {}
        for entry in self.entries:
            if entry["stage"] == stage:
                out[entry["id"]] = entry
        return out

    def duplicate_ids(self, stage: str) -> list[str]:
        counts: dict[str, int] = {}
        for entry in self.entries:
            if entry["stage"] == stage:
                counts[entry["id"]] = counts.get(entry["id"], 0) + 1
        return sorted(k for k, v in counts.items() if v > 1)


def import_quality_ledger(ledger: Path, out: Path) -> int:
    """Convert the existing quality_system ledger into the common manifest."""
    man = Manifest(out)
    imported = 0
    for line in ledger.open(encoding="utf-8"):
        if not line.strip():
            continue
        try:
            row = json.loads(line)
        except json.JSONDecodeError:
            continue
        if "id" not in row or "content_hash" not in row:
            continue
        man.append(
            stage="python_score",
            rid=row["id"],
            digest=row["content_hash"],
            version=row.get("verifier_version", "qa-1.0.0"),
            status="completed",
            timestamp=row.get("processed_at", now()),
        )
        imported += 1
    print(json.dumps({"imported": imported, "manifest": str(out),
                      "skipped_lines": man.skipped}, ensure_ascii=False, indent=2))
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    sub = ap.add_subparsers(dest="command", required=True)

    s = sub.add_parser("show")
    s.add_argument("--path", required=True, type=Path)
    s.add_argument("--stage", default="")
    s.set_defaults(func=lambda a: _show(a))

    i = sub.add_parser("import-quality")
    i.add_argument("--ledger", required=True, type=Path)
    i.add_argument("--out", required=True, type=Path)
    i.set_defaults(func=lambda a: import_quality_ledger(a.ledger, a.out))

    args = ap.parse_args()
    return int(args.func(args))


def _show(args) -> int:
    man = Manifest(args.path)
    rows = [e for e in man.entries if not args.stage or e["stage"] == args.stage]
    print(json.dumps({"entries": rows[-50:], "total": len(rows),
                      "skipped_lines": man.skipped,
                      "duplicate_ids": man.duplicate_ids(args.stage) if args.stage else []},
                     ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
