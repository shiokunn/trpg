# バルク生成ワークスペース（隔離・一時ディレクトリ）

TRPG 学習用文章を約 10,000 件规模で生成するための**専用一時領域**です。
既存の学習データ（`training/raw`, `processed`, `splits`, `metadata`,
`sft_synthetic_*.jsonl`）は読み取りすらともなわず、**変更しません**。
コミット・プッシュも行いません。

## レイアウト

```
training/.bulk_gen/
├── README.md / config.json
├── instructions/                 サブエージェント向け生成ルール
├── inbox/                        書き込み中／完了した生成物（監査は直接読まない）
│   ├── seeds/trpg_gm/*.seed.jsonl
│   ├── trpg_gm/*.jsonl
│   ├── world_json/*.jsonl
│   └── extraction/*.jsonl
├── pipeline/                     新規オーケストレーション スクリプト
│   ├── bulk_common.py
│   ├── bulk_expand.py           コンパクト GM シード → raw（inbox へ）
│   ├── bulk_snapshot.py         seal / take（不変スナップショット作成）
│   ├── bulk_audit.py            既存監査ツールをスナップショットに適用＋集計
│   └── bulk_status.py           進捗
├── state/                        封印イベント等の追記型ログ
├── snapshots/snap-NNNN-<ts>/     不変。監査の唯一の入力
│   ├── MANIFEST.json
│   └── raw/<category>/*.jsonl
└── audit/<snap>/                 監査結果（既存ツール出力＋集計＋レポート）
    ├── quality_scores.json/.csv  （既存 quality_system.py の出力）
    ├── ledger.jsonl / score_cache.jsonl
    ├── AGGREGATE.json / REPORT.md
    └── audit_run.json
```

## 書き込みと監査の分離（重要）

1. サブエージェントは `inbox/` に**1回の write** でバッチを書く。
2. `bulk_snapshot.py seal` が検証し、`<file>.done.json`（sha256 と件数）を付与。
   検証失败・未完了ファイルは封印しない。
3. `bulk_snapshot.py take` が封印済みかつ**ハッシュが一致するファイルだけ**を
   ステージング経由で `snapshots/` へ原子的にコピー。書き込み中にハッシュが
   変わったファイルは除外（監査対象にならない）。
4. `bulk_audit.py run --snapshot <id>` が既存の
   `training/tools/quality_system.py score --root <snap>/raw --out-dir <audit>/` を
   呼ぶ。**監査は snapshots 配下のみを読み、inbox は一切読まない。**
5. `bulk_audit.py trends` が全スナップショットの監査結果を時系列集計。

生成が途中でも、完了済みバッチだけを封印→スナップショット化すれば、
途中経過の品質監査が何度でも可能です。

## 典型的な操作

```bash
cd training/.bulk_gen/pipeline
python3 bulk_expand.py --seeds ../inbox/seeds/trpg_gm/b01.seed.jsonl \
    --prefix bgm01- --out bgm01.jsonl          # 各サブエージェントの後に実行
python3 bulk_snapshot.py seal                  # 完了バッチを封印
python3 bulk_snapshot.py take                  # 不変スナップショット作成
python3 bulk_audit.py run --snapshot latest    # 既存監査ツール＋集計
python3 bulk_audit.py trends                   # 傾向の推移
python3 bulk_status.py                         # 目標 10,000 件までの進捗
```

## 正式データへの統合（まだ実行しない）

生成と全スナップショットの監査が完了した後にのみ、
既存の `training/tools/build_sft_outputs.py --out-dir <隔離先> --accepted-ids <PASS IDs>`
を使って別ディレクトリへ成果物を作ります。既存の
`sft_synthetic_json.jsonl` / `sft_synthetic_extraction.jsonl` は上書きしません。

## 注記

- 10,000 件は多数の波（ウェーブ）に分けた生成が必要です。1セッションで
  高品質に到達できない場合は、実測件数と未達を正直に報告します。
- 品質点は機械ヒューリスティックであり、面白さ・自然さの最終判断は人手レビューが必要です。
