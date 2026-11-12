# DEBUG_MODE_PROGRESS — 一時的なデバッグモード（推論計測）

**Status: IN_PROGRESS**
**Phase: C（CI 検証待ち）**
**Updated: 2026-11-12**

---

## 現在の状態

ユーザー承認済み。S1〜S7（実装）完了、S8（ローカルで core テスト）完了。
次は S9（commit / push → CI 検証）。実機検証（S10）は CI 成功後。

---

## 完了した手順

| # | 内容 | 結果 |
|---|---|---|
| R1 | 既存の PLAN.md / PROGRESS.md を確認 | 別ゴール（Phase 8）。ユーザー指示により専用ファイルへ分離 |
| R2 | プロジェクト構成・命名・テスト配置の調査 | 完了（§調査メモ） |
| R3 | 推論エンジン / JNI / ネイティブの接続点の特定 | 完了 |
| R4 | 設計・ファイル一覧・手順・リスクの作成 | 完了（DEBUG_MODE_PLAN.md） |
| S1 | core: DebugConfig / DebugMetrics / DebugParsers / DebugSession / DebugJson | 完了 |
| S2 | core: 単体テスト（Config / Session / JSON / parser） | 完了・26 件パス |
| S3 | native: debug_bridge.h / debug_jni.cpp / metrics 出力 / CMake | 完了（CI 未検証） |
| S4 | app: DebugProcessInfo / DebugRecorder / DebugViewState | 完了（CI 未検証） |
| S5 | app: DebugViewModel / DebugExporter | 完了（CI 未検証） |
| S6 | app: DebugScreen（状態・処理時間・実行設定・メモリ・ログ・出力） | 完了（CI 未検証） |
| S7 | 接続: 設定トグル / ナビ / エンジンフック / Manifest + FileProvider | 完了（CI 未検証） |
| S8 | ローカル検証: core を kotlinc + JUnit で直接コンパイル・実行 | 26 tests OK |

---

## 実装内容（ファイル）

### core（純 Kotlin・JUnit4）

- `core/src/main/kotlin/io/github/shiokunn/trpg/core/debug/DebugConfig.kt`
  - インメモリ ON/OFF（`StateFlow<Boolean>`）。再起動で OFF。推論・KV には触れない。
- `DebugMetrics.kt`
  - `NativeMetrics` / `RuntimeInfo` / `DebugSettingsSnapshot` / `MemorySample` /
    `DebugLogEntry` / `TokenRateSample` / `DebugOutcome` / `DebugReport`。
  - 「取得不可」は `null`。プロンプト・生成本文のフィールドは存在しない。
- `DebugParsers.kt`
  - 結果 JSON の `metrics` と `nativeRuntimeInfo()` の防御的パース。
- `DebugSession.kt`
  - フェーズ（PREPARING/DECODE/COMPLETED/CANCELLED/ERROR）、トークン数、
    16 トークン窓の速度、ログ上限 200 件、メモリサンプル上限 300 件、レポート集計。
  - 全エントリポイント `@Synchronized`。
- `DebugJson.kt`
  - prettyPrint の JSON エンコード / デコード。

### app

- `app/src/main/kotlin/io/github/shiokunn/trpg/debug/DebugViewState.kt`
- `DebugProcessInfo.kt` — `/proc/self/status` の VmRSS / VmSwap（失敗時 null）
- `DebugRecorder.kt` — OFF なら即 null。ON のときだけセッション・2 秒間隔の
  メモリサンプリング・状態通知（250ms スロットル）を開始。
- `DebugViewModel.kt` — 画面状態 + JSON 保存 / 共有
- `DebugExporter.kt` — `getExternalFilesDir("debug")` に保存、FileProvider で共有
- `DebugScreen.kt` — 状態 / 処理時間 / llama.cpp 実行設定（実値と要求値）/
  メモリ / ログ / エクスポート

### 既存ファイルへの接続点（すべて `DEBUG MODE (temporary)` コメント付き）

- `ai/engine/LlamaCppEngine.kt` — `generate()` の 4 箇所（begin / onToken / attachResult / finish）
- `ai/engine/LlamaCppNative.kt` — `nativeRuntimeInfo()` 宣言
- `ui/settings/SettingsScreen.kt` — 開発者向けセクションと `onOpenDebug`
- `ui/navigation/TrpgNavHost.kt` — `Routes.DEBUG` と `DebugScreen`
- `app/src/main/AndroidManifest.xml` — FileProvider
- `app/src/main/res/xml/debug_file_paths.xml` — 共有パス
- `cpp/CMakeLists.txt` — `debug_jni.cpp` を追加
- `cpp/llama_jni.cpp` — 計測値の `metrics` 出力と実行設定の記録（挙動変更なし）

### ネイティブ

- `cpp/debug_bridge.h` / `cpp/debug_jni.cpp`
  - `TrpgDebugState`（実値・要求値・threadpool・flash attn・KV 型・GPU layers）
  - `trpg_debug_runtime_json()`（`Cpus_allowed_list` を /proc から取得）
  - `nativeRuntimeInfo()` JNI
- `llama_jni.cpp` の結果 JSON に `metrics` を追加
  - `applyMs` / `tokenizeMs` / `clearMs` / `promptMs` / `genMs` / `ttftMs` /
    `promptTokens` / `generatedTokens` / `budget` / `threads` / `nBatch`
  - いずれも既に計算済みの値の転記で、推論経路に処理を追加していない。

---

## 調査メモ（事実）

- モジュール: `:core`（純 Kotlin, JUnit4, kotlinx.serialization）/ `:app`（Compose, Room, DataStore）
- ナビゲーション: `app/src/main/kotlin/io/github/shiokunn/trpg/ui/navigation/TrpgNavHost.kt`
- 設定画面: `app/src/main/kotlin/io/github/shiokunn/trpg/ui/settings/SettingsScreen.kt`
- エンジン: `app/src/main/kotlin/io/github/shiokunn/trpg/ai/engine/LlamaCppEngine.kt`
- JNI 宣言: `app/src/main/kotlin/io/github/shiokunn/trpg/ai/engine/LlamaCppNative.kt`
- ネイティブ: `app/src/main/cpp/llama_jni.cpp`
- 既存の計測: `prompt_ms` / `gen_ms` / `ttft_ms` などは計算済みだが Logcat のみだった
- CI: `.github/workflows/android.yml`（`:core:test` → `:app:testDebugUnitTest` → `assembleDebug` → 署名検証）
- ローカルに Android SDK はない（`:app` のコンパイルは CI のみで検証可能）

---

## 検証状況

| 項目 | 状態 |
|---|---|
| core 単体テスト（26 件） | ✅ ローカルで実行し全パス |
| `:app` コンパイル / `:app:testDebugUnitTest` | ⏳ CI 待ち |
| `assembleDebug` / 署名検証 | ⏳ CI 待ち |
| 実機（arrows Alpha）での動作確認 | ⏳ 未実施 |

ローカル実行方法（Android SDK 不要）:

```
kotlinc -Xplugin=<serialization-plugin.jar> -cp <serialization/core/coroutines/junit> \
  core/src/main/kotlin/io/github/shiokunn/trpg/core/debug/*.kt \
  core/src/test/kotlin/io/github/shiokunn/trpg/core/debug/*.kt -d out
java -cp out:<...> org.junit.runner.JUnitCore <テストクラス>
```

---

## 既知の問題・制約

- 長時間推論で OS がアプリを凍結する既知の問題がある（本ゴールの対象外。修正しない）
- ライブ表示の段階（PREPARING/DECODE）は粗い。正確な内訳は完了時の metrics で表示する
- `piece = 1 token` を前提に 16 token サンプルを算出する（実装上は一致）
- メモリは 2 秒間隔のサンプル。推論中のピークを厳密に捉えるものではない
- Flash Attention は「設定値」のみ。実行時の有効/無効を返す API は v0.9.0 にない
- `nativeRuntimeInfo()` はモデル読み込み後にのみ意味のある値を返す

---

## 次のアクション

1. commit / push → CI（`:core:test` / `:app:testDebugUnitTest` / `assembleDebug` / 署名検証）
2. CI が赤なら修正して再 push
3. 実機で ON → 推論 → 画面確認 → JSON 保存 → adb pull で中身確認
4. 結果を本ファイルに追記し、最終報告
