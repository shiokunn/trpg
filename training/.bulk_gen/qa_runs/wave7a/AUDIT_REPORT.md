# 監査レポート（classify_qa + verify_sft_outputs）

- 対象スナップショット: `snap-0009-20261117T121222Z`
- 総件数: 67 / PASS: 48 / REVIEW: 19 / FAIL: 0
- PASS率: 71.6%
- ID形式: 正常 / 重複: 0
- 隔離SFT形式検証エラー: 0
- 隔離SFT行数: 48（PASS ID 48 と一致: True）

## 検出理由（REVIEW/FAIL）

- `unjudged_or_missing_semantic`: 17件
- `fallback`: 2件

## 問題のあるID

| 元ID | 監査ID | 判定 | 理由 |
|---|---|---|---|
| bgm14-0020 | gm-a-0008 | REVIEW | fallback |
| bgm14-0030 | gm-a-0012 | REVIEW | fallback |
| bwd08-0009 | world-0009 | REVIEW | unjudged_or_missing_semantic |
| bwd08-0010 | world-0010 | REVIEW | unjudged_or_missing_semantic |
| bwd08-0011 | world-0011 | REVIEW | unjudged_or_missing_semantic |
| bwd08-0012 | world-0012 | REVIEW | unjudged_or_missing_semantic |
| bwd08-0013 | world-0013 | REVIEW | unjudged_or_missing_semantic |
| bwd08-0014 | world-0014 | REVIEW | unjudged_or_missing_semantic |
| bwd08-0015 | world-0015 | REVIEW | unjudged_or_missing_semantic |
| bwd08-0016 | world-0016 | REVIEW | unjudged_or_missing_semantic |
| bwd08-0017 | world-0017 | REVIEW | unjudged_or_missing_semantic |
| bwd08-0018 | world-0018 | REVIEW | unjudged_or_missing_semantic |
| bwd08-0019 | world-0019 | REVIEW | unjudged_or_missing_semantic |
| bwd08-0020 | world-0020 | REVIEW | unjudged_or_missing_semantic |
| bwd08-0021 | world-0021 | REVIEW | unjudged_or_missing_semantic |
| bwd08-0022 | world-0022 | REVIEW | unjudged_or_missing_semantic |
| bwd08-0023 | world-0023 | REVIEW | unjudged_or_missing_semantic |
| bwd08-0024 | world-0024 | REVIEW | unjudged_or_missing_semantic |
| bwd08-0025 | world-0025 | REVIEW | unjudged_or_missing_semantic |

## 結論

PASS品のみ隔離SFTに含まれ、形式検証エラー0。正式データへの統合は全Wave監査完了後。
