# LFM3 TRPG 追加学習データセット

このフォルダは `shiokunn/trpg`（ブランチ `feature/world-json-slot-template`）の
実装に合わせた LoRA/QLoRA 追加学習データの置き場です。アプリ本体のコードは変更しません。

## 現在の状態（重要）

**検証済みの部分データです。学習にそのまま使える規模ではありません。**
最新の件数は `metadata/dataset_info.json` の `counts` を参照してください。

| カテゴリ | 現在 | 目標 |
|---|---:|---:|
| TRPG GM応答 (`trpg_gm`) | 191 | 5,000 |
| World JSON (`world_json`) | 15 | 2,000 |
| 自然文→JSON抽出 (`extraction`) | 68 | 2,000 |
| 合計 | 274 | 9,000 |

- train 246 / validation 14 / test 14（グループ単位で分割。各カテゴリが全splitに含まれる）。
- 最終成果物は `training/sft_synthetic_json.jsonl`（206件）と
  `training/sft_synthetic_extraction.jsonl`（68件）。いずれも合格rawのみを集約。
  `feature/world-json-slot-template` の実装契約には
  すべて適合していますが、件数・チャットテンプレート・人手レビューが未完了です。
- 詳細と未解決事項は `metadata/dataset_info.json` の
  `status` / `not_ready_for_training` / `notes` を参照してください。

## 構成

```
training/
├── README.md
├── raw/                          # 生成原文（上書き・改変しない）
│   ├── trpg_gm/*.jsonl           # 1シナリオ=1ファイル、複数ターンを含む
│   ├── world_json/*.jsonl
│   └── extraction/*.jsonl
├── processed/                    # rawを検証し、チャット形式へ変換したもの
│   ├── trpg_gm.jsonl
│   ├── world_json.jsonl
│   └── extraction.jsonl
├── splits/
│   ├── train.jsonl
│   ├── validation.jsonl
│   └── test.jsonl
├── metadata/
│   └── dataset_info.json
├── tools/                        # 生成・検証の再現用（データ本体ではない）
    ├── schema_lib.py             # Kotlinパーサの契約を写した検証器
    ├── prompts.py                # アプリのsystemプロンプト（転記）
    ├── render.py                 # PromptBuilderと同じuser/system組み立て
    ├── build_dataset.py          # raw → processed → splits → metadata
    ├── verify_dataset.py         # processed/splitsの独立再検証
    ├── seed_expand.py            # seeds/ → raw/trpg_gm/（検証付き）
    ├── fix_raw_consistency.py    # グループ名衝突とcheck付き自由入力の検出
    ├── gen_extraction_h.py       # 抽出バッチ生成器（idempotent）
    ├── gen_extraction_i.py       # 同上（別バッチ）
    └── gen_world_h.py            # WorldDraftバッチ生成器（idempotent）
├── seeds/                        # 手書きシード（1シナリオ=3ターン分）
│   └── gm_*.jsonl
└── specs/                        # 生成仕様・シナリオ台帳（重複防止）
    ├── GENERATION_SPEC.md
    └── BATCH_THEMES.md
```

### 生成の再現手順

```bash
# 1. specs/BATCH_THEMES.md で未使用の世界観を確認し、seeds/ にシナリオを手書き
# 2. 検証だけ実行
python3 training/tools/seed_expand.py --dry-run training/seeds/gm_x_01.jsonl \
  --prefix gm-x- --dest gm_x_01.jsonl
# 3. raw に追記
python3 training/tools/seed_expand.py training/seeds/gm_x_01.jsonl \
  --prefix gm-x- --dest gm_x_01.jsonl
# 4. カテゴリ全体を検証
python3 training/tools/build_dataset.py --check
# 5. 問題が無ければ派生ファイルを再生成
python3 training/tools/build_dataset.py
python3 training/tools/verify_dataset.py
```

シード方式を採った理由: 1シナリオの `context` を1回だけ書き、`history` の連鎖と
レコード封筒を機械的に埋めることで、手書き量を減らしつつ文体を保つためです。
`seed_expand.py` は各レコードを `schema_lib` で検証し、不合格ならファイルを書きません。

`raw/` のレコードは 1 行 1 サンプルで、次の形をしています。

```json
{
  "id": "gm-0001",
  "category": "trpg_gm",
  "task": "gm_turn",
  "group": "scenario-forest",
  "notes": "何を狙ったサンプルか",
  "context": { "world": {}, "character": {}, "npcs": [], "quests": [], "locations": [] },
  "action": { "text": "森の中へ入る", "check": {} },
  "history": [{ "action": {}, "narrative": "前ターンの描写" }],
  "output": { "narrative": "..." }
}
```

`context` / `action` / `history` を持つのは、ID参照や判定結果の整合性を機械検証するためです。
`processed/` ではアプリと同じ位置に配置されます（状態と履歴は `system`、今回の行動は `user`）。

## 実装から確定した契約

確認したファイル:

- `core/ai/GmResponse.kt`, `core/ai/GmResponseParser.kt`
- `core/ai/DraftModels.kt`, `core/ai/DraftParsers.kt`, `core/ai/WorldDraftGrammar.kt`
- `core/state/StateChange.kt`, `core/state/StateChangeValidator.kt`, `core/state/StateChangeApplier.kt`
- `core/domain/Attributes.kt`, `Npc.kt`, `Quest.kt`, `Skill.kt`, `Memory.kt`, `Dice.kt`
- `core/prompt/PromptBuilder.kt`
- `app/creation/AdventureCreator.kt`, `app/game/GmTurnService.kt`, `app/game/CombatService.kt`

### GmResponse

| フィールド | 必須 | 制約 |
|---|---|---|
| `narrative` | ○ | 空でない、8,000文字以内 |
| `sceneTitle` | – | 60文字以内 |
| `choices` | ○ | 3〜4個。`id` は一意・必須、`text` は必須200文字以内 |
| `choices[].check` | – | 判定があるときだけ。`attribute` は `STR/DEX/INT/WIL/PER/CHA`、`difficulty` は `4/6/8/10/12/14`、`rollMode` は `NORMAL/ADVANTAGE/DISADVANTAGE`、`skillId` は所持スキルIDか `null` |
| `stateChanges` | – | 最大32件（下表） |
| `memories` | – | 最大10件、`content` 500文字以内、`importance` 1〜5、`relatedIds` 最大20 |
| `npcMemories` | – | 最大10件、`npcId` と `content` は必須 |
| `eventSummary` | – | 300文字以内 |

`stateChanges` は「アプリへの宣言」であり、直接の状態変更ではありません。型ごとに使うフィールドが決まっています。

| type | 必須 | 意味 |
|---|---|---|
| `HP_DELTA` | `amount` | PCのHP増減（0以外、±100以内） |
| `XP_DELTA` | `amount` | 経験値増減（0以外、±1000以内） |
| `ITEM_ADD` | `item` | 新規アイテム。`item.id` は付けない（アプリが採番）。`quantity` 1〜99 |
| `ITEM_REMOVE` | `targetId` | 所持品ID。`amount` は省略時1 |
| `NPC_RELATION_DELTA` | `targetId`, `amount` | 既存NPCのみ（±100以内） |
| `QUEST_OBJECTIVE_PROGRESS` | `targetId`, `objectiveIndex` | ACTIVEなクエストのみ。`amount > 0` で達成 |
| `QUEST_STATUS` | `targetId`, `questStatus` | `COMPLETED` か `FAILED` のみ。終了済みは変更不可 |
| `LOCATION_CHANGE` | `targetId` | 既存の場所のみ。戦闘中・現在地は不可 |
| `FLAG_SET` | `targetId` | キーは `[A-Za-z0-9_.:-]{1,64}`。`value` 200文字以内 |
| `COMBAT_START` | `targetId` | 生存NPCかつ `combat` あり。既に戦闘中は不可 |
| `COMBAT_END` | – | 戦闘中のみ |

このデータセットで守っている禁止事項:

- **サイコロを振らせない。** 出目・合計・結果は `user` の「【アプリが確定した判定結果】」だけを根拠にします。
- **敵HPを動かさない。** 戦闘ラウンドは `CombatService`（`CombatRoundEngine`）が解決し、
  AIは呼ばれません。`HP_DELTA` はPCにだけ作用します。
- **存在しないIDを書かない。** `targetId` / `relatedIds` は `context` に実在するものだけです。
- **知らない事実を作らない。** 情報不足・矛盾入力では、状態を足さず状況を言い直します。

### WorldDraft / CharacterDraft

`WorldDraft` は 16 キーすべてが必須で、`WorldDraftGrammar` と同じ順序・階層です。
`scenarioPremise` は `WorldDraft` のキーではありません（`scenario.premise` を使う）。
`opening` は開始地点1つ・NPC最大8・クエスト最大8、`quests[].objectives` は1件以上必要です。

`CharacterDraft` は `attributes` 各1〜5、スキル3〜5個（各0〜2、同名不可）、
所持品2〜5個（`quantity` 1〜99、`power`/`defense` 0〜20、`tags` 最大8）です。

## データ形式

- 1行1サンプル、`messages` 配列（`system` / `user` / `assistant`）。
- `assistant.content` は JSON オブジェクトを compact にした文字列（コードフェンスなし）。
- GM応答は `system` に「GMルール＋JSON契約＋現在の状態・クエスト・NPC・記憶・場所・世界設定」、
  `user` に「プレイヤーの選択（＋確定した判定結果）」を置きます。`PromptBuilder` と同じ配置です。
- 会話履歴は `user`（`【判定: 感覚 / 出目 2 = 6 / 難易度 8 → 部分的成功】\n行動`）と
  `assistant`（narrativeのみ）の組で挿入します。`GmTurnService` が保存する形に合わせています。
- World/Character 生成はアプリと同じ `system`＋`user` です。

**学習側のチャットテンプレートは未確定です。** 学習 Notebook・スクリプトはリポジトリに
存在しません。なお推論用の `third_party/llama.cpp/models/templates/LFM3.jinja` は実在し、
`system` は `<|im_start|>system` として保持されます。ただし同テンプレートは
assistant ターン直前に `<think></think>` を挿入します（アプリは `enable_thinking=false`）。
学習トークナイザがこれと等価とは限らないため、実物の
`tokenizer.apply_chat_template()` で BOS/EOS・`<think></think>`・生成開始位置を確認し、
loss マスキングの方針（最終JSONのみ／履歴narrativeも）を決めてください。
`messages` 形式自体はテンプレート非依存です。

## 検証

```bash
python3 training/tools/build_dataset.py --check   # rawの構文・契約のみ
python3 training/tools/build_dataset.py           # processed / splits / metadata を再生成
python3 training/tools/verify_dataset.py          # 生成物の独立再検証
```

検証できること:

- JSON構文、キー集合（未知キー拒否）、型、列挙値、文字数・件数上限
- `stateChanges` の型別フィールドと、`context` に基づくID・範囲・状態の整合性
- 選択肢のID重複、所持スキル以外の `skillId`
- 完全重複（task＋入力＋出力のハッシュ）と、近似重複（6-gram shingle Jaccard ≥ 0.85）
- split間のID重複、シナリオグループの漏洩、assistant内容とrawの一致

検証できないこと（人手レビューが必要）:

- 描写の面白さ、世界設定とのニュアンスの一致、日本語の自然さ
- 判定結果と描写の意味的な整合（「成功なのに失敗したように書く」等）
- NPCの秘密をプレイヤーへ漏らしていないかの最終判断

## 目標件数までの生成計画

### 学習ステップ

指示された設定（`per_device_train_batch_size=1`, `gradient_accumulation_steps=16`,
`num_train_epochs=1`）では、`steps = ceil(train_n / 16)` です。

| train件数 | ステップ |
|---:|---:|
| 8,000 | 500 |
| 8,100 | 507 |
| 9,000 | 563 |

90:5:5 を保つなら、train 8,100 に対して validation 450 / test 450、合計 9,000 が最小構成です。
実際の学習スクリプトの実効バッチサイズが違う場合は `train_n = 500 × 実効バッチサイズ` で再計算してください。
（リポジトリに学習スクリプトが無いため、この式は指示された想定設定に基づきます。）

パイロットの 26 件では、カテゴリごとに検証データを確保すると比率は 18:4:4（69:15:15）になります。
1グループ最小2〜3件のため、5% を満たすには 1 グループあたりの件数かグループ数が足りません。
件数が増えれば `assign_splits` はカテゴリごとに 90:5:5 へ収束します。

### カテゴリ別の割り当て（案）

`trpg_gm` 5,000:

| 種別 | 件数 |
|---|---:|
| 探索・知覚 | 600 |
| 会話・交渉 | 600 |
| クエスト進行・完了 | 600 |
| アイテム発見・使用 | 500 |
| 謎解き | 450 |
| 失敗・代償 | 450 |
| 戦闘開始・戦闘後 | 400 |
| 予想外の自由発想 | 350 |
| 記憶参照・継続 | 350 |
| 情報不足・矛盾入力 | 350 |
| 移動・フラグ | 200 |
| 選択肢の提示のみ | 150 |

`world_json` 2,000: ファンタジー600 / SF400 / 現代・都市400 / 歴史300 / ホラー・ミステリ300。

`extraction` 2,000: CharacterDraft 350 / StateChange 500 / 記憶 350 / アイテム 250 /
場所 200 / NPC 200 / クエスト 150。

### 生成手順（1バッチ = 40〜60件）

1. シナリオグループを先に決める（1グループ = 1世界観、GMは2〜4ターン）。
2. `tools/prompts.py` の system プロンプトと `context` を渡して raw を生成する。
3. 生成した raw を `training/raw/<category>/` に追記する。
4. `build_dataset.py --check` で不合格を確認し、不合格は raw から削除せず修正版を別IDで追加する。
5. カテゴリ単位で `build_dataset.py` → `verify_dataset.py`。
6. 近似重複が閾値を超えたら、そのグループは採用しない。

件数を満たすための言い換え量産は禁止です。1グループあたりのターン数を増やすにしても、
状況・NPC・判定結果・目的を変えてください。

## 既知の実装上の不整合（データ側で回避）

1. `WorldDraftParser` は `combat.hp` を 1〜999 まで許容しますが、
   `AdventureCreator.WORLD_SYSTEM` は 1〜30 を指示しています。本データは 1〜30 に統一しました。
2. `CombatStats.MAX_HP` は 999 ですが、アプリのプロンプト例は 10 前後です。
3. `GmResponse` の `narrative` 以外は既定値を持つため、パーサはキー省略を許します。
   本データはプロンプトの契約に合わせ、`choices` / `narrative` 以外は必要なキーだけを書いています。
4. `WorldDraftParser.normalizeOpeningLocationSecrets` は `opening.location.secrets` が
   文字列のときだけ配列へ直します。本データは最初から配列です。
5. `extraction` の `system` プロンプトはアプリに実装がありません（補助タスク）。
   推論時に同じプロンプトを使わない限り、このカテゴリだけを学習すると指示追従がずれます。

## 出所

- 生成: DeepSeek V4.1 Flash Free（`opencode/deepseek-v4.1-flash-free`）、OpenCode セッション上で作成。
- 一括生成用の DeepSeek API キーは環境に無いため、現時点ではパイロットのみ。
- 生成日時・件数・検証結果は `metadata/dataset_info.json` に記録しています。

---

## 既存のワークスペース規約（マージ時に保持した内容）

This directory stores synthetic SFT data generated with DeepSeek for the TRPG app.

## Workflow

1. Read the current implementation and prompt/schema definitions on the target branch before generating data.
2. Generate examples with the prompts in `prompts/`.
3. Save untouched DeepSeek responses in `raw/`, including generation metadata.
4. Normalize them into JSONL in `processed/`.
5. Validate JSON syntax, exact app schema, value constraints, IDs/references, and duplicates.
6. Split by scenario/source group into `splits/train.jsonl`, `validation.jsonl`, and `test.jsonl`. Never split near-duplicate variants across sets.
7. Train only from the validated training split. Keep validation/test data out of training.

## Dataset format

Each JSONL line is one SFT conversation:

```json
{"messages":[{"role":"system","content":"..."},{"role":"user","content":"..."},{"role":"assistant","content":"..."}],"metadata":{"task":"trpg_gm","source":"deepseek","license_review":"pending"}}
```

Use only `system`, `user`, and `assistant` roles. The assistant content must be the exact target output expected by the app. For JSON tasks, assistant content must contain one valid JSON object and no Markdown fences or surrounding commentary.

## Dataset groups

- `trpg_gm`: GM narration, choices, state changes, story memories, and NPC memories.
- `world_json`: natural-language requirements to a valid world/scenario draft.
- `extraction`: extracting only explicitly supported facts into structured JSON.

Keep these tasks separately identifiable in metadata and separate files. Do not mix app-incompatible JSON contracts.

## Quality and safety gates

- Treat the current Kotlin serializers, parsers, validators, and prompt contract on this branch as the source of truth.
- Do not invent schema keys, enum values, IDs, dice results, or game-state facts.
- Include both ordinary and edge cases, but never make invalid outputs the target answer.
- Avoid repetitive templates and superficial paraphrases.
- Exclude personal data, copyrighted passages, and outputs whose terms do not permit dataset use.
- Record the exact teacher model/provider, generation date, prompt version, and applicable license/terms review.
- Do not claim a license permits distillation until the model and API provider terms have been checked.

## Directory map

- `raw/`: untouched teacher outputs and generation manifests.
- `processed/`: normalized, validated JSONL datasets.
- `splits/`: fixed train/validation/test partitions.
- `prompts/`: versioned DeepSeek generation instructions.
- `schemas/`: dataset format and validation notes.
- `metadata/`: dataset manifest and provenance.

Generated data is not considered training-ready until validation and provenance checks pass.
