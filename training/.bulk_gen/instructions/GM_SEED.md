# GM 応答データ（trpg_gm）— コンパクト・シード形式

まず `instructions/COMMON.md` と `training/specs/GENERATION_SPEC.md` を読んでください。
出力先は `inbox/seeds/trpg_gm/<割り当てファイル名>`（シード）。
`pipeline/bulk_expand.py` が context の既定値と history 連鎖を埋めて raw レコードに変換します。

## シード1行 = 1シナリオ（2〜3ターン）
```json
{"group":"scenario-<一意な英語slug>","notes":"狙う能力と狙い","context":{ ... },"turns":[ ... ]}
```
- `group` はシナリオごとに一意（例 `scenario-lantern-archive`）。
- 1シードにつき `turns` は 2〜3。ターンごとに状況を進展させる。

## context（必須キーのみ。他は expander が補完）
```json
"context": {
  "world": {
    "name": "世界名", "genre": "ジャンル", "setting": "設定の説明",
    "atmosphere": "空気感", "history": "世界の過去", "objective": "目的",
    "problem": "今起きている問題", "secrets": ["GMだけが知る秘密"]
  },
  "character": {
    "name": "主人公名", "occupation": "職業", "hp": 10, "maxHp": 10,
    "attributes": {"str":3,"dex":3,"int":3,"wil":3,"per":3,"cha":3},
    "skills": [
      {"id":"s1","name":"技能名","level":2,"description":"説明"}
    ],
    "inventory": [
      {"id":"i1","name":"道具名","quantity":1}
    ]
  },
  "npcs": [
    {"id":"n1","name":"NPC名","description":"外見","personality":"性格",
     "occupation":"役職","secrets":[]}
  ],
  "quests": [
    {"id":"q1","title":"クエスト名","objectives":[{"description":"目標"}]}
  ],
  "locations": [
    {"id":"l1","name":"場所名","description":"説明","secrets":[]}
  ],
  "currentLocationId": "l1"
}
```
- 能力値は各 1〜5。スキル 2〜4個（各 level 0〜3、id は s1,s2,...）。
  所持品 2〜5個（quantity 1〜99、id は i1,i2,...）。
- NPC 1〜3人（id n1,n2,...）、クエスト 1〜2件（id q1,q2）、場所 1〜3（id l1,l2）。

## turns の1ターン
```json
{
  "action": {"text": "プレイヤーの行動（自由入力。check は付けない）"},
  "narrative": "GMの情景描写（日本語 80〜250文字。成功/失敗の結果と次の手掛かりを含む）",
  "choices": [
    {"id":"c1","text":"選択肢の文"},
    {"id":"c2","text":"選択肢の文","check":{"attribute":"PER","difficulty":8,"skillId":"s2","rollMode":"NORMAL"}},
    {"id":"c3","text":"選択肢の文"}
  ],
  "sceneTitle": "場面名（任意・60字以内）",
  "eventSummary": "出来事の要約（任意・300字以内）",
  "stateChanges": [ ... ],
  "memories": [ ... ],
  "npcMemories": [ ... ]
}
```
- `choices` は 3〜4個。id は c1,c2,... で一意。`text` は 200字以内。
- **choice.check に書けるキーは `attribute` / `difficulty` / `skillId` / `rollMode` の4つだけ**。
  - attribute: STR/DEX/INT/WIL/PER/CHA
  - difficulty: 4/6/8/10/12/14
  - skillId: 所持スキルの id（s1 等）か `null`
  - rollMode: NORMAL/ADVANTAGE/DISADVANTAGE
  - dice / total / outcome / reason は**書かない**（監査でスキーマ違反になります）。
- `action` には `check` を付けない（自由入力として扱う）。判定付き選択肢は output.choices 側に置く。

## stateChanges（任意。使う場合のみ。必須のキー以外は書かない）
型ごとに使えるキーが決まっています。余分なキーはエラーです。
- `{"type":"HP_DELTA","amount":-2,"reason":"..."}`（0以外、±100。PCのみ）
- `{"type":"XP_DELTA","amount":10,"reason":"..."}`
- `{"type":"ITEM_ADD","item":{"name":"...","description":"...","quantity":1,"type":"tool","power":0,"defense":0,"tags":["..."]},"reason":"..."}`
  （item に `id` を付けない。7キーすべて必要）
- `{"type":"ITEM_REMOVE","targetId":"i1","amount":1,"reason":"..."}`（所持品のみ）
- `{"type":"NPC_RELATION_DELTA","targetId":"n1","amount":5,"reason":"..."}`
- `{"type":"QUEST_OBJECTIVE_PROGRESS","targetId":"q1","objectiveIndex":0,"amount":1,"reason":"..."}`
- `{"type":"QUEST_STATUS","targetId":"q1","questStatus":"COMPLETED"|"FAILED"}`
- `{"type":"LOCATION_CHANGE","targetId":"l2"}`
- `{"type":"FLAG_SET","targetId":"found_key","value":"true"}`
  （targetId は `[A-Za-z0-9_.:-]{1,64}`）
- `{"type":"COMBAT_START","targetId":"n1"}` / `{"type":"COMBAT_END"}`
- 1ターン最大32件。通常は 0〜2件でよい。

## memories / npcMemories（任意）
- memories: `{"category":"EVENT|DISCOVERY|UNRESOLVED|FORESHADOWING|WORLD|NPC_RELATION","content":"...","importance":3,"isSecret":false,"isConfirmed":true,"relatedIds":["n1"]}`
  （最大10件、content 500字以内、importance 1〜5、relatedIds は実在IDのみ）
- npcMemories: `{"npcId":"n1","content":"...","importance":2,"isSecret":false}`
  （最大10件。npcId は実在NPCのみ）

## 検証
シードを書いたら、可能なら次で確認してください（prefix/out は割り当てに合わせる）:
```bash
python3 training/.bulk_gen/pipeline/bulk_expand.py \
  --seeds training/.bulk_gen/inbox/seeds/trpg_gm/<ファイル名> \
  --prefix <接頭辞> --out <rawファイル名> --dry-run
```
エラーが出たらシードを修正してください（raw は dry-run では書き出されません）。
実際の raw 書き出しはメインエージェントが行います。
