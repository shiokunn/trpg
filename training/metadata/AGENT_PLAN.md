# LFM3 TRPG学習データ生成 — エージェント体制と進捗

## Phase 1: 既存環境の調査結果（2026-11-17）

| 項目 | 調査結果 |
|---|---|
| ブランチ | `feature/world-json-slot-template` / HEAD `20b52736631d0d137c52a4ca5037f13ec5061462` |
| リモート | `origin https://github.com/shiokunn/trpg.git` |
| 既存の `sft_synthetic_json.jsonl` | **存在しない**（要在庫確認済み） |
| 既存の `sft_synthetic_extraction.jsonl` | **存在しない** |
| Kaggle Notebook / `.ipynb` | リポジトリ内に**存在しない**（変更対象なし） |
| 既存学習データ | `training/raw/`, `training/processed/`, `training/splits/`, `training/metadata/` |
| 検証基盤 | `training/tools/schema_lib.py`（Kotlin契約の写し）, `build_dataset.py`, `verify_dataset.py` |
| 生成ルール | `training/specs/GENERATION_SPEC.md`, シナリオ台帳 `training/specs/BATCH_THEMES.md` |
| 未コミット変更 | `.github/workflows/android.yml`, `GmTurnService.kt`, `MemorySelector.kt`, `PromptBuilder.kt`, `docs/*`, `scripts/*` — **一切触れない** |

### 配置先の決定

指定された2ファイルはリポジトリ内に存在しなかったため、
既存の学習データ置き場 `training/` の直下に**新規作成**する（上書きなし）。

- `training/sft_synthetic_json.jsonl`
- `training/sft_synthetic_extraction.jsonl`

### 2ファイルへの割り当て（重要な判断）

| ファイル | 含めるカテゴリ | 根拠 |
|---|---|---|
| `sft_synthetic_json.jsonl` | `trpg_gm`（GM応答JSON生成）＋ `world_json`（WorldDraft生成） | 「TRPGに関する情報を適切なJSON構造に変換・生成する能力」。両者とも出力がJSON契約に従う生成タスク。 |
| `sft_synthetic_extraction.jsonl` | `extraction`（7タスク） | 「自然言語から必要情報を抽出しJSONに変換する能力」。生成タスクと明確に区別する。 |

この割り当ては指示書の「正しいJSONを生成する能力と、文章から情報を抽出する能力を区別する」に基づく。
指示書に明示的なマッピングが無かったため、メインエージェントの判断として記録する。

### 出力形式

両ファイルとも 1行1サンプル、`{"messages": [{"role":...}]}`。
`assistant.content` は compact JSON 文字列。
`processed/` の検証済み行をそのまま集約し、不合格行は一切含めない。
chat template は未確定（リポジトリにLFM3学習スクリプトが無いため）。学習時に
`tokenizer.apply_chat_template()` で適用する方針は変更なし。

## Phase 2: エージェント構成（20サブエージェント＋メイン1）

### 創作チーム（12人）

| # | 担当 | 割り当てカテゴリ / 出力先 | 状態 |
|---:|---|---|---|
| C01 | 冒険・探索 A | `raw/trpg_gm/gm_j_01.jsonl` | 完了（9件、rejected 0） |
| C02 | 冒険・探索 B | `raw/trpg_gm/gm_k_01.jsonl` | 完了（9件、rejected 0） |
| C03 | NPC・会話 A | `raw/trpg_gm/gm_l_01.jsonl` | 完了（9件、rejected 0） |
| C04 | NPC・会話 B | `raw/trpg_gm/gm_m_01.jsonl` | 完了（9件、rejected 0） |
| C05 | 戦闘・イベント A | `raw/trpg_gm/gm_n_01.jsonl` | 稼働中 |
| C06 | 戦闘・イベント B | `raw/trpg_gm/gm_o_01.jsonl` | 完了（9件、rejected 0） |
| C07 | 世界観・設定 A | `raw/world_json/world_04.jsonl` | 完了（3件、rejected 0） |
| C08 | 世界観・設定 B | `raw/world_json/world_05.jsonl` | 完了（3件、rejected 0） |
| C09 | アイテム・魔法 A | `raw/extraction/extraction_05.jsonl` | 完了（12件、rejected 0） |
| C10 | アイテム・魔法 B | `raw/extraction/extraction_06.jsonl` | 完了（12件、rejected 0） |
| C11 | プレイヤー行動・分岐 A | `raw/trpg_gm/gm_p_01.jsonl` | 完了（9件、rejected 0） |
| C12 | プレイヤー行動・分岐 B | `raw/trpg_gm/gm_q_01.jsonl` | 完了（9件、rejected 0） |

※ 本体制発足前に起動していた GM H/I は完了（各9件、rejected 0）。回収済み。

### 専門チーム（8人）

| # | 担当 | 作業 | 状態 |
|---:|---|---|---|
| S01 | JSON構造・スキーマ検証 A | raw全件を検証 | 完了（274件/不合格0） |
| S02 | JSON構造・スキーマ検証 B | 統合後2ファイルを再検証 | 完了（274件/エラー0） |
| S03 | 日本語品質・自然さ A | 中心データ120行を抽检 | 完了（簡体字3種・算出1件を修正） |
| S04 | 日本語品質・自然さ B | 抽出68件を全文検査 | 完了（18件を修正） |
| S05 | 重複・多様性検査 | 完全一致・近似重複を集計 | 完了（各0件） |
| S06 | 学習データ形式・整形 | 形式・role・改行を検証 | 完了（必須修正なし、リスク3件を指摘） |
| S07 | データ統合 | 合格行のみを2ファイルへ集約 | 完了（206/68件） |
| S08 | GitHub管理・最終確認 | push・リモート配置確認 | 完了（origin HEAD 6a8c46b、両ファイル確認） |

### 進捗（実測値のみ）

| 項目 | 件数 |
|---|---:|
| raw 検証済み（現時点） | 163 |
| 不合格 | 0 |
| 完全一致重複 | 0 |
| 近似重複 | 0 |
| 目標（指示書） | 10,000 |
| 未達 | 8,837 |

**重要**: 20人並列でも1バATCHあたり10〜12件程度の高品質データしか生産できず、
1セッションで10,000件に到達する見込みはない。件数を優先して言い換え量産・
テンプレート再生を行わない（指示書の禁止事項に反するため）。
実測件数と未達を正直に報告する。

## Phase 3〜5 の結果

| 項目 | 結果 |
|---|---:|
| raw 検証済み | 274件（trpg_gm 191 / world_json 15 / extraction 68） |
| 不合格 | 0件 |
| 完全一致重複 / 近似重複 | 0件 / 0件 |
| split | train 246 / validation 14 / test 14（グループ漏洩なし） |
| 最終ファイル | `sft_synthetic_json.jsonl` 206件 / `sft_synthetic_extraction.jsonl` 68件 |
| 専門レビュー後の修正 | 22件（S04の18件＋type正規化4件）、簡体字3種・判定算術1件も修正 |
| スキーマ再検証 | 全274件・エラー0（S02） |
| 形式検証 | エラー0（verify_sft_outputs.py／S06） |
| 目標10,000件との差 | 9,726件未達（約2.7%） |

### GitHub 配置

- ブランチ: `feature/world-json-slot-template`
- リモート HEAD: `6a8c46bc49541cebf5d590be910c303f0dbb5990`（ローカルHEADと一致）
- 配置ファイル: `training/sft_synthetic_json.jsonl`（206行）, `training/sft_synthetic_extraction.jsonl`（68行）
- マージ: リモートに既存の `training/README.md`（ワークスペース規約）があったため
  `5a2b73d` をマージし、規約内容を保持したうえで本データセット説明を追記。
- 既存の未コミット変更（`.github`, `app`, `core`, `docs`, `scripts`）は未接触。

### 未解決

- 10,000件には未到達。単純言い換え・テンプレート量産は禁止のため、件数優先の生成は行っていない。
- 学習トークナイザの chat template が未確定。推論用 `LFM3.jinja` は `<think></think>` を挿入するため、
  loss マスキングと生成開始位置の確認が必要。
- S03 は120行の抽检であり、86行は未レビュー。
- 本番学習・Kaggle Notebook は変更していない。

## 制約（再掲）

- 既存LoRAモデル・Kaggle Notebook・本番学習・既存学習成果物は変更しない。
- 既存データを無断で上書きしない。
- 未検証データを完成品として扱わない。検証していない件数を報告しない。
- サブエージェントの無料枠に上限があり、20人同時起動は失敗する可能性がある。
  その場合は波状（4〜5人ずつ）に分割起動し、失敗分はメインが回収する。
