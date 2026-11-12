# DEBUG_MODE_PROGRESS — 一時的なデバッグモード（推論計測）

**Status: COMPLETED**
**Phase: D（実機検証まで完了）**
**Updated: 2026-11-12**

---

## 現在の状態

S1〜S11 完了。CI green、実機（arrows Alpha）で計測・表示・JSON 出力を確認済み。
**推論エンジンの性能改善は本ゴールの対象外**であり、行っていない。

---

## 完了した手順

| # | 内容 | 結果 |
|---|---|---|
| R1 | 既存 PLAN / PROGRESS の確認 | 別ゴールのため専用ファイルへ分離 |
| R2-R4 | 調査・設計・リスク整理 | `DEBUG_MODE_PLAN.md` |
| S1 | core: DebugConfig / DebugMetrics / DebugParsers / DebugSession / DebugJson | 完了 |
| S2 | core: 単体テスト（26 件） | 完了・全パス |
| S3 | native: debug_bridge.h / debug_jni.cpp / metrics 出力 / CMake | 完了 |
| S4 | app: DebugProcessInfo / DebugRecorder / DebugViewState | 完了 |
| S5 | app: DebugViewModel / DebugExporter | 完了 |
| S6 | app: DebugScreen | 完了 |
| S7 | 接続: 設定トグル / ナビ / エンジンフック / Manifest + FileProvider | 完了 |
| S8 | ローカル core テスト（kotlinc + JUnit） | 26 tests OK |
| S9 | CI（commit `165477a`） | ✅ `:core:test` / `:app:testDebugUnitTest` / `assembleDebug` / 署名検証 |
| S10 | 実機検証（下記） | ✅ 完了 |
| S11 | 報告・ドキュメント更新 | 完了 |

---

## 実機検証（arrows Alpha / Android 16）

実行: 世界と主人公の AI 生成（`qwen3-4b.Q4_K_M`, prompt 733 tokens, budget 1000）。
約 13 分後に「生成を中止」でキャンセルし、その計測結果を確認。

### 画面表示（確認済み）

| 項目 | 表示 |
|---|---|
| 状態 | キャンセル |
| 開始時刻 / 経過時間 | 18:42:18 / 807123 ms |
| 入力トークン数 | 733 |
| 生成済みトークン数 | 19（ライブ） |
| 生成速度 | 0.41 tok/s |
| Template 構築 | 14 ms |
| Tokenize | 44 ms |
| KV Cache 準備/クリア | 0 ms |
| Prompt Evaluation | **760333 ms** |
| Decode | 46681 ms |
| TTFT | 760351 ms |
| 推論全体 | 807123 ms |
| Prompt / Generated | 733 / 21 |
| Budget | 1000 |
| 16トークンごとの速度 | 0.48 tok/s / 33606 ms |
| n_ctx（実値 / 要求） | 8192 / 8192 |
| n_ctx_train | 40960 |
| n_batch / n_ubatch | 1024 / 1024, 512 / 512 |
| n_seq_max | 1 |
| threads（実値 / 要求） | 4 / 4, threads_batch 4 |
| CPU affinity | `profile=A threads=4 strict_cpu=true cpus=7,4,5,6` |
| 許可CPU（プロセス） | 0-7 |
| Flash Attention（設定） | auto |
| KV Cache 型 | f16 / f16 |
| GPU layers / Backend / offload | 0 / CPU / なし |
| VmRSS（開始時 → 最新） | 4,844,976 kB → 4,068,108 kB |
| VmSwap（開始時 → 最新） | 46,448 kB → 79,480 kB |
| メモリサンプル数 | 300（上限） |
| ログ | 推論開始 / Decode開始 / 推論キャンセル（各時刻付き） |

### JSON エクスポート（確認済み）

- 保存先: `/storage/emulated/0/Android/data/io.github.shiokunn.trpg/files/debug/trpg-debug-20261112-185545.json`（37.8 KB）
- トップキー: `exportedAtMs, startedAtMs, endedAtMs, outcome, error, runtime, settings, metrics, totalMs, generatedTokens, tokensPerSecond, tokenRates, memory, logs`
- `metrics`: `applyMs=14, tokenizeMs=44, clearMs=0, promptMs=760333, genMs=46681, ttftMs=760351, promptTokens=733, generatedTokens=21, budget=1000, threads=4, nBatch=1024`
- `runtime`: llama 0.9.0 / cd959ba、threadpool 文字列、allowedCpus、KV 型、backend 等を実際の値で記録
- **プロンプト本文・生成本文・メッセージ内容は含まれない**（`"text"`, `"prompt"`, `"content"`, `"messages"`, 入力キーワードのいずれも出現しないことを確認）
- メモリサンプル 300 件、ログ 3 件、tokenRates 1 件

### ON/OFF（確認済み）

- 設定 →「開発者向け（一時）」のスイッチで ON → 文言が「ON — …」に変化し「デバッグ画面を開く」が出現
- OFF → 「OFF — 通常動作」に変化し、デバッグ画面への導線が消える
- アプリ再起動後は OFF（インメモリ設定）

### 未確認（正直な範囲）

- `COMPLETED` で終わる実機実行は未実施（今回の実機実行はキャンセル）。
  完了時の同じ経路は core 単体テストで検証済み。
- 実機での「共有」ボタンからの共有先アプリ起動は未実施（保存までは確認）。
- 推論中のライブ更新（PREPARING → DECODE）は、プレフィル中は PREPARING のまま
  であることのみ確認。DECODE 中のライブ数値更新はログの「Decode開始」で確認。

---

## 計測された事実（性能改善は未実施）

- 733 トークンのプレフィルに 760.3 秒（約 0.96 tok/s）
- デコードは 21 トークン / 46.7 秒（約 0.45 tok/s、16 トークン窓で 0.48 tok/s）
- 実行時は `n_batch=1024`, `n_ubatch=512`, `n_ctx=8192`, threads=4, KV f16, flash attn auto
- プロセスの許可 CPU は `0-7`、スレッドプールは `7,4,5,6`
- VmRSS は約 4.0〜4.8 GB、VmSwap は 46〜79 MB

※ 原因分析・改善は Phase 8 の別ゴール。本ファイルは計測結果の記録のみ。

---

## 変更したファイル

### 新規（削除だけで消せる範囲）

- `core/src/main/kotlin/io/github/shiokunn/trpg/core/debug/`（5 ファイル）
- `core/src/test/kotlin/io/github/shiokunn/trpg/core/debug/`（3 ファイル）
- `app/src/main/kotlin/io/github/shiokunn/trpg/debug/`（6 ファイル）
- `app/src/main/cpp/debug_bridge.h`, `app/src/main/cpp/debug_jni.cpp`
- `app/src/main/res/xml/debug_file_paths.xml`

### 既存ファイルへの接続点（`DEBUG MODE (temporary)` コメント付き）

- `ai/engine/LlamaCppEngine.kt` — begin / onToken / attachResult / finish の 4 箇所
- `ai/engine/LlamaCppNative.kt` — `nativeRuntimeInfo()` 宣言
- `ui/settings/SettingsScreen.kt` — 開発者向けセクション + `onOpenDebug`
- `ui/navigation/TrpgNavHost.kt` — `Routes.DEBUG` + `DebugScreen`
- `app/src/main/AndroidManifest.xml` — FileProvider
- `app/src/main/cpp/CMakeLists.txt` — `debug_jni.cpp`
- `app/src/main/cpp/llama_jni.cpp` — `metrics` 出力、実行設定の記録

### 削除手順

1. 上記「新規」を削除
2. `DEBUG MODE (temporary)` で検索し、各ブロックを削除
3. `SettingsScreen` の `onOpenDebug` 引数と `DebugConfig` import を削除
4. `LlamaCppEngine` の `DebugRecorder` import と 4 箇所を削除
5. `llama_jni.cpp` の `#include "debug_bridge.h"` と該当ブロックを削除

---

## 既知の制約

- ライブの「生成済みトークン数」は Kotlin コールバックで数えるため、
  キャンセル直後はネイティブ値より最大 2 ほど少ないことがある（レポートはネイティブ値を使用）
- メモリは 2 秒間隔のサンプル（上限 300 件 ≈ 10 分）。それ以降は古いものから捨てる
- Flash Attention は設定値のみ（実行時の有効/無効を返す API が v0.9.0 にない）
- `nativeRuntimeInfo()` はモデル読み込み後のみ意味のある値。未ロード時は「取得不可」
- 長時間推論で OS がアプリを凍結する既知の問題は未修正（本ゴールの対象外）

---

## 次のアクション

なし（本ゴール完了）。性能原因の追及は Phase 8 の別ゴールで扱う。
