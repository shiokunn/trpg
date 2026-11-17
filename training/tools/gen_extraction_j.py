#!/usr/bin/env python3
"""Third hand-authored extraction batch (edge cases). Idempotent."""

from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(HERE))
import schema_lib  # noqa: E402
from gen_extraction_h import CTX  # noqa: E402

DEST = ROOT / "raw" / "extraction" / "extraction_04.jsonl"

RECORDS = [
    {"id": "ex-0045", "task": "character_draft", "group": "extract-character-numeral-age",
     "notes": "E-D 日本語数詞。年齢と所持品の数を数値へ変換する。",
     "request": "十六歳の見習い職人を作って。所持品は三つだけで、そのうち二つは道具。名前はミナ。",
     "output": {
         "name": "ミナ", "species": "人間", "occupation": "見習い職人",
         "background": "十六歳から工房に通い、木を削る仕事を覚えた。",
         "personality": "黙々と手を動かす。",
         "attributes": {"str": 2, "dex": 4, "int": 3, "wil": 2, "per": 4, "cha": 3},
         "skills": [
             {"name": "木細工", "level": 2, "description": "木を正確に削る"},
             {"name": "寸法読み", "level": 1, "description": "目で寸法を測る"},
             {"name": "掃除", "level": 1, "description": "工房を片付ける"},
         ],
         "items": [
             {"name": "小さな鉋", "description": "使い込まれた鉋", "quantity": 1,
              "type": "tool", "power": 0, "defense": 0, "tags": ["道具"]},
             {"name": "巻き尺", "description": "布製の巻き尺", "quantity": 1,
              "type": "tool", "power": 0, "defense": 0, "tags": ["道具"]},
             {"name": "木工の欠片", "description": "作業後に残った木の欠片", "quantity": 1,
              "type": "misc", "power": 0, "defense": 0, "tags": ["材料"]},
         ],
     }},
    {"id": "ex-0046", "task": "state_changes", "group": "extract-state-multi-negation",
     "notes": "E-F 長文に複数変更。E-E 否定文は変更に反映しない。敵HPは動かさない。", "context": CTX,
     "request": "ルカは地下の倉まで足を運び、番人オルドに鍵のありかを聞いた。オルドは面倒がりながらも答えてくれ、信頼が7上がった。『鍵を取り戻す』の目標を一つ片づけた。腹が減ったので乾パンを二つ食べ、麻縄もほどいて一つ失くした。短剣は先に置いてきたので、もう持っていない。灯油は結局集めなかった。",
     "output": {"stateChanges": [
         {"type": "LOCATION_CHANGE", "targetId": "l2", "reason": "地下の倉まで足を運んだ"},
         {"type": "NPC_RELATION_DELTA", "targetId": "n1", "amount": 7, "reason": "鍵のありかを聞かせてもらった"},
         {"type": "QUEST_OBJECTIVE_PROGRESS", "targetId": "q1", "objectiveIndex": 0, "amount": 1,
          "reason": "番人から話を聞いた"},
         {"type": "ITEM_REMOVE", "targetId": "i3", "amount": 2, "reason": "乾パンを二つ食べた"},
         {"type": "ITEM_REMOVE", "targetId": "i2", "amount": 1, "reason": "麻縄を一つ失くした"},
         {"type": "ITEM_REMOVE", "targetId": "i1", "amount": 1, "reason": "短剣を置いてきた"},
     ]}},
    {"id": "ex-0047", "task": "memories", "group": "extract-memory-unconfirmed",
     "notes": "E-B 曖昧な見聞きは isConfirmed:false。memories と npcMemories の両方を出す。", "context": CTX,
     "request": "影の男が地下の倉の前で何かを拾ったように見えた。手の中までは見えていない。番人オルドには見えていなかったと思うが、定かではない。",
     "output": {
         "memories": [
             {"category": "DISCOVERY", "content": "影の男が地下の倉の前で何かを拾ったように見えた。手の中は未確認。",
              "importance": 3, "isSecret": False, "isConfirmed": False, "relatedIds": ["n2", "l2"]},
             {"category": "NPC_RELATION", "content": "番人オルドは影の男の動きに気づいていないかもしれない。",
              "importance": 2, "isSecret": False, "isConfirmed": False, "relatedIds": ["n1", "n2"]},
         ],
         "npcMemories": [
             {"npcId": "n1", "content": "ルカが倉の前をうろついていたのが気になった。", "importance": 2, "isSecret": False},
         ],
     }},
    {"id": "ex-0048", "task": "items", "group": "extract-items-unknown",
     "notes": "E-A 情報不足で空配列。中身が特定できない道具は出力しない。",
     "request": "カバンの中に何か入っているようだ。布に包まれていて、それが道具なのか食料なのかは分からない。中身を確認する前だ。",
     "output": {"items": []}},
    {"id": "ex-0049", "task": "locations", "group": "extract-locations-pronoun",
     "notes": "E-H 代名詞と固有名詞の混在。彼女・そこ・あれを固有名詞に解決する。",
     "request": "ミナは灰の塔を出た。彼女の足は自然とあの古い橋へ向かった。そこを渡ると、川のほとりに廃墟があった。あれが『水車小屋』だ。その奥にもう一つ、苔むした穴が見えた。",
     "output": {"locations": [
         {"name": "灰の塔", "description": "ミナが出発した塔",
          "connections": ["古い橋"], "secrets": []},
         {"name": "古い橋", "description": "灰の塔から続く古い橋",
          "connections": ["灰の塔", "川のほとり"], "secrets": []},
         {"name": "川のほとり", "description": "廃墟が建つ川辺",
          "connections": ["古い橋", "水車小屋"], "secrets": []},
         {"name": "水車小屋", "description": "川のほとりにある廃墟",
          "connections": ["川のほとり", "苔むした穴"], "secrets": []},
         {"name": "苔むした穴", "description": "水車小屋の奥にある苔むした穴",
          "connections": ["水車小屋"], "secrets": ["奥から水の音がする"]},
     ]}},
    {"id": "ex-0050", "task": "npcs", "group": "extract-npcs-contradict",
     "notes": "E-C 矛盾する発言。古くからいる vs 昨日着いたを一貫した記述に統合する。locationName 必須。",
     "request": "塔の下にいる番人のオルドは、自分はここに長くいると言う一方、昨日着いたばかりだとも言った。剣は一切使わないと誓っているのに、いつも剣を帯びている。",
     "output": {"npcs": [
         {"name": "オルド", "description": "塔の下にいる番人。古くからいると自称するが、実際は最近着いたばかりらしい。帯びた剣は抜かないと誓っている。",
          "personality": "気難しいが、約束は守る", "occupation": "番人",
          "locationName": "塔の下", "disposition": "慎重",
          "secrets": ["古くからいると偽っている"], "combat": None},
     ]}},
    {"id": "ex-0051", "task": "quests", "group": "extract-quests-idle-chat",
     "notes": "E-G 該当なし・雑談。依頼の話が出ないので空配列。",
     "request": "「朝だ。まずは粥を炊こう」。二人は炉の前で天気の話をし、それから黙って火を見つめた。依頼の話は出なかった。",
     "output": {"quests": []}},
    {"id": "ex-0052", "task": "character_draft", "group": "extract-character-no-weapon",
     "notes": "E-E 否定文。武器を持たないので武器以外のスキル・所持品にする。",
     "request": "戦うのは嫌いだ。武器は持たない。名前はテオ。荷物は軽いほうがいい。三つくらいの道具があれば十分だ。",
     "output": {
         "name": "テオ", "species": "人間", "occupation": "行商人",
         "background": "争いを避けながら各地を巡り、小さな品を売って生計を立てている。",
         "personality": "穏やかで、めんどうごとを避ける。",
         "attributes": {"str": 1, "dex": 3, "int": 4, "wil": 2, "per": 4, "cha": 4},
         "skills": [
             {"name": "交渉", "level": 2, "description": "穏やかに値を掛け合う"},
             {"name": "荷造り", "level": 1, "description": "少ない荷物を効率よくまとめる"},
             {"name": "様子見", "level": 1, "description": "場の空気を静かに読む"},
         ],
         "items": [
             {"name": "布の袋", "description": "軽い布でできた袋", "quantity": 1,
              "type": "misc", "power": 0, "defense": 0, "tags": ["荷物"]},
             {"name": "水筒", "description": "小さな水筒", "quantity": 1,
              "type": "misc", "power": 0, "defense": 0, "tags": ["荷物"]},
             {"name": "算盤", "description": "値を計算するための算盤", "quantity": 1,
              "type": "tool", "power": 0, "defense": 0, "tags": ["道具"]},
         ],
     }},
    {"id": "ex-0053", "task": "state_changes", "group": "extract-state-pronoun-mix",
     "notes": "E-H 代名詞「彼」と固有名詞「オルド」が混在。どちらも n1 を指す。", "context": CTX,
     "request": "ルカは番人オルドに礼を言った。彼も『礼なんて要らない』と言ったが、さっきより打ち解けた感じで信頼が4上がった。その後ルカは依頼をもう一度確認し、灯油を一つ買い足した。麻縄はそのまま残っている。",
     "output": {"stateChanges": [
         {"type": "NPC_RELATION_DELTA", "targetId": "n1", "amount": 4, "reason": "礼を言って打ち解けた"},
         {"type": "FLAG_SET", "targetId": "npc.ord.thanked", "value": "ルカが礼を言った", "reason": "礼を伝えた"},
         {"type": "ITEM_ADD", "item": {"name": "灯油", "description": "買い足した灯油", "quantity": 1,
                                       "type": "misc", "power": 0, "defense": 0, "tags": ["燃料"]},
          "reason": "灯油を一つ買い足した"},
     ]}},
    {"id": "ex-0054", "task": "memories", "group": "extract-memory-numeral-past",
     "notes": "E-D 日本語数詞（三日前・二度・一度）を記憶内容に反映。両方の配列を出す。", "context": CTX,
     "request": "三日前、影の男が地下の倉の前で二度ほど様子をうかがっていた。番人オルドも一度だけ顔を出した。どちらも記憶に留めておきたい。",
     "output": {
         "memories": [
             {"category": "EVENT", "content": "三日前、影の男が地下の倉の前を二度うかがっていた。",
              "importance": 3, "isConfirmed": True, "isSecret": False, "relatedIds": ["n2", "l2"]},
             {"category": "NPC_RELATION", "content": "番人オルドが一度だけ地下の倉に顔を出した。",
              "importance": 2, "isConfirmed": True, "isSecret": False, "relatedIds": ["n1", "l2"]},
         ],
         "npcMemories": [
             {"npcId": "n1", "content": "三日前に一度だけ地下の倉を訪ねた。", "importance": 2, "isSecret": False},
         ],
     }},
    {"id": "ex-0055", "task": "items", "group": "extract-items-count-conflict",
     "notes": "E-C 矛盾する個数。二つと言うが実物は一つだけなので実際の数を採用する。",
     "request": "持っている鍵は二つだと主張したが、懐にあったのは一つだけだった。もう一つは失くしたらしい。他に、切れた紐が一本ある。",
     "output": {"items": [
         {"name": "古い鍵", "description": "二つあると言っていたが実際は一つだけ", "quantity": 1,
          "type": "misc", "power": 0, "defense": 0, "tags": ["鍵"]},
         {"name": "切れた紐", "description": "端が切れた紐", "quantity": 1,
          "type": "tool", "power": 0, "defense": 0, "tags": ["道具"]},
     ]}},
    {"id": "ex-0056", "task": "quests", "group": "extract-quests-multi-objective",
     "notes": "E-F 長文に三つの依頼と複数目標が混在。objectives は文字列配列。",
     "request": "町の掲示に三つの仕事が出ていた。一つ目は『川のゴミを片付ける』。二つ目は『失踪した子を探す』で、まず近所を聞き歩き、それから川辺を捜す二段構え。三つ目は『古い時計を直す』。報酬は特に書いていない。",
     "output": {"quests": [
         {"title": "川のゴミを片付ける", "description": "川辺に溜まったゴミを集める",
          "objectives": ["川のゴミを片付ける"], "rewards": []},
         {"title": "失踪した子を探す", "description": "行方不明の子の手がかりを探す",
          "objectives": ["近所を聞き歩く", "川辺を捜す"], "rewards": []},
         {"title": "古い時計を直す", "description": "止まった古い時計を修理する",
          "objectives": ["古い時計を直す"], "rewards": []},
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
