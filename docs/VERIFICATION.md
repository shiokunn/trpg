# 検証レポート

最終更新: 2026-11-10

このファイルは「実際に検証できたこと」と「この開発環境では検証できなかったこと」を
分けて記録します。未検証の項目を検証済みとして扱いません。

## 検証環境

- 開発: Android 上の Termux（Linux 互換環境）。**Android SDK / NDK は無い**ため、
  ローカルで APK・ネイティブコードをビルドすることはできません。
- ビルドと Android テストの唯一の実行経路: GitHub Actions（`.github/workflows/android.yml`）。
- 端末実機・実 GGUF モデル・GPU はこの環境から利用できません。

## 自動テスト（ローカル実行）

コマンド:

```bash
./gradlew -p core test
```

結果: **BUILD SUCCESSFUL / 89 tests passed**（2026-11-08）。

| 対象 | 件数 | 内容 |
| --- | ---: | --- |
| ダイス・判定 | 7 | 能力値＋スキル、難易度 4/6/8/10/12/14、有利/不利、致命/成功/部分成功/失敗 |
| キャラクター規則 | 7 | 能力値合計18（各1〜5）、スキル作成6点・最大2、成長上限3、XP/レベル |
| 戦闘解決 | 6 | 攻撃・防御・ダメージ・HP下限・戦闘不能 |
| 戦闘ラウンド | 5 | プレイヤー攻撃→敵反撃、装備反映、決着、HP保存 |
| 状態変更 | 19 | 全変更種別の範囲検証、ID検証、全件拒否、戦闘・移動制約、装備解除 |
| GM 応答パーサ | 13 | 未知キー拒否、選択肢数、難易度、記憶、修復指示、コードフェンス除去 |
| 冒険草案パーサ | 10 | 世界・導入・主人公の厳格 JSON、範囲・必須項目 |
| プロンプト | 7 | 予算配分、切詰め、履歴トリム、秘密のGM専用表示、**エンティティIDと目的インデックス** |
| 記憶選別 | 6 | 重要度・新しさ・未解決・伏線・キーワード関連度、上限 |
| バックアップ | 8 | v1スキーマ、未知キー拒否、ID重複、参照整合、往復変換 |
| その他 | 1 | コアのバージョン定数 |

`app` の単体テスト（`AdventureAssemblerTest`、5件）は Android SDK を要するため
ローカルでは実行できず、CI の `:app:testDebugUnitTest` で実行しています。

## CI（GitHub Actions）

| 実行 | 内容 | 結果 |
| --- | --- | --- |
| [42481150919](https://github.com/shiokunn/trpg/actions/runs/42481150919) | llama.cpp JNI / arm64-v8a APK の初回ビルド | 成功 |
| [42484039490](https://github.com/shiokunn/trpg/actions/runs/42484039490) | core 89件 + app 5件 + Debug APK（プロンプトID修正時点） | 成功 |
| [42485089100](https://github.com/shiokunn/trpg/actions/runs/42485089100) | Phase 7 一式（core 89 + app 5 + arm64-v8a APK + Room スキーマ） | 成功 |
| [42485846213](https://github.com/shiokunn/trpg/actions/runs/42485846213) | Room スキーマ基準のコミット（最終実行、core 89 + app 5 + APK） | 成功 |

CI で確認できること:

- `:core:test` が全件成功する
- `:app:testDebugUnitTest` が成功する
- NDK 29 / CMake 3.31.6 で llama.cpp v0.9.0（コミット固定）がコンパイルできる
- `:app:assembleDebug` が arm64-v8a の APK を生成できる
- Room のスキーマ JSON が生成される（成果物 `room-schema`）

## 実装済みだが実機未検証

以下はコードとビルド成功のみ確認しており、**動作を保証していません**。

1. **実 GGUF モデルの読み込みと生成** — 実機・モデルファイルが無いため未実行。
   JNI のシグネチャと llama.cpp v0.9.0 API への適合はコンパイルでのみ確認。
2. **ストリーミング表示・キャンセル・修復リトライ** — 実モデルでの応答時間、
   キャンセル後のプロセス状態、トークン境界での JSON 修復は未確認。
3. **メモリ不足時の挙動** — OOM を捕捉して日本語メッセージを返す実装はあるが、
   実際に OOM を発生させた検証はしていない。OS によるプロセス kill は防げない。
4. **Vulkan バックエンド** — `-Ptrpg.vulkan=true` のビルドも実行も未検証。既定は CPU。
5. **Room マイグレーション** — スキーマは version 1 のみ。ベースライン
   `app/schemas/io.github.shiokunn.trpg.data.db.AppDatabase/1.json` をリポジトリに保存した。
   マイグレーションコードは未実装（破壊的フォールバックも置いていない）。将来のバージョンアップ時は
   スキーマ JSON の差分から明示的な Migration を追加し、MigrationTestHelper で検証する必要がある。
6. **SAF によるモデル取り込み / JSON バックアップ** — 実端末のファイル選択 UI、
   大容量ファイル、空き容量不足、書き込み失敗時の挙動は未確認。
7. **画面回転・バックグラウンド・プロセス death** — `configChanges` と ViewModel により
   回転で状態を保持する設計だが、実機での確認は未実施。ターン確定は単一トランザクション
   なので途中終了でも半端な保存にはならない、という設計上の保証のみ。
8. **TalkBack / 文字サイズ 200%** — プログレスバーと生成状態に semantics を追加し、
   クイック操作の高さを可変にしたが、実際の読み上げ・大文字でのレイアウト崩れは未確認。
9. **戦闘のAI描写連携** — 戦闘ラウンドはアプリが確定しログを表示する。
   戦闘後のGMターン生成は未実装。
10. **場所・クエストの手動追加、アイテム数量編集** — 未実装。

## 設計上の保証（テストで確認済み）

- AI の応答は厳格な JSON として解析され、未知キー・不正な enum・範囲外の値は拒否される。
- 1 回だけ修復プロンプトを送り、それでも失敗した場合はターンを保存しない。
- 状態変更は全件検証してから単一トランザクションで適用する（1件でも不正なら全件拒否）。
- ダイス、戦闘、HP、所持品の更新はアプリ側の決定論的コードのみが行う。
- GM 専用の秘密はプロンプトには含まれるが、プレイヤー向け画面（履歴・世界情報）から除外される。
- バックアップのインポートは検証後に単一トランザクションで置換し、失敗時は既存データを変更しない。

## Phase 8（llama.cpp v0.9.0 推論エンジン改善）

### この環境で実施した検証

| 検証 | 方法 | 結果 |
| --- | --- | --- |
| JNI シグネチャ照合 | Kotlin の `external fun` 13件と `llama_jni.cpp` の `JNIEXPORT` 13件を引数個数・型で機械照合 | **全一致** |
| C++ 構文検証 | `g++ -std=c++17 -fsyntax-only -Wall -Wextra`、`llama_jni.cpp` を llama.cpp v0.9.0 の実ヘッダに対して profile A/B/C で | **当方コード警告ゼロ**（残る警告は llama.cpp 上流ヘッダ由来） |
| UTF-8 ヘルパー | `utf8_utils.cpp` をホストコンパイルし、日本語・絵文字・非BMP・分割・不完全末尾・孤立継続・overlong・surrogate・範囲外・clear の 13 ケースを実行 | **13/13 成功** |
| エンジン Kotlin | `kotlinc` で `ai/engine` 3ファイルを `:core` クラス・coroutines・serialization jar 付きで単体コンパイル | **成功** |
| Core ビルド | `gradle -p core build` | **成功** |
| llama.cpp シンボル実在 | `common_chat_templates_*` / `llama_attach_threadpool` / `llama_set_abort_callback` / `common_chat_prompt.parts`/`is_input` / `additional_stops` / `llama-common` ターゲット | **すべて実在** |
| threadpool 解放順序 | `llama_detach_threadpool` の実装（ポインタを nullptr 化するだけ）を確認 | detach 後に解放する当方の順序が安全と判断 |

### 未検証（Phase 8）

- **Android arm64-v8a ビルド / APK 生成** — Android SDK/NDK がローカルに無く、
  この変更の NDK ビルドは未実施。`common`（`chat.cpp` / minja / jinja）の
  コンパイル可否、`llama-common` 共有ライブラリの APK パッケージングは未確認。
- **CI 実行** — 既存の承認条件を守り、未実行。
- **ADB 実機** — 未実施。テンプレート適用（Gemma 4 / Qwen3 / LFM2.5）、
  fail closed、BOS の二重付与なし、`enable_thinking=false`、stop 文字列、
  キャンセル（待機中 / prompt decode 中 / 生成中）、絵文字ストリーミング、
  threadpool attach / affinity、各計測ログの実出力はすべて未確認。
- **実モデル（Kyalulu）** — モデルファイルが未取得のため、テンプレート評価・
  トークン化・停止原因の再現は未実施。
- **A/B/C 計測** — 未実施。既定は profile A のまま。
- **P1-3 ゴールデンテスト / CI host job** — 未着手。
