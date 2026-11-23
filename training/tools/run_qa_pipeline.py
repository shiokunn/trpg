#!/usr/bin/env python3
"""Orchestrate the five-stage TRPG QA pipeline.

Stages (in order):
  1 quality_system.py score
  2 semantic_llm_engine.py prepare
  3 semantic judgments (external input; this orchestrator never calls a model)
  4 semantic_llm_engine.py ingest
  5 classify_qa.py
  6 build_sft_outputs.py (isolated output only)
  7 verify_sft_outputs.py

The LLM judgment step is a PASS-THROUGH: a judgments JSONL must already exist
(produced by the agent or an HTTP client). Missing judgments cause REVIEW, never
automatic PASS. Existing outputs are reused via --resume / content hashes.

Usage:
  python3 training/tools/run_qa_pipeline.py \
      --root training/raw \
      --work-dir /tmp/qa-pipeline-run \
      --judgments /tmp/judgments.jsonl

  # custom/copy roots must be explicitly allowed
  python3 training/tools/run_qa_pipeline.py --root /tmp/sft-root --allow-root ...
"""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
TOOLS = HERE
DEFAULT_ROOT = ROOT / "raw"
FORBIDDEN = ("eval", "experiment", "audit", "gold", "fixture", "training-data-copy")

STAGES = ("quality_score", "semantic_prepare", "semantic_judgments", "semantic_ingest",
          "classify", "sft_export", "sft_verify")


def now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def parse_json_output(text: str) -> dict:
    """Parse the last JSON object printed by a stage."""
    text = text.strip()
    if not text:
        return {}
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        pass
    start = text.find("{")
    while start != -1:
        try:
            obj, end = json.JSONDecoder().raw_decode(text[start:])
            if isinstance(obj, dict):
                last = obj
            else:
                break
            next_start = text.find("{", start + end + 1)
            if next_start == -1:
                return last
            start = next_start
        except json.JSONDecodeError:
            return {}
    return {}


def safe_path(path: Path, label: str) -> list[str]:
    parts = {p.lower() for p in path.resolve().parts}
    bad = parts & set(FORBIDDEN)
    return [f"{label}: 評価データ/実験データと疑われるパスを拒否: {path}"] if bad else []


def run_stage(name: str, cmd: list[str], work: Path, expect: list[Path] | None = None,
              counts_from: str | None = None) -> dict:
    entry = {
        "stage": name, "command": cmd, "started_at": now(),
        "exit_code": None, "status": "not_run", "counts": {}, "error": "",
    }
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True)
        entry["exit_code"] = proc.returncode
        entry["counts"] = parse_json_output(proc.stdout)
        if proc.returncode != 0:
            entry["status"] = "failed"
            entry["error"] = (proc.stderr or proc.stdout).strip()[:1000]
        else:
            missing = [str(p) for p in (expect or []) if not p.exists()]
            if missing:
                entry["status"] = "failed"
                entry["error"] = f"出力ファイルが無い: {missing}"
            else:
                entry["status"] = "success"
    except OSError as exc:
        entry["status"] = "failed"
        entry["error"] = f"実行失敗: {exc}"
    entry["finished_at"] = now()
    return entry


class Pipeline:
    def __init__(self, args):
        self.args = args
        self.root = Path(args.root)
        self.work = Path(args.work_dir)
        self.report_path = self.work / "pipeline_report.json"
        self.report = {
            "pipeline_version": "qa-pipeline-1.0",
            "root": str(self.root),
            "work_dir": str(self.work),
            "judgments": str(args.judgments),
            "started_at": now(),
            "stages": [],
            "final_status": "running",
        }
        if self.report_path.exists():
            try:
                previous = json.loads(self.report_path.read_text(encoding="utf-8"))
                if isinstance(previous, dict):
                    self.report.update(previous)
                    self.report["started_at"] = previous.get("started_at", now())
                    self.report["judgments"] = str(args.judgments)
            except json.JSONDecodeError:
                pass

    def save(self) -> None:
        self.work.mkdir(parents=True, exist_ok=True)
        tmp = self.report_path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self.report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        tmp.replace(self.report_path)

    def should_skip(self, stage: str, outputs: list[Path]) -> bool:
        return all(p.exists() for p in outputs)

    def record(self, entry: dict, skipped: bool = False) -> bool:
        if skipped:
            entry["status"] = "skipped_reused"
            entry["exit_code"] = 0
        self.report["stages"].append(entry)
        self.save()
        return entry["status"] in ("success", "skipped_reused")

    def run(self) -> int:
        a = self.args
        errors = safe_path(self.root, "root") + safe_path(self.work, "work_dir") + safe_path(
            Path(a.judgments), "judgments")
        if self.root.resolve() != DEFAULT_ROOT.resolve() and not a.allow_root:
            errors.append("--allow-root が必要です（既定ルートは training/raw）")
        if errors:
            for e in errors:
                print(e, file=sys.stderr)
            self.report["final_status"] = "rejected"
            self.save()
            return 2

        judgments = Path(a.judgments)
        if not judgments.is_file():
            print(f"判定ファイルが存在しません: {judgments}", file=sys.stderr)
            self.report["final_status"] = "rejected"
            self.save()
            return 2

        qdir = self.work / "quality"
        sdir = self.work / "semantic"
        cdir = self.work / "classified"
        fdir = self.work / "sft"

        # 1 quality score
        q_cache = qdir / "score_cache.jsonl"
        q_cmd = [sys.executable, str(TOOLS / "quality_system.py"), "score",
                 "--root", str(self.root), "--out-dir", str(qdir),
                 "--batch-size", str(a.batch_size), "--workers", str(a.workers)]
        if a.limit:
            q_cmd += ["--limit", str(a.limit)]
        if q_cache.exists():
            q_cmd.append("--resume")
        e = run_stage("quality_score", q_cmd, self.work, [q_cache])
        if not self.record(e, skipped=False if q_cache.exists() is False else False):
            return self.fail(e)
        # reuse count on a truly skipped stage is handled by the stage output itself

        # 2 semantic prepare — packets are namespaced by the scored content set
        score_rows = [json.loads(l) for l in q_cache.open(encoding="utf-8") if l.strip()]
        root_hash = hashlib.sha256(json.dumps(
            sorted((r["id"], r.get("content_hash", "")) for r in score_rows),
            ensure_ascii=False).encode()).hexdigest()[:12]
        packets_dir = sdir / "packets" / root_hash
        packets = packets_dir / "packets.jsonl"
        p_cmd = [sys.executable, str(TOOLS / "semantic_llm_engine.py"), "prepare",
                 "--root", str(self.root), "--out", str(packets_dir)]
        if a.limit:
            p_cmd += ["--limit", str(a.limit)]
        if packets.exists():
            e = run_stage("semantic_prepare", p_cmd, self.work, [packets])
            self.record(e, skipped=True)
        else:
            e = run_stage("semantic_prepare", p_cmd, self.work, [packets])
            if not self.record(e):
                return self.fail(e)

        # 3 judgments (pass-through, never auto-generated) + coverage audit
        packet_ids: set[str] = set()
        if packets.exists():
            for line in packets.open(encoding="utf-8"):
                if not line.strip():
                    continue
                try:
                    packet_ids.add(json.loads(line)["id"])
                except (json.JSONDecodeError, KeyError, TypeError):
                    continue
        judgment_ids: set[str] = set()
        duplicate_judgments = 0
        invalid_judgments = 0
        for line in judgments.open(encoding="utf-8"):
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError:
                invalid_judgments += 1
                continue
            rid = row.get("id") if isinstance(row, dict) else None
            if not rid:
                invalid_judgments += 1
                continue
            if rid in judgment_ids:
                duplicate_judgments += 1
            judgment_ids.add(rid)
        missing_ids = sorted(packet_ids - judgment_ids)
        extra_ids = sorted(judgment_ids - packet_ids)
        j_entry = {
            "stage": "semantic_judgments", "command": ["PASS_THROUGH", str(judgments)],
            "started_at": now(), "finished_at": now(), "exit_code": 0,
            "status": "success", "counts": {
                "judgments_file": str(judgments),
                "packets": len(packet_ids), "judgments": len(judgment_ids),
                "missing_judgments": len(missing_ids), "extra_judgments": len(extra_ids),
                "duplicate_judgments": duplicate_judgments,
                "invalid_judgments": invalid_judgments,
                "missing_ids_sample": missing_ids[:20], "extra_ids_sample": extra_ids[:20],
            },
            "error": "",
            "note": "LLM判定は外部入力。オーケストレータはモデルを呼ばない。欠落IDはREVIEW扱い。",
        }
        self.report["stages"].append(j_entry)
        self.save()

        # 4 semantic ingest
        sem_final = sdir / "semantic_final.jsonl"
        i_cmd = [sys.executable, str(TOOLS / "semantic_llm_engine.py"), "ingest",
                 "--packets", str(packets), "--judgments", str(judgments),
                 "--root", str(self.root), "--out", str(sdir)]
        if sem_final.exists():
            i_cmd.append("--resume")
        e = run_stage("semantic_ingest", i_cmd, self.work, [sem_final, sdir / "manifest.jsonl"])
        if not self.record(e):
            return self.fail(e)

        # 5 classify
        pass_file = cdir / "pass.jsonl"
        c_cmd = [sys.executable, str(TOOLS / "classify_qa.py"),
                 "--scores", str(q_cache), "--semantic", str(sem_final),
                 "--manifest", str(sdir / "manifest.jsonl"),
                 "--root", str(self.root), "--out-dir", str(cdir)]
        if a.allow_root:
            c_cmd.append("--allow-root")
        if pass_file.exists():
            c_cmd.append("--resume")
        e = run_stage("classify", c_cmd, self.work,
                      [pass_file, cdir / "review.jsonl", cdir / "fail.jsonl"])
        if not self.record(e):
            return self.fail(e)

        # 6 SFT export (isolated, PASS ids only)
        sft_main = fdir / "sft_synthetic_json.jsonl"
        pass_hash = hashlib.sha256(pass_file.read_bytes()).hexdigest()
        prev_hash = self.report.get("pass_set_hash")
        b_cmd = [sys.executable, str(TOOLS / "build_sft_outputs.py"),
                 "--root", str(self.root), "--accepted-ids", str(pass_file),
                 "--out-dir", str(fdir)]
        if sft_main.exists() and prev_hash == pass_hash:
            self.record({"stage": "sft_export", "command": b_cmd, "started_at": now(),
                         "finished_at": now(), "exit_code": 0, "status": "skipped_reused",
                         "counts": {"pass_set_hash": pass_hash}, "error": ""}, skipped=True)
        else:
            if sft_main.exists():
                b_cmd.append("--force")  # PASS集合が変わったため隔離出力のみ再生成
            e = run_stage("sft_export", b_cmd, self.work,
                          [sft_main, fdir / "sft_synthetic_extraction.jsonl"])
            e["counts"]["pass_set_hash"] = pass_hash
            if not self.record(e):
                return self.fail(e)
            self.report["pass_set_hash"] = pass_hash
            self.save()

        # 7 verify
        v_cmd = [sys.executable, str(TOOLS / "verify_sft_outputs.py"), "--root", str(fdir)]
        e = run_stage("sft_verify", v_cmd, self.work)
        if not self.record(e):
            return self.fail(e)

        # PASS ID 集合と隔離SFT出力の ID 集合を照合
        pass_ids = [json.loads(l)["id"] for l in pass_file.open(encoding="utf-8") if l.strip()]
        sft_ids: list[str] = []
        for name in ("sft_synthetic_json.jsonl", "sft_synthetic_extraction.jsonl"):
            for line in (fdir / name).open(encoding="utf-8"):
                if not line.strip():
                    continue
                try:
                    obj = json.loads(line)
                    sft_ids.append(obj["metadata"]["id"])
                except (json.JSONDecodeError, KeyError, TypeError):
                    continue
        expected, actual = set(pass_ids), set(sft_ids)
        consistency = {
            "pass_ids": len(expected), "sft_ids": len(actual),
            "missing_in_sft": sorted(expected - actual)[:50],
            "extra_in_sft": sorted(actual - expected)[:50],
            "pass_id_duplicates": len(pass_ids) - len(expected),
            "sft_id_duplicates": len(sft_ids) - len(actual),
        }
        self.report["sft_id_consistency"] = consistency
        self.save()
        if expected != actual or consistency["sft_id_duplicates"]:
            self.report["final_status"] = "failed"
            self.report["failed_stage"] = "sft_id_consistency"
            self.report["error"] = "PASS集合とSFT出力IDが一致しない"
            self.save()
            print(json.dumps({"final_status": "failed",
                              "failed_stage": "sft_id_consistency",
                              "consistency": consistency}, ensure_ascii=False, indent=2),
                  file=sys.stderr)
            return 1

        self.report["final_status"] = "success"
        self.report["finished_at"] = now()
        self.save()
        print(json.dumps({
            "final_status": "success",
            "stages": [{"stage": s["stage"], "status": s["status"]} for s in self.report["stages"]],
            "report": str(self.report_path),
        }, ensure_ascii=False, indent=2))
        return 0

    def fail(self, entry: dict) -> int:
        self.report["final_status"] = "failed"
        self.report["failed_stage"] = entry["stage"]
        self.report["error"] = entry.get("error", "")
        self.report["finished_at"] = now()
        self.save()
        print(json.dumps({
            "final_status": "failed", "failed_stage": entry["stage"],
            "error": entry.get("error", ""), "report": str(self.report_path),
        }, ensure_ascii=False, indent=2), file=sys.stderr)
        return 1


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--root", default=str(DEFAULT_ROOT))
    ap.add_argument("--work-dir", required=True)
    ap.add_argument("--judgments", required=True)
    ap.add_argument("--allow-root", action="store_true")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--batch-size", type=int, default=500)
    ap.add_argument("--workers", type=int, default=1)
    args = ap.parse_args()
    return Pipeline(args).run()


if __name__ == "__main__":
    sys.exit(main())
