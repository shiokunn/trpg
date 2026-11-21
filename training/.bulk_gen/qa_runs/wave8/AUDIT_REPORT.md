# 監査レポート（classify_qa + verify_sft_outputs）

- 対象スナップショット: `snap-0011-20261117T123515Z`
- 総件数: 1749 / PASS: 1654 / REVIEW: 69 / FAIL: 26
- PASS率: 94.6%
- ID形式: 正常 / 重複: 0
- 隔離SFT形式検証エラー: 69
- 隔離SFT行数: 1654（PASS ID 1654 と一致: True）

## 検出理由（REVIEW/FAIL）

- `fallback`: 48件
- `semantic_final_verdict=fail`: 26件
- `python_semantic_category_mismatch`: 21件

## 問題のあるID

| 元ID | 監査ID | 判定 | 理由 |
|---|---|---|---|
| bex01-0022 | ex-0022 | REVIEW | fallback |
| bex03-0022 | ex-0070 | REVIEW | fallback |
| bex03-0023 | ex-0071 | REVIEW | fallback |
| bex04-0017 | ex-0095 | REVIEW | fallback |
| bex04-0028 | ex-0106 | REVIEW | fallback |
| bex06-0006 | ex-0154 | REVIEW | fallback |
| bex06-0012 | ex-0160 | REVIEW | fallback |
| bex06-0013 | ex-0161 | REVIEW | python_semantic_category_mismatch |
| bex07-0048 | ex-0236 | REVIEW | fallback |
| bex08-0055 | ex-0293 | REVIEW | python_semantic_category_mismatch |
| bex09-0049 | ex-0347 | REVIEW | fallback |
| bex10-0026 | ex-0394 | REVIEW | python_semantic_category_mismatch |
| bex10-0030 | ex-0398 | REVIEW | python_semantic_category_mismatch |
| bex12-0003 | ex-0521 | REVIEW | fallback |
| bex12-0011 | ex-0529 | REVIEW | python_semantic_category_mismatch |
| bex12-0020 | ex-0538 | REVIEW | python_semantic_category_mismatch |
| bex12-0043 | ex-0561 | REVIEW | python_semantic_category_mismatch |
| bex12-0062 | ex-0580 | REVIEW | python_semantic_category_mismatch |
| bex12-0066 | ex-0584 | REVIEW | python_semantic_category_mismatch |
| bex12-0073 | ex-0591 | REVIEW | python_semantic_category_mismatch |
| bex12-0074 | ex-0592 | REVIEW | fallback |
| bex12-0075 | ex-0593 | REVIEW | fallback |
| bex12-0077 | ex-0595 | REVIEW | fallback |
| bex12-0078 | ex-0596 | REVIEW | fallback |
| bex12-0079 | ex-0597 | REVIEW | python_semantic_category_mismatch |
| bex13-0011 | ex-0609 | REVIEW | python_semantic_category_mismatch |
| bex13-0055 | ex-0653 | REVIEW | fallback |
| bex13-0068 | ex-0666 | REVIEW | fallback |
| bex14-0011 | ex-0689 | REVIEW | python_semantic_category_mismatch |
| bex14-0028 | ex-0706 | REVIEW | fallback |
| bex14-0029 | ex-0707 | REVIEW | fallback |
| bex14-0030 | ex-0708 | REVIEW | python_semantic_category_mismatch |
| bex14-0046 | ex-0724 | REVIEW | fallback |
| bex14-0047 | ex-0725 | REVIEW | fallback |
| bex14-0050 | ex-0728 | REVIEW | fallback |
| bex14-0052 | ex-0730 | REVIEW | python_semantic_category_mismatch |
| bex14-0056 | ex-0734 | REVIEW | python_semantic_category_mismatch |
| bex14-0067 | ex-0745 | REVIEW | python_semantic_category_mismatch |
| bgm01-0010 | gm-a-0004 | REVIEW | fallback |
| bgm01-0012 | gm-a-0006 | REVIEW | python_semantic_category_mismatch |
| bgm01-0019 | gm-a-0007 | REVIEW | fallback |
| bgm01-0021 | gm-a-0009 | REVIEW | python_semantic_category_mismatch |
| bgm01-0030 | gm-a-0012 | REVIEW | fallback |
| bgm04-0038 | gm-a-0062 | REVIEW | fallback |
| bgm06-0003 | gm-a-0099 | REVIEW | fallback |
| bgm07-0029 | gm-a-0131 | REVIEW | fallback |
| bgm07-0037 | gm-a-0133 | REVIEW | fallback |
| bgm09-0010 | gm-a-0184 | REVIEW | fallback |
| bgm10-0010 | gm-a-0214 | REVIEW | fallback |
| bgm10-0030 | gm-a-0222 | REVIEW | fallback |
| bgm16-0002 | gm-a-0452 | REVIEW | fallback |
| bgm16-0047 | gm-a-0467 | REVIEW | fallback |
| bgm16-0048 | gm-a-0468 | REVIEW | fallback |
| bgm16-0074 | gm-a-0476 | REVIEW | fallback |
| bgm19-0129 | gm-a-0603 | REVIEW | fallback |
| bgm19-0146 | gm-a-0608 | REVIEW | fallback |
| bgm20-0010 | gm-a-0622 | REVIEW | fallback |
| bgm20-0118 | gm-a-0658 | REVIEW | fallback |
| bgm20-0163 | gm-a-0673 | REVIEW | fallback |
| bgm21-0057 | gm-a-0699 | REVIEW | python_semantic_category_mismatch |
| bgm24-0003 | gm-a-0741 | REVIEW | fallback |
| bgm24-0021 | gm-a-0747 | REVIEW | fallback |
| bgm24-0056 | gm-a-0758 | REVIEW | fallback |
| bwd05-0002 | world-0046 | REVIEW | fallback |
| bwd05-0013 | world-0057 | REVIEW | fallback |
| bwd07-0018 | world-0092 | REVIEW | python_semantic_category_mismatch |
| bwd08-0009 | world-0103 | REVIEW | fallback |
| bwd08-0024 | world-0118 | REVIEW | fallback |
| bwd09-0007 | world-0126 | REVIEW | fallback |
| bex03-0024 | ex-0072 | FAIL | semantic_final_verdict=fail |
| bex03-0030 | ex-0078 | FAIL | semantic_final_verdict=fail |
| bex04-0016 | ex-0094 | FAIL | semantic_final_verdict=fail |
| bex06-0011 | ex-0159 | FAIL | semantic_final_verdict=fail |
| bex07-0047 | ex-0235 | FAIL | semantic_final_verdict=fail |
| bex09-0064 | ex-0362 | FAIL | semantic_final_verdict=fail |
| bex10-0033 | ex-0401 | FAIL | semantic_final_verdict=fail |
| bex10-0037 | ex-0405 | FAIL | semantic_final_verdict=fail |
| bex10-0041 | ex-0409 | FAIL | semantic_final_verdict=fail |
| bex10-0060 | ex-0428 | FAIL | semantic_final_verdict=fail |
| bex12-0005 | ex-0523 | FAIL | semantic_final_verdict=fail |

## 結論

要確認: 隔離SFTと分類結果に不整合があります。
