# 検証レポート（Phase 8 — llama.cpp v0.9.0 統合）

## 0. 環境

| 項目 | 値 |
|---|---|
| 端末 | arrows Alpha (FCNT M08 / fuji) |
| Android | 16 (SDK 36) |
| SoC | Dimensity 8350-Extreme (MT6897) |
| RAM | 12GB |
| ABI | arm64-v8a |
| Thread Profile | A |
| GGUF | `/sdcard/localLLM/qwen3-4b.Q4_K_M.gguf`（2.32 GiB, Q4_K, qwen3, 36 blocks, 40960 ctx） |
| APK | `app-debug.apk`（CI run 42905312348 / 42992514053 成果物、v0.1.0、code=1） |
| 既存アプリデータ | 導入前に完全バックアップ → アンインストール → インストール → 復元（DB・DataStore・Kyalulu 3.4GB も原本と完全一致） |

## 1. APK 導入

| 項目 | 結果 |
|---|---|
| アーキテクチャ | arm64-v8a（CI 成果物通り） |
| 署名 | Signer DN: C=US, O=Android, CN=Android Debug |
| インストール | 署名不一致のため `adb install -r` は失敗。バックアップ保存後、uninstall→install で導入（既存データは保護） |
| 導入記録 | 2026-11-11 18:31:31（firstInstallTime/lastUpdateTime） |

**PASS**

## 2. JNI ライブラリロード・アプリ起動

- アプリ起動時、クラッシュ・ANR なし。
- `run-as` で Room DB (`trpg.db`) が正常にオープン（WAL 247KB も正常に同期）。
- `logcat` に `TrpgLlama` タグの初期化ログ出力。

**PASS**

## 3. モデル読み込み（qwen3-4b.Q4_K_M）

```
llama_model_loader: loaded meta data with 28 key-value pairs and 398 tensors
... qwen3.context_length u32 = 40960 ...
print_info: file format = GGUF V3 (latest)
print_info: file type   = Q4_K - Medium
print_info: file size   = 2.32 GiB (4.95 BPW)
init_tokenizer: initializing tokenizer for type 2
load: 0 unused tokens
sched_reserve: CPU compute buffer size = 329.77 MiB
sched_reserve: graph: nodes = 1269, splits = 1, input objects = 4, input tensors = 9
template_init=227ms template=applied explicit=1 applied=1
attach_threadpool: call
Threadpool attached: profile=A threads=1 strict_cpu=true cpus=7
threadpool_attach=1ms ggml_threadpool available; profile=0; attach=true
```

画面：「モデルを読み込みました。」「qwen3 4B Q4_K - Medium」「コンテキスト 8192 / 学習時 40960・チャットテンプレート あり（対応済み）」「読み込み済み」

**PASS**

## 4. テンプレート適用

```
template_init=227ms template=applied explicit=1 applied=1
template_apply[chat]: apply_ms=15 tokenize_ms=34 parts=7 tokens=737
```

`template=applied explicit=1 applied=1` → Qwen3 の GGUF 埋め込みテンプレートが正しく読み込まれ、`enable_thinking=false` で適用（`<|im_start|>` 等）。**テンプレート適用は正常動作**。

**PASS**

## 5. Threadpool attach（Profile A）

```
Threadpool attached: profile=A threads=1 strict_cpu=true cpus=7
```

Profile A は「最速コアあたり 1 スレッド」の仕様。端末の周波数：
```
cpu0-3: 2200000 / cpu4-6: 3200000 / cpu7: 3350000  → 最速は cpu7 のみ（1 コア）
```
そのため `threads = min(4, 1) = 1` になります。attach 自体は成功（`threadpool_attach=1ms`）。

**attach は動作したが、実効スレッド数が 1 に低下（後述の生成不具合の原因）**

## 6. 生成（重大問題 — 原因確定）

### 6.1 現象
- 世界生成（「宇宙」1 トピック、737 トークン）：355.7 秒かかり、`graph_compute: ggml_backend_sched_graph_compute_async failed with error 1` で中断。
  ```
  generate: prompt_tokens=737 prompt_ms=355704 generated_tokens=0 gen_ms=0 tok_per_sec=0.00 ttft_ms=-1 cancelled=1 failed=0
  ```
- 短プロンプト「宇宙」でも同一エラー：`cplan requested more threads (4) than available (1)`
- **問題の原因はプロンプト長に依存せず、すべての生成で再現**

### 6.2 原因分析（確定）

端末の CPU 周波数（実機確認）：
```
cpu0-3: 2200000 / cpu4-6: 3200000 / cpu7: 3350000  → 最速コアは cpu7 のみ（1 コア）
```
- Profile A の実装（`llama_jni.cpp` `build_threadpool_params()`、profile 0 の「one thread per core」分岐）は**最速コア 1 つあたり 1 スレッド**とするため、`threads = min(4, 1) = 1` になります。
- スレッドプールが実質 1 スレッドしかない状態で llama.cpp の `ggml_backend_sched_graph_compute_async` を実行すると、cplan が並列化のために 4 スレッドを要求しますが「利用可能 (1)」となり、**error 1 (ALLOC_FAILED) で失敗**します。
- 結果、prompt decode が極端に遅延（各トークンが逐次的に処理され 2 トークン/秒程度）し、最終的に graph_compute エラーで 1 トークンも生成されずにキャンセルされます。

### 6.3 修正方針（承認待ち）
`build_threadpool_params()` の profile 0 分岐に fallback を追加：`high_cpus` が 1 コアしかない場合でも、第 2 次クロック（3.2GHz 以上）や全コアを fallback として含め、最低スレッド数を確保する（例：`threads >= min(requested_threads, 2)` 程度）。

**FAIL** — コード修正が必要。

## 7. UTF-8 検証

生成が失敗するため **未実施（BLOCKED）**。

## 8. キャンセル検証

- 生成中のキャンセル：UI 側は正常に反応（「生成をキャンセルしました」）、`generate: ... cancelled=1 failed=0`（約 340ms 応答）。
- バックグラウンドの生成完了処理：未確認（graph_compute エラー直後の挙動）。
- キャンセル直後の再生成・連続キャンセル防止：未実施（生成バグのため）。

**部分 PASS**（UI 応答 OK / バックグラウンド完了は未確認）。

## 9. モデル管理（読み込み→生成→解放→再読み込み→再生成）

- 読み込み：PASS
- 生成：FAIL（バグのため）
- 解放 / 再読み込み / 再生成：未実施（生成バグのため）

**未確認**（アプリ再起動済み、データは intact）。

## 10. 発生した問題

1. **生成バグ（重大）**：Profile A（最速コア 1 つあたり 1 スレッド）の仕様により、Dimensity 8350-Extreme では実効スレッド数が 1 となり、llama.cpp の `ggml_backend_sched_graph_compute_async` が error 1 で失敗。prompt decode に 355 秒かかり、1 トークンも生成されずにキャンセルされます。
   - 再現条件：Profile A で、最速コアが 1 コアしかない環境（本端末）。
   - 原因ファイル：`app/src/main/cpp/llama_jni.cpp` の `build_threadpool_params()`（profile 0 の「one thread per core」分岐）。
   - 修正方針：`high_cpus` が 1 コアだけになる場合、第 2 次クロック以上のコアや全コアを fallback として含め、最低スレッド数を確保する。
   - 修正にはコードレビューの承認が必要。

2. **`adb install -r` 署名不一致**：CI 毎に異なる debug keystore になるため、インストール時はアンインストールを要する（アプリデータは別途バックアップ/復元が必要）。

## 11. Phase 8 の残課題

| No. | 課題 | 状態 |
|---|---|---|
| 1 | **生成バグ修正（Profile A / threadpool）** | **要コード修正（承認待ち）** |
| 2 | UTF-8 検証（絵文字・日本語混在・ストリーミング一致） | バグ修正後に再試 |
| 3 | 生成性能測定（TTFT/tok/s/tokenize_ms/clear_ms/decode_ms/attach_ms） | バグ修正後に再試 |
| 4 | キャンセルの詳細検証（prompt decode 中/生成中/連続/再生成） | バグ修正後に再試 |
| 5 | モデル管理（解放→再読み込み→再生成） | バグ修正後に再試 |
| 6 | Golden test（Gemma4/Qwen3/LFM2.5/未対応テンプレート） | 未着手（P1-3） |
| 7 | CI host job / A/B/C 計測 | 未着手（未承認） |
| 8 | 実モデル（Kyalulu）による検証 | モデル未取得（本機には保存済みだが未使用） |

---

*更新日：2026-11-11（arrows Alpha 実機検証。検証結果を docs/VERIFICATION.md に記録）*
