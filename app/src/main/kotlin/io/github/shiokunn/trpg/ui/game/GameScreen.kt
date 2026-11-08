package io.github.shiokunn.trpg.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.shiokunn.trpg.core.domain.ChatRole
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

    val session = bundle?.session
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
                val current = bundle!!
                val location = current.locations.firstOrNull { it.id == current.session.currentLocationId }
                val character = current.character
                val lastGm = chat.lastOrNull { it.role == ChatRole.GM }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = location?.name ?: "未知の場所",
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        Text(
                                            text = "第${current.session.currentChapter}章 ・ ターン ${current.session.turnCount}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(
                                        text = "${character.hp} / ${character.maxHp}",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.tertiary,
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { if (character.maxHp <= 0) 0f else character.hp.toFloat() / character.maxHp },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = MaterialTheme.colorScheme.tertiary,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "${character.name} ／ ${character.species} ${character.occupation}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    if (lastGm == null) {
                        item {
                            EmptyState(
                                icon = Icons.Default.MenuBook,
                                title = "物語はまだ始まっていません",
                                message = "この冒険の導入はまだ生成されていません。モデルを読み込み、ゲームマスターの開始処理を実行してください。",
                            )
                        }
                    } else {
                        item {
                            NarrativeText(lastGm.content)
                        }
                    }

                    item {
                        Text(
                            text = "ここに情景描写と選択肢が表示されます。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
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
        }
    }
}
