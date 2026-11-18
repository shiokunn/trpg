# 監査レポート（classify_qa + verify_sft_outputs）

- 対象スナップショット: `snap-0005-20261117T115054Z`
- 総件数: 230 / PASS: 223 / REVIEW: 6 / FAIL: 1
- PASS率: 97.0%
- ID形式: 正常 / 重複: 0
- 隔離SFT形式検証エラー: 0
- 隔離SFT行数: 223（PASS ID 223 と一致: True）

## 検出理由（REVIEW/FAIL）

- `fallback`: 6件
- `semantic_final_verdict=fail`: 1件

## 問題のあるID

| 元ID | 監査ID | 判定 | 理由 |
|---|---|---|---|
| bex05-0036 | ex-0036 | REVIEW | fallback |
| bex06-0013 | ex-0053 | REVIEW | fallback |
| bgm08-0010 | gm-a-0034 | REVIEW | fallback |
| bgm08-0011 | gm-a-0035 | REVIEW | fallback |
| bgm10-0010 | gm-a-0094 | REVIEW | fallback |
| bgm10-0030 | gm-a-0102 | REVIEW | fallback |
| bex06-0011 | ex-0051 | FAIL | semantic_final_verdict=fail |

## 結論

PASS品のみ隔離SFTに含まれ、形式検証エラー0。正式データへの統合は全Wave監査完了後。
