#!/usr/bin/env python3
"""Shared helpers for the isolated bulk-generation pipeline.

This module lives entirely under training/.bulk_gen/. It never imports from or
writes to training/raw, processed, splits, metadata or the final SFT files.
"""

from __future__ import annotations

import hashlib
import json
import os
from datetime import datetime, timezone
from pathlib import Path

BASE = Path(__file__).resolve().parents[1]          # training/.bulk_gen
TOOLS = BASE.parent / "tools"                        # training/tools
INBOX = BASE / "inbox"
SEEDS = BASE / "inbox" / "seeds"
STATE = BASE / "state"
SNAPSHOTS = BASE / "snapshots"
AUDIT = BASE / "audit"
STAGING = BASE / "staging"
CATEGORIES = ("trpg_gm", "world_json", "extraction")
ALLOWED_ID_RE = None  # ids are free-form strings; uniqueness is enforced per batch


def now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def stamp() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with Path(path).open("rb") as fh:
        for chunk in iter(lambda: fh.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def sha256_json(obj) -> str:
    payload = json.dumps(obj, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def read_jsonl_strict(path: Path) -> tuple[list[dict], list[str]]:
    """Return (records, errors). Stops at the first malformed line's content."""
    records: list[dict] = []
    errors: list[str] = []
    with Path(path).open(encoding="utf-8") as fh:
        for lineno, line in enumerate(fh, 1):
            if not line.strip():
                continue
            try:
                obj = json.loads(line)
            except json.JSONDecodeError as exc:
                errors.append(f"line {lineno}: JSON parse error: {exc.msg}")
                continue
            if not isinstance(obj, dict):
                errors.append(f"line {lineno}: not a JSON object")
                continue
            records.append(obj)
    return records, errors


def batch_problems(path: Path) -> tuple[list[dict], list[str]]:
    """Structural validation of a generated batch file (no schema_lib needed)."""
    records, errors = read_jsonl_strict(path)
    seen: set[str] = set()
    for i, rec in enumerate(records, 1):
        rid = rec.get("id")
        if not isinstance(rid, str) or not rid.strip():
            errors.append(f"record {i}: missing/blank id")
        elif rid in seen:
            errors.append(f"record {i}: duplicate id {rid!r}")
        else:
            seen.add(rid)
        if rec.get("category") not in CATEGORIES:
            errors.append(f"record {i} ({rid}): category {rec.get('category')!r} not in {CATEGORIES}")
        if not isinstance(rec.get("task"), str) or not rec["task"]:
            errors.append(f"record {i} ({rid}): missing task")
        if not isinstance(rec.get("output"), dict):
            errors.append(f"record {i} ({rid}): output is not an object")
        if rec.get("category") == "trpg_gm" and not isinstance(rec.get("context"), dict):
            errors.append(f"record {i} ({rid}): gm record missing context")
    if not records:
        errors.append("empty batch (no records)")
    return records, errors


def iter_batch_files() -> list[Path]:
    if not INBOX.exists():
        return []
    return sorted(p for p in INBOX.glob("*/*.jsonl") if not p.name.startswith("."))


def marker_path(batch: Path) -> Path:
    return batch.with_name(batch.name + ".done.json")


def load_marker(batch: Path) -> dict | None:
    mp = marker_path(batch)
    if not mp.exists():
        return None
    try:
        data = json.loads(mp.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError):
        return None
    if not isinstance(data, dict):
        return None
    if data.get("sha256") != sha256_file(batch):
        return None
    if data.get("file") != batch.name:
        return None
    return data


def append_jsonl(path: Path, row: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with Path(path).open("a", encoding="utf-8") as fh:
        fh.write(json.dumps(row, ensure_ascii=False) + "\n")
        fh.flush()
        try:
            os.fsync(fh.fileno())
        except OSError:
            pass


def write_json(path: Path, obj) -> None:
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text(
        json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def atomic_write_json(path: Path, obj) -> None:
    """Write via a temporary file in the same directory, then rename."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    os.replace(tmp, path)


def latest_snapshot() -> Path | None:
    if not SNAPSHOTS.exists():
        return None
    dirs = sorted(d for d in SNAPSHOTS.iterdir() if d.is_dir() and d.name.startswith("snap-"))
    return dirs[-1] if dirs else None


def load_manifest(snap: Path) -> dict:
    return json.loads((Path(snap) / "MANIFEST.json").read_text(encoding="utf-8"))


def count_snapshot_records(snap: Path) -> int:
    total = 0
    for p in sorted((Path(snap) / "raw").rglob("*.jsonl")):
        with p.open(encoding="utf-8") as fh:
            total += sum(1 for line in fh if line.strip())
    return total
