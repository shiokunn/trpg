# TRPGオフライン

端末内の GGUF モデルを llama.cpp で動かし、AI がゲームマスターを担当する
**完全オフライン**の選択肢型 TRPG Android アプリです。

- Kotlin / Jetpack Compose / Material 3
- Room / DataStore / Kotlin Serialization
- llama.cpp (JNI) + GGUF
- 独自 D6 システム（判定・戦闘・状態変更はアプリ側で確定）
- 4 層の記憶システム（短期 / 長期 / 世界 / NPC）

## ビルド

```bash
# llama.cpp を固定版で取得（初回のみ）
./scripts/fetch-llama.sh

# ルールエンジンなどの純 Kotlin テスト（Android SDK 不要）
gradle -p core test

# Android APK（Android SDK + NDK + CMake が必要）
./gradlew :app:assembleDebug
```

APK のビルド検証は GitHub Actions で行います。詳細は `PLAN.md` を参照してください。

## 構成

- `core/` — Android 非依存のドメインモデル、D6 ルール、AI 出力パーサ、プロンプト構築
- `app/` — Compose UI、Room、DataStore、llama.cpp JNI

## プライバシー

実行時に外部 API・クラウド・ネットワークを使用しません。`INTERNET` 権限は付与していません。
モデル、セーブ、設定はすべて端末内に保存されます。
