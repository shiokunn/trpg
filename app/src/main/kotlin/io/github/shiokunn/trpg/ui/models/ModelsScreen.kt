package io.github.shiokunn.trpg.ui.models

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.shiokunn.trpg.ai.engine.BackendInfo
import io.github.shiokunn.trpg.ai.engine.EngineStatus
import io.github.shiokunn.trpg.ai.engine.ModelInfo
import io.github.shiokunn.trpg.ai.model.ModelFileManager
import io.github.shiokunn.trpg.ai.model.formatFileSize
import io.github.shiokunn.trpg.core.domain.ModelSettings
import io.github.shiokunn.trpg.ui.ImportProgress
import io.github.shiokunn.trpg.ui.ModelsViewModel
import io.github.shiokunn.trpg.ui.components.ConfirmDialog
import io.github.shiokunn.trpg.ui.components.EmptyState
import io.github.shiokunn.trpg.ui.components.TrpgScaffold
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun ModelsScreen(viewModel: ModelsViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val status by viewModel.engineStatus.collectAsStateWithLifecycle()
    val backend by viewModel.backend.collectAsStateWithLifecycle()
    val progress by viewModel.importProgress.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    var pendingDelete by remember { mutableStateOf<ModelFileManager.ModelFile?>(null) }
    var draft by remember(settings?.updatedAt) {
        mutableStateOf((settings ?: ModelSettings()).normalized())
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.importModel(uri)
    }
    val openPicker = { picker.launch(arrayOf("*/*")) }

    TrpgScaffold(
        title = "モデル管理",
        onBack = onBack,
        actions = {
            if (status is EngineStatus.Ready) {
                TextButton(onClick = viewModel::unloadModel) { Text("解放") }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            message?.let { text ->
                item { MessageCard(text = text, onDismiss = viewModel::dismissMessage) }
            }

            item { EngineStatusCard(status = status, backend = backend) }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "モデルファイル",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "GGUF形式のみ対応（アプリ専用領域に保存）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = openPicker, enabled = progress == null) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("取り込む")
                    }
                }
            }

            progress?.let { state ->
                item { ImportCard(progress = state, onCancel = viewModel::cancelImport) }
            }

            if (models.isEmpty() && progress == null) {
                item {
                    EmptyState(
                        icon = Icons.Default.SmartToy,
                        title = "モデルがまだありません",
                        message = "端末内のGGUFファイルを取り込むと、完全オフラインでGMを動かせます。",
                        actionLabel = "GGUFファイルを取り込む",
                        onAction = openPicker,
                    )
                }
            }

            items(models, key = { it.fileName }) { model ->
                ModelRow(
                    model = model,
                    selected = settings?.modelFileName == model.fileName,
                    loaded = (status as? EngineStatus.Ready)?.modelFileName == model.fileName,
                    loading = (status as? EngineStatus.Loading)?.modelFileName == model.fileName,
                    onSelect = { viewModel.selectModel(model) },
                    onLoad = { viewModel.loadModel(model) },
                    onDelete = { pendingDelete = model },
                )
            }

            item {
                Text(
                    text = "推論設定",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            item {
                ParamCard(
                    draft = draft,
                    onChange = { draft = it },
                    onSave = { viewModel.saveParams(draft) },
                )
            }

            item { MemoryNoteCard() }
        }
    }

    pendingDelete?.let { model ->
        ConfirmDialog(
            title = "モデルを削除",
            message = "「${model.displayName}」を端末から削除します。この操作は取り消せません。",
            onConfirm = { viewModel.deleteModel(model) },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun MessageCard(text: String, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "閉じる",
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun EngineStatusCard(status: EngineStatus, backend: BackendInfo) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Memory,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text("推論エンジン", style = MaterialTheme.typography.titleSmall)
            }

            when (status) {
                is EngineStatus.Unloaded -> {
                    Text(
                        "未読み込み。使用するモデルの「読み込む」を押してください。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is EngineStatus.Loading -> {
                    Text(
                        "「${status.modelFileName}」を読み込み中…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                is EngineStatus.Ready -> {
                    Text(
                        status.modelFileName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (status.info.description.isNotBlank()) {
                        Text(
                            status.info.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        "コンテキスト ${status.info.nCtx} / 学習時 ${status.info.nCtxTrain}・" +
                            "チャットテンプレート ${templateStatusLabel(status.info)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is EngineStatus.Failed -> {
                    Text(
                        "読み込みに失敗しました",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        status.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (backend.system.isNotBlank()) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text(
                    text = if (backend.available) {
                        "バックエンド: ${backend.backends.joinToString(", ").ifBlank { "CPU" }}"
                    } else {
                        "バックエンド情報を取得できません"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = backend.system,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (backend.gpuOffload) {
                    Text(
                        "GPUオフロード対応ビルドです（GPUレイヤー設定が有効）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ImportCard(progress: ImportProgress, onCancel: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "モデルを取り込み中…",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCancel) { Text("キャンセル") }
            }
            val fraction = progress.fraction
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(
                text = if (progress.total > 0L) {
                    "${formatFileSize(progress.copied)} / ${formatFileSize(progress.total)}" +
                        "（${(fraction!! * 100).roundToInt()}%）"
                } else {
                    "${formatFileSize(progress.copied)} をコピーしました"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

@Composable
private fun ModelRow(
    model: ModelFileManager.ModelFile,
    selected: Boolean,
    loaded: Boolean,
    loading: Boolean,
    onSelect: () -> Unit,
    onLoad: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            },
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.SmartToy,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = model.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${formatFileSize(model.sizeBytes)}・${model.fileName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selected) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "選択中",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (loading) {
                    Text(
                        "読み込み中…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    TextButton(onClick = onLoad, enabled = !loaded) {
                        Text(if (loaded) "読み込み済み" else "読み込む")
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "「${model.displayName}」を削除",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun ParamCard(
    draft: ModelSettings,
    onChange: (ModelSettings) -> Unit,
    onSave: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "生成パラメータ",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "会話の安定性と創造性のバランスを調整します。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SliderRow(
                label = "コンテキストサイズ",
                valueText = "${draft.nCtx} トークン",
                value = draft.nCtx.toFloat(),
                range = ModelSettings.MIN_N_CTX.toFloat()..ModelSettings.MAX_N_CTX.toFloat(),
                onChange = { raw ->
                    val stepped = ((raw / 512f).roundToInt().coerceAtLeast(1) * 512)
                        .coerceIn(ModelSettings.MIN_N_CTX, ModelSettings.MAX_N_CTX)
                    onChange(draft.copy(nCtx = stepped))
                },
            )
            SliderRow(
                label = "最大生成トークン",
                valueText = "${draft.maxTokens}",
                value = draft.maxTokens.toFloat(),
                range = 64f..4096f,
                onChange = { raw ->
                    val stepped = (raw / 64f).roundToInt().coerceAtLeast(1) * 64
                    onChange(draft.copy(maxTokens = stepped))
                },
            )
            SliderRow(
                label = "温度（高いほど自由）",
                valueText = formatDecimal(draft.temperature),
                value = draft.temperature,
                range = 0f..2f,
                onChange = { raw ->
                    onChange(draft.copy(temperature = (raw * 100).roundToInt() / 100f))
                },
            )
            SliderRow(
                label = "Top-P",
                valueText = formatDecimal(draft.topP),
                value = draft.topP,
                range = 0.05f..1f,
                onChange = { raw ->
                    onChange(draft.copy(topP = (raw * 100).roundToInt() / 100f))
                },
            )
            SliderRow(
                label = "Top-K",
                valueText = "${draft.topK}",
                value = draft.topK.toFloat(),
                range = 1f..200f,
                onChange = { raw -> onChange(draft.copy(topK = raw.roundToInt())) },
            )
            SliderRow(
                label = "繰り返しペナルティ",
                valueText = formatDecimal(draft.repeatPenalty),
                value = draft.repeatPenalty,
                range = 1f..2f,
                onChange = { raw ->
                    onChange(draft.copy(repeatPenalty = (raw * 100).roundToInt() / 100f))
                },
            )
            SliderRow(
                label = "GPUレイヤー（0でCPUのみ）",
                valueText = "${draft.gpuLayers}",
                value = draft.gpuLayers.toFloat(),
                range = 0f..99f,
                onChange = { raw -> onChange(draft.copy(gpuLayers = raw.roundToInt())) },
            )

            Spacer(Modifier.height(8.dp))
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
                Text("設定を保存")
            }
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                valueText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value.coerceIn(range),
            onValueChange = onChange,
            valueRange = range,
        )
    }
}

@Composable
private fun MemoryNoteCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp)) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.width(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("メモリの目安", style = MaterialTheme.typography.titleSmall)
                Text(
                    "モデルとコンテキストを合わせて6〜8GB程度の空きが必要です。" +
                        "動作が重い場合はQ4_K_Mなどの量子化モデルを使うか、" +
                        "コンテキストサイズを下げてください。モデルは端末内のアプリ専用領域に保存され、外部通信は行いません。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun templateStatusLabel(info: ModelInfo): String {
    if (info.chatTemplateError.isNotBlank() && info.chatTemplateStatus == "unsupported") {
        return "あり（未対応）"
    }
    return when (info.chatTemplateStatus) {
        "applied" -> "あり（対応済み）"
        "none" -> if (info.hasChatTemplate) "あり" else "なし（ChatMLフォールバック）"
        "unsupported" -> "あり（未対応）"
        else -> if (info.hasChatTemplate) "あり" else "なし"
    }
}

private fun formatDecimal(value: Float): String =
    String.format(Locale.US, "%.2f", value)
