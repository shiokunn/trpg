# 生データ生成仕様（batch generator）

このファイルは `training/raw/` に追記するレコードの唯一の仕様です。
生成者は `training/README.md` と `training/tools/schema_lib.py` も必ず読んでください。

## 出力

- 1行1レコードの JSONL。追記のみ。既存ファイルは変更しない。
- 割り当てられたファイルパスに新規作成する（例: `training/raw/trpg_gm/batch_a.jsonl`）。
- アプリ本体・他カテゴリ・`processed/`・`splits/`・`metadata/` は触らない。

## レコード封筒

```json
{
  "id": "gm-a-0001",
  "category": "trpg_gm",
  "task": "gm_turn",
  "group": "scenario-<一意な名前>",
  "notes": "このサンプルが狙う能力（探索、失敗、情報不足など）",
  "context": { ... },
  "action": { "text": "...", "check": { ... } },
  "history": [ { "action": { ... }, "narrative": "..." } ],
  "output": { ... }
}
```

- `group` は1シナリオ＝1グループ。同じグループのターンだけが `history` を共有する。
- `id` は割り当てられた接頭辞＋4桁連番。
- `history` は2〜3ターンまで。各 `narrative` は直前レコードの `output.narrative` と完全一致させる。
- 1グループは2〜4ターン。全ターンを別レコードとして出力する。

## context（GM）

```json
{
  "world": {"name":"","genre":"","era":"","setting":"","atmosphere":"","history":"",
            "geography":"","socialStructure":"","factions":[],"techMagic":"",
            "specialSettings":[],"objective":"","problem":"","scenarioPremise":"","secrets":[]},
  "character": {"name":"","species":"","occupation":"","hp":10,"maxHp":10,"experience":0,
                "attributes":{"str":3,"dex":3,"int":3,"wil":3,"per":3,"cha":3},
                "skills":[{"id":"s1","name":"","level":1,"description":""}],
                "inventory":[{"id":"i1","name":"","quantity":1}]},
  "npcs": [{"id":"n1","name":"","description":"","personality":"","occupation":"",
            "relationship":0,"disposition":"","secrets":[],"isAlive":true,
            "isDiscovered":true,"combat":null}],
  "quests": [{"id":"q1","title":"","description":"","status":"ACTIVE",
              "objectives":[{"description":"","completed":false}],"rewards":[]}],
  "locations": [{"id":"l1","name":"","description":"","secrets":[],"isDiscovered":true}],
  "flags": {}, "currentLocationId": "l1", "memories": [], "npcMemories": [],
  "activeCombat": null, "turn": 0, "summary": ""
}
```

- 能力値は各1〜5。スキルは2〜4個、`level` 0〜3。所持品は2〜5個。
- `combat` は `{"attackPower":0-20,"defense":0-20,"attackSkillLevel":0-3,"defenseSkillLevel":0-3,"hp":1-30}` か `null`。
- 記憶は `{"category":"EVENT|NPC_RELATION|DISCOVERY|UNRESOLVED|FORESHADOWING|WORLD","content":"","importance":1-5,"isSecret":false,"isConfirmed":true,"relatedIds":[]}`
- `activeCombat` は `{"enemyNpcId":"n1","enemyName":"","enemyHp":10,"enemyMaxHp":14,"enemyAttackPower":4,"enemyDefense":2,"round":1}` か `null`。

## 判定（check）

`check` を付けるのは、アプリが判定する選択肢だけ。付ける場合は次の式と整合させる。

```
total = 使用ダイス + attributeValue + skillLevel
使用ダイス: NORMAL=1個, ADVANTAGE/DISADVANTAGE=2個（有利=max, 不利=min）
outcome: total >= difficulty+3 → CRITICAL_SUCCESS
         total >= difficulty   → SUCCESS
         total >= difficulty-2 → PARTIAL_SUCCESS
         それ未満              → FAILURE
diffculty は 4 / 6 / 8 / 10 / 12 / 14 のみ
```

`check` のフィールド: `attribute`（STR/DEX/INT/WIL/PER/CHA）, `skillId`（contextのskillsのidかnull）,
`skillName`, `skillLevel`, `attributeValue`, `dice`（1〜6の配列）, `rollMode`, `total`, `difficulty`, `outcome`, `reason`。

`action.text` は選択肢の文。`check` を持たない行動もある。

**重要（アプリの挙動）**: 自由入力は `PlayerTurnAction(text)` なので `check` が付かない。
したがって `history` があるターンで `action.check` を付けるなら、`action.text` は
直前ターンの `output.choices[].text` と**完全一致**させ、その選択肢の `check` と
`attribute` / `difficulty` / `rollMode` / `skillId` を一致させること。
完全一致しない自由入力には `check` を付けない。

既存データを修正したら `python3 training/tools/fix_raw_consistency.py --dry-run` で
グループ名の衝突と「check 付き自由入力」が残っていないか確認する。

## output（GmResponse）

```json
{
  "narrative": "情景描写（8,000文字以内、空不可）",
  "sceneTitle": "60文字以内",
  "choices": [{"id":"c1","text":"200文字以内"}, {"id":"c2","text":"..."}, {"id":"c3","text":"..."}],
  "stateChanges": [],
  "memories": [],
  "npcMemories": [],
  "eventSummary": "300文字以内"
}
```

- `choices` は3〜4個、`id` は一意。
- 判定が必要な選択肢だけ `check` を持つ（`attribute`, `difficulty`, 必要なら `skillId`）。
- `stateChanges` は最大32件。型ごとに使うフィールドは次の通り。

| type | 必須 | 備考 |
|---|---|---|
| HP_DELTA | amount | 0以外、±100以内。PCのみ |
| XP_DELTA | amount | 0以外、±1000以内 |
| ITEM_ADD | item | `{"name","description","quantity","type","power","defense","tags"}`。`id` は付けない |
| ITEM_REMOVE | targetId | 所持品ID。`amount` 省略可（既定1） |
| NPC_RELATION_DELTA | targetId, amount | 既存NPCのみ、±100以内 |
| QUEST_OBJECTIVE_PROGRESS | targetId, objectiveIndex | ACTIVEのみ、amount>0 |
| QUEST_STATUS | targetId, questStatus | COMPLETED か FAILED のみ |
| LOCATION_CHANGE | targetId | 既存の場所のみ、戦闘中・現在地は不可 |
| FLAG_SET | targetId, value | キーは `[A-Za-z0-9_.:-]{1,64}` |
| COMBAT_START | targetId | 生存NPC・combatあり・非戦闘中 |
| COMBAT_END | なし | 戦闘中のみ |

- 使わないフィールドは**書かない**（既定値で補われる）。`targetId` が不要な型に `""` を書かない。
- `memories` は最大10件、`content` 500文字以内、`importance` 1〜5。`relatedIds` は context に実在するIDのみ。
- `npcMemories` は最大10件、`npcId` は実在NPCのみ。
- 「推測」は `isConfirmed: false`、「GM専用の秘密」は `isSecret: true`。

## 禁止事項

- 出目・合計・判定結果を創作しない。`user` に渡された確定結果だけを描写する。
- 敵HPを動かさない。`HP_DELTA` はPCにだけ使う。
- context にないID・NPC・場所・アイテムを使わない。新規アイテムは `ITEM_ADD` の `item` にだけ書く。
- `WorldDraft` に `scenarioPremise` を入れない（`scenario.premise` を使う）。
- 既存の特定作品の固有名詞を使わない。
- 同じシナリオ・同じNPC名・同じ展開の使い回しをしない。各グループは独立した世界観にする。

## 検証

```bash
python3 training/tools/build_dataset.py --check
```

不合格があれば、自分のファイルの該当 `id` を修正する（他者のファイルは触らない）。
`rejected` が0になるまで直す。最後に `jq -c . <file>` で1行1JSONを確認する。
