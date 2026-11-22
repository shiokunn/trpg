package io.github.shiokunn.trpg.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.MaterialTheme
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
import io.github.shiokunn.trpg.data.db.RecentSessionRow
import io.github.shiokunn.trpg.ui.HomeViewModel
import io.github.shiokunn.trpg.ui.components.ConfirmDialog
import io.github.shiokunn.trpg.ui.components.EmptyState
import io.github.shiokunn.trpg.ui.components.SectionTitle
import io.github.shiokunn.trpg.ui.components.formatDateTime

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onNewAdventure: () -> Unit,
    onOpenSession: (String) -> Unit,
    onSaves: () -> Unit,
    onModels: () -> Unit,
    onSettings: () -> Unit,
) {
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<RecentSessionRow?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentPadding = PaddingValues(start = 20.dp, top = 28.dp, end = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "TRPGオフライン",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.size(10.dp))
                        Text("新しい冒険", style = MaterialTheme.typography.titleLarge)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "世界を決め、キャラクターを作り、AIのゲームマスターと物語を進めます。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onNewAdventure) {
                        Text("冒険を始める")
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HomeQuickAction("セーブ", Icons.Default.MenuBook, Modifier.weight(1f), onSaves)
                HomeQuickAction("モデル", Icons.Default.SmartToy, Modifier.weight(1f), onModels)
                HomeQuickAction("設定", Icons.Default.Settings, Modifier.weight(1f), onSettings)
            }
        }

        item { SectionTitle("最近の冒険") }

        if (recent.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Default.Public,
                    title = "まだ冒険がありません",
                    message = "「冒険を始める」から、最初の世界とキャラクターを作成してください。",
                )
            }
        } else {
            items(recent, key = { it.id }) { session ->
                DismissibleSessionCard(
                    session = session,
                    onClick = { onOpenSession(session.id) },
                    onRequestDelete = { pendingDelete = session },
                )
            }
        }
    }

    pendingDelete?.let { session ->
        ConfirmDialog(
            title = "冒険を削除",
            message = "「${session.title}」を削除します。この操作は取り消せません。",
            onConfirm = {
                viewModel.delete(session.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/** A session card that can be deleted by long press or by swiping left. */
@Composable
private fun DismissibleSessionCard(
    session: RecentSessionRow,
    onClick: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart ||
                value == SwipeToDismissBoxValue.StartToEnd
            ) {
                onRequestDelete()
            }
            false
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.error)
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "削除",
                    tint = MaterialTheme.colorScheme.onError,
                )
            }
        },
        enableDismissFromStartToEnd = false,
        content = {
            SessionCard(
                session = session,
                onClick = onClick,
                onLongClick = onRequestDelete,
            )
        },
    )
}

@Composable
private fun HomeQuickAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        TextButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(vertical = 4.dp),
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.height(2.dp))
                Text(label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
fun SessionCard(
    session: RecentSessionRow,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = session.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "最終プレイ ${formatDateTime(session.lastPlayedAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
