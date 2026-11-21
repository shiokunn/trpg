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

DEST = ROOT / "raw" / "world_json" / "world_05.jsonl"


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
        "id": "world-0013", "group": "world-sung-law",
        "notes": "歌声で法が書き換わる国。消えた楽譜の頁と禁歌。戦闘NPCは覆面の詠い手1人のみ。",
        "request": "次のキーワード・雰囲気から、独創的なTRPG世界と短編シナリオを作ってください。\nキーワード: 歌声, 法律, 議会\n雰囲気: 儀式めいた熱\n想定難易度: 難しい\n導入には開始地点1つ、NPCを2〜4人、クエストを1〜3件含めてください。",
        "output": normal({
            "name": "唄律の国ソラニ",
            "genre": "音楽法政ファンタジー",
            "era": "法が譜面に記され、声で唱えられる時代",
            "setting": "円形の議事堂を中心にした小国。ここでは法は文字ではなく旋律で定められ、議会は『詠い手』と呼ばれる歌い手が歌うことで法律が書き換わる。歌が美しく響くほど法は強くなり、音程が狂えば法も歪む。国中の法典は楽譜の束として大切に保管されている。",
            "atmosphere": "蝋燭の揺れと、石の広間に反響する歌声。儀式のような熱気が空気を張りつめさせている。",
            "history": "建国の昔、初代の詠い手が一節を歌って国の基本法を定めた。以来、法を歌う技は座に受け継がれ、歌い手は貴族に審査されながら議事の広間で腕を競う。近年、楽譜に記録のない旋律が夜ごと広間に響くという噂が広がっている。",
            "geography": "都の中央にドーム屋根の議事堂があり、中央に歌壇、周囲に三段の傍聴席が取り巻く。地下には楽譜の蔵が広がり、道を隔てて詠い手を育てる合唱館が建つ。広間へ続く回廊は反響を計算して湾曲している。",
            "socialStructure": "詠い手の座が法を歌い、楽譜係がそれを記述して法典を守る。貴族の審査会が歌の出来を裁き、市民は傍聴席から見守るだけである。歌を唱えられぬ者は法から遠ざけられる。",
            "factions": ["詠い手の座", "楽譜を守る記録所", "貴族の審査会", "禁歌を広める一派"],
            "techMagic": "譜面と音叉、反響を測る湾曲した壁。魔法ではなく、声の音程と広間の反響が法の効力を決めると信じられている。",
            "specialSettings": ["詠い手が歌うと、その場の法が一時的に書き換わる", "三日たつまでに楽譜へ写されなかった歌は消える"],
            "objective": "正体不明の歌で書き換えられた法を元に戻し、詠い手の座を守る。",
            "problem": "誰も歌っていない夜に法が変わり、翌朝には楽譜にない条文が町に告げられている。記録所の楽譜からは該当の頁が消えている。",
            "secrets": ["覆面の詠い手が禁じられた古い歌で法を書き換えている", "消えた楽譜の頁は記録所の奥ではなく合唱館に隠されている"],
            "scenario": {
                "title": "消えた歌の法",
                "premise": "議事の広間で誰も歌っていないのに法が変わる。楽譜係とともに消えた頁を探すうち、覆面の詠い手が禁じられた古い旋律で国を支配しようとしていることがわかる。正しい歌を取り戻し、法を元へ戻せるか。",
                "length": "4〜5時間", "difficulty": "難しい",
                "openingHook": "夜明け前、広間の歌壇から誰も居ないはずの歌声が響き、新しい法が町に告げられた。",
            },
            "opening": {
                "location": {
                    "name": "議事の広間",
                    "description": "ドームの天井に歌声が吸い込まれる円形の広間。中央の石の歌壇を三段の傍聴席が囲み、湾曲した壁が音を広間の隅へ運ぶ。地下の楽譜蔵へ降りる階段だけが暗い。",
                    "connections": ["詠い手の控室", "楽譜の蔵", "合唱館", "貴族の審査席"],
                    "secrets": ["歌壇の石に、楽譜にない音程が刻まれている"],
                },
                "npcs": [
                    npc("詠い手の長ミハル", "銀の喉当てをつけた女性。朝ごとに声を整えている。", "誇り高く、法のことになると譲らない", "詠い手の座の長", "協力的", "議事の広間",
                        secrets=["法が勝手に変わる理由を知っているが、座の名折れと恐れている"]),
                    npc("楽譜係の老人ヤス", "抱えきれない楽譜の束を腕に抱える老人。", "几帳面で、頁の減りに気づいている", "楽譜係", "友好的", "楽譜の蔵",
                        secrets=["消えた頁が合唱館へ運ばれたのを見た"]),
                    npc("貴族の審査役リエル", "金色の眼鏡をかけた貴族。歌の出来を冷たく採点する。", "皮肉で、詠い手をよく戒める", "貴族の審査役", "警戒", "貴族の審査席",
                        secrets=["禁歌の噂を耳にしながら座を守っている"]),
                    npc("覆面の詠い手デン", "黒い覆面で顔を隠した歌い手。合唱館の奥で声を練る。", "冷徹で、自分こそ正しい法を歌うと信じる", "禁歌の詠い手", "敵対", "合唱館",
                        secrets=["古代の法を復活させようとしている"],
                        combat={"attackPower": 7, "defense": 3, "attackSkillLevel": 2,
                                "defenseSkillLevel": 1, "hp": 16}),
                ],
                "quests": [
                    {"title": "消えた頁を追う", "description": "楽譜から消えた条文の行方を探す",
                     "objectives": ["楽譜の蔵を調べる", "合唱館の帳面を確かめる"], "rewards": ["記録所の信任"]},
                    {"title": "法を正す歌", "description": "書き換えられた法を元の旋律へ戻す",
                     "objectives": ["歌壇に刻まれた音程を読み解く", "ミハルと歌を合わせる"], "rewards": ["詠い手の証"]},
                    {"title": "覆面を剥ぐ", "description": "禁歌を唱える者を止める",
                     "objectives": ["合唱館の奥へ踏み込む"], "rewards": []},
                ],
            },
        }),
    },
    {
        "id": "world-0014", "group": "world-sugar-refinery",
        "notes": "砂糖の精製都市。純度が身分を決め、鍵のかかった蔵の白砂糖が消える。戦闘NPCは密糖の運び屋1人のみ。",
        "request": "次のキーワード・雰囲気から、独創的なTRPG世界と短編シナリオを作ってください。\nキーワード: 砂糖, 精製所, 純度\n雰囲気: 甘く粘りつく\n想定難易度: 易しい\n導入には開始地点1つ、NPCを2〜4人、クエストを1〜3件含めてください。",
        "output": normal({
            "name": "糖晶の精製都市シロミ",
            "genre": "都市職人ミステリ",
            "era": "白い砂糖が富と身分を測る時代",
            "setting": "砂糖を精製する事業が街の心臓。甘茎という作物を搾り、不純物を落として白い結晶にする。純度が高いほど価値が上がり、市民は白さで身分を競う。手に入りにくい黒い糖蜜は倉庫で固く封じられている。",
            "atmosphere": "煮詰める甘い匂い、靴底に張りつく糖蜜、結晶が光る蔵の薄暗がり。",
            "history": "昔、この地で特産の甘茎から砂糖を取る技術が生まれ、精製所の周囲に街が育った。以来、精製の腕が家の格を決め、純度を競う祭りが毎年開かれる。最近、上質の白砂糖が蔵から姿を消している。",
            "geography": "街の中央に巨大な精製所がどんと構え、煮沸棟、結晶室、糖蔵が並ぶ。精製所の裏に廃糖蜜の池が広がり、街の外れに密造の小屋が点在する。",
            "socialStructure": "精製所の親方が工程管理と格付けを握り、甘味鑑定人が純度を判定する。糖蔵の番人が在庫を見守り、労働者は結晶室で腕を磨く。",
            "factions": ["精製所の親方衆", "甘味鑑定人の組合", "糖蔵の番人", "密糖を運ぶ一派"],
            "techMagic": "搾汁機、煮沸釜、結晶を篩う網と濾過の布。魔法ではなく温度と時間の加減で白さが決まる。",
            "specialSettings": ["純度の等級がそのまま市民の身分として扱われる", "一度固まった黒糖は、湯でも溶けにくい"],
            "objective": "消える白砂糖の謎を解き、精製所の信用を取り戻す。",
            "problem": "先月より、鍵のかかった糖蔵から上質の白砂糖が少しずつ減っている。在庫帳は整合しているのに、実際の袋は軽い。",
            "secrets": ["蔵の番人が純度の記録を書き換えて、等級を水増ししている", "消えた白砂糖は裏の廃糖蜜の池で密造の甘味に混ぜられている"],
            "scenario": {
                "title": "白さの値",
                "premise": "鍵のかかった糖蔵から白砂糖が消える。在庫帳は合っているのに袋は軽い。甘味鑑定人とともに記録と結晶を照合するうち、身分を守るための純度の偽装と、裏での密造が見えてくる。",
                "length": "3〜4時間", "difficulty": "易しい",
                "openingHook": "朝の検分で、開けたはずの糖蔵の袋が、昨夜より明らかに軽くなっていた。",
            },
            "opening": {
                "location": {
                    "name": "精製所の結晶室",
                    "description": "煮沸棟の脇に並ぶ結晶室。棚に白い結晶の篩が並び、甘い湯気が窓を曇らせる。床は糖蜜でほんのり粘り、奥の糖蔵へ続く扉だけが重い。",
                    "connections": ["煮沸棟", "糖蔵", "甘味鑑定の間", "廃糖蜜の池", "密造の小屋"],
                    "secrets": ["篩の目に、通常より粗い粒が引っかかっている"],
                },
                "npcs": [
                    npc("精製所の親方ブン", "広い前掛けの男。釜の火加減を目で見て指示する。", "黙々と働くが、帳面にはうるさい", "精製所の親方", "協力的", "精製所の結晶室",
                        secrets=["在庫の減りに気づいているが、騒ぎを嫌う"]),
                    npc("甘味鑑定人アヤ", "小さな皿を覗き込む女性。舌で純度を当てる。", "冷静で、甘さに絶対の自信を持つ", "甘味鑑定人", "友好的", "甘味鑑定の間",
                        secrets=["先月の等級判定に不審な点があった"]),
                    npc("糖蔵の番人キタ", "大きな鍵を首から下げた男。扉を開けたがらない。", "無口で、在庫のことを聞かれると黙る", "糖蔵の番人", "警戒", "糖蔵",
                        secrets=["純度の記録を書き換えている"]),
                    npc("密糖の運び屋ダマ", "糖蜜だらけの外套の男。夜に小屋へ向かう。", "素っ気なく、追われると口を閉ざす", "密糖の運び屋", "敵対", "密造の小屋",
                        secrets=["裏の池で密造の甘味を作っている"],
                        combat={"attackPower": 5, "defense": 3, "attackSkillLevel": 1,
                                "defenseSkillLevel": 2, "hp": 11}),
                ],
                "quests": [
                    {"title": "減る白砂糖", "description": "鍵のかかった糖蔵の在庫を照合する",
                     "objectives": ["在庫帳と袋の重さを比べる", "結晶室の篩を調べる"], "rewards": ["親方の礼"]},
                    {"title": "純度の偽装", "description": "書き換えられた等級の記録を暴く",
                     "objectives": ["甘味鑑定人と記録を確かめる", "糖蔵の帳面を読む"], "rewards": ["鑑定人の証言"]},
                    {"title": "密造の小屋", "description": "裏の池と小屋を調べて密造を止める",
                     "objectives": ["廃糖蜜の池をたどる"], "rewards": []},
                ],
            },
        }),
    },
    {
        "id": "world-0015", "group": "world-mirrorless-labyrinth",
        "notes": "鏡のない迷宮。姿が映らないことで自分を失う旅人たち。戦闘NPCは姿を奪う影1人のみ。",
        "request": "次のキーワード・雰囲気から、独創的なTRPG世界と短編シナリオを作ってください。\nキーワード: 迷宮, 鏡, 記憶\n雰囲気: 無反射の不安\n想定難易度: 普通\n導入には開始地点1つ、NPCを2〜4人、クエストを1〜3件含めてください。",
        "output": normal({
            "name": "鏡なき迷宮シズク",
            "genre": "記憶ホラー・迷宮",
            "era": "鏡が人の姿を写し留めると信じられた時代",
            "setting": "平原の下に広がる巨大な迷宮。壁は滑らかに磨かれているが、どこにも鏡がなく、自分の姿が映らない。この国では鏡に映った姿がその人の証とされ、迷宮へ入った者は徐々に自分が分からなくなる。出口を知るには、自分を思い出すことが鍵だと言われる。",
            "atmosphere": "無反射の静けさ。振り返っても自分が居ないという感覚が、じわりと迫る。",
            "history": "昔、この地に鏡を奉る神殿があり、人々は鏡に自分の姿を映して名を証した。あるとき神殿の鏡がすべて割れ、代わりに姿を写さない迷宮が残った。以来、迷宮へ入った者が帰らなくなったという。",
            "geography": "入口は平原の裂け目。迷宮は磨かれた石の廊下が入り組み、所々に彫り物の壁と乾いた泉がある。奥には姿を写さない黒い広間が待つ。",
            "socialStructure": "迷宮の案内人が入口で旅人を迎え、彫り師が壁に手がかりを残す。記憶を失った旅人が迷宮のどこかで暮らし、村人は裂け目に近づかない。",
            "factions": ["迷宮の案内人", "壁の彫り師", "記憶を失った旅人", "自分を求める影"],
            "techMagic": "磨かれた石、彫り物、名を呼ぶ呼び鈴。魔法ではなく、姿が映らないことそのものが恐怖と混乱を生む。",
            "specialSettings": ["迷宮では自分の姿がどこにも映らない", "名を呼ばれると、呼んだ者の記憶が少しだけ戻る"],
            "objective": "迷宮の奥へ進み、失われた自分と出口を取り戻す。",
            "problem": "案内人の一人が迷宮へ入ったきり帰らず、代わりに『自分が誰か分からない』という旅人が増えている。",
            "secrets": ["黒い広間には、入った者の姿を奪う影が潜む", "彫り師の壁の彫り物は、かつての旅人が残した自分の名である"],
            "scenario": {
                "title": "映らない自分",
                "premise": "案内人が迷宮へ消えた。入口から進むと、壁に自分の姿が映らず、同行者の顔すら思い出せなくなる。彫り物に刻まれた名をたどり、黒い広間に潜む影と向き合うところまでを描く。",
                "length": "4〜5時間", "difficulty": "普通",
                "openingHook": "裂け目の縁で案内人が振り返った瞬間、磨かれた壁に彼の姿が映らなかった。",
            },
            "opening": {
                "location": {
                    "name": "迷宮の入口の広間",
                    "description": "平原の裂け目を降りた場所。磨かれた石の壁が光を吸い、どこを眺めても自分の姿がない。三方向の廊下が闇へ伸び、壁には小さな彫り物が点在する。",
                    "connections": ["彫り物の廊下", "乾いた泉の間", "案内人の消失地点", "黒い広間"],
                    "secrets": ["壁の彫り物は、失踪した旅人が刻んだ名である"],
                },
                "npcs": [
                    npc("迷宮の案内人ハク", "古い呼び鈴を手にした男。入口を離れない。", "落ち着いているが、奥の話は避ける", "迷宮の案内人", "協力的", "迷宮の入口の広間",
                        secrets=["奥の広間に姿を奪う影がいることを知っている"]),
                    npc("記憶を失った旅人エリ", "薄い布をまとった女性。自分の名を探している。", "優しいが、話すうちに記憶が揺れる", "迷宮の旅人", "友好的", "乾いた泉の間",
                        secrets=["彫り物に自分の名がある気がしている"]),
                    npc("壁の彫り師トウ", "鑿と槌を携えた老人。壁に名を刻み続ける。", "無愛想だが、手がかりは漏らす", "壁の彫り師", "中立", "彫り物の廊下",
                        secrets=["彫り物を増やしているのは、影に姿を奪われないため"]),
                    npc("影を名乗る者カラ", "輪郭だけが黒い人影。自分の姿を返すと近づく。", "淡々として、姿を渡そうとしない", "姿を奪う影", "敵対", "黒い広間",
                        secrets=["入った者の映った姿を蓄えている"],
                        combat={"attackPower": 6, "defense": 4, "attackSkillLevel": 2,
                                "defenseSkillLevel": 2, "hp": 13}),
                ],
                "quests": [
                    {"title": "消えた案内人", "description": "迷宮へ消えた案内人の足取りを追う",
                     "objectives": ["消失地点の痕を調べる", "呼び鈴を鳴らす"], "rewards": ["案内人の呼び鈴"]},
                    {"title": "名を取り戻す", "description": "彫り物から失われた名を読み解く",
                     "objectives": ["彫り物の廊下をたどる", "エリと名を照合する"], "rewards": ["エリの記憶"]},
                    {"title": "姿を奪う影", "description": "黒い広間の影と向かい、姿を取り返す",
                     "objectives": ["黒い広間へ踏み込む"], "rewards": []},
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
