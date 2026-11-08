package io.github.shiokunn.trpg.ui.saves

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.shiokunn.trpg.data.db.RecentSessionRow
import io.github.shiokunn.trpg.ui.SavesViewModel
import io.github.shiokunn.trpg.ui.components.ConfirmDialog
import io.github.shiokunn.trpg.ui.components.EmptyState
import io.github.shiokunn.trpg.ui.components.TrpgScaffold
import io.github.shiokunn.trpg.ui.home.SessionCard

@Composable
fun SavesScreen(
    viewModel: SavesViewModel,
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<RecentSessionRow?>(null) }

    TrpgScaffold(title = "セーブデータ", onBack = onBack) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (sessions.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.MenuBook,
                    title = "セーブデータがありません",
                    message = "冒険を始めると、ターンごとに自動で保存されます。",
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(sessions, key = { it.id }) { session ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SessionCard(
                                session = session,
                                onClick = { onOpenSession(session.id) },
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { pendingDelete = session }) {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    contentDescription = "削除",
                                    tint = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { target ->
        ConfirmDialog(
            title = "セーブデータを削除",
            message = "「${target.title}」を削除します。キャラクター・NPC・記憶・履歴もすべて削除され、元に戻せません。",
            onConfirm = { viewModel.delete(target.id) },
            onDismiss = { pendingDelete = null },
        )
    }
}
