# 監査レポート（classify_qa + verify_sft_outputs）

- 対象スナップショット: `snap-0004-20261117T115045Z`
- 総件数: 180 / PASS: 167 / REVIEW: 10 / FAIL: 3
- PASS率: 92.8%
- ID形式: 正常 / 重複: 0
- 隔離SFT形式検証エラー: 0
- 隔離SFT行数: 167（PASS ID 167 と一致: True）

## 検出理由（REVIEW/FAIL）

- `fallback`: 10件
- `semantic_final_verdict=fail`: 3件

## 問題のあるID

| 元ID | 監査ID | 判定 | 理由 |
|---|---|---|---|
| bex03-0022 | ex-0022 | REVIEW | fallback |
| bex03-0023 | ex-0023 | REVIEW | fallback |
| bex04-0007 | ex-0037 | REVIEW | fallback |
| bex04-0011 | ex-0041 | REVIEW | fallback |
| bex04-0017 | ex-0047 | REVIEW | fallback |
| bex04-0028 | ex-0058 | REVIEW | fallback |
| bgm04-0038 | gm-a-0038 | REVIEW | fallback |
| bgm05-0055 | gm-a-0067 | REVIEW | fallback |
| bgm06-0066 | gm-a-0096 | REVIEW | fallback |
| bwd04-0010 | world-0022 | REVIEW | fallback |
| bex03-0024 | ex-0024 | FAIL | semantic_final_verdict=fail |
| bex03-0030 | ex-0030 | FAIL | semantic_final_verdict=fail |
| bex04-0016 | ex-0046 | FAIL | semantic_final_verdict=fail |

## 結論

PASS品のみ隔離SFTに含まれ、形式検証エラー0。正式データへの統合は全Wave監査完了後。
