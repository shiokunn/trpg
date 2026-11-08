package io.github.shiokunn.trpg.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.shiokunn.trpg.ai.engine.EngineStatus
import io.github.shiokunn.trpg.core.ai.GmChoice
import io.github.shiokunn.trpg.core.domain.ChatMessage
import io.github.shiokunn.trpg.core.domain.ChatRole
import io.github.shiokunn.trpg.core.domain.CombatState
import io.github.shiokunn.trpg.core.domain.DiceResult
import io.github.shiokunn.trpg.core.domain.Difficulty
import io.github.shiokunn.trpg.game.GmTurnPhase
import io.github.shiokunn.trpg.ui.GameTurnUiState
import io.github.shiokunn.trpg.ui.GameViewModel
import io.github.shiokunn.trpg.ui.components.EmptyState
import io.github.shiokunn.trpg.ui.components.LoadingPane
import io.github.shiokunn.trpg.ui.components.NarrativeText
import io.github.shiokunn.trpg.ui.components.TrpgScaffold

@Composable
fun GameScreen(
    viewModel: GameViewModel,
    onBack: () -> Unit,
    onCharacter: () -> Unit,
    onWorld: () -> Unit,
    onJournal: () -> Unit,
) {
    val bundle by viewModel.bundle.collectAsStateWithLifecycle()
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val choices by viewModel.choices.collectAsStateWithLifecycle()
    val turn by viewModel.turn.collectAsStateWithLifecycle()
    val engineStatus by viewModel.engineStatus.collectAsStateWithLifecycle()

    val session = bundle?.session
    val modelReady = engineStatus is EngineStatus.Ready

    TrpgScaffold(
        title = session?.title ?: "冒険",
        onBack = onBack,
        actions = {
            IconButton(onClick = onCharacter) { Icon(Icons.Default.Person, contentDescription = "キャラクター") }
            IconButton(onClick = onWorld) { Icon(Icons.Default.Public, contentDescription = "世界情報") }
            IconButton(onClick = onJournal) { Icon(Icons.Default.MenuBook, contentDescription = "履歴") }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (bundle == null) {
                LoadingPane()
            } else {
                GameContent(
                    bundle = bundle!!,
                    chat = chat,
                    choices = choices?.choices.orEmpty(),
                    sceneTitle = choices?.sceneTitle.orEmpty(),
                    turn = turn,
                    modelReady = modelReady,
                    onBegin = viewModel::beginAdventure,
                    onChoose = viewModel::choose,
                    onAct = { text -> viewModel.act(text) },
                    onAttack = viewModel::attack,
                    onCancel = viewModel::cancelGeneration,
                    onDismissError = viewModel::clearError,
                )
            }
        }
    }
}

@Composable
private fun GameContent(
    bundle: io.github.shiokunn.trpg.data.repository.SessionBundle,
    chat: List<ChatMessage>,
    choices: List<GmChoice>,
    sceneTitle: String,
    turn: GameTurnUiState,
    modelReady: Boolean,
    onBegin: () -> Unit,
    onChoose: (GmChoice) -> Unit,
    onAct: (String) -> Unit,
    onAttack: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onDismissError: () -> Unit,
) {
    val current = bundle
    val location = current.locations.firstOrNull { it.id == current.session.currentLocationId }
    val character = current.character
    val hasNarrative = chat.any { it.role == ChatRole.GM }
    val recent = chat.takeLast(6)
    val busy = turn.running

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            StatusCard(
                locationName = location?.name ?: "未知の場所",
                chapter = current.session.currentChapter,
                turnCount = current.session.turnCount,
                characterName = character.name,
                species = character.species,
                occupation = character.occupation,
                hp = character.hp,
                maxHp = character.maxHp,
            )
        }

        if (!modelReady) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "モデルが読み込まれていません",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "「設定」→「モデル管理」からGGUFモデルを読み込むと物語を進められます。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }

        current.session.activeCombat?.let { combat ->
            item {
                CombatCard(
                    combat = combat,
                    enabled = !busy,
                    onAttack = onAttack,
                )
            }
        }

        if (sceneTitle.isNotBlank()) {
            item {
                Text(
                    text = "― $sceneTitle ―",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (!hasNarrative && !busy && turn.error == null) {
            item {
                EmptyState(
                    icon = Icons.Default.PlayArrow,
                    title = "物語を始めましょう",
                    message = if (modelReady) {
                        "最初の情景と選択肢をゲームマスターが生成します。"
                    } else {
                        "先にモデルを読み込んでください。"
                    },
                    actionLabel = if (modelReady) "冒険を始める" else null,
                    onAction = if (modelReady) onBegin else null,
                )
            }
        }

        if (hasNarrative) {
            items(recent, key = { it.id }) { message ->
                ChatMessageRow(message)
            }
        }

        if (busy) {
            item {
                StreamingCard(
                    phase = turn.phase,
                    streamedText = turn.streamedText,
                    onCancel = onCancel,
                )
            }
        }

        turn.lastCheck?.let { dice ->
            if (!busy) {
                item { CheckResultCard(dice) }
            }
        }

        if (turn.error != null) {
            item {
                ErrorCard(
                    message = turn.error,
                    details = turn.errorDetails,
                    onDismiss = onDismissError,
                )
            }
        }

        if (!busy && hasNarrative && turn.error == null && choices.isNotEmpty()) {
            item {
                Text(
                    "選択してください",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(choices, key = { it.id }) { choice ->
                ChoiceButton(choice = choice, onClick = { onChoose(choice) })
            }
        }

        if (!busy && modelReady && (hasNarrative || choices.isNotEmpty())) {
            item {
                FreeActionRow(onSend = onAct)
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(
                    "ステータス・所持品・クエストは各画面から確認できます。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusCard(
    locationName: String,
    chapter: Int,
    turnCount: Int,
    characterName: String,
    species: String,
    occupation: String,
    hp: Int,
    maxHp: Int,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(text = locationName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "第${chapter}章 ・ ターン $turnCount",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "$hp / $maxHp",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { if (maxHp <= 0) 0f else hp.toFloat() / maxHp },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.tertiary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = listOf(characterName, species, occupation).filter { it.isNotBlank() }.joinToString(" ／ "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CombatCard(
    combat: CombatState,
    enabled: Boolean,
    onAttack: (Boolean) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "戦闘中: ${combat.enemyName}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        "ラウンド ${combat.round} ／ 攻撃 ${combat.enemyAttackPower} ／ 防御 ${combat.enemyDefense}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                Text(
                    "HP ${combat.enemyHp}/${combat.enemyMaxHp}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = {
                    if (combat.enemyMaxHp <= 0) 0f
                    else combat.enemyHp.toFloat() / combat.enemyMaxHp
                },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.error,
                trackColor = MaterialTheme.colorScheme.errorContainer,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "戦闘はアプリのD6ルールで解決されます（AIは出目を決めません）。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onAttack(false) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("筋力で攻撃")
                }
                OutlinedButton(
                    onClick = { onAttack(true) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("敏捷で攻撃")
                }
            }
        }
    }
}

@Composable
private fun ChatMessageRow(message: ChatMessage) {
    when (message.role) {
        ChatRole.GM -> NarrativeText(message.content)
        ChatRole.PLAYER -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Text(
                    text = message.content,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        ChatRole.SYSTEM -> Text(
            text = message.content,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun StreamingCard(
    phase: GmTurnPhase?,
    streamedText: String,
    onCancel: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                    text = when (phase) {
                        is GmTurnPhase.Repairing -> "応答を修正しています（再試行 1/1）"
                        is GmTurnPhase.Committing -> "ターンを保存しています"
                        else -> "ゲームマスターが応答を生成しています"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (streamedText.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                NarrativeText(streamedText)
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onCancel) { Text("生成を中止") }
        }
    }
}

@Composable
private fun ChoiceButton(choice: GmChoice, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(choice.text, style = MaterialTheme.typography.bodyMedium)
            choice.check?.let { check ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append("判定: ").append(check.attribute.label)
                        append(" ／ 難易度 ").append(check.difficulty)
                        append("（").append(Difficulty.labelFor(check.difficulty)).append("）")
                        if (check.rollMode != io.github.shiokunn.trpg.core.domain.RollMode.NORMAL) {
                            append(" ／ ").append(check.rollMode.label)
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun FreeActionRow(onSend: (String) -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val submit = {
        val text = input.trim()
        if (text.isNotEmpty()) {
            onSend(text)
            input = ""
            focus.clearFocus()
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.weight(1f),
            label = { Text("自由行動") },
            placeholder = { Text("例: 扉の鍵穴を調べる") },
            maxLines = 3,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
        )
        IconButton(onClick = { submit() }, enabled = input.isNotBlank()) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "送信")
        }
    }
}

@Composable
private fun CheckResultCard(dice: DiceResult) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = "判定結果: ${dice.outcome.label}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = buildString {
                    append(dice.attributeId.label).append(" ").append(dice.attributeValue)
                    if (dice.skillName.isNotBlank()) append(" + ").append(dice.skillName).append(" ").append(dice.skillLevel)
                    append(" / 出目 ").append(dice.dice.joinToString(", "))
                    append(" = ").append(dice.total)
                    append(" / 難易度 ").append(dice.difficulty)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
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
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            if (details.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                details.take(5).forEach { detail ->
                    Text(
                        text = "・$detail",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            TextButton(onClick = onDismiss) {
                Text("閉じる", color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}
