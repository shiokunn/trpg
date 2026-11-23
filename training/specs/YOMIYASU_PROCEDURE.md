# yomiyasu 導入前の正式手順（承認待ち）

このファイルは、yomiyasu を推敲に使うときの**正式な実行手順**を定義する。

## 承認状態

| ゲート | 状態 | 記録 |
|---|---|---|
| ゲートA（手順・ツールの採用） | **承認済み** | 2026-11-17 / ユーザー承認。対象: 本手順書、`yomiyasu_compat.py`, `check_protected_text.py`, `yomiyasu_roundtrip.py`, 試験証跡。**データへの適用は含まない。** |
| ゲートB（既存274件への適用） | **未承認** | ドライラン完了後、改めて承認を求める。承認まで既存274件・`sft_synthetic_*.jsonl`・`processed/`・`splits/`・`metadata/` は一切変更しない。 |

本番学習・Kaggle Notebook・既存LoRAは、ゲートBが承認されても変更対象に含まない。

## 0. yomiyasu の位置づけ

- `nanaism/yomiyasu` v3.1.0（MIT）は **推敲指示書（SKILL.md）＋検査ツール** であり、
  文章を自動変換するライブラリではない。推敲はエージェントが SKILL.md に従って行う。
- 機械的に使えるのは `yomiyasu_lint.py` / `yomiyasu_diff.py` / `yomiyasu_check.py` のみ。
  これらは「見直し候補」と「変化」を出すだけで、意味保持や読みやすさの合格判定はしない。

## 1. インストール（Termux / Android 対応）

リポジトリ本体はベンダリングしない。作業用に取得する。

```bash
YOMIYASU_DIR=/data/data/com.termux/files/usr/tmp/opencode/yomiyasu
git clone --depth 1 https://github.com/nanaism/yomiyasu "$YOMIYASU_DIR"
```

Termux の Python には `os.link` が無く、`yomiyasu_check.py` はそのままでは失敗する。
一時ラッパーではなく、リポジトリの正式スクリプトを使う。

```bash
export YOMIYASU_REPO="$YOMIYASU_DIR"
python3 training/tools/yomiyasu_compat.py run \
  --source src.md --final final.md --stance=説明
python3 training/tools/yomiyasu_compat.py verify --final final.md
```

`yomiyasu_compat.py` は `os.link` が無いときだけ `shutil.copyfile` 相当の
フォールバックを入れてからツールの `main()` を呼ぶ。yomiyasu 本体は改造しない。

## 2. 推敲対象の限定（保護対象）

推敲してよいのは**自然言語の本文だけ**。以下は絶対に変更しない。

| 保護対象 | 例 |
|---|---|
| JSONキー | `narrative`, `stateChanges`, `targetId` など |
| ID・識別子 | `gm-f-0001`, `n1`, `q1`, `scenario-*` |
| 数値・出目・合計・難易度 | `6`, `4/6/8/10/12/14`, `total`, `dice` |
| 判定結果 | `SUCCESS`, `FAILURE`, `outcome` |
| 列挙値 | `HP_DELTA`, `ACTIVE`, `STR`, `NORMAL` など |
| 否定・条件・制約 | `ない`, `ず`, `まだ`, `らしい`, `かもしれない`, `ではなく`, `なら`, `たら`, `場合`, `だけ` |
| 情報抽出の正解構造 | `extraction` の `output` キーと型 |
| `stateChanges` / `memories` / `history` / `action.check` | すべて protection |

推敲対象フィールドは `training/tools/check_protected_text.py` の
`ALLOWED_FIELDS` が唯一の定義（例: `output.narrative`, `request`,
`output.setting`, `output.scenario.premise`）。SCENE タイトルやイベント要約も
自然文だが、事実を要約し直すと保護情報の増減につながるため、既定では対象外。

## 3. 実行手順（1バッチ）

1. **対象を選ぶ**: 元レコードを直接触らず、IDを指定して抽出する。

   ```bash
   python3 training/tools/yomiyasu_roundtrip.py prepare \
     --ids gm-f-0001 gm-f-0002 --out work/source.md
   ```

2. **推敲する**: SKILL.md 3.1.0 に従って `source.md` → `final.md` を作る。
   文書種別は NPC会話なら `script`、世界観説明なら `article` または `formal`、
   自然文入力なら `mail` / `manual` を検討する。原文の事実・数値・否定・条件を変えない。

3. **yomiyasu の検査**:

   ```bash
   YOMIYASU_REPO=... python3 training/tools/yomiyasu_compat.py run \
     --source work/source.md --final work/final.md --stance=説明
   python3 training/tools/yomiyasu_diff.py work/source.md work/final.md --fail-on=meaning-risk
   YOMIYASU_REPO=... python3 training/tools/yomiyasu_compat.py verify --final work/final.md
   ```

   `meaning-risk` が1件でも出たら**自動採用しない**。该当箇所を原文に戻して再検査する。

4. **コピーへ書き戻す**（元 raw は変更しない）:

   ```bash
   cp work/before.jsonl work/after.jsonl   # または prepare 時の元レコード集合
   python3 training/tools/yomiyasu_roundtrip.py apply \
     --source work/source.md --final work/final.md \
     --before work/before.jsonl --after work/after.jsonl
   ```

5. **独自ガード（維持必須）**:

   ```bash
   python3 training/tools/check_protected_text.py compare \
     --before work/before.jsonl --after work/after.jsonl
   python3 training/tools/check_protected_text.py scan --files 'training/raw/*/*.jsonl'
   ```

   `compare` は保護フィールドの変化・数値/否定/条件/IDの変化・新規簡体字を検出する。
   `scan` は yomiyasu が検出できない簡体字を検出する（現状 `误` / `录` / `采` が残存）。
   どちらもエラー0でない次へ進まない。

6. **スキーマ再検証**:

   ```bash
   python3 training/tools/build_dataset.py --check
   ```

   推敲後コピーについても `schema_lib.validate_output()` を通し、不合格0を確認する。

7. **差分保存とレビュー**: `source.md`, `final.md`, `diff.txt`, `check_run.txt`,
   `check_verify.txt`, `compare` のJSON出力をバッチ単位で残す。人手レビューで
   意味・情報量・文体を確認する。

## 4. 承認ゲート

- **ゲートA（本手順の承認）**: この手順書とツールの採用可否をメインエージェントが決定する。
- **ゲートB（実データ適用の承認）**: 既存274件へ推敲結果を反映するのは**別途承認**が必要。
  承認なく既存データ・最終JSONL・split・metadata を書き換えない。
- 反映時は `build_dataset.py` → `verify_dataset.py` → `build_sft_outputs.py` →
  `verify_sft_outputs.py` を再実行し、件数・重複・スキーマを再確認する。

## 5. 試験結果の参照

20件の試験は `training/experiments/yomiyasu_trial/REPORT.md` を参照。
既存274件への適用は行っていない。
