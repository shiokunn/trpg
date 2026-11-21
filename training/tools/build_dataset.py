#!/usr/bin/env python3
"""raw/*.jsonl -> processed/*.jsonl -> splits/*.jsonl + metadata/dataset_info.json

Raw records are never modified. Every derived line is revalidated, so a bad raw
record can only be excluded, not silently copied.

Usage:
  python3 training/tools/build_dataset.py
  python3 training/tools/build_dataset.py --check   # validate only, write nothing
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import sys
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(HERE))

import schema_lib  # noqa: E402
import prompts  # noqa: E402
import render  # noqa: E402

CATEGORIES = ("trpg_gm", "world_json", "extraction")
RAW_DIR = ROOT / "raw"
PROCESSED_DIR = ROOT / "processed"
SPLIT_DIR = ROOT / "splits"
META_DIR = ROOT / "metadata"

# Scenario: per_device_train_batch_size=1, gradient_accumulation_steps=16,
# num_train_epochs=1 -> 500 steps need 500 * 16 = 8000 train samples.
STEPS_CONFIG = {
    "per_device_train_batch_size": 1,
    "gradient_accumulation_steps": 16,
    "num_train_epochs": 1,
    "target_train_steps": 500,
}

SPLIT_RATIO = {"train": 0.90, "validation": 0.05, "test": 0.05}


# ------------------------------------------------------------------ loading --

def iter_raw():
    for category in CATEGORIES:
        folder = RAW_DIR / category
        if not folder.exists():
            continue
        for path in sorted(folder.glob("*.jsonl")):
            with path.open(encoding="utf-8") as fh:
                for lineno, line in enumerate(fh, 1):
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        record = json.loads(line)
                    except json.JSONDecodeError as exc:
                        yield None, (path.name, lineno), f"JSON構文エラー: {exc}"
                        continue
                    yield record, (path.name, lineno), None


def record_problems(record: dict) -> tuple[list[str], list[str]]:
    """Structural problems in the raw envelope (before output validation)."""
    errors: list[str] = []
    warnings: list[str] = []
    for key in ("id", "category", "task", "group", "output"):
        if key not in record:
            errors.append(f"必須キーなし: {key}")
    if errors:
        return errors, warnings
    if record["category"] not in CATEGORIES:
        errors.append(f"未知のcategory: {record['category']}")
    task = record["task"]
    if record["category"] == "trpg_gm":
        if task != "gm_turn":
            errors.append(f"trpg_gmのtaskはgm_turnのみ: {task}")
        if not isinstance(record.get("context"), dict):
            errors.append("contextがありません")
        if not isinstance(record.get("action"), dict) or not record["action"].get("text"):
            errors.append("action.textがありません")
    elif record["category"] == "world_json":
        if task != "world_json":
            errors.append(f"world_jsonのtaskはworld_jsonのみ: {task}")
        if not record.get("request"):
            errors.append("requestがありません")
    else:
        if task not in schema_lib.EXTRACTION_SCHEMAS:
            errors.append(f"未知のextraction task: {task}")
        if not record.get("request"):
            errors.append("requestがありません")
    return errors, warnings


def system_key(record: dict) -> str:
    if record["category"] == "trpg_gm":
        return "gm"
    if record["category"] == "world_json":
        return "world_json"
    if record["task"] == "character_draft":
        return "character_draft"
    return f"extract_{record['task']}"


def to_messages(record: dict) -> list[dict]:
    key = system_key(record)
    if key not in prompts.SYSTEMS:
        raise KeyError(f"system prompt not defined: {key}")
    if record["category"] == "trpg_gm":
        system = render.render_gm_system(record["context"])
        user = render.render_user(record["context"], record["action"])
    else:
        system = prompts.SYSTEMS[key]
        user = record["request"].strip()
    assistant = json.dumps(record["output"], ensure_ascii=False, separators=(",", ":"))
    messages = [{"role": "system", "content": system}]
    for turn in record.get("history") or []:
        messages.append(
            {"role": "user", "content": render.render_history_action(turn["action"])}
        )
        messages.append({"role": "assistant", "content": turn["narrative"]})
    messages.append({"role": "user", "content": user})
    messages.append({"role": "assistant", "content": assistant})
    return messages


# --------------------------------------------------------------------- dedup --

def shingles(text: str, n: int = 6) -> set[str]:
    text = re.sub(r"\s+", "", text)
    if len(text) <= n:
        return {text}
    return {text[i:i + n] for i in range(len(text) - n + 1)}


def near_duplicates(items: list[dict], threshold: float = 0.85) -> list[tuple[str, str, float]]:
    """Candidate pairs from an inverted shingle index, then Jaccard."""
    index: dict[str, list[int]] = defaultdict(list)
    grams = [shingles(item["text"]) for item in items]
    for i, sh in enumerate(grams):
        for g in sh:
            index[g].append(i)

    pairs: dict[tuple[int, int], int] = defaultdict(int)
    for ids in index.values():
        if len(ids) > 400:  # common shingle, not discriminative
            continue
        for a in range(len(ids)):
            for b in range(a + 1, len(ids)):
                pairs[(ids[a], ids[b])] += 1
    found = []
    for (a, b), shared in pairs.items():
        if shared < 4:
            continue
        union = len(grams[a] | grams[b])
        if not union:
            continue
        score = len(grams[a] & grams[b]) / union
        if score >= threshold:
            found.append((items[a]["id"], items[b]["id"], round(score, 3)))
    return found


# --------------------------------------------------------------------- split --

def assign_splits(
    groups: dict[str, list[str]],
    group_category: dict[str, str],
) -> dict[str, str]:
    """Deterministic, group-preserving, category-stratified split.

    Groups (one scenario / one source world) never span splits, so a validation
    sample can never be a paraphrase of a train sample. Each category is split
    independently so validation/test still contain every task type.
    """
    assigned: dict[str, str] = {}
    for category in sorted(set(group_category.values())):
        cat_groups = [g for g in groups if group_category[g] == category]
        total = sum(len(groups[g]) for g in cat_groups)
        order = sorted(cat_groups, key=lambda g: hashlib.sha256(g.encode()).hexdigest())
        for split in ("validation", "test"):
            remaining = SPLIT_RATIO[split] * total
            for group in order:
                if group in assigned or remaining <= 0:
                    continue
                size = len(groups[group])
                if size <= max(1, round(remaining)):
                    assigned[group] = split
                    remaining -= size
            if len(cat_groups) > 2 and not any(assigned.get(g) == split for g in cat_groups):
                free = [g for g in order if g not in assigned]
                if free:
                    assigned[min(free, key=lambda g: (len(groups[g]), g))] = split
    for group in groups:
        assigned.setdefault(group, "train")
    return assigned


# ---------------------------------------------------------------------- main --

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="validate only")
    args = parser.parse_args()

    records: list[dict] = []
    rejected: list[dict] = []
    json_ok = 0
    parse_failures = 0
    seen_ids: set[str] = set()

    for record, source, parse_error in iter_raw():
        if parse_error:
            parse_failures += 1
            rejected.append({"error": parse_error, "source": f"{source[0]}:{source[1]}", "errors": [parse_error]})
            continue
        json_ok += 1
        errors, warnings = record_problems(record)
        if not errors:
            if record["id"] in seen_ids:
                errors.append(f"id重複: {record['id']}")
            else:
                seen_ids.add(record["id"])
            report = schema_lib.validate_output(
                record["task"], record["output"], record.get("context")
            )
            errors.extend(report.errors)
            warnings.extend(report.warnings)
        if errors:
            rejected.append(
                {
                    "id": record.get("id"),
                    "source": f"{source[0]}:{source[1]}",
                    "errors": errors[:8],
                }
            )
            continue
        record["_warnings"] = warnings
        records.append(record)

    # exact duplicates (same task + input + output)
    exact_seen: dict[str, str] = {}
    unique: list[dict] = []
    exact_dupes: list[tuple[str, str]] = []
    for record in records:
        key = hashlib.sha256(
            json.dumps(
                {
                    "task": record["task"],
                    "input": [record.get("context"), record.get("action"), record.get("request")],
                    "output": record["output"],
                },
                ensure_ascii=False,
                sort_keys=True,
            ).encode()
        ).hexdigest()
        if key in exact_seen:
            exact_dupes.append((record["id"], exact_seen[key]))
            continue
        exact_seen[key] = record["id"]
        unique.append(record)

    # near duplicates per task
    near: list[tuple[str, str, float]] = []
    by_task: dict[str, list[dict]] = defaultdict(list)
    for record in unique:
        text = (
            record["output"].get("narrative", "")
            if record["task"] == "gm_turn"
            else record.get("request", "")
        )
        by_task[record["task"]].append({"id": record["id"], "text": text})
    for items in by_task.values():
        if len(items) > 1:
            near.extend(near_duplicates(items))
    near_drop = {pid for pair in near for pid in pair[1:2]}

    accepted = [r for r in unique if r["id"] not in near_drop]
    held_back = [r for r in unique if r["id"] in near_drop]

    if args.check:
        print(json.dumps(
            {
                "raw_json_ok": json_ok,
                "rejected": len(rejected),
                "exact_duplicates": len(exact_dupes),
                "near_duplicates": len(near),
                "accepted": len(accepted),
            },
            ensure_ascii=False,
            indent=2,
        ))
        return 0 if not rejected else 1

    PROCESSED_DIR.mkdir(parents=True, exist_ok=True)
    SPLIT_DIR.mkdir(parents=True, exist_ok=True)
    META_DIR.mkdir(parents=True, exist_ok=True)

    by_category: dict[str, list[dict]] = defaultdict(list)
    for record in accepted:
        by_category[record["category"]].append(record)

    processed_counts: dict[str, int] = {}
    for category, items in by_category.items():
        with (PROCESSED_DIR / f"{category}.jsonl").open("w", encoding="utf-8") as fh:
            for record in items:
                messages = to_messages(record)
                line = json.dumps(
                    {"id": record["id"], "task": record["task"], "messages": messages},
                    ensure_ascii=False,
                    separators=(",", ":"),
                )
                fh.write(line + "\n")
        processed_counts[category] = len(items)

    # group-preserving split over every accepted record
    groups: dict[str, list[str]] = defaultdict(list)
    id_to_group: dict[str, str] = {}
    for record in accepted:
        group = record.get("group") or record["id"]
        groups[group].append(record["id"])
        id_to_group[record["id"]] = group
    assignment = assign_splits(
        groups,
        {g: next(r["category"] for r in accepted if (r.get("group") or r["id"]) == g)
         for g in groups},
    )

    rows_by_id = {}
    for category, items in by_category.items():
        for record in items:
            rows_by_id[record["id"]] = {
                "id": record["id"],
                "task": record["task"],
                "messages": to_messages(record),
            }

    split_counts = {}
    split_category: dict[str, Counter] = {}
    char_lengths: list[int] = []
    for split in SPLIT_RATIO:
        ids = [rid for rid, group in id_to_group.items() if assignment[group] == split]
        with (SPLIT_DIR / f"{split}.jsonl").open("w", encoding="utf-8") as fh:
            for rid in sorted(ids):
                fh.write(json.dumps(rows_by_id[rid], ensure_ascii=False, separators=(",", ":")) + "\n")
        split_counts[split] = len(ids)
        split_category[split] = Counter(
            next(r["category"] for r in accepted if r["id"] == rid) for rid in ids
        )
    for row in rows_by_id.values():
        char_lengths.append(sum(len(m["content"]) for m in row["messages"]))

    task_counts = Counter(r["task"] for r in accepted)
    cat_counts = Counter(r["category"] for r in accepted)
    warn_count = sum(len(r["_warnings"]) for r in accepted)

    pending = {}
    for category, target in (("trpg_gm", 5000), ("world_json", 2000), ("extraction", 2000)):
        pending[category] = max(0, target - cat_counts.get(category, 0))

    train_n = split_counts["train"]
    steps = math.ceil(
        train_n
        / (STEPS_CONFIG["per_device_train_batch_size"] * STEPS_CONFIG["gradient_accumulation_steps"])
    ) * STEPS_CONFIG["num_train_epochs"]

    metadata = {
        "dataset_name": "trpg-lfm3-adapter",
        "created_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "status": (
            "PARTIAL — 検証済みだが目標件数に未到達。"
            "スモークテスト Verified データとしては使えるが、"
            "目標の9,000件（train 8,000件）に対する学習データとしては不十分。"
        ),
        "generation_model": {
            "name": "DeepSeek V4.1 Flash Free",
            "model_id": "opencode/deepseek-v4.1-flash-free",
            "service": "OpenCode (this agent session)",
            "note": "全件をこのエージェントセッションで生成。一括生成用のDeepSeek APIキーは環境に存在しない。",
        },
        "format": {
            "kind": "chat messages (JSONL, 1 sample per line)",
            "roles": ["system", "user", "assistant"],
            "assistant_content": "JSON object as a compact string",
            "chat_template": (
                "学習側は未確定。third_party/llama.cpp/models/templates/LFM3.jinja は"
                "推論用に実在し system を保持するが、学習トークナイザと等価とは限らない。"
                "同テンプレートは assistant 直前に <think></think> を挿入するため、"
                "loss マスキングと生成開始位置の確認が必要。"
            ),
            "system_role_note": "アプリはsystemにGMルールと状態セクションを入れるため、user/assistantの2roleだけにしない",
        },
        "counts": {
            "by_category": dict(cat_counts),
            "by_task": dict(sorted(task_counts.items())),
            "processed_files": processed_counts,
            "splits": split_counts,
            "by_split_category": {k: dict(v) for k, v in split_category.items()},
            "length_chars": {
                "max_per_sample": max(char_lengths) if char_lengths else 0,
                "avg_per_sample": round(sum(char_lengths) / len(char_lengths)) if char_lengths else 0,
                "note": "文字数。日本語は概ね1文字1トークンとして見積もる（LFM3の実トークナイザは未確認）。",
            },
            "accepted": len(accepted),
            "rejected": len(rejected),
            "held_back_as_near_duplicate": len(held_back),
            "warnings": warn_count,
        },
        "targets": {
            "trpg_gm": 5000,
            "world_json": 2000,
            "extraction": 2000,
            "train_min": 8000,
            "validation": "450-500",
            "test": "450-500",
            "remaining_to_generate": pending,
        },
        "training_steps": {
            **STEPS_CONFIG,
            "effective_batch_size": STEPS_CONFIG["per_device_train_batch_size"]
            * STEPS_CONFIG["gradient_accumulation_steps"],
            "formula": "ceil(train_n / (batch * grad_accum)) * epochs",
            "estimated_steps_with_current_train": steps,
            "estimated_steps_at_target": 500,
            "note": "実際の学習スクリプトはリポジトリ内に無いため、指示された想定設定で計算した。",
        },
        "validation": {
            "json_syntax": {"checked": json_ok + parse_failures, "failed": parse_failures},
            "schema_and_contract": {
                "checked": json_ok,
                "passed": len(records),
                "failed": json_ok - len(records),
                "rules": "training/tools/schema_lib.py mirrors GmResponseParser / WorldDraftParser / CharacterDraftParser / StateChangeValidator",
                "limits_note": "アプリが受理するかはKotlinパーサが最終判定。ここでの合格は構文・契約の合格であり、文章の品質評価ではない。",
            },
            "duplicates": {
                "exact": len(exact_dupes),
                "near": len(near),
                "threshold_shingle_jaccard": 0.85,
                "held_back": len(held_back),
            },
        },
        "generation_conditions": {
            "scenario_diversity": "1シナリオグループ=1世界観。グループ単位でsplitし、同一シナリオを跨がせない。",
            "coverage": "探索/戦闘/会話/交渉/クエスト/謎/アイテム/失敗/予想外/記憶参照/選択肢提示/状態変更/情報不足",
            "id_policy": "stateChanges と memories.relatedIds はcontextに実在するIDのみ。新規ITEM_ADDのitemにidは付けない。",
            "check_policy": "choice.check はアプリが判定する場合のみ。difficultyは4/6/8/10/12/14。",
            "no_dice_contradiction": "判定結果はユーザーメッセージの【アプリが確定した判定結果】だけを根拠にする。",
        },
        "raw_sources": sorted(
            f"raw/{p.relative_to(RAW_DIR).as_posix()}"
            for p in RAW_DIR.rglob("*.jsonl")
        ),
        "verification_commands": [
            "python3 training/tools/build_dataset.py --check",
            "python3 training/tools/build_dataset.py",
            "python3 training/tools/verify_dataset.py",
            "python3 training/tools/build_sft_outputs.py",
            "python3 training/tools/verify_sft_outputs.py",
            "python3 training/tools/fix_raw_consistency.py --dry-run",
        ],
        "not_ready_for_training": [
            f"目標9,000件に対して全{len(accepted)}件（約{round(100 * len(accepted) / 9000, 1)}%）。"
            f"train {split_counts['train']}件では500ステップに届かない。",
            "学習トークナイザのchat_templateが未確定。推論用LFM3.jinjaの"
            "<think></think>挿入とlossマスキングの整合を確認する必要がある。",
            "失敗・例外入力・情報不足の網羅は一部のみ。専門担当の抽出レビューで"
            "22件の修正（捏造・推測・秘密の誤扱い・type値の正規化）を実施済みだが、"
            "全件の人手レビューは未実施。",
            "本番学習・Kaggle Notebookの変更は指示により行っていない。",
        ],
        "rejected_samples": rejected[:50],
        "near_duplicate_pairs": near[:50],
        "notes": [
            "PARTIAL: 目標件数には到達していないが、全件が検証済みでスモークテスト用データとしては使用可能。",
            "推論用の third_party/llama.cpp/models/templates/LFM3.jinja は存在する。"
            "system は <|im_start|>system として保持される一方、assistant 直前に "
            "<think></think> が入る。学習側トークナイザが同一とは限らないため、"
            "apply_chat_template() の実物確認が必要。",
            "専門チーム検査: S02 全274件スキーマ適合・エラー0、S04 抽出68件を全文検査し"
            "不合格18件を修正、S06 形式検査で必須修正なし。S03 は120件を抽检し"
            "簡体字3種と判定算術1件を修正済み（残り86行は未検査）。",
            "extraction の system プロンプトはアプリに実装がない補助タスク用。アプリの推論プロンプトと同一ではない。",
            "world_json の combat.hp はパーサが1〜999、アプリのプロンプトが1〜30を指示しており不一致。データは1〜30に寄せた。",
            "統計は raw の検証結果であり、文章の面白さ・一貫性は機械検証できない。人手レビューが別途必要。",
        ],
    }
    (META_DIR / "dataset_info.json").write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )

    print(json.dumps(
        {
            "accepted": len(accepted),
            "rejected": len(rejected),
            "by_category": dict(cat_counts),
            "splits": split_counts,
            "by_split_category": {k: dict(v) for k, v in split_category.items()},
            "length_chars": {
                "max_per_sample": max(char_lengths) if char_lengths else 0,
                "avg_per_sample": round(sum(char_lengths) / len(char_lengths)) if char_lengths else 0,
                "note": "文字数。日本語は概ね1文字1トークンとして見積もる（LFM3の実トークナイザは未確認）。",
            },
            "exact_dupes": len(exact_dupes),
            "near_dupes": len(near),
            "estimated_steps": steps,
        },
        ensure_ascii=False,
        indent=2,
    ))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
