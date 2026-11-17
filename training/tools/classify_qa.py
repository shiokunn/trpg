#!/usr/bin/env python3
"""Final PASS / REVIEW / FAIL classification and isolated SFT hand-off.

Inputs:
  --scores      quality_system score_cache.jsonl (or quality_scores.json samples)
  --semantic    semantic_llm_engine semantic_final.jsonl
  --manifest    common manifest JSONL
  --root        raw source root (provenance / hash check, read-only)

Semantic verdicts are NEVER recomputed or overwritten here. The three hashes
(python, semantic, integrated) are kept separately.

Usage:
  python3 training/tools/classify_qa.py \
    --scores qa/score_cache.jsonl --semantic qa/semantic/semantic_final.jsonl \
    --manifest qa/semantic/manifest.jsonl --root training/raw \
    --out-dir qa/classified [--resume]

  # temporary/copy roots must be explicitly allowed
  python3 training/tools/classify_qa.py ... --root /tmp/sft-root --allow-root
"""

from __future__ import annotations

import argparse
import json
import os
import re
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
import sys  # noqa: E402
sys.path.insert(0, str(HERE))

import qa_manifest as qm  # noqa: E402
import quality_system as qs  # noqa: E402

VERSION = "classify-1.0"
ID_RE = re.compile(r"^(gm(?:-[a-z])?|ex|world)-\d{4}$")
FORBIDDEN_PATH_TOKENS = ("eval", "experiment", "audit", "test", "gold", "judgment", "fixture")
ALLOWED_DEFAULT_ROOT = ROOT / "raw"


def iter_jsonl(path: Path):
    if not path.exists():
        return
    with path.open(encoding="utf-8") as fh:
        for lineno, line in enumerate(fh, 1):
            if not line.strip():
                continue
            try:
                yield json.loads(line), lineno
            except json.JSONDecodeError:
                print(f"不正なJSONL行をスキップ: {path.name}:{lineno}", file=sys.stderr)


def load_scores(path: Path) -> tuple[dict[str, dict], int, list[str]]:
    if path.name == "quality_scores.json" and path.exists():
        data = json.loads(path.read_text(encoding="utf-8"))
        rows = data.get("samples", [])
        return {r["id"]: r for r in rows if isinstance(r, dict) and r.get("id")}, 0, []
    out: dict[str, dict] = {}
    skipped = 0
    duplicates: list[str] = []
    for lineno, line in enumerate(path.open(encoding="utf-8"), 1):
        if not line.strip():
            continue
        try:
            row = json.loads(line)
        except json.JSONDecodeError:
            print(f"不正なJSONL行をスキップ: {path.name}:{lineno}", file=sys.stderr)
            skipped += 1
            continue
        if not isinstance(row, dict) or not row.get("id"):
            skipped += 1
            continue
        if row["id"] in out:
            duplicates.append(row["id"])
        out[row["id"]] = row  # 重複IDは最後の1件（監査で記録）
    return out, skipped, duplicates


def validate_root(path: Path, allow_root: bool) -> list[str]:
    errors: list[str] = []
    resolved = path.resolve()
    if resolved == ALLOWED_DEFAULT_ROOT.resolve():
        return errors
    if not allow_root:
        errors.append(f"許可されていないルートです: {path}（--allow-root が必要）")
    parts = {p.lower() for p in resolved.parts}
    if parts & set(FORBIDDEN_PATH_TOKENS):
        errors.append(f"評価データと疑われるルートを拒否しました: {path}")
    return errors


def load_raw_index(root: Path) -> dict[str, dict]:
    return {r.get("id"): r for r in qs.iter_records(root)}


def load_previous(out: Path) -> dict[str, dict]:
    rows: dict[str, dict] = {}
    for name in ("pass.jsonl", "review.jsonl", "fail.jsonl"):
        for row, _ in iter_jsonl(out / name):
            if isinstance(row, dict) and row.get("id"):
                rows[row["id"]] = row
    return rows


def decide(score: dict, sem: dict | None, python_hash: str, semantic_hash: str,
           sem_last: dict | None, raw_hash: str) -> tuple[str, list[str]]:
    reasons: list[str] = []
    # 1 Python重大違反
    if score.get("red_flags"):
        return "FAIL", ["python_red_flags:" + ",".join(score["red_flags"][:5])]
    # 2 Semantic fail（保存済み判定をそのまま使用）
    if sem and sem.get("final_verdict") == "fail":
        return "FAIL", ["semantic_final_verdict=fail"]
    # 3 Python / Semantic のカテゴリ不一致（再計算しない）
    if sem:
        pcats = {c for c in (sem.get("python_categories") or [])}
        lcats = {c for c, v in (sem.get("llm_labels") or {}).items()
                 if c != "review" and isinstance(v, (int, float)) and v >= 0.5}
        if pcats != lcats:
            reasons.append("python_semantic_category_mismatch")
    # 4 未判定 / ハッシュ不一致
    if not sem or not sem.get("judged"):
        reasons.append("unjudged_or_missing_semantic")
    if not sem_last or sem_last.get("content_hash") != semantic_hash:
        reasons.append("semantic_hash_mismatch")
    if raw_hash and raw_hash != python_hash:
        reasons.append("python_hash_mismatch")
    # 5 needs_human_review
    if score.get("needs_human_review"):
        reasons.append("python_needs_human_review")
    if reasons:
        return "REVIEW", reasons
    # 6 両方PASSかつハッシュ一致
    if score.get("verdict") == "pass" and sem and sem.get("final_verdict") == "pass":
        return "PASS", ["python_verdict=pass", "semantic_final_verdict=pass", "hashes_match"]
    # 7 その他
    return "REVIEW", ["fallback"]


def cmd_classify(args) -> int:
    root = Path(args.root)
    errors = validate_root(root, args.allow_root)
    for p in (args.scores, args.semantic, args.manifest):
        if any(tok in str(p).lower() for tok in FORBIDDEN_PATH_TOKENS):
            errors.append(f"評価データと疑われる入力ファイルを拒否: {p}")
    if errors:
        for e in errors:
            print(e, file=sys.stderr)
        return 2

    out = Path(args.out_dir)
    out.mkdir(parents=True, exist_ok=True)
    outputs = [out / n for n in ("pass.jsonl", "review.jsonl", "fail.jsonl")]
    if any(p.exists() for p in outputs) and not args.resume:
        print("既存分類ファイルがあります。--resume を指定してください。", file=sys.stderr)
        return 2

    scores, skipped_scores, duplicate_score_ids = load_scores(args.scores)
    semantic, skipped_sem, duplicate_sem_ids = load_scores(args.semantic)
    manifest = qm.Manifest(args.manifest)
    previous = load_previous(out) if args.resume else {}
    raw_index = load_raw_index(root)

    buckets: dict[str, list[dict]] = {"PASS": [], "REVIEW": [], "FAIL": []}
    seen: set[str] = set()
    duplicate_ids: list[str] = list(dict.fromkeys(duplicate_score_ids + duplicate_sem_ids))
    invalid_ids: list[str] = []
    reused = recomputed = 0
    mismatch_rows: list[dict] = []

    for rid, score in scores.items():
        if rid in seen:
            duplicate_ids.append(rid)
            continue
        seen.add(rid)
        if not ID_RE.match(rid):
            invalid_ids.append(rid)
            buckets["REVIEW"].append({
                "id": rid, "verdict": "REVIEW", "reasons": ["invalid_id_prefix"],
                "python_hash": score.get("content_hash", ""), "semantic_hash": "",
                "integrated_hash": "", "verifier_version": VERSION,
            })
            continue

        sem = semantic.get(rid)
        sem_last = manifest.latest("semantic_ingest", rid)
        semantic_hash = sem_last.get("content_hash", "") if sem_last else ""
        python_hash = score.get("content_hash", "")
        integrated = qm.content_hash(python_hash, semantic_hash, VERSION)
        raw_hash = qs.content_hash(raw_index[rid]) if rid in raw_index else ""

        last = manifest.latest("final_classify", rid)
        if (args.resume and last and last.get("content_hash") == integrated
                and last.get("verifier_version") == VERSION and rid in previous):
            row = previous[rid]
            reused += 1
        else:
            verdict, reasons = decide(score, sem, python_hash, semantic_hash, sem_last, raw_hash)
            row = {
                "id": rid,
                "category": score.get("category", ""),
                "task": score.get("task", ""),
                "verdict": verdict,
                "reasons": reasons,
                "python_hash": python_hash,
                "semantic_hash": semantic_hash,
                "integrated_hash": integrated,
                "python_verdict": score.get("verdict"),
                "semantic_final_verdict": sem.get("final_verdict") if sem else None,
                "verifier_version": VERSION,
            }
            manifest.append("final_classify", rid, integrated, VERSION, verdict)
            recomputed += 1
        buckets[row["verdict"]].append(row)
        if row["verdict"] != "PASS":
            mismatch_rows.append({"id": rid, "verdict": row["verdict"], "reasons": row.get("reasons", [])})

    # 監査: 件数・ID形式・重複
    all_rows = [r for rows in buckets.values() for r in rows]
    audit = {
        "total": len(all_rows),
        "counts": {k: len(v) for k, v in buckets.items()},
        "duplicate_input_ids": duplicate_ids,
        "invalid_id_prefix": invalid_ids,
        "unique_output_ids": len({r["id"] for r in all_rows}),
        "id_format_all_valid": all(ID_RE.match(r["id"]) for r in all_rows),
        "root": str(root),
        "root_allowed": str(root.resolve()) == str(ALLOWED_DEFAULT_ROOT.resolve()) or args.allow_root,
    }

    def write(path: Path, rows: list[dict]) -> None:
        tmp = path.with_suffix(".tmp")
        with tmp.open("w", encoding="utf-8") as fh:
            for row in rows:
                fh.write(json.dumps(row, ensure_ascii=False) + "\n")
            fh.flush()
            try:
                os.fsync(fh.fileno())
            except OSError:
                pass
        os.replace(tmp, path)

    for name, rows in (("pass.jsonl", buckets["PASS"]), ("review.jsonl", buckets["REVIEW"]),
                       ("fail.jsonl", buckets["FAIL"])):
        write(out / name, rows)

    metrics = {
        "verifier_version": VERSION,
        "classification_counts": audit["counts"],
        "audit": audit,
        "reused": reused, "recomputed": recomputed,
        "skipped_score_lines": skipped_scores, "skipped_semantic_lines": skipped_sem,
        "manifest_duplicate_ids": manifest.duplicate_ids("final_classify"),
        "manifest_skipped_lines": manifest.skipped,
        "review_or_fail_rows": mismatch_rows[:200],
    }
    (out / "classification_metrics.json").write_text(
        json.dumps(metrics, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"counts": audit["counts"], "reused": reused, "recomputed": recomputed,
                      "invalid_ids": len(invalid_ids), "duplicates": len(duplicate_ids)},
                     ensure_ascii=False, indent=2))
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--scores", type=Path, required=True)
    ap.add_argument("--semantic", type=Path, required=True)
    ap.add_argument("--manifest", type=Path, required=True)
    ap.add_argument("--root", type=Path, default=ALLOWED_DEFAULT_ROOT)
    ap.add_argument("--allow-root", action="store_true")
    ap.add_argument("--out-dir", type=Path, required=True)
    ap.add_argument("--resume", action="store_true")
    args = ap.parse_args()
    return int(cmd_classify(args))


if __name__ == "__main__":
    sys.exit(main())
