# 監査レポート（classify_qa + verify_sft_outputs）

- 対象スナップショット: `snap-0008-20261117T120648Z`
- 総件数: 60 / PASS: 58 / REVIEW: 1 / FAIL: 1
- PASS率: 96.7%
- ID形式: 正常 / 重複: 0
- 隔離SFT形式検証エラー: 0
- 隔離SFT行数: 58（PASS ID 58 と一致: True）

## 検出理由（REVIEW/FAIL）

- `fallback`: 1件
- `semantic_final_verdict=fail`: 1件

## 問題のあるID

| 元ID | 監査ID | 判定 | 理由 |
|---|---|---|---|
| bex08-0044 | ex-0044 | REVIEW | fallback |
| bex08-0055 | ex-0055 | FAIL | semantic_final_verdict=fail |

## 結論

PASS品のみ隔離SFTに含まれ、形式検証エラー0。正式データへの統合は全Wave監査完了後。
