#!/usr/bin/env python3
"""HY4 Flash Free semantic-validation engine.

The current agent model (opencode/hy4-flash-free) performs the semantic review.
Because no callable HTTP endpoint/key for that model exists in this environment,
the engine supports two clients:

  manual : the agent writes judgments to a JSONL file (used for this trial)
  http   : an OpenAI-compatible endpoint via HY4_API_URL / HY4_API_KEY
           (keys are never logged; retry, timeout and resume are implemented)

Python machine validation from quality_system.py is always merged into the final
verdict. Ambiguous LLM judgments become "review", not automatic pass/fail.

Subcommands:
  prepare  --root RAW --out DIR [--limit N] [--gold LABELS.json]
  judge    --packets F --out F [--workers N] [--model ...]
  ingest   --packets F --judgments F --out DIR
  selftest --out DIR
"""

from __future__ import annotations

import argparse
import json
import os
import random
import sys
import time
import urllib.error
import urllib.request
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(HERE))

import quality_system as qs  # noqa: E402
import qa_manifest as qm  # noqa: E402

VERSION = "semantic-llm-1.2"
CATEGORIES = ("fabrication", "negation", "condition", "number", "contradiction")
CONF_FAIL = 0.80
CONF_REVIEW = 0.50

# Python側の誤検知を抑えるための判定基準（既定値・空配列・数詞・条件の扱い）
DEFAULT_NUMBERS = {"0", "1", "2", "3", "5", "10", "100"}
NEG_FLIP_PAIRS = (
    ("食べなかった", "食べた"), ("食べない", "食べた"),
    ("拾わなかった", "拾った"), ("拾わない", "拾った"),
    ("持っていない", "持った"), ("持たなかった", "持った"),
    ("渡さなかった", "渡した"), ("渡さない", "渡した"),
    ("見つけなかった", "見つけた"), ("飲まなかった", "飲んだ"),
)
CONDITION_FLIP_MARKERS = ("ても", "なくても", "かかわらず", "ではあるが")

JUDGMENT_SCHEMA = {
    "required": ["id", "labels"],
    "labels": "list of {category, confidence(0..1), evidence}",
    "categories": list(CATEGORIES),
    "note": "曖昧なら.category='review' confidence 0.5〜0.79 を使う。根拠は原文の語句のみ。",
}


# ------------------------------------------------------------------ packets --

def build_packet(rec: dict, gold: dict | None = None) -> dict:
    rid = rec.get("id", "?")
    if rec.get("category") == "extraction":
        source = rec.get("request", "")
        generated = json.dumps(rec.get("output") or {}, ensure_ascii=False, separators=(",", ":"))
    elif rec.get("task") == "gm_turn":
        source = (rec.get("action") or {}).get("text", "")
        check = (rec.get("action") or {}).get("check") or {}
        if check:
            source += (f" / 出目{check.get('dice')} 合計{check.get('total')} "
                       f"難易度{check.get('difficulty')} 結果{check.get('outcome')}")
        generated = (rec.get("output") or {}).get("narrative", "")
    else:
        source = rec.get("request", "")
        generated = json.dumps(rec.get("output") or {}, ensure_ascii=False, separators=(",", ":"))[:1200]
    packet = {
        "id": rid,
        "category": rec.get("category", ""),
        "task": rec.get("task", ""),
        "source_text": source[:1500],
        "generated_text": generated[:2000],
        "instruction": (
            "原文(source_text)と生成JSON(generated_text)を比較し、"
            "事実捏造(fabrication)/否定反転(negation)/条件反転(condition)/"
            "数値改変(number)/物語と判定の矛盾(contradiction)を検出してください。"
            "曖昧なら review として信心度を0.5〜0.79にしてください。"
        ),
    }
    if gold is not None and rid in gold:
        packet["_gold"] = gold.get(rid, [])
        packet["_has_gold"] = True
    return packet


def load_gold(path: str | None) -> dict:
    if not path:
        return {}
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    if isinstance(data, dict) and "labels" in data:
        return data["labels"]
    return data


def cmd_prepare(args) -> int:
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    gold = load_gold(args.gold)
    packets_path = out / "packets.jsonl"
    if packets_path.exists():
        print(f"既に存在します: {packets_path}", file=sys.stderr)
        return 2
    recs = list(qs.iter_records(Path(args.root)))
    if args.limit:
        recs = recs[: args.limit]
    with packets_path.open("w", encoding="utf-8") as fh:
        for rec in recs:
            fh.write(json.dumps(build_packet(rec, gold), ensure_ascii=False) + "\n")
    print(json.dumps({"packets": len(recs), "path": str(packets_path)}, ensure_ascii=False, indent=2))
    return 0


# ------------------------------------------------------------------- clients --

def validate_judgment(obj: dict) -> list[str]:
    errors = []
    if not isinstance(obj, dict) or "id" not in obj:
        return ["id が無い"]
    labels = obj.get("labels")
    if not isinstance(labels, list):
        return ["labels が配列でない"]
    for lab in labels:
        if not isinstance(lab, dict):
            errors.append("label が辞書でない")
            continue
        if lab.get("category") == "review":
            continue
        if lab.get("category") not in CATEGORIES:
            errors.append(f"未知カテゴリ: {lab.get('category')}")
        conf = lab.get("confidence")
        if not isinstance(conf, (int, float)) or not 0 <= float(conf) <= 1:
            errors.append("confidence が0..1外")
    return errors


class ManualClient:
    """Reads judgments produced by the agent (or an external reviewer)."""

    def __init__(self, path: Path):
        self.path = path
        self.done: dict[str, dict] = {}
        if path.exists():
            for line in path.open(encoding="utf-8"):
                if line.strip():
                    row = json.loads(line)
                    self.done[row["id"]] = row

    def missing(self, packets: list[dict]) -> list[dict]:
        return [p for p in packets if p["id"] not in self.done]

    def record(self, packet: dict, judgment: dict) -> None:
        self.done[packet["id"]] = judgment
        with self.path.open("a", encoding="utf-8") as fh:
            fh.write(json.dumps(judgment, ensure_ascii=False) + "\n")


class HTTPClient:
    """OpenAI-compatible chat endpoint. Keys are read from env, never logged."""

    def __init__(self, url: str, model: str, timeout: float, retries: int):
        self.url = url
        self.model = model
        self.timeout = timeout
        self.retries = retries

    def judge(self, packet: dict) -> dict:
        payload = {
            "model": self.model,
            "messages": [
                {"role": "system", "content": "出力はJSONオブジェクト1つだけ。理由は原文の語句のみ。"},
                {"role": "user", "content": packet["instruction"] + "\n\n" + json.dumps(packet, ensure_ascii=False)},
            ],
            "temperature": 0,
        }
        api_key = os.environ.get("HY4_API_KEY", "")
        headers = {"Content-Type": "application/json"}
        if api_key:
            headers["Authorization"] = f"Bearer {api_key}"
        last_error = ""
        for attempt in range(self.retries + 1):
            try:
                req = urllib.request.Request(self.url, data=json.dumps(payload).encode("utf-8"), headers=headers)
                with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                    body = json.loads(resp.read().decode("utf-8"))
                content = body["choices"][0]["message"]["content"]
                obj = json.loads(content)
                errs = validate_judgment(obj)
                if errs:
                    raise ValueError(f"応答不正: {errs}")
                obj["id"] = packet["id"]
                return obj
            except (urllib.error.URLError, TimeoutError, KeyError, ValueError, json.JSONDecodeError) as exc:
                last_error = type(exc).__name__
                if attempt < self.retries:
                    time.sleep(min(2 ** attempt, 8) + random.random() * 0.2)
        return {"id": packet["id"], "labels": [{"category": "review", "confidence": 0.5,
                                                "evidence": f"api_error:{last_error}"}]}


def cmd_judge(args) -> int:
    packets = [json.loads(l) for l in Path(args.packets).open(encoding="utf-8") if l.strip()]
    manual = ManualClient(Path(args.out))
    pending = manual.missing(packets)
    if args.http:
        client = HTTPClient(args.url, args.model, args.timeout, args.retries)
        if not args.url:
            print("HTTPモードには --url が必要です", file=sys.stderr)
            return 2
        if args.workers > 1:
            with ThreadPoolExecutor(max_workers=args.workers) as pool:
                results = list(pool.map(client.judge, pending))
        else:
            results = [client.judge(p) for p in pending]
        for judgment in results:
            manual.record({"id": judgment["id"]}, judgment)
    else:
        # 手動モード: エージェントが別途 judgments を書く。ここでは何も生成しない。
        pass
    print(json.dumps({"packets": len(packets), "pending": len(pending),
                      "completed": len(packets) - len(pending),
                      "judgments_file": str(args.out), "mode": "http" if args.http else "manual"},
                     ensure_ascii=False, indent=2))
    return 0


# -------------------------------------------------------------------- ingest --

def llm_categories(judgment: dict) -> dict[str, float]:
    out: dict[str, float] = {}
    for lab in judgment.get("labels", []):
        cat = lab.get("category")
        if cat == "review":
            out["review"] = max(out.get("review", 0), float(lab.get("confidence", 0.5)))
        elif cat in CATEGORIES:
            out[cat] = max(out.get(cat, 0), float(lab.get("confidence", 0)))
    return out


CODE_TO_CATEGORY = {
    "FABRICATED_NUMBER": "number", "NEGATION_INVERSION": "negation",
    "CONDITION_LOST": "condition", "CONDITION_INVERSION": "condition",
    "OUTCOME_CONTRADICTION": "contradiction",
}


def refine_python_categories(rec: dict, result: dict) -> tuple[set[str], list[dict], list[str]]:
    """False-positive suppression layer over quality_system's Python findings.

    Rules are defined independently of the LLM judgments:
      - default numbers (1/2/3/...) are not fabrication when no explicit number exists
      - an empty array is a valid representation of "持っていない"
      - a condition is only inverted when the output asserts the opposite condition
      - negation is only inverted when the negated action is asserted positively
    Returns (kept categories, suppressed findings, suppression reasons).
    """
    source = rec.get("request", "") or ""
    generated = json.dumps(rec.get("output") or {}, ensure_ascii=False)
    source_numbers = set(__import__("re").findall(r"\d+", qs.normalize_numbers(source)))
    out_numbers = set(__import__("re").findall(r"\d+", qs.normalize_numbers(generated)))
    kept: set[str] = set()
    suppressed: list[dict] = []
    reasons: list[str] = []

    for f in result["findings"]:
        code = f["code"]
        cat = CODE_TO_CATEGORY.get(code)
        if cat is None:
            continue
        keep = True
        why = ""
        if code == "FABRICATED_NUMBER":
            extra = out_numbers - source_numbers
            meaningful = {n for n in extra if n not in DEFAULT_NUMBERS}
            keep = bool(meaningful)
            why = "既定値のみ" if not keep else f"意味のある数値: {sorted(meaningful)}"
        elif code == "NEGATION_INVERSION":
            keep = any(neg in source and pos in generated for neg, pos in NEG_FLIP_PAIRS)
            why = "空配列/省略で表現" if not keep else "否定動作が肯定で再登場"
        elif code in ("CONDITION_LOST", "CONDITION_INVERSION"):
            keep = ("たら" in source or "なら" in source or "ば" in source) and any(
                m in generated for m in CONDITION_FLIP_MARKERS
            )
            why = "条件は結果表現に置換" if not keep else "反対条件が明示"
        elif code == "OUTCOME_CONTRADICTION":
            keep = True
            why = "判定と描写の矛盾"
        if keep:
            kept.add(cat)
        else:
            suppressed.append(f)
            reasons.append(f"{code}:{why}")
    return kept, suppressed, reasons


def python_categories(result: dict) -> set[str]:
    """Backward-compatible wrapper (no suppression)."""
    return {CODE_TO_CATEGORY[f["code"]] for f in result["findings"] if f["code"] in CODE_TO_CATEGORY}


def _write_jsonl_atomic(path: Path, rows: list[dict]) -> None:
    tmp = path.with_suffix(path.suffix + ".tmp")
    with tmp.open("w", encoding="utf-8") as fh:
        for row in rows:
            fh.write(json.dumps(row, ensure_ascii=False) + "\n")
        fh.flush()
        try:
            os.fsync(fh.fileno())
        except OSError:
            pass
    os.replace(tmp, path)


def cmd_ingest(args) -> int:
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    fpath = out / "semantic_final.jsonl"

    # 既存出力を無断で上書きしない（--resume のときのみ再利用）
    if fpath.exists() and not args.resume:
        print(f"出力が既に存在します。--resume を指定してください: {fpath}", file=sys.stderr)
        return 2
    if not fpath.exists() and args.resume:
        print(f"再開元がありません: {fpath}", file=sys.stderr)
        return 2

    packets: dict[str, dict] = {}
    for line in Path(args.packets).open(encoding="utf-8"):
        if not line.strip():
            continue
        try:
            packet = json.loads(line)
            packets[packet["id"]] = packet  # 同じidは最後の1件だけ（重複防止）
        except (json.JSONDecodeError, KeyError, TypeError):
            print(f"不正なパケット行をスキップ: {line[:60]}", file=sys.stderr)

    judgments: dict[str, dict] = {}
    jpath = Path(args.judgments)
    if jpath.exists():
        for line in jpath.open(encoding="utf-8"):
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError:
                print(f"不正なJSON行をスキップ: {line[:60]}", file=sys.stderr)
                continue
            errs = validate_judgment(row)
            if errs:
                print(f"無効な判定をスキップ: {row.get('id')} {errs}", file=sys.stderr)
                continue
            judgments[row["id"]] = row

    root = Path(args.root)
    exact, near = qs.build_duplicate_index(root)
    rec_by_id = {r.get("id"): r for r in qs.iter_records(root)}

    # 共通マニフェスト（stage=semantic_ingest）と旧結果の読み込み
    man = qm.Manifest(out / "manifest.jsonl")
    prev_rows: dict[str, dict] = {}
    if args.resume and fpath.exists():
        for line in fpath.open(encoding="utf-8"):
            if not line.strip():
                continue
            try:
                row = json.loads(line)
                prev_rows[row["id"]] = row
            except json.JSONDecodeError:
                continue

    results: list[dict] = []
    disagreements: list[dict] = []
    review_reasons: Counter = Counter()
    reused = 0
    recomputed = 0

    for rid, packet in packets.items():
        rec = rec_by_id.get(rid)
        if rec is None:
            print(f"元データが見つからないためスキップ: {rid}", file=sys.stderr)
            continue
        j = judgments.get(rid)
        digest = qm.content_hash(rec, j)

        # 差分処理: 内容も判定も変わっていなければ再計算しない
        last = man.latest("semantic_ingest", rid)
        if (args.resume and last and last.get("content_hash") == digest
                and last.get("verifier_version") == VERSION and rid in prev_rows):
            row = prev_rows[rid]
            reused += 1
            review_reasons["resumed"] += 1
            if row.get("final_verdict") == "review":
                review_reasons["resumed_review"] += 1
        else:
            py = qs.evaluate(rec, exact, near)
            if not j:
                final_verdict = "review"
                llm_cats = {}
            else:
                llm_cats = llm_categories(j)
                if py["red_flags"]:
                    final_verdict = "fail"
                elif any(v >= CONF_FAIL for k, v in llm_cats.items() if k != "review"):
                    final_verdict = "fail"
                elif llm_cats.get("review", 0) >= CONF_REVIEW or any(
                        CONF_REVIEW <= v < CONF_FAIL for k, v in llm_cats.items() if k != "review"):
                    final_verdict = "review"
                else:
                    final_verdict = "pass" if py["verdict"] == "pass" else py["verdict"]

            pcats, _suppressed, suppression_reasons = refine_python_categories(rec, py)
            for reason in suppression_reasons:
                review_reasons[f"python_suppressed:{reason.split(':')[0]}"] += 1
            if not j:
                review_reasons["unjudged"] += 1
            elif llm_cats.get("review", 0) >= CONF_REVIEW:
                review_reasons["llm_review"] += 1
            elif any(CONF_REVIEW <= v < CONF_FAIL for k, v in llm_cats.items() if k != "review"):
                review_reasons["llm_low_confidence"] += 1

            row = {
                "id": rid, "python_verdict": py["verdict"], "python_red_flags": py["red_flags"],
                "python_score": py["total"], "python_categories": sorted(pcats),
                "llm_labels": llm_cats, "llm_evidence": [
                    {"category": lab.get("category"), "evidence": lab.get("evidence", ""),
                     "confidence": lab.get("confidence")} for lab in (j or {}).get("labels", [])
                ],
                "final_verdict": final_verdict, "judged": j is not None,
                "_gold": packet.get("_gold", []),
                "_has_gold": bool(packet.get("_has_gold", False)),
            }
            man.append("semantic_ingest", rid, digest, VERSION, final_verdict)
            recomputed += 1
        results.append(row)

        # 不一致は全件（再開分も含めて）再構築
        pcats = set(row.get("python_categories", []))
        lset = {k for k, v in (row.get("llm_labels") or {}).items()
                if k != "review" and isinstance(v, (int, float)) and v >= CONF_REVIEW}
        if pcats != lset:
            py_only, llm_only = pcats - lset, lset - pcats
            cause = ("mixed" if py_only and llm_only
                     else "python_only:" + ",".join(sorted(py_only)) if py_only
                     else "llm_only:" + ",".join(sorted(llm_only)))
            disagreements.append({
                "id": rid, "cause": cause,
                "python_categories": sorted(pcats), "llm_categories": sorted(lset),
                "python_verdict": row.get("python_verdict"),
                "final_verdict": row.get("final_verdict"),
            })

    _write_jsonl_atomic(fpath, results)
    _write_jsonl_atomic(out / "semantic_mismatches.jsonl", disagreements)
    api_errors = sum(
        1 for row in judgments.values()
        for lab in row.get("labels", [])
        if str(lab.get("evidence", "")).startswith("api_error:")
    )
    report = compute_metrics(results, disagreements, review_reasons, api_errors)
    report["reused"] = reused
    report["recomputed"] = recomputed
    report["manifest_duplicate_ids"] = man.duplicate_ids("semantic_ingest")
    report["manifest_skipped_lines"] = man.skipped
    (out / "semantic_metrics.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({
        "gold_cases": report["gold_cases"], "precision": report["precision"],
        "recall": report["recall"], "f1": report["f1"],
        "reused": reused, "recomputed": recomputed,
        "disagreements": report["disagreements"],
    }, ensure_ascii=False, indent=2))
    return 0


def compute_metrics(results: list[dict], disagreements: list[dict],
                    review_reasons: Counter | None = None, api_errors: int = 0) -> dict:
    gold_rows = [r for r in results if r.get("_has_gold")]
    per = {}
    for cat in CATEGORIES:
        tp = fp = fn = 0
        for r in gold_rows:
            expected = cat in set(r["_gold"])
            predicted = cat in {k for k, v in r["llm_labels"].items() if k != "review" and v >= CONF_REVIEW}
            if predicted and expected: tp += 1
            elif predicted and not expected: fp += 1
            elif expected and not predicted: fn += 1
        p = tp / (tp + fp) if tp + fp else 0.0
        rc = tp / (tp + fn) if tp + fn else 0.0
        f1 = 2 * p * rc / (p + rc) if p + rc else 0.0
        tn = len(gold_rows) - tp - fp - fn
        per[cat] = {"tp": tp, "fp": fp, "fn": fn, "tn": tn,
                    "confusion_matrix": {"tp": tp, "fp": fp, "fn": fn, "tn": tn},
                    "precision": round(p, 3), "recall": round(rc, 3), "f1": round(f1, 3)}
    macro_p = round(sum(v["precision"] for v in per.values()) / len(per), 3)
    macro_r = round(sum(v["recall"] for v in per.values()) / len(per), 3)
    macro_f = round(sum(v["f1"] for v in per.values()) / len(per), 3)
    return {
        "gold_cases": len(gold_rows),
        "per_category": per,
        "precision": macro_p, "recall": macro_r, "f1": macro_f,
        "disagreements": len(disagreements),
        "disagreement_causes": dict(Counter(d.get("cause", "unknown") for d in disagreements)),
        "disagreement_rows": disagreements[:50],
        "review_reasons": dict(review_reasons or {}),
        "review_rate": round(sum(1 for r in results if r.get("final_verdict") == "review") / len(results), 3) if results else 0,
        "unjudged": sum(1 for r in results if not r["judged"]),
        "unjudged_rate": round(sum(1 for r in results if not r["judged"]) / len(results), 3) if results else 0,
        "api_errors": api_errors,
        "api_error_rate": round(api_errors / len(results), 3) if results else 0,
        "total": len(results),
    }


# ------------------------------------------------------------------ selftest --

def contradiction_cases() -> list[dict]:
    """Independent evaluation cases for the 'contradiction' category."""
    def gm(rid, outcome, narrative):
        return {
            "id": rid, "category": "trpg_gm", "task": "gm_turn", "group": "contra-fixture",
            "notes": "判定と描写の整合性の独立試験",
            "context": {
                "world": {"name": "試験の塔", "genre": "ファンタジー", "era": "", "setting": "",
                          "atmosphere": "", "history": "", "geography": "", "socialStructure": "",
                          "factions": [], "techMagic": "", "specialSettings": [], "objective": "",
                          "problem": "", "scenarioPremise": "", "secrets": []},
                "character": {"name": "ルカ", "species": "人間", "occupation": "冒険者",
                              "hp": 10, "maxHp": 10, "experience": 0,
                              "attributes": {"str": 3, "dex": 3, "int": 3, "wil": 3, "per": 3, "cha": 3},
                              "skills": [], "inventory": []},
                "npcs": [], "quests": [], "locations": [], "flags": {},
                "currentLocationId": None, "memories": [], "npcMemories": [],
                "activeCombat": None, "turn": 1, "summary": "",
            },
            "action": {"text": "古い扉を開ける", "check": {
                "attribute": "PER", "skillId": None, "skillName": "", "skillLevel": 0,
                "attributeValue": 3, "dice": [4], "rollMode": "NORMAL", "total": 7,
                "difficulty": 8, "outcome": outcome, "reason": ""}},
            "history": [],
            "output": {"narrative": narrative, "choices": [
                {"id": "c1", "text": "もう一度押す"}, {"id": "c2", "text": "鍵を探す"},
                {"id": "c3", "text": "引き返す"}],
                "stateChanges": [], "memories": [], "npcMemories": [], "eventSummary": "扉を試した"},
        }
    return [
        gm("contra-01", "FAILURE", "扉は滑らかに開き、無事に中へ入れた。成功だった。"),
        gm("contra-02", "SUCCESS", "鍵は折れ、扉は開かなかった。失敗した。"),
        gm("contra-03", "PARTIAL_SUCCESS", "扉は半分だけ開き、残りは重いまま動かなかった。"),
        gm("contra-04", "CRITICAL_SUCCESS", "鍵は見事に回り、扉が全開した。"),
    ]


def cmd_contradiction(args) -> int:
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    fixtures = out / "fixtures"
    fixtures.mkdir(exist_ok=True)
    cases = contradiction_cases()
    (fixtures / "cases.jsonl").write_text(
        "".join(json.dumps(c, ensure_ascii=False) + "\n" for c in cases), encoding="utf-8")
    gold = {"contra-01": ["contradiction"], "contra-02": ["contradiction"],
            "contra-03": [], "contra-04": []}
    (out / "gold.json").write_text(json.dumps({"labels": gold}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    # 参考となるHY4判定（独立ラベルから導いたものであり、実装ロジックには依存しない）
    judgments = {
        "contra-01": [{"category": "contradiction", "confidence": 0.95,
                       "evidence": "結果は失敗だが本文は「無事に開き成功」"}],
        "contra-02": [{"category": "contradiction", "confidence": 0.95,
                       "evidence": "結果は成功だが本文は「開かなかった失敗」"}],
        "contra-03": [], "contra-04": [],
    }
    (out / "judgments.jsonl").write_text("".join(
        json.dumps({"id": k, "labels": v}, ensure_ascii=False) + "\n" for k, v in judgments.items()),
        encoding="utf-8")
    # prepare / ingest を内部呼び出し
    from types import SimpleNamespace
    prep = SimpleNamespace(root=str(fixtures), out=str(out / "packets"), limit=0, gold=str(out / "gold.json"))
    cmd_prepare(prep)
    ing = SimpleNamespace(packets=str(out / "packets" / "packets.jsonl"),
                          judgments=str(out / "judgments.jsonl"), root=str(fixtures),
                          out=str(out / "result"), resume=False)
    cmd_ingest(ing)
    return 0


def cmd_compare(args) -> int:
    old = [json.loads(l) for l in Path(args.old).open(encoding="utf-8") if l.strip()]
    new = [json.loads(l) for l in Path(args.new).open(encoding="utf-8") if l.strip()]
    om = {r["id"]: r for r in old}
    nm = {r["id"]: r for r in new}
    rows = []
    for rid in sorted(set(om) | set(nm)):
        o, n = om.get(rid), nm.get(rid)
        rows.append({
            "id": rid,
            "old_verdict": o.get("final_verdict") if o else None,
            "new_verdict": n.get("final_verdict") if n else None,
            "verdict_changed": bool(o and n and o.get("final_verdict") != n.get("final_verdict")),
            "old_python_categories": o.get("python_categories", []) if o else [],
            "new_python_categories": n.get("python_categories", []) if n else [],
            "llm_categories_changed": bool(o and n and
                {k for k, v in (o.get("llm_labels") or {}).items() if k != "review" and v >= 0.5} !=
                {k for k, v in (n.get("llm_labels") or {}).items() if k != "review" and v >= 0.5}),
        })
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    path = out / "judgment_comparison.json"
    path.write_text(json.dumps({"old": args.old, "new": args.new, "rows": rows,
                                "verdict_changes": sum(1 for r in rows if r["verdict_changed"])},
                               ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"compared": len(rows),
                      "verdict_changes": sum(1 for r in rows if r["verdict_changed"])},
                     ensure_ascii=False, indent=2))
    return 0


def cmd_selftest(args) -> int:
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    payload = {"ok": True, "version": VERSION, "categories": list(CATEGORIES),
               "http_client_available": bool(os.environ.get("HY4_API_URL")),
               "api_key_present": bool(os.environ.get("HY4_API_KEY"))}
    (out / "selftest.json").write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(payload, ensure_ascii=False, indent=2))
    return 0


# ----------------------------------------------------------------------- CLI --

def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    sub = ap.add_subparsers(dest="command", required=True)

    p = sub.add_parser("prepare")
    p.add_argument("--root", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--limit", type=int, default=0)
    p.add_argument("--gold", default=None)
    p.set_defaults(func=cmd_prepare)

    j = sub.add_parser("judge")
    j.add_argument("--packets", required=True)
    j.add_argument("--out", required=True)
    j.add_argument("--http", action="store_true")
    j.add_argument("--url", default=os.environ.get("HY4_API_URL", ""))
    j.add_argument("--model", default=os.environ.get("HY4_MODEL", "opencode/hy4-flash-free"))
    j.add_argument("--timeout", type=float, default=30.0)
    j.add_argument("--retries", type=int, default=3)
    j.add_argument("--workers", type=int, default=4)
    j.set_defaults(func=cmd_judge)

    i = sub.add_parser("ingest")
    i.add_argument("--packets", required=True)
    i.add_argument("--judgments", required=True)
    i.add_argument("--root", required=True)
    i.add_argument("--out", required=True)
    i.add_argument("--resume", action="store_true")
    i.set_defaults(func=cmd_ingest)

    c = sub.add_parser("contradiction")
    c.add_argument("--out", required=True)
    c.set_defaults(func=cmd_contradiction)

    cp = sub.add_parser("compare")
    cp.add_argument("--old", required=True)
    cp.add_argument("--new", required=True)
    cp.add_argument("--out", required=True)
    cp.set_defaults(func=cmd_compare)

    s = sub.add_parser("selftest")
    s.add_argument("--out", required=True)
    s.set_defaults(func=cmd_selftest)

    args = ap.parse_args()
    return int(args.func(args))


if __name__ == "__main__":
    sys.exit(main())
