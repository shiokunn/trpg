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

## 指定ツールによる最終監査（Wave 1 以降の共通手順）

`quality_system.py` による機械監査に加え、指定の2ツールを必备します。

```bash
# 0. 完成バッチを封印 → Wave単位でスナップショット
python3 pipeline/bulk_snapshot.py seal
python3 pipeline/bulk_snapshot.py take --note wave2 --only <wave2のバッチ名...>

# 1. 派生監査ルートを作成（IDを classify_qa の ID_RE に適合させるのみ。内容は不変）
python3 pipeline/audit_make_derived.py --snapshot <snap-id> --run-dir ../qa_runs/wave2

# 2. Python品質スコア＋Semantic判定の準備
python3 ../../tools/quality_system.py score --root <run>/raw --out-dir <run>/python
python3 ../../tools/semantic_llm_engine.py prepare --root <run>/raw --out <run>/semantic/packets
#    判定者サブエージェントが packets_part*.jsonl をレビューし judgments_part*.jsonl を作成
#    （根拠のないラベル・pass強制のための空配列は禁止）

# 3. 指定ツール①: classify_qa.py（PASS / REVIEW / FAIL、UNKNOWN_ID も厳しく照合）
python3 ../../tools/semantic_llm_engine.py ingest \
    --packets <run>/semantic/packets/packets.jsonl \
    --judgments <run>/semantic/judgments.jsonl --root <run>/raw --out <run>/semantic
python3 ../../tools/classify_qa.py \
    --scores <run>/python/score_cache.jsonl \
    --semantic <run>/semantic/semantic_final.jsonl \
    --manifest <run>/semantic/manifest.jsonl \
    --root <run>/raw --allow-root --out-dir <run>/classified

# 4. PASS_only の隔離SFTを生成し、指定ツール②: verify_sft_outputs.py で形式検証
python3 pipeline/audit_build_isolated_sft.py --raw <run>/raw \
    --pass-ids <run>/classified/pass.jsonl --out-dir <run>/sft_isolated
python3 ../../tools/verify_sft_outputs.py --root <run>/sft_isolated \
    > <run>/verify_sft_outputs.json

# 5. 集計（件数・PASS率・問題ID・検出理由）
python3 pipeline/audit_report.py --run-dir <run>
```

- 監査ディレクトリ名は `qa_runs/` を使ってください（`classify_qa.py` は
  パスに `audit` / `test` / `judgment` 等のトークンがあると拒否します）。
- `classify_qa.py` は ID 形式 `gm-a-0001` / `world-0001` / `ex-0001` のみ受理します。
  `audit_make_derived.py` が元IDを変換し、`id_map.json` に全対応を記録します。
- `verify_sft_outputs.py` は隔離ディレクトリの2ファイルのみを検証します。
  正式SFTは一切読み書きしません。
- 監査結果は `qa_runs/<wave>/AUDIT_REPORT.json` / `.md` に記録します。

## 正式データへの統合（まだ実行しない）

生成と全スナップショットの監査が完了した後にのみ、
既存の `training/tools/build_sft_outputs.py --out-dir <隔離先> --accepted-ids <PASS IDs>`
を使って別ディレクトリへ成果物を作ります。既存の
`sft_synthetic_json.jsonl` / `sft_synthetic_extraction.jsonl` は上書きしません。

## 注記

- 10,000 件は多数の波（ウェーブ）に分けた生成が必要です。1セッションで
  高品質に到達できない場合は、実測件数と未達を正直に報告します。
- 品質点は機械ヒューリスティックであり、面白さ・自然さの最終判断は人手レビューが必要です。
