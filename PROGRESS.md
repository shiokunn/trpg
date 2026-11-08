# PROGRESS — Android向け完全オフラインTRPGアプリ

**Status: IN_PROGRESS**
**Current phase: Phase 4 (GMターンパイプライン / 冒険開始)**
**Last updated: 2026-11-08**

---

## 現在の状態

- リポジトリ: `https://github.com/shiokunn/trpg`（private, `main`）
- Phase 0（基盤・CI）完了。GitHub Actions で `:core:test` / `:app:testDebugUnitTest` / `:app:assembleDebug` を実行。
- Phase 1（Room 15テーブル・リポジトリ・Compose 画面骨格）実装済み。
- Phase 2 完了:
  - [x] `LlamaCppEngine` / `LlamaCppNative` / `InferenceEngine` 抽象（ストリーミング・キャンセル・バックエンド情報）
  - [x] `ModelFileManager`（SAF取り込み・進捗・キャンセル・空き容量確認・GGUFマジック検証）
  - [x] モデル管理画面（一覧・取り込み・選択・読み込み・削除・推論パラメータ・メモリ注意）
  - [x] JNI `app/src/main/cpp/llama_jni.cpp`（v0.9.0 API）
  - [x] CMake / Gradle NDK 統合（arm64-v8a, GGML_BACKEND_DL, CPU_ALL_VARIANTS）
  - [x] CI での llama.cpp 取得・NDK/CMake 導入
  - [x] CI のネイティブビルド成功（run 42481150919, arm64-v8a Debug APK）
  - [ ] 実機での読み込み・生成・キャンセル検証（この環境では不可）
- Phase 3 完了:
  - [x] D6ルール・戦闘・キャラクター作成検証（既存）
  - [x] StateChange モデル / バリデータ / アプライヤ（原子的適用、範囲クランプ、ID検証）
  - [x] GMレスポンス厳格パーサ（未知キー拒否、選択肢3〜4、難易度4/6/8/10/12/14、記憶検証、修復指示）
  - [x] 世界・シナリオ・キャラクター草案パーサ（厳格JSON、CharacterRules連携）
- Phase 4 進行中:
  - [x] PromptBuilder（優先度付きトークン予算・切り詰め・履歴トリム・GM専用秘密の明示）
  - [x] `GmTurnService`（判定→プロンプト→ストリーミング→厳格解析→1回修復→状態検証→単一トランザクション確定）
  - [x] ターン失敗・キャンセル時に保存データを変更しない経路
  - [x] 選択肢の永続化（`CHOICES` イベント）と GameScreen の選択肢・自由行動・ストリーミング・中止UI
  - [x] `NewAdventureScreen`（AI自動生成 / ユーザー設定+AI補完、世界・導入・主人公の確認、保存）
  - [x] `AdventureAssembler` と単体テスト（WorldDraft → World/Location/Npc/Quest、初期HP、ID一意性、戦闘値正規化）
  - [ ] 世界・導入の事後編集UI（現状は再生成と入力条件の変更で対応）
  - [ ] 実機でのターン生成・修復リトライ・キャンセル検証

## 完了したステップ

- [x] 調査（既存実装・llama.cpp v0.9.0 API・ツールチェーン）
- [x] `PLAN.md` 作成・承認
- [x] Phase 0: Git / private repo / Gradle wrapper / CI
- [x] Phase 1: Core ドメインモデル・D6ルール・戦闘・テスト21件
- [x] Phase 1: Room 15テーブル・DAO・マッパー・リポジトリ・DataStore
- [x] Phase 1: テーマ・ナビゲーション・Home/Saves/Game/Character/World/Journal/Settings/Models
- [x] CI 修正: Gradle wrapper タイムアウト、enum マッピング、combine 型推論、JNI v0.9.0 API
- [x] Phase 2: Kotlin 推論層・モデルファイル管理・モデル管理UI・ネイティブビルド
- [x] Phase 3: 状態変更エンジン・GM/草案パーサとテスト
- [x] Phase 4: PromptBuilder とテスト、GMターンパイプライン
- [x] Phase 4: 冒険開始画面（世界・キャラクター生成、確認、保存）

- Phase 5 進行中:
  - [x] 短期記憶（直近チャット）・長期記憶（重要度/関連度）・世界状態・NPC記憶の4層をプロンプトへ供給
  - [x] `MemorySelector`（重要度・新しさ・未解決・伏線・行動キーワードの関連度で選別、上限つき）とテスト6件
  - [x] GM専用 secret をプレイヤー画面（履歴・世界情報）から除外
  - [x] NPC記憶を世界情報画面に表示
  - [ ] 記憶の競合記録方針の明文化・テスト（現状は上書きせず全件保持）

- Phase 6 進行中:
  - [x] JSONエクスポート/インポート（厳格検証・ID衝突検査・単一トランザクション・失敗時は既存データ不変）
  - [x] 戦闘ラウンドエンジン（`CombatRoundEngine`）とテスト5件、GameScreen の戦闘カード・攻撃ボタン
  - [x] 所持品の装備/解除（攻撃力・防御力に反映）
  - [x] クエスト進捗・場所・NPC・履歴・XP/レベル表示（既存画面を実データに接続）
  - [ ] 場所の追加・編集、クエストの手動追加、アイテム数量変更
  - [ ] 戦闘終了後のGM描写連携（現状はアプリ生成の戦闘ログ）

## 進行中

- Phase 6 仕上げと Phase 7

## 次にやること

1. Phase 7: 品質向上（アクセシビリティ、空状態、エラー表示、画面回転、OOM通知）
2. README と検証レポートの整備（実機未検証項目の明記）
3. CI で全機能のビルドとテストを最終確認

## 既知の問題・制約

- Termux に Android SDK/NDK が無く、ローカルでの APK/ネイティブビルドは不可。CI 検証が必須。
- 実機・実 GGUF モデル・Mali-G615 Vulkan はこの環境では検証できない。未検証事項は完了報告に明記する。
- Vulkan バックエンドは未ビルド（`-Ptrpg.vulkan=true` の配線のみ）。CPU を既定とする。
- 戦闘は状態変更（COMBAT_START/END）まで。ラウンド解決UIは Phase 6。

## 検証済み

- `./gradlew -p core test`: コアテスト全件 green（全75件: ルール21 + 状態変更18 + GMパーサ13 + 草案パーサ10 + プロンプト6 + 記憶選別6 + 他1）
- GitHub Actions run 42481150919: `:core:test` / `:app:testDebugUnitTest` / `:app:assembleDebug`（arm64-v8a）すべて成功
- GitHub Actions run 42481907089: GMターンパイプライン込みで `:app:assembleDebug`（arm64-v8a）成功
- `AdventureAssemblerTest`（app unit test）: 5件（マッピング、初期HP、ID一意性、戦闘値正規化、タイトル補完）
- 未検証: 実機推論、GGUF読み込み、Vulkan、Room 実マイグレーション（DB未出荷のため v1 のみ）、ターン生成・修復リトライ・キャンセルの実行時挙動
