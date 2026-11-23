#!/usr/bin/env python3
"""Second hand-authored extraction batch (edge cases). Idempotent."""

from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(HERE))
import schema_lib  # noqa: E402
from gen_extraction_h import CTX  # noqa: E402

DEST = ROOT / "raw" / "extraction" / "extraction_03.jsonl"

RECORDS = [
    {"id": "ex-0021", "task": "character_draft", "group": "extract-character-numbers",
     "notes": "日本語の数詞と年齢。数値に変換する。",
     "request": "三年前から塔に住む、十二歳の見習いを作って。力は弱め、器用さはそこそこ。名前はピア。",
     "output": {
        "name": "ピア", "species": "人間", "occupation": "見習い",
        "background": "三年前から塔に住み、雑用をしながら技術を習っている。",
        "personality": "素直で、失敗を隠さない。",
        "attributes": {"str": 1, "dex": 4, "int": 3, "wil": 2, "per": 4, "cha": 3},
        "skills": [
            {"name": "雑用", "level": 1, "description": "塔の作業をこなす"},
            {"name": "観察", "level": 2, "description": "細かな変化に気づく"},
            {"name": "記録", "level": 1, "description": "見たことを書き留める"},
        ],
        "items": [
            {"name": "小さな箒", "description": "雑用用の箒", "quantity": 1,
             "type": "tool", "power": 0, "defense": 0, "tags": ["道具"]},
            {"name": "木炭", "description": "二個の木炭", "quantity": 2,
             "type": "misc", "power": 0, "defense": 0, "tags": ["燃料"]},
        ],
    }},
    {"id": "ex-0022", "task": "state_changes", "group": "extract-state-multi",
     "notes": "長文に複数の変更が混在。進行とフラグと関係値。", "context": CTX,
     "request": "ルカは番人オルドに鍵のことを聞き、trust が6上がった。地下の倉の探索を一歩進め、「倉の鍵を預かった」と記録した。乾パンはまだ残っている。",
     "output": {"stateChanges": [
        {"type": "NPC_RELATION_DELTA", "targetId": "n1", "amount": 6, "reason": "話を聞いてもらった"},
        {"type": "QUEST_OBJECTIVE_PROGRESS", "targetId": "q1", "objectiveIndex": 0, "amount": 1,
         "reason": "番人から話を聞いた"},
        {"type": "FLAG_SET", "targetId": "store.key", "value": "倉の鍵を預かった", "reason": "鍵を受け取った"},
     ]}},
    {"id": "ex-0023", "task": "memories", "group": "extract-memory-secret",
     "notes": "GM専用の秘密は isSecret true。",
     "request": "番人は実だけ見せて、鍵を隠し持っている。プレイヤーにはまだ知らせない。",
     "output": {
        "memories": [
            {"category": "WORLD", "content": "番人は鍵を隠し持っている。プレイヤーには未公開。",
             "importance": 4, "isSecret": True, "isConfirmed": True, "relatedIds": []},
        ],
        "npcMemories": [],
    }},
    {"id": "ex-0024", "task": "items", "group": "extract-items-stats",
     "notes": "威力・防御・タグを含む道具。",
     "request": "手に入れたのは、攻撃2の古い短剣と、防御3の鉄の盾。どちらも一个ずつ。",
     "output": {"items": [
        {"name": "古い短剣", "description": "刃が欠けた短剣", "quantity": 1,
         "type": "weapon", "power": 2, "defense": 0, "tags": ["武器"]},
        {"name": "鉄の盾", "description": "重いが丈夫な盾", "quantity": 1,
         "type": "armor", "power": 0, "defense": 3, "tags": ["防具"]},
     ]}},
    {"id": "ex-0025", "task": "locations", "group": "extract-locations-secret",
     "notes": "場所に秘匿情報を付ける。",
     "request": "塔の裏手に、普段は閉じられた小さな門がある。その先の穴には、古い骨が積まれているらしい。",
     "output": {"locations": [
        {"name": "塔の裏手の小さな門", "description": "普段は閉じられている門",
         "connections": [], "secrets": []},
        {"name": "門の先の穴", "description": "古い骨が積まれているらしい穴",
         "connections": ["塔の裏手の小さな門"], "secrets": ["古い骨が積まれている"]},
     ]}},
    {"id": "ex-0026", "task": "npcs", "group": "extract-npcs-combat",
     "notes": "戦闘能力を持つNPC。hpは1〜30。",
     "request": "影の男は長い爪を持ち、攻撃5、防御2、hp十二。技能は攻撃2、防御1。",
     "output": {"npcs": [
        {"name": "影の男", "description": "長い爪を持つ男", "personality": "無言",
         "occupation": "", "locationName": "塔の下", "disposition": "敵対",
         "secrets": [],
         "combat": {"attackPower": 5, "defense": 2, "attackSkillLevel": 2,
                    "defenseSkillLevel": 1, "hp": 12}},
     ]}},
    {"id": "ex-0027", "task": "quests", "group": "extract-quests-none",
     "notes": "依頼の話が出てこない。空配列。",
     "request": "「今日はまず飯を食おう」。二人は炉の前に座った。",
     "output": {"quests": []}},
    {"id": "ex-0028", "task": "state_changes", "group": "extract-state-combat-start",
     "notes": "戦闘開始。敵HPは変更しない。", "context": CTX,
     "request": "影の男が短剣を抜いた。ルカも構えた。戦闘が始まる。男のHPはまだ十二のまま。",
     "output": {"stateChanges": [
        {"type": "COMBAT_START", "targetId": "n2", "reason": "影の男が短剣を抜いた"},
     ]}},
    {"id": "ex-0029", "task": "memories", "group": "extract-memory-npc-only",
     "notes": "NPCメモリだけが残る例。",
     "request": "番人オルドは、ルカに鍵の場所を教えたことを後で思い返していた。",
     "output": {
        "memories": [],
        "npcMemories": [
            {"npcId": "n1", "content": "ルカに地下の倉の鍵の場所を教えた。", "importance": 3, "isSecret": False},
        ],
    }},
    {"id": "ex-0030", "task": "character_draft", "group": "extract-character-thin",
     "notes": "情報が非常に少ない。それでもスキル3個・所持品2個は満たす。",
     "request": "旅人を作って。名前はソラ。あまり話さない人。",
     "output": {
        "name": "ソラ", "species": "人間", "occupation": "旅人",
        "background": "", "personality": "あまり話さない。",
        "attributes": {"str": 3, "dex": 3, "int": 3, "wil": 3, "per": 3, "cha": 1},
        "skills": [
            {"name": "歩く", "level": 1, "description": "長距離を歩く"},
            {"name": "荷物の整理", "level": 1, "description": "荷を効率よく詰める"},
            {"name": "様子見", "level": 1, "description": "静かに状況をうかがう"},
        ],
        "items": [
            {"name": "旅袋", "description": "一枚の布でできた袋", "quantity": 1,
             "type": "misc", "power": 0, "defense": 0, "tags": ["荷物"]},
            {"name": "水筒", "description": "小さな水筒", "quantity": 1,
             "type": "misc", "power": 0, "defense": 0, "tags": ["荷物"]},
        ],
    }},
    {"id": "ex-0031", "task": "items", "group": "extract-items-empty-phrase",
     "notes": "「拾った」「盗んだ」などの動詞から道具を抽出する。",
     "request": "拾ったのは美しい石。盗んだのは銀のコップ。捨てたのは古い靴。",
     "output": {"items": [
        {"name": "美しい石", "description": "拾った石", "quantity": 1,
         "type": "misc", "power": 0, "defense": 0, "tags": ["拾得物"]},
        {"name": "銀のコップ", "description": "盗んだ銀のコップ", "quantity": 1,
         "type": "misc", "power": 0, "defense": 0, "tags": ["盗品"]},
        {"name": "古い靴", "description": "捨てられた古い靴", "quantity": 1,
         "type": "misc", "power": 0, "defense": 0, "tags": ["廃品"]},
     ]}},
    {"id": "ex-0032", "task": "state_changes", "group": "extract-state-no-enemy-hp",
     "notes": "戦闘の記述でも敵HPを変更しない。PCのHPだけ。", "context": CTX,
     "request": "影の男を斬った。男はまだ立っている。ルカの手に浅い傷ができ、1点ダメージ。",
     "output": {"stateChanges": [
        {"type": "HP_DELTA", "amount": -1, "reason": "手に浅い傷を負った"},
     ]}},
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
        env = {
            "id": rec["id"], "category": "extraction", "task": rec["task"],
            "group": rec["group"], "notes": rec["notes"],
        }
        if "context" in rec:
            env["context"] = rec["context"]
        env["request"] = rec["request"]
        env["output"] = rec["output"]
        rep = schema_lib.validate_extraction(rec["task"], rec["output"], rec.get("context"))
        if not rep.ok:
            print(rec["id"], "INVALID", rep.errors)
            return 1
        fresh.append(env)
    with DEST.open("a", encoding="utf-8") as fh:
        for rec in fresh:
            fh.write(json.dumps(rec, ensure_ascii=False) + "\n")
    print(f"appended {len(fresh)} extraction records to {DEST.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
