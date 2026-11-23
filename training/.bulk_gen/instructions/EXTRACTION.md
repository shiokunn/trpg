# 自然文→JSON抽出データ（extraction）

まず `instructions/COMMON.md` を読んでください。
出力先は `inbox/extraction/<割り当てファイル名>`。1行1レコードを直接書きます。

## レコード
```json
{
  "id": "bex01-0001",
  "category": "extraction",
  "task": "<下の7 task のいずれか>",
  "group": "extract-<一意なslug>",
  "notes": "難所（否定・数詞・情報不足など）",
  "request": "自然言語の指示文（日本語）。明示された事実だけを root に置く。",
  "context": { ...参照IDの実体。state_changes / memories では必須... },
  "output": { ...task に応じた JSON... }
}
```

**重要（監査で必須）**: `stateChanges.targetId` や `memories[].relatedIds` /
`npcMemories[].npcId` を使う場合、`request` に書くだけでは不十分です。
トップレベル `context` に実体を置いてください。無い場合、監査ツールは
「存在しないID（`UNKNOWN_ID`／赤線・不合格）」と判定します。
```json
"context": {
  "character": {"id":"c1","name":"ルカ","hp":10,"maxHp":10,
                "inventory":[{"id":"i1","name":"乾パン","quantity":3}]},
  "npcs": [{"id":"n1","name":"ガルド"}],
  "quests": [{"id":"q1","title":"..."}],
  "locations": [{"id":"l1","name":"..."}]
}
```
`request` にも同じ ID と数量・状態を明記し、両者を矛盾させないこと。

## task と output の形（**余分なキーを書かない**）

### character_draft — output は CharacterDraft をルートに直接（wrapper なし）
```json
{"name":"...","species":"人間","occupation":"...","background":"...","personality":"...",
 "attributes":{"str":3,"dex":3,"int":3,"wil":3,"per":3,"cha":3},
 "skills":[{"name":"...","level":1,"description":"..."}],
 "items":[{"name":"...","description":"...","quantity":1,"type":"tool","power":0,"defense":0,"tags":[]}]}
```
属性は各1〜5、スキル3〜5個（各0〜2、同名不可）、所持品2〜5個（quantity 1〜99、power/defense 0〜20、tags 最大8）。

### state_changes — `{"stateChanges":[ ... ]}`
各要素は `{"type":..., "targetId":..., "amount":..., "reason":"..."}` など。
型ごとの必須キー・余分なキーの扱いは `instructions/GM_SEED.md` の stateChanges と同じ。
`context.character.inventory` 等を request 内で明示し、ID は c1/i1/n1/q1/l1 を使う。
- 例: request「ルカ(HP10)は乾パン(i1, 所持3)を一つ食べた。」
  output `{"stateChanges":[{"type":"ITEM_REMOVE","targetId":"i1","amount":1,"reason":"食べた"}]}`

### memories — `{"memories":[ ... ], "npcMemories":[ ... ]}`
- memories 要素: `{"category":"EVENT|DISCOVERY|UNRESOLVED|FORESHADOWING|WORLD|NPC_RELATION","content":"...","importance":3,"isSecret":false,"isConfirmed":true,"relatedIds":["n1"]}`（最大10件、content 500字以内、importance 1〜5）
- npcMemories 要素: `{"npcId":"n1","content":"...","importance":2,"isSecret":false}`（最大10件）

### items — `{"items":[ ... ]}`（各要素は ITEM_KEYS の7キー、最大5件）
### locations — `{"locations":[ ... ]}`（各要素 `name,description,connections,secrets`）
### npcs — `{"npcs":[ ... ]}`（各要素は NPC_KEYS、最大8人）
### quests — `{"quests":[ ... ]}`（各要素 `title,description,objectives, rewards`。objectives は1文字列以上の配列）

## 難所（多様性を出す。同じ難所を3回連続させない）
- 情報不足 → 空配列 `[]`（無い事実を作らない）
- 曖昧・推測 → `isConfirmed:false` や控えめな content
- 矛盾する記述 → 両方をそのまま記録するか、確定できない方を残さない
- 日本語の数詞（「三つ」「半日」「二つ」）を正しく数値化
- 否定文（「持っていない」「行かなかった」）を状態変更に変換しない
- 1文に複数変更が混在 → すべて抽出
- 雑談のみ → 空配列
- 固有名詞と代名詞の混在 → 指示された対象だけを抽出

## 監査で特に見られる点
- 否定を無視して「食べた」等と抽出すると `NEGATION_INVERSION` の指摘对象になります。
- request に無い数値・ID を書くと `FABRICATED_NUMBER` / `UNKNOWN_ID` になります。
- 空配列でよい場面で無理に要素を作らないでください。
