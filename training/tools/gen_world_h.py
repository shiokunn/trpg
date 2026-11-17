#!/usr/bin/env python3
"""Add hand-authored WorldDraft samples. Idempotent; validates before writing."""

from __future__ import annotations

import copy
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(HERE))
import schema_lib  # noqa: E402

DEST = ROOT / "raw" / "world_json" / "world_02.jsonl"


def npc(name, desc, personality, occupation, disposition, location_name,
        secrets=None, combat=None):
    return {
        "name": name, "description": desc, "personality": personality,
        "occupation": occupation, "disposition": disposition,
        "locationName": location_name, "secrets": secrets or [], "combat": combat,
    }


def normal(world: dict) -> dict:
    w = copy.deepcopy(world)
    w.setdefault("factions", [])
    w.setdefault("specialSettings", [])
    w.setdefault("secrets", [])
    op = w["opening"]
    op["location"].setdefault("secrets", [])
    for n in op["npcs"]:
        n.setdefault("secrets", [])
        n.setdefault("combat", None)
    for q in op["quests"]:
        q.setdefault("rewards", [])
        for obj in q["objectives"]:
            if isinstance(obj, str):
                continue
    return w


WORLDS = [
    {
        "id": "world-0005", "group": "world-rail-graveyard",
        "notes": "廃線車両墓地。解体の順番をめぐる対立。戦闘NPCはcombat付き、他はnull。",
        "request": "次のキーワード・雰囲気から、独創的なTRPG世界と短編シナリオを作ってください。\nキーワード: 廃線, 車両墓地, 解体\n雰囲気: 錆と郷愁\n想定難易度: 易しい\n導入には開始地点1つ、NPCを2〜4人、クエストを1〜3件含めてください。",
        "output": normal({
            "name": "錆の車両墓地ガラ",
            "genre": "スチームパンク",
            "era": "鉄道が廃れた時代",
            "setting": "山間の谷に何百両もの廃車が積み上げられ、住民がその部品を剥がして暮らす。朽ちた車体の間には細い道が通り、夜になると車掌の鈴が勝手に鳴る。",
            "atmosphere": "鉄のきしみと、風に揺れる鈴の音",
            "history": "三十年前の鉄道敷設の失敗で車両がここに放置された。以来、解体組合が部品の順番を決めてきた。",
            "geography": "谷に車両の層が連なり、頂上に古い機関車が一台残る。麓に製鉄所の計量場がある。",
            "socialStructure": "解体組合が作業の順番を管理し、住民はその順に従う。組み外れた者は製鉄所へ売られる部品を拾う。",
            "factions": ["解体組合", "車両に住む人々", "製鉄所の買い手"],
            "techMagic": "蒸気式の起重機、手工具、溶接の道具。超自然の要素はない。",
            "specialSettings": ["夜になると車両の鈴が鳴る", "解体の順番は組合の印章で決まる"],
            "objective": "消えた車両の番号を特定し、解体夫の行方を追う。",
            "problem": "解体予定の車両が一両だけ番号を消されている。",
            "secrets": ["買い手が帳簿を書き換えて車両を密かに買い取っている"],
            "scenario": {
                "title": "消えた番号",
                "premise": "刻印を削られた車両が一両ある。解体夫はその車両を控えるよう言い残していた。組合の帳簿と拓本を照合するうち、買い手の動きが見えてくる。",
                "length": "3〜4時間", "difficulty": "易しい",
                "openingHook": "車体の前に拓本を広げた解体夫が、刻印を削られた迹を見つける。",
            },
            "opening": {
                "location": {
                    "name": "車両の谷",
                    "description": "錆びた車体が層になって立ち、細い足場がその間を縫う。風に揺れる鈴が絶えず鳴る。",
                    "connections": ["機関車の頂上", "製鉄所の計量場"],
                    "secrets": ["削られた刻印の下に元の番号が残っている"],
                },
                "npcs": [
                    npc("組合長ガトー", "赤いスカーフの男性。順番の話しかしない。", "事務的", "組合長", "協力的", "車両の谷"),
                    npc("住人ミナ", "車両の室内に住む老女。鈴の音を気にする。", "神経質", "住人", "友好的", "車両の谷"),
                    npc("買い手の代理クロ", "黒い外套の男性。名乗らない。", "質問に別の質問で返す", "買い手の代理", "警戒", "製鉄所の計量場"),
                    npc("谷のならず者", "部品を漁る男。組合から追い出された。", "短気", "元解体夫", "敵対", "車両の谷",
                        combat={"attackPower": 5, "defense": 2, "attackSkillLevel": 1,
                                "defenseSkillLevel": 1, "hp": 8}),
                ],
                "quests": [
                    {"title": "消えた番号", "description": "削られた刻印から元の番号を読み取る",
                     "objectives": ["刻印の拓本を取る", "帳簿と照合する"], "rewards": ["組合からの謝礼"]},
                    {"title": "解体夫の捜索", "description": "行方不明の解体夫の足跡をたどる",
                     "objectives": ["最後の足跡をたどる"], "rewards": []},
                ],
            },
        }),
    },
    {
        "id": "world-0006", "group": "world-memory-bazaar",
        "notes": "記憶を売る市場。瓶の複製と倫理。医療・取引・推測の扱い。",
        "request": "次のキーワード・雰囲気から、独創的なTRPG世界と短編シナリオを作ってください。\nキーワード: 記憶, 市場, 取引\n雰囲気: 甘く危うい\n想定難易度: 難しい\n導入には開始地点1つ、NPCを2〜4人、クエストを1〜3件含めてください。",
        "output": normal({
            "name": "記憶の露天市モル",
            "genre": "都市ファンタジー",
            "era": "記憶が商品になった時代",
            "setting": "夜だけ開く市場で、ガラスの瓶に記憶が詰めて売られる。売った人はその記憶を思い出せない。役所は取引を禁じていない。",
            "atmosphere": "蝋燭の匂いと、瓶が触れ合う音",
            "history": "市場ができて二十年。最初は嘆きを消すための施設だったが、いまでは貴族の嗜好になっている。",
            "geography": "路地に棚が連なり、奥に鑑定の台がある。さらに奥に仕入れの記録が置かれる。",
            "socialStructure": "記憶商が仲介し、鑑定士が値をつける。監視役が帳簿を抽査する。",
            "factions": ["記憶商組合", "市の監視役", "買い手の貴族"],
            "techMagic": "記憶を瓶に移す硝子細工。開けた瓶は一度しか使えない。",
            "specialSettings": ["瓶の記憶は一度しか開けられない", "売った記憶は元の人に返せない"],
            "objective": "妹の記憶を売った人物を見つけ、瓶の出所を確かめる。",
            "problem": "妹が自分の名を忘れて戻ってきた。同じラベルの瓶が三つ並んでいる。",
            "secrets": ["鑑定士が記憶を複製して帳簿に載せずに転売している"],
            "scenario": {
                "title": "三つの同じ瓶",
                "premise": "妹の記憶らしい瓶が三つある。どれが本物かを判別するうち、複製の存在と帳簿の空白が見えてくる。瓶を開ける前に、誰の記憶かを確かめなければならない。",
                "length": "4〜5時間", "difficulty": "難しい",
                "openingHook": "妹が「川辺の石」の話をしていたことを思い出し、棚の同じラベルの三つの瓶に気づく。",
            },
            "opening": {
                "location": {
                    "name": "露天の棚",
                    "description": "蝋燭に照らされた棚に、同じ形の瓶がずらりと並ぶ。ラベルの筆跡だけが微妙に違う。",
                    "connections": ["鑑定の台", "市場の奥"],
                    "secrets": ["三つの瓶のうち一つは複製である"],
                },
                "npcs": [
                    npc("記憶商サレ", "小柄な男性。棚の整理を欠かさない。", "値段の話になると妥協しない", "記憶商", "中立", "露天の棚"),
                    npc("鑑定士ユナ", "白い手袋の女性。中身よりラベルを見る。", "慎重", "鑑定士", "警戒", "鑑定の台"),
                    npc("妹リセ", "自分の名を忘れた女性。周囲の表情をうかがう。", "控えめ", "", "協力的", "露天の棚"),
                    npc("監視役の役人", "帳簿を確認に来る男。", "疑い深い", "監視役", "中立", "市場の奥",
                        combat={"attackPower": 3, "defense": 4, "attackSkillLevel": 1,
                                "defenseSkillLevel": 2, "hp": 9}),
                ],
                "quests": [
                    {"title": "妹の記憶を取り戻す", "description": "売られた瓶を特定して買い戻す",
                     "objectives": ["瓶の出所を確かめる", "買い手を突き止める"], "rewards": ["妹の記憶"]},
                    {"title": "瓶の複製", "description": "同じ記憶の瓶が並ぶ理由を調べる",
                     "objectives": ["帳簿と瓶を照合する"], "rewards": []},
                ],
            },
        }),
    },
]


def main() -> int:
    existing = set()
    if DEST.exists():
        for line in DEST.open(encoding="utf-8"):
            if line.strip():
                existing.add(json.loads(line)["id"])
    fresh = []
    for wd in WORLDS:
        if wd["id"] in existing:
            continue
        rec = {
            "id": wd["id"], "category": "world_json", "task": "world_json",
            "group": wd["group"], "notes": wd["notes"],
            "request": wd["request"], "output": wd["output"],
        }
        rep = schema_lib.validate_world(wd["output"])
        if not rep.ok:
            print(wd["id"], "INVALID", rep.errors)
            return 1
        if rep.warnings:
            print(wd["id"], "WARN", rep.warnings)
        fresh.append(rec)
    with DEST.open("a", encoding="utf-8") as fh:
        for rec in fresh:
            fh.write(json.dumps(rec, ensure_ascii=False) + "\n")
    print(f"appended {len(fresh)} world records to {DEST.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
