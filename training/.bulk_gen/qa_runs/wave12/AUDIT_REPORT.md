# 監査レポート（classify_qa + verify_sft_outputs）

- 対象スナップショット: `snap-0016-20261120T232123Z`
- 総件数: 1283 / PASS: 1225 / REVIEW: 33 / FAIL: 25
- PASS率: 95.5%
- ID形式: 正常 / 重複: 0
- 隔離SFT形式検証エラー: 0
- 隔離SFT行数: 1225（PASS ID 1225 と一致: True）

## 検出理由（REVIEW/FAIL）

- `fallback`: 30件
- `semantic_final_verdict=fail`: 25件
- `python_semantic_category_mismatch`: 3件

## 問題のあるID

| 元ID | 監査ID | 判定 | 理由 |
|---|---|---|---|
| bex18-0044 | ex-0044 | REVIEW | fallback |
| bex18-0058 | ex-0058 | REVIEW | fallback |
| bex25-0009 | ex-0089 | REVIEW | python_semantic_category_mismatch |
| bex25-0039 | ex-0119 | REVIEW | fallback |
| bex25-0045 | ex-0125 | REVIEW | fallback |
| bex25-0049 | ex-0129 | REVIEW | fallback |
| bex25-0056 | ex-0136 | REVIEW | fallback |
| bex25-0073 | ex-0153 | REVIEW | python_semantic_category_mismatch |
| bgm35-0001 | gm-a-0055 | REVIEW | fallback |
| bgm41-0073 | gm-a-0295 | REVIEW | fallback |
| bgm42-0057 | gm-a-0345 | REVIEW | fallback |
| bgm42-0073 | gm-a-0349 | REVIEW | fallback |
| bwd28-0001 | world-0001 | REVIEW | fallback |
| bwd28-0018 | world-0018 | REVIEW | fallback |
| bwd28-0020 | world-0020 | REVIEW | fallback |
| bwd28-0023 | world-0023 | REVIEW | fallback |
| bwd28-0027 | world-0027 | REVIEW | fallback |
| bwd33-0023 | world-0058 | REVIEW | fallback |
| bwd34-0001 | world-0071 | REVIEW | fallback |
| bwd39-0011 | world-0256 | REVIEW | fallback |
| bwd39-0016 | world-0261 | REVIEW | python_semantic_category_mismatch |
| bwd39-0032 | world-0277 | REVIEW | fallback |
| bwd41-0025 | world-0340 | REVIEW | fallback |
| bwd41-0027 | world-0342 | REVIEW | fallback |
| bwd41-0034 | world-0349 | REVIEW | fallback |
| bwd42-0003 | world-0353 | REVIEW | fallback |
| bwd42-0005 | world-0355 | REVIEW | fallback |
| bwd42-0007 | world-0357 | REVIEW | fallback |
| bwd42-0009 | world-0359 | REVIEW | fallback |
| bwd47-0009 | world-0534 | REVIEW | fallback |
| bwd49-0010 | world-0605 | REVIEW | fallback |
| bwd49-0014 | world-0609 | REVIEW | fallback |
| bwd50-0001 | world-0631 | REVIEW | fallback |
| bex18-0050 | ex-0050 | FAIL | semantic_final_verdict=fail |
| bex18-0053 | ex-0053 | FAIL | semantic_final_verdict=fail |
| bex25-0050 | ex-0130 | FAIL | semantic_final_verdict=fail |
| bex26-0049 | ex-0209 | FAIL | semantic_final_verdict=fail |
| bgm33-0057 | gm-a-0021 | FAIL | semantic_final_verdict=fail |
| bwd28-0011 | world-0011 | FAIL | semantic_final_verdict=fail |
| bwd36-0005 | world-0145 | FAIL | semantic_final_verdict=fail |
| bwd39-0018 | world-0263 | FAIL | semantic_final_verdict=fail |
| bwd39-0034 | world-0279 | FAIL | semantic_final_verdict=fail |
| bwd40-0030 | world-0310 | FAIL | semantic_final_verdict=fail |
| bwd40-0031 | world-0311 | FAIL | semantic_final_verdict=fail |
| bwd41-0015 | world-0330 | FAIL | semantic_final_verdict=fail |
| bwd43-0002 | world-0387 | FAIL | semantic_final_verdict=fail |
| bwd43-0011 | world-0396 | FAIL | semantic_final_verdict=fail |
| bwd43-0015 | world-0400 | FAIL | semantic_final_verdict=fail |
| bwd43-0027 | world-0412 | FAIL | semantic_final_verdict=fail |
| bwd43-0029 | world-0414 | FAIL | semantic_final_verdict=fail |
| bwd44-0027 | world-0447 | FAIL | semantic_final_verdict=fail |
| bwd45-0001 | world-0456 | FAIL | semantic_final_verdict=fail |
| bwd47-0005 | world-0530 | FAIL | semantic_final_verdict=fail |
| bwd47-0007 | world-0532 | FAIL | semantic_final_verdict=fail |
| bwd47-0022 | world-0547 | FAIL | semantic_final_verdict=fail |
| bwd47-0024 | world-0549 | FAIL | semantic_final_verdict=fail |
| bwd49-0007 | world-0602 | FAIL | semantic_final_verdict=fail |
| bwd49-0025 | world-0620 | FAIL | semantic_final_verdict=fail |

## 結論

PASS品のみ隔離SFTに含まれ、形式検証エラー0。正式データへの統合は全Wave監査完了後。
