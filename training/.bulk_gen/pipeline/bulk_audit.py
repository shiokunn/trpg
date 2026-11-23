#!/usr/bin/env python3
"""Audit an immutable snapshot with the existing QA tool, then aggregate.

The existing auditor (training/tools/quality_system.py) is invoked unchanged:
    quality_system.py score --root <snapshot>/raw --out-dir <audit dir>

It only ever reads the snapshot directory, never the inbox. This script adds:
  * AGGREGATE.json  machine-readable aggregation + trends
  * REPORT.md       human-readable Japanese quality/trend report
  * audit/index.jsonl append-only audit run ledger

Usage:
  python3 pipeline/bulk_audit.py run --snapshot latest
  python3 pipeline/bulk_audit.py run --snapshot snap-0001-2026...
  python3 pipeline/bulk_audit.py trends
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from collections import Counter, defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import bulk_common as bc  # noqa: E402

AUDITOR = bc.TOOLS / "quality_system.py"

CODE_LABELS = {
    "SCHEMA_ERROR": "スキーマ違反", "UNKNOWN_ID": "存在しないID",
    "DICE_MISMATCH": "判定計算の不一致", "SECRET_LEAK": "秘密漏洩",
    "ENEMY_HP_CHANGE": "敵HPの変更", "FABRICATED_NUMBER": "数値の捏造",
    "NEGATION_INVERSION": "否定の反転", "CONDITION_INVERSION": "条件の反転",
    "OUTCOME_CONTRADICTION": "判定結果と描写の矛盾", "NEAR_DUPLICATE": "近似重複",
    "EXACT_DUPLICATE": "完全一致重複", "SIMPLIFIED_CHINESE": "簡体字混入",
    "OUTCOME_WEAK": "判定描写が弱い", "WARNING": "警告",
}


def resolve_snapshot(name: str) -> Path:
    if name == "latest":
        snap = bc.latest_snapshot()
        if snap is None:
            raise SystemExit("no snapshot found: run bulk_snapshot.py take first")
        return snap
    snap = bc.SNAPSHOTS / name
    if not snap.is_dir() or not (snap / "MANIFEST.json").exists():
        raise SystemExit(f"snapshot not found: {name}")
    return snap


def aggregate(snap: Path, audit_dir: Path, run: dict) -> dict:
    manifest = bc.load_manifest(snap)
    scores_path = audit_dir / "quality_scores.json"
    report = json.loads(scores_path.read_text(encoding="utf-8")) if scores_path.exists() else {}
    samples = report.get("samples", [])

    verdict_counts = dict(Counter(s.get("verdict", "?") for s in samples))
    red_flags = Counter(f for s in samples for f in s.get("red_flags", []))

    finding_counts: Counter = Counter()
    finding_severity: dict[str, Counter] = defaultdict(Counter)
    finding_examples: dict[str, list[dict]] = defaultdict(list)
    for s in samples:
        for f in s.get("findings", []):
            code = f.get("code", "?")
            finding_counts[code] += 1
            finding_severity[code][f.get("severity", "?")] += 1
            if len(finding_examples[code]) < 3:
                finding_examples[code].append({
                    "id": s.get("id"), "category": s.get("category"),
                    "message": f.get("message"), "evidence": str(f.get("evidence", ""))[:160],
                })

    by_category: dict[str, dict] = {}
    cat_groups: dict[str, list[dict]] = defaultdict(list)
    task_groups: dict[str, list[dict]] = defaultdict(list)
    for s in samples:
        cat_groups[s.get("category", "?")].append(s)
        task_groups[s.get("task", "?")].append(s)
    for cat, rows in sorted(cat_groups.items()):
        by_category[cat] = _group_stats(rows)
    by_task = {k: _group_stats(v) for k, v in sorted(task_groups.items())}

    totals = [s.get("total", 0) for s in samples]
    buckets = Counter()
    for t in totals:
        if t <= 40: buckets["0-40"] += 1
        elif t <= 60: buckets["41-60"] += 1
        elif t <= 70: buckets["61-70"] += 1
        elif t <= 85: buckets["71-85"] += 1
        else: buckets["86-100"] += 1

    top_issues = []
    for code, count in finding_counts.most_common(20):
        top_issues.append({
            "code": code, "label": CODE_LABELS.get(code, code), "count": count,
            "severity": dict(finding_severity[code]),
            "examples": finding_examples[code],
        })

    total_in_snapshot = manifest.get("total_records", 0)
    agg = {
        "snapshot_id": snap.name,
        "created_at": manifest.get("created_at"),
        "audited_at": bc.now(),
        "source_manifest": {
            "total_records": total_in_snapshot,
            "by_category": manifest.get("by_category"),
            "files": manifest.get("total_files"),
        },
        "audit": {
            "scored": len(samples),
            "coverage_pct": round(100 * len(samples) / total_in_snapshot, 1) if total_in_snapshot else 0,
            "verdict_counts": verdict_counts,
            "review_queue": report.get("review_queue", sum(1 for s in samples if s.get("needs_human_review"))),
            "average_score": report.get("average_score"),
            "threshold": report.get("threshold"),
            "verifier_version": report.get("verifier_version"),
        },
        "red_flag_counts": dict(red_flags),
        "finding_counts": dict(finding_counts),
        "score_distribution": dict(sorted(buckets.items())),
        "by_category": by_category,
        "by_task": by_task,
        "top_issues": top_issues,
        "run": run,
        "notes": [
            "監査入力はスナップショットの raw のみ。inbox の書き込み中ファイルは読んでいない。",
            "品質点はヒューリスティック推定であり人手レビューの代替ではない。",
        ],
    }
    return agg


def _group_stats(rows: list[dict]) -> dict:
    totals = [s.get("total", 0) for s in rows]
    verdicts = Counter(s.get("verdict", "?") for s in rows)
    return {
        "count": len(rows),
        "average_score": round(sum(totals) / len(totals), 1) if totals else 0,
        "verdict_counts": dict(verdicts),
        "red_flags": sum(len(s.get("red_flags", [])) for s in rows),
        "findings": sum(len(s.get("findings", [])) for s in rows),
    }


def render_report(agg: dict, prev: dict | None) -> str:
    a = agg["audit"]
    lines = [
        "# バルク生成 品質監査レポート",
        "",
        f"- スナップショット: `{agg['snapshot_id']}`（{agg['created_at']}）",
        f"- 監査実行: {agg['audited_at']} / verifier `{a.get('verifier_version')}`",
        f"- 監査対象: 完成済みバッチのみを封印した**不変スナップショット**"
        f"（`snapshots/{agg['snapshot_id']}/raw`）。書き込み中の inbox は未読。",
        "",
        "## 生成量と监査覆盖率",
        "",
        f"- スナップショット件数: {agg['source_manifest']['total_records']}"
        f"（ファイル {agg['source_manifest']['files']}）",
        f"- 監査済み: {a['scored']} 件（覆盖率 {a['coverage_pct']}%）",
        f"- カテゴリ内訳: {json.dumps(agg['source_manifest']['by_category'], ensure_ascii=False)}",
        "",
        "## 品質サマリ",
        "",
        f"- 平均点: {a['average_score']} / 100（閾値 {a.get('threshold', 70)} 以下はレビュー対象）",
        f"- 判定内訳: {json.dumps(a['verdict_counts'], ensure_ascii=False)}",
        f"- 人手レビュー対象: {a['review_queue']} 件",
        f"- 点数分布: {json.dumps(agg['score_distribution'], ensure_ascii=False)}",
        "",
        "## 品質上の問題（検出コード別）",
        "",
    ]
    if agg["red_flag_counts"]:
        lines += ["### 赤線（重大違反・点数に関係なく不合格）", "",
                  "| コード | 件数 |", "|---|---:|"]
        for k, v in sorted(agg["red_flag_counts"].items(), key=lambda x: -x[1]):
            lines.append(f"| {CODE_LABELS.get(k, k)} (`{k}`) | {v} |")
        lines.append("")
    if agg["top_issues"]:
        lines += ["### 検出された指摘（上位）", "",
                  "| 指摘 | 件数 | 重大度 | 例 |", "|---|---:|---|---|"]
        for it in agg["top_issues"][:12]:
            sev = ", ".join(f"{k}:{v}" for k, v in it["severity"].items())
            ex = it["examples"][0] if it["examples"] else {}
            ex_s = f"{ex.get('id')}: {ex.get('message', '')}"[:70]
            lines.append(f"| {it['label']} (`{it['code']}`) | {it['count']} | {sev} | {ex_s} |")
        lines.append("")
    lines += ["## 生成傾向（カテゴリ／タスク）", "",
              "| 区分 | 件数 | 平均点 | pass / review / fail | 赤線 | 指摘 |",
              "|---|---:|---:|---|---:|---:|"]
    for name, st in sorted(agg["by_category"].items()):
        v = st["verdict_counts"]
        lines.append(f"| category:{name} | {st['count']} | {st['average_score']} | "
                     f"{v.get('pass', 0)} / {v.get('review', 0)} / {v.get('fail', 0)} | "
                     f"{st['red_flags']} | {st['findings']} |")
    for name, st in sorted(agg["by_task"].items()):
        v = st["verdict_counts"]
        lines.append(f"| task:{name} | {st['count']} | {st['average_score']} | "
                     f"{v.get('pass', 0)} / {v.get('review', 0)} / {v.get('fail', 0)} | "
                     f"{st['red_flags']} | {st['findings']} |")
    lines.append("")
    if prev:
        pa = prev["audit"]
        lines += ["## 前回スナップショットとの比較（生成傾向の推移）", "",
                  f"- 前回: `{prev['snapshot_id']}` / {pa['scored']} 件 / 平均 {pa['average_score']}",
                  f"- 今回: `{agg['snapshot_id']}` / {a['scored']} 件 / 平均 {a['average_score']}",
                  f"- 平均点の変化: {round((a['average_score'] or 0) - (pa['average_score'] or 0), 1)}",
                  f"- 赤線合計の変化: {sum(agg['red_flag_counts'].values()) - sum(prev['red_flag_counts'].values())}",
                  f"- 指摘合計の変化: {sum(agg['finding_counts'].values()) - sum(prev['finding_counts'].values())}",
                  ""]
    lines += ["## 運用上の注意", "",
              "- 最終的な正式データへの統合は、生成と全スナップショットの監査が完了した後に行う。",
              "- 本レポートは機械監査の結果であり、面白さ・自然さ・意味整合の最終判断は人手レビューが必要。",
              ""]
    return "\n".join(lines)


def load_all_aggregates() -> list[dict]:
    rows = []
    if bc.AUDIT.exists():
        for p in bc.AUDIT.glob("*/AGGREGATE.json"):
            try:
                rows.append(json.loads(p.read_text(encoding="utf-8")))
            except (OSError, json.JSONDecodeError):
                continue
    return sorted(rows, key=lambda r: r.get("snapshot_id", ""))


def cmd_run(args) -> int:
    snap = resolve_snapshot(args.snapshot)
    audit_dir = bc.AUDIT / snap.name
    audit_dir.mkdir(parents=True, exist_ok=True)
    cmd = [sys.executable, str(AUDITOR), "score",
           "--root", str(snap / "raw"), "--out-dir", str(audit_dir)]
    print("running:", " ".join(cmd), flush=True)
    proc = subprocess.run(cmd, capture_output=True, text=True)
    (audit_dir / "audit_run.json").write_text(json.dumps({
        "command": cmd, "returncode": proc.returncode,
        "stdout": proc.stdout, "stderr": proc.stderr[-4000:],
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(proc.stdout)
    if proc.returncode != 0:
        print(proc.stderr[-2000:], file=sys.stderr)
        return proc.returncode

    run = {"command": cmd, "returncode": proc.returncode, "audited_at": bc.now()}
    prev_list = [r for r in load_all_aggregates() if r["snapshot_id"] != snap.name]
    prev = prev_list[-1] if prev_list else None
    agg = aggregate(snap, audit_dir, run)
    bc.atomic_write_json(audit_dir / "AGGREGATE.json", agg)
    (audit_dir / "REPORT.md").write_text(render_report(agg, prev), encoding="utf-8")
    bc.append_jsonl(bc.AUDIT / "index.jsonl", {
        "snapshot_id": snap.name, "audited_at": agg["audited_at"],
        "scored": agg["audit"]["scored"],
        "verdict_counts": agg["audit"]["verdict_counts"],
        "average_score": agg["audit"]["average_score"],
        "red_flags": agg["red_flag_counts"],
    })
    print(json.dumps({
        "snapshot": snap.name, "scored": agg["audit"]["scored"],
        "verdict_counts": agg["audit"]["verdict_counts"],
        "average_score": agg["audit"]["average_score"],
        "red_flag_counts": agg["red_flag_counts"],
        "report": str(audit_dir / "REPORT.md"),
    }, ensure_ascii=False, indent=2))
    return 0


def cmd_trends(args) -> int:
    aggs = load_all_aggregates()
    rows = []
    for i, agg in enumerate(aggs):
        prev = aggs[i - 1] if i else None
        rows.append({
            "snapshot_id": agg["snapshot_id"],
            "records": agg["source_manifest"]["total_records"],
            "scored": agg["audit"]["scored"],
            "average_score": agg["audit"]["average_score"],
            "verdict_counts": agg["audit"]["verdict_counts"],
            "red_flag_total": sum(agg["red_flag_counts"].values()),
            "finding_total": sum(agg["finding_counts"].values()),
            "delta_score": (round(agg["audit"]["average_score"] - prev["audit"]["average_score"], 1)
                            if prev else None),
        })
    out = {"generated_at": bc.now(), "snapshots": rows}
    bc.write_json(bc.AUDIT / "TRENDS.json", out)
    md = ["# 監査結果の推移（生成傾向）", "",
          "| スナップショット | 件数 | 平均点 | Δ点 | pass/review/fail | 赤線 | 指摘 |",
          "|---|---:|---:|---:|---|---:|---:|"]
    for r in rows:
        v = r["verdict_counts"]
        md.append(f"| `{r['snapshot_id']}` | {r['records']} | {r['average_score']} | "
                  f"{r['delta_score'] if r['delta_score'] is not None else '-'} | "
                  f"{v.get('pass', 0)}/{v.get('review', 0)}/{v.get('fail', 0)} | "
                  f"{r['red_flag_total']} | {r['finding_total']} |")
    md.append("")
    (bc.AUDIT / "TRENDS.md").write_text("\n".join(md), encoding="utf-8")
    print(json.dumps(out, ensure_ascii=False, indent=2))
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    sub = ap.add_subparsers(dest="command", required=True)
    r = sub.add_parser("run"); r.add_argument("--snapshot", default="latest")
    r.set_defaults(func=cmd_run)
    t = sub.add_parser("trends"); t.set_defaults(func=cmd_trends)
    args = ap.parse_args()
    return int(args.func(args))


if __name__ == "__main__":
    sys.exit(main())
