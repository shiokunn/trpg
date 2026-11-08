# PROGRESS — Android向け完全オフラインTRPGアプリ

**Status: IN_PROGRESS**
**Current phase: Phase 0 (repository foundation)**
**Last updated: 2026-11-08**

---

## 現在の状態

- 作業ディレクトリは空だった。既存コード・Git・AGENTS.md・PLAN/PROGRESS は無し。
- 環境・参考実装・llama.cpp v0.9.0 の現行 API を調査済み。
- `PLAN.md` を作成済み。
- **コード変更・リポジトリ作成・push は未実施。**

## 完了した調査

- [x] 作業ディレクトリ / Git / ファイル構成の確認（空、非 Git）
- [x] 開発指示ファイルの確認（無し）
- [x] Android プロジェクト構成の確認（無し）
- [x] GitHub Actions 設定の確認（無し）
- [x] 既存 llama.cpp 関連コードの調査
  - `~/llm-player`: 実績ある AGP/Gradle/NDK/CI 構成、JNI streaming/cancel の参考
  - 公式 llama.cpp v0.9.0 `examples/llama.android`: 推奨バージョン、`InferenceEngine`、JNI、CMake 設定
  - 現行 `llama.h`: batch/decode/sampler/chat template API を確認
- [x] Termux のツールチェーン確認（Java 21 / Gradle 9.6 / kotlinc / gh、SDK・NDK 無し）

## 完了したステップ

- Phase A-1: プロジェクト調査
- Phase A-2: ゴール分析・設計判断
- Phase A-3: `PLAN.md` 作成
- Phase A-4: `PROGRESS.md` 作成

## 進行中

- 承認待ち（WAITING_APPROVAL）

## 未着手（承認後）

- Phase 0: リポジトリ基盤 / CI
- Phase 1: アプリ基盤（Compose / Navigation / Room / DataStore）
- Phase 2: llama.cpp 統合 / モデル管理
- Phase 3: D6 ゲームエンジン
- Phase 4: AI ゲームマスター
- Phase 5: 物語記憶
- Phase 6: 総合 TRPG 機能
- Phase 7: 品質向上

## 変更したファイル

- `PLAN.md`（新規）
- `PROGRESS.md`（新規）

## 既知の問題・制約

- Termux に Android SDK/NDK が無く、ローカルでの APK ビルドは不可。CI 検証が必須。
- 実機・実 GGUF モデル・Mali-G615 Vulkan はこの環境では検証できない。
- GitHub リポジトリ作成 / push は未承認のため未実施。

## 次のアクション

- ユーザーの PLAN 承認および GitHub リポジトリ方針の回答を待つ。
- 承認後: `PLAN.md` を `IN_PROGRESS` に更新し、Phase 0 から開始。
