# 検証レポート（Phase 9 — 推論安定化 / 2026-11-16）

## 0. 環境

| 項目 | 値 |
|---|---|
| 端末 | FCNT M08（model=M08, device=fuji, brand=FCNT） |
| SoC | MediaTek MT6897（Dimensity 8350 系。指示書の環境に相当） |
| Android | 16（SDK 36） |
| RAM | `MemTotal 11,728,556 kB`（約11.2 GiB） / `MemAvailable 3,376,596 kB`（計測時点） |
| ABI | arm64-v8a |
| Thread Profile | A（ビルド時指定） |
| スタティック設定 | Debug でも `RelWithDebInfo`（`-O2 -g -DNDEBUG`） |
| モデル | `Kyalulu-E2B-JP-Rolepaly-beta-Q4_K_M.gguf`（3.2 GB, Q4_K_M, `nCtxTrain=131072`） |
| コンテキスト | **8192**（本 Phase で推奨値を採用）。旧設定は 16384 |
| APK | commit `2a2c51b` / CI run `43857282916` / v0.1.0 (code=1) |
| APK SHA-256 | `b31e1cc8021d1cc84efc27d87404e16e957200f699ba03c044fb4ba0825940e4` |
| 署名 | `CN=Android Debug, O=Android, C=US` |
| ADB シリアル | `emulator-5554`（ただし `ro.soc.model=MT6897` の実機） |

> 本ファイルは 2026-11-16 時点の結果に更新した。Phase 8（llama.cpp v0.9.0 統合）の
> 旧レポートは、端末・方法・結論が現在と一致しないため置き換えた。

## 1. ビルド / テスト / APK 導入

| 項目 | 結果 |
|---|---|
| C++ 構文検証 | `clang++ -std=c++17 -fsyntax-only -DTRPG_THREAD_PROFILE=0 ...` 成功 |
| `./gradlew -p core test` | 150件成功（ローカル、2026-11-16） |
| GitHub Actions | run `43857282916` / commit `2a2c51b` — **成功**（core 150件、app 11件、arm64-v8a APK） |
| APK 導入 | `adb install -r app-debug.apk` 成功 |
| 既存データ | `firstInstallTime=2026-11-12 07:17:30` は不変。セッション「霧灯台の残響 ／ カイル」は維持 |
| `lastUpdateTime` | `2026-11-16 20:35:53` |

**PASS**

## 2. モデル管理画面のメモリ推奨

- 起動直後は保存済みの `nCtx=16384` が表示され、警告が出た:
  - `推奨: 8192 トークン（端末RAM 11.2 GB / モデル 3.2 GB）`
  - `このモデルと端末RAMでは 16384 トークンはメモリ不足の可能性があります。推奨は 8192 トークンです。`
- 「推奨値（8192）を使う」→「設定を保存」で 8192 を保存。警告は消えた。
- 再読み込み後のモデル情報: `コンテキスト 8192 / 学習時 131072`。

**PASS**

## 3. スレッド / バックエンド / メモリ

読み込み直後のネイティブ・デバッグ情報:

```
Threadpool attached: profile=A threads=4 strict_cpu=true cpus=7,4,5,6 allowed=0,1,2,3,4,5,6,7
threads requested=4 actual=4
llama_kv_cache: size = 96.00 MiB (8192 cells, 12 layers, 1/1 seqs), K (f16): 48.00 MiB, V (f16): 48.00 MiB
```

- デバッグ画面で `Threads 4 / 4`、`CPU affinity ... allowed=0,1,2,3,4,5,6,7`、
  `許可CPU（プロセス） 0-7` を確認。
- 旧設定（16384）では `VmSwap` が約 2.04 GB に達していたが、8192 では読み込み直後
  `VmRSS 4,964,904 kB` / `VmSwap 24,904 kB` に低下。KV も 96 MiB（16K の半分）。
- デバッグ画面のメモリカードで `VmRSS 2,124,184 kB` / `VmSwap 179,888 kB` /
  `MemAvailable 3,952,080 kB` / `SwapFree 4,659,088 kB` を確認（新規追加項目）。

**PASS**（メモリ推奨の効果を実測で確認）

## 4. 生成・KV キャッシュ

初回（キャッシュなし）:

```
generate start: prompt_tokens=2314 budget=1088 cache_ms=0 reused=0
[KvCache] prepare generation=1 status=miss reused=0 recompute=2314 cached=0 reason=no_cache
```

修復生成（同一ペアの再利用）:

```
generate start: prompt_tokens=2999 budget=1088 cache_ms=0 reused=2938
[KvCache] finish generation=2 valid=1 cached=3623 reused=2938 computed=61 reason=prefix_reuse
generate: prompt_tokens=2999 prompt_ms=841 generated_tokens=624 gen_ms=69188 tok_per_sec=9.02 ttft_ms=843 cancelled=0 failed=0
[TokenCounter] finish generation=2 generated=624 maxTokens=1088 budget=1088 finishReason=eos limitReached=0
```

- 別の試行では `reused=2313 recompute=1` の再利用も確認。
- 生成で得られた JSON は `difficulty` が契約値（4/6/8/10/12/14）外で、
  `LOCATION_CHANGE` も「既にその場所にいます」だった。アプリ側の検証が働き、
  ターンは保存されなかった。**これは既知のコンテンツ/契約順守の問題であり、
  KV キャッシュや①の変更とは無関係**。
- タイムアウト（5分）は発火しなかった。

**PASS**（推論継続・KV 再利用）、**既知の残課題**（JSON 契約違反）

## 5. キャンセル / エラー後復帰

```
graph_compute: ggml_backend_sched_graph_compute_async failed with error 1
[KvCache] invalidate reason=decode_error cached_before=2555
[KvCache] finish generation=4 valid=0 cached=0 reused=2555 computed=0 reason=decode_error
generate: prompt_tokens=3138 prompt_ms=45585 generated_tokens=0 gen_ms=0 tok_per_sec=0.00 ttft_ms=-1 cancelled=1 failed=0
[TokenCounter] finish generation=4 generated=0 maxTokens=1088 budget=1088 finishReason=cancelled limitReached=0
```

- UI に「生成をキャンセルしました。このターンは保存されていません。」を表示。
- クラッシュ・ANR なし。ネイティブの KV を無効化し、リソース状態が整合。
- キャンセル後の再生成も開始できた（KV は miss から再構築）。

**PASS**

## 6. 未検証 / 残課題

実機で意図的な再現を行っていない項目:

| 項目 | 状況 |
|---|---|
| 5分間の無進捗で watchdog が発火するか | 単体テストでは時間経過を検証済み。実機で5分待つ試験は未実施 |
| `errorKind=alloc/context_alloc` の表示 | コード追加・パーサテスト済み。実機で OOM を強制していない |
| 背景移行後の cpuset 縮小（0-2） | `keepScreenOn` と cpuset 交差で緩和。生成中にホームへ移す試験は未実施 |
| モデル切り替え後の KV 無効化 | 前回セッションで一部確認済みだが、本 APK では未再検証 |
| JSON 契約違反（difficulty）の是正 | ②〜⑥の③（選択肢/プロンプト改善）の対象 |
| Vulkan バックエンド | 未ビルド・未検証（既定は CPU） |

## 7. 未コミット変更の扱い

本 Phase では、以下の未コミット/未追跡ファイルに触れていない:

- `.github/workflows/android.yml`（GBNF 検証ステップの追加）
- `docs/LLAMA_V090_INVESTIGATION.md`, `docs/LLM_*.md`, `docs/PROMPT_EVAL_INVESTIGATION.md`
- `scripts/gbnf-check/`, `scripts/validate-gbnf.sh`

これらは今後のコミット判断を保留するため、そのまま保持する。
