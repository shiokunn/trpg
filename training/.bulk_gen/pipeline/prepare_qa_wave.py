#!/usr/bin/env python3
"""Prepare a two-tool audit run from a snapshot (derived root + scores + packets).

Creates <qa_runs>/<wave>/ with:
  id_map.json, raw/, python/, semantic/packets/packets.jsonl
  semantic/packets_partN.jsonl (for judge subagents, ~25 each)

Usage:
  python3 prepare_qa_wave.py --snapshot snap-0009-... --wave wave7a [--part-size 25]
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
BASE = HERE.parent
REPO = BASE.parent.parent
TOOLS = REPO / "training" / "tools"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--snapshot", required=True)
    ap.add_argument("--wave", required=True)
    ap.add_argument("--part-size", type=int, default=25)
    args = ap.parse_args()

    wave_dir = BASE / "qa_runs" / args.wave
    if (wave_dir / "id_map.json").exists():
        print(f"already prepared: {wave_dir}", file=sys.stderr)
        return 2

    def run(cmd):
        print("RUN:", " ".join(str(c) for c in cmd), flush=True)
        proc = subprocess.run([str(c) for c in cmd], capture_output=True, text=True)
        print(proc.stdout)
        if proc.returncode != 0:
            print(proc.stderr, file=sys.stderr)
            raise SystemExit(f"failed: {cmd[0]}")
        return proc

    run([sys.executable, HERE / "audit_make_derived.py",
         "--snapshot", args.snapshot, "--run-dir", wave_dir])
    (wave_dir / "python").mkdir(parents=True, exist_ok=True)
    (wave_dir / "semantic").mkdir(parents=True, exist_ok=True)
    run([sys.executable, TOOLS / "quality_system.py", "score",
         "--root", wave_dir / "raw", "--out-dir", wave_dir / "python"])
    run([sys.executable, TOOLS / "semantic_llm_engine.py", "prepare",
         "--root", wave_dir / "raw", "--out", wave_dir / "semantic/packets"])

    packets = [json.loads(l) for l in (wave_dir / "semantic/packets/packets.jsonl").open(encoding="utf-8")]
    size = max(1, args.part_size)
    parts = (len(packets) + size - 1) // size
    for i in range(parts):
        chunk = packets[i * size:(i + 1) * size]
        path = wave_dir / "semantic" / f"packets_part{i + 1}.jsonl"
        with path.open("w", encoding="utf-8") as fh:
            for row in chunk:
                fh.write(json.dumps(row, ensure_ascii=False) + "\n")
    print(json.dumps({
        "wave": args.wave, "snapshot": args.snapshot,
        "records": len(packets), "judge_parts": parts,
        "run_dir": str(wave_dir),
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
