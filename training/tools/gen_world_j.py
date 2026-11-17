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

DEST = ROOT / "raw" / "world_json" / "world_03.jsonl"


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
        "id": "world-0007", "group": "world-border-checkpoint",
        "notes": "国境の検問所。開かない箱と二通りの帳簿。戦闘NPCは密輸組の用心棒1人のみ。",
        "request": "次のキーワード・雰囲気から、独創的なTRPG世界と短編シナリオを作ってください。\nキーワード: 国境, 検問, 密輸\n雰囲気: 張り詰めた日常\n想定難易度: 難しい\n導入には開始地点1つ、NPCを2〜4人、クエストを1〜3件含めてください。",
        "output": normal({
            "name": "灰霧の国境関所アワキ",
            "genre": "政治サスペンス",
            "era": "両国が和平を結んで七年目",
            "setting": "山脈を分ける川に架かる一本の石橋。そのたもとに関所が置かれ、通行する荷物を一つずつ開けて調べる。霧が深く、対岸の境界標が見える日は少ない。",
            "atmosphere": "濡れた石と灯油ランプの匂い。紙をめくる音だけが大きく響く。",
            "history": "七年の戦争の末に二国が川を境界と定め、この関所が建てられた。密輸はたちまち日常になり、役人は見て見ぬふりを覚えた。",
            "geography": "橋の中央に国境線が引かれている。手前に関所と宿場、対岸に守備隊の小屋。上流には干潮で渡れる浅瀬がある。",
            "socialStructure": "税関吏が通行と徴税を扱い、宿場が荷物の仲介を担う。両国の密探が人足に紛れて出入りする。",
            "factions": ["関所の税関吏", "宿場の密輸組", "北岸の守備隊", "両国の密探"],
            "techMagic": "秤と印鑑、鎖と鍵、密書を読むための道具。火薬は両国で厳しく管理されている。",
            "specialSettings": ["橋の中央を越えると国が変わり、法も変わる", "関所の帳簿は表と裏で二通りに書かれる"],
            "objective": "開かない箱の正体を割り出し、国境を越える密書を止める。",
            "problem": "税関吏の帳簿と実際の荷物が一つだけ一致しない。鍵の合わない箱が持ち込まれている。",
            "secrets": ["税関吏長が密輸組と通じている", "箱の中は火薬ではなく、和平の証文の写しである"],
            "scenario": {
                "title": "開かない箱",
                "premise": "朝の点検で、荷物の数と帳簿が一つ違う。税関吏長は箱を開けさせない。帳簿の裏の数字をたどるうち、その箱が両国の和平を揺るがす証文を運んでいることが判明する。誰が、なぜ国境を越えさせようとしたのか。",
                "length": "4〜5時間", "difficulty": "難しい",
                "openingHook": "点検の最中、税関吏長が『その箱は見なかったことにしろ』と低声で言う。",
            },
            "opening": {
                "location": {
                    "name": "灰霧の検問所",
                    "description": "川風が石垣を濡らす検問所。天井のランプが揺れ、帳簿と秤が並ぶ。橋へ続く道だけが霧の中へ消えている。",
                    "connections": ["橋の中央の国境標", "宿場の荷物置き", "上流の浅瀬"],
                    "secrets": ["帳簿の裏に、別の手書きの数字が残っている"],
                },
                "npcs": [
                    npc("税関吏長ボルダ", "赤ら顔の初老の男。印鑑を握りしめている。", "偉ぶるが目は泳ぐ", "税関吏長", "警戒", "灰霧の検問所"),
                    npc("若い税関吏リィ", "新人の男性。帳簿の文字が丁寧に整っている。", "真面目で規則を疑わない", "税関吏", "協力的", "灰霧の検問所"),
                    npc("宿場の女将オルナ", "荷物の匂いを嗅ぎ分ける女。", "愛想がよく、情報も売る", "宿場の女将", "中立", "宿場の荷物置き"),
                    npc("密輸組の用心棒ガン", "川沿いに潜む男。短剣を懐に隠す。", "無口で、追われると出る", "密輸組の用心棒", "敵対", "上流の浅瀬",
                        secrets=["箱を運ぶ荷車の道を知っている"],
                        combat={"attackPower": 6, "defense": 3, "attackSkillLevel": 2,
                                "defenseSkillLevel": 1, "hp": 12}),
                ],
                "quests": [
                    {"title": "開かない箱を調べる", "description": "帳簿と荷物を照合し、箱の持ち主を割り出す",
                     "objectives": ["帳簿の裏の数字を読む", "箱の持ち主を尋ねる"], "rewards": ["関所の通行証"]},
                    {"title": "密書を止める", "description": "国境を越える密書を渡させない",
                     "objectives": ["密輸組の経路を押さえる", "国境標で受け取り人を待つ"], "rewards": ["両国からの謝礼"]},
                ],
            },
        }),
    },
    {
        "id": "world-0008", "group": "world-salvage-port",
        "notes": "沈船の引き揚げ港。帳面に自分の名。戦闘NPCは酔った漁民1人のみ。",
        "request": "次のキーワード・雰囲気から、独創的なTRPG世界と短編シナリオを作ってください。\nキーワード: 沈没船, 引き揚げ, 海\n雰囲気: 塩と労働\n想定難易度: 普通\n導入には開始地点1つ、NPCを2〜4人、クエストを1〜3件含めてください。",
        "output": normal({
            "name": "沈み船の港クラガ",
            "genre": "海洋ミステリ",
            "era": "大型帆船が廃れた時代",
            "setting": "港の沖合に、三十年前に沈んだ貨物船が横たわる。干潮のときだけ船体の上部が現れる。引き揚げ組合が潜水夫を送るが、船内には今も帳面が残っているという。",
            "atmosphere": "潮の満ち引きとロープのきしみ。唇に残る塩の味。",
            "history": "沈没当時、船には高価な積荷が載っていた。引き揚げは何度も試みられ、三人の潜水夫が戻らなかった。",
            "geography": "波止場に倉と起重機。沖に沈船。岸壁の下に洞窟があり、干潮でないと入れない。",
            "socialStructure": "引き揚げ組合が潜水夫を雇い、港の役所が免許を管理する。漁民は沈船を忌み嫌って近づかない。",
            "factions": ["引き揚げ組合", "港の役所", "地元の漁民", "保険の調査員"],
            "techMagic": "潜水鐘と空気ポンプ、蒸気起重機。超自然の要素はない。",
            "specialSettings": ["沈船は干潮のときだけ上部が露出する", "海水を吸った船内の木材は非常に重い"],
            "objective": "帳面の続きを回収し、行方不明の潜水夫の真相を確かめる。",
            "problem": "持ち帰った帳面に、まだ生まれていないはずの自分の名前が書かれていた。",
            "secrets": ["三十年前の沈没は保険金目当ての自沈だった", "行方不明の潜水夫は船内の部屋に閉じ込められた"],
            "scenario": {
                "title": "帳面の自分の名",
                "premise": "引き揚げた帳面に自分の名があった。組合は帳面を隠せという。次の干潮の間に船内へ戻り、帳面の続きと消えた潜水夫の足跡を探す。",
                "length": "3〜4時間", "difficulty": "普通",
                "openingHook": "干潮で現れた船体の裂け目から冷たい空気が流れ、帳面の束が波打ち際に置いてある。",
            },
            "opening": {
                "location": {
                    "name": "波止場の引き揚げ倉",
                    "description": "ロープと滑車が天井に吊るされた倉。床は常に濡れ、壁に沈船の古い図面が貼ってある。",
                    "connections": ["沖合の沈船", "岸壁の下の洞窟", "港の役所"],
                    "secrets": ["図面には記載のない船室がある"],
                },
                "npcs": [
                    npc("組合長ダル", "髭を生やした男。起重機の操作が好き。", "事業熱心だが事故の話を嫌う", "引き揚げ組合長", "中立", "波止場の引き揚げ倉"),
                    npc("潜水夫の老手ミラ", "耳の聞こえが悪い古参。", "口数は少ないが海を知る", "潜水夫", "協力的", "沖合の沈船"),
                    npc("保険の調査員セト", "黒いスーツの男。帳面を欲しがる。", "丁寧だが譲らない", "保険調査員", "警戒", "港の役所"),
                    npc("酔った漁民ゴロ", "港で酒をあおる男。沈船の話をしたがる。", "大声で誰も信じない", "漁民", "友好的", "波止場の引き揚げ倉",
                        secrets=["洞窟の入口を知っている"],
                        combat={"attackPower": 4, "defense": 2, "attackSkillLevel": 1,
                                "defenseSkillLevel": 1, "hp": 8}),
                ],
                "quests": [
                    {"title": "帳面の続きを探す", "description": "沈船の奥から帳面の残りを持ち帰る",
                     "objectives": ["次の干潮の時間を確認する", "図面にない船室を捜す"], "rewards": ["組合からの報酬"]},
                    {"title": "消えた潜水夫", "description": "三十年前の事故の真相を確かめる",
                     "objectives": ["古い図面と船内を照合する"], "rewards": []},
                ],
            },
        }),
    },
    {
        "id": "world-0009", "group": "world-volcano-forge",
        "notes": "火山の麓の鍛冶街。弱まった溶鉱炉。戦闘NPCは采掘組の男1人のみ。",
        "request": "次のキーワード・雰囲気から、独創的なTRPG世界と短編シナリオを作ってください。\nキーワード: 火山, 鍛冶, 溶鉱炉\n雰囲気: 熱と煤\n想定難易度: 易しい\n導入には開始地点1つ、NPCを2〜4人、クエストを1〜3件含めてください。",
        "output": normal({
            "name": "火口の鍛冶街ヤケ",
            "genre": "職人ファンタジー",
            "era": "火山が活発になった時代",
            "setting": "火山の麓に広がる鍛冶街。溶鉱炉が昼夜なく火を吹き、通りは煤で黒い。職人は火口の煙の色を見て、その日の仕事を決める。",
            "atmosphere": "硫黄の匂いと鉄を打つ音。熱で揺らぐ空気。",
            "history": "火山が目覚めて二十年、古い溶鉱炉が再び動き出した。以来、街は火を絶やさぬための祈りを続けている。",
            "geography": "山腹に火口、中腹に溶鉱炉、麓に鍛冶の通り。脇には冷まし場の大きな水槽がある。",
            "socialStructure": "親方が炉を管理し、職人がこれに従う。火読みが火山の様子を伝え、采掘組が鉱石を運ぶ。",
            "factions": ["鍛冶の親方衆", "火読み", "山の采掘組"],
            "techMagic": "溶鉱炉、風箱、水冷の槽。火の力で金属を鍛えるが、魔法ではない。",
            "specialSettings": ["火口の煙の色で翌日の炉の調子がわかる", "溶鉱炉の火は絶やしてはならない"],
            "objective": "炉の火が弱まる理由を探し、街の鍛冶を守る。",
            "problem": "一番大きな溶鉱炉の火が忽然と弱まった。親方は誰も炉に近づけない。",
            "secrets": ["采掘組が火口の通気口を塞いだことが原因である", "親方は火読みの助言を無視している"],
            "scenario": {
                "title": "弱まった炉の火",
                "premise": "街の中心の溶鉱炉が冷えかけている。親方は原因を隠す。火読みの助言を聞き、山の通気口を確かめに向かう。",
                "length": "2〜3時間", "difficulty": "易しい",
                "openingHook": "朝、炉の火がいつもより小さい。親方が『今日は炉を見るな』と幕をかける。",
            },
            "opening": {
                "location": {
                    "name": "鍛冶の通り",
                    "description": "両側に小さな炉が並び、鉄を打つ音が絶えない。煤で黒い壁に火除けの札が貼ってある。",
                    "connections": ["親方の溶鉱炉", "火読みの小屋", "山への登り口"],
                    "secrets": ["札の裏に先月の火読みの記録が隠されている"],
                },
                "npcs": [
                    npc("親方ゴウ", "大柄で、腕に古い火傷の跡。", "短気だが職人を大事にする", "親方", "警戒", "親方の溶鉱炉"),
                    npc("火読みの少女ホノ", "煙の色を見つめる目が鋭い。", "素直だがはっきり言う", "火読み", "協力的", "火読みの小屋"),
                    npc("職人タツ", "若い職人。槌を持つ手がおどる。", "陽気で噂好き", "職人", "友好的", "鍛冶の通り"),
                    npc("采掘組の男クロ", "山から降りてきた男。火薬の袋を背負う。", "無愛想で質問を避ける", "采掘組", "敵対", "山への登り口",
                        secrets=["通気口に石を詰めた"],
                        combat={"attackPower": 5, "defense": 2, "attackSkillLevel": 1,
                                "defenseSkillLevel": 1, "hp": 10}),
                ],
                "quests": [
                    {"title": "火を取り戻す", "description": "炉が弱まった原因を探す",
                     "objectives": ["火読みの記録を読む", "山の通気口を調べる"], "rewards": ["親方から鍛えた短剣"]},
                    {"title": "火除けの札", "description": "札の裏に隠された記録を確かめる",
                     "objectives": ["先月の記録を探す"], "rewards": []},
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
