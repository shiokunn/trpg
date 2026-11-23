# World JSON データ（world_json）— 生レコード形式

まず `instructions/COMMON.md` を読んでください。
出力先は `inbox/world_json/<割り当てファイル名>`。シード展開は不要で、1行1レコードを直接書きます。

## レコード
```json
{
  "id": "bwd01-0001",
  "category": "world_json",
  "task": "world_json",
  "group": "world-<一意なslug>",
  "notes": "狙い（世界観の特徴、難所）",
  "request": "次のキーワード・雰囲気から、独創的なTRPG世界と短編シナリオを作ってください。\nキーワード: ...\n雰囲気: ...\n想定難易度: ...\n導入には開始地点1つ、NPCを2〜4人、クエストを1〜3件含めてください。",
  "output": { ...WorldDraft... }
}
```

## output（WorldDraft）は **16キーすべて必須**。キー名・階層を正確に。
```json
{
  "name": "世界名", "genre": "ジャンル", "era": "時代",
  "setting": "世界の仕組み（長めの説明）", "atmosphere": "空気感",
  "history": "過去の出来事", "geography": "地理", "socialStructure": "社会構造",
  "factions": ["勢力1", "勢力2"], "techMagic": "技術や魔法の仕組み",
  "specialSettings": ["特別な設定1", "特別な設定2"],
  "objective": "シナリオの目的", "problem": "起きている問題", "secrets": ["GM専用の秘密"],
  "scenario": {
    "title": "シナリオ名", "premise": "前提・あらすじ（必須）",
    "length": "短い", "difficulty": "普通", "openingHook": "導入の一言"
  },
  "opening": {
    "location": {
      "name": "開始地点", "description": "説明",
      "connections": ["隣の場所"], "secrets": ["場所の秘密"]
    },
    "npcs": [
      {"name":"NPC名","description":"外見","personality":"性格","occupation":"役職",
       "disposition":"協力的","locationName":"開始地点","secrets":[],"combat":null}
    ],
    "quests": [
      {"title":"クエスト名","description":"説明",
       "objectives":["目標1","目標2"],"rewards":["報酬"]}
    ]
  }
}
```
- `scenario.premise` は必須（`scenarioPremise` というキーは**使わない**）。
- opening.location は開始地点1つだけ。`connections` は場所名の配列（解決不要）。
- opening.npcs は 2〜4人。`combat` は null か
  `{"attackPower":4,"defense":2,"attackSkillLevel":1,"defenseSkillLevel":1,"hp":10}`（hp は 1〜30）。
- opening.quests は 1〜3件。`objectives` は文字列の配列で 1件以上必須。
- 配列・文字列の長さは控えめに（設定文は 200字前後、リスト項目は短文）。
- 世界ごとにジャンル・仕組み・摩擦・秘密を変える。既存の世界（氷河の見張り塔、
  乾いた巡礼都市、停電した水族館、廃線の夜行バス、江戸の火の見櫓、塩の市場、
  真鍮の水道管の街、凧祭りの谷、極夜の無線中継所、藍染めの職人街、骨天気の遊牧民、
  深海観測基地、寄生植物の森、空中庭園、車両の墓場、歌声で法が変わる国、
  記憶を売る市場、国境の検問所、沈没船の引き揚げ、砂時計の塔、夜だけ現れる駅、
  火山の鍛冶街、氷の寄宿学校、声を失った歌劇場、増えすぎた図書館、雨の裁判所、
  風車の村、塩湖の浮島、影が貸し出される劇場街、養蜂都市、文字が消える印刷所、
  沈む遊園地、季節を売る隊商、夢を検疫する港、骨董の自動人形の屋敷、天井のない坑道）
  と重複しないテーマを選んでください。
