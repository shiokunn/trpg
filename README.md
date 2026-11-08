# TRPGオフライン

端末内の GGUF モデルを llama.cpp で動かし、AI がゲームマスターを担当する
**完全オフライン**の選択肢型 TRPG Android アプリです。

- Kotlin / Jetpack Compose / Material 3
- Room / DataStore / Kotlin Serialization
- llama.cpp (JNI) + GGUF
- 独自 D6 システム（判定・戦闘・状態変更はアプリ側で確定）
- 4 層の記憶システム（短期 / 長期 / 世界 / NPC）
- JSON バックアップの書き出し・読み込み

## 動作環境

- Android 13（API 33）以上
- arm64-v8a（64bit ARM）のみ
- 端末内に置ける GGUF モデル（推奨: Q4_K_M 程度の 1B〜4B クラス）
- RAM とストレージはモデルサイズに依存します。大きなモデルは読み込み時に
  メモリ不足となる場合があるため、コンテキストサイズを下げるか小さいモデルを使用してください。

## 使い方

1. 「モデル管理」から端末内の GGUF ファイルを取り込み、「読み込む」を押す。
2. 「冒険を始める」から、AI 自動生成またはユーザー設定＋AI 補完で世界と主人公を作る。
3. 提示された選択肢、または自由行動で物語を進める。
4. セーブはターン成功時に自動で確定する。設定画面から JSON で書き出し・読み込みができる。

AI はダイスを振らず、HP や所持品を直接書き換えません。判定・戦闘・状態変更は
アプリ内の D6 ルールエンジンが確定し、AI の応答は厳格な JSON として検証されます。
検証に失敗したターンやキャンセルしたターンは保存されません。

## ビルド

```bash
# llama.cpp を固定版（v0.9.0）で取得（初回のみ）
./scripts/fetch-llama.sh

# ルールエンジンなどの純 Kotlin テスト（Android SDK 不要）
./gradlew -p core test

# Android APK（Android SDK 36 + NDK 29.0.13113456 + CMake 3.31.6 が必要）
./gradlew :app:assembleDebug
```

CI（GitHub Actions）は core テスト、app 単体テスト、arm64-v8a Debug APK のビルドを実行します。
CPU 推論が既定です。Vulkan を試す場合のみ `-Ptrpg.vulkan=true` を付けてビルドします
（このリポジトリでは Vulkan 構成は未検証です）。

## 構成

- `core/` — Android 非依存。ドメインモデル、D6 ルール、戦闘、状態変更、
  AI 出力パーサ、プロンプト構築、記憶選別、バックアップの検証・整形
- `app/` — Compose UI、Room（15 テーブル）、DataStore、llama.cpp JNI、
  SAF によるモデル取り込みとバックアップ

主要な設計判断とフェーズの完了条件は `PLAN.md`、進捗と検証状況は
`PROGRESS.md` / [docs/VERIFICATION.md](docs/VERIFICATION.md) を参照してください。

## プライバシー

実行時に外部 API・クラウド・ネットワークを使用しません。`INTERNET` 権限は付与していません。
OS のクラウド自動バックアップも無効化しており、モデル・セーブ・設定は端末内にのみ保存されます。
バックアップはユーザーが明示的に選んだ保存先へ JSON を書き出す方式です。
