# 監査レポート（classify_qa + verify_sft_outputs）

- 対象スナップショット: `snap-0014-20261119T221153Z`
- 総件数: 1129 / PASS: 1037 / REVIEW: 31 / FAIL: 61
- PASS率: 91.9%
- ID形式: 正常 / 重複: 0
- 隔離SFT形式検証エラー: 4
- 隔離SFT行数: 1037（PASS ID 1037 と一致: True）

## 検出理由（REVIEW/FAIL）

- `python_red_flags:SCHEMA_ERROR`: 35件
- `semantic_final_verdict=fail`: 26件
- `fallback`: 19件
- `python_semantic_category_mismatch`: 12件

## 問題のあるID

| 元ID | 監査ID | 判定 | 理由 |
|---|---|---|---|
| bex17-0004 | ex-0004 | REVIEW | fallback |
| bex17-0064 | ex-0064 | REVIEW | fallback |
| bex19-0009 | ex-0089 | REVIEW | fallback |
| bex19-0051 | ex-0131 | REVIEW | python_semantic_category_mismatch |
| bex19-0055 | ex-0135 | REVIEW | fallback |
| bex19-0075 | ex-0155 | REVIEW | fallback |
| bex20-0008 | ex-0168 | REVIEW | fallback |
| bex20-0016 | ex-0176 | REVIEW | fallback |
| bex20-0043 | ex-0203 | REVIEW | python_semantic_category_mismatch |
| bex20-0049 | ex-0209 | REVIEW | fallback |
| bex20-0051 | ex-0211 | REVIEW | python_semantic_category_mismatch |
| bex21-0040 | ex-0280 | REVIEW | python_semantic_category_mismatch |
| bex21-0043 | ex-0283 | REVIEW | python_semantic_category_mismatch |
| bex21-0045 | ex-0285 | REVIEW | python_semantic_category_mismatch |
| bex21-0046 | ex-0286 | REVIEW | python_semantic_category_mismatch |
| bex21-0047 | ex-0287 | REVIEW | fallback |
| bex21-0049 | ex-0289 | REVIEW | python_semantic_category_mismatch |
| bex21-0054 | ex-0294 | REVIEW | fallback |
| bex21-0055 | ex-0295 | REVIEW | python_semantic_category_mismatch |
| bex21-0056 | ex-0296 | REVIEW | fallback |
| bex21-0058 | ex-0298 | REVIEW | python_semantic_category_mismatch |
| bex22-0040 | ex-0360 | REVIEW | python_semantic_category_mismatch |
| bex22-0049 | ex-0369 | REVIEW | python_semantic_category_mismatch |
| bex22-0055 | ex-0375 | REVIEW | fallback |
| bgm32-0110 | gm-a-0146 | REVIEW | fallback |
| bgm32-0154 | gm-a-0160 | REVIEW | fallback |
| bgm34-0001 | gm-a-0163 | REVIEW | fallback |
| bgm34-0136 | gm-a-0208 | REVIEW | fallback |
| bgm36-0038 | gm-a-0230 | REVIEW | fallback |
| bwd21-0024 | world-0059 | REVIEW | fallback |
| bwd25-0007 | world-0077 | REVIEW | fallback |
| bex17-0012 | ex-0012 | FAIL | semantic_final_verdict=fail |
| bex19-0041 | ex-0121 | FAIL | semantic_final_verdict=fail |
| bex19-0044 | ex-0124 | FAIL | semantic_final_verdict=fail |
| bex19-0058 | ex-0138 | FAIL | semantic_final_verdict=fail |
| bex20-0036 | ex-0196 | FAIL | semantic_final_verdict=fail |
| bex20-0042 | ex-0202 | FAIL | semantic_final_verdict=fail |
| bex20-0050 | ex-0210 | FAIL | semantic_final_verdict=fail |
| bex20-0070 | ex-0230 | FAIL | semantic_final_verdict=fail |
| bex20-0075 | ex-0235 | FAIL | semantic_final_verdict=fail |
| bex21-0042 | ex-0282 | FAIL | semantic_final_verdict=fail |
| bex21-0044 | ex-0284 | FAIL | semantic_final_verdict=fail |
| bex21-0050 | ex-0290 | FAIL | semantic_final_verdict=fail |
| bex21-0052 | ex-0292 | FAIL | semantic_final_verdict=fail |
| bex22-0045 | ex-0365 | FAIL | semantic_final_verdict=fail |
| bex22-0052 | ex-0372 | FAIL | semantic_final_verdict=fail |
| bex22-0053 | ex-0373 | FAIL | semantic_final_verdict=fail |
| bex22-0057 | ex-0377 | FAIL | semantic_final_verdict=fail |
| bex23-0052 | ex-0452 | FAIL | semantic_final_verdict=fail |
| bex23-0053 | ex-0453 | FAIL | semantic_final_verdict=fail |
| bex23-0070 | ex-0470 | FAIL | semantic_final_verdict=fail |
| bex23-0074 | ex-0474 | FAIL | semantic_final_verdict=fail |
| bex23-0079 | ex-0479 | FAIL | semantic_final_verdict=fail |
| bex24-0042 | ex-0522 | FAIL | semantic_final_verdict=fail |
| bex24-0070 | ex-0550 | FAIL | semantic_final_verdict=fail |
| bgm29-0001 | gm-a-0001 | FAIL | semantic_final_verdict=fail |
| bgm29-0010 | gm-a-0004 | FAIL | semantic_final_verdict=fail |
| bwd30-0001 | world-0141 | FAIL | python_red_flags:SCHEMA_ERROR |
| bwd30-0002 | world-0142 | FAIL | python_red_flags:SCHEMA_ERROR |
| bwd30-0003 | world-0143 | FAIL | python_red_flags:SCHEMA_ERROR |
| bwd30-0004 | world-0144 | FAIL | python_red_flags:SCHEMA_ERROR |
| bwd30-0005 | world-0145 | FAIL | python_red_flags:SCHEMA_ERROR |
| bwd30-0006 | world-0146 | FAIL | python_red_flags:SCHEMA_ERROR |
| bwd30-0007 | world-0147 | FAIL | python_red_flags:SCHEMA_ERROR |
| bwd30-0008 | world-0148 | FAIL | python_red_flags:SCHEMA_ERROR |
| bwd30-0009 | world-0149 | FAIL | python_red_flags:SCHEMA_ERROR |
| bwd30-0010 | world-0150 | FAIL | python_red_flags:SCHEMA_ERROR |
| bwd30-0011 | world-0151 | FAIL | python_red_flags:SCHEMA_ERROR |
| bwd30-0012 | world-0152 | FAIL | python_red_flags:SCHEMA_ERROR |
| bwd30-0013 | world-0153 | FAIL | python_red_flags:SCHEMA_ERROR |
| bwd30-0014 | world-0154 | FAIL | python_red_flags:SCHEMA_ERROR |
| bwd30-0015 | world-0155 | FAIL | python_red_flags:SCHEMA_ERROR |
| bwd30-0016 | world-0156 | FAIL | python_red_flags:SCHEMA_ERROR |
| bwd30-0017 | world-0157 | FAIL | python_red_flags:SCHEMA_ERROR |
| bwd30-0018 | world-0158 | FAIL | python_red_flags:SCHEMA_ERROR |
| bwd30-0019 | world-0159 | FAIL | python_red_flags:SCHEMA_ERROR |
| bwd30-0020 | world-0160 | FAIL | python_red_flags:SCHEMA_ERROR |
| bwd30-0021 | world-0161 | FAIL | python_red_flags:SCHEMA_ERROR |
| bwd30-0022 | world-0162 | FAIL | python_red_flags:SCHEMA_ERROR |
| bwd30-0023 | world-0163 | FAIL | python_red_flags:SCHEMA_ERROR |

## 結論

要確認: 隔離SFTと分類結果に不整合があります。
