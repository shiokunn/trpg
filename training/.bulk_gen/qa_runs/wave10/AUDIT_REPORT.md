# 監査レポート（classify_qa + verify_sft_outputs）

- 対象スナップショット: `snap-0013-20261118T141312Z`
- 総件数: 761 / PASS: 729 / REVIEW: 5 / FAIL: 27
- PASS率: 95.8%
- ID形式: 正常 / 重複: 0
- 隔離SFT形式検証エラー: 0
- 隔離SFT行数: 729（PASS ID 729 と一致: True）

## 検出理由（REVIEW/FAIL）

- `semantic_final_verdict=fail`: 27件
- `fallback`: 3件
- `python_semantic_category_mismatch`: 2件

## 問題のあるID

| 元ID | 監査ID | 判定 | 理由 |
|---|---|---|---|
| bex16-0040 | ex-0120 | REVIEW | python_semantic_category_mismatch |
| bex16-0049 | ex-0129 | REVIEW | python_semantic_category_mismatch |
| bgm28-0055 | gm-a-0127 | REVIEW | fallback |
| bgm30-0002 | gm-a-0164 | REVIEW | fallback |
| bgm30-0146 | gm-a-0212 | REVIEW | fallback |
| bex16-0045 | ex-0125 | FAIL | semantic_final_verdict=fail |
| bex16-0052 | ex-0132 | FAIL | semantic_final_verdict=fail |
| bex16-0053 | ex-0133 | FAIL | semantic_final_verdict=fail |
| bex16-0055 | ex-0135 | FAIL | semantic_final_verdict=fail |
| bex16-0057 | ex-0137 | FAIL | semantic_final_verdict=fail |
| bgm30-0093 | gm-a-0195 | FAIL | semantic_final_verdict=fail |
| bwd15-0007 | world-0042 | FAIL | semantic_final_verdict=fail |
| bwd17-0001 | world-0071 | FAIL | semantic_final_verdict=fail |
| bwd17-0002 | world-0072 | FAIL | semantic_final_verdict=fail |
| bwd17-0004 | world-0074 | FAIL | semantic_final_verdict=fail |
| bwd22-0017 | world-0227 | FAIL | semantic_final_verdict=fail |
| bwd23-0003 | world-0248 | FAIL | semantic_final_verdict=fail |
| bwd23-0006 | world-0251 | FAIL | semantic_final_verdict=fail |
| bwd23-0009 | world-0254 | FAIL | semantic_final_verdict=fail |
| bwd23-0012 | world-0257 | FAIL | semantic_final_verdict=fail |
| bwd23-0015 | world-0260 | FAIL | semantic_final_verdict=fail |
| bwd23-0018 | world-0263 | FAIL | semantic_final_verdict=fail |
| bwd23-0021 | world-0266 | FAIL | semantic_final_verdict=fail |
| bwd23-0024 | world-0269 | FAIL | semantic_final_verdict=fail |
| bwd23-0027 | world-0272 | FAIL | semantic_final_verdict=fail |
| bwd23-0030 | world-0275 | FAIL | semantic_final_verdict=fail |
| bwd23-0033 | world-0278 | FAIL | semantic_final_verdict=fail |
| bwd26-0004 | world-0319 | FAIL | semantic_final_verdict=fail |
| bwd26-0007 | world-0322 | FAIL | semantic_final_verdict=fail |
| bwd26-0025 | world-0340 | FAIL | semantic_final_verdict=fail |
| bwd27-0002 | world-0352 | FAIL | semantic_final_verdict=fail |
| bwd27-0020 | world-0370 | FAIL | semantic_final_verdict=fail |

## 結論

PASS品のみ隔離SFTに含まれ、形式検証エラー0。正式データへの統合は全Wave監査完了後。
