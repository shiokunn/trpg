# PROGRESS — Android向け完全オフラインTRPGアプリ

**Status: IN_PROGRESS**
**Current phase: Phase 2 (llama.cpp integration / model management)**
**Last updated: 2026-11-08**

---

## 現在の状態

- リポジトリ: `https://github.com/shiokunn/trpg`（private, `main`）
- Phase 0（基盤・CI）完了。GitHub Actions で `:core:test` / `:app:testDebugUnitTest` / `:app:assembleDebug` を実行。
- Phase 1（Room 15テーブル・リポジトリ・Compose 画面骨格）実装済み。
- Phase 2 進行中:
  - [x] `LlamaCppEngine` / `LlamaCppNative` / `InferenceEngine` 抽象（ストリーミング・キャンセル・バックエンド情報）
  - [x] `ModelFileManager`（SAF取り込み・進捗・キャンセル・空き容量確認・GGUFマジック検証）
  - [x] モデル管理画面（一覧・取り込み・選択・読み込み・削除・推論パラメータ・メモリ注意）
  - [x] JNI `app/src/main/cpp/llama_jni.cpp`（v0.9.0 API）
  - [x] CMake / Gradle NDK 統合（arm64-v8a, GGML_BACKEND_DL, CPU_ALL_VARIANTS）
  - [x] CI での llama.cpp 取得・NDK/CMake 導入
  - [ ] CI のネイティブビルド成功（v0.9.0 `llama_load_mode` 対応を反映して再実行中）
  - [ ] 実機での読み込み・生成・キャンセル検証（この環境では不可）
- Phase 3 進行中:
  - [x] D6ルール・戦闘・キャラクター作成検証（既存）
  - [x] StateChange モデル / バリデータ / アプライヤ（原子的適用、範囲クランプ、ID検証）
  - [x] GMレスポンス厳格パーサ（未知キー拒否、選択肢3〜4、難易度4/6/8/10/12/14、記憶検証、修復指示）
  - [x] 世界・シナリオ・キャラクター草案パーサ（厳格JSON、CharacterRules連携）

## 完了したステップ

- [x] 調査（既存実装・llama.cpp v0.9.0 API・ツールチェーン）
- [x] `PLAN.md` 作成・承認
- [x] Phase 0: Git / private repo / Gradle wrapper / CI
- [x] Phase 1: Core ドメインモデル・D6ルール・戦闘・テスト21件
- [x] Phase 1: Room 15テーブル・DAO・マッパー・リポジトリ・DataStore
- [x] Phase 1: テーマ・ナビゲーション・Home/Saves/Game/Character/World/Journal/Settings/Models
- [x] CI 修正: Gradle wrapper タイムアウト、enum マッピング、combine 型推論
- [x] Phase 2: Kotlin 推論層・モデルファイル管理・モデル管理UI

## 進行中

- Phase 2: ネイティブビルド統合（CMake / NDK / CI）

## 次にやること

1. CI で arm64-v8a APK ビルド成功を確認（JNI の v0.9.0 API 修正を push 済み）
2. Phase 3: キャラクター作成UIとターン確定リポジトリ経路
3. Phase 4: PromptBuilder・GMターンパイプライン・1回修復リトライ・失敗時の状態不変
4. Phase 5: 4層記憶、Phase 6: 総合TRPG機能、Phase 7: 品質向上

## 既知の問題・制約

- Termux に Android SDK/NDK が無く、ローカルでの APK/ネイティブビルドは不可。CI 検証が必須。
- 実機・実 GGUF モデル・Mali-G615 Vulkan はこの環境では検証できない。未検証事項は完了報告に明記する。
- `NewAdventureScreen` と `GameScreen` のターン進行は未実装（Phase 3–4）。

## 検証済み

- `./gradlew -p core test`: コアテスト全件 green（ローカル、ルール21 + 状態変更18 + GMパーサ13 + 草案パーサ10）
- GitHub Actions: `:core:test` / `:app:testDebugUnitTest` は green。`assembleDebug` は JNI の API 不一致2件を修正して再検証中。
- 未検証: 実機推論、GGUF読み込み、Vulkan、Room 実マイグレーション（DB未出荷のため v1 のみ）
