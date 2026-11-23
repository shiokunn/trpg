# 監査レポート（classify_qa + verify_sft_outputs）

- 対象スナップショット: `snap-0010-20261117T122807Z`
- 総件数: 150 / PASS: 137 / REVIEW: 5 / FAIL: 8
- PASS率: 91.3%
- ID形式: 正常 / 重複: 0
- 隔離SFT形式検証エラー: 0
- 隔離SFT行数: 137（PASS ID 137 と一致: True）

## 検出理由（REVIEW/FAIL）

- `semantic_final_verdict=fail`: 8件
- `fallback`: 4件
- `python_semantic_category_mismatch`: 1件

## 問題のあるID

| 元ID | 監査ID | 判定 | 理由 |
|---|---|---|---|
| bex10-0019 | ex-0019 | REVIEW | fallback |
| bex10-0044 | ex-0044 | REVIEW | fallback |
| bex10-0062 | ex-0062 | REVIEW | fallback |
| bex13-0011 | ex-0081 | REVIEW | python_semantic_category_mismatch |
| bex13-0068 | ex-0138 | REVIEW | fallback |
| bex10-0026 | ex-0026 | FAIL | semantic_final_verdict=fail |
| bex10-0029 | ex-0029 | FAIL | semantic_final_verdict=fail |
| bex10-0030 | ex-0030 | FAIL | semantic_final_verdict=fail |
| bex10-0033 | ex-0033 | FAIL | semantic_final_verdict=fail |
| bex10-0037 | ex-0037 | FAIL | semantic_final_verdict=fail |
| bex10-0041 | ex-0041 | FAIL | semantic_final_verdict=fail |
| bex10-0060 | ex-0060 | FAIL | semantic_final_verdict=fail |
| bex10-0064 | ex-0064 | FAIL | semantic_final_verdict=fail |

## 結論

PASS品のみ隔離SFTに含まれ、形式検証エラー0。正式データへの統合は全Wave監査完了後。
