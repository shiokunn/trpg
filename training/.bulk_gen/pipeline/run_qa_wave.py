#!/usr/bin/env python3
"""Run the identical two-tool audit chain for one wave after judgments exist.

Steps (all inside the isolated wave run directory):
  1. semantic_llm_engine.py ingest   (uses existing qa_manifest + hash matching)
  2. classify_qa.py                  (PASS / REVIEW / FAIL; strict ID + hash checks)
  3. audit_build_isolated_sft.py     (PASS-only isolated SFT, official files untouched)
  4. verify_sft_outputs.py           (SFT format/content/consistency verification)
  5. audit_report.py                 (counts, PASS rate, problematic IDs, reasons)

Usage:
  python3 run_qa_wave.py --run-dir ../qa_runs/wave2
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


def run(cmd: list[str], capture: bool = False) -> subprocess.CompletedProcess:
    print("RUN:", " ".join(str(c) for c in cmd), flush=True)
    proc = subprocess.run([str(c) for c in cmd], capture_output=capture, text=True)
    if proc.returncode != 0:
        print(proc.stdout)
        print(proc.stderr, file=sys.stderr)
        raise SystemExit(f"step failed (rc={proc.returncode}): {cmd[0]}")
    if capture:
        return proc
    print(proc.stdout)
    return proc


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--run-dir", type=Path, required=True)
    args = ap.parse_args()
    run_dir = Path(args.run_dir).resolve()
    py = sys.executable

    judgments = sorted(run_dir.glob("semantic/judgments_*.jsonl"))
    merged = run_dir / "semantic" / "judgments.jsonl"
    if not merged.exists():
        with merged.open("w", encoding="utf-8") as out:
            for p in judgments:
                out.write(p.read_text(encoding="utf-8"))
    if not merged.exists() or merged.stat().st_size == 0:
        raise SystemExit("no semantic judgments found")

    run([py, TOOLS / "semantic_llm_engine.py", "ingest",
         "--packets", run_dir / "semantic/packets/packets.jsonl",
         "--judgments", merged,
         "--root", run_dir / "raw",
         "--out", run_dir / "semantic"])

    run([py, TOOLS / "classify_qa.py",
         "--scores", run_dir / "python/score_cache.jsonl",
         "--semantic", run_dir / "semantic/semantic_final.jsonl",
         "--manifest", run_dir / "semantic/manifest.jsonl",
         "--root", run_dir / "raw", "--allow-root",
         "--out-dir", run_dir / "classified"])

    run([py, HERE / "audit_build_isolated_sft.py",
         "--raw", run_dir / "raw",
         "--pass-ids", run_dir / "classified/pass.jsonl",
         "--out-dir", run_dir / "sft_isolated"])

    proc = run([py, TOOLS / "verify_sft_outputs.py",
                "--root", run_dir / "sft_isolated"], capture=True)
    (run_dir / "verify_sft_outputs.json").write_text(proc.stdout, encoding="utf-8")
    print(proc.stdout)

    run([py, HERE / "audit_report.py", "--run-dir", run_dir])
    report = json.loads((run_dir / "AUDIT_REPORT.json").read_text(encoding="utf-8"))
    print(json.dumps({
        "run_dir": str(run_dir),
        "counts": report["counts"],
        "pass_rate_pct": report["pass_rate_pct"],
        "verify_errors": report["verify_sft_errors"],
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
