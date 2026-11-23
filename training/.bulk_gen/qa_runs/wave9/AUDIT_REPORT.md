# 監査レポート（classify_qa + verify_sft_outputs）

- 対象スナップショット: `snap-0012-20261118T130810Z`
- 総件数: 333 / PASS: 310 / REVIEW: 23 / FAIL: 0
- PASS率: 93.1%
- ID形式: 正常 / 重複: 0
- 隔離SFT形式検証エラー: 0
- 隔離SFT行数: 310（PASS ID 310 と一致: True）

## 検出理由（REVIEW/FAIL）

- `fallback`: 21件
- `python_semantic_category_mismatch`: 2件

## 問題のあるID

| 元ID | 監査ID | 判定 | 理由 |
|---|---|---|---|
| bgm17-0055 | gm-a-0019 | REVIEW | fallback |
| bgm17-0075 | gm-a-0027 | REVIEW | fallback |
| bgm17-0084 | gm-a-0030 | REVIEW | python_semantic_category_mismatch |
| bgm22-0011 | gm-a-0059 | REVIEW | fallback |
| bgm22-0048 | gm-a-0072 | REVIEW | fallback |
| bgm23-0001 | gm-a-0109 | REVIEW | fallback |
| bgm23-0002 | gm-a-0110 | REVIEW | fallback |
| bgm23-0039 | gm-a-0123 | REVIEW | fallback |
| bgm23-0047 | gm-a-0125 | REVIEW | fallback |
| bgm23-0164 | gm-a-0164 | REVIEW | fallback |
| bgm25-0002 | gm-a-0170 | REVIEW | fallback |
| bgm25-0037 | gm-a-0181 | REVIEW | fallback |
| bgm25-0055 | gm-a-0187 | REVIEW | fallback |
| bwd11-0032 | world-0032 | REVIEW | fallback |
| bwd12-0007 | world-0042 | REVIEW | fallback |
| bwd12-0009 | world-0044 | REVIEW | fallback |
| bwd12-0013 | world-0048 | REVIEW | fallback |
| bwd12-0014 | world-0049 | REVIEW | fallback |
| bwd12-0016 | world-0051 | REVIEW | fallback |
| bwd12-0026 | world-0061 | REVIEW | fallback |
| bwd12-0027 | world-0062 | REVIEW | fallback |
| bwd13-0001 | world-0071 | REVIEW | python_semantic_category_mismatch |
| bwd13-0004 | world-0074 | REVIEW | fallback |

## 結論

PASS品のみ隔離SFTに含まれ、形式検証エラー0。正式データへの統合は全Wave監査完了後。
