#!/usr/bin/env python3
"""Fifth hand-authored extraction batch (magic tools / spells / attributes /
equipment effects / materials / alchemy).

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

DEST = ROOT / "raw" / "extraction" / "extraction_06.jsonl"

RECORDS = [
    {"id": "ex-0069", "task": "items", "group": "extract-items-spell-tablet-numeral-negation",
     "notes": "E-D 日本語数詞「四枚」「三包」「一つ」「二本」。E-E 湿って使えない導火の芯は数えない。",
     "request": "呪文を刻んだ板を整理したい。呪文板は四枚、星屑の粉は三包、黒い坩堝は一つある。導火の芯は二本あったが、湿って使えないので数えないでほしい。",
     "output": {"items": [
         {"name": "呪文板", "description": "呪文が刻まれた小さな木の板", "quantity": 4,
          "type": "magic", "power": 1, "defense": 0, "tags": ["魔法の道具", "呪文"]},
         {"name": "星屑の粉", "description": "錬成に使う淡く光る粉", "quantity": 3,
          "type": "material", "power": 0, "defense": 0, "tags": ["素材", "錬成"]},
         {"name": "黒い坩堝", "description": "呪文を煮詰めるための黒い鍋", "quantity": 1,
          "type": "tool", "power": 0, "defense": 0, "tags": ["道具", "錬成"]},
     ]}},
    {"id": "ex-0070", "task": "character_draft", "group": "extract-character_draft-alchemy-stats",
     "notes": "E-D 数詞「四つ」「三つ」をスキル数・道具数へ。レベル0のスキルを含む。装備の効果(power)を設定。",
     "request": "錬成師の人物を作って。名前はイオリ。能力は四つ、道具は三つでいい。律儀で、効能を確かめずに飲まない性格。",
     "output": {
         "name": "イオリ", "species": "人間", "occupation": "錬成師",
         "background": "古い炉を預かり、材料の効能を確かめながら錬成を続けてきた。",
         "personality": "律儀で、確証のないものは口にしない。",
         "attributes": {"str": 2, "dex": 3, "int": 5, "wil": 4, "per": 4, "cha": 2},
         "skills": [
             {"name": "錬成の知識", "level": 2, "description": "材料の配合と手順を組み立てる"},
             {"name": "薬効の選別", "level": 1, "description": "似た粉の効能を見分ける"},
             {"name": "火加減", "level": 1, "description": "炉の温度を一定に保つ"},
             {"name": "記録", "level": 0, "description": "配合を帳面に写す練習中"},
         ],
         "items": [
             {"name": "刻印された石", "description": "力がこもる印を刻んだ石", "quantity": 1,
              "type": "magic", "power": 2, "defense": 0, "tags": ["魔法の道具"]},
             {"name": "温もりの種", "description": "炉の熱を蓄える小さな種", "quantity": 1,
              "type": "misc", "power": 1, "defense": 0, "tags": ["素材"]},
             {"name": "反響の貝殻", "description": "唱えた音を少しだけ返す貝殻", "quantity": 1,
              "type": "magic", "power": 1, "defense": 0, "tags": ["魔法の道具"]},
         ],
     }},
    {"id": "ex-0071", "task": "state_changes", "group": "extract-state_changes-long-multi-magic",
     "notes": "E-F 長文に複数変更。E-H 代名詞「彼」＝影の男。E-E 買わなかった粉・何もしてこなかった影は変更なし。敵HPは触らない。", "context": CTX,
     "request": "ルカは塔の下を出て地下の倉へ降り、番人オルドに古い呪文の板のことを尋ねた。オルドはしぶしぶ答え、信頼が5上がった。『塔の灯をともす』の灯油集めも一つ進んだ。持っていた麻縄を一つほどいて失くし、乾パンを二つ食べた。星屑の粉は買わなかった。影の男が廊下にいたが、彼は何もしてこなかった。",
     "output": {"stateChanges": [
         {"type": "LOCATION_CHANGE", "targetId": "l2", "reason": "塔の下から地下の倉へ降りた"},
         {"type": "NPC_RELATION_DELTA", "targetId": "n1", "amount": 5, "reason": "オルドが呪文の板の話をしてくれた"},
         {"type": "QUEST_OBJECTIVE_PROGRESS", "targetId": "q2", "objectiveIndex": 0, "amount": 1,
          "reason": "灯油集めが一つ進んだ"},
         {"type": "ITEM_REMOVE", "targetId": "i2", "amount": 1, "reason": "麻縄を一つ失くした"},
         {"type": "ITEM_REMOVE", "targetId": "i3", "amount": 2, "reason": "乾パンを二つ食べた"},
     ]}},
    {"id": "ex-0072", "task": "memories", "group": "extract-memories-spell-rumor",
     "notes": "E-B 噂・推測は isConfirmed:false。E-D 数詞「三人ほど」。memories と npcMemories の両方を出す。", "context": CTX,
     "request": "地下の倉の壁に新しい呪文が刻まれていると影の男が囁いていた。本当かどうかは分からない。番人オルドはその噂を耳にしたかどうか怪しい様子だ。塔の下で灯油の商人が三人ほど道具を並べていたのも見かけた気がする。",
     "output": {
         "memories": [
             {"category": "DISCOVERY", "content": "地下の倉の壁に新しい呪文が刻まれていると影の男が囁いた。真偽は未確認。",
              "importance": 3, "isSecret": False, "isConfirmed": False, "relatedIds": ["n2", "l2"]},
             {"category": "WORLD", "content": "塔の下で灯油の商人が三人ほど道具を並べていたように見えた。人数ははっきりしない。",
              "importance": 2, "isSecret": False, "isConfirmed": False, "relatedIds": ["l1"]},
             {"category": "FORESHADOWING", "content": "番人オルドが呪文の噂を耳にした兆しがあるが、反応は読み取れなかった。",
              "importance": 2, "isSecret": False, "isConfirmed": False, "relatedIds": ["n1", "n2"]},
         ],
         "npcMemories": [
             {"npcId": "n1", "content": "影の男が呪文の話をしていたのが遠くで聞こえた。気にしないふりをした。",
              "importance": 2, "isSecret": False},
             {"npcId": "n2", "content": "塔の下の商人が灯油を並べているのを見た。呪文の板の噂も拾った。",
              "importance": 3, "isSecret": False},
         ],
     }},
    {"id": "ex-0073", "task": "items", "group": "extract-items-alchemy-materials-empty",
     "notes": "E-G 該当なし。呪文書の解釈の話だけで、素材名も道具も具体的に出ない。空配列。",
     "request": "古い呪文書の注釈を読んでいると、効能の記述だけが目に留まった。「この光は闇を払う」「いや、影を濃くする」と解釈が割れ、注釈者は結論を書かずに頁を閉じた。錬成の手順にはまだ触れていないし、素材の名前も出ていない。",
     "output": {"items": []}},
    {"id": "ex-0074", "task": "character_draft", "group": "extract-character_draft-contradiction-creed",
     "notes": "E-C 矛盾（呪文を使わない誓い vs 呪文板を常に携える）を解消。E-E 武器は否定。E-D 数詞「三つ」「四つ」。",
     "request": "呪文を一切使わないと誓っているのに、呪文綴りの板を常に肌身離さず持つ人物を作って。名前はレン。武器は持たない。能力は三つ、道具は四つ。",
     "output": {
         "name": "レン", "species": "人間", "occupation": "封印師",
         "background": "呪文を唱えない誓いを立てたが、呪文綴りの板は刻まれた文字を読むためだけに携えている。",
         "personality": "誓いを重んじ、無駄な言葉を避ける。",
         "attributes": {"str": 2, "dex": 3, "int": 4, "wil": 5, "per": 3, "cha": 2},
         "skills": [
             {"name": "刻字の読解", "level": 2, "description": "板に刻まれた文字を音に出さず読む"},
             {"name": "封印の所作", "level": 1, "description": "決まった手つきで印を結ぶ"},
             {"name": "観察", "level": 1, "description": "周囲の細かな変化に気づく"},
         ],
         "items": [
             {"name": "呪文綴りの板", "description": "唱えずに読むための刻字の板", "quantity": 1,
              "type": "magic", "power": 1, "defense": 0, "tags": ["魔法の道具"]},
             {"name": "沈黙の結び目", "description": "音を飲み込む小さな結び目", "quantity": 1,
              "type": "magic", "power": 0, "defense": 0, "tags": ["魔法の道具"]},
             {"name": "反響の貝殻", "description": "反響を確かめるための貝殻", "quantity": 1,
              "type": "misc", "power": 0, "defense": 0, "tags": ["素材"]},
             {"name": "布の包み", "description": "板を包むための柔らかい布", "quantity": 1,
              "type": "misc", "power": 0, "defense": 0, "tags": ["道具"]},
         ],
     }},
    {"id": "ex-0075", "task": "state_changes", "group": "extract-state_changes-negation-items",
     "notes": "E-E 否定文（買わなかった・残っている・移らなかった）は変更に反映しない。E-D 数詞「一つ」「二本」。敵HPは触らない。", "context": CTX,
     "request": "ルカは呪文板を買うつもりだったが、値が高いのでやめた。オルドに会って礼を言い、信頼が2上がった。乾パンを一つ食べたが、麻縄はまだ二本とも残っている。塔の下から地下の倉へは移らなかった。",
     "output": {"stateChanges": [
         {"type": "NPC_RELATION_DELTA", "targetId": "n1", "amount": 2, "reason": "オルドに礼を言った"},
         {"type": "ITEM_REMOVE", "targetId": "i3", "amount": 1, "reason": "乾パンを一つ食べた"},
     ]}},
    {"id": "ex-0076", "task": "npcs", "group": "extract-npcs-alchemist-contradiction",
     "notes": "E-C 矛盾（素材は譲らない vs 粉を渡した）。E-H 固有名詞「サギル」「影の男」と代名詞「彼」。locationName 必須。",
     "request": "塔の下の錬成師サギルは、素材は一切譲らないと言い切る。だが昨夜、彼が影の男に星屑の粉を渡しているのを見かけた。",
     "output": {"npcs": [
         {"name": "サギル", "description": "塔の下の錬成師。素材は一切譲らないと言い切るが、昨夜は影の男に星屑の粉を渡していた。",
          "personality": "言葉は硬いが、取引の話になると目を逸らす", "occupation": "錬成師",
          "locationName": "塔の下", "disposition": "警戒",
          "secrets": ["影の男に素材を密かに渡している"], "combat": None},
     ]}},
    {"id": "ex-0077", "task": "items", "group": "extract-items-equipment-effects",
     "notes": "E-D 数詞「三つ」「一つ」「二つ」。E-E 割れた鏡片は否定して除外。power/defense を装備の効果として設定。",
     "request": "装備の効果を記録する。星刻の指輪は三つあり、いずれも力をわずかに高める。静寂の鈴は一つで、音を消す効能がある。反射の外套は二つ。割れた鏡片はもう使わないので記録から外す。",
     "output": {"items": [
         {"name": "星刻の指輪", "description": "星の印を刻んだ指輪。力を少し高める", "quantity": 3,
          "type": "magic", "power": 2, "defense": 0, "tags": ["魔法の道具", "装備"]},
         {"name": "静寂の鈴", "description": "鳴らすと音を消す鈴", "quantity": 1,
          "type": "magic", "power": 1, "defense": 0, "tags": ["魔法の道具", "装備"]},
         {"name": "反射の外套", "description": "軽い打撃を跳ね返す外套", "quantity": 2,
          "type": "armor", "power": 0, "defense": 2, "tags": ["装備"]},
     ]}},
    {"id": "ex-0078", "task": "quests", "group": "extract-quests-nothing-magic-talk",
     "notes": "E-G 該当なし。古い道具を眺める雑談だけで依頼は出ない。空配列。",
     "request": "「この坩堝は古いね」「うん、昔の錬成の道具だ」「でも使い方は誰も知らない」。二人は坩堝を眺めて古い頃の話をし、依頼や仕事の話はしなかった。",
     "output": {"quests": []}},
    {"id": "ex-0079", "task": "state_changes", "group": "extract-state_changes-pronoun-alchemy",
     "notes": "E-H 代名詞「彼」＝番人オルド、「そこ」＝地下の倉を解決。E-E『教えられない』は進捗なし。ITEM_ADD に id を付けない。敵HPは触らない。", "context": CTX,
     "request": "ルカは塔の下から地下の倉へ移り、そこで錬成の基礎を学んで経験値が12増えた。番人オルドは『呪文の板の在処は教えられない』と言ったが、彼の表情は柔らかく、信頼が3上がった。星屑の粉を一つ手に入れたので、『錬成入門』と記録に残しておく。",
     "output": {"stateChanges": [
         {"type": "LOCATION_CHANGE", "targetId": "l2", "reason": "塔の下から地下の倉へ移った"},
         {"type": "XP_DELTA", "amount": 12, "reason": "錬成の基礎を学んだ"},
         {"type": "NPC_RELATION_DELTA", "targetId": "n1", "amount": 3, "reason": "オルドの表情が柔らかくなった"},
         {"type": "ITEM_ADD", "item": {"name": "星屑の粉", "description": "手に入れた光る粉",
                                       "quantity": 1, "type": "material", "power": 0, "defense": 0,
                                       "tags": ["素材", "錬成"]},
          "reason": "星屑の粉を一つ手に入れた"},
         {"type": "FLAG_SET", "targetId": "craft.alchemy.beginner", "value": "錬成の初歩を学んだ",
          "reason": "錬成入門と記録した"},
     ]}},
    {"id": "ex-0080", "task": "character_draft", "group": "extract-character_draft-equipment-effect",
     "notes": "E-D 数詞「五つ」。E-E『呪文は唱えない』はスキルに反映しない。能力5個・道具5個の上限とレベル0を含む0〜2を確認。装備の効果を設定。",
     "request": "装備の効果を重視する人物を作って。名前はミサオ。能力は五つ、道具は五つ。呪文は唱えないが、刻印で力を引き出す。",
     "output": {
         "name": "ミサオ", "species": "人間", "occupation": "刻印師",
         "background": "唱える代わりに道具へ刻印を施し、装備の効きを引き出す道を選んだ。",
         "personality": "口より手が先に動く。",
         "attributes": {"str": 3, "dex": 4, "int": 4, "wil": 3, "per": 3, "cha": 2},
         "skills": [
             {"name": "刻印の読み", "level": 2, "description": "刻まれた印の効能を読み取る"},
             {"name": "装備合わせ", "level": 1, "description": "複数の装備の効果を組み合わせる"},
             {"name": "素材の目利き", "level": 2, "description": "錬成向きの材料を見分ける"},
             {"name": "静かな動き", "level": 1, "description": "音を立てずに装備を整える"},
             {"name": "帳面", "level": 0, "description": "刻印の記録を書き始めたばかり"},
         ],
         "items": [
             {"name": "反響の篭手", "description": "打撃を跳ね返す篭手", "quantity": 1,
              "type": "armor", "power": 0, "defense": 2, "tags": ["装備"]},
             {"name": "雷の糸", "description": "刻印に使う痺れる糸", "quantity": 1,
              "type": "magic", "power": 2, "defense": 0, "tags": ["素材", "魔法の道具"]},
             {"name": "炉の火種", "description": "錬成の炉を点ける火種", "quantity": 1,
              "type": "misc", "power": 0, "defense": 0, "tags": ["素材"]},
             {"name": "刻字の板", "description": "印を試すための小さな板", "quantity": 1,
              "type": "magic", "power": 1, "defense": 0, "tags": ["魔法の道具"]},
             {"name": "防水の布", "description": "道具を湿気から守る布", "quantity": 1,
              "type": "misc", "power": 0, "defense": 1, "tags": ["道具"]},
         ],
     }},
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
