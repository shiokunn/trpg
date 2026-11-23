package io.github.shiokunn.trpg.ui.game

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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
import io.github.shiokunn.trpg.core.ai.GmErrorCategory
import io.github.shiokunn.trpg.core.domain.ChatMessage
import io.github.shiokunn.trpg.core.domain.ChatRole
import io.github.shiokunn.trpg.core.domain.CombatState
import io.github.shiokunn.trpg.game.GmTurnPhase
import io.github.shiokunn.trpg.ui.GameTurnUiState
import io.github.shiokunn.trpg.ui.GameViewModel
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
                    onChoose = { choice ->
                        if (modelReady) viewModel.choose(choice) else onOpenModels()
                    },
                    onAct = { text ->
                        if (modelReady) viewModel.act(text) else onOpenModels()
                    },
                    onAttack = viewModel::attack,
                    onCancel = viewModel::cancelGeneration,
                    onDismissError = viewModel::clearError,
                    onRetry = viewModel::regenerate,
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
    onChoose: (GmChoice) -> Unit,
    onAct: (String) -> Unit,
    onAttack: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onDismissError: () -> Unit,
    onRetry: () -> Unit,
) {
    val current = bundle
    val location = current.locations.firstOrNull { it.id == current.session.currentLocationId }
    val character = current.character
    val gmMessages = chat.filter { it.role == ChatRole.GM }
    val hasNarrative = gmMessages.isNotEmpty()
    val latestGmId = gmMessages.lastOrNull()?.id
    val gmOrdinals = gmMessages
        .mapIndexed { index, message -> message.id to index }
        .toMap()
    val busy = turn.running
    val view = LocalView.current

    // Android moves a background app to a much smaller cpuset (often cpu0-2),
    // which makes generation extremely slow. Keep the screen on while the model
    // is working so the user does not accidentally trigger that state.
    DisposableEffect(busy) {
        view.keepScreenOn = busy
        onDispose { view.keepScreenOn = false }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(20.dp),
    ) {
        StatusHeader(
            locationName = location?.name ?: "未知の場所",
            chapter = current.session.currentChapter,
            turnCount = current.session.turnCount,
            characterName = character.name,
            species = character.species,
            occupation = character.occupation,
            hp = character.hp,
            maxHp = character.maxHp,
        )

        Spacer(Modifier.height(12.dp))

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            // 65:35 is the baseline. Wider screens may give the narrative a little more room.
            val narrativeWeight = if (maxWidth >= 600.dp) 0.70f else 0.65f
            val choiceWeight = 1f - narrativeWeight

            Row(Modifier.fillMaxSize()) {
                AiMessageArea(
                    modifier = Modifier.weight(narrativeWeight).fillMaxHeight(),
                    sceneTitle = sceneTitle,
                    messages = gmMessages,
                    latestGmId = latestGmId,
                    gmOrdinals = gmOrdinals,
                    combat = current.session.activeCombat,
                    modelReady = modelReady,
                    busy = busy,
                    phase = turn.phase,
                    generatedTokens = turn.generatedTokens,
                    cancelling = turn.cancelling,
                    error = turn.error,
                    errorDetails = turn.errorDetails,
                    errorKind = turn.errorKind,
                    onCancel = onCancel,
                    onDismissError = onDismissError,
                    onRetry = onRetry,
                    onAttack = onAttack,
                )

                Spacer(Modifier.width(12.dp))

                ChoicePanel(
                    choices = choices,
                    visible = !busy && hasNarrative && turn.error == null &&
                        current.session.activeCombat == null,
                    enabled = modelReady && !busy,
                    onChoose = onChoose,
                    modifier = Modifier.weight(choiceWeight).fillMaxHeight(),
                )
            }
        }

        FreeActionRow(
            onSend = onAct,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Top area: the GM narrative plus scene title. Empty until the first answer arrives. */
@Composable
private fun AiMessageArea(
    modifier: Modifier,
    sceneTitle: String,
    messages: List<ChatMessage>,
    latestGmId: String?,
    gmOrdinals: Map<String, Int>,
    combat: CombatState?,
    modelReady: Boolean,
    busy: Boolean,
    phase: GmTurnPhase?,
    generatedTokens: Int,
    cancelling: Boolean,
    error: String?,
    errorDetails: List<String>,
    errorKind: GmErrorCategory?,
    onCancel: () -> Unit,
    onDismissError: () -> Unit,
    onRetry: () -> Unit,
    onAttack: (Boolean) -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        Column(Modifier.fillMaxWidth()) {
            if (!modelReady) {
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
                Spacer(Modifier.height(16.dp))
            }

            if (sceneTitle.isNotBlank()) {
                Text(
                    text = "― $sceneTitle ―",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
            }

            messages.takeLast(6).forEach { message ->
                if (message.id == latestGmId) {
                    if (gmOrdinals[message.id] == 0) {
                        FadeInContent { NarrativeText(message.content) }
                    } else {
                        ExpandRevealContent { NarrativeText(message.content) }
                    }
                } else {
                    NarrativeText(message.content)
                }
                Spacer(Modifier.height(12.dp))
            }

            combat?.let {
                CombatCard(combat = it, enabled = !busy, onAttack = onAttack)
                Spacer(Modifier.height(16.dp))
            }

            if (busy) {
                GeneratingIndicator(
                    phase = phase,
                    generatedTokens = generatedTokens,
                    cancelling = cancelling,
                    onCancel = onCancel,
                )
                Spacer(Modifier.height(16.dp))
            }

            if (error != null) {
                ErrorCard(
                    message = error,
                    details = errorDetails,
                    errorKind = errorKind,
                    onDismiss = onDismissError,
                    onRetry = onRetry,
                )
                Spacer(Modifier.height(16.dp))
            }
        }

        if (busy) {
            Text(
                text = "生成トークン: $generatedTokens",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp),
            )
        }
    }
}

/** Bottom-right area: fixed panel, its contents animate but the panel stays in place. */
@Composable
private fun ChoicePanel(
    choices: List<GmChoice>,
    visible: Boolean,
    enabled: Boolean,
    onChoose: (GmChoice) -> Unit,
    modifier: Modifier = Modifier,
) {
    val choicesKey = choices.joinToString { it.id + ":" + it.text }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        key(choicesKey) {
            AnimatedVisibility(
                visible = visible,
                enter = expandVertically(
                    animationSpec = tween(durationMillis = 698, easing = FastOutSlowInEasing),
                ) + fadeIn(animationSpec = tween(durationMillis = 698, easing = FastOutSlowInEasing)),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    choices.forEachIndexed { index, choice ->
                        ChoiceButton(
                            choice = choice,
                            number = index + 1,
                            enabled = enabled,
                            onClick = { onChoose(choice) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusHeader(
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
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = locationName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "第${chapter}章 ・ ターン $turnCount",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$hp / $maxHp",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 1,
                )
                Spacer(Modifier.width(8.dp))
                LinearProgressIndicator(
                    progress = { if (maxHp <= 0) 0f else hp.toFloat() / maxHp },
                    modifier = Modifier
                        .weight(0.35f)
                        .semantics { contentDescription = "HP $hp / $maxHp" },
                    color = MaterialTheme.colorScheme.tertiary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = listOf(characterName, species, occupation).filter { it.isNotBlank() }.joinToString(" ／ "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(0.65f),
                )
            }
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

/** Loading state for both the first and every later answer: no card or background. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GeneratingIndicator(
    phase: GmTurnPhase?,
    generatedTokens: Int,
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
        ContainedLoadingIndicator(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            indicatorColor = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = when {
                cancelling -> "生成を中止しています"
                phase is GmTurnPhase.Repairing -> "応答を修正しています"
                phase is GmTurnPhase.Committing -> "ターンを保存しています"
                generatedTokens <= 0 -> "読み込み中"
                else -> "作成中"
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

/** Unfolds later GM answers downward from just below the previous answer. */
@Composable
private fun ExpandRevealContent(content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(
            animationSpec = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
            expandFrom = Alignment.Top,
        ) + fadeIn(animationSpec = tween(durationMillis = 1000, easing = FastOutSlowInEasing)),
        exit = shrinkVertically() + fadeOut(),
    ) {
        Box(Modifier.fillMaxWidth()) { content() }
    }
}

@Composable
private fun ChoiceButton(
    choice: GmChoice,
    number: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text("$number. ${choice.text}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun FreeActionRow(onSend: (String) -> Unit, modifier: Modifier = Modifier) {
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
        modifier = modifier.fillMaxWidth(),
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
private fun ErrorCard(
    message: String,
    details: List<String>,
    errorKind: GmErrorCategory?,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
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
            if (errorKind != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "エラー種別: ${errorKind.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = "直前の行動から再度シナリオを生成できます。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRetry) {
                    Text("同じ行動で再生成", color = MaterialTheme.colorScheme.onErrorContainer)
                }
                TextButton(onClick = onDismiss) {
                    Text("閉じる", color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
    }
}
