package io.github.shiokunn.trpg.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.shiokunn.trpg.core.card.CharacterCard
import io.github.shiokunn.trpg.data.repository.ChatCharacter
import io.github.shiokunn.trpg.data.repository.ChatTurn
import io.github.shiokunn.trpg.ui.components.EmptyState
import io.github.shiokunn.trpg.ui.components.TrpgScaffold

/** Character card list and one-on-one chat, in the app's existing visual language. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CharacterChatScreen(viewModel: CharacterChatViewModel, onBack: () -> Unit) {
    val characters by viewModel.characters.collectAsStateWithLifecycle()
    val selectedId by viewModel.selectedId.collectAsStateWithLifecycle()
    val turns by viewModel.turns.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val creating by viewModel.creating.collectAsStateWithLifecycle()
    val selected = characters.firstOrNull { it.id == selectedId }

    var input by remember { mutableStateOf("") }
    var keywords by remember { mutableStateOf("") }
    var wish by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var personality by remember { mutableStateOf("") }
    var scenario by remember { mutableStateOf("") }
    var firstMessage by remember { mutableStateOf("") }
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    viewModel.importCard(stream.readBytes(), uri.lastPathSegment ?: "character")
                }
            }
        }
    }
    val openPicker = { picker.launch(arrayOf("*/*")) }

    TrpgScaffold(
        title = selected?.name ?: "キャラクターチャット",
        onBack = if (selected == null) onBack else ({ viewModel.select(null) }),
        actions = {
            if (selected != null) {
                TextButton(onClick = { viewModel.clearChat() }) { Text("履歴消去") }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            message?.let { text ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { viewModel.dismissMessage() }) { Text("閉じる") }
                    }
                }
                Spacer(Modifier.size(8.dp))
            }

            if (selected == null) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Text(
                            text = "キャラクターカード（JSON / PNG）の取り込みか、新規作成ができます。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.size(8.dp))
                        Button(onClick = openPicker) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("カードを取り込む")
                        }
                    }
                    item {
                        CreateCharacterCard(
                            creating = creating,
                            keywords = keywords,
                            onKeywords = { keywords = it },
                            wish = wish,
                            onWish = { wish = it },
                            name = name,
                            onName = { name = it },
                            description = description,
                            onDescription = { description = it },
                            personality = personality,
                            onPersonality = { personality = it },
                            scenario = scenario,
                            onScenario = { scenario = it },
                            firstMessage = firstMessage,
                            onFirstMessage = { firstMessage = it },
                            onAi = {
                                val seed = if (
                                    name.isBlank() && description.isBlank() && personality.isBlank() &&
                                    scenario.isBlank() && firstMessage.isBlank()
                                ) {
                                    null
                                } else {
                                    CharacterCard.Parsed(
                                        name = name,
                                        description = description,
                                        personality = personality,
                                        scenario = scenario,
                                        firstMessage = firstMessage,
                                    )
                                }
                                viewModel.createWithAi(keywords, wish, seed)
                            },
                            onManual = {
                                viewModel.createManual(name, description, personality, scenario, firstMessage)
                            },
                        )
                    }
                    if (characters.isEmpty()) {
                        item {
                            EmptyState(
                                icon = Icons.Default.Add,
                                title = "キャラクターがいません",
                                message = "ChatterUI形式のキャラクターカードを取り込んでください。",
                            )
                        }
                    }
                    items(characters, key = { it.id }) { character ->
                        CharacterRow(
                            character = character,
                            onClick = { viewModel.select(character.id) },
                            onDelete = { viewModel.deleteCharacter(character) },
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (turns.isEmpty()) {
                        Text(
                            text = "メッセージを送ると会話が始まります。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    turns.forEach { turn -> ChatBubble(turn = turn) }
                    if (busy) {
                        ContainedLoadingIndicator(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            indicatorColor = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text("メッセージ") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            viewModel.send(input)
                            input = ""
                        }),
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            viewModel.send(input)
                            input = ""
                        },
                        enabled = !busy && input.isNotBlank(),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "送信")
                    }
                }
            }
        }
    }
}

@Composable
private fun CharacterRow(
    character: ChatCharacter,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
            ) {
                TextButton(onClick = onClick) {
                    Text(character.name, style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    text = character.description.ifBlank { character.personality },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "削除")
            }
        }
    }
}

@Composable
private fun ChatBubble(turn: ChatTurn) {
    val isUser = turn.role == "user"
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                text = if (isUser) "あなた" else "キャラクター",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(4.dp))
            Text(text = turn.content, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** One form for both paths: AI fills blanks, or the entered values are saved as-is. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CreateCharacterCard(
    creating: Boolean,
    keywords: String,
    onKeywords: (String) -> Unit,
    wish: String,
    onWish: (String) -> Unit,
    name: String,
    onName: (String) -> Unit,
    description: String,
    onDescription: (String) -> Unit,
    personality: String,
    onPersonality: (String) -> Unit,
    scenario: String,
    onScenario: (String) -> Unit,
    firstMessage: String,
    onFirstMessage: (String) -> Unit,
    onAi: () -> Unit,
    onManual: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("キャラクターを作る", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = keywords,
                onValueChange = onKeywords,
                label = { Text("キーワード") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = wish,
                onValueChange = onWish,
                label = { Text("どんなキャラクターが欲しいか") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 2,
            )
            Text(
                text = "以下の項目は任意です。入力した項目は残し、空欄をAIが補完します。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = name,
                onValueChange = onName,
                label = { Text("名前") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = description,
                onValueChange = onDescription,
                label = { Text("設定・外見") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
            )
            OutlinedTextField(
                value = personality,
                onValueChange = onPersonality,
                label = { Text("性格") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
            )
            OutlinedTextField(
                value = scenario,
                onValueChange = onScenario,
                label = { Text("状況") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 2,
            )
            OutlinedTextField(
                value = firstMessage,
                onValueChange = onFirstMessage,
                label = { Text("最初の発言") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 2,
            )
            if (creating) {
                ContainedLoadingIndicator(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    indicatorColor = MaterialTheme.colorScheme.primary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAi, enabled = !creating, modifier = Modifier.weight(1f)) {
                    Text("AIに作ってもらう")
                }
                Button(onClick = onManual, enabled = !creating, modifier = Modifier.weight(1f)) {
                    Text("この内容で保存")
                }
            }
        }
    }
}
