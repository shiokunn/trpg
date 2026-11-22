package io.github.shiokunn.trpg.ui.game

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import io.github.shiokunn.trpg.game.GmTurnPhase
import io.github.shiokunn.trpg.ui.GameTurnUiState
import io.github.shiokunn.trpg.ui.GameViewModel
import io.github.shiokunn.trpg.ui.components.EmptyState
import io.github.shiokunn.trpg.ui.components.LoadingPane
import io.github.shiokunn.trpg.ui.components.NarrativeText
import io.github.shiokunn.trpg.ui.components.SessionMissingState
import io.github.shiokunn.trpg.ui.components.TrpgScaffold

@Composable
fun GameScreen(
    viewModel: GameViewModel,
    onBack: () -> Unit,
    onCharacter: () -> Unit,
    onWorld: () -> Unit,
    onJournal: () -> Unit,
    onOpenModels: () -> Unit,
) {
    val bundle by viewModel.bundle.collectAsStateWithLifecycle()
    val missing by viewModel.missing.collectAsStateWithLifecycle()
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val choices by viewModel.choices.collectAsStateWithLifecycle()
    val turn by viewModel.turn.collectAsStateWithLifecycle()
    val engineStatus by viewModel.engineStatus.collectAsStateWithLifecycle()

    val session = bundle?.session
    val modelReady = engineStatus is EngineStatus.Ready

    // Roleplay cannot start without a model. On entry, send the player to the existing
    // model screen instead of starting a generation that would only fail. The guard
    // prevents a navigation loop if the player comes back before loading a model.
    val navigatedToModels = rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(engineStatus) {
        val needsModel = engineStatus is EngineStatus.Unloaded || engineStatus is EngineStatus.Failed
        if (!navigatedToModels.value && needsModel) {
            navigatedToModels.value = true
            onOpenModels()
        }
    }

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
            if (missing) {
                SessionMissingState(onBack = onBack)
            } else if (bundle == null) {
                LoadingPane()
            } else {
                GameContent(
                    bundle = bundle!!,
                    chat = chat,
                    choices = choices?.choices.orEmpty(),
                    sceneTitle = choices?.sceneTitle.orEmpty(),
                    turn = turn,
                    modelReady = modelReady,
                    onBegin = { if (modelReady) viewModel.beginAdventure() else onOpenModels() },
                    onChoose = { choice ->
                        if (modelReady) viewModel.choose(choice) else onOpenModels()
                    },
                    onAct = { text ->
                        if (modelReady) viewModel.act(text) else onOpenModels()
                    },
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
    val latestGmId = chat.lastOrNull { it.role == ChatRole.GM }?.id
    val recent = chat.takeLast(6)
    val busy = turn.running
    val view = LocalView.current

    // Android moves a background app to a much smaller cpuset (often cpu0-2),
    // which makes generation extremely slow. Keep the screen on while the model
    // is working so the user does not accidentally trigger that state.
    DisposableEffect(busy) {
        view.keepScreenOn = busy
        onDispose { view.keepScreenOn = false }
    }

    Box(Modifier.fillMaxSize()) {
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
                    actionLabel = "冒険を始める",
                    onAction = onBegin,
                )
            }
        }

        if (hasNarrative) {
            items(recent, key = { it.id }) { message ->
                // Only the freshly generated GM message fades in; older messages stay put.
                if (message.role == ChatRole.GM && message.id == latestGmId) {
                    FadeInContent { ChatMessageRow(message) }
                } else {
                    ChatMessageRow(message)
                }
            }
        }

        if (busy) {
            item {
                if (!hasNarrative) {
                    // Very first turn: dots only, no card or explanatory copy.
                    InitialGenerating(
                        phase = turn.phase,
                        cancelling = turn.cancelling,
                        onCancel = onCancel,
                    )
                } else {
                    StreamingCard(
                        phase = turn.phase,
                        generatedTokens = turn.generatedTokens,
                        cancelling = turn.cancelling,
                        onCancel = onCancel,
                    )
                }
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

        val showChoices = !busy && hasNarrative && turn.error == null && choices.isNotEmpty() &&
            current.session.activeCombat == null
        val choicesKey = sceneTitle + "|" + choices.joinToString { it.id + ":" + it.text }
        item {
            key(choicesKey) {
                AnimatedVisibility(
                    visible = showChoices,
                    enter = expandVertically(
                        animationSpec = tween(
                            durationMillis = 698,
                            easing = FastOutSlowInEasing,
                        ),
                    ) + fadeIn(animationSpec = tween(durationMillis = 698, easing = FastOutSlowInEasing)),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        choices.forEach { choice ->
                            ChoiceButton(
                                choice = choice,
                                enabled = modelReady && !busy,
                                onClick = { onChoose(choice) },
                            )
                        }
                    }
                }
            }
        }

        if (!busy && modelReady && current.session.activeCombat == null &&
            (hasNarrative || choices.isNotEmpty())
        ) {
            item {
                FreeActionRow(onSend = onAct)
            }
        }

        }
        // Initial generation has no narrative card to host the counter, so pin it to
        // the bottom-right of the screen. Normal turns keep it inside the shimmer card.
        if (busy && !hasNarrative) {
            Text(
                text = "生成トークン: ${turn.generatedTokens}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp),
            )
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
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "HP $hp / $maxHp" },
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
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "敵HP ${combat.enemyHp} / ${combat.enemyMaxHp}"
                    },
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
                    text = message.content.withoutCheckPrefix(),
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

/** Keeps the stored game log intact while hiding the check prefix on screen. */
private fun String.withoutCheckPrefix(): String {
    if (!startsWith("【判定:")) return this
    val action = substringAfter('\n', "")
    return action.ifBlank { this }
}

/** Five dots falling from above one after another and bouncing. First turn only. */
@Composable
private fun FallBounceDots() {
    val transition = rememberInfiniteTransition(label = "fallBounce")

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(5) { index ->
            val offsetY by transition.animateFloat(
                initialValue = -24f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 1200
                        -24f at 0
                        0f at 520 using FastOutSlowInEasing
                        -10f at 760
                        0f at 1000 using FastOutSlowInEasing
                        0f at 1200
                    },
                    repeatMode = RepeatMode.Restart,
                    initialStartOffset = StartOffset(index * 140),
                ),
                label = "dot$index",
            )
            Box(
                modifier = Modifier
                    .offset(y = offsetY.dp)
                    .size(8.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
        }
    }
}

/** First-turn loading state: no card, no background, no explanatory paragraph. */
@Composable
private fun InitialGenerating(
    phase: GmTurnPhase?,
    cancelling: Boolean,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        FallBounceDots()
        Spacer(Modifier.height(16.dp))
        Text(
            text = when (phase) {
                is GmTurnPhase.Repairing -> "応答を修正しています"
                is GmTurnPhase.Committing -> "ターンを保存しています"
                else -> if (cancelling) "生成を中止しています" else "生成中"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        TextButton(
            onClick = onCancel,
            enabled = !cancelling,
        ) { Text(if (cancelling) "中止中" else "生成を中止") }
    }
}

/** Fades newly generated content in once per message. Older messages stay in place. */
@Composable
private fun FadeInContent(content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 1143, easing = LinearOutSlowInEasing),
        label = "fadeIn",
    )
    Box(Modifier.fillMaxWidth().alpha(alpha)) { content() }
}

@Composable
private fun StreamingCard(
    phase: GmTurnPhase?,
    generatedTokens: Int,
    cancelling: Boolean,
    onCancel: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Shimmer(
                modifier = Modifier.semantics { contentDescription = "ゲームマスターが生成中" },
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = when (phase) {
                    is GmTurnPhase.Repairing -> "応答を修正しています（再試行 1/1）"
                    is GmTurnPhase.Committing -> "ターンを保存しています"
                    else -> if (cancelling) "生成を中止しています" else "ゲームマスターが応答を生成しています"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "画面を消さないでください（背景では推論が遅くなります）。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = onCancel,
                enabled = !cancelling,
            ) { Text(if (cancelling) "中止中" else "生成を中止") }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = "生成トークン: $generatedTokens",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Light sweep from left to right. Only composed while generation is running. */
@Composable
private fun Shimmer(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(952, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer",
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        val width = constraints.maxWidth.toFloat()
        val start = (progress * (width + width * 0.32f)) - width * 0.32f
        val brush = Brush.linearGradient(
            colors = listOf(
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.colorScheme.surface,
                MaterialTheme.colorScheme.surfaceVariant,
            ),
            start = Offset(start, 0f),
            end = Offset(start + width * 0.32f, width * 0.31111112f),
        )
        Box(Modifier.fillMaxSize().background(brush))
    }
}

@Composable
private fun ChoiceButton(choice: GmChoice, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(choice.text, style = MaterialTheme.typography.bodyMedium)
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
