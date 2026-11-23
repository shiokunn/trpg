# 監査レポート（classify_qa + verify_sft_outputs）

- 対象スナップショット: `snap-0006-20261117T120140Z`
- 総件数: 106 / PASS: 101 / REVIEW: 3 / FAIL: 2
- PASS率: 95.3%
- ID形式: 正常 / 重複: 0
- 隔離SFT形式検証エラー: 0
- 隔離SFT行数: 101（PASS ID 101 と一致: True）

## 検出理由（REVIEW/FAIL）

- `fallback`: 3件
- `semantic_final_verdict=fail`: 2件

## 問題のあるID

| 元ID | 監査ID | 判定 | 理由 |
|---|---|---|---|
| bex07-0034 | ex-0034 | REVIEW | fallback |
| bex07-0039 | ex-0039 | REVIEW | fallback |
| bex07-0042 | ex-0042 | REVIEW | fallback |
| bex07-0047 | ex-0047 | FAIL | semantic_final_verdict=fail |
| bwd07-0018 | world-0018 | FAIL | semantic_final_verdict=fail |

## 結論

PASS品のみ隔離SFTに含まれ、形式検証エラー0。正式データへの統合は全Wave監査完了後。
