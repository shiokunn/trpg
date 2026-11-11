# PROGRESS — Android向け完全オフラインTRPGアプリ

**Status: Phase 7（仕上げ）実装完了 / Phase 8（推論エンジン改善）IN_PROGRESS（P0-1〜P1-2 実装完了、P1-3・CI・実機は未実施）**
**Last updated: 2026-11-10**

---

## 現在の状態

- リポジトリ: `https://github.com/shiokunn/trpg`（private, `main`）
- アプリ名「TRPGオフライン」/ package `io.github.shiokunn.trpg`
- 構成: `:core`（純 Kotlin/JVM）+ `:app`（Android）
- ビルド/テストは GitHub Actions が唯一の実行経路（Termux に Android SDK/NDK が無い）
- 検証の詳細と未検証項目: [docs/VERIFICATION.md](docs/VERIFICATION.md)

## フェーズ

- [x] Phase 0: Git / private repo / Gradle wrapper / CI
- [x] Phase 1: Core ドメイン・D6ルール・Room 15テーブル・リポジトリ・Compose 画面骨格
- [x] Phase 2: `InferenceEngine` / llama.cpp JNI v0.9.0 / モデル取り込み・読み込み・削除 /
      CMake・NDK 統合 / CI での arm64-v8a APK ビルド
- [x] Phase 3: 状態変更エンジン、GM・草案の厳格パーサ、D6 判定・戦闘解決
- [x] Phase 4: PromptBuilder、`GmTurnService`（判定→生成→厳格解析→1回修復→検証→単一トランザクション）、
      選択肢・自由行動・ストリーミング・中止 UI、冒険開始（AI生成 / 設定+AI補完）
- [x] Phase 5: 4層記憶のプロンプト供給、`MemorySelector`、秘密情報のプレイヤー画面除外、NPC記憶表示
- [x] Phase 6: JSON バックアップ（厳格検証・ID衝突検査・単一トランザクション）、
      決定論的戦闘ラウンド、装備切替、クエスト/場所/NPC/履歴/XP 表示
- [x] Phase 7（コード）:
  - [x] 削除済み・読み込み不能セッションで無限ローディングにせず「冒険が見つかりません」を表示
  - [x] OOM・予期しない例外を日本語の対処メッセージへ変換（モデル読み込み/生成/ターン/冒険生成）
  - [x] HP・XP・クエスト達成度・生成中状態の semantics 付与
  - [x] テーマ選択行を全面選択可能に、クイック操作を文字拡大に追従
  - [x] モデル未読み込み時は選択肢を無効化
  - [x] OS クラウド自動バックアップを無効化（明示的な JSON 書き出しのみ）
  - [x] README / 検証レポート / Room スキーマ成果物の CI 保存

## ローカル検証

- `./gradlew -p core test` — 89件すべて成功（2026-11-08）
  - 判定・キャラクター規則 14、戦闘解決 6、戦闘ラウンド 5、状態変更 19、
    GM パーサ 13、草案パーサ 10、プロンプト 7、記憶 6、バックアップ 8、その他 1
- `app` 単体テスト 5件は Android SDK が必要なため CI で実行

## CI ログ（抜粋）

| run | 内容 | 結果 |
| --- | --- | --- |
| 42481150919 | llama.cpp JNI / arm64-v8a APK 初回ビルド | 成功 |
| 42483091454 | 記憶選別コミット | 成功 |
| 42484039490 | プロンプトのエンティティID修正（core 89 + app 5 + APK） | 成功 |
| 42485089100 | Phase 7 一式（core 89 + app 5 + arm64-v8a APK + Room スキーマ） | 成功 |
| 42485846213 | 最終実行（core 89 + app 5 + arm64-v8a APK） | 成功 |

失敗して修正した主な CI 問題: Gradle wrapper タイムアウト、enum マッピング、
`combine` の型推論、JNI v0.9.0 の `use_mmap`/`use_mlock`、非 suspend からの
`settings()` 呼び出し、冒険生成の例外処理。

## 未検証・未実装（正直な残課題）

- 実機での GGUF 読み込み・生成・ストリーミング・キャンセル・修復リトライ
- 実機での Vulkan（未ビルド）、OOM の再現、プロセス kill
- Room マイグレーション（version 1 のみ。将来はスキーマ差分から明示的 Migration を追加）
- SAF の大容量ファイル・空き容量不足・書き込み失敗
- 実機での画面回転・バックグラウンド・TalkBack・文字サイズ 200%
- 戦闘終了後の GM 描写連携
- 場所・クエストの手動追加、アイテム数量編集
- 世界・導入の事後編集 UI

## 次にやること

1. 実機でモデル読み込み→冒険開始→数ターン→保存→再起動→続きから、を確認
2. 実機でバックアップ書き出し→削除→読み込みを確認
3. 次バージョンでスキーマ差分から Migration を追加し、MigrationTestHelper で検証
4. Phase 6 の残り（場所・クエスト・アイテム編集、戦闘後描写）に着手

---

# Phase 8 — llama.cpp v0.9.0 推論エンジン改善

**Status: IN_PROGRESS（P0-1〜P1-2 実装完了 / クラウド Android ビルド検証済み / P1-3・実機は未実施）**
**Last updated: 2026-11-10**
**Base commit: `3f3602c`（変更は未 commit）**

## 現在の状態

- 既存の Phase 0–7 の履歴は上に保持している。Phase 8 は推論層だけを対象とする。
- **P0-1 / P0-2 / P1-1 / P1-2 は実装済み。P1-3（ゴールデンテスト・CI host job）と
  P2（A/B/C 計測）は未実施。**
- **CI 実行・commit / push・ADB 実機テストは行っていない。**
- 調査は llama.cpp v0.9.0（`cd959bae99ff4bed22bef2604f45241c09bbb8c8`）を
  `scripts/fetch-llama.sh` で `third_party/llama.cpp`（gitignore 済み）に取得して実施。
- 計画と実装結果の詳細は [PLAN.md](PLAN.md) の Phase 8 節（8.9 が実装結果）。

## Phase 8 の調査結果（要約）

- 現行 `nativeFormatChat` の `llama_chat_apply_template` は Kyalulu の Gemma 4 系
  テンプレートを評価できず、`LlamaCppEngine` が黙って ChatML へフォールバックしている。
- v0.9.0 には `common`/minja と Gemma 4 専用ハンドラがあり、
  `common_chat_templates_init` / `common_chat_templates_apply` で GGUF の
  Jinja テンプレートを評価できる。現行ビルドは `LLAMA_BUILD_COMMON=OFF`。
- 永続 `ggml_threadpool` は未使用。グラフごとに使い捨てプールが作られ、
  CPU affinity も設定されていない。対象端末の高クロック群は 4 コア。
- キャンセルは mutex 取得後にフラグをリセットするため、待機中の要求を取りこぼす。
  abort callback も未設定。テンプレート整形は UI スレッド上で呼ばれている。
- JNI は `GetStringUTFChars` / `NewStringUTF` を使っており、絵文字・非BMP・
  トークン境界で分割された UTF-8 を安全に扱えない。
- Kyalulu の停止は native の `generate start` より前で、テンプレート整形・
  トークン化・memory clear のどこかは未確定。今回は計測を追加する計画のみ。

## Phase A-2 既存実装の再利用調査（2026-11-10）

- LLM-PLAYER の `4f3293f` は **native-lib.cpp を直接変更していない**。
  ワークフロー内のパッチ文字列がコードを挿入する仕組みで、実 decoder は
  `6571062` の `append_valid_utf8_to_utf16` / `new_jstring_from_utf8`。
- `6571062` は不完全な UTF-8 の保留、overlong / surrogate / 範囲外の
  U+FFFD 置換、4バイトの UTF-16 サロゲートペア生成、`NewString` による
  出力、`pending_utf8` の管理を実装している。テストは無い。
- LLM-PLAYER は入力側で `GetStringUTFChars` を使い続けており、
  prompt の絵文字・非BMP は修正されていない。cancel 時の pending flush も
  一貫していない。
- llama.cpp v0.9.0 の server `validate_utf8` は末尾の不完全列のみ。
  `is_valid_utf8` / `common_parse_utf8_codepoint` も完全な Unicode 検証では
  ない。server のテストベクタは参考にできる。
- Android JNI 公式仕様では `GetStringChars`（UTF-16）→ 変換 →
  `NewString` が正しく、`NewStringUTF` は Modified UTF-8 専用。
  local reference と pending exception に注意が必要。
- 再利用判断: decoder は LLM-PLAYER 版をベースに移植。入力側
  `GetStringChars`、final flush、RAII、例外処理、テストは新規実装。
  詳細は PLAN.md の 8.8 節。

## Phase 8 実装内容（2026-11-10）

- **P0-1（完了）**: `LLAMA_BUILD_COMMON=ON` + `llama-common` リンク。
  `common_chat_templates_init/apply` による実プローブで
  `applied` / `none` / `unsupported` を判定。テンプレートありの失敗は fail closed。
  `common_chat_prompt.parts` の `is_input` でユーザー入力を `parse_special=false`
  トークン化（BOS は先頭 part のみ）。`nativeFormatChatEx` が status/stops/addBos/
  supportsThinking を返し、`ModelsScreen` が表示。
- **P0-2（完了）**: `ggml_threadpool_new/free` の proc address 取得と
  `llama_attach_threadpool`。API 不在時は既定へ安全に戻る。sysfs 由来の CPU affinity
  （検出不能なら affinity なし）。既定 profile A（4スレッド）、B/C は比較用。
- **P1-1（完了）**: `AtomicLong` generation ID + `llama_set_abort_callback` +
  バッチ境界チェック。ID は mutex 取得前に発行し待機中の要求も捕捉。
  `GenerationStateGuard`（RAII）で例外時も ID 解放。
  `GameTurnUiState` / `NewAdventureUiState` に `cancelling` を追加（二重タップ防止）。
  `formatChat` を suspend 化して `Dispatchers.Default` で実行。
- **P1-2（完了）**: 新規 `utf8_utils.{h,cpp}`。`GetStringChars` / `NewString` 経由に
  全移行（`NewStringUTF` / `GetStringUTFChars` 全廃）。ストリーミング用
  `Utf8StreamDecoder`、キャンセル時も flush して最終出力と一致させる。
- **計測ログ（完了）**: `template_init` / `template_apply[chat]` / `tokenize[ex]` /
  `clear_ms` / `prompt_ms` / `threadpool_attach`（すべて ms）。
- **旧経路の整理**: `fallbackChatPrompt`（黙って ChatML へ落とす旧経路）を削除。
  旧 `nativeGenerate()` は `nativeGenerateEx` へ委譲する1経路のみ。

### 実装中に見つけて修正したバグ（抜粋）

1. `nativeLoadModel` が `g_model` 代入前にテンプレート初期化を呼んでおり、
   すべてのチャット生成が fail closed になっていた（重大）。
2. 不完全な UTF-8 の最終フラッシュが1文字を複数の U+FFFD に分裂していた。
3. 不完全な保留バイトが次の文字と結合していた。
4. `parseFormatResult` の `it.isNotBlank`（括弧欠落）。
5. 非 suspend の `formatChat` が `withContext` を使用。
6. `LOGI` の `%ld` に `long long` を渡していた。

## ローカル検証（Phase 8）

| 検証 | 結果 |
| --- | --- |
| JNI 13 関数の Kotlin ↔ C++（引数個数・型）機械照合 | 全一致 |
| `llama_jni.cpp` ホスト構文検証 `-Wall -Wextra`（profile A/B/C） | 当方コード警告ゼロ |
| `utf8_utils` ホストコンパイル + 挙動 13ケース | 13/13 成功 |
| エンジン Kotlin 3ファイル `kotlinc` 単体コンパイル | 成功 |
| `gradle -p core build` | 成功 |
| llama.cpp v0.9.0 のシンボル実在確認 | すべて実在 |

## クラウドビルド検証（Phase 8）

GitHub Actions `Android CI` / run `42905312348` / commit `6f1eef3`（2026-11-10）。

- **全ステップ成功**（Core tests → App unit tests → Build Debug APK → artifact アップロード）。
- `:core:test` 89件 / `:app:testDebugUnitTest` 5件、failures 0 / ignored 0。
- `llama-common`（`chat.cpp` / `parsers/*` / `jinja/*`）が NDK 29 でコンパイル成功。
- `app-debug.apk`（arm64-v8a のみ）に `libllama-common.so` / `libllama.so` /
  `libtrpg_llama.so` / `libggml*.so` が同梱。
- `libllama-common.so` の `DT_NEEDED` に `libllama.so` が入っていることを確認。
- `libtrpg_llama.so` の全未定義シンボル（162）が同梱ライブラリ + システム
  ライブラリで解決することを確認（アプリ固有の未解決シンボルなし）。
- `apksigner verify` 成功（v2、`CN=Android Debug`）。
- 当方のソース（`llama_jni.cpp` / `utf8_utils.cpp` / `common/chat.cpp` /
  `common/jinja/*`）に関する警告・エラーはログに無し。

## 未実施・未確定

- **CI host job / golden / A/B/C**: 承認条件を守り未実行。
- **ADB 実機**: 未実施。テンプレート適用・キャンセル・絵文字・threadpool attach は未確認。
  ビルド成功と実機動作成功は別物。
- Kyalulu の停止原因と、停止が Phase 8 で解消するか
- A/B/C の性能・消費電力・熱
- Qwen3 の `enable_thinking=false` の実機出力
- 実モデルのテンプレートを使ったホストテスト（モデル/テンプレートの取得は未実施）

## 次にやること

1. 承認を得て ADB 実機でテンプレート適用・キャンセル・絵文字・計測ログを確認。
2. P1-3（ゴールデンテスト、CI host job）と P2（A/B/C 計測）に着手。
3. ADB 実機テストは別途承認を得てから行う。
