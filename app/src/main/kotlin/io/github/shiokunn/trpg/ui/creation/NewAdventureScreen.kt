package io.github.shiokunn.trpg.ui.creation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.shiokunn.trpg.ai.engine.EngineStatus
import io.github.shiokunn.trpg.ai.engine.GenerationFinishReason
import io.github.shiokunn.trpg.ai.engine.GenerationProgress
import io.github.shiokunn.trpg.core.ai.CharacterDraft
import io.github.shiokunn.trpg.core.ai.WorldDraft
import io.github.shiokunn.trpg.core.domain.AttributeId
import io.github.shiokunn.trpg.core.rules.CharacterRules
import io.github.shiokunn.trpg.creation.CreationMode
import io.github.shiokunn.trpg.ui.CreationStage
import io.github.shiokunn.trpg.ui.NewAdventureUiState
import io.github.shiokunn.trpg.ui.NewAdventureViewModel
import io.github.shiokunn.trpg.ui.components.TrpgScaffold

@Composable
fun NewAdventureScreen(
    viewModel: NewAdventureViewModel,
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val engineStatus by viewModel.engineStatus.collectAsStateWithLifecycle()

    LaunchedEffect(state.createdSessionId) {
        state.createdSessionId?.let(onCreated)
    }

    TrpgScaffold(title = "新しい冒険", onBack = onBack) { padding ->
        if (state.stage == CreationStage.PREVIEW) {
            PreviewContent(
                state = state,
                padding = Modifier.padding(padding),
                onCreate = viewModel::createAdventure,
                onRegenerate = viewModel::generate,
                onBackToForm = viewModel::backToForm,
                onDismissError = viewModel::clearError,
            )
        } else {
            FormContent(
                state = state,
                modelReady = engineStatus is EngineStatus.Ready,
                padding = Modifier.padding(padding),
                onMode = viewModel::setMode,
                onKeywords = viewModel::setKeywords,
                onSetting = viewModel::setSetting,
                onWish = viewModel::setProtagonistWish,
                onTone = viewModel::setTone,
                onDifficulty = viewModel::setDifficulty,
                onGenerate = viewModel::generate,
                onCancel = viewModel::cancelGeneration,
                onDismissError = viewModel::clearError,
            )
        }
    }
}

@Composable
private fun FormContent(
    state: NewAdventureUiState,
    modelReady: Boolean,
    padding: Modifier,
    onMode: (CreationMode) -> Unit,
    onKeywords: (String) -> Unit,
    onSetting: (String) -> Unit,
    onWish: (String) -> Unit,
    onTone: (String) -> Unit,
    onDifficulty: (String) -> Unit,
    onGenerate: () -> Unit,
    onCancel: () -> Unit,
    onDismissError: () -> Unit,
) {
    val busy = state.stage == CreationStage.GENERATING || state.saving

    LazyColumn(
        modifier = Modifier.fillMaxSize().then(padding),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("冒険の作り方", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                "世界とシナリオ、主人公をAIが提案します。内容を確認してから開始できます。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!modelReady) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "モデルが読み込まれていません。「設定」→「モデル管理」からGGUFモデルを読み込んでください。",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CreationMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.mode == mode,
                        onClick = { onMode(mode) },
                        label = { Text(mode.label) },
                        enabled = !busy,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                state.mode.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            OutlinedTextField(
                value = state.keywords,
                onValueChange = onKeywords,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("キーワード・テーマ") },
                placeholder = { Text("例: 霧に沈む港町、失われた航海日誌") },
                enabled = !busy,
                maxLines = 3,
            )
        }

        if (state.mode == CreationMode.GUIDED) {
            item {
                OutlinedTextField(
                    value = state.setting,
                    onValueChange = onSetting,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("世界設定（あなたが決めた設定）") },
                    placeholder = { Text("例: 空に浮かぶ島々を船で渡る世界。魔法は希少。") },
                    enabled = !busy,
                    minLines = 3,
                    maxLines = 8,
                )
            }
        }

        item {
            OutlinedTextField(
                value = state.protagonistWish,
                onValueChange = onWish,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("主人公への希望（任意）") },
                placeholder = { Text("例: 記憶を失った元航海士。交渉が得意。") },
                enabled = !busy,
                maxLines = 3,
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = state.tone,
                    onValueChange = onTone,
                    modifier = Modifier.weight(1f),
                    label = { Text("雰囲気（任意）") },
                    placeholder = { Text("ダーク、冒険活劇 など") },
                    enabled = !busy,
                )
                OutlinedTextField(
                    value = state.difficulty,
                    onValueChange = onDifficulty,
                    modifier = Modifier.weight(1f),
                    label = { Text("難易度（任意）") },
                    placeholder = { Text("普通") },
                    enabled = !busy,
                )
            }
        }

        if (busy) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(state.statusText.ifBlank { "生成しています…" }, style = MaterialTheme.typography.bodyMedium)
                        }
                        state.generation?.let { progress ->
                            Spacer(Modifier.height(6.dp))
                            TokenCounterLive(progress)
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = onCancel,
                            enabled = !state.cancelling,
                        ) { Text(if (state.cancelling) "中止中" else "生成を中止") }
                    }
                }
            }
        }

        state.error?.let { message ->
            item {
                ErrorCard(message = message, details = state.details, onDismiss = onDismissError)
            }
        }

        state.generation?.takeIf { it.finishReason != null }?.let { progress ->
            item { TokenCounterSummary(progress) }
        }

        item {
            Button(
                onClick = onGenerate,
                enabled = modelReady && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(if (state.world == null) "世界と主人公を生成する" else "もう一度生成する")
            }
        }

        item {
            Text(
                "ヒント: 生成には数十秒かかることがあります。このアプリは完全オフラインで動作します。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun PreviewContent(
    state: NewAdventureUiState,
    padding: Modifier,
    onCreate: () -> Unit,
    onRegenerate: () -> Unit,
    onBackToForm: () -> Unit,
    onDismissError: () -> Unit,
) {
    val world = state.world
    val character = state.character
    if (world == null || character == null) {
        FormContent(
            state = state,
            modelReady = true,
            padding = padding,
            onMode = {},
            onKeywords = {},
            onSetting = {},
            onWish = {},
            onTone = {},
            onDifficulty = {},
            onGenerate = onRegenerate,
            onCancel = {},
            onDismissError = {},
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().then(padding),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("この冒険で始めますか？", style = MaterialTheme.typography.headlineSmall)
            Text(
                "内容はあとから世界情報・キャラクター画面で確認できます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.generation?.takeIf { it.finishReason != null }?.let { progress ->
            item { TokenCounterSummary(progress) }
        }

        state.error?.let { message ->
            item {
                ErrorCard(message = message, details = state.details, onDismiss = onDismissError)
            }
        }

        item { WorldSummaryCard(world) }
        item { OpeningSummaryCard(world) }
        item { CharacterSummaryCard(character) }

        item {
            Button(
                onClick = onCreate,
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(if (state.saving) "保存しています…" else "この冒険で開始する")
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRegenerate, modifier = Modifier.weight(1f), enabled = !state.saving) {
                    Text("作り直す")
                }
                OutlinedButton(onClick = onBackToForm, modifier = Modifier.weight(1f), enabled = !state.saving) {
                    Text("条件を変更")
                }
            }
        }
    }
}

@Composable
private fun TokenCounterLive(progress: GenerationProgress) {
    Text(
        text = tokenCounterText(progress),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TokenCounterSummary(progress: GenerationProgress) {
    Text(
        text = tokenCounterText(progress) + "・" + finishReasonText(progress),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun tokenCounterText(progress: GenerationProgress): String {
    val effective = progress.budget?.takeIf { it in 1 until progress.maxTokens }
    val suffix = if (effective != null) "（実効上限 $effective）" else ""
    return "生成トークン: ${progress.generatedTokens} / ${progress.maxTokens}$suffix"
}

private fun finishReasonText(progress: GenerationProgress): String {
    val reason = when (progress.finishReason) {
        GenerationFinishReason.LENGTH -> "上限到達"
        GenerationFinishReason.EOS -> "完了（EOS）"
        GenerationFinishReason.STOP -> "停止文字列"
        GenerationFinishReason.CANCELLED -> "キャンセル"
        GenerationFinishReason.ERROR -> "エラー"
        GenerationFinishReason.CONTEXT -> "コンテキスト上限"
        GenerationFinishReason.UNKNOWN, null -> "不明"
    }
    val limit = when (progress.limitReached) {
        true -> "到達"
        false -> "未到達"
        null -> "不明"
    }
    return "終了理由: $reason・maxTokens到達: $limit"
}

@Composable
private fun WorldSummaryCard(world: WorldDraft) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(world.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                listOf(world.genre, world.era).filter { it.isNotBlank() }.joinToString(" ／ "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (world.setting.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(world.setting, style = MaterialTheme.typography.bodyMedium)
            }
            if (world.atmosphere.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text("雰囲気: ${world.atmosphere}", style = MaterialTheme.typography.bodySmall)
            }
            if (world.objective.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text("目的: ${world.objective}", style = MaterialTheme.typography.bodySmall)
            }
            if (world.problem.isNotBlank()) {
                Text("問題: ${world.problem}", style = MaterialTheme.typography.bodySmall)
            }
            if (world.factions.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("勢力: ${world.factions.joinToString("、")}", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text("シナリオ", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(world.scenario.title, style = MaterialTheme.typography.titleSmall)
            if (world.scenario.premise.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(world.scenario.premise, style = MaterialTheme.typography.bodyMedium)
            }
            val meta = listOf(world.scenario.length, world.scenario.difficulty)
                .filter { it.isNotBlank() }.joinToString(" ／ ")
            if (meta.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun OpeningSummaryCard(world: WorldDraft) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("導入", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(world.opening.location.name, style = MaterialTheme.typography.titleSmall)
            if (world.opening.location.description.isNotBlank()) {
                Text(world.opening.location.description, style = MaterialTheme.typography.bodySmall)
            }
            if (world.opening.npcs.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("登場人物", style = MaterialTheme.typography.labelMedium)
                world.opening.npcs.forEach { npc ->
                    Text(
                        "・${npc.name}" +
                            listOf(npc.occupation, npc.disposition)
                                .filter { it.isNotBlank() }
                                .joinToString("（", "）", "") { it },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (world.opening.quests.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("クエスト", style = MaterialTheme.typography.labelMedium)
                world.opening.quests.forEach { quest ->
                    Text("・${quest.title}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun CharacterSummaryCard(character: CharacterDraft) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("主人公", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(character.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                listOf(character.species, character.occupation).filter { it.isNotBlank() }.joinToString(" ／ "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Text("能力値（合計 ${character.attributes.total()}）", style = MaterialTheme.typography.labelMedium)
            Text(
                AttributeId.entries.joinToString(" ／ ") { id ->
                    "${id.label} ${character.attributes.valueOf(id)}"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(10.dp))
            Text("スキル", style = MaterialTheme.typography.labelMedium)
            if (character.skills.isEmpty()) {
                Text("なし", style = MaterialTheme.typography.bodySmall)
            } else {
                Text(
                    character.skills.joinToString("、") { "${it.name} ${it.level}" },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text("所持品", style = MaterialTheme.typography.labelMedium)
            if (character.items.isEmpty()) {
                Text("なし", style = MaterialTheme.typography.bodySmall)
            } else {
                character.items.forEach { item ->
                    Text("・${item.name} ×${item.quantity}", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (character.personality.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text("性格: ${character.personality}", style = MaterialTheme.typography.bodySmall)
            }
            if (character.background.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text("経歴: ${character.background}", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "HP ${CharacterRules.INITIAL_HP} / レベル 1",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun ErrorCard(message: String, details: List<String>, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            details.take(5).forEach { detail ->
                Text(
                    "・$detail",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            TextButton(onClick = onDismiss) {
                Text("閉じる", color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}
