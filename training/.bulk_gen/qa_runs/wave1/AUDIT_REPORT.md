# 監査レポート（classify_qa + verify_sft_outputs）

- 対象スナップショット: `snap-0003-20261117T111330Z`
- 総件数: 96 / PASS: 88 / REVIEW: 6 / FAIL: 2
- PASS率: 91.7%
- ID形式: 正常 / 重複: 0
- 隔離SFT形式検証エラー: 0
- 隔離SFT行数: 88（PASS ID 88 と一致: True）

## 検出理由（REVIEW/FAIL）

- `fallback`: 6件
- `semantic_final_verdict=fail`: 2件

## 問題のあるID

| 元ID | 監査ID | 判定 | 理由 |
|---|---|---|---|
| bex01-0022 | ex-0022 | REVIEW | fallback |
| bex01-0023 | ex-0023 | REVIEW | fallback |
| bgm01-0010 | gm-a-0004 | REVIEW | fallback |
| bgm01-0019 | gm-a-0007 | REVIEW | fallback |
| bgm01-0030 | gm-a-0012 | REVIEW | fallback |
| bwd02-0003 | world-0013 | REVIEW | fallback |
| bgm01-0012 | gm-a-0006 | FAIL | semantic_final_verdict=fail |
| bgm01-0021 | gm-a-0009 | FAIL | semantic_final_verdict=fail |

## 結論

PASS品のみ隔離SFTに含まれ、形式検証エラー0。正式データへの統合は全Wave監査完了後。
