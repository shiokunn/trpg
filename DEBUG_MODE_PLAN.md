# DEBUG_MODE_PLAN — 一時的なデバッグモード（推論計測）

**Status: COMPLETED**
**Created: 2026-11-12**
**Goal owner: 一時的な調査用機能（恒久機能ではない）**
**Related: `docs/LLAMA_V090_INVESTIGATION.md`（原因調査。本計画では性能改善は行わない）**

---

## 1. ゴール

TRPG Android アプリに、ローカル LLM 推論の「どこに時間がかかっているか」を数値で確認できる
**一時的な開発者向けデバッグモード**を追加する。

- 推論の各段階（Template / Tokenize / KV Clear / Prompt Evaluation / Decode）を個別に計測
- llama.cpp の実行時設定（n_ctx / n_batch / n_ubatch / threads / affinity / KV 型）を実値で表示
- VmRSS / VmSwap を推論前・中・後に記録
- 時系列ログと JSON エクスポート
- **デバッグ OFF 時は通常動作に完全に戻る**
- 削除時に「専用ファイル群 + 数箇所の接続」を消すだけで済む構造

**今回やらないこと（明示）**: 推論速度の改善、KV Cache 方針の変更、threadpool / affinity の変更、
プロンプト構築・サンプリング・モデルロードの変更。

---

## 2. 現状（調査結果）

| 項目 | 現状 |
|---|---|
| UI | Compose + Navigation。`TrpgNavHost` に設定画面ルートあり |
| 設定 | `SettingsScreen` + `SettingsViewModel`（`ui/ViewModels.kt`）。`DataStore` は AppPreferences |
| エンジン | `LlamaCppEngine`（`InferenceEngine` 実装）。`generate()` が JNI を同期呼び出し |
| JNI | `LlamaCppNative`（object）。結果は JSON 文字列 |
| ネイティブ | `app/src/main/cpp/llama_jni.cpp` 単一ファイル。計測値は内部で計算済みだが結果 JSON に含めていない |
| 既存計測 | Logcat のみ: `generate: prompt_tokens/prompt_ms/generated_tokens/gen_ms/tok_per_sec/ttft_ms` |
| 欠けている計測 | 結果 JSON に metrics がない / 推論途中の進捗がない / メモリがない / 実行時設定の取得 API がない |
| テスト | `:core` は JUnit4 の純 Kotlin テスト、`:app` は JVM テスト。CI が両方実行 |
| ビルド | ローカルに Android SDK/Gradle なし。**GitHub Actions でビルド・テスト** |

---

## 3. 設計

### 3.1 レイヤ分離

```
core/debug/   ← 純 Kotlin。Android 依存なし。単体テスト対象
  DebugConfig         ON/OFF の単一の真実（in-memory）
  DebugMetrics        計測データのモデル（NativeMetrics / MemorySample / ログ / レポート）
  DebugSession        1 回の推論の状態機械・集計・tok/s・ログ上限・16 token サンプル
  DebugJson           JSON エンコード（kotlinx.serialization）

app/debug/    ← Android 依存。UI・ファイル・プロセス情報
  DebugRecorder       エンジンとの唯一の接続点（StateFlow で UI に配信）
  DebugProcessInfo    /proc/self/status から VmRSS / VmSwap
  DebugExporter       JSON 保存 + 共有（FileProvider）
  DebugViewModel      UI 状態
  DebugScreen         デバッグ画面

app/src/main/cpp/
  debug_bridge.h      デバッグ用の共有状態（小さな struct）
  debug_jni.cpp       nativeRuntimeInfo() と runtime JSON 生成
  llama_jni.cpp       既存 + 「計測値の受け渡し」だけ最小追加
```

### 3.2 ON/OFF の扱い

- `DebugConfig.enabled: MutableStateFlow<Boolean>`（**in-memory**。再起動で OFF）。
- 設定画面の「開発者向け（一時）」セクションで切替。ON のときだけデバッグ画面への導線を表示。
- 切替はエンジン・KV Cache・進行中の推論に一切触れない（参照するのは記録側のみ）。
- OFF 時: メモリ収集・ログ蓄積・StateFlow 更新・詳細計測を行わない。

### 3.3 計測の取り方（正確さの担保）

| 段階 | 取得元 | 精度 |
|---|---|---|
| Template 構築 | ネイティブ `apply_ms` | 正確 |
| Tokenize | ネイティブ `tokenize_ms` | 正確 |
| KV Clear | ネイティブ `clear_ms` | 正確 |
| Prompt Evaluation | ネイティブ `prompt_ms` | 正確 |
| Decode | ネイティブ `gen_ms` / ttft | 正確 |
| 全体 | Kotlin の wall clock（開始→終了） | 正確 |
| 16 token ごとの速度 | Kotlin がトークン到着時刻から算出 | 近似（piece = 1 token） |
| ライブの段階表示 | 開始→（最初のトークンまで）`PREPARING`→`DECODE` | 粗い（正確な境界は完了時の metrics） |

ネイティブは既に全タイミングを計算している。**結果 JSON に `metrics` を追加するだけ**で、
追加の計算コストはほぼゼロ。ON/OFF で計測値を変えない。

### 3.4 ネイティブへの追加（最小）

1. `llama_jni.cpp`
   - ロード時に実値を `g_debug_state` へ記録（n_ctx / n_batch / n_ubatch / n_seq_max /
     n_threads / n_threads_batch / KV 型 / Flash Attn 設定 / threadpool 説明）。
     **挙動は変更しない（記録のみ）。**
   - `run_generation_locked()` の結果 JSON に `"metrics":{...}` を追加。
     `apply_ms` / `tokenize_ms` を引数で受け取る（既定 `{0,0}`）。
2. `debug_jni.cpp`（新規）
   - `nativeRuntimeInfo(): String?` … 実値 + `sched_getaffinity(0)` の許可 CPU +
     llama.cpp バージョン/コミット（`0.9.0` / `cd959ba`、固定値）。
   - 取得できない項目は JSON に含めない（Kotlin 側で「取得不可」表示）。
3. `CMakeLists.txt` に `debug_jni.cpp` を追加（1 行）。

**変更しない**: プロンプト構築、tokenize、サンプリング、KV クリア方針、ロード、
threadpool、affinity、キャンセル。

### 3.5 エンジンへの接続（最小）

`LlamaCppEngine.generate()` に 4 箇所だけ追加:

```kotlin
val recording = DebugRecorder.begin(params, request.messages.size)   // OFF なら null
...
callback = TokenCallback { piece -> pieces.trySend(piece); recording?.onToken() }
...
recording?.onResult(raw)        // metrics を解析
recording?.finish(outcome)      // 成功/キャンセル/エラー
```

- `DebugRecorder` は `DebugConfig.enabled` が false なら即 return（オブジェクトすら作らない）。
- プロンプト本文・生成本文は記録しない（件数と時刻のみ）。

### 3.6 画面

`DebugScreen` は 1 画面をセクション分割:

- A 推論状態（IDLE / PREPARING / DECODE / COMPLETED / CANCELLED / ERROR、開始時刻、経過、トークン数、tok/s）
- B 処理時間（Template / Tokenize / KV Clear / Prompt / Decode / Total、16 token ごとの速度）
- C llama.cpp 実行情報（実値と「設定値」を別行で表示、取得不可は明示）
- D メモリ（VmRSS / VmSwap / 取得時刻、推論前・中・後）
- E ログ（時刻 + レベル、最大 200 件のリングバッファ）
- F エクスポート（保存 + 共有、保存先パス表示）

### 3.7 JSON

`trpg-debug-YYYYMMDD-HHmmss.json` を `context.getExternalFilesDir("debug")` に保存。
含める: アプリ version / llama.cpp version・commit / 推論設定 / 各処理時間 / トークン数 /
tok/s / メモリ推移 / CPU affinity / 時系列ログ / エラー。
**含めない**: プロンプト本文、生成本文、モデルの絶対パス以外の個人情報。
（モデル名はファイル名のみ）

---

## 4. 変更ファイル一覧

### 新規（削除対象）

| ファイル | 内容 | 目安 |
|---|---|---|
| `core/src/main/kotlin/io/github/shiokunn/trpg/core/debug/DebugConfig.kt` | ON/OFF | ~25 行 |
| `core/src/main/kotlin/io/github/shiokunn/trpg/core/debug/DebugMetrics.kt` | データモデル | ~120 行 |
| `core/src/main/kotlin/io/github/shiokunn/trpg/core/debug/DebugSession.kt` | 状態機械・集計 | ~180 行 |
| `core/src/main/kotlin/io/github/shiokunn/trpg/core/debug/DebugJson.kt` | JSON 化 | ~80 行 |
| `core/src/test/kotlin/io/github/shiokunn/trpg/core/debug/DebugConfigTest.kt` | 単体テスト | ~40 行 |
| `core/src/test/kotlin/io/github/shiokunn/trpg/core/debug/DebugSessionTest.kt` | 単体テスト | ~180 行 |
| `core/src/test/kotlin/io/github/shiokunn/trpg/core/debug/DebugJsonTest.kt` | 単体テスト | ~80 行 |
| `app/src/main/kotlin/io/github/shiokunn/trpg/debug/DebugRecorder.kt` | エンジン接続 | ~110 行 |
| `app/src/main/kotlin/io/github/shiokunn/trpg/debug/DebugProcessInfo.kt` | メモリ読み取り | ~50 行 |
| `app/src/main/kotlin/io/github/shiokunn/trpg/debug/DebugExporter.kt` | 保存・共有 | ~80 行 |
| `app/src/main/kotlin/io/github/shiokunn/trpg/debug/DebugViewModel.kt` | UI 状態 | ~60 行 |
| `app/src/main/kotlin/io/github/shiokunn/trpg/debug/DebugScreen.kt` | UI | ~300 行 |
| `app/src/main/cpp/debug_bridge.h` | 共有状態 | ~30 行 |
| `app/src/main/cpp/debug_jni.cpp` | 実行時情報 JNI | ~110 行 |
| `app/src/main/res/xml/debug_file_paths.xml` | FileProvider | ~6 行 |

### 既存ファイルへの接続（削除時に戻す箇所）

| ファイル | 変更 | 目安 |
|---|---|---|
| `app/src/main/cpp/llama_jni.cpp` | `#include "debug_bridge.h"` / ロード時に実値記録 / 結果 JSON に metrics / `apply_ms`・`tokenize_ms` 引数 | ~35 行 |
| `app/src/main/cpp/CMakeLists.txt` | `debug_jni.cpp` を追加 | 1 行 |
| `app/src/main/kotlin/.../ai/engine/LlamaCppNative.kt` | `external fun nativeRuntimeInfo()` | 1 行 |
| `app/src/main/kotlin/.../ai/engine/LlamaCppEngine.kt` | `DebugRecorder` 呼び出し 4 箇所 | ~8 行 |
| `app/src/main/kotlin/.../ui/navigation/TrpgNavHost.kt` | `Routes.DEBUG` + composable + SettingsScreen 引数 | ~8 行 |
| `app/src/main/kotlin/.../ui/settings/SettingsScreen.kt` | 「開発者向け（一時）」セクション + `onOpenDebug` | ~30 行 |
| `app/src/main/AndroidManifest.xml` | FileProvider（共有用） | ~6 行 |

**接続箇所は合計 6 ファイル・約 90 行**。デバッグ機能の削除は
「新規 15 ファイル削除 + 上記 6 ファイルの該当ブロック削除」で完了する。
各接続箇所には `// DEBUG MODE (temporary) — remove with app/debug/` の目印コメントを付ける。

---

## 5. 実装手順

| # | 手順 | 完了条件 |
|---|---|---|
| S1 | core の DebugConfig / DebugMetrics / DebugSession / DebugJson を実装 | コンパイル可能な純 Kotlin。機能は §3.2–3.3 のとおり |
| S2 | core の単体テストを追加 | ON/OFF、開始終了、集計、tok/s、ログ上限、JSON、欠損メトリクスが通る |
| S3 | ネイティブ: `debug_bridge.h` / `debug_jni.cpp` / metrics JSON / CMake | JNI シンボルが生成され、既存の結果 JSON に `metrics` が付く |
| S4 | app: DebugRecorder / DebugProcessInfo | OFF で何も記録しない。ON で StateFlow が更新される |
| S5 | エンジン接続 | `generate()` の戻り値・挙動が不変。ON のときだけ記録される |
| S6 | DebugViewModel / DebugScreen | §3.6 の A–F が表示・更新される |
| S7 | 設定トグル + ナビ + Manifest/XML | 設定から ON/OFF・画面遷移できる |
| S8 | ローカル静的確認（差分レビュー） | 既存推論ロジックに差分がないことを目視確認 |
| S9 | commit / push → GitHub Actions | `:core:test` / `:app:testDebugUnitTest` / `assembleDebug` が成功 |
| S10 | 実機（arrows Alpha）確認 | §7 の実機項目 |
| S11 | 最終レポート + PROGRESS 更新 | §8 の完了条件をすべて判定 |

> **commit / push の許可**: 本計画の承認をもって、S9 の commit / push（main）を
> 許可いただいたものとして扱います。不要な場合は承認時に明示してください。

---

## 6. テスト計画

### 6.1 単体テスト（`:core:test`）

- `DebugConfig`: 初期 OFF / ON / OFF / 二重設定
- `DebugSession`: 開始→token→完了（COMPLETED）/ キャンセル / エラー
- 集計: prompt_ms / gen_ms / total / ttft / tok/s（0 除算を含む）
- 16 token サンプルが 16 件ごとに作られる
- ログ: 200 件上限、古いものから捨てる、レベルと時刻が付く
- JSON: 必須キー、本文が含まれない、取得不可フィールドの欠落
- 欠損 metrics（テンプレート失敗など）でもクラッシュしない

### 6.2 アプリテスト

- 既存 `:app:testDebugUnitTest` が通る（回帰）
- 追加の JVM テストが必要なら `DebugRecorder` の OFF 時 no-op を確認
  （Android 依存が強い場合は core 側のテストで代替し、その旨を報告）

### 6.3 CI

`.github/workflows/android.yml` を**変更せず**利用:
`:core:test` → `:app:testDebugUnitTest` → `:app:assembleDebug` → 署名検証。

### 6.4 実機（arrows Alpha / Android 16）

- デバッグ OFF: 通常の画面・推論が動く
- デバッグ ON: 設定から ON → デバッグ画面 → 推論実行
- 推論中に経過時間・トークン数・tok/s が更新される
- 完了後に Template / Tokenize / KV / Prompt / Decode が個別表示される
- VmRSS / VmSwap が記録される
- JSON を保存でき、パスが表示され `adb pull` できる
- キャンセルで CANCELLED になる
- ON/OFF 切替後も推論が正常

**注意**: 長時間推論は OS によるアプリ凍結（既知・本次の対象外）で停止しうる。
実機確認は短い生成（例: maxTokens 64–128）で行い、凍結した場合はその事実を記録して
「未確認」と報告する（性能改善は行わない）。

---

## 7. リスクと対策

| リスク | 対策 |
|---|---|
| 計測の追加が推論結果を変える | ネイティブは「既に計算済みの値」を JSON に足すだけ。プロンプト・サンプリング・KV に触れない |
| OFF 時にオーバーヘッドが残る | `DebugRecorder.begin()` が即 null。メモリ収集・ログ・StateFlow は ON のみ |
| デバッグ用コードの散在 | 接続は 6 ファイル・目印コメント付き。専用ファイルに集約 |
| ログでメモリを圧迫 | 200 件のリングバッファ + UI は最新のみ |
| JSON に会話が混入 | 本文を保持しない設計。テストで検証 |
| FileProvider 追加でビルドが壊れる | manifest + xml は最小構成。CI で検証 |
| 実機の凍結で検証不能 | 短い生成で検証。凍結は既知問題として記録（修正しない） |
| 既存テストの回帰 | CI で `:core:test` と `:app:testDebugUnitTest` を必ず実行 |

---

## 8. 完了条件（ユーザー指定のチェックリスト）

- [ ] デバッグモードをアプリ内から ON/OFF できる
- [ ] 推論の処理時間を個別に確認できる
- [ ] Prompt Evaluation と Decode を区別できる
- [ ] 生成速度を確認できる
- [ ] メモリ情報を確認できる
- [ ] llama.cpp の実行設定を確認できる
- [ ] ログを確認できる
- [ ] JSON エクスポートができる
- [ ] デバッグモード OFF で通常動作する
- [ ] 新規テストが成功する
- [ ] 既存テストが成功する
- [ ] Android ビルドが成功する
- [ ] 削除対象のファイルと接続箇所が明確になっている

---

## 9. 承認をお願いしたい点

1. 本計画（§3 設計 / §4 ファイル一覧 / §5 手順）で進めてよいか
2. **commit / push（main へ）を許可してよいか**（CI 実行のため必須）
3. デバッグ ON/OFF を **in-memory**（再起動で OFF）でよいか
   （DataStore に永続化する場合は既存ファイルへの接続が 1 箇所増えます）
4. 実機確認は「短い生成（maxTokens 64–128）」で行ってよいか

承認後は `Status: IN_PROGRESS` にして S1 から自律実行します。
