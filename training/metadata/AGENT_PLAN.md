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
| C01 | 冒険・探索 A | `raw/trpg_gm/gm_j_01.jsonl` | 完了（9件） |
| C02 | 冒険・探索 B | `raw/trpg_gm/gm_k_01.jsonl` | 完了（9件） |
| C03 | NPC・会話 A | `raw/trpg_gm/gm_l_01.jsonl` | 完了（9件） |
| C04 | NPC・会話 B | `raw/trpg_gm/gm_m_01.jsonl` | 完了（9件） |
| C05 | 戦闘・イベント A | `raw/trpg_gm/gm_n_01.jsonl` | 稼働中 |
| C06 | 戦闘・イベント B | `raw/trpg_gm/gm_o_01.jsonl` | 完了（9件） |
| C07 | 世界観・設定 A | `raw/world_json/world_04.jsonl` | 完了（3件） |
| C08 | 世界観・設定 B | `raw/world_json/world_05.jsonl` | 完了（3件） |
| C09 | アイテム・魔法 A | `raw/extraction/extraction_05.jsonl` | 完了（12件） |
| C10 | アイテム・魔法 B | `raw/extraction/extraction_06.jsonl` | 完了（12件） |
| C11 | プレイヤー行動・分岐 A | `raw/trpg_gm/gm_p_01.jsonl` | 完了（9件） |
| C12 | プレイヤー行動・分岐 B | `raw/trpg_gm/gm_q_01.jsonl` | 完了（9件） |

※ 本体制発足前に起動していた GM H/I は完了（各9件、rejected 0）。回収済み。

### 専門チーム（8人）

| # | 担当 | 作業 | 状態 |
|---:|---|---|---|
| S01 | JSON構造・スキーマ検証 A | `schema_lib.py` で raw 全件を検証し不合格IDを報告 | メインが機械検証で代替中 |
| S02 | JSON構造・スキーマ検証 B | 統合後の2ファイルを再パースし件数を照合 | 未着手 |
| S03 | 日本語品質・自然さ A | 抽出したサンプルの不自然さ・矛盾を指摘 | 未着手 |
| S04 | 日本語品質・自然さ B | 同上（別カテゴリ） | 未着手 |
| S05 | 重複・多様性検査 | 完全一致・近似重複・グループ偏りを集計 | メインが機械検証で代替中 |
| S06 | 学習データ形式・整形 | `messages` 形式・role 構成・JSONL改行を検証 | 未着手 |
| S07 | データ統合 | 合格行のみを2ファイルへ集約 | メインが実施 |
| S08 | GitHub管理・最終確認 | 配置先・件数・整合性の最終確認 | メインが実施（pushは要確認） |

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

## 制約（再掲）

- 既存LoRAモデル・Kaggle Notebook・本番学習・既存学習成果物は変更しない。
- 既存データを無断で上書きしない。
- 未検証データを完成品として扱わない。検証していない件数を報告しない。
- サブエージェントの無料枠に上限があり、20人同時起動は失敗する可能性がある。
  その場合は波状（4〜5人ずつ）に分割起動し、失敗分はメインが回収する。
