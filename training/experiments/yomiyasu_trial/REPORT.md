# yomiyasu 導入試験レポート（承認待ち）

## 試験条件

| 項目 | 内容 |
|---|---|
| 対象ツール | `nanaism/yomiyasu` v3.1.0（commit `db78b9b`）、MIT |
| 検査ツール | `yomiyasu_lint.py`, `yomiyasu_diff.py`, `yomiyasu_check.py`（Python標準ライブラリのみ） |
| 取得先 | 一時ディレクトリ `/data/data/com.termux/files/usr/tmp/opencode/yomiyasu-trial/yomiyasu` |
| 試験対象 | 20文（GM narration 10 / World setting 5 / 自然文入力 5） |
| 対象データ | `selected.json` に記録。元レコードへの書き込みなし |
| 推敲方法 | SKILL.md 3.1.0 の原則を適用して `final.md` を作成（ツールは検査のみ） |
| 証跡 | `source.md`, `final.md`, `lint_source.json`, `lint_final.json`, `diff.txt`, `check_run.txt`, `check_verify.txt`, `trial_before.jsonl`, `trial_after.jsonl`, `json_check.json` |
| 正式手順 | `training/specs/YOMIYASU_PROCEDURE.md`（承認待ち）。Termux互換は `training/tools/yomiyasu_compat.py`、独自ガードは `training/tools/check_protected_text.py`、抽出/書き戻しは `training/tools/yomiyasu_roundtrip.py`。 |

### 承認前の追加作業の結果

1. **Termux互換**: `yomiyasu_compat.py` で `os.link` 不在を正式に吸収。`run` / `verify` とも終了コード0。
2. **独自文字走査**: `check_protected_text.py scan` が既存rawから簡体字3件（`误`, `录`, `采`）を検出。yomiyasu lint は検出しないため、この検査を維持する。既存データは変更していない。
3. **保護対象チェック**: `check_protected_text.py compare` で条件トークン（`だけ`）の消失1件を検出して推敲稿を修正。最終的にエラー0。
4. **推敲対象の限定**: `ALLOWED_FIELDS` に自然言語本文のみを列挙。キー/ID/数値/否定/条件/判定/stateChanges は変更不可。
5. **実データへの適用**: 未実施。ゲートB（別途承認）を待つ。
| 副作用 | 既存274件・2つの最終JSONL・processed/splits/metadata は**未変更** |

## 比較結果

| 観点 | 結果 |
|---|---|
| 日本語の自然さ | lint候補は原文2件(info) → 推敲後2件(info)、warn/error は両方0。lintは合格点ではないため、自然さは目視で判断: 主述の明確化、比喩動詞の平易化（霧へ溶け→霧に包まれ、枝が教えてくれた→枝の折れ方から分かった）、間を作るだけの読点と予告の「つまり」を削除し、15件で可読性が改善。5件は自然なため変更なし。 |
| 意味の保持 | yomiyasu diff の意味変更リスクは最終的に **0件**。初回に「静止していた→動いていない」が否定変化として検出され、ルール6に従い自動採用せず差し戻した。 |
| 情報の追加・削除 | 事実・人物・原因・数値・条件の追加なし。削除したのは「つまり」「それから」等のつなぎと重複表現のみ。「長く黙り→しばらく黙り」は含みの幅がわずかに変わるため要注意だが、事実の追加・削除ではない。 |
| 数値・否定・条件の保持 | 独自トークン検査で数値・否定マーカー（ない/ず/まだ/らしい/かもしれない/ではなく/未）の増減 **0件**。「置かずに→置いたまま」は意味同じだが否定マーカー「ず」が消えたため差し戻した。 |
| JSON構造の保持 | 20件のコピー（`trial_before.jsonl` / `trial_after.jsonl`）を `schema_lib` で検証: 前後とも不合格 **0件**。変更されたフィールドパスは対象の `output.narrative` / `output.setting` / `request` のみで、キー・ID・列挙・判定結果・stateChanges は不変。 |

## yomiyasu 側の検査出力

```
yomiyasu-check: executed via=verify run=20261117T075353Z-8b77f53a attempt=3
final=sha256:c05bdf62cace mode=rewrite checks=lint,diff meaning_risk=0 replay=skipped
check_protected_text.py compare: error_count=0 / 20 records
```

- `yomiyasu_diff.py --fail-on=meaning-risk`: 終了コード 0
- `yomiyasu_check.py verify`: 終了コード 0（最終稿ハッシュ一致）
- lint の残候補2件は `negative_parallelism`（「数値ではなく、訂正のほう」「鍵を隠し持っている…まだ知らせない」）。いずれも誤解を正す否定・情報不足の明示であり、SKILL.md の原則に従い残した。

## 発見された問題・限界

1. **yomiyasu は推敲ツールではなく「指示書＋検査ツール」**。文章変換はエージェントが SKILL.md に従って行うため、同じ原文でも推敲結果は担当者よって差が出る。自動品質ゲートとして使えるのは lint / diff / check の機械部分だけ。
2. **Termux 環境で `yomiyasu_check.py` が失敗**（`os.link` 不在）。一時ラッパーで `os.link` を `shutil.copyfile` 相当に置き換えて実行した。yomiyasu 本体は未変更。正式導入時はこの互換対応が必要。
3. **簡体字は yomiyasu lint が検出しない**。試験原文の `gm-f-0003` に `误り` / `記录板` が残っていたが、lint は指摘せず、手作業レビューで発見した。日本語品質検査には引き続き独自の文字走査が必要。
4. **S03 は120行抽检で86行未レビュー**。yomiyasu を入れても全件レビューが不要になるわけではない。
5. **保護対象との相性**: 台詞・固有名詞・判定結果は SKILL.md の `script` 相当の扱いで保持すべき。lint の `--doc-type script` を使うと台詞の口調が候補から外れるため、今後は文書種別ごとに使い分けるのが望ましい。

## ゲートA承認後の追加作業（2026-11-17）

- ゲートA（手順・ツールの採用）はユーザーにより承認済み。`training/specs/YOMIYASU_PROCEDURE.md` に記録した。
- 証跡を `training/experiments/yomiyasu_trial/` に整理し、`MANIFEST.json`（全23ファイルのパス・サイズ・SHA-256）を追加した。
- **ゲートB（既存274件への適用）は未承認**。既存raw・最終JSONL・processed/splits/metadata は未変更。

### 274件対象の適用前ドライラン（コピー上のみ）

| 項目 | 結果 |
|---|---|
| 対象 | `dryrun_274/before.jsonl` / `after.jsonl`（いずれも274件のコピー） |
| 変更提案 | 15件（承認済み20件の試験から、実際に差分のあった15レコードのみ） |
| 変更なし | 259件（no-op。274件全体を推敲したわけではない） |
| スキーマ検証 | 前後とも不合格 0件 |
| `check_protected_text.py compare` | `error_count: 0` |
| yomiyasu diff | 意味変更リスク 0件（20件の推敲対象に対して実施） |
| yomiyasu check | `run` / `verify` とも終了コード0、最終ハッシュ一致 |
| 元データへの書き込み | なし |

**重要**: このドライランは、承認済みの手順・ツール・保護検査が274件規模で動作することを確認したもの。
254件超の文章を実際に推敲して品質改善を測ったものではない。ゲートB承認後、
推敲対象を選んで改めてバッチ実行し、同じゲートを通す必要がある。

## REVIEW_15 反映後の適用候補版（最終）

`REVIEW_15.md` の判定に従い、推敲結果を更新した。

| 項目 | 結果 |
|---|---|
| 採用 | 12件（#01, #02, #06, #07, #09, #11, #12, #15, #16, #17, #19, #20） |
| 差し戻し | #03 と #13 を原文に復元 |
| 原文維持 | #10 を原文に復元 |
| 簡体字 | 別途 `SIMPLIFIED_CHAR_FINDINGS.json` に記録。今回は修正しない |
| 変更レコード数 | 20件中12件（元274件中12件） |
| スキーマ検証 | 20件コピー・274件コピーとも不合格 0件 |
| 保護検査 | `compare` error_count=0（20件コピー・274件コピーとも） |
| yomiyasu diff | 意味変更リスク 0件（変更13か所） |
| yomiyasu check | run/verify 終了コード0、`attempt=4`、ハッシュ一致 |
| データへの適用 | 未実施（ゲートB未承認） |

差し戻し3件の詳細は `REVIEW_15.md` を参照。 #03 に含まれる簡体字は原文由来で、
yomiyasu の推敲範囲外として今回は触れていない。

## 結論（ゲートB承認待ち）

20件の試験と274件コピー上のドライランで、意味変更リスク0・JSON構造不変・
数値/否定/条件保持を確認した。ゲートA（手順・ツールの採用）は承認済み。

残るのは **ゲートB: 既存274件への推敲結果の適用** のみである。
ゲートBを承認するまで、以下は行わない:

- 既存274件、`sft_synthetic_*.jsonl`、`processed/`、`splits/`、`metadata/` の変更
- 本番学習、既存LoRA、Kaggle Notebook の変更

ゲートB承認後も、推敲対象は自然言語本文のみとし、
`check_protected_text.py compare` / `scan` とスキーマ再検証を毎バッチ必須で通す。
