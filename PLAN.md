# PLAN — Android向け完全オフラインTRPGアプリ

**Status: IN_PROGRESS**
**Created: 2026-11-08**
**Working directory: `/data/data/com.termux/files/home/trpg`**

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
