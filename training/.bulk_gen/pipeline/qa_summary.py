#!/usr/bin/env python3
"""Summarise all completed wave audits (counts, PASS rate, problematic IDs)."""

from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import bulk_common as bc  # noqa: E402


def main() -> int:
    base = bc.BASE / "qa_runs"
    waves = []
    for p in sorted(base.glob("wave*/AUDIT_REPORT.json")):
        r = json.loads(p.read_text(encoding="utf-8"))
        waves.append({
            "wave": p.parent.name,
            "snapshot": r["source_snapshot"],
            "total": r["total"],
            "PASS": r["counts"]["PASS"],
            "REVIEW": r["counts"]["REVIEW"],
            "FAIL": r["counts"]["FAIL"],
            "pass_rate_pct": r["pass_rate_pct"],
            "verify_errors": r["verify_sft_errors"],
            "problem_ids": [x["original_id"] for x in r["problematic_ids"]],
        })
    total = sum(w["total"] for w in waves)
    passed = sum(w["PASS"] for w in waves)
    out = {
        "generated_at": bc.now(),
        "target_records": json.loads((bc.BASE / "config.json").read_text(encoding="utf-8"))["target_records"],
        "waves": waves,
        "total_audited": total,
        "total_pass": passed,
        "overall_pass_rate_pct": round(100 * passed / total, 1) if total else 0,
        "total_non_pass": total - passed,
        "verify_errors_total": sum(w["verify_errors"] for w in waves),
        "official_data_touched": False,
    }
    bc.write_json(base / "SUMMARY.json", out)
    md = ["# 全Wave監査サマリ", "",
          f"- 監査済み合計: {total} / PASS: {passed} ({out['overall_pass_rate_pct']}%)",
          f"- 目標: {out['target_records']} / 未達: {max(0, out['target_records'] - total)}",
          f"- 形式検証エラー合計: {out['verify_errors_total']}", "",
          "| Wave | スナップショット | 件数 | PASS | REVIEW | FAIL | PASS率 |",
          "|---|---|---:|---:|---:|---:|---:|"]
    for w in waves:
        md.append(f"| {w['wave']} | `{w['snapshot']}` | {w['total']} | {w['PASS']} | "
                  f"{w['REVIEW']} | {w['FAIL']} | {w['pass_rate_pct']}% |")
    md += ["", "## 問題ID（全Wave）", ""]
    ids = [f"{w['wave']}: {', '.join(w['problem_ids'])}" for w in waves if w["problem_ids"]]
    md += [f"- {x}" for x in ids] if ids else ["- なし"]
    md.append("")
    (base / "SUMMARY.md").write_text("\n".join(md), encoding="utf-8")
    print(json.dumps({k: out[k] for k in ("total_audited", "total_pass", "overall_pass_rate_pct",
                                           "total_non_pass", "verify_errors_total")},
                     ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
