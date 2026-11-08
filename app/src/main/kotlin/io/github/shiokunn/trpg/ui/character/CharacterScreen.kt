package io.github.shiokunn.trpg.ui.character

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.shiokunn.trpg.core.domain.AttributeId
import io.github.shiokunn.trpg.core.rules.CharacterRules
import io.github.shiokunn.trpg.ui.CharacterViewModel
import io.github.shiokunn.trpg.ui.components.LoadingPane
import io.github.shiokunn.trpg.ui.components.SectionTitle
import io.github.shiokunn.trpg.ui.components.SessionMissingState
import io.github.shiokunn.trpg.ui.components.TrpgScaffold

@Composable
fun CharacterScreen(viewModel: CharacterViewModel, onBack: () -> Unit) {
    val bundle by viewModel.bundle.collectAsStateWithLifecycle()
    val missing by viewModel.missing.collectAsStateWithLifecycle()

    TrpgScaffold(title = "キャラクター", onBack = onBack) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val current = bundle
            if (missing) {
                SessionMissingState(onBack = onBack)
            } else if (current == null) {
                LoadingPane()
            } else {
                val character = current.character
                val level = CharacterRules.levelForXp(character.experience)
                val xpInLevel = CharacterRules.xpIntoLevel(character.experience)
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(18.dp)) {
                                Text(character.name, style = MaterialTheme.typography.headlineSmall)
                                Text(
                                    "${character.species} ／ ${character.occupation}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (character.personality.isNotBlank()) {
                                    Spacer(Modifier.height(8.dp))
                                    Text(character.personality, style = MaterialTheme.typography.bodyMedium)
                                }
                                Spacer(Modifier.height(14.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("HP", style = MaterialTheme.typography.labelMedium)
                                    Spacer(Modifier.size(8.dp))
                                    LinearProgressIndicator(
                                        progress = {
                                            if (character.maxHp <= 0) 0f else character.hp.toFloat() / character.maxHp
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .semantics {
                                                contentDescription = "HP ${character.hp} / ${character.maxHp}"
                                            },
                                        color = MaterialTheme.colorScheme.tertiary,
                                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                    )
                                    Spacer(Modifier.size(8.dp))
                                    Text(
                                        "${character.hp} / ${character.maxHp}",
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                                Spacer(Modifier.height(10.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Lv.$level", style = MaterialTheme.typography.labelMedium)
                                    Spacer(Modifier.size(8.dp))
                                    LinearProgressIndicator(
                                        progress = { xpInLevel / 10f },
                                        modifier = Modifier
                                            .weight(1f)
                                            .semantics {
                                                contentDescription = "次のレベルまで $xpInLevel / 10"
                                            },
                                        color = MaterialTheme.colorScheme.primary,
                                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                    )
                                    Spacer(Modifier.size(8.dp))
                                    Text(
                                        "XP ${character.experience}",
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            }
                        }
                    }

                    item { SectionTitle("能力値", Modifier.padding(top = 4.dp)) }
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                AttributeId.entries.forEach { id ->
                                    AttributeRow(id, character.attributes.valueOf(id))
                                }
                            }
                        }
                    }

                    item { SectionTitle("スキル", Modifier.padding(top = 4.dp)) }
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (character.skills.isEmpty()) {
                                    Text(
                                        "スキルはまだありません。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                character.skills.forEach { skill ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(skill.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            "Lv.${skill.level} / ${CharacterRules.SKILL_MAX}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item { SectionTitle("所持品", Modifier.padding(top = 4.dp)) }
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (character.inventory.isEmpty()) {
                                    Text(
                                        "所持品はありません。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                character.inventory.forEach { item ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                buildString {
                                                    append(item.name)
                                                    if (item.quantity > 1) append(" ×${item.quantity}")
                                                },
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                            if (item.description.isNotBlank()) {
                                                Text(
                                                    item.description,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                        if (item.power > 0 || item.defense > 0) {
                                            Text(
                                                "攻${item.power} 防${item.defense}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.outline,
                                            )
                                        }
                                        TextButton(onClick = { viewModel.toggleEquipment(item.id) }) {
                                            Text(
                                                if (item.equipped) "外す" else "装備",
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (character.background.isNotBlank()) {
                        item { SectionTitle("背景", Modifier.padding(top = 4.dp)) }
                        item {
                            Text(
                                character.background,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AttributeRow(id: AttributeId, value: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(id.label, Modifier.size(width = 48.dp, height = 20.dp), style = MaterialTheme.typography.bodyMedium)
        Text(
            id.meaning,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(5) { index ->
                Box(
                    Modifier
                        .size(width = 16.dp, height = 8.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            if (index < value) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                        ),
                )
            }
        }
        Spacer(Modifier.size(8.dp))
        Text("$value", style = MaterialTheme.typography.labelLarge)
    }
}
