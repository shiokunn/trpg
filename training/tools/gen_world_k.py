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

DEST = ROOT / "raw" / "world_json" / "world_04.jsonl"


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
        "id": "world-0010", "group": "world-hourglass-tower",
        "notes": "砂時計の塔。減る砂と過去のやり直し。戦闘NPCは塔に潜む学者1人のみ。",
        "request": "次のキーワード・雰囲気から、独創的なTRPG世界と短編シナリオを作ってください。\nキーワード: 砂時計, 塔, 時間\n雰囲気: 静かな切迫\n想定難易度: 難しい\n導入には開始地点1つ、NPCを2〜4人、クエストを1〜3件含めてください。",
        "output": normal({
            "name": "逆さ砂の塔トキロ",
            "genre": "時間ミステリ",
            "era": "砂が時を測る唯一の道具とされた時代",
            "setting": "街の中心にそびえる七層の塔。頂点に据えられた巨大な砂時計の砂が落ちる音が街じゅうに響き、町人はその音で一日を知る。砂が尽きれば街の時間が止まると信じられている。",
            "atmosphere": "細かい砂の落下音と、静まり返った石の螺旋。灯りの揺れだけが時を告げる。",
            "history": "百年前、初代時計師が塔を建て、砂の量で一日を刻ませた。以来、時計師の家系が塔を守り続けてきた。砂が尽きた年、街は同じ一日を二度経験したという伝説が残る。",
            "geography": "塔は七層の螺旋階段で、最上階に砂時計、中段に作業場、地下に砂の貯蔵庫がある。街は塔を中心に同心円状に広がり、外ほど古い建物が並ぶ。",
            "socialStructure": "時計師が塔と時間を管理し、砂運びの組合が地下から砂を運ぶ。町の役所は塔の記録で日付を決め、学者たちは塔の仕掛けを外から調べている。",
            "factions": ["塔の時計師一家", "砂運びの組合", "町の役所", "塔を調べる学者"],
            "techMagic": "砂時計、歯車、砂のろ過と貯蔵の装置。魔法ではなく、砂の流れで時を測るからくりが使われる。",
            "specialSettings": ["塔の砂が尽きると、その日はもう一度やり直せる", "砂時計の砂は一度に全て落ちることはない"],
            "objective": "砂が減る原因を突き止め、塔の砂時計が止まるのを防ぐ。",
            "problem": "砂時計の砂が三日で半分になった。時計師は不作を疑うが、地下の貯蔵庫の砂は少しも減っていない。",
            "secrets": ["砂を盗み出しているのは時計師の弟子で、過去のある一日をやり直そうとしている", "塔の砂時計は逆さにすると、過ぎた日の砂が戻る"],
            "scenario": {
                "title": "逆しまの砂",
                "premise": "塔の砂が急速に減っている。貯蔵庫は満杯なのに、時計師は砂を補充できない。弟子がこっそり砂を運び出す姿を見たプレイヤーは、塔の頂点で禁じられた砂時計の逆転が行われようとしていることを知る。過去をやり直そうとする者を止められるか。",
                "length": "4〜5時間", "difficulty": "難しい",
                "openingHook": "真夜中、塔の砂時計が逆に回り始め、今朝聞いたはずの音がもう一度聞こえてきた。",
            },
            "opening": {
                "location": {
                    "name": "塔の螺旋階段",
                    "description": "石の螺旋が七層続き、壁に砂の落下音が反響する。所々に小さな砂時計がはめ込まれ、最上階へ続く最後の段だけが暗い。",
                    "connections": ["砂時計の間", "時計師の作業場", "地下の砂蔵"],
                    "secrets": ["一段目の砂時計だけ、砂の流れが他のものと逆向きである"],
                },
                "npcs": [
                    npc("時計師センガ", "白い髭の老人。砂時計の首を毎日丁寧に拭いている。", "静かだが、砂のことになると細かい", "時計師", "警戒", "時計師の作業場",
                        secrets=["砂が減る理由を知っているが、口にしない"]),
                    npc("弟子のユキ", "若い女性。袖の中に砂を溜め込んでいる。", "従順に見えるが目が落ち着かない", "時計師の弟子", "協力的", "塔の螺旋階段",
                        secrets=["過去のある一日をやり直したいと思っている"]),
                    npc("砂運びの親方タモ", "肩に砂袋を担ぐ男。地下と地上を往復する。", "陽気だが、塔の奥へは決して入らない", "砂運びの親方", "友好的", "地下の砂蔵",
                        secrets=["貯蔵庫の砂が減っていないことを知っている"]),
                    npc("塔に潜む学者ミヅ", "黒い外套の男。砂時計の間をうろつく。", "冷静で、目的を聞かれても答えない", "塔を調べる学者", "敵対", "砂時計の間",
                        secrets=["砂を逆転させる手順を知っている"],
                        combat={"attackPower": 6, "defense": 3, "attackSkillLevel": 2,
                                "defenseSkillLevel": 2, "hp": 14}),
                ],
                "quests": [
                    {"title": "減る砂の謎", "description": "貯蔵庫と塔の砂を照合し、減る原因を追う",
                     "objectives": ["砂時計の砂の減り具合を記録する", "地下の砂蔵を調べる"], "rewards": ["時計師の信任"]},
                    {"title": "逆転を止める", "description": "禁じられた砂の逆転を食い止める",
                     "objectives": ["弟子が運んだ砂の行き先を探す", "砂時計の間へ上がる"], "rewards": ["塔の鍵"]},
                    {"title": "消えた一日", "description": "塔に伝わるやり直しの話の真偽を確かめる",
                     "objectives": ["古い記録を読み解く"], "rewards": []},
                ],
            },
        }),
    },
    {
        "id": "world-0011", "group": "world-night-station",
        "notes": "夜だけ現れる駅。臨時列車と行き先を忘れた乗客。戦闘NPCは保守係1人のみ。",
        "request": "次のキーワード・雰囲気から、独創的なTRPG世界と短編シナリオを作ってください。\nキーワード: 夜の駅, 臨時列車, 乗客\n雰囲気: 夢とうつつ\n想定難易度: 普通\n導入には開始地点1つ、NPCを2〜4人、クエストを1〜3件含めてください。",
        "output": normal({
            "name": "零時の駅ヨルミ",
            "genre": "夢幻ファンタジー",
            "era": "鉄道が街から姿を消しはじめた時代",
            "setting": "街の外れにある小さな駅。昼は使われていないが、夜零時になると時刻表にない臨時列車が霧の中から姿を現し、見知らぬ乗客を降ろす。乗客は皆、自分がどこへ向かうのかを忘れている。",
            "atmosphere": "ホームの黄色い灯りと霧。列車のきしみと、時計の針が逆に進むような感覚。",
            "history": "数十年前、この駅で最終列車が事故に遭った。以来、同じ時刻に列車が現れ、乗客を降ろしては消えるを繰り返している。駅員だけがこの駅に常駐する。",
            "geography": "単線のホームが一つあり、駅舎に待合室と古い時刻表、裏手に保守の小屋がある。線路は霧の中へまっすぐ続く。",
            "socialStructure": "駅員が乗客の世話をし、町の役所はこの駅を存在しないものとして扱う。降りた乗客の中に、元駅員が紛れているという噂がある。",
            "factions": ["夜の駅の駅員", "迷い乗客", "町の役所", "線路の保守係"],
            "techMagic": "古い時刻表、ランプ、手旗。列車が幻なのか実体なのかは、誰にも判別がつかない。",
            "specialSettings": ["零時を過ぎると、降りた乗客の記憶が駅に残る", "時刻表に載っていない列車だけがこの駅に停まる"],
            "objective": "降りた乗客を本来の行き先へ送り出し、駅に残った記憶を整理する。",
            "problem": "今夜の臨時列車がいつもより一人多く乗客を降ろした。駅員は『誰か一人、行き先を間違えた』と呟く。",
            "secrets": ["乗客の一人は、過去にこの駅で事故った元駅員である", "臨時列車は消えた乗客を毎夜同じ駅へ運び直している"],
            "scenario": {
                "title": "零時の乗客",
                "premise": "零時の臨時列車がいつもより一人多く乗客を降ろした。乗客たちの持物と記憶をたどり、本来の行き先を見つけて送り出そうとする。だが一人は、自分が昔この駅で働いていたことを思い出そうとしている。",
                "length": "3〜4時間", "difficulty": "普通",
                "openingHook": "零時を回った瞬間、時刻表のない列車が霧の中から姿を現し、扉が静かに開いた。",
            },
            "opening": {
                "location": {
                    "name": "夜の駅のホーム",
                    "description": "単線のホームに黄色い灯りが点在する。霧が線路を飲み込み、古い時刻表の紙が風に揺れる。零時を指す時計だけが動いている。",
                    "connections": ["駅舎の待合室", "線路の保守小屋", "霧の向こうの線路"],
                    "secrets": ["時刻表の最後の欄に、消された列車名がうっすら残っている"],
                },
                "npcs": [
                    npc("駅員のサキ", "古い制服の女性。手旗を常に携える。", "丁寧だが、駅のことを聞かれると曖昧になる", "駅員", "協力的", "夜の駅のホーム",
                        secrets=["乗客が一人増えた理由を知っている"]),
                    npc("乗客の老女オモ", "年配の女性。手提げ袋を抱えている。", "ぼんやりしているが、話せば優しい", "乗客", "友好的", "駅舎の待合室",
                        secrets=["自分がこの駅へ来るのは二度目だと気づいている"]),
                    npc("若い乗客ハル", "リュックを背負った青年。切符を握りしめている。", "焦っているが、質問には答える", "乗客", "中立", "夜の駅のホーム",
                        secrets=["行き先を紙に書いたのに、その紙を失くした"]),
                    npc("霧の保守係ゲン", "古い道具を担いだ男。線路から離れようとしない。", "無愛想で、近づく者を拒む", "線路の保守係", "敵対", "霧の向こうの線路",
                        secrets=["自分が元駅員だったことを思い出せない"],
                        combat={"attackPower": 5, "defense": 3, "attackSkillLevel": 1,
                                "defenseSkillLevel": 2, "hp": 12}),
                ],
                "quests": [
                    {"title": "行き先を探す", "description": "乗客の持物と記憶から本来の行き先を割り出す",
                     "objectives": ["乗客の持物を調べる", "時刻表の消された欄を読む"], "rewards": ["駅員の礼"]},
                    {"title": "一人多く降りた理由", "description": "増えた乗客の正体を確かめる",
                     "objectives": ["保守係に話を聞く", "霧の向こうの線路を歩く"], "rewards": ["古い切符"]},
                    {"title": "記憶を送り出す", "description": "駅に残った記憶を本来の持ち主へ返す",
                     "objectives": ["待合室に残る記憶を整理する"], "rewards": []},
                ],
            },
        }),
    },
    {
        "id": "world-0012", "group": "world-mushroom-market",
        "notes": "胞子の森の朝市。濃くなる森と消える朝。戦闘NPCは炭焼き小屋の男1人のみ。",
        "request": "次のキーワード・雰囲気から、独創的なTRPG世界と短編シナリオを作ってください。\nキーワード: きのこ, 森, 市場\n雰囲気: 朝霧と土の香り\n想定難易度: 易しい\n導入には開始地点1つ、NPCを2〜4人、クエストを1〜3件含めてください。",
        "output": normal({
            "name": "胞子の森の市場コモ",
            "genre": "森の日常ファンタジー",
            "era": "森が年ごとに濃くなる時代",
            "setting": "深い森の奥に、朝だけ開く市場がある。茸を育てる人々が集まり、胞子の粉や乾かした傘を商品にする。森は日ごとに濃くなり、霧が晴れるまでの開店時間が少しずつ短くなっている。",
            "atmosphere": "湿った土と茸の匂い、低くただよう朝霧、傘の下の薄暗がり。",
            "history": "村人が森の恵みを分け合うために市場を始めた。しかし近年、森が異常に育ち朝の光が届かなくなった。古い『森の求め』の言い伝えが残っている。",
            "geography": "森の中央に円形の空地があり、周囲に茸棚、奥に古い祠と森への小道がある。東に小川、西に廃れた炭焼き小屋が建つ。",
            "socialStructure": "茸師が菌を育て、市場の仲買が値をつける。森守りが祠を守り、村人は朝のうちに取引を終えるならわしだ。",
            "factions": ["茸師の一家", "市場の仲買", "森守り", "炭焼き小屋の男"],
            "techMagic": "菌の培養、胞子の粉、乾燥と保存の技術。魔法ではなく、森そのものの生命力が不思議がられる。",
            "specialSettings": ["市場は朝霧が晴れるまでの間だけ開く", "森が濃くなるほど、朝の時間が短くなる"],
            "objective": "森が濃くなる原因を探し、市場が開ける朝を取り戻す。",
            "problem": "今朝は霧が晴れず、市場が開けなかった。森守りは『森が何かを求めている』とだけ言う。",
            "secrets": ["炭焼き小屋の男が森の奥で禁じられた菌を育てている", "祠の下に、森と村を結ぶ古い約束の札が埋まっている"],
            "scenario": {
                "title": "朝が消える森",
                "premise": "朝霧が晴れず、市場が開けない。茸師の娘とともに森の奥を調べると、炭焼き小屋の男が禁じられた菌を育てている姿が見つかる。村と森の古い約束を思い出すところから、解決の糸口が見えてくる。",
                "length": "2〜3時間", "difficulty": "易しい",
                "openingHook": "朝なのに森が暗い。霧が晴れず、棚に並ぶはずの茸がまだ湿っている。",
            },
            "opening": {
                "location": {
                    "name": "森の市場の空地",
                    "description": "木々に囲まれた円形の空地に簡易な棚が並ぶ。朝霧が低く立ちこめ、茸の傘に水滴が光る。祠へ続く小道だけが薄暗い。",
                    "connections": ["茸師の小屋", "古い祠", "炭焼き小屋", "森奥の小川"],
                    "secrets": ["棚の下に、前夜誰かが撒いた胞子の粉が残っている"],
                },
                "npcs": [
                    npc("茸師の娘モモ", "若い女性。軍手に土をつけている。", "明るく、森の話をしたがる", "茸師", "協力的", "森の市場の空地",
                        secrets=["父が祠の近くで古い札を見つけた"]),
                    npc("森守りの老女シイ", "白い髪の女性。祠の前で手を合わせる。", "静かだが、はっきり言う", "森守り", "友好的", "古い祠",
                        secrets=["森と村の古い約束を知っている"]),
                    npc("仲買の男トク", "尺を持った男。値段を大声で言う。", "せっかちだが、客には親切", "市場の仲買", "中立", "森の市場の空地",
                        secrets=["朝が短くなる前に儲けようとしている"]),
                    npc("炭焼き小屋の男クル", "煤けた男。森の奥で何かを育てている。", "人を寄せつけない", "炭焼き", "敵対", "炭焼き小屋",
                        secrets=["禁じられた菌を育てている"],
                        combat={"attackPower": 4, "defense": 2, "attackSkillLevel": 1,
                                "defenseSkillLevel": 0, "hp": 9}),
                ],
                "quests": [
                    {"title": "朝霧を晴らす", "description": "市場が開ける朝を取り戻す",
                     "objectives": ["棚の胞子の粉を調べる", "森の奥の菌を確かめる"], "rewards": ["新鮮な茸の籠"]},
                    {"title": "森との約束", "description": "祠に埋まった古い札を探す",
                     "objectives": ["祠の下を掘る", "森守りに話を聞く"], "rewards": ["森守りのお守り"]},
                    {"title": "暴走した菌を止める", "description": "炭焼き小屋の男を止め、森を元に戻す",
                     "objectives": ["炭焼き小屋へ向かう"], "rewards": []},
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
