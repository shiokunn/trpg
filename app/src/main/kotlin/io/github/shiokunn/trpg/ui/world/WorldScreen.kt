package io.github.shiokunn.trpg.ui.world

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.shiokunn.trpg.core.domain.Location
import io.github.shiokunn.trpg.core.domain.Npc
import io.github.shiokunn.trpg.core.domain.Quest
import io.github.shiokunn.trpg.core.domain.World
import io.github.shiokunn.trpg.ui.WorldViewModel
import io.github.shiokunn.trpg.ui.components.LoadingPane
import io.github.shiokunn.trpg.ui.components.SectionTitle
import io.github.shiokunn.trpg.ui.components.TrpgScaffold

@Composable
fun WorldScreen(viewModel: WorldViewModel, onBack: () -> Unit) {
    val bundle by viewModel.bundle.collectAsStateWithLifecycle()
    val memories by viewModel.memories.collectAsStateWithLifecycle()

    TrpgScaffold(title = "世界情報", onBack = onBack) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val current = bundle
            if (current == null) {
                LoadingPane()
            } else {
                val world = current.world
                val discovered = current.locations.filter { it.isDiscovered }
                val currentLocation = current.locations.firstOrNull { it.id == current.session.currentLocationId }
                val npcs = current.npcs.filter { it.isDiscovered }
                val openMemories = memories.filter { !it.isSecret }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Text(world.name, style = MaterialTheme.typography.headlineMedium)
                        Text(
                            listOf(world.genre, world.era).filter { it.isNotBlank() }.joinToString(" ／ "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    item { WorldSection("世界の雰囲気", world.atmosphere) }
                    item { WorldSection("世界設定", world.setting) }
                    if (world.history.isNotBlank()) item { WorldSection("歴史", world.history) }
                    if (world.geography.isNotBlank()) item { WorldSection("地理", world.geography) }
                    if (world.socialStructure.isNotBlank()) item { WorldSection("社会構造", world.socialStructure) }
                    if (world.techMagic.isNotBlank()) item { WorldSection("技術・魔法体系", world.techMagic) }
                    if (world.factions.isNotEmpty()) {
                        item { WorldListSection("主な勢力", world.factions) }
                    }
                    if (world.specialSettings.isNotEmpty()) {
                        item { WorldListSection("特殊設定", world.specialSettings) }
                    }
                    if (world.problem.isNotBlank()) item { WorldSection("世界の問題", world.problem) }
                    if (world.objective.isNotBlank()) item { WorldSection("物語の目的", world.objective) }

                    if (currentLocation != null) {
                        item { SectionTitle("現在地") }
                        item {
                            LocationCard(currentLocation, current = true)
                        }
                    }

                    item { SectionTitle("発見済みの場所") }
                    if (discovered.isEmpty()) {
                        item {
                            Text(
                                "まだ場所を発見していません。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        discovered.forEach { location ->
                            item { LocationCard(location, current = location.id == currentLocation?.id) }
                        }
                    }

                    item { SectionTitle("NPC") }
                    if (npcs.isEmpty()) {
                        item {
                            Text(
                                "まだ誰とも出会っていません。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        npcs.forEach { npc -> item { NpcCard(npc) } }
                    }

                    item { SectionTitle("クエスト") }
                    if (current.quests.isEmpty()) {
                        item {
                            Text(
                                "クエストはまだありません。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        current.quests.forEach { quest -> item { QuestCard(quest) } }
                    }

                    item { SectionTitle("発見済みの情報") }
                    if (openMemories.isEmpty()) {
                        item {
                            Text(
                                "まだ重要な情報を記録していません。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        openMemories.forEach { memory ->
                            item {
                                Row(verticalAlignment = Alignment.Top) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        modifier = Modifier.height(16.dp),
                                        tint = if (memory.isConfirmed) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outline
                                        },
                                    )
                                    Column(Modifier.padding(start = 8.dp)) {
                                        Text(memory.content, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            buildString {
                                                append(memory.category.label)
                                                append(" ・ 重要度 ${memory.importance}")
                                                if (!memory.isConfirmed) append(" ・ 推測")
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorldSection(title: String, body: String) {
    if (body.isBlank()) return
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun WorldListSection(title: String, values: List<String>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            values.forEach { Text("・$it", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun LocationCard(location: Location, current: Boolean) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.height(16.dp))
                Text(
                    location.name,
                    Modifier.padding(start = 6.dp),
                    style = MaterialTheme.typography.titleSmall,
                )
                if (current) {
                    Text(
                        "現在地",
                        Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (location.description.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(location.description, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun NpcCard(npc: Npc) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(npc.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    "関係 ${npc.relationship}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (npc.occupation.isNotBlank() || npc.disposition.isNotBlank()) {
                Text(
                    listOf(npc.occupation, npc.disposition).filter { it.isNotBlank() }.joinToString(" ／ "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!npc.isAlive) {
                Text("消息不明", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            if (npc.description.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(npc.description, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun QuestCard(quest: Quest) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(quest.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    quest.status.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (quest.status.name == "COMPLETED") {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (quest.description.isNotBlank()) {
                Text(quest.description, style = MaterialTheme.typography.bodySmall)
            }
            if (quest.objectives.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { quest.progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                quest.objectives.forEach { objective ->
                    Text(
                        (if (objective.completed) "☑ " else "☐ ") + objective.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (objective.completed) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}
