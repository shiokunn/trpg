package io.github.shiokunn.trpg.ui.journal

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.shiokunn.trpg.core.domain.ChatRole
import io.github.shiokunn.trpg.ui.JournalViewModel
import io.github.shiokunn.trpg.ui.components.EmptyState
import io.github.shiokunn.trpg.ui.components.SessionMissingState
import io.github.shiokunn.trpg.ui.components.TrpgScaffold
import io.github.shiokunn.trpg.ui.components.formatDateTime
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MenuBook

@Composable
fun JournalScreen(viewModel: JournalViewModel, onBack: () -> Unit) {
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val events by viewModel.events.collectAsStateWithLifecycle()
    val memories by viewModel.memories.collectAsStateWithLifecycle()
    val dice by viewModel.dice.collectAsStateWithLifecycle()
    val missing by viewModel.missing.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }

    TrpgScaffold(title = "履歴と記録", onBack = onBack) { padding ->
        if (missing) {
            SessionMissingState(onBack = onBack, modifier = Modifier.fillMaxSize().padding(padding))
        } else Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(
                selectedTabIndex = tab,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                listOf("会話", "イベント", "記憶", "判定").forEachIndexed { index, label ->
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        text = { Text(label) },
                    )
                }
            }
            Box(Modifier.fillMaxSize()) {
                when (tab) {
                    0 -> ChatTab(chat, onBack)
                    1 -> EventTab(events)
                    2 -> MemoryTab(memories.filter { !it.isSecret })
                    3 -> DiceTab(dice)
                }
            }
        }
    }
}

@Composable
private fun ChatTab(
    chat: List<io.github.shiokunn.trpg.core.domain.ChatMessage>,
    onBack: () -> Unit,
) {
    if (chat.isEmpty()) {
        EmptyState(
            icon = Icons.Default.MenuBook,
            title = "会話履歴がありません",
            message = "冒険が始まると、ゲームマスターとあなたのやり取りがここに残ります。",
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(chat, key = { it.id }) { message ->
            val isPlayer = message.role == ChatRole.PLAYER
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (isPlayer) Arrangement.End else Arrangement.Start,
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(0.88f),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isPlayer) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                    ),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = when (message.role) {
                                ChatRole.GM -> "GM"
                                ChatRole.PLAYER -> "あなた"
                                ChatRole.SYSTEM -> "システム"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            message.content,
                            style = if (isPlayer) {
                                MaterialTheme.typography.bodyMedium
                            } else {
                                MaterialTheme.typography.bodyLarge
                            },
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "ターン ${message.turn} ・ ${formatDateTime(message.createdAt)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EventTab(events: List<io.github.shiokunn.trpg.core.domain.GameEvent>) {
    if (events.isEmpty()) {
        EmptyState(
            icon = Icons.Default.History,
            title = "イベントがありません",
            message = "物語の節目や重要な出来事がここに記録されます。",
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(events, key = { it.id }) { event ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(event.type, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text(
                            "  ターン ${event.turn}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    Text(event.summary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun MemoryTab(memories: List<io.github.shiokunn.trpg.core.domain.StoryMemory>) {
    if (memories.isEmpty()) {
        EmptyState(
            icon = Icons.Default.MenuBook,
            title = "記憶がありません",
            message = "重要な出来事や発見は、物語の長期記憶として保存されます。",
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(memories, key = { it.id }) { memory ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "${memory.category.label} ・ 重要度 ${memory.importance}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(memory.content, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        buildString {
                            append("ターン ${memory.turn}")
                            if (!memory.isConfirmed) append(" ・ 推測")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

@Composable
private fun DiceTab(dice: List<io.github.shiokunn.trpg.core.domain.DiceResult>) {
    if (dice.isEmpty()) {
        EmptyState(
            icon = Icons.Default.History,
            title = "判定履歴がありません",
            message = "能力値やスキルを使った判定の結果が、ダイス目を含めて記録されます。",
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(dice.size) { index ->
            val roll = dice[index]
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            roll.outcome.label,
                            style = MaterialTheme.typography.titleSmall,
                            color = when (roll.outcome.name) {
                                "CRITICAL_SUCCESS" -> MaterialTheme.colorScheme.primary
                                "FAILURE" -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${roll.total} / 難易度 ${roll.difficulty}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        buildString {
                            append(roll.attributeId.label)
                            if (roll.skillName.isNotBlank()) append(" + ${roll.skillName}(${roll.skillLevel})")
                            append(" ・ ダイス ${roll.dice.joinToString(", ")}")
                            append(" (${roll.rollMode.label})")
                            if (roll.reason.isNotBlank()) append(" ・ ${roll.reason}")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Start,
                    )
                    Text(
                        "ターン ${roll.turn}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}
