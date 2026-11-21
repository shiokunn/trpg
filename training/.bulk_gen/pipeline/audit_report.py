#!/usr/bin/env python3
"""Aggregate classify_qa + verify_sft_outputs results into an audit report.

Records counts, PASS rate, problematic IDs (mapped back to original wave IDs)
and detection reasons. Output stays inside the isolated audit run directory.

Usage:
  python3 audit_report.py --run-dir ../audit_runs/wave1
"""

from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent
import sys  # noqa: E402
sys.path.insert(0, str(HERE))
import bulk_common as bc  # noqa: E402


def load_jsonl(path: Path) -> list[dict]:
    if not path.exists():
        return []
    return [json.loads(l) for l in path.open(encoding="utf-8") if l.strip()]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--run-dir", type=Path, required=True)
    args = ap.parse_args()
    run = Path(args.run_dir)

    metrics = json.loads((run / "classified" / "classification_metrics.json").read_text(encoding="utf-8"))
    verify = json.loads((run / "verify_sft_outputs.json").read_text(encoding="utf-8"))
    isolated = json.loads((run / "sft_isolated" / "isolated_sft_report.json").read_text(encoding="utf-8"))
    id_map = json.loads((run / "id_map.json").read_text(encoding="utf-8"))["id_map"]
    reverse = {v: k for k, v in id_map.items()}

    rows = {v: load_jsonl(run / "classified" / f"{v.lower()}.jsonl") for v in ("PASS", "REVIEW", "FAIL")}
    counts = {k: len(v) for k, v in rows.items()}
    total = sum(counts.values())

    problems = []
    for verdict in ("REVIEW", "FAIL"):
        for r in rows[verdict]:
            problems.append({
                "audit_id": r["id"],
                "original_id": reverse.get(r["id"], r["id"]),
                "verdict": verdict,
                "reasons": r.get("reasons", []),
                "python_verdict": r.get("python_verdict"),
                "semantic_final_verdict": r.get("semantic_final_verdict"),
                "python_red_flags": r.get("python_red_flags", []),
                "llm_labels": r.get("llm_labels", {}),
            })

    reason_counts = Counter(reason for p in problems for reason in p["reasons"])
    report = {
        "run_dir": str(run),
        "source_snapshot": json.loads((run / "id_map.json").read_text(encoding="utf-8"))["source_snapshot"],
        "generated_at": bc.now(),
        "counts": counts,
        "total": total,
        "pass_rate_pct": round(100 * counts["PASS"] / total, 1) if total else 0,
        "non_pass_count": counts["REVIEW"] + counts["FAIL"],
        "problematic_ids": problems,
        "reason_counts": dict(reason_counts.most_common()),
        "id_format_all_valid": metrics["audit"]["id_format_all_valid"],
        "duplicate_input_ids": metrics["audit"]["duplicate_input_ids"],
        "invalid_id_prefix": metrics["audit"]["invalid_id_prefix"],
        "manifest_duplicate_ids": metrics.get("manifest_duplicate_ids", []),
        "verify_sft_errors": verify["errors"],
        "verify_sft_error_samples": verify.get("error_samples", []),
        "verify_sft_files": verify["files"],
        "isolated_sft": isolated,
        "conclusion": (
            "PASS品のみ隔離SFTに含まれ、形式検証エラー0。正式データへの統合は全Wave監査完了後。"
            if verify["errors"] == 0 and isolated["written_ids_all_in_pass"] and not isolated["pass_ids_not_found_in_raw"]
            else "要確認: 隔離SFTと分類結果に不整合があります。"
        ),
    }
    bc.write_json(run / "AUDIT_REPORT.json", report)

    md = [
        "# 監査レポート（classify_qa + verify_sft_outputs）",
        "",
        f"- 対象スナップショット: `{report['source_snapshot']}`",
        f"- 総件数: {total} / PASS: {counts['PASS']} / REVIEW: {counts['REVIEW']} / FAIL: {counts['FAIL']}",
        f"- PASS率: {report['pass_rate_pct']}%",
        f"- ID形式: {'正常' if report['id_format_all_valid'] else '不正'} / 重複: {len(report['duplicate_input_ids'])}",
        f"- 隔離SFT形式検証エラー: {verify['errors']}",
        f"- 隔離SFT行数: {isolated['rows_written']}（PASS ID {isolated['pass_ids']} と一致: {isolated['written_ids_all_in_pass']}）",
        "",
        "## 検出理由（REVIEW/FAIL）",
        "",
    ]
    if reason_counts:
        for reason, n in reason_counts.most_common():
            md.append(f"- `{reason}`: {n}件")
    else:
        md.append("- なし")
    md += ["", "## 問題のあるID", ""]
    if problems:
        md += ["| 元ID | 監査ID | 判定 | 理由 |", "|---|---|---|---|"]
        for p in problems[:80]:
            md.append(f"| {p['original_id']} | {p['audit_id']} | {p['verdict']} | "
                      f"{', '.join(p['reasons'])} |")
    else:
        md.append("- なし")
    md += ["", f"## 結論", "", report["conclusion"], ""]
    (run / "AUDIT_REPORT.md").write_text("\n".join(md), encoding="utf-8")
    print(json.dumps({
        "total": total, "counts": counts, "pass_rate_pct": report["pass_rate_pct"],
        "problem_count": len(problems), "verify_errors": verify["errors"],
        "report": str(run / "AUDIT_REPORT.md"),
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
