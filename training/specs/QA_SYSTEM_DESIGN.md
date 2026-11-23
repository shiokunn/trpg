# 品質保証システム 設計・変更計画

## 目的

既存の `score_dataset_quality.py` をベースに、約10,000件規模の学習データに対して
再現可能で監査可能な品質保証を行う5機能を追加する。

1. 機械検証（スキーマ・ID・出目・HP・数値・状態遷移）
2. 意味検証（捏造・反転・判定矛盾・秘密漏洩）
3. 品質評価（100点満点・重大違反は点数に関係なく不合格）
4. 自動修正（原文のみを根拠にした候補生成・差分保存・再検証）
5. 回帰テスト（正常系／境界値／異常系／既存バグの再発防止）

## 既存実装の調査結果（読み取り専用）

| 項目 | 現状 |
|---|---|
| 採点 | `score_dataset_quality.py` 476行。機械55点／人手45点、70点以下または赤線でレビュー対象 |
| 契約 | `schema_lib.py` 843行がKotlinのパーサ／バリデータを写す。`build_dataset.py` が封筒・重複・分割を管理 |
| 赤線 | 現状は `SCHEMA_ERROR`, `UNKNOWN_ID`, `DICE_MISMATCH`, `SECRET_LEAK`, `ENEMY_HP_CHANGE` の5種類。定義書の7項目のうち「事実捏造」「否定・条件・数値反転」が未対応 |
| 既知の不具合 | `ITEM_ADD.id` の二重減点、完全一致＋近似重複の二重減点、秘密漏洩の完全一致のみ、物語と判定結果の矛盾が赤線化されない |
| スケール | 現状は全件をメモリに読み込む。10,000件では動くが再開・差分管理・バッチ処理が無い |

## 設計方針

### 互換性
- 既存 `score_dataset_quality.py` は変更せず、新規 `quality_system.py` を**追加**する。
  既存CLIの挙動（JSON/CSV出力、70点閾値）は変えない。
- 配点・データ形式（`raw/*.jsonl` の封筒、`messages` 形式）・定義書の70点閾値は維持する。
- `quality_system.py` は既存モジュール（`schema_lib`, `build_dataset`, `check_simplified_chinese`）を再利用する。

### データモデル
各サンプルにつき以下を1レコードとして保存する。

```
id, file, category, task, group,
content_hash, verifier_version,
machine_score, quality_score, total, verdict,
red_flags[], findings[], fix_candidates[],
processed_at
```

- `content_hash` は入力レコードの正規化JSONのSHA-256。同じ入力なら同じ結果になる。
- `verifier_version` は検査器のバージョン文字列（例: `qa-1.0.0/schema-lib-1`）。
- `verdict` は `pass` / `review` / `fail`。重大違反は点数に関係なく `fail`。

### 5機能の割り当て

| 機能 | 実装 |
|---|---|
| 1 機械検証 | `machine_validate()` が `schema_lib.validate_output` + 封筒 + 判定式 + 実在ID + 重複 + 簡体字を検査 |
| 2 意味検証 | `semantic_validate()` が原文（context/request/action）を唯一の根拠にして捏造・反転・判定矛盾・秘密漏洩を検査し、信頼度を付与 |
| 3 品質評価 | `evaluate_quality()` が機械55点と品質45点を計算。`red_flags` があれば `fail` |
| 4 自動修正 | `generate_candidates()` が許容された決定論的修正のみ候補を生成。`apply` は別ディレクトリへ出力し、rawは変更しない。修正後に全検証を再実行 |
| 5 回帰テスト | `regression_suite()` がラベル付きフィクスチャを生成し、精度・再現率・誤検知を計測 |

### 大規模処理

- `--root` 配下のJSONLを**ファイル単位・バッチ単位**で読む（`batch-size` 既定500）。
- `--resume` 時に `ledger.jsonl` の完了済み `id + content_hash` をスキップ。
- `--workers` でプロセス並列。結果は `id` 順にソートして書き出し、順序を決定論的に保つ。
- 中間状態は `ledger.jsonl` に追記のみ。再開しても同一入力のスコアは変わらない。

### 自動修正の原則

- 原文（当該レコードの `context` / `request` / `action`）以外を根拠にしない。
- 許容する修正は以下に限定する。
  - 未知キーの削除（スキーマのキー集合に従うのみ）
  - `ITEM_ADD.item.id` の削除（仕様で禁止）
  - `check.total` / `check.outcome` の再計算（式から一意に決まる）
  - 原文に一意に存在するIDへの `targetId` 復元（候補が1つの場合のみ）
- 数値・否定・条件・判定結果を推測で書き換えない。判断できない場合は `hold` として記録する。
- 候補は `candidates/` 配下に before/after/diff を保存し、適用先を明示的に指定しない限りrawへ書かない。

### 誤検知・見逃しの測定

- 回帰テストの各ケースに `expected_codes` と `expected_verdict` を付与。
- 検出結果と比較して TP / FP / FN を集計し、precision / recall をJSONで出力する。
-  motifs 既存バグ（FLAG_SET誤検知、notes英字誤検知、二重減点）をケースとして固定する。

## 変更計画

1. `training/tools/quality_system.py` を新規作成（5機能＋CLI）。
2. `training/specs/QA_SYSTEM_DESIGN.md`（本ファイル）を作成。
3. 一時ディレクトリで回帰テスト・境界値テスト・大量データ処理テストを実施。
4. 既存274件に読み取り専用で適用し、既存の採点結果と互換性があることを確認。
5. 既存raw・JSONL・split・metadata・LoRA・Kaggle Notebookは変更しない。
6. コミット・プッシュは行わない。

## 既知の制約

- 品質45点は依然としてヒューリスティック推定であり、人手レビューの代替ではない。
- 意味検証はルールベースであり、比喩・文脈依存の捏造は見逃す可能性がある。
- 並列処理はプロセス数に比例してメモリを使う。10,000件规模では `--workers 4` 程度を想定する。
