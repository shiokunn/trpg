# ゲートB 適用レポート（2026-11-17）

## 承認条件と遵守状況

| 条件 | 状況 |
|---|---|
| 承認済み12件のみ反映 | 遵守。変更フィールドは12件のみ |
| #03 / #13 / #10 は原文維持 | 遵守。いずれも適用対象外 |
| 簡体字3件は修正せず別課題 | 遵守。修正していない |
| 元データを直接上書きせずバックアップ確保 | 遵守。適用前にバックアップ作成 |
| 適用後274件にスキーマ・保護・重複検査 | 実施（下記） |
| 最終JSONL / split / metadata を変更しない | 遵守。ハッシュ比較で不変を確認 |
| LoRA / Kaggle Notebook を変更しない | 遵守。触れていない |
| GitHubコミット・プッシュを行わない | 遵守。ローカルHEAD＝リモートHEAD `6cf0679` のまま |

## バックアップ

- ディレクトリ: `training/experiments/yomiyasu_trial/backup_before_apply/`
- 対象5ファイル（元rawのコピー）:
  `gm_f_01.jsonl`, `gm_forest.jsonl`, `world_03.jsonl`, `world_04.jsonl`, `extraction_03.jsonl`
- 全274件のスナップショット: `raw_snapshot_all.jsonl`（SHA-256 `88498a652f37b728...`）
- 凍結成果物の適用前ハッシュ: `frozen_artifacts_before.json`

## 適用した変更（12件）

| ID | フィールド | 変更の要旨 |
|---|---|---|
| gm-f-0001 | output.narrative | `だが` → `ただ` |
| gm-f-0002 | output.narrative | `つまり` 削除、主導更改変 |
| gm-f-0006 | output.narrative | `が` → `は`、`何度も` の語順変更 |
| gm-0001 | output.narrative | `霧へ溶け` → `霧に包まれ`、`教えてくれた` → `示していた` |
| gm-0003 | output.narrative | `霧へ` → `霧に` |
| world-0007 | output.setting | `関所が置かれ` → `関所があり` |
| world-0008 | output.setting | `横たわる` → `横たわっている` |
| world-0011 | output.setting | 条件のあとに読点を追加 |
| ex-0021 | request | 間の読点を削除 |
| ex-0022 | request | `鍵のことを` → `鍵について` |
| ex-0024 | request | 列挙の読点を2つ削除 |
| ex-0025 | request | 主題のあとの読点を削除 |

変更した元rawファイルは上記5ファイルのみ。書き込みは一時ファイル＋`os.replace()`で行い、途中状態を残していない。

## 検証結果

| 検査 | 結果 |
|---|---|
| `build_dataset.py --check` | raw_json_ok 274 / rejected **0** / exact_duplicates **0** / near_duplicates **0** / accepted 274 |
| 全274件 JSON Schema | `schema_lib.validate_output()` 不合格 **0件** |
| 保護検査 `check_protected_text.py compare` | 変更フィールド **12件のみ**、`error_count: **0**`（数値・否定・条件・ID・列挙の変化なし） |
| 重複検査 | 完全一致0 / 近似重複0 |
| 簡体字走査 `scan` | 検出 **3件**（`误`, `录`, `采`）。今回は修正せず別課題として残す |
| 凍結成果物ハッシュ比較 | 最終JSONL206件・68件、split 3ファイル、metadata の **変更0件** |
| Git 上のデータ変更 | `training/raw/` の5ファイルのみ（成果物・split・metadata は未変更） |
| コミット・プッシュ | 未実施（local = remote = `6cf0679`） |

証跡:
- `GATE_B_APPLIED.json`（適用IDと対象ファイル）
- `GATE_B_protected_compare.json`（12件の変更フィールドとエラー0）
- `GATE_B_simplified_scan.json`（簡体字3件）
- `after_apply_snapshot_274.jsonl`（適用後274件のスナップショット）
- `backup_before_apply/`（適用前バックアップ）

## 未解決事項

1. **簡体字3件**（`误`, `录`, `采`）は承認どおり未修正。別課題として扱う。
2. 最終JSONL（206件・68件）、split、metadataは凍結したままで、今回のraw変更は反映されていない。次回データ再生成時に反映されるが、その実行は今回の承認範囲外。
3. #10 は「要観察」として原文維持。ゲートB承認時にも原文を選んだため、推敲は未適用。
4. yomiyasu は推敲指示書であり、自動変換ではない。今回の12件は20件の試験でレビュー済みの差分であり、残り262件の文章改善は未実施。
5. Travis/Termux の `os.link` 不在は `yomiyasu_compat.py` で吸収済みだが、他環境での挙動は未検証。
