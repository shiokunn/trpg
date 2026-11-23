#!/usr/bin/env python3
"""Fourth hand-authored extraction batch (equipment / tools / magic items / effects).

Idempotent: records whose id already exists in the destination are skipped.
Every record is validated with schema_lib before anything is appended.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(HERE))
import schema_lib  # noqa: E402
from gen_extraction_j import CTX  # noqa: E402

DEST = ROOT / "raw" / "extraction" / "extraction_05.jsonl"

RECORDS = [
    {"id": "ex-0057", "task": "items", "group": "extract-items-equipment-numeral",
     "notes": "E-D 日本語数詞「三つ」「二個」。E-E 否定文の欠けた矢じりは数えない。",
     "request": "武具棚を改めている。鉄の小盾は三つ、皮の腕当ては二個、木の柄は一本ある。先端部が欠けた矢じりはもう使わないので数えないで。",
     "output": {"items": [
         {"name": "鉄の小盾", "description": "武具棚に並ぶ小さな鉄盾", "quantity": 3,
          "type": "armor", "power": 0, "defense": 2, "tags": ["装備", "盾"]},
         {"name": "皮の腕当て", "description": "革製の腕当て", "quantity": 2,
          "type": "armor", "power": 0, "defense": 1, "tags": ["装備"]},
         {"name": "木の柄", "description": "予備の木製の柄", "quantity": 1,
          "type": "misc", "power": 0, "defense": 0, "tags": ["材料"]},
     ]}},
    {"id": "ex-0058", "task": "character_draft", "group": "extract-character_draft-magic-tool-conflict",
     "notes": "E-C 矛盾（杖を持たない誓い vs 杖術の名人）を解消。E-E 否定文。アイテムは4個。",
     "request": "魔法の道具を扱う人を作って。名前はセラ。杖は絶対に持たないと誓っているのに、杖術の名人だと自称するんだ。能力は三つ、道具は四つくらいでいい。",
     "output": {
         "name": "セラ", "species": "人間", "occupation": "呪具師",
         "background": "かつて杖を操ったが、誓いを立ててからは道具に力を宿す道を選んだ。",
         "personality": "一度決めた誓いを曲げない。",
         "attributes": {"str": 2, "dex": 3, "int": 4, "wil": 4, "per": 3, "cha": 2},
         "skills": [
             {"name": "呪文の知識", "level": 2, "description": "道具に込める呪文を読み解く"},
             {"name": "道具扱い", "level": 1, "description": "小さな呪具を慎重に扱う"},
             {"name": "観察", "level": 1, "description": "道具の細かな変化に気づく"},
         ],
         "items": [
             {"name": "石の指輪", "description": "呪いを宿した小さな指輪", "quantity": 1,
              "type": "magic", "power": 2, "defense": 0, "tags": ["魔法の道具"]},
             {"name": "魔法の粉", "description": "光る白い粉", "quantity": 1,
              "type": "misc", "power": 0, "defense": 0, "tags": ["材料"]},
             {"name": "小さな鍋", "description": "呪文を煮詰めるための鍋", "quantity": 1,
              "type": "tool", "power": 0, "defense": 0, "tags": ["道具"]},
             {"name": "空の瓶", "description": "粉を入れておく瓶", "quantity": 1,
              "type": "misc", "power": 0, "defense": 0, "tags": ["容器"]},
         ],
     }},
    {"id": "ex-0059", "task": "state_changes", "group": "extract-state_changes-long-multi",
     "notes": "E-F 長文に複数変更。E-E 否定文・未実施は反映しない。代名詞「彼」＝オルド。敵HPは触らない。", "context": CTX,
     "request": "ルカは地下の倉まで降り、番人オルドに鍵のありかを再度尋ねた。彼は渋ったが答えてくれ、信頼が6上がった。『鍵を取り戻す』の最初の目標も片づいた。乾パンを二つ食べて減らし、短剣は先に置いてきたのでもう持っていない。麻縄は結び直しただけで減っていない。灯油は結局買わなかった。影の男が遠くで見ていたが、何も起きなかった。",
     "output": {"stateChanges": [
         {"type": "LOCATION_CHANGE", "targetId": "l2", "reason": "地下の倉まで降りた"},
         {"type": "NPC_RELATION_DELTA", "targetId": "n1", "amount": 6, "reason": "オルドが鍵のありかを教えた"},
         {"type": "QUEST_OBJECTIVE_PROGRESS", "targetId": "q1", "objectiveIndex": 0, "amount": 1,
          "reason": "番人から話を聞いた"},
         {"type": "ITEM_REMOVE", "targetId": "i3", "amount": 2, "reason": "乾パンを二つ食べた"},
         {"type": "ITEM_REMOVE", "targetId": "i1", "amount": 1, "reason": "短剣を置いてきた"},
     ]}},
    {"id": "ex-0060", "task": "items", "group": "extract-items-magic-unknown-empty",
     "notes": "E-A 情報不足。中身が判別できないので空配列。",
     "request": "魔法の道具が入っていそうな箱を見つけた。蓋は固く閉まっていて、呪いの品なのかただの石なのか分からない。開ける前に記録だけ残しておく。",
     "output": {"items": []}},
    {"id": "ex-0061", "task": "memories", "group": "extract-memories-magic-ambiguous",
     "notes": "E-B 曖昧な見聞きは isConfirmed:false。memories と npcMemories の両方を出す。", "context": CTX,
     "request": "影の男が地下の倉の前で灯油の瓶を拾ったように見えた。呪文を唱えていたのかもしれないが、声までは聞こえていない。番人オルドは何も見ていないふうだった。",
     "output": {
         "memories": [
             {"category": "DISCOVERY", "content": "影の男が地下の倉の前で灯油の瓶を拾ったように見えた。手の中までは未確認。",
              "importance": 3, "isSecret": False, "isConfirmed": False, "relatedIds": ["n2", "l2"]},
             {"category": "UNRESOLVED", "content": "影の男が呪文を唱えていた可能性があるが、声は確認できていない。",
              "importance": 2, "isSecret": False, "isConfirmed": False, "relatedIds": ["n2"]},
         ],
         "npcMemories": [
             {"npcId": "n1", "content": "ルカが地下の倉の前を気にしているのが気になった。",
              "importance": 2, "isSecret": False},
         ],
     }},
    {"id": "ex-0062", "task": "character_draft", "group": "extract-character_draft-lamp-numeral",
     "notes": "E-D 数詞「三つ」を能力数に、「二つ」を道具数に変換。",
     "request": "灯を司る人物を作って。名前はヒナ。能力は三つ、道具は二つでいい。この世に火を灯すのが役目。",
     "output": {
         "name": "ヒナ", "species": "人間", "occupation": "灯守",
         "background": "古い灯台で火を絶やさない役目を続けてきた。",
         "personality": "言葉少なに灯を見守る。",
         "attributes": {"str": 2, "dex": 4, "int": 3, "wil": 3, "per": 4, "cha": 2},
         "skills": [
             {"name": "灯り調整", "level": 2, "description": "火力を細かく調整して保つ"},
             {"name": "火起こし", "level": 1, "description": "少ない材料で火を点ける"},
             {"name": "風読み", "level": 1, "description": "風の気配から火の危険を測る"},
         ],
         "items": [
             {"name": "火打ち石", "description": "擦ると火花が出る石", "quantity": 1,
              "type": "tool", "power": 0, "defense": 0, "tags": ["道具"]},
             {"name": "油の壺", "description": "灯に注ぐための油", "quantity": 1,
              "type": "misc", "power": 0, "defense": 0, "tags": ["燃料"]},
         ],
     }},
    {"id": "ex-0063", "task": "state_changes", "group": "extract-state_changes-potion-add",
     "notes": "HP_DELTA はPCのみ。ITEM_ADD に id を付けない。E-E「捨てずに」は変更なし。", "context": CTX,
     "request": "ルカは回復の薬を飲み、淡い光に包まれてHPが4回復した。空になった瓶は捨てずに持ち帰った。オルドから古い鍵を一つ受け取り、信頼が3上がった。腹が減ったので乾パンを一つ食べた。",
     "output": {"stateChanges": [
         {"type": "HP_DELTA", "amount": 4, "reason": "回復の薬を飲んだ"},
         {"type": "ITEM_ADD", "item": {"name": "古い鍵", "description": "オルドから受け取った古い鍵",
                                       "quantity": 1, "type": "misc", "power": 0, "defense": 0,
                                       "tags": ["鍵"]},
          "reason": "鍵を一つ受け取った"},
         {"type": "NPC_RELATION_DELTA", "targetId": "n1", "amount": 3, "reason": "オルドから鍵を受け取った"},
         {"type": "ITEM_REMOVE", "targetId": "i3", "amount": 1, "reason": "乾パンを一つ食べた"},
     ]}},
    {"id": "ex-0064", "task": "npcs", "group": "extract-npcs-magic-merchant",
     "notes": "E-C 矛盾（魔法の道具は扱わないと言いながら光る杖を並べる）。locationName 必須。",
     "request": "塔の下に店を出す道具商のナイルは、魔法の道具は一切扱わないと断言する。だが棚には光る杖が並んでいる。",
     "output": {"npcs": [
         {"name": "ナイル", "description": "魔法の道具を扱わないと公言しながら、棚には光る杖を並べる道具商。",
          "personality": "言葉は強いが目は泳ぐ", "occupation": "道具商",
          "locationName": "塔の下", "disposition": "警戒",
          "secrets": ["実は魔法の道具を密かに売っている"], "combat": None},
     ]}},
    {"id": "ex-0065", "task": "items", "group": "extract-items-spell-chat-none",
     "notes": "E-G 該当なし。魔法の効果の議論だけで道具は登場しない。",
     "request": "「この呪文は風を呼ぶ」「いや、火を呼ぶはずだ」「どちらにせよ練習が要るな」。三人は道端で魔法の効果を言い合い、道具の話はしなかった。",
     "output": {"items": []}},
    {"id": "ex-0066", "task": "character_draft", "group": "extract-character_draft-no-weapon-negation",
     "notes": "E-C 道具を持たないと言い張りながら囊を背負う矛盾を解消。E-E 武器は否定。",
     "request": "道具は何も持たないと言い張るが、実際には囊を背負っている人物を作って。名前はダグ。武器は絶対に持たない。",
     "output": {
         "name": "ダグ", "species": "人間", "occupation": "運び屋",
         "background": "道具には頼らないと語りながら、荷はいつも背負っている。",
         "personality": "頑固で、人の機嫌を伺うのが苦手。",
         "attributes": {"str": 4, "dex": 2, "int": 2, "wil": 3, "per": 3, "cha": 2},
         "skills": [
             {"name": "荷運び", "level": 2, "description": "重い荷を長く運ぶ"},
             {"name": "道案内", "level": 1, "description": "道順を覚えて案内する"},
             {"name": "火起こし", "level": 1, "description": "野営の火を点ける"},
         ],
         "items": [
             {"name": "布の囊", "description": "背負うための大きな布袋", "quantity": 1,
              "type": "misc", "power": 0, "defense": 0, "tags": ["荷物"]},
             {"name": "水筒", "description": "携帯用の水筒", "quantity": 1,
              "type": "misc", "power": 0, "defense": 0, "tags": ["荷物"]},
             {"name": "麻のロープ", "description": "荷を縛る麻縄", "quantity": 1,
              "type": "tool", "power": 0, "defense": 0, "tags": ["道具"]},
         ],
     }},
    {"id": "ex-0067", "task": "state_changes", "group": "extract-state_changes-no-change",
     "notes": "E-G 該当なし。会話だけで所持品も場所も変わらない。", "context": CTX,
     "request": "ルカは番人オルドと魔法の道具について話した。オルドは首を横に振るだけで、何も渡さなかった。ルカも何も買わず、塔の下に留まった。",
     "output": {"stateChanges": []}},
    {"id": "ex-0068", "task": "items", "group": "extract-items-magic-count-conflict",
     "notes": "E-C 矛盾する個数は実物を採用。E-D 数詞「二個」「三つ」。",
     "request": "呪いの粉は二個あると聞かされたが、袋を開けると一つしか入っていなかった。ほかに光る石が三つ、割れた水晶が一つある。",
     "output": {"items": [
         {"name": "呪いの粉", "description": "二個あると言われたが実際は一つだけ", "quantity": 1,
          "type": "misc", "power": 0, "defense": 0, "tags": ["魔法の道具"]},
         {"name": "光る石", "description": "淡く光る小さな石", "quantity": 3,
          "type": "misc", "power": 1, "defense": 0, "tags": ["魔法の道具"]},
         {"name": "割れた水晶", "description": "端が割れた水晶", "quantity": 1,
          "type": "misc", "power": 0, "defense": 0, "tags": ["材料"]},
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
