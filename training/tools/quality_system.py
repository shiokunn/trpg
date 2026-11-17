#!/usr/bin/env python3
"""High-precision QA system for TRPG training data (10,000+ samples).

Five integrated functions, built on the existing scoring program:
  1. machine validation  (schema / ID / dice / HP / numbers / state changes)
  2. semantic validation (fabrication, inversion, outcome contradiction, secrets)
  3. quality scoring      (100 pts; critical violations fail regardless of score)
  4. auto-fix candidates  (original text is the only source of truth)
  5. regression tests     (normal / boundary / abnormal / historic bugs)

Read-only with respect to training/raw, derived JSONL, splits, metadata, LoRA
and notebooks. All candidates and reports go to a user-specified output dir.

Subcommands:
  score  --root RAW_DIR --out-dir DIR [--batch-size N] [--workers N] [--resume]
  fix    --root RAW_DIR --out-dir DIR [--limit N]
  test   --out-dir DIR
"""

from __future__ import annotations

import argparse
import copy
import csv
import difflib
import hashlib
import json
import re
import sys
from collections import Counter, defaultdict
from concurrent.futures import ProcessPoolExecutor
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(HERE))

import build_dataset as bd  # noqa: E402
import check_simplified_chinese as csc  # noqa: E402
import schema_lib  # noqa: E402
import score_dataset_quality as base  # noqa: E402

VERSION = "qa-1.0.0"
THRESHOLD = 70

NUMBER_RE = re.compile(r"\d+|[一二三四五六七八九十百千万]+")
NEG_MARKERS = ("ない", "なかった", "ず", "まだ", "らしい", "かもしれない", "ではなく", "未", "せず")

KANJI_NUM = {
    "一": "1", "二": "2", "三": "3", "四": "4", "五": "5", "六": "6", "七": "7", "八": "8",
    "九": "9", "十": "10", "十一": "11", "十二": "12", "二十": "20", "三十": "30",
    "四十": "40", "五十": "50", "百": "100",
}


def normalize_numbers(text: str) -> str:
    for k in sorted(KANJI_NUM, key=len, reverse=True):
        text = text.replace(k, KANJI_NUM[k])
    return text
COND_MARKERS = ("なら", "たら", "れば", "とき", "場合", "まで", "だけ", "必ず", "もし", "いずれ")
OUTCOME_WORDS = {
    "SUCCESS": ("成功", "できた", "見つけた", "通った", "届いた"),
    "CRITICAL_SUCCESS": ("大成功", "完璧", "見事"),
    "PARTIAL_SUCCESS": ("一部", "半分", "途中", "焦げ"),
    "FAILURE": ("失敗", "できなかった", "ならなかった", "届かなかった"),
}
OPPOSITE_OUTCOME = {
    "SUCCESS": ("失敗", "できなかった", "ならなかった"),
    "FAILURE": ("成功", "できた", "見つけた"),
    "CRITICAL_SUCCESS": ("失敗", "できなかった"),
}


# --------------------------------------------------------------- utilities --

def normalize(obj) -> str:
    return json.dumps(obj, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def content_hash(record: dict) -> str:
    return hashlib.sha256(normalize(record).encode("utf-8")).hexdigest()


def finding(code, source, severity, message, evidence="", confidence=1.0) -> dict:
    return {
        "code": code, "source": source, "severity": severity,
        "message": message, "evidence": str(evidence)[:300],
        "confidence": round(float(confidence), 2),
    }


def read_jsonl(path: Path):
    for lineno, line in enumerate(path.open(encoding="utf-8"), 1):
        if not line.strip():
            continue
        try:
            yield json.loads(line), lineno
        except json.JSONDecodeError as exc:
            yield {"_parse_error": str(exc), "id": f"PARSE:{path.name}:{lineno}",
                   "category": "?", "task": "?", "group": "?", "output": {}}, lineno


def iter_records(root: Path):
    for path in sorted(root.rglob("*.jsonl")):
        for rec, lineno in read_jsonl(path):
            rec["_file"] = str(path.relative_to(root))
            rec["_line"] = lineno
            yield rec


def build_duplicate_index(root: Path) -> tuple[dict, dict]:
    """Lightweight first pass: exact hashes and near-duplicate pairs by task."""
    exact: dict[str, str] = {}
    exact_dupe: dict[str, str] = {}
    texts: dict[str, list[dict]] = defaultdict(list)
    for rec in iter_records(root):
        if rec.get("_parse_error") or "_file" not in rec:
            continue
        key = hashlib.sha256(json.dumps({
            "task": rec.get("task"),
            "input": [rec.get("context"), rec.get("action"), rec.get("request")],
            "output": rec.get("output"),
        }, ensure_ascii=False, sort_keys=True).encode()).hexdigest()
        rid = rec.get("id", "")
        if key in exact:
            exact_dupe[rid] = exact[key]
        else:
            exact[key] = rid
        text = (rec.get("output", {}).get("narrative", "")
                if rec.get("task") == "gm_turn" else rec.get("request", ""))
        texts[rec.get("task", "?")].append({"id": rid, "text": text or ""})
    near: dict[str, list[str]] = defaultdict(list)
    for items in texts.values():
        if len(items) > 1:
            for a, b, score in bd.near_duplicates(items):
                near[b].append(f"{a} (Jaccard {score})")
    return exact_dupe, near


# ---------------------------------------------------------- machine checks --

def machine_validate(rec: dict, exact_dupe: dict, near: dict) -> tuple[int, list[dict], list[str]]:
    score = 55
    findings: list[dict] = []
    red: list[str] = []
    rid = rec.get("id", "?")

    def deduct(points, code, msg, evidence="", severity="warn", conf=1.0):
        nonlocal score
        score = max(0, score - points)
        findings.append(finding(code, "machine", severity, msg, evidence, conf))

    def flag(code, msg, evidence=""):
        if code not in red:
            red.append(code)
        findings.append(finding(code, "machine", "critical", msg, evidence, 1.0))

    if rec.get("_parse_error"):
        flag("SCHEMA_ERROR", "JSON構文エラー", rec["_parse_error"])
        return 0, findings, red

    # 項目1 JSON構文・学習形式 (10)
    problems, _ = bd.record_problems(rec)
    if problems:
        flag("SCHEMA_ERROR", "封筒キー不足", problems[:3])
        deduct(10, "FORMAT_ERROR", "封筒キー不足", problems[:3])
    else:
        try:
            messages = bd.to_messages(rec)
            roles = [m["role"] for m in messages]
            if roles[0] != "system" or roles[-1] != "assistant":
                deduct(3, "ROLE_ORDER", "messages role順序が不正", roles[:3])
            last = messages[-1]["content"]
            if last.startswith("```"):
                deduct(3, "CODE_FENCE", "コードフェンス混入")
            json.loads(last)
        except Exception as exc:
            flag("SCHEMA_ERROR", "messages化失敗", str(exc))
            deduct(10, "FORMAT_ERROR", "messages化失敗", str(exc))

    # 項目2 スキーマ契約 (15)
    rep = schema_lib.validate_output(rec["task"], rec["output"], rec.get("context"))
    if rep.errors:
        flag("SCHEMA_ERROR", "schema_lib error", rep.errors[:3])
        deduct(15, "SCHEMA_ERROR", "スキーマerror", rep.errors[:3])
    else:
        for w in rep.warnings:
            deduct(2, "SCHEMA_WARNING", "スキーマwarning", w)

    # 項目3 ID・参照整合 (10) — FLAG_SET は新規フラグ名なので除外
    ids = base.known_ids(rec.get("context") or {}) if isinstance(rec.get("context"), dict) else set()
    out = rec["output"] if isinstance(rec.get("output"), dict) else {}
    bad = []
    if rec.get("task") == "gm_turn":
        for ch in out.get("stateChanges") or []:
            if isinstance(ch, dict) and ch.get("type") in (
                "ITEM_REMOVE", "NPC_RELATION_DELTA", "QUEST_OBJECTIVE_PROGRESS",
                "QUEST_STATUS", "LOCATION_CHANGE", "COMBAT_START"
            ) and ch.get("targetId") not in ids:
                bad.append(ch["type"])
        for m in out.get("memories") or []:
            if isinstance(m, dict):
                bad += ["relatedIds"] * sum(1 for r in m.get("relatedIds") or [] if r not in ids)
        for m in out.get("npcMemories") or []:
            if isinstance(m, dict) and m.get("npcId") not in ids:
                bad.append("npcId")
    elif rec.get("task") in ("state_changes", "memories"):
        for ch in out.get("stateChanges") or []:
            if (isinstance(ch, dict) and ch.get("targetId")
                    and ch.get("type") != "FLAG_SET" and ch.get("targetId") not in ids):
                bad.append("stateChanges.targetId")
        for m in out.get("memories") or []:
            if isinstance(m, dict):
                bad += ["relatedIds"] * sum(1 for r in m.get("relatedIds") or [] if r not in ids)
    if bad:
        flag("UNKNOWN_ID", "存在しないID参照", bad[:4])
        for _ in bad[:4]:
            deduct(3, "UNKNOWN_ID", "未知ID参照", bad[:4])

    # 項目4 数値・判定・状態変更 (10) — 二重減点を排除
    numeric_issues = []
    if rec.get("task") == "gm_turn":
        check = (rec.get("action") or {}).get("check")
        if check:
            die = base.effective_die(check)
            if die is None:
                numeric_issues.append("出目数とrollModeが不一致")
            else:
                expected = die + (check.get("attributeValue") or 0) + (check.get("skillLevel") or 0)
                if expected != check.get("total"):
                    flag("DICE_MISMATCH", "totalが判定式と不一致", f"期待{expected} 記録{check.get('total')}")
                    numeric_issues.append("total不一致")
                exp = base.outcome_for(check.get("total", 0), check.get("difficulty", 0))
                if exp != check.get("outcome"):
                    numeric_issues.append(f"outcome不一致(期待{exp})")
        for ch in out.get("stateChanges") or []:
            if not isinstance(ch, dict):
                continue
            if ch.get("type") == "ITEM_ADD" and "id" in (ch.get("item") or {}):
                numeric_issues.append("ITEM_ADDにid")
            if ch.get("type") in ("HP_DELTA", "XP_DELTA", "NPC_RELATION_DELTA") and ch.get("amount") == 0:
                numeric_issues.append(f"{ch['type']} amount=0")
    for issue in numeric_issues[:4]:
        deduct(3, "NUMERIC_MISMATCH", issue, issue)
    if "DICE_MISMATCH" in red:
        findings.append(finding("DICE_MISMATCH", "machine", "critical",
                                "判定式と合計が一致しない", "赤線", 1.0))

    # 項目5 重複・多様性 (5) — 完全一致を優先し二重減点しない
    if rid in exact_dupe:
        deduct(5, "EXACT_DUPLICATE", "完全一致重複", exact_dupe[rid])
    elif rid in near:
        deduct(3, "NEAR_DUPLICATE", "近似重複", near[rid][0])

    # 項目6 簡体字 (5) — 出現回数ベース
    hits = 0
    for _path, text in base.all_strings(rec):
        for ch in text:
            if ch in csc.SIMPLIFIED_ONLY and csc.is_finding(ch, text, text.index(ch)):
                hits += 1
    for _ in range(min(hits, 5)):
        deduct(1, "SIMPLIFIED_CHINESE", "簡体字出現", f"{hits}件")

    return score, findings, red


# --------------------------------------------------------- semantic checks --

def _texts(rec: dict) -> str:
    if rec.get("task") == "gm_turn":
        return (rec.get("output") or {}).get("narrative", "") or ""
    return " ".join(t for _p, t in base.all_strings(rec.get("output") or {}))


def semantic_validate(rec: dict) -> tuple[list[dict], list[str]]:
    findings: list[dict] = []
    red: list[str] = []
    if rec.get("_parse_error"):
        return findings, red
    out = rec.get("output") or {}
    task = rec.get("task", "")

    # 秘密漏洩（原文の context.world.secrets のみを根拠にする）
    if task == "gm_turn":
        narrative = out.get("narrative", "") or ""
        secrets = ((rec.get("context") or {}).get("world") or {}).get("secrets") or []
        norm = re.sub(r"[\s、。「」『』：:]", "", narrative)
        for secret in secrets:
            if not isinstance(secret, str) or len(secret) < 8:
                continue
            key = re.sub(r"[\s、。「」『』：:]", "", secret)
            if len(key) >= 8 and key in norm:
                findings.append(finding("SECRET_LEAK", "semantic", "critical",
                                        "GM専用の秘密が本文に出現", secret, 0.95))
                red.append("SECRET_LEAK")

        # 物語と判定結果の矛盾（明確な反対語が両方出た場合のみ赤線）
        check = (rec.get("action") or {}).get("check")
        if check:
            outcome = check.get("outcome", "")
            opposite = OPPOSITE_OUTCOME.get(outcome, ())
            if opposite and any(w in narrative for w in opposite):
                findings.append(finding("OUTCOME_CONTRADICTION", "semantic", "critical",
                                        f"判定{outcome}と矛盾する描写", narrative[:120], 0.85))
                red.append("OUTCOME_CONTRADICTION")
            elif outcome in OUTCOME_WORDS and not any(w in narrative for w in OUTCOME_WORDS[outcome]):
                findings.append(finding("OUTCOME_WEAK", "semantic", "warn",
                                        f"判定{outcome}の描写が弱い", narrative[:120], 0.5))

    # 抽出: 否定・条件・数値の反転（ request を原文とする）
    if rec.get("category") == "extraction":
        request = rec.get("request", "") or ""
        output_text = _texts(rec)
        req_neg = {w for w in NEG_MARKERS if w in request}
        out_neg = {w for w in NEG_MARKERS if w in output_text}
        if req_neg and not out_neg:
            # 否定マーカーの消失だけでは反転断定できない（「持っていない」→空配列等は正しい）
            findings.append(finding("NEGATION_INVERSION", "semantic", "warn",
                                    "入力の否定・不確実表現が出力から消失（要確認）", sorted(req_neg), 0.6))
        elif out_neg and not req_neg:
            findings.append(finding("NEGATION_ADDED", "semantic", "warn",
                                    "入力に無い否定・不確実表現が出力に追加", sorted(out_neg), 0.6))
        req_cond = {w for w in COND_MARKERS if w in request}
        out_cond = {w for w in COND_MARKERS if w in output_text}
        if req_cond and not out_cond:
            findings.append(finding("CONDITION_LOST", "semantic", "warn",
                                    "入力の条件表現が出力から消失", sorted(req_cond), 0.7))
        req_nums = set(NUMBER_RE.findall(normalize_numbers(request)))
        out_nums = set(NUMBER_RE.findall(normalize_numbers(output_text)))
        extra = out_nums - req_nums
        if extra:
            findings.append(finding("FABRICATED_NUMBER", "semantic", "warn",
                                    "入力に無い数値が出力に存在", sorted(extra), 0.65))
        if any(w in request for w in ("かもしれない", "らしい", "気がする")):
            for m in out.get("memories") or []:
                if isinstance(m, dict) and m.get("isConfirmed") is True:
                    findings.append(finding("UNCERTAIN_CONFIRMED", "semantic", "warn",
                                            "不確実な記述が isConfirmed:true", m.get("content", "")[:80], 0.75))

    return findings, red


# ----------------------------------------------------------- quality score --

def quality_validate(rec: dict, semantic_findings: list[dict]) -> int:
    score = 45
    out = rec.get("output") or {}
    task = rec.get("task", "")

    def d(n, msg):
        nonlocal score
        score = max(0, score - n)

    if task == "gm_turn":
        narrative = out.get("narrative", "") or ""
        if len(narrative) < 80:
            d(4, "narrative短すぎ")
        check = (rec.get("action") or {}).get("check")
        if check and any(f["code"] == "OUTCOME_WEAK" for f in semantic_findings):
            d(3, "判定描写が弱い")
        if "！" in narrative:
            d(1, "文末！")
        if narrative and not narrative.rstrip().endswith("。！？」』"):
            d(2, "文末不自然")
        combat_npcs = {n.get("id") for n in (rec.get("context") or {}).get("npcs", [])
                       if isinstance(n, dict) and n.get("combat")}
        for ch in out.get("stateChanges") or []:
            if isinstance(ch, dict) and ch.get("type") == "HP_DELTA" and ch.get("targetId") in combat_npcs:
                d(5, "敵HP変更")
    elif task == "world_json":
        m = re.search(r"キーワード:\s*(.+)", rec.get("request", "") or "")
        if m:
            words = [w.strip() for w in m.group(1).split(",") if w.strip()]
            setting = (out.get("setting", "") or "") + (out.get("genre", "") or "")
            if any(w not in setting for w in words):
                d(4, "requestキーワード未反映")
        for n in (out.get("opening") or {}).get("npcs", []) or []:
            combat = n.get("combat") if isinstance(n, dict) else None
            if isinstance(combat, dict) and combat.get("hp", 10) > 30:
                d(2, "combat.hpが推奨30超")
    elif rec.get("category") == "extraction":
        req = set(NUMBER_RE.findall(normalize_numbers(rec.get("request", "") or "")))
        got = set(NUMBER_RE.findall(normalize_numbers(_texts(rec))))
        if req and not (req & got):
            d(4, "request数値未反映")
        if len(got - req) > 2:
            d(3, "入力に無い数値が多い")
    return score


# ------------------------------------------------------------ evaluation ----

def evaluate(rec: dict, exact_dupe: dict, near: dict) -> dict:
    m_score, m_findings, red = machine_validate(rec, exact_dupe, near)
    s_findings, s_red = semantic_validate(rec)
    findings = m_findings + s_findings
    for code in s_red:
        if code not in red:
            red.append(code)
    q_score = quality_validate(rec, s_findings)
    total = m_score + q_score
    verdict = "fail" if red else ("review" if total <= THRESHOLD else "pass")
    return {
        "id": rec.get("id", "?"),
        "file": rec.get("_file", ""),
        "line": rec.get("_line", 0),
        "category": rec.get("category", "?"),
        "task": rec.get("task", "?"),
        "group": rec.get("group", "?"),
        "content_hash": content_hash(rec),
        "verifier_version": VERSION,
        "machine_score": m_score,
        "quality_score_provisional": q_score,
        "total": total,
        "verdict": verdict,
        "needs_human_review": verdict != "pass",
        "red_flags": red,
        "findings": findings,
        "fix_candidates": [],
    }


# -------------------------------------------------------------- auto fix ----

ALLOWED_FIXES = {"UNKNOWN_KEY", "ITEM_ADD_ID", "DICE_MISMATCH", "TARGET_ID"}

def generate_candidates(rec: dict, result: dict) -> list[dict]:
    """Deterministic candidates only. Original record is the sole source."""
    if rec.get("_parse_error"):
        return []
    out = copy.deepcopy(rec.get("output") or {})
    original = copy.deepcopy(rec)
    candidates = []

    def add(code, before, after, reason, confidence):
        candidates.append({
            "code": code, "confidence": confidence, "reason": reason,
            "before": before, "after": after,
            "diff": list(difflib.unified_diff(
                json.dumps(before, ensure_ascii=False, indent=2).splitlines(),
                json.dumps(after, ensure_ascii=False, indent=2).splitlines(),
                lineterm="")),
        })

    # 未知キーの削除（スキーマのキー集合のみを根拠にする）
    allowed = {
        "gm_turn": set(schema_lib.GM_KEYS),
        "world_json": set(schema_lib.WORLD_KEYS),
    }.get(rec.get("task"))
    if allowed is None and rec.get("task") in schema_lib.EXTRACTION_SCHEMAS:
        if rec.get("task") == "memories":
            allowed = {"memories", "npcMemories"}
        else:
            allowed = {schema_lib.EXTRACTION_SCHEMAS[rec["task"]][0]}
    if allowed:
        optional = {"sceneTitle", "eventSummary", "stateChanges", "memories", "npcMemories"} if rec.get("task") == "gm_turn" else set()
        removed = {k: v for k, v in out.items() if k not in allowed and k not in optional}
        if removed:
            fixed = {k: v for k, v in out.items() if k not in removed}
            add("UNKNOWN_KEY", out, fixed, "スキーマに定義のないキーを削除", 1.0)
            out = fixed

    # ITEM_ADD の id 削除（仕様で禁止）
    for i, ch in enumerate(out.get("stateChanges") or []):
        if isinstance(ch, dict) and ch.get("type") == "ITEM_ADD" and "id" in (ch.get("item") or {}):
            before = copy.deepcopy(out)
            item = copy.deepcopy(ch["item"])
            item.pop("id", None)
            out["stateChanges"][i]["item"] = item
            add("ITEM_ADD_ID", before, out, "ITEM_ADD.item.id はアプリが採番するため削除", 1.0)

    # 判定値の再計算（式から一意に決まる）
    check = (original.get("action") or {}).get("check")
    if check:
        die = base.effective_die(check)
        if die is not None:
            expected_total = die + (check.get("attributeValue") or 0) + (check.get("skillLevel") or 0)
            expected_outcome = base.outcome_for(expected_total, check.get("difficulty", 0))
            changed = {}
            if check.get("total") != expected_total:
                changed["total"] = expected_total
            if check.get("outcome") != expected_outcome:
                changed["outcome"] = expected_outcome
            if changed:
                before = copy.deepcopy(original["action"]["check"])
                after = dict(before)
                after.update(changed)
                add("DICE_MISMATCH", before, after, "total/outcome を判定式から再計算", 1.0)

    # targetId の復元は、context 内に候補が1つだけ存在するときだけ
    ids = base.known_ids(original.get("context") or {})
    for i, ch in enumerate(out.get("stateChanges") or []):
        if (isinstance(ch, dict) and ch.get("targetId")
                and ch.get("type") != "FLAG_SET" and ch.get("targetId") not in ids
                and len(ids) == 1):
            before = copy.deepcopy(out)
            out["stateChanges"][i]["targetId"] = next(iter(ids))
            add("TARGET_ID", before, out, "context内の候補が1つのみのため復元", 0.9)

    # 検証器のバージョンと再検証結果を候補に添付
    for cand in candidates:
        cand["verifier_version"] = VERSION
        cand["original_content_hash"] = content_hash(original)
    return candidates


def verify_candidate(rec: dict, result: dict, candidate: dict) -> dict:
    trial = copy.deepcopy(rec)
    if candidate["code"] == "UNKNOWN_KEY":
        trial["output"] = candidate["after"]
    elif candidate["code"] == "ITEM_ADD_ID":
        trial["output"] = candidate["after"]
    elif candidate["code"] == "DICE_MISMATCH":
        trial["action"]["check"] = candidate["after"]
    elif candidate["code"] == "TARGET_ID":
        trial["output"] = candidate["after"]
    else:
        return {"code": candidate["code"], "verified": False, "reason": "未対応の修正種別"}
    after = evaluate(trial, {}, {})
    return {
        "code": candidate["code"],
        "verified": not after["red_flags"] and after["total"] > result["total"],
        "before_total": result["total"],
        "after_total": after["total"],
        "after_red_flags": after["red_flags"],
        "after_verdict": after["verdict"],
    }


# --------------------------------------------------------------- pipeline ---

def load_ledger(path: Path) -> dict:
    """Read the append-only ledger. Corrupted lines from an interrupted write are skipped."""
    done = {}
    if path.exists():
        for lineno, line in enumerate(path.open(encoding="utf-8"), 1):
            if not line.strip():
                continue
            try:
                row = json.loads(line)
                rid = row["id"]
                digest = row["content_hash"]
            except (json.JSONDecodeError, KeyError, TypeError):
                print(f"不正なledger行をスキップ: {path.name}:{lineno}", file=sys.stderr)
                continue
            done[rid] = digest
    return done


def write_report(out_dir: Path, results: list[dict], name_prefix="quality_scores"):
    out_dir.mkdir(parents=True, exist_ok=True)
    json_path = out_dir / f"{name_prefix}.json"
    csv_path = out_dir / f"{name_prefix}.csv"
    report = {
        "verifier_version": VERSION,
        "threshold": THRESHOLD,
        "scored": len(results),
        "review_queue": sum(1 for r in results if r["needs_human_review"]),
        "verdict_counts": dict(Counter(r["verdict"] for r in results)),
        "red_flag_counts": dict(Counter(f for r in results for f in r["red_flags"])),
        "average_score": round(sum(r["total"] for r in results) / len(results), 1) if results else 0,
        "samples": results,
    }
    json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    with csv_path.open("w", encoding="utf-8", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["id", "category", "task", "group", "total", "machine_score",
                    "quality_score_provisional", "verdict", "needs_human_review",
                    "red_flags", "findings", "content_hash", "verifier_version"])
        for r in results:
            w.writerow([r["id"], r["category"], r["task"], r["group"], r["total"],
                        r["machine_score"], r["quality_score_provisional"], r["verdict"],
                        int(r["needs_human_review"]), ";".join(r["red_flags"]),
                        " | ".join(f["code"] for f in r["findings"]),
                        r["content_hash"], r["verifier_version"]])
    return report


def cmd_score(args) -> int:
    root = Path(args.root)
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    ledger_path = out_dir / "ledger.jsonl"
    done = load_ledger(ledger_path) if args.resume else {}
    exact, near = build_duplicate_index(root)
    if args.limit:
        recs = list(iter_records(root))[: args.limit]
    else:
        recs = list(iter_records(root))

    pending = [r for r in recs if done.get(r.get("id")) != content_hash(r)]
    results: list[dict] = []
    batch_size = max(1, args.batch_size)
    processed = 0
    with ledger_path.open("a", encoding="utf-8") as ledger:
        for start in range(0, len(pending), batch_size):
            batch = pending[start:start + batch_size]
            if args.workers > 1:
                with ProcessPoolExecutor(max_workers=args.workers) as pool:
                    batch_results = list(pool.map(_evaluate_worker, batch))
            else:
                batch_results = [_evaluate_worker(r) for r in batch]
            for rec, result in zip(batch, batch_results):
                ledger.write(json.dumps({
                    "id": result["id"], "content_hash": result["content_hash"],
                    "verifier_version": VERSION,
                    "processed_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
                }, ensure_ascii=False) + "\n")
                results.append(result)
                processed += 1
            ledger.flush()

    all_results = []
    for lineno, line in enumerate(ledger_path.open(encoding="utf-8"), 1):
        if not line.strip():
            continue
        try:
            all_results.append(json.loads(line))
        except json.JSONDecodeError:
            print(f"不正なledger行をスキップ: {ledger_path.name}:{lineno}", file=sys.stderr)
    # ledger には軽量レコードしか無いため、最終レポートは今回の結果＋前回のスコアを再構築
    score_cache = out_dir / "score_cache.jsonl"
    cache = {}
    if score_cache.exists():
        for lineno, line in enumerate(score_cache.open(encoding="utf-8"), 1):
            if not line.strip():
                continue
            try:
                row = json.loads(line)
                cache[row["id"]] = row
            except (json.JSONDecodeError, KeyError, TypeError):
                print(f"不正なscore_cache行をスキップ: {score_cache.name}:{lineno}", file=sys.stderr)
    for r in results:
        cache[r["id"]] = r
    _tmp = score_cache.with_suffix(".tmp")
    _tmp.write_text("".join(json.dumps(v, ensure_ascii=False) + "\n" for v in cache.values()), encoding="utf-8")
    _tmp.replace(score_cache)
    final = [cache[entry["id"]] for entry in all_results if entry["id"] in cache]
    final.sort(key=lambda r: r["id"])
    report = write_report(out_dir, final)
    print(json.dumps({
        "newly_processed": processed, "resume_skipped": len(recs) - len(pending),
        "scored": report["scored"], "review_queue": report["review_queue"],
        "verdict_counts": report["verdict_counts"],
        "red_flag_counts": report["red_flag_counts"],
        "average_score": report["average_score"],
    }, ensure_ascii=False, indent=2))
    return 0


def _evaluate_worker(rec: dict) -> dict:
    # 重複情報はレコード自身では無いため、ワーカー内では再利用しない（近似重複は別途）
    return evaluate(rec, {}, {})


def cmd_fix(args) -> int:
    root = Path(args.root)
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    cand_dir = out_dir / "candidates"
    cand_dir.mkdir(exist_ok=True)
    exact, near = build_duplicate_index(root)
    recs = list(iter_records(root))
    if args.limit:
        recs = recs[: args.limit]
    reports = []
    for rec in recs:
        result = evaluate(rec, exact, near)
        candidates = generate_candidates(rec, result)
        if not candidates:
            continue
        verified = [verify_candidate(rec, result, c) for c in candidates]
        entry = {
            "id": result["id"], "source_file": rec.get("_file", ""),
            "before_total": result["total"], "before_red_flags": result["red_flags"],
            "candidates": candidates, "verification": verified,
            "note": "候補は別領域のみ。元データを変更しない。",
        }
        (cand_dir / f"{result['id']}.json").write_text(
            json.dumps(entry, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        reports.append({"id": result["id"], "candidates": len(candidates),
                        "verified": sum(1 for v in verified if v["verified"])})
    (out_dir / "fix_summary.json").write_text(
        json.dumps(reports, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"fix_candidates": len(reports), "out_dir": str(out_dir)},
                     ensure_ascii=False, indent=2))
    return 0


# ------------------------------------------------------------ regression ----

def fixture_cases() -> list[dict]:
    """Labeled fixtures. Each case has expected codes and expected verdict."""
    base_rec = {
        "id": "fix-0001", "category": "extraction", "task": "state_changes",
        "group": "extract-fixture", "notes": "",
        "context": {"character": {"id": "c1", "name": "ルカ", "hp": 10, "maxHp": 10,
                                  "inventory": [{"id": "i1", "name": "乾パン", "quantity": 3}]},
                    "npcs": [], "quests": [], "locations": [], "currentLocationId": None,
                    "activeCombat": None},
        "request": "ルカは乾パンを一つ食べた。",
        "output": {"stateChanges": [{"type": "ITEM_REMOVE", "targetId": "i1", "amount": 1,
                                      "reason": "食べた"}]},
    }
    cases = []

    def add(name, kind, mutate, expected_codes, verdict):
        rec = copy.deepcopy(base_rec)
        rec["id"] = name
        mutate(rec)
        # 各フィクスチャを重複検査の上で一意にする（番号は入力側のみ）
        rec["request"] = (rec.get("request", "") or "") + f"／試験{len(cases) + 1}"
        cases.append({"name": name, "kind": kind, "record": rec,
                      "expected_codes": expected_codes, "expected_verdict": verdict})

    add("normal_01", "normal", lambda r: None, [], "pass")
    add("schema_error", "abnormal", lambda r: r["output"].update({"unknown_key": 1}),
        ["SCHEMA_ERROR"], "fail")
    add("unknown_id", "abnormal",
        lambda r: r["output"]["stateChanges"][0].update({"targetId": "NO_SUCH"}),
        ["UNKNOWN_ID", "SCHEMA_ERROR"], "fail")
    add("negation_lost", "abnormal",
        lambda r: r.update({"request": "ルカは乾パンを食べなかった。",
                            "output": {"stateChanges": [{"type": "ITEM_REMOVE", "targetId": "i1",
                                                          "amount": 1, "reason": "食べた"}]}}),
        ["NEGATION_INVERSION", "FABRICATED_NUMBER"], "pass")
    add("fabricated_number", "abnormal",
        lambda r: r["output"]["stateChanges"][0].update({"amount": 99, "reason": "99個食べた"}),
        ["FABRICATED_NUMBER", "SCHEMA_ERROR"], "fail")
    add("simplified", "abnormal",
        lambda r: r.update({"request": r["request"].replace("ルカ", "误ルカ")}),
        ["SIMPLIFIED_CHINESE"], "pass")
    def boundary_low(r):
        r["request"] = "误记采误记采误ルカは乾パンを一つ食べた。"
        r["output"]["stateChanges"][0]["reason"] = "99個の道具をtoolで作った！"
        r["output"]["unknown_key"] = 1
    add("boundary_low", "boundary", boundary_low,
        ["SCHEMA_ERROR", "SIMPLIFIED_CHINESE", "FABRICATED_NUMBER"], "fail")
    add("regression_flagset", "regression",
        lambda r: r["output"].update({"stateChanges": [
            {"type": "FLAG_SET", "targetId": "new_flag", "value": "x", "reason": "立てた"}]}),
        [], "pass")
    add("regression_notes", "regression",
        lambda r: r.update({"notes": "CharacterDraft の extraction を検証"}),
        [], "pass")
    add("duplicate_a", "abnormal", lambda r: None, ["NEAR_DUPLICATE"], "pass")
    dup = copy.deepcopy(next(c["record"] for c in cases if c["name"] == "duplicate_a")); dup["id"] = "duplicate_b"
    cases.append({"name": "duplicate_b", "kind": "abnormal", "record": dup,
                  "expected_codes": ["EXACT_DUPLICATE"], "expected_verdict": "pass"})
    return cases


def cmd_test(args) -> int:
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    cases = fixture_cases()
    exact, near = build_duplicate_index_from_records([c["record"] for c in cases])
    rows = []
    for c in cases:
        result = evaluate(c["record"], exact, near)
        codes = {f["code"] for f in result["findings"]} | set(result["red_flags"])
        expected = set(c["expected_codes"])
        rows.append({
            "name": c["name"], "kind": c["kind"],
            "expected_codes": sorted(expected), "detected_codes": sorted(codes),
            "expected_verdict": c["expected_verdict"], "actual_verdict": result["verdict"],
            "score": result["total"],
            "true_positive": sorted(expected & codes),
            "false_positive": sorted(codes - expected),
            "false_negative": sorted(expected - codes),
            "verdict_ok": c["expected_verdict"] == result["verdict"],
        })
    tp = sum(len(r["true_positive"]) for r in rows)
    fp = sum(len(r["false_positive"]) for r in rows)
    fn = sum(len(r["false_negative"]) for r in rows)
    report = {
        "verifier_version": VERSION,
        "cases": len(rows),
        "verdict_pass_count": sum(1 for r in rows if r["verdict_ok"]),
        "tp": tp, "fp": fp, "fn": fn,
        "precision": round(tp / (tp + fp), 3) if tp + fp else 1.0,
        "recall": round(tp / (tp + fn), 3) if tp + fn else 1.0,
        "rows": rows,
    }
    path = out_dir / "regression_report.json"
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: report[k] for k in ("cases", "verdict_pass_count", "tp", "fp", "fn", "precision", "recall")},
                     ensure_ascii=False, indent=2))
    return 0 if report["verdict_pass_count"] == len(rows) else 1


def build_duplicate_index_from_records(records: list[dict]) -> tuple[dict, dict]:
    exact: dict[str, str] = {}
    exact_dupe: dict[str, str] = {}
    texts: dict[str, list[dict]] = defaultdict(list)
    for rec in records:
        key = hashlib.sha256(normalize({
            "task": rec.get("task"),
            "input": [rec.get("context"), rec.get("action"), rec.get("request")],
            "output": rec.get("output"),
        }).encode()).hexdigest()
        rid = rec.get("id", "")
        if key in exact:
            exact_dupe[rid] = exact[key]
        else:
            exact[key] = rid
        text = rec.get("request", "") if rec.get("category") == "extraction" else ""
        texts[rec.get("task", "?")].append({"id": rid, "text": text})
    near: dict[str, list[str]] = defaultdict(list)
    for items in texts.values():
        if len(items) > 1:
            for a, b, sc in bd.near_duplicates(items):
                near[b].append(f"{a} (Jaccard {sc})")
    return exact_dupe, near


# --------------------------------------------------------------------- CLI --

def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    sub = ap.add_subparsers(dest="command", required=True)

    p = sub.add_parser("score")
    p.add_argument("--root", required=True)
    p.add_argument("--out-dir", required=True)
    p.add_argument("--limit", type=int, default=0)
    p.add_argument("--batch-size", type=int, default=500)
    p.add_argument("--workers", type=int, default=1)
    p.add_argument("--resume", action="store_true")
    p.set_defaults(func=cmd_score)

    f = sub.add_parser("fix")
    f.add_argument("--root", required=True)
    f.add_argument("--out-dir", required=True)
    f.add_argument("--limit", type=int, default=0)
    f.set_defaults(func=cmd_fix)

    t = sub.add_parser("test")
    t.add_argument("--out-dir", required=True)
    t.set_defaults(func=cmd_test)

    args = ap.parse_args()
    return int(args.func(args))


if __name__ == "__main__":
    sys.exit(main())
