# PLAN — Android向け完全オフラインTRPGアプリ

**Status: IN_PROGRESS**
**Created: 2026-11-08**
**Updated: 2026-11-10**
**Current goal: Phase 8 — llama.cpp v0.9.0 推論エンジン改善（テンプレート / スレッドプール / キャンセル / UTF-8）**
**Prior goal: Phase 0–7 完了（履歴は本ファイル前半と PROGRESS.md に保持）**
**Working directory: `/data/data/com.termux/files/home/trpg`**

> 注: Phase 8 見出しより前の内容は Phase 0–7 時点の履歴である。現在の作業計画は
> 末尾の「Phase 8」節に記載する。既存の履歴・完了済み判断は置き換えない。

---

## 1. ゴールと完成イメージ

端末内の GGUF モデルを llama.cpp で推論し、AI が GM（ゲームマスター）として物語を進める、
完全オフライン・選択肢型・独自 D6 システムの総合 TRPG Android アプリを実装する。

完成時に成立させる一連の操作:

1. アプリ起動 → 2. GGUF モデル読込 → 3. 新規冒険作成 → 4. AI自動生成 / ユーザー設定+AI補完の選択
→ 5. 世界観決定 → 6. AIが世界・シナリオ構築 → 7. キャラクター作成 → 8. GMが導入開始
→ 9. 選択肢で進行 → 10. 必要時 D6 判定 → 11. 結果を反映した物語生成 → 12. NPC/アイテム/クエスト/世界状態の更新
→ 13. 重要出来事を長期記憶へ保存 → 14. 終了後もセーブ維持 → 15. 後日再開

**最重要事項:** AI の物語生成と長期的な整合性。ルール・数値・状態変更はアプリ側が確定し、AI には委ねない。

---

## 2. 現状調査の結果

### 作業ディレクトリ
- `/data/data/com.termux/files/home/trpg` は**完全に空**。既存コード・Git リポジトリ・AGENTS.md・PLAN.md/PROGRESS.md は存在しない。
- したがって「既存の正常なコードを壊さない」対象は現時点で無い。周辺プロジェクト（`~/llm-player` 等）は**変更しない**。

### 環境
- Termux on Android (aarch64), Android 16 (SDK 36), 12GB RAM 相当の端末。
- Java 21 / システム Gradle 9.6.0 / kotlinc / git / gh（`shiokunn` で認証済み）は利用可能。
- **Android SDK / NDK / CMake は未導入。** Termux は Linux-aarch64 ホストであり、公式 NDK は Linux-aarch64 ホスト非対応のため、
  ネイティブを含む APK のローカルビルドは非現実的。**APK ビルド検証は GitHub Actions を正とする。**
- 純 Kotlin の `:core` モジュールは Android SDK 不要でローカルテスト可能（この構成を採用する）。

### 参考にした既存実装（読み取りのみ）
- `~/llm-player`（shiokunn, private）: AGP 9.1.1 / Gradle 9.3.1 / NDK 26.1 / CMake 3.22.1 で llama.cpp を
  GitHub Actions からビルドする実績パターン。JNI の streaming / cancel / サンプリング / コンテキスト変更の実装例。
  **古い API 前提のためコピーせず、ビルド構成の参考に留める。**
- 公式 `ggml-org/llama.cpp` **v0.9.0** の `examples/llama.android` を調査:
  - AGP 8.13.2 / Kotlin 2.3.0 / compileSdk 36 / NDK 29.0.13113456 / CMake 3.31.6
  - `InferenceEngine` 抽象 + `InferenceEngineImpl` + JNI（`ai_chat.cpp`）
  - `GGML_BACKEND_DL=ON`, `GGML_CPU_ALL_VARIANTS=ON`, `GGML_NATIVE=OFF`, arm64 で KleidiAI/OpenMP
  - 現行 API 確認済み: `llama_model_load_from_file`, `llama_init_from_model`, `llama_batch_ext_init`,
    `llama_process(LLAMA_PROCESS_TYPE_DECODE)`, `llama_sampler_chain_init`, `llama_model_chat_template`,
    `llama_chat_apply_template`, `llama_model_n_ctx_train`, `llama_memory_clear` 等。
    v0.9.0 では `llama_load_mode` が mmap/mlock を表すため、JNI は `LLAMA_LOAD_MODE_AUTO` を使用する。

### 既存 PLAN/PROGRESS
- 無し。別ゴールのファイルも無いため、新規作成する。

---

## 3. 主要な設計判断

### 3.1 モジュール構成（責務分離）
```
:core  純 Kotlin/JVM（Android 依存なし）
  domain model, D6 rules, dice, combat, state change validation,
  GMResponse parser/validator, PromptBuilder/token budget, memory selection
:app   Android
  Compose UI, ViewModel, Room, DataStore, SAF, JNI (llama.cpp), repositories
```
- `:core` は単独 Gradle ビルドとしても実行可能にし、**ローカルで `gradle -p core test` を実行**できるようにする。
- `:app` は `:core` に依存。UI / GameEngine / AI / DB の責務を分離する。

### 3.2 バージョン（llama.cpp v0.9.0 公式サンプル準拠を基本）
- Gradle 8.14.3 / AGP 8.13.2 / Kotlin 2.3.21 / KSP 2.3.13
- compileSdk 36 / targetSdk 36 / minSdk 33 / ABI `arm64-v8a`（デバッグ用途で x86_64 は任意）
- Compose BOM 2026.02.01 / Material 3 / Navigation 2.9.8 / Lifecycle 2.10.0 / Activity 1.12.2
- Room 2.8.5 (KSP, schema export) / DataStore 1.2.0 / kotlinx-serialization-json 1.9.0 / coroutines 1.10.2
- JDK 17 toolchain（CI は JDK 21 で実行）
- 互換性問題が出た場合は `~/llm-player` の実績構成（AGP 9.1.1 / Gradle 9.3.1）へフォールバックし、PLAN/PROGRESS に記録する。

### 3.3 llama.cpp
- `third_party/llama.cpp` に **v0.9.0 を固定**（ローカルは `scripts/fetch-llama.sh`、CI は clone + commit 検証）。
  リポジトリにはソースをコミットせず `.gitignore`（再現性はタグ + commit 固定で担保）。
- CPU を基本。`GGML_BACKEND_DL` + `GGML_CPU_ALL_VARIANTS` で実機 CPU 機能を実行時選択。
- Vulkan は**ビルドフラグ**（`-Ptrpg.vulkan=true`）で任意対応とし、デフォルトは CPU。
  実行時に利用可能バックエンドを列挙して画面表示し、GPU を決めつけない。Mali-G615 実機検証は環境上不可のため「未検証」と明記。
- JNI は自前の `llama_jni.cpp` を現行 API で実装。`InferenceEngine` インターフェース越しのみ UI から利用。
- ストリーミングは Kotlin `Flow` + JNI コールバック。キャンセルは atomic フラグ。推論は UI スレッドで実行しない。

### 3.4 AI 出力の扱い
- GM/世界/シナリオ/記憶整理は JSON 契約。`kotlinx.serialization`（`ignoreUnknownKeys=false` 相当の厳格検証）で解析。
- コードフェンス除去 → 最初の `{`〜最後の `}` 抽出 → 解析 → スキーマ検証。失敗時は修正プロンプトで**1回だけ**再生成。
- `stateChanges` は許可リスト + 対象 ID 存在 + 範囲クランプ検証。1件でも不正なら**全件不適用**（ゲーム状態を変更しない）。
- 判定は必ずアプリ側 `DiceEngine`。AI には確定済み結果のみ渡す。

### 3.5 記憶
- 4層（短期 / 長期 / 世界 / NPC）。全会話をコンテキストへ詰めない。
- `PromptBuilder` が優先順位（現在行動+判定 > 現在状態 > 関連NPC/クエスト > 長期記憶 > 世界設定 > 直近会話）で構築。
- トークン数はモデル読込時に JNI `countTokens`、未読込時は安全側の文字数推定。`ctx - maxGen - margin` を上限に低優先度から削る。

### 3.6 データ
- Room 15 テーブル: Worlds, GameSessions, Characters, Skills, InventoryItems, NPCs, NPCMemories,
  Locations, Quests, GameEvents, StoryMemories, ChatHistory, DiceRollLogs, ModelSettings, WorldFlags。
- 複合構造は JSON カラム（TypeConverter）。外部キー + インデックス、`CASCADE` はセッション削除時のみ。
- ターン確定・インポートは `withTransaction`。DB バージョンは 1 から開始し、以後は破壊的 Migration 禁止。
- 全データ JSON エクスポート/インポート（Phase 6）。

### 3.7 アプリ識別子
- applicationId / namespace: `io.github.shiokunn.trpg`
- アプリ名: 「TRPGオフライン」
- 画面は日本語標準、Material 3、ダーク/ライト、縦画面優先。INTERNET 権限は付与しない。

### 3.8 リポジトリ / CI
- 新規 Git リポジトリを作成し、GitHub へ push（**要承認**）。
- GitHub Actions: JDK 21 + Android SDK + NDK 29.0.13113456 + CMake 3.31.6 + llama.cpp 固定取得
  → `:core:test` → `:app:testDebugUnitTest` → `:app:assembleDebug` → APK artifact 保存。
- ビルド成功報告は**実際の CI 成功ログと artifact を確認した場合のみ**行う。

---

## 4. 実装フェーズと完了条件

各ステップは単独で追跡可能。完了条件は観測可能な形で記載する。

### Phase 0: リポジトリ基盤
- 0.1 `git init`、`.gitignore`、README、`scripts/fetch-llama.sh`、Gradle wrapper、`settings.gradle.kts`、version catalog。
- 0.2 GitHub リポジトリ作成 + CI ワークフロー。**空に近い状態で core test + app build を一度通す。**
- 完了条件: ローカル `gradle -p core test` 成功、CI が green、APK artifact 生成。

### Phase 1: アプリ基盤
- 1.1 Compose テーマ（ダーク/ライト、日本語タイポグラフィ）、Navigation（全画面ルート）、共通コンポーネント。
- 1.2 画面の骨格: Home / 新規冒険 / 世界確認 / キャラクター作成 / Game / Character / World / Journal / Saves / Models / Settings。
- 1.3 Room スキーマ v1、Entity/DAO/TypeConverter、Repository、`AppContainer`（手動 DI）、DataStore 設定。
- 1.4 セッション自動保存基盤（ターン確定トランザクション）、セッション一覧・再開・削除。
- 完了条件: CI で app ビルド成功、Room schema JSON 出力、core test 成功、画面遷移コードが全ルート到達可能。

### Phase 2: llama.cpp 統合 / モデル管理
- 2.1 CMake + JNI（init/load/generate stream/cancel/unload/modelInfo/backendInfo/countTokens/formatChat）実装。
- 2.2 `LlamaCppEngine`（Flow、状態、排他、キャンセル、ライフサイクル）、`InferenceEngine` 抽象。
- 2.3 SAF からの GGUF ストリーミングコピー（空き容量確認、進捗、キャンセル、失敗時 .part 削除）、モデル一覧/選択/削除。
- 2.4 推論パラメータ設定（ctx/temp/top_p/top_k/repeat/maxTokens/GPU layers）、バックエンド表示、メモリ警告。
- 2.5 CI に NDK/CMake/llama.cpp を組み込み、**arm64-v8a Debug APK を実際に生成**。
- 完了条件: CI で APK 生成成功。JNI/エンジンの単体テスト（純 Kotlin 部分・パラメータ検証）成功。
  実機推論と Vulkan は環境上未検証であることを明記。

### Phase 3: D6 ゲームエンジン
- 3.1 `Attributes`（1..5、合計18検証）、`Skill`（0..3、作成時最大2、合計6）。
- 3.2 `DiceEngine`: 1D6 + 能力 + スキル、有利/不利、難易度、大成功/成功/部分的成功/失敗、`DiceResult` 保存。
- 3.3 `CombatResolver`: 攻撃/防御、命中、`max(1, 威力 + floor((攻撃-防御-1)/3) - 防御力)`、HP 0 で戦闘不能（死亡は自動確定しない）。
- 3.4 `StateChangeValidator/Applier`: HP_DELTA, XP_DELTA, ITEM_ADD/REMOVE, NPC_RELATION_DELTA (±100), QUEST_PROGRESS/STATUS,
  LOCATION_CHANGE, FLAG_SET（+ 戦闘開始/終了）。ID 存在検証・範囲クランプ・原子性。
- 3.5 キャラクター作成 UI（手動割振り + 検証、AI支援は Phase 4 の生成結果を同じ検証器に通す）。
- 完了条件: `:core` テストで全境界値・判定分類・有利不利・ダメージ最小1・範囲クランプ・不正変更拒否が green。

### Phase 4: AI ゲームマスター
- 4.1 `GMResponse`/`WorldDraft`/`ScenarioDraft`/`CharacterDraft`/`MemoryDraft` モデル + 厳格パーサ + 1回再生成 + テスト。
- 4.2 世界生成 AI・シナリオ生成 AI、2方式（AI自動 / ユーザー設定+AI補完）、世界確認・編集 UI。
- 4.3 メイン GM ターンパイプライン:
  選択 → （必要なら）判定確定 → PromptBuilder → ストリーミング表示 → 解析/検証 → トランザクション確定 → 自動保存。
  生成中ロック、キャンセル、失敗時は直前セーブ維持、再試行。
- 4.4 選択肢カード（3〜4個、判定バッジ、無効時無効化）、NPC 会話表示、ローディング、ストリーミング整合。
- 完了条件: パーサ/検証/プロンプトのテスト green、CI ビルド green、ターン確定が単一トランザクションであることをコードレビューで確認。
  実モデルでの実プレイは端末/モデルが無いため未検証と明記。

### Phase 5: 物語記憶
- 5.1 ChatHistory / StoryMemory / NPCMemory の保存と取得、GM専用 secret フラグの分離。
- 5.2 記憶整理 AI（分類・重要度1..5・推測/確定の区別・矛盾は上書きせず競合記録）。
- 5.3 PromptBuilder の優先順位・トークン予算・切り詰め・関連記憶選択、セッション再開コンテキスト。
- 5.4 再開時に世界状態・NPC関係・長期記憶が復元されるリポジトリ経路。
- 完了条件: 予算超過時の切詰め順序、secret 非表示、再開時コンテキストのテスト green。

### Phase 6: 総合 TRPG 機能
- 6.1 戦闘（敵 NPC の戦闘値、ラウンド UI、判定は GameEngine）。
- 6.2 クエスト、インベントリ/装備、世界地図/場所、NPC 一覧、履歴/重要イベント、成長（XP/レベル）。
- 6.3 JSON エクスポート/インポート（SAF、検証、トランザクション、競合方針、失敗時に既存データを壊さない）。
- 完了条件: 各画面が実データに接続（ダミー表示なし）、core/リポジトリテスト green、CI ビルド green。

### Phase 7: 品質向上
- 7.1 UI 調整（可読性、空状態、エラー、ローディング、適度なアニメーション、アクセシビリティ基本）。
- 7.2 推論の安定性（画面回転、バックグラウンド、キャンセル、OOM 通知、再試行）、KV キャッシュ再利用は余力があれば。
- 7.3 CI 仕上げ（Vulkan 任意ビルド、成果物、README、検証レポート）、未実装項目の明示。
- 完了条件: CI green、コアテスト green、ドキュメント整備、最終報告に「実機未検証」を明記。

---

## 5. 変更・作成予定ファイル（主要）

```
PLAN.md, PROGRESS.md, README.md, .gitignore, settings.gradle.kts, build.gradle.kts,
gradle.properties, gradle/libs.versions.toml, gradle/wrapper/*,
.github/workflows/android.yml
scripts/fetch-llama.sh

core/settings.gradle.kts, core/build.gradle.kts
core/src/main/kotlin/io/github/shiokunn/trpg/core/
  domain/{World,Character,Session,Item,Npc,Quest,Location,Memory,Dice,ModelSettings}.kt
  rules/{Attributes.kt,Skills.kt,DiceEngine.kt,CheckResult.kt,CombatResolver.kt}
  state/{StateChange.kt,StateChangeValidator.kt,GameSnapshot.kt}
  ai/{GmResponse.kt,WorldDraft.kt,ScenarioDraft.kt,CharacterDraft.kt,DraftParser.kt}
  prompt/{PromptBuilder.kt,PromptSections.kt,TokenBudget.kt}
  memory/{MemorySelector.kt,MemoryModels.kt}
core/src/test/kotlin/... （ルール・判定・戦闘・状態変更・JSON・プロンプト予算）

app/build.gradle.kts, app/src/main/AndroidManifest.xml
app/src/main/cpp/{CMakeLists.txt,llama_jni.cpp}
app/src/main/kotlin/io/github/shiokunn/trpg/
  TrpgApplication.kt, MainActivity.kt, AppContainer.kt
  data/database/{AppDatabase.kt,Entities.kt,Daos.kt,Converters.kt}
  data/repository/{WorldRepository,SessionRepository,CharacterRepository,MemoryRepository,ModelRepository,SettingsRepository}.kt
  ai/engine/{InferenceEngine.kt,LlamaCppEngine.kt,LlamaCppNative.kt,ModelFileManager.kt}
  ai/gm/{WorldGenerator.kt,ScenarioGenerator.kt,GameMaster.kt,MemoryCurator.kt,TurnPipeline.kt}
  ui/theme/*, ui/components/*, ui/navigation/*
  ui/home/*, ui/creation/*, ui/game/*, ui/character/*, ui/world/*, ui/journal/*, ui/models/*, ui/settings/*
app/src/main/res/values/strings.xml, themes.xml
```

---

## 6. 検証戦略

- **ローカル（Termux）**: `gradle -p core test`（Android SDK 不要）。純 Kotlin のルール/パーサ/状態変更/プロンプト予算を実行。
- **CI（GitHub Actions）**: `:core:test` → `:app:testDebugUnitTest` → `:app:assembleDebug`。APK artifact を保存。
- **レビュー**: 全ターン確定のトランザクション境界、secret の UI 非露出、INTERNET 権限なし、AI 出力の原子性をコードで確認。
- **実施しない / できない検証**: 実機起動、実 GGUF 推論、Mali-G615 Vulkan、長時間プレイ、UI インストルメンテーションテスト。
  これらは「未検証」と明記し、成功扱いにしない。

> 注: `/goal` の既定ルールはテスト実行を禁止しているが、本プロジェクト仕様 16 章が
> 「テストコードを用意し、実行できる環境では実施」「ビルド成功は実際の結果で確認」を明示的に要求しているため、
> プロジェクト固有指示を優先し、ビルドと自動テストのみ実行する。commit/push は別途承認を得る。

---

## 7. リスクと制約

| リスク | 対応 |
|---|---|
| Termux に Android SDK/NDK が無い | APK 検証は GitHub Actions。コアはローカルテスト |
| AGP/Kotlin/KSP/Compose の版整合 | 公式 llama.android 構成を基準に CI で検証。失敗時は llm-player 実績構成へ |
| llama.cpp v0.9.0 の API 変更 | 公式ヘッダで確認済みの batch/decode API を使用。CI でコンパイル検証 |
| CI ビルド時間（ネイティブ） | キャッシュ、ABI arm64 のみ、CPU 既定。Vulkan は任意 |
| 実機 GPU/Vulkan 未検証 | CPU フォールバックを既定、バックエンドを実行時表示、未検証と明記 |
| 実モデルが無い | 推論の実測は未検証。JSON/プロンプト/状態遷移はテストで担保 |
| 仕様が非常に広い | フェーズ分割し、各フェーズでビルド可能状態を維持。未実装は明示 |
| ストレージ/メモリ | コピー前空き容量確認、進捗/キャンセル、OOM 捕捉、ctx 調整 |

---

## 8. 承認が必要な事項

1. 本 PLAN の承認（実装開始の可否）。
2. GitHub リポジトリ作成と push の許可（CI ビルドに必須）。
   - 推奨: private `shiokunn/trpg`（Actions 無料枠に注意）
   - 代替: public（Actions 無料・ビルド時間無制限だがコード公開）
   - 代替: リポジトリ作成なし（ローカルの core テストのみ、APK ビルド検証不可）
3. Vulkan 対応の扱い: まず CPU で完成させ、Phase 7 に任意ビルドフラグとして追加（推奨）。
4. アプリ名/パッケージの既定値（「TRPGオフライン」/ `io.github.shiokunn.trpg`）で進めてよいか。

承認後は `Status: IN_PROGRESS` に更新し、Phase 0 から自律的に実行する。

---

# Phase 8 — llama.cpp v0.9.0 推論エンジン改善

**Status: IN_PROGRESS（実装 P0-1〜P1-2 完了 / クラウド Android ビルド検証済み / P1-3・実機は未実施）**
**開始日: 2026-11-10**
**実装完了日: 2026-11-10**
**対象: 既存のアプリ本体・D6/状態変更ルールは変更しない。推論層とその状態表示、検証コードのみ。**

## 8.1 ゴールと非ゴール

### ゴール

1. GGUF 内の Jinja チャットテンプレートを llama.cpp v0.9.0 の `common`/minja で評価し、
   Gemma 4、Qwen2.5/Qwen3、LFM2/2.5 を含むモデルで正しいプロンプトを作る。
2. テンプレートが無い場合と、テンプレートがあるが評価できない場合を区別する。
   **テンプレートがあるのに ChatML へ黙ってフォールバックしない。**
3. CPU の永続 `ggml_threadpool` と実行時の CPU affinity を使い、対象端末では初期設定 A
   （高クロック4コア / 4スレッド）で動かす。利用できない場合は安全に既定へ戻す。
4. キャンセル要求とキャンセル完了を区別し、待機中・プロンプト処理中・生成中の
   各段階で取りこぼさない。`llama_set_abort_callback` を使う。
5. JNI の文字列変換を UTF-16 経由にし、日本語・絵文字・非BMP・トークン境界で分割された
   UTF-8 を壊さない。
6. BOS の二重付与、Qwen3 の `enable_thinking=false`、stop 文字列、UI のテンプレート表示を
   テンプレート実際の評価結果に合わせる。
7. 上記を検証するゴールデンテストと、A/B/C を比較できる計測手順を用意する。

### 非ゴール

- llama.cpp v0.9.0 から LiteRT-LM への移行。
- GGUF ファイルの書き換え、モデルごとの無根拠な分岐。
- アプリの D6・状態変更・記憶・バックアップ・画面構成の変更。
- ADB 実機テスト（今回は行わない）。
- 未承認の commit / push / CI 実行。
- Kyalulu の停止原因を証拠なしに断定すること。

## 8.2 Phase A の読み取り専用調査結果

### テンプレート

- 現行 `nativeFormatChat` は `llama_chat_apply_template` を呼ぶ。
  v0.9.0 の組み込み検出は Kyalulu の Gemma 4 系テンプレートを認識せず、`-1` を返す。
  その結果 `LlamaCppEngine` の `fallbackChatPrompt` が **黙って** ChatML を組み立てている。
  `hasChatTemplate` は GGUF メタデータの有無だけを見ており、適用可能かは表していない。
- v0.9.0 には `common/chat.h` / `common/chat.cpp` の `common_chat_templates_init` /
  `common_chat_templates_apply` と minja があり、Gemma 4 専用の
  `common_chat_params_init_gemma4` も存在する。
- `common_chat_prompt` はテンプレート部分とユーザー入力部分を `is_input` で分離している。
  この情報を使わず文字列に戻すと、ユーザー入力中の特殊トークンをテンプレート構文として
  解釈し得る。テンプレート適用とトークン化を分けて扱う必要がある。
- `common_chat_templates_init` はテンプレートが無い場合に ChatML を既定として持つ。
  そのため「テンプレートが無い」と「テンプレートがある」を
  `common_chat_templates_was_explicit` で区別する必要がある。
- `enable_thinking` はテンプレートが対応している場合だけ意味を持つ。
  Qwen3 は `false` で空の think ブロックを出力する。Gemma 4 も `false` で
  思考チャネルを空にできる。モデル名ではなくテンプレート能力で判断する。
- 現行 `nativeGenerate` は `add_special=true` 固定でトークン化し、BOS の正規化を
  テンプレート側と共有していない。stop は EOG だけで、`additional_stops` を見ていない。

### スレッドプール / affinity

- `llama_attach_threadpool` は存在するが、現行 JNI から呼ばれていない。
  v0.9.0 ではコンテキストにプールが無い場合、グラフごとに使い捨てプールを作って解放する。
- `ggml_threadpool_new` / `ggml_threadpool_free` は CPU backend の
  `ggml_backend_reg_get_proc_address` から取得できる。
- 対象端末は cpu0–3 が little、cpu4–6 が big、cpu7 が prime で、
  高クロック群は **4コア**。現行 `defaultThreads()` の「big 3 + prime で6」という
  コメント・上限6は端末構成と一致していない。
- 初期設定 A は cpu4–7 / 4スレッド。比較用 B は cpu4–7 / 6スレッド、
  C は cpu2–7 / 6スレッド。固定値を前提にせず、sysfs の max frequency 等から
  実行時に高クロック群を検出し、検出できない場合は affinity なしで安全に動かす。

### キャンセル

- `nativeGenerate` はモデル mutex を取った後に `g_cancel` を false にするため、
  mutex 待ちやテンプレート整形中のキャンセルが消える。
- `llama_set_abort_callback` は未設定。プロンプト処理・生成のチェックは
  バッチ境界とトークン境界だけで、abort callback によるグラフ途中の中断がない。
- `formatChat` は `withContext(Dispatchers.Default)` の外で呼ばれ、UI スレッドを
  ブロックし得る。
- UI は「生成を中止」を押した後も同じ running 表示で、要求と完了を区別していない。

### UTF-8 / JNI

- `jstring_to_string` は `GetStringUTFChars`（Java modified UTF-8）を使い、
  `NewStringUTF` で戻す箇所がある。絵文字・非BMP・トークン分割された不完全な
  UTF-8 で壊れる可能性がある。
- ストリーミングの `JvmCallback::emit` はトークンごとの `NewStringUTF` なので、
  マルチバイト文字がトークン境界をまたぐと不正な文字列になる。
- 最終応答とストリーミングで同じデコーダを使い、途中の不完全バイトを保持して
  最後に flush する必要がある。

### 停止事象

- Kyalulu の停止は native の `generate start` より前で起きており、テンプレート整形・
  `llama_tokenize`・`llama_memory_clear` のどこかは未確定。今回の計測で切り分ける。
- v0.9.0 の `llama_tokenize` に安全な中断コールバックは無い。トークン化を独自実装して
  互換性を壊すことはしない。分割できない場合は制約として明記する。

### ビルド

- 現行は `LLAMA_BUILD_COMMON=OFF`。Phase 8 では `LLAMA_BUILD_COMMON=ON` とし、
  `llama-common` を `trpg_llama` にリンクする。
- `common` は Android で未検証。ビルドできない場合は、ChatML への暗黙フォールバックで
  ごまかさず、`common`/minja の最小構成を切り出して修正する。それも不可能なら
  実装を止めて報告する。

## 8.3 設計方針

### D1. テンプレートは `common`/minja を第一候補にする

- `nativeLoadModel` 後に `common_chat_templates_init(model, "")` を作る。
- `common_chat_templates_was_explicit` で GGUF テンプレートの有無を判定する。
- 読み込み時に代表メッセージ（system + user、日本語を含む）で一度だけ apply し、
  実際に描画できるかを確認する。成功を `applied`、失敗を `unsupported` とする。
- モデルの読み込み自体は維持する。テンプレート不能でもモデルを読み込み、
  生成時に明示的なエラーを返す（UI は `unsupported` を表示）。
- **テンプレートがあるのに apply が失敗した場合、ChatML で生成しない。**
- **GGUF にテンプレートが無い場合だけ**、既存互換のための ChatML を明示的な
  ポリシーとして許可する。ログと UI に「なし（ChatML フォールバック）」と表示する。
  この扱いは承認時に確認する（D6 参照）。

### D2. プロンプトの parts を保ってトークン化する

- 文字列に戻して `parse_special=true` で全体をトークン化する経路は、ユーザー入力中の
  `<|im_end|>` 等をテンプレート構文として解釈する。
- `common_chat_prompt.parts` を使い、テンプレート由来の part は `parse_special=true`、
  ユーザー入力由来の part は `parse_special=false` でトークン化する。
- 既存の `nativeFormatChat` / `nativeGenerate` は互換用に残し、チャット生成用に
  新しい JNI 経路を追加する。既存シンボルは削除・改名しない。
- BOS は common の正規化（モデルの `add_bos` に応じてテンプレート先頭 BOS を除去）と
  整合させ、プロンプト全体で一度だけ付与する。EOS の扱いもテンプレートと
  トークナイザの設定を一致させ、二重付与をテストする。

### D3. thinking はテンプレート能力で無効化する

- `common_chat_templates_support_enable_thinking` 相当の能力確認を行う。
- 対応している場合のみ `inputs.enable_thinking = false` を設定する。
- モデル名による分岐はしない。Qwen3 の空 think ブロック、Gemma 4 の空 thought
  チャネルをゴールデンで確認する。

### D4. stop は EOG + `additional_stops`

- `common_chat_params.additional_stops` を生成側へ渡す。
- 各トークン後に EOG と stop 文字列を確認し、stop 文字列自体は出力に含めない。
- ストリーミングでは stop 文字列がトークン境界をまたぐ場合があるため、
  出力バッファの末尾で判定する。必要な場合だけ数トークン保留する。

### D5. 永続 threadpool と affinity

- モデル読み込み時に `ggml_threadpool_new`（batch 用も必要なら別）を作り、
  `llama_attach_threadpool(ctx, tp, tp_batch)` する。
- 解放時は `llama_detach_threadpool` → `llama_free` → `ggml_threadpool_free` の順。
  proc address が取れない場合はプールを作らず、llama.cpp の既定動作に安全に戻す。
- profile A: 高クロック4コア、4スレッド、`strict_cpu=true`。
- profile B: 高クロック4コア、6スレッド、`strict_cpu=false`（比較用）。
- profile C: cpu2–7、6スレッド、`strict_cpu=false`（比較用）。
- 既定は A。B/C は Gradle プロパティ / ネイティブ定義で切り替えられるようにし、
  既定 APK の挙動を変えない。affinity 検出失敗時は A のスレッド数だけを使い、
  affinity なしでログに明示する。
- `n_threads` / `n_threads_batch` とプールのスレッド数を一致させ、
  バッチ処理でも同じプールを使う。

### D6. テンプレートなしの扱い（承認対象）

提案は次の二段階:

| GGUF の状態 | 生成 | UI |
|---|---|---|
| テンプレートあり・apply 成功 | minja の結果を使用 | 「あり（対応済み）」 |
| テンプレートあり・apply 失敗 | **明示エラー。ChatML に落とさない** | 「あり（未対応）」 |
| テンプレートなし | 明示的に許可した ChatML フォールバック | 「なし（ChatMLフォールバック）」 |

「テンプレートなし」を許可しない厳格設定を既定にする選択肢もある。
既存モデルとの互換性を優先するなら上表を推奨する。承認時に決定する。

### D7. キャンセル

- Kotlin 側で単調増加する generation ID を発行し、ネイティブに渡す。
- ネイティブは「実行中 ID」と「キャンセル要求 ID」を別 atomic で保持する。
- モデル mutex 取得後、テンプレート整形後、トークン化後、各バッチ後、
  各トークン後に ID を確認する。mutex 待ち中の要求も ID で拾う。
- `llama_set_abort_callback` に「現在の生成 ID がキャンセル要求済みか」を返す
  コールバックを設定し、`llama_process` の途中でも中断できるようにする。
- `llama_process` の失敗がキャンセル由来なら `cancelled=true`、その他の失敗なら
  error として返す。キャンセルを「正常完了」と混同しない。
- `formatChat` / テンプレート整形 / トークン化を `Dispatchers.Default` へ移す。
- UI の running 表示に `cancelling` を追加し、要求中と完了を区別する。
  これは Phase 8 に直接関係する最小限の UI 変更に留める。

### D8. UTF-8 / UTF-16

- `jstring_to_string` を `GetStringChars`（UTF-16）ベースに置き換え、
  サロゲートペアを正しい UTF-8 へ変換する。不正なサロゲートは U+FFFD にする。
- 戻り値は UTF-8 → UTF-16 のヘルパーを作り、`NewString` で返す。
  `NewStringUTF` は全廃する。
- ストリーミングは `Utf8StreamDecoder` を導入し、不完全な末尾バイトを保持する。
  最終出力も同じデコーダを通し、途中と最終で文字列が一致するようにする。
- 変換ロジックは JNI から独立したヘッダ/ソースに置き、ホストテスト可能にする。

### D9. 状態表示

- `ModelInfo` に以下を追加する（既存フィールドは維持）:
  `chatTemplateStatus`（`applied` / `unsupported` / `none` / `chatml_fallback`）、
  `chatTemplateError`、`chatTemplateSupportsThinking`、`chatTemplateAddBos`。
- `hasChatTemplate` は互換のため残し、UI は新しい status を表示する。
- テンプレート評価に失敗したモデルは、生成開始前に日本語で理由を返す。

### D10. 計測

- 既存の `generate start` / `generate` ログに加え、次を記録する:
  template init/apply、tokenize（1回目/2回目）、memory clear、prompt decode、
  first token、generation、threadpool 作成/attach、affinity mask、profile。
- ログは結果を保証しない。Kyalulu の停止がどこで起きるかを切り分けるための証拠にする。
- A/B/C は同じプロンプト・同じモデル・同じ設定で比較し、端末温度/周波数も記録する。
  今回は計測を実施しない。

## 8.4 実装順序と完了条件

### P0-1 テンプレート共通基盤（最優先）

変更候補:
- `app/build.gradle.kts`（`LLAMA_BUILD_COMMON=ON`、必要な定義）
- `app/src/main/cpp/CMakeLists.txt`（`llama-common`、include）
- `app/src/main/cpp/llama_jni.cpp`（template 初期化・状態・format 経路）
- `LlamaCppNative.kt` / `InferenceEngine.kt` / `LlamaCppEngine.kt`
- `ModelsScreen.kt`（status 表示）

完了条件:
- `common` が Android NDK でコンパイルできる。
- Gemma 4 / Qwen3 / LFM2.5 のテンプレートを apply でき、ChatML へ落ちない。
- 未知テンプレートは fail closed。
- テンプレートなしのポリシーが承認内容どおり。
- 既存のモデル読み込み API と旧 JNI シンボルが維持される。

### P0-2 永続 threadpool / affinity

変更候補:
- `llama_jni.cpp`（pool 作成・attach・detach・解放、proc address、検出）
- `LlamaCppEngine.kt`（profile A のスレッド数）
- `app/build.gradle.kts` / CMake（B/C の比較用切り替え。既定 A）

完了条件:
- 対象端末で 4 スレッド / cpu4–7 のプールが attach され、ログで確認できる。
- API 不在時はプールを作らず、クラッシュせず既定動作に戻る。
- unload / 再load でリーク・二重解放がない。
- 既定 A の挙動が承認済みで、B/C は比較時だけ選択できる。

### P1-1 キャンセル

変更候補:
- `llama_jni.cpp`（ID、abort callback、checks）
- `LlamaCppEngine.kt` / `LlamaCppNative.kt`（ID と runCatching）
- `ViewModels.kt` / 生成中 UI（cancelling）

完了条件:
- mutex 待ち中、テンプレート整形中、トークン化中、prompt decode 中、生成中の
  各点でキャンセルが要求どおり伝わる。
- キャンセル結果と失敗結果が JSON で区別される。
- `formatChat` が UI スレッドをブロックしない。
- 連続 generate で前の cancel が次に漏れない（ID テスト）。

### P1-2 UTF-8 JNI

変更候補:
- `llama_jni.cpp` と新規 `utf8` ヘルパー
- `JvmCallback`
- 必要なら `LlamaCppNative.kt` の戻り値処理

完了条件:
- 日本語・絵文字・非BMP・サロゲートペア・不正バイトの round-trip テスト。
- トークン境界で分割された絵文字がストリーミングで壊れない。
- 最終出力とストリーミングの文字列が一致する。
- `NewStringUTF` / `GetStringUTFChars` が JNI から除去される。

### P1-3 テスト / 状態

- Kotlin: `ModelInfo` JSON 解析、status、キャンセル状態の単体テスト。
- C++ host: `common_chat_templates_*` と `llama_tokenize` を使うゴールデン。
  - Gemma 4 公式テンプレート + gemma4 vocab
  - Qwen3 テンプレート + qwen2/3 vocab
  - LFM2.5 テンプレート（format + special token 検証）
  - 未知 / 不正テンプレートの fail closed
  - テンプレートなしのポリシー
  - BOS が 1 個、`enable_thinking=false`、stop、日本語/emoji
  - `common_chat_prompt.parts` の入力 part が特殊トークンとして解釈されないこと
- 実モデル（Kyalulu）のテンプレートを使うテストは、実機/モデルが利用できる
  承認済みの検証段階で追加する。現時点では未取得を明記する。
- CI には host テスト用の独立 job を追加する。実行は承認後。

### P2 計測 / ベンチマーク

- A/B/C のビルドまたはネイティブ設定を用意し、同じ条件で比較する。
- 計測結果が出るまで既定を B/C に変更しない。
- Kyalulu の停止が再現した場合は、ログの最後の段階を報告し、原因を断定しない。

## 8.5 テスト / 検証の現時点の状態

**この Phase A ではコード変更・テスト実行・CI 実行・ADB 接続のいずれも行っていない。**
以下は承認後の計画であり、成功したとは扱わない。

- ローカルで実行可能: 純 Kotlin の `:core` テスト、C++ ホストテスト（環境がある場合）。
- CI で実行予定: `:core:test`、`:app:testDebugUnitTest`、`:app:assembleDebug`、
  新設のテンプレート host job。
- 実行しない: ADB 実機、実 GGUF の生成、A/B/C 実測、Kyalulu の停止再現。
- 実機未検証の項目は `docs/VERIFICATION.md` に「未検証」と明記する。
- ビルドやテストが失敗した場合は、失敗ログと未解決点をそのまま報告する。

## 8.6 リスク

| リスク | 対応 |
|---|---|
| `common` の Android ビルド不整合 | 最初に最小の CMake/CI で検証。失敗時は common の chat/minja 最小ターゲットへ。ChatML で代替しない |
| minja が実モデルの独自テンプレートを評価できない | 読み込み時に fail closed。テンプレート原文とエラーを記録し、実モデルで再検証 |
| テンプレートなしモデルの互換性 | D6 の明示ポリシーを承認。UI/ログで可視化 |
| BOS/EOS の二重付与 | common の正規化と実トークン列をゴールデンで確認 |
| threadpool API の差異 / affinity 失敗 | proc address と sysfs を確認し、無い場合はプールなし・affinity なしで安全に戻す |
| abort callback の粒度が行列積内部までは届かない | トークン/バッチ境界のチェックを併用。限界を docs に明記 |
| `llama_tokenize` が中断不能 | 独自トークナイザを作らない。前後チェックと計測に留め、制約を明記 |
| UTF-8 変換の不正入力 | U+FFFD へ置換し、ラウンドトリップテスト。JNI 例外を残さない |
| formatChat の UI ブロック | `Dispatchers.Default` へ移動。キャンセル ID と併用 |
| Kyalulu の停止原因不明 | template/tokenize/memory clear の時刻を計測。原因究明前に最適化を断定しない |
| 既存 UI への影響 | テンプレート status と cancelling 以外の画面変更をしない |
| 検証不足のままの成功報告 | 実行していないテスト/実機/ベンチを明示。失敗は失敗として報告 |

## 8.7 承認をお願いしたい事項

1. 本 Phase 8 計画と、P0-1 → P0-2 → P1-1 → P1-2 → P1-3 → P2 の順序。
2. `LLAMA_BUILD_COMMON=ON` と `llama-common` リンクを採用すること。
3. D6 の「テンプレートなしのみ ChatML フォールバック、テンプレートありの失敗は
   fail closed」という方針。
4. 既定を profile A（高クロック4コア / 4スレッド）とし、B/C は比較用に残すこと。
5. 実装後の検証は、承認された範囲のビルド・テストのみ行い、ADB 実機テストは
   別途承認とする方針。
6. commit / push / CI 実行は、実装レビュー後に別途承認を得ること。

## 8.8 既存実装の再利用調査（Phase A-2）

**Status: 調査完了 / 実装は未着手**

### LLM-PLAYER

- リポジトリ: `/data/data/com.termux/files/home/llm-player`（`origin/main` = `373135c`）。
- 指定コミット `4f3293f` は **コードを直接変更していない**。変更されたのは
  `.github/workflows/apply-stop-cancel-fix-v2.yml` だけで、ワークフロー内の
  Python パッチ文字列が `native-lib.cpp` へ decoder を挿入する仕組みになっている。
  同コミットの埋め込みコードは `append_complete_utf8_to_utf16` という初期版で、
  不完全な末尾を保留し、最終変換時に U+FFFD を足す。
- 実質的な decoder は後続コミット `6571062`（`feat: implement incremental UTF-8 to UTF-16 decoder`）
  で追加された `append_valid_utf8_to_utf16` と `new_jstring_from_utf8`。
  現在の `native-lib.cpp` の該当実装はこのコミットの blame になっている。

#### `6571062` の実装内容

- `append_valid_utf8_to_utf16(input, consumed, output, is_final_flush)`:
  - 1〜4バイトの標準 UTF-8 を判定し、不完全な列は `is_final_flush=false` なら
    `consumed` を止めて保留する。
  - 最終 flush で不完全な場合は U+FFFD に置換する。
  - overlong（2/3/4バイト）、UTF-16 surrogate 相当（3バイト）、
    4バイトの範囲外（U+110000 超）も U+FFFD にする。
  - 4バイトは `jchar` のサロゲートペア（0xD800+ / 0xDC00+）に展開する。
- `new_jstring_from_utf8(env, input)`:
  - decoder で UTF-16 に変換し、`env->NewString(utf16.data(), size)` で返す。
  - 空文字は `NewString(nullptr, 0)` を使う。JNI 仕様上 `unicodeChars` が
    `NULL` のときは `len == 0` が許容される。
- ストリーミング側は `pending_utf8` を JNI 呼び出しごとに持ち、
  トークン piece を追記してから complete な前缀だけを UTF-16 化して
  `NewString` → `CallVoidMethod` → `DeleteLocalRef` する。
- 最終 flush は `!cancel` のときだけ実行し、`pending_utf8.clear()` する。
- `jclass cb_class` は `GetObjectClass` の結果を `DeleteLocalRef` する。
  `jstring jpiece` も毎回 `DeleteLocalRef` する。

#### 再利用時の注意点 / 既存実装の不足

- **入力側は未修正**。LLM-PLAYER は prompt / session_id に
  `GetStringUTFChars`（Modified UTF-8）を使い続けている。日本語 BMP は
  実用上通る場合が多いが、絵文字・非BMP・サロゲートペア・埋め込み NUL は
  正しい標準 UTF-8 にならない可能性がある。
- **例外時の JNI 安全性が完全ではない**。`NewString` が OOM で NULL の
  場合に例外を残したまま後続の JNI 呼び出しへ進み得る。
- **キャンセル時の pending 扱いが一貫していない**。現在の
  `nativeGenerateStream` は cancel 時に pending を flush せず、
  `raw_text` にも追加しない（途中の不完全バイトが最終出力から消える）。
  一方、4f3293f の初期パッチは `raw_text.append(pending_utf8)` していた。
  どちらが正しいかは仕様決定が必要。本プロジェクトでは「ストリーミングと
  最終出力を一致させる」ことを優先し、cancel 時も flush して U+FFFD を
  出力する方針を提案する。
- **RAII ではない**。早期 return が多いが、主要パスは手動 release されて
  いる。本プロジェクトでは `GetStringChars` / `ReleaseStringChars` を
  RAII で管理する。
- **テストが無い**。`app/src/test` / `androidTest` に UTF-8 関連テストは
  見つからなかった。
- **BOS の参考にはできない**。`tokenize_prompt` は
  `add_special=false, parse_special=true` 固定で、common が BOS を除去した
  後のトークン化と整合していない可能性がある。本プロジェクトの D2 の
  BOS 方針は別途ゴールデンで決める。

### llama.cpp v0.9.0 の server / common

- `tools/server/server-common.cpp` の `validate_utf8()` は **末尾の
  不完全な UTF-8 列だけ**を検査し、先頭から最後の安全な位置を返す。
  内部の不正バイトや overlong / surrogate / 範囲外は検査しない。
- 同ファイルの `is_valid_utf8()` は先頭・継続バイト構造を検査するが、
  overlong / surrogate / 範囲外までは検査しない。
- `common/unicode.cpp` の `common_parse_utf8_codepoint()` は
  SUCCESS / INCOMPLETE / INVALID を返すが、overlong / surrogate /
  範囲外の完全な検証はしない。`common_utf8_is_complete()` は末尾の
  完成判定だけを行う。
- server は `server-context.cpp` で `slot.generated.text` 全体を
  `validate_utf8()` にかけ、不完全なら送信保留する。
  `server-task.cpp` は JSON 出力時に `validate_utf8` の長さへ truncate する。
  Android JNI にそのまま流用すると「保留」と「置換」の方針が異なる。
- server の `tests/test-server-component.cpp` と `tests/test-reasoning-budget.cpp`
  の UTF-8 ベクタは再利用可能（不完全 2/3/4 バイト、絵文字、孤立
  continuation byte、不正 lead など）。

### Android JNI 公式仕様

- Android の Java/Kotlin 文字列は UTF-16。`GetStringChars()` は
  UTF-16 ポインタと長さで取得し、`ReleaseStringChars()` が必須。
  UTF-16 は NUL 終端ではなく、長さも別途管理する。
- `NewString()` は UTF-16 から Java 文字列を作る。`unicodeChars == NULL`
  のときは `len == 0` が許容される。
- `NewStringUTF()` / `GetStringUTFChars()` は Modified UTF-8。
  標準 UTF-8 の 4 バイト列は 6 バイトの surrogate 表現を期待し、
  絵文字や不完全・不正なバイト列を渡すと CheckJNI で abort し得る。
- JNI local reference は native メソッドの実行中だけ有効。ループ内の
  `GetObjectArrayElement` 等は `DeleteLocalRef` するか
  `PushLocalFrame` / `PopLocalFrame` で管理する。
- JNI 呼び出し中に例外が pending の状態でほとんどの JNI 関数を呼んでは
  ならない。`NewString` の失敗も例外を確認する。

### 再利用・新規実装の判断

| 対象 | 判断 |
|---|---|
| UTF-8 → UTF-16 decoder | LLM-PLAYER `6571062` の `append_valid_utf8_to_utf16` をベースに流用。そのままコピーせず、テスト可能・例外安全・final flush 付きの `utf8` ヘルパーへ移植 |
| 不完全バイトの保留 | LLM-PLAYER の `pending_utf8` + `consumed` 方式をベース。生成中のバッファとしてクラス化 |
| 不正バイトの置換 | LLM-PLAYER の overlong / surrogate / range チェックを踏襲し、server のテストベクタを追加 |
| 入力側の UTF-16 → UTF-8 | LLM-PLAYER に無いため新規実装。`GetStringChars` + `GetStringLength` + `ReleaseStringChars` |
| 出力側の UTF-8 → UTF-16 | `new_jstring_from_utf8` をベースに、空文字・NULL・例外処理を整理 |
| JNI local reference | LLM-PLAYER の手動 delete を踏襲しつつ、文字列取得は RAII 化 |
| キャンセル時の flush | 新規に明示的な `flush(final=true)` を実装。cancel でも保留バイトを破棄せず U+FFFD にして整合させる |
| server `validate_utf8` | テストベクタと「末尾保留」の考え方の参考。本番 decoder にはより厳格な LLM-PLAYER 版を使う |
| `common` の `common_parse_utf8_codepoint` | 参考。完全な Unicode 検証をしていないため、decoder 本体には採用しない |
| テスト | LLM-PLAYER には UTF-8 テスト無し。server / common のベクタと新規 JNI round-trip テストを追加 |

### テストの再利用可能性

- そのまま再利用できるテストコードは無い（LLM-PLAYER に UTF-8 テスト無し）。
- 参考にできるベクタ:
  - `tests/test-server-component.cpp` の `validate_utf8` / `is_valid_utf8`
  - `tests/test-reasoning-budget.cpp` の `common_utf8_is_complete`
- 新規追加するテスト:
  - 2/3/4 バイトの正常・不完全・overlong・surrogate・範囲外
  - 絵文字がトークン境界で分割されたケース
  - 日本語 + 絵文字 + 非BMP の round-trip
  - cancel 時に incomplete なまま終わったケース
  - `jstring_to_utf8` / `new_string_utf8` の JNI ラウンドトリップ（CI では
    Android instrumentation が必要。今回は実施しない）

承認後は `Status: IN_PROGRESS` に変更し、P0-1 から小さな差分で実装する。

## 8.9 実装結果と検証状態（2026-11-10）

**Status: 実装完了 / クラウド Android ビルド検証済み（実機は未実施）**

### 実装した内容

- **P0-1 テンプレート**: `LLAMA_BUILD_COMMON=ON` と `llama-common` リンクを追加。
  `initialize_chat_templates_locked()` で `common_chat_templates_init` /
  `common_chat_templates_apply` の実プローブを行い、状態を
  `applied` / `none` / `unsupported` に三分割。`g_template.applied == false` の場合は
  `apply_chat_template_locked()` がエラーを返し **fail closed**（ChatML へ落ちない）。
  `common_chat_templates_was_explicit` で「テンプレート無し（none）」を区別。
  `common_chat_prompt.parts` の `is_input` に従ってユーザー入力を
  `parse_special=false` でトークン化（特殊トークンの誤解釈を防止、BOS は先頭 part のみ）。
  `nativeFormatChatEx` が status / stops / addBos / supportsThinking を返す。
  `ModelsScreen` が status を表示する。
- **P0-2 threadpool**: `ggml_threadpool_new/free` を backend の proc address から取得し、
  `llama_attach_threadpool` / `llama_detach_threadpool` で永続プールを attach。
  proc address が無い場合はプールを作らず既定へ安全に戻る。
  CPU affinity は sysfs の周波数から高クロック群を検出し、検出不能なら affinity なし。
  既定 profile A（4スレッド）。B/C は `-Ptrpg.threadProfile` の比較用。
  `LlamaCppEngine.defaultThreads()` が `BuildConfig.THREAD_PROFILE` から決定。
- **P1-1 キャンセル**: `AtomicLong` の generation ID、`llama_set_abort_callback`、
  prompt decode / 生成ループのバッチ境界チェック。Kotlin は ID を mutex 取得前に発行し、
  待ち中の要求も取りこぼさない。`GenerationStateGuard`（RAII）で例外経路でも ID を確実に解放。
  UI は `GameTurnUiState.cancelling` / `NewAdventureUiState.cancelling` を追加し、
  二重タップを防止、完了時に通常状態へ戻す。`formatChat` は suspend 化して
  `Dispatchers.Default` で実行。
- **P1-2 UTF-8**: 新規 `utf8_utils.{h,cpp}`。入力は `GetStringChars`（UTF-16）、
  出力は `NewString`。`NewStringUTF` / `GetStringUTFChars` は全廃。
  `Utf8StreamDecoder` がトークン境界で分割された UTF-8 を保持し、
  最終フラッシュで U+FFFD に置換。キャンセル時も flush するので
  ストリーミングと最終出力が一致する。
- **計測ログ（PLAN 8.4）**: `template_init=…ms` / `template_apply[chat]=…ms` /
  `tokenize[ex]=…ms` / `clear_ms=…` / `prompt_ms=…` / `threadpool_attach=…ms`。
  すべて ms 単位で統一。

### 修正したバグ

1. **テンプレート初期化が即 return していた**（重大）。`nativeLoadModel` が
   `g_model` / `g_ctx` のグローバル代入より前に `initialize_chat_templates_locked()` /
   `create_threadpool_locked()` を呼んでいたため、`g_model == nullptr` で抜けて
   テンプレートが一切初期化されず、全チャット生成が fail closed になっていた。
   グローバル代入を abort callback 直後に移動し、batch 初期化失敗時は
   `free_model_locked()` で全解放するよう修正。
2. **不完全な UTF-8 の最終フラッシュが1文字を複数の U+FFFD に分裂**。
   リードバイトだけ消費していたため。継続バイトまで1回で消費するように修正。
3. **不完全な保留バイトが次の文字と結合**していた。保留中の列が
   継続バイトで始まらない新規データに出会った時点で確定させるよう修正。
4. `LlamaCppNative.parseFormatResult` の `it.isNotBlank`（括弧欠落、コンパイルエラー）。
5. `formatChat` が非 suspend のまま `withContext` を使用（コンパイルエラー）。
   インターフェースを suspend 化（呼び出し元が無く互換影響なし）。
6. 未使用の `fallbackChatPrompt`（黙って ChatML を組み立てる旧経路）を削除。
   旧 `nativeGenerate()` は `nativeGenerateEx` へ委譲する1経路のみ。
7. 未使用の `kAllowChatmlFallback` / `ok_json` / 書き込みのみの `g_cancel_legacy` を削除。
8. `LOGI` の `%ld` に `long long` を渡していた形式不整合を `%lld` に修正。
9. `cancelling` が `onToken` で解除されて二重タップを許していたため、
   フェーズ遷移と結果処理でのみリセットするよう修正。

### 実施した検証

| 検証 | 結果 |
| --- | --- |
| JNI 13 関数の Kotlin 宣言 ↔ C++ 実装（引数個数・型） | 全一致（機械照合） |
| `llama_jni.cpp` ホスト構文検証（`-Wall -Wextra`、profile A/B/C） | 当方コード警告ゼロ（残る警告は llama.cpp 上流ヘッダ由来） |
| `utf8_utils.cpp` ホストコンパイル + 挙動検証 13ケース | 13/13 成功（日本語・絵文字・非BMP・分割・不完全末尾・overlong・surrogate・範囲外・孤立継続・clear） |
| エンジン Kotlin 3ファイルの単体コンパイル（`kotlinc` + core クラス） | 成功 |
| `gradle -p core build` | 成功 |
| llama.cpp v0.9.0 のシンボル実在確認（`common_chat_templates_*`、`llama_attach_threadpool`、`llama_set_abort_callback`、`common_chat_prompt.parts/is_input`、`additional_stops`、`llama-common` ターゲット） | すべて実在 |
| `llama_detach_threadpool` の実装確認（ポインタを nullptr 化するだけ） | detach 後に threadpool を解放する当方の順序が安全であることを確認 |

### クラウドビルド検証（2026-11-10）

GitHub Actions `Android CI`（`.github/workflows/android.yml`、run `42905312348`、
commit `6f1eef3`）で arm64-v8a のビルド検証を実施。**全ステップ成功**。

| 確認項目 | 結果 |
| --- | --- |
| `llama.cpp` | ビルド成功（`llama-chat.cpp` 等 665 タスク） |
| `llama-common` / minja | `common/chat.cpp`・`common/parsers/*`・`common/jinja/{lexer,parser,string,runtime,caps,value}.cpp` が NDK 29 でコンパイル成功 |
| `utf8_utils.cpp` | コンパイル成功 |
| JNI | `llama_jni.cpp` コンパイル・`libtrpg_llama.so` リンク成功。13 個の `Java_io_github_shiokunn_trpg_ai_engine_LlamaCppNative_*` シンボルがすべて exported |
| APK パッケージング | `app-debug.apk`（arm64-v8a のみ）に `libllama-common.so` / `libllama.so` / `libtrpg_llama.so` / `libggml*.so` が同梱 |
| `llama-common` → `llama` リンク | `libllama-common.so` の `DT_NEEDED` に `libllama.so` が入っていることを確認（upstream が宣言していない依存を `CMakeLists.txt` で明示した効果） |
| シンボル解決 | `libtrpg_llama.so` の全未定義シンボル（162）が、同梱 5 ライブラリ + `libc`/`libm`/`libdl`/`libandroid`/`liblog` で解決することを確認。アプリ固有の未解決シンボルなし |
| APK 署名 | `apksigner verify` 成功（v2 スキーム、`CN=Android Debug`） |
| 自動テスト | `:core:test` 89件・`:app:testDebugUnitTest` 5件、ともに failures 0 / ignored 0 |

### 未実施・未検証（重要）

- **CI host job / golden / A/B/C**: 既存の承認条件を守り、未承認のため未実行。
- **ADB 実機**: 未実施。テンプレート適用・キャンセル・絵文字ストリーミング・
  threadpool attach の実挙動は未確認。**ビルド成功と実機動作成功は別物**。
- 実モデル（Kyalulu）のテンプレートを使った検証はモデル未取得のため未実施。

### 未解決のリスク

- `minja` が実モデルの独自テンプレートを評価できるかは未確認（fail closed で安全側）。
- `BUILD_SHARED_LIBS=ON` + `LLAMA_BUILD_COMMON=ON` の組み合わせは upstream の
  既定（OFF）と異なるが、NDK 29 / CMake 3.31.6 でのビルド・パッケージングは確認済み。
