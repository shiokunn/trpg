# 監査レポート（classify_qa + verify_sft_outputs）

- 対象スナップショット: `snap-0007-20261117T120340Z`
- 総件数: 36 / PASS: 35 / REVIEW: 1 / FAIL: 0
- PASS率: 97.2%
- ID形式: 正常 / 重複: 0
- 隔離SFT形式検証エラー: 0
- 隔離SFT行数: 35（PASS ID 35 と一致: True）

## 検出理由（REVIEW/FAIL）

- `fallback`: 1件

## 問題のあるID

| 元ID | 監査ID | 判定 | 理由 |
|---|---|---|---|
| bgm11-0037 | gm-a-0013 | REVIEW | fallback |

## 結論

PASS品のみ隔離SFTに含まれ、形式検証エラー0。正式データへの統合は全Wave監査完了後。
