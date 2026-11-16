package io.github.shiokunn.trpg.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.shiokunn.trpg.core.debug.DebugConfig
import io.github.shiokunn.trpg.core.debug.DebugLogEntry
import io.github.shiokunn.trpg.core.debug.DebugLogLevel
import io.github.shiokunn.trpg.core.debug.DebugSession
import io.github.shiokunn.trpg.core.debug.MemorySample
import io.github.shiokunn.trpg.core.debug.RuntimeInfo
import io.github.shiokunn.trpg.core.debug.TokenRateSample
import io.github.shiokunn.trpg.ui.components.TrpgScaffold
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TEMPORARY DEBUG MODE — developer panel.
 *
 * Deletion: remove `app/src/main/kotlin/io/github/shiokunn/trpg/debug/` and the marked
 * blocks in SettingsScreen / TrpgNavHost / LlamaCppEngine / LlamaCppNative / manifest.
 */
@Composable
fun DebugScreen(viewModel: DebugViewModel, onBack: () -> Unit) {
    val enabled by DebugConfig.enabled.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val export by viewModel.export.collectAsStateWithLifecycle()
    val context = LocalContext.current

    TrpgScaffold(title = "デバッグ（一時）", onBack = onBack) { padding ->
        if (!enabled) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "デバッグモードはOFFです。\n設定 →「開発者向け（一時）」から有効にしてください。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { StatusCard(state) }
                item { TimingCard(state) }
                item { RuntimeCard(state.runtime) }
                item { MemoryCard(state.memory) }
                item { LogCard(logs) }
                item {
                    ExportCard(
                        hasReport = state.hasReport,
                        export = export,
                        onSave = { viewModel.export(context) },
                        onShare = { viewModel.share(context) },
                        onClear = viewModel::clearMessage,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusCard(state: DebugViewState) {
    DebugCard("推論状態") {
        InfoRow("状態", phaseLabel(state.phase), accent = phaseColor(state.phase))
        InfoRow("開始時刻", state.startedAtMs?.let(::formatTime) ?: "取得不可")
        InfoRow("経過時間", "${state.elapsedMs} ms")
        InfoRow(
            "入力トークン数",
            state.promptTokens?.toString()
                ?: if (state.isActive) "計測中" else "取得不可",
        )
        InfoRow("生成済みトークン数", state.generatedTokens.toString())
        InfoRow("最大生成トークン数", state.maxTokens.toString())
        InfoRow("生成速度", formatTps(state.tokensPerSecond))
        state.error?.let { InfoRow("エラー", it, accent = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun TimingCard(state: DebugViewState) {
    val metrics = state.metrics
    DebugCard("処理時間") {
        InfoRow("Template 構築", formatMs(metrics?.applyMs))
        InfoRow("Tokenize", formatMs(metrics?.tokenizeMs))
        InfoRow("KV Cache 準備（差分削除）", formatMs(metrics?.clearMs))
        InfoRow("KV 再利用トークン", metrics?.kvReusedTokens?.toString() ?: "取得不可")
        InfoRow("KV 再計算トークン", metrics?.kvComputedPromptTokens?.toString() ?: "取得不可")
        InfoRow("KV 保持トークン", metrics?.kvCachedTokens?.toString() ?: "取得不可")
        InfoRow(
            "KV 再利用",
            metrics?.kvHit?.let { if (it) "あり" else "なし" } ?: "取得不可",
        )
        InfoRow("KV 無効化理由", metrics?.kvInvalidationReason ?: "取得不可")
        InfoRow("Prompt Evaluation", formatMs(metrics?.promptMs))
        InfoRow("Decode", formatMs(metrics?.genMs))
        InfoRow("初回トークンまで (TTFT)", formatMs(metrics?.ttftMs))
        InfoRow("推論全体", "${state.elapsedMs} ms")
        InfoRow(
            "Prompt / Generated",
            "${metrics?.promptTokens ?: "取得不可"} / ${metrics?.generatedTokens ?: state.generatedTokens}",
        )
        InfoRow("Budget", metrics?.budget?.toString() ?: "取得不可")
        if (metrics == null && state.isActive) {
            Text(
                "※ 内訳は推論完了後に確定します（推論中は全体時間と速度のみ更新されます）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.tokenRates.isNotEmpty()) {
            Text(
                "16トークンごとの速度（直近5件）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            state.tokenRates.takeLast(5).forEach { sample -> RateRow(sample) }
        }
    }
}

@Composable
private fun RateRow(sample: TokenRateSample) {
    InfoRow(
        "${sample.atToken} tokens",
        "${formatTps(sample.tokensPerSecond)} / ${sample.windowMs} ms",
    )
}

@Composable
private fun RuntimeCard(runtime: RuntimeInfo?) {
    DebugCard("llama.cpp 実行情報") {
        if (runtime == null) {
            Text("取得不可（モデル未ロード）", style = MaterialTheme.typography.bodySmall)
        } else {
            InfoRow(
                "llama.cpp",
                listOfNotNull(runtime.llamaVersion, runtime.llamaCommit)
                    .joinToString(" / ")
                    .ifBlank { "取得不可" },
            )
            InfoRow("モデル", runtime.modelFileName ?: "取得不可")
            InfoRow("n_ctx（実値 / 要求）", pair(runtime.nCtx, runtime.nCtxRequested))
            InfoRow("n_ctx_train", runtime.nCtxTrain?.toString() ?: "取得不可")
            InfoRow("n_batch（実値 / 要求）", pair(runtime.nBatch, runtime.nBatchRequested))
            InfoRow("n_ubatch（実値 / 要求）", pair(runtime.nUbatch, runtime.nUbatchRequested))
            InfoRow("n_seq_max", runtime.nSeqMax?.toString() ?: "取得不可")
            InfoRow("threads（実値 / 要求）", pair(runtime.nThreads, runtime.nThreadsRequested))
            InfoRow("threads_batch", runtime.nThreadsBatch?.toString() ?: "取得不可")
            InfoRow("CPU affinity", runtime.threadpool ?: "取得不可")
            InfoRow("許可CPU（プロセス）", runtime.allowedCpus ?: "取得不可")
            InfoRow("Flash Attention（設定）", runtime.flashAttn ?: "取得不可")
            InfoRow(
                "KV Cache 型",
                listOfNotNull(runtime.kvTypeK, runtime.kvTypeV)
                    .joinToString(" / ")
                    .ifBlank { "取得不可" },
            )
            InfoRow("GPU layers（設定）", runtime.gpuLayers?.toString() ?: "取得不可")
            InfoRow("Backend", runtime.backends.joinToString(", ").ifBlank { "取得不可" })
            InfoRow(
                "GPU offload",
                runtime.gpuOffload?.let { if (it) "利用可能" else "なし" } ?: "取得不可",
            )
        }
    }
}

@Composable
private fun MemoryCard(samples: List<MemorySample>) {
    val latest = samples.lastOrNull()
    val first = samples.firstOrNull()
    DebugCard("メモリ") {
        InfoRow("VmRSS（最新）", formatKb(latest?.vmRssKb))
        InfoRow("VmSwap（最新）", formatKb(latest?.vmSwapKb))
        InfoRow("取得時刻（最新）", latest?.atMs?.let(::formatTime) ?: "取得不可")
        InfoRow("VmRSS（開始時）", formatKb(first?.vmRssKb))
        InfoRow("VmSwap（開始時）", formatKb(first?.vmSwapKb))
        InfoRow("サンプル数", samples.size.toString())
    }
}

@Composable
private fun LogCard(logs: List<DebugLogEntry>) {
    DebugCard("ログ（最大 ${DebugSession.MAX_LOG_ENTRIES} 件）") {
        if (logs.isEmpty()) {
            Text("まだログはありません。", style = MaterialTheme.typography.bodySmall)
        } else {
            logs.forEach { entry -> LogRow(entry) }
        }
    }
}

@Composable
private fun LogRow(entry: DebugLogEntry) {
    val color = when (entry.level) {
        DebugLogLevel.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
        DebugLogLevel.WARN -> Color(0xFFB26A00)
        DebugLogLevel.ERROR -> MaterialTheme.colorScheme.error
    }
    Text(
        "${formatTime(entry.atMs)} [${entry.level.name}] ${entry.message}",
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = color,
    )
}

@Composable
private fun ExportCard(
    hasReport: Boolean,
    export: DebugExportState,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onClear: () -> Unit,
) {
    DebugCard("JSONエクスポート") {
        Text(
            "推論1回分の計測結果をJSONで保存します。プロンプト・生成本文は含まれません。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onSave, enabled = hasReport && export !is DebugExportState.Running) {
                Text(if (export is DebugExportState.Running) "保存中…" else "JSONを保存")
            }
            if (export is DebugExportState.Saved) {
                TextButton(onClick = onShare) { Text("共有") }
            }
            if (export is DebugExportState.Failed) {
                TextButton(onClick = onClear) { Text("閉じる") }
            }
        }
        when (export) {
            is DebugExportState.Saved -> Text(
                "保存先: ${export.file.absolutePath}",
                style = MaterialTheme.typography.bodySmall,
            )
            is DebugExportState.Failed -> Text(
                "エラー: ${export.message}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            else -> Unit
        }
        if (!hasReport) {
            Text(
                "推論を1回実行すると保存できます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DebugCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, accent: Color? = null) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = accent ?: MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.4f),
        )
    }
}

@Composable
private fun phaseColor(phase: DebugSession.Phase?): Color = when (phase) {
    DebugSession.Phase.DECODE -> MaterialTheme.colorScheme.primary
    DebugSession.Phase.PREPARING -> Color(0xFFB26A00)
    DebugSession.Phase.ERROR -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurface
}

private fun phaseLabel(phase: DebugSession.Phase?): String = when (phase) {
    null -> "待機中（まだ推論していません）"
    DebugSession.Phase.PREPARING -> "準備中（Template / Tokenize / Prompt Evaluation）"
    DebugSession.Phase.DECODE -> "Decode 中"
    DebugSession.Phase.COMPLETED -> "完了"
    DebugSession.Phase.CANCELLED -> "キャンセル"
    DebugSession.Phase.ERROR -> "エラー"
}

private fun formatMs(value: Long?): String = value?.let { "$it ms" } ?: "取得不可"

private fun formatKb(value: Long?): String = value?.let { "$it kB" } ?: "取得不可"

private fun formatTps(value: Double): String =
    if (value > 0.0) String.format(Locale.US, "%.2f tok/s", value) else "0.00 tok/s"

private fun formatTime(ms: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))

private fun pair(actual: Int?, requested: Int?): String =
    "${actual ?: "取得不可"} / ${requested ?: "取得不可"}"
