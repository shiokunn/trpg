#!/usr/bin/env python3
"""Score training samples with training/specs/DATASET_QUALITY_SCORING.md.

Read-only. It never modifies, moves or deletes raw data or derived artefacts.
Outputs a JSON report and a CSV report to a directory given by --out-dir.

Machine items (55 pts) are deterministic. Human items (45 pts) are heuristic
proxies only and are always marked provisional: a program cannot replace the
human judgement required by the scoring definition.

Usage:
  python3 training/tools/score_dataset_quality.py --limit 8 \
      --out-dir /tmp/quality-test
  python3 training/tools/score_dataset_quality.py \
      --out-dir training/experiments/quality_scoring
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(HERE))

import build_dataset as bd  # noqa: E402
import check_simplified_chinese as csc  # noqa: E402
import schema_lib  # noqa: E402

REVIEW_THRESHOLD = 70

# --------------------------------------------------------------- utilities --

NUMBER_RE = re.compile(r"\d+|[一二三四五六七八九十百千万]+")
JAPANESE_SENTENCE_END = "。！？」』"


def all_strings(obj, prefix=""):
    if isinstance(obj, dict):
        for k, v in obj.items():
            yield from all_strings(v, f"{prefix}.{k}" if prefix else str(k))
    elif isinstance(obj, list):
        for i, v in enumerate(obj):
            yield from all_strings(v, f"{prefix}[{i}]")
    elif isinstance(obj, str):
        yield prefix, obj


def known_ids(context: dict) -> set[str]:
    ids: set[str] = set()
    for n in context.get("npcs", []) or []:
        if isinstance(n, dict) and n.get("id"):
            ids.add(n["id"])
    for q in context.get("quests", []) or []:
        if isinstance(q, dict) and q.get("id"):
            ids.add(q["id"])
    for l in context.get("locations", []) or []:
        if isinstance(l, dict) and l.get("id"):
            ids.add(l["id"])
    ch = context.get("character") or {}
    for it in ch.get("inventory", []) or []:
        if isinstance(it, dict) and it.get("id"):
            ids.add(it["id"])
    for sk in ch.get("skills", []) or []:
        if isinstance(sk, dict) and sk.get("id"):
            ids.add(sk["id"])
    return ids


def effective_die(check: dict) -> int | None:
    dice = check.get("dice") or []
    if not dice or not all(isinstance(d, int) for d in dice):
        return None
    mode = check.get("rollMode", "NORMAL")
    if mode == "NORMAL":
        return dice[0] if len(dice) == 1 else None
    if mode == "ADVANTAGE":
        return max(dice) if len(dice) == 2 else None
    if mode == "DISADVANTAGE":
        return min(dice) if len(dice) == 2 else None
    return None


def outcome_for(total: int, difficulty: int) -> str:
    if total >= difficulty + 3:
        return "CRITICAL_SUCCESS"
    if total >= difficulty:
        return "SUCCESS"
    if total >= difficulty - 2:
        return "PARTIAL_SUCCESS"
    return "FAILURE"


def simplified_hits(text: str) -> list[str]:
    return [
        ch for ch in text
        if ch in csc.SIMPLIFIED_ONLY and csc.is_finding(ch, text, text.index(ch))
    ]


# ------------------------------------------------------------------ loading --

def load_all(root: Path | None = None):
    records = []
    base = root if root is not None else ROOT / "raw"
    for path in sorted(base.rglob("*.jsonl")):
        for lineno, line in enumerate(path.open(encoding="utf-8"), 1):
            if not line.strip():
                continue
            try:
                records.append(json.loads(line))
            except json.JSONDecodeError as exc:
                records.append({
                    "id": f"PARSE_ERROR:{path.name}:{lineno}",
                    "category": "?", "task": "?", "group": "?", "output": {},
                    "_parse_error": str(exc),
                })
    return records


def duplicate_maps(records):
    exact: dict[str, str] = {}
    exact_dupe: dict[str, str] = {}
    for r in records:
        if r.get("_parse_error"):
            continue
        key = hashlib.sha256(json.dumps({
            "task": r["task"],
            "input": [r.get("context"), r.get("action"), r.get("request")],
            "output": r["output"],
        }, ensure_ascii=False, sort_keys=True).encode()).hexdigest()
        if key in exact:
            exact_dupe[r["id"]] = exact[key]
        else:
            exact[key] = r["id"]

    near: dict[str, list[str]] = defaultdict(list)
    by_task = defaultdict(list)
    for r in records:
        if r.get("_parse_error"):
            continue
        text = r["output"].get("narrative", "") if r["task"] == "gm_turn" else r.get("request", "")
        by_task[r["task"]].append({"id": r["id"], "text": text})
    for items in by_task.values():
        if len(items) > 1:
            for a, b, score in bd.near_duplicates(items):
                near[b].append(f"{a} (Jaccard {score})")
    return exact_dupe, near


# ------------------------------------------------------------------ scoring --

class Score:
    def __init__(self, rec: dict):
        self.rec = rec
        self.machine = 0
        self.human = 0
        self.reasons: list[str] = []
        self.red: list[str] = []

    def deduct(self, bucket: str, amount: int, reason: str) -> None:
        if bucket == "machine":
            self.machine = max(0, self.machine - amount)
        else:
            self.human = max(0, self.human - amount)
        self.reasons.append(reason)

    def red_flag(self, code: str, reason: str) -> None:
        if code not in self.red:
            self.red.append(code)
        self.reasons.append(f"赤線 {code}: {reason}")


def score_machine(s: Score, exact_dupe, near) -> None:
    r = s.rec
    s.machine = 55

    # 項目1 JSON構文・学習形式 (10)
    if r.get("_parse_error"):
        s.red_flag("SCHEMA_ERROR", "JSON構文エラー")
        s.deduct("machine", 10, "JSON構文エラー")
        return
    envelope_problems, _ = bd.record_problems(r)
    if envelope_problems:
        s.red_flag("SCHEMA_ERROR", "封筒キー不足")
        s.deduct("machine", 10, f"封筒エラー: {envelope_problems[:3]}")
    else:
        try:
            messages = bd.to_messages(r)
            roles = [m["role"] for m in messages]
            if roles[0] != "system" or roles[-1] != "assistant":
                s.deduct("machine", 3, "messages の role 順序が不正")
            last = messages[-1]["content"]
            if last.startswith("```"):
                s.deduct("machine", 3, "コードフェンス混入")
            json.loads(last)
        except Exception as exc:
            s.red_flag("SCHEMA_ERROR", "messages 化に失敗")
            s.deduct("machine", 10, f"messages 化失敗: {exc}")

    # 項目2 スキーマ契約 (15)
    rep = schema_lib.validate_output(r["task"], r["output"], r.get("context"))
    if rep.errors:
        s.red_flag("SCHEMA_ERROR", "schema_lib error")
        s.deduct("machine", 15, f"スキーマ error: {rep.errors[:3]}")
    else:
        for w in rep.warnings:
            s.deduct("machine", 2, f"スキーマ warning: {w}")

    # 項目3 ID・参照整合 (10)
    ids = known_ids(r.get("context") or {}) if isinstance(r.get("context"), dict) else set()
    out = r["output"] if isinstance(r["output"], dict) else {}
    bad_refs = []
    if r["task"] == "gm_turn":
        for ch in out.get("stateChanges") or []:
            if isinstance(ch, dict) and ch.get("type") in (
                "ITEM_REMOVE", "NPC_RELATION_DELTA", "QUEST_OBJECTIVE_PROGRESS",
                "QUEST_STATUS", "LOCATION_CHANGE", "COMBAT_START"
            ):  # FLAG_SET の targetId は新規フラグ名なので実在ID检验の対象外
                if ch.get("targetId") not in ids:
                    bad_refs.append(ch["type"])
        for m in out.get("memories") or []:
            if isinstance(m, dict):
                for rid in m.get("relatedIds") or []:
                    if rid not in ids:
                        bad_refs.append("relatedIds")
        for m in out.get("npcMemories") or []:
            if isinstance(m, dict) and m.get("npcId") not in ids:
                bad_refs.append("npcId")
    elif r["task"] == "state_changes":
        # FLAG_SET の targetId は新しいフラグ名なので context の実在IDと照合しない
        for ch in out.get("stateChanges") or []:
            if (
                isinstance(ch, dict) and ch.get("targetId")
                and ch.get("type") != "FLAG_SET"
                and ch.get("targetId") not in ids
            ):
                bad_refs.append("stateChanges.targetId")
    elif r["task"] == "memories":
        for m in out.get("memories") or []:
            if isinstance(m, dict):
                for rid in m.get("relatedIds") or []:
                    if rid not in ids:
                        bad_refs.append("relatedIds")
    if bad_refs:
        s.red_flag("UNKNOWN_ID", "存在しないID参照")
        for bad in bad_refs[:4]:
            s.deduct("machine", 3, f"未知ID参照: {bad}")

    # 項目4 数値・判定・状態変更 (10)
    if r["task"] == "gm_turn":
        action = r.get("action") or {}
        check = action.get("check")
        if check:
            die = effective_die(check)
            if die is None:
                s.deduct("machine", 3, "出目数とrollModeが不一致")
            else:
                expected = die + (check.get("attributeValue") or 0) + (check.get("skillLevel") or 0)
                if expected != check.get("total"):
                    s.red_flag("DICE_MISMATCH", "total が判定式と不一致")
                    s.deduct("machine", 3, f"total不一致: 期待{expected} 記録{check.get('total')}")
                exp_outcome = outcome_for(check["total"], check["difficulty"])
                if exp_outcome != check.get("outcome"):
                    s.deduct("machine", 3, f"outcome不一致: 期待{exp_outcome}")
        for ch in out.get("stateChanges") or []:
            if not isinstance(ch, dict):
                continue
            if ch.get("type") == "ITEM_ADD" and "id" in (ch.get("item") or {}):
                s.deduct("machine", 3, "ITEM_ADD に id を付与")
            if ch.get("type") in ("HP_DELTA", "XP_DELTA", "NPC_RELATION_DELTA") and ch.get("amount") == 0:
                s.deduct("machine", 3, f"{ch['type']} の amount が0")
    elif r["task"] in ("items", "character_draft"):
        pass  # schema_lib が範囲を検証済み
    if any(c.get("type") == "ITEM_ADD" and "id" in (c.get("item") or {})
           for c in (out.get("stateChanges") or []) if isinstance(c, dict)):
        s.deduct("machine", 3, "ITEM_ADD に id を付与")

    # 項目5 重複・多様性 (5)
    if r["id"] in exact_dupe:
        s.deduct("machine", 5, f"完全一致重複: {exact_dupe[r['id']]}")
    for pair in near.get(r["id"], []):
        s.deduct("machine", 3, f"近似重複: {pair}")

    # 項目6 簡体字・保護対象 (5)
    hits = 0
    for _path, text in all_strings(r):
        for ch in text:
            if ch in csc.SIMPLIFIED_ONLY and csc.is_finding(ch, text, text.index(ch)):
                hits += 1
    for _ in range(min(hits, 5)):
        s.deduct("machine", 1, f"簡体字検出: {hits}件（出現回数）")


def score_human(s: Score) -> None:
    r = s.rec
    out = r["output"] if isinstance(r["output"], dict) else {}
    s.human = 45

    # 項目7 指示への忠実性 (15) — 機械補助推定
    if r["task"] == "gm_turn":
        narrative = out.get("narrative", "")
        action_text = (r.get("action") or {}).get("text", "")
        if len(narrative) < 80:
            s.deduct("human", 4, "narrative が短すぎる（目安80文字未満）")
        if action_text and action_text not in narrative and not any(
            ch in narrative for ch in re.findall(r"[^\s、。]{3,}", action_text)[:2]
        ):
            s.deduct("human", 3, "行動との関連が読み取りにくい")
        check = (r.get("action") or {}).get("check")
        if check:
            outcome = check.get("outcome", "")
            keyword = {
                "FAILURE": ("失敗", "できない", "ならなかった", "届かなかった"),
                "SUCCESS": ("成功", "できた", "見つけた", "通った"),
                "PARTIAL_SUCCESS": ("一部", "半分", "途中", "焦げ"),
                "CRITICAL_SUCCESS": ("大成功", "完璧", "見事"),
            }.get(outcome, ())
            if keyword and not any(k in narrative for k in keyword):
                s.deduct("human", 3, f"判定結果 {outcome} の描写が見当たらない")
    elif r["task"] == "world_json":
        request = r.get("request", "")
        m = re.search(r"キーワード:\s*(.+)", request)
        if m:
            words = [w.strip() for w in m.group(1).split(",") if w.strip()]
            setting = out.get("setting", "") + out.get("genre", "")
            missing = [w for w in words if w not in setting]
            if missing:
                s.deduct("human", 4, f"request のキーワード未反映: {missing}")
    elif r["category"] == "extraction":
        request = r.get("request", "")
        out_text = " ".join(t for _p, t in all_strings(out))
        req_nums, out_nums = set(NUMBER_RE.findall(request)), set(NUMBER_RE.findall(out_text))
        if req_nums and not (req_nums & out_nums):
            s.deduct("human", 4, "request の数値が出力に反映されていない")

    # 項目8 情報の正確性 (15) — 機械補助推定
    if r["task"] == "gm_turn":
        narrative = out.get("narrative", "")
        secrets = ((r.get("context") or {}).get("world") or {}).get("secrets") or []
        for secret in secrets:
            if isinstance(secret, str) and len(secret) >= 10 and secret in narrative:
                s.red_flag("SECRET_LEAK", "GM専用の秘密が本文に出現")
                s.deduct("human", 10, "GM秘密の直接開示")
    elif r["category"] == "extraction":
        request = r.get("request", "")
        out_text = " ".join(t for _p, t in all_strings(out))
        req_nums = set(NUMBER_RE.findall(request))
        extra = set(NUMBER_RE.findall(out_text)) - req_nums
        if extra:
            s.deduct("human", 3, f"request に無い数値: {sorted(extra)}")
        if any(w in request for w in ("かもしれない", "らしい", "気がする")):
            for m in out.get("memories") or []:
                if isinstance(m, dict) and m.get("isConfirmed") is True:
                    s.deduct("human", 2, "不確実な記述が isConfirmed:true")

    # 項目9 日本語品質 (10) — lint補助推定
    for _path, text in all_strings(r):
        if not text or _path.startswith(("notes", "id", "category", "task", "group")):
            continue
        letters = [w for w in re.findall(r"[A-Za-z]{3,}", text) if w.lower() != "trust"]
        if letters:
            s.deduct("human", 1, f"日本語文中の英字: {letters[:2]}")
            break
    narrative = out.get("narrative", "")
    if "！" in narrative:
        s.deduct("human", 1, "文末に！")
    if narrative and not narrative.rstrip().endswith(JAPANESE_SENTENCE_END):
        s.deduct("human", 2, "文末が自然でない")

    # 項目10 TRPG整合 (5) — 機械補助推定
    if r["task"] == "gm_turn":
        ctx = r.get("context") or {}
        combat_npcs = {n.get("id") for n in ctx.get("npcs", []) if isinstance(n, dict) and n.get("combat")}
        for ch in out.get("stateChanges") or []:
            if isinstance(ch, dict) and ch.get("type") == "HP_DELTA":
                if ch.get("targetId") in combat_npcs:
                    s.red_flag("ENEMY_HP_CHANGE", "敵HPを変更")
                    s.deduct("human", 5, "HP_DELTA が敵NPCを対象")
    elif r["task"] == "world_json":
        for n in (out.get("opening") or {}).get("npcs", []) or []:
            combat = n.get("combat") if isinstance(n, dict) else None
            if isinstance(combat, dict) and combat.get("hp", 10) > 30:
                s.deduct("human", 2, "combat.hp が推奨上限30を超過（受理は999）")


def score_record(rec, exact_dupe, near) -> dict:
    s = Score(rec)
    score_machine(s, exact_dupe, near)
    score_human(s)
    total = s.machine + s.human
    needs = total <= REVIEW_THRESHOLD or bool(s.red)
    return {
        "id": rec["id"],
        "category": rec.get("category", "?"),
        "task": rec.get("task", "?"),
        "group": rec.get("group", "?"),
        "score": total,
        "machine_score": s.machine,
        "human_score_provisional": s.human,
        "red_flags": s.red,
        "needs_human_review": needs,
        "reasons": s.reasons,
        "human_items_provisional": True,
        "note": "人手項目は機械推定であり最終確認が必要。スキーマ合格は文章品質を意味しない。",
    }


# -------------------------------------------------------------------- main --

def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--limit", type=int, default=0, help="先頭N件のみ採点（動作確認用）")
    ap.add_argument("--root", type=Path, default=None,
                    help="読み取り専用のJSONLを置くディレクトリ（テスト用。省略時は training/raw）")
    ap.add_argument("--out-dir", type=Path, required=True)
    ap.add_argument("--json-name", default="quality_scores.json")
    ap.add_argument("--csv-name", default="quality_scores.csv")
    args = ap.parse_args()

    records = load_all(args.root)
    if args.limit:
        records = records[: args.limit]
    exact_dupe, near = duplicate_maps(records)
    results = [score_record(r, exact_dupe, near) for r in records]

    args.out_dir.mkdir(parents=True, exist_ok=True)
    json_path = args.out_dir / args.json_name
    csv_path = args.out_dir / args.csv_name
    for p in (json_path, csv_path):
        if p.exists():
            print(f"出力先が既に存在します: {p}", file=sys.stderr)
            return 2

    report = {
        "scoring_definition": "training/specs/DATASET_QUALITY_SCORING.md",
        "threshold": REVIEW_THRESHOLD,
        "scored": len(results),
        "review_queue": sum(1 for r in results if r["needs_human_review"]),
        "average_score": round(sum(r["score"] for r in results) / len(results), 1) if results else 0,
        "red_flag_counts": dict(Counter(f for r in results for f in r["red_flags"])),
        "samples": results,
    }
    json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    with csv_path.open("w", encoding="utf-8", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["id", "category", "task", "group", "score", "machine_score",
                    "human_score_provisional", "needs_human_review", "red_flags", "reasons"])
        for r in results:
            w.writerow([
                r["id"], r["category"], r["task"], r["group"], r["score"],
                r["machine_score"], r["human_score_provisional"],
                int(r["needs_human_review"]), ";".join(r["red_flags"]),
                " | ".join(r["reasons"]),
            ])

    print(json.dumps({
        "scored": report["scored"],
        "review_queue": report["review_queue"],
        "average_score": report["average_score"],
        "red_flag_counts": report["red_flag_counts"],
        "json": str(json_path), "csv": str(csv_path),
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
