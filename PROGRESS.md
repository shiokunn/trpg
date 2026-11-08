# PROGRESS — Android向け完全オフラインTRPGアプリ

**Status: Phase 7（仕上げ）実装完了 / 実機検証は未実施**
**Last updated: 2026-11-08**

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
| 42484731143 | UI アクセシビリティ/エラー表示の修正 | 実行後に本ファイルへ追記 |

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
3. スキーマ JSON をリポジトリへコミットし、次バージョンで Migration テストを追加
4. Phase 6 の残り（場所・クエスト・アイテム編集、戦闘後描写）に着手
