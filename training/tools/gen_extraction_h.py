#!/usr/bin/env python3
"""Add a hand-authored extraction batch. Idempotent; validates before writing."""

from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(HERE))
import schema_lib  # noqa: E402

DEST = ROOT / "raw" / "extraction" / "extraction_02.jsonl"

CTX = {
    "character": {
        "id": "c1", "name": "ルカ", "hp": 7, "maxHp": 10, "experience": 30,
        "inventory": [
            {"id": "i1", "name": "短剣", "quantity": 1},
            {"id": "i2", "name": "麻縄", "quantity": 2},
            {"id": "i3", "name": "乾パン", "quantity": 3},
        ],
    },
    "npcs": [
        {"id": "n1", "name": "番人オルド", "relationship": 10, "isAlive": True, "combat": None},
        {"id": "n2", "name": "影の男", "relationship": -60, "isAlive": True,
         "combat": {"attackPower": 5, "defense": 2, "attackSkillLevel": 2,
                    "defenseSkillLevel": 1, "hp": 12}},
    ],
    "quests": [
        {"id": "q1", "title": "鍵を取り戻す", "status": "ACTIVE",
         "objectives": [{"description": "番人に話を聞く", "completed": False}]},
        {"id": "q2", "title": "塔の灯をともす", "status": "ACTIVE",
         "objectives": [{"description": "灯油を集める", "completed": False}]},
    ],
    "locations": [
        {"id": "l1", "name": "塔の下", "isDiscovered": True},
        {"id": "l2", "name": "地下の倉", "isDiscovered": True},
    ],
    "currentLocationId": "l1",
    "activeCombat": None,
}

RECORDS = [
    {
        "id": "ex-0009", "task": "character_draft", "group": "extract-character-reluctant",
        "notes": "希望が抽象的で詳細が少ない。推測で埋めず、短い記述に留める。",
        "request": "灰の塔を舞台にした主人公を作って。名前はアニタ。漢方を少し知っている女性。あとは適当でいい。",
        "output": {
            "name": "アニタ", "species": "人間", "occupation": "薬草採り",
            "background": "灰の塔の麓で育ち、薬草の効能を独学で覚えた。",
            "personality": "口数が少ない。",
            "attributes": {"str": 2, "dex": 3, "int": 4, "wil": 3, "per": 4, "cha": 2},
            "skills": [
                {"name": "薬草の知識", "level": 2, "description": "効能と用量を見分ける"},
                {"name": "採集", "level": 1, "description": "野山から薬草を集める"},
                {"name": "観察", "level": 1, "description": "植物の状態から周囲を推し量る"},
            ],
            "items": [
                {"name": "薬草の束", "description": "乾燥させた数種の草", "quantity": 2,
                 "type": "misc", "power": 0, "defense": 0, "tags": ["薬"]},
                {"name": "小さな包丁", "description": "根を掘るための刃", "quantity": 1,
                 "type": "tool", "power": 1, "defense": 0, "tags": ["道具"]},
            ],
        },
    },
    {
        "id": "ex-0010", "task": "state_changes", "group": "extract-state-negation",
        "notes": "否定文と日本語の数詞。敵HPは変更しない。",
        "context": CTX,
        "request": "ルカは乾パンを一つ食べ、麻縄を二つ残した。短剣はもう持っていない。影の男に殴られて3点ダメージを受けたが、男のHPはそのままだった。",
        "output": {"stateChanges": [
            {"type": "ITEM_REMOVE", "targetId": "i3", "amount": 1, "reason": "乾パンを一つ食べた"},
            {"type": "HP_DELTA", "amount": -3, "reason": "影の男に殴られた"},
            {"type": "NPC_RELATION_DELTA", "targetId": "n2", "amount": -5, "reason": "襲われた"},
        ]},
    },
    {
        "id": "ex-0011", "task": "memories", "group": "extract-memory-empty",
        "notes": "挨拶だけで、記憶に残す情報がない。空配列。",
        "request": "「おはよう」「今日もいい天気だね」「うん、そうだね」",
        "output": {"memories": [], "npcMemories": []},
    },
    {
        "id": "ex-0012", "task": "items", "group": "extract-items-japanese-numeral",
        "notes": "数詞「三つ」「二つ」と、否定の「持っていない」。",
        "request": "持っているのは、古い鍵が三つと、切れたロープが二つ。護符はもう持っていない。",
        "output": {"items": [
            {"name": "古い鍵", "description": "錆びた三つの鍵", "quantity": 3,
             "type": "misc", "power": 0, "defense": 0, "tags": ["鍵"]},
            {"name": "切れたロープ", "description": "端がほつれた二本のロープ", "quantity": 2,
             "type": "tool", "power": 0, "defense": 0, "tags": ["道具"]},
        ]},
    },
    {
        "id": "ex-0013", "task": "locations", "group": "extract-locations-long",
        "notes": "長文の中から場所だけを二つ抜き出す。",
        "request": "塔を出ると、雨に濡れた石段が下へ続いていた。石段の終わりに半分崩れた門があり、その先に広い地下の倉があった。倉の奥からは水の音がする。番人は門の前で立ち止まり、これ以上は行かないと言った。",
        "output": {"locations": [
            {"name": "雨に濡れた石段", "description": "塔から下へ続く濡れた石段",
             "connections": ["半分崩れた門"], "secrets": []},
            {"name": "半分崩れた門", "description": "石段の終わりにある崩れかけた門",
             "connections": ["雨に濡れた石段", "地下の倉"], "secrets": []},
            {"name": "地下の倉", "description": "奥から水の音がする広い倉",
             "connections": ["半分崩れた門"], "secrets": ["奥に水の音がする"]},
        ]},
    },
    {
        "id": "ex-0014", "task": "npcs", "group": "extract-npcs-vague",
        "notes": "肩書きが曖昧な人物。分からない項目は空にする。",
        "request": "塔の近くに、白い服を着た人が一人いた。名前は聞かなかった。何の仕事をしているのかも分からない。ただ、こちらをずっと見ていた。",
        "output": {"npcs": [
            {"name": "白い服の人", "description": "塔の近くにいて、こちらを見つめていた人物",
             "personality": "", "occupation": "", "locationName": "塔の近く",
             "disposition": "警戒", "secrets": [], "combat": None},
        ]},
    },
    {
        "id": "ex-0015", "task": "quests", "group": "extract-quests-completed",
        "notes": "完了済みの依頼と、進行中の依頼が混在。",
        "request": "「塔の灯をともす」はもう終わった。灯油は届いた。代わりに「鍵を取り戻す」を頼む。番人に一度話してみてほしい。",
        "output": {"quests": [
            {"title": "塔の灯をともす", "description": "灯油を届けて灯をともす",
             "objectives": ["灯油を届ける"], "rewards": []},
            {"title": "鍵を取り戻す", "description": "失われた鍵を探す",
             "objectives": ["番人に話を聞く"], "rewards": []},
        ]},
    },
    {
        "id": "ex-0016", "task": "character_draft", "group": "extract-character-conflict",
        "notes": "希望が矛盾（武器を持たないのに剣の達人）。両方を入れず、矛盾を解消した形にする。",
        "request": "剣を一切持てない主人公を作ってほしい。でも剣術の達人で、必ず剣で戦う人。名前はゲルド。",
        "output": {
            "name": "ゲルド", "species": "人間", "occupation": "武器禁止の警備員",
            "background": "過去に剣で人を傷つけ、以来、刃物を携えないと誓った。",
            "personality": "約束を重んじる。",
            "attributes": {"str": 4, "dex": 3, "int": 2, "wil": 4, "per": 3, "cha": 2},
            "skills": [
                {"name": "体術", "level": 2, "description": "刃物を使わずに相手を制する"},
                {"name": "剣の知識", "level": 2, "description": "剣の構えと動きを読む（自身は使わない）"},
                {"name": "警戒", "level": 1, "description": "異常な気配を察知する"},
            ],
            "items": [
                {"name": "木の杖", "description": "携えることを許された棒", "quantity": 1,
                 "type": "weapon", "power": 2, "defense": 0, "tags": ["武器"]},
                {"name": "古い手袋", "description": "刃物を握らないための手袋", "quantity": 1,
                 "type": "misc", "power": 0, "defense": 0, "tags": ["誓い"]},
            ],
        },
    },
    {
        "id": "ex-0017", "task": "state_changes", "group": "extract-state-chat",
        "notes": "雑談で状態変更なし。空配列。",
        "context": CTX,
        "request": "ルカは番人に天気の話をした。番人も相槌を打つ。それだけのやり取りだった。",
        "output": {"stateChanges": []},
    },
    {
        "id": "ex-0018", "task": "memories", "group": "extract-memory-uncertain",
        "notes": "見ただけの推測は isConfirmed false。断定しない。",
        "context": CTX,
        "request": "影の男が地下の倉の前で何かを拾ったように見えた。手の中までは見えていない。番人はその様子に気づいていないと思う。",
        "output": {
            "memories": [
                {"category": "DISCOVERY", "content": "影の男が地下の倉の前で何かを拾ったように見えた。手の中は未確認。",
                 "importance": 3, "isConfirmed": False, "isSecret": False, "relatedIds": ["n2", "l2"]},
                {"category": "NPC_RELATION", "content": "番人は影の男に気づいていない可能性がある。",
                 "importance": 2, "isConfirmed": False, "isSecret": False, "relatedIds": ["n1", "n2"]},
            ],
            "npcMemories": [],
        },
    },
    {
        "id": "ex-0019", "task": "items", "group": "extract-items-none",
        "notes": "道具の記述がない。空配列。",
        "request": "彼は何も持たずに塔を出た。ポケットも空だった。",
        "output": {"items": []},
    },
    {
        "id": "ex-0020", "task": "quests", "group": "extract-quests-numeral",
        "notes": "「三つ」を数値に変換する。目標は文字列のまま。",
        "request": "やることは三つ。鍵を探す。番人に聞く。それから塔の灯を確認する。報酬は特に決まっていない。",
        "output": {"quests": [
            {"title": "鍵を探す", "description": "失われた鍵を探す",
             "objectives": ["鍵を探す"], "rewards": []},
            {"title": "番人に聞く", "description": "番人から話を聞く",
             "objectives": ["番人に話を聞く"], "rewards": []},
            {"title": "塔の灯を確認する", "description": "塔の灯の状態を確認する",
             "objectives": ["塔の灯を確認する"], "rewards": []},
        ]},
    },
]


def main() -> int:
    existing = set()
    if DEST.exists():
        for line in DEST.open(encoding="utf-8"):
            if line.strip():
                existing.add(json.loads(line)["id"])
    fresh = []
    for rec in RECORDS:
        if rec["id"] in existing:
            continue
        envelope = {
            "id": rec["id"], "category": "extraction", "task": rec["task"],
            "group": rec["group"], "notes": rec.get("notes", ""),
        }
        if "context" in rec:
            envelope["context"] = rec["context"]
        envelope["request"] = rec["request"]
        envelope["output"] = rec["output"]
        rep = schema_lib.validate_extraction(rec["task"], rec["output"], rec.get("context"))
        if not rep.ok:
            print(rec["id"], "INVALID", rep.errors)
            return 1
        fresh.append(envelope)
    with DEST.open("a", encoding="utf-8") as fh:
        for rec in fresh:
            fh.write(json.dumps(rec, ensure_ascii=False) + "\n")
    print(f"appended {len(fresh)} extraction records to {DEST.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
