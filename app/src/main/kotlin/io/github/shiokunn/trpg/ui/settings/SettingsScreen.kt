package io.github.shiokunn.trpg.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.shiokunn.trpg.BuildConfig
import io.github.shiokunn.trpg.backup.BackupFileManager
import io.github.shiokunn.trpg.core.debug.DebugConfig
import io.github.shiokunn.trpg.data.repository.ThemeMode
import io.github.shiokunn.trpg.ui.BackupUiState
import io.github.shiokunn.trpg.ui.SettingsViewModel
import io.github.shiokunn.trpg.ui.components.SectionTitle
import io.github.shiokunn.trpg.ui.components.TrpgScaffold

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenDebug: () -> Unit = {},
) {
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val backup by viewModel.backup.collectAsStateWithLifecycle()
    // DEBUG MODE (temporary)
    val debugEnabled by DebugConfig.enabled.collectAsStateWithLifecycle()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupFileManager.MIME_TYPE),
    ) { uri -> uri?.let(viewModel::exportBackup) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::prepareImport) }

    backup.pendingImport?.let { pending ->
        AlertDialog(
            onDismissRequest = viewModel::cancelPendingImport,
            title = { Text("バックアップを読み込みますか？") },
            text = {
                Text(
                    "世界${pending.worlds.size}件、セーブ${pending.sessions.size}件を含みます。" +
                        "同じIDのセーブは置き換えられます。読み込みは1つのトランザクションで実行され、" +
                        "失敗した場合は既存のデータは変更されません。",
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmImport) { Text("読み込む") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelPendingImport) { Text("キャンセル") }
            },
        )
    }

    TrpgScaffold(title = "設定", onBack = onBack) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { SectionTitle("外観", Modifier.padding(top = 0.dp)) }
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(vertical = 6.dp)) {
                            ThemeMode.entries.forEach { mode ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .selectable(
                                            selected = preferences.themeMode == mode,
                                            onClick = { viewModel.setTheme(mode) },
                                            role = Role.RadioButton,
                                        )
                                        .padding(horizontal = 12.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(
                                        selected = preferences.themeMode == mode,
                                        onClick = null,
                                    )
                                    Text(
                                        mode.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(start = 8.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                item { SectionTitle("データ管理") }
                item {
                    BackupCard(
                        state = backup,
                        onExport = {
                            exportLauncher.launch("trpg-backup-${System.currentTimeMillis()}.json")
                        },
                        onImport = {
                            importLauncher.launch(
                                arrayOf("application/json", "text/plain", "application/octet-stream"),
                            )
                        },
                        onDismiss = viewModel::clearBackupMessage,
                    )
                }

                // DEBUG MODE (temporary) — remove this item together with the debug package.
                item { SectionTitle("開発者向け（一時）") }
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("デバッグモード", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        if (debugEnabled) {
                                            "ON — 推論の処理時間・メモリ・実行設定を記録します"
                                        } else {
                                            "OFF — 通常動作"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (debugEnabled) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                                Switch(
                                    checked = debugEnabled,
                                    onCheckedChange = { DebugConfig.setEnabled(it) },
                                )
                            }
                            Text(
                                "推論の処理時間・メモリ・llama.cppの実行設定を確認するための一時的な機能です。" +
                                    "アプリを再起動するとOFFに戻り、進行中の推論やKVキャッシュには影響しません。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (debugEnabled) {
                                TextButton(onClick = onOpenDebug) { Text("デバッグ画面を開く") }
                            }
                        }
                    }
                }

                item { SectionTitle("このアプリについて") }
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("TRPGオフライン", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "バージョン ${BuildConfig.VERSION_NAME}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "ローカルLLMをゲームマスターとして利用する、選択肢型のTRPGアプリです。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BackupCard(
    state: BackupUiState,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "セーブデータと世界・記憶を1つのJSONファイルに書き出せます。" +
                    "モデル設定とGGUFファイルは端末ごとに異なるため含まれません。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onExport, enabled = !state.running, modifier = Modifier.weight(1f)) {
                    Text("エクスポート")
                }
                OutlinedButton(onClick = onImport, enabled = !state.running, modifier = Modifier.weight(1f)) {
                    Text("インポート")
                }
            }
            if (state.running) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ContainedLoadingIndicator(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        indicatorColor = MaterialTheme.colorScheme.primary,
                    )
                    Text("処理しています…", style = MaterialTheme.typography.labelSmall)
                }
            }
            state.message?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            state.error?.let { error ->
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                state.errors.take(5).forEach { detail ->
                    Text(
                        "・$detail",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (state.message != null || state.error != null) {
                Spacer(Modifier.height(2.dp))
                TextButton(onClick = onDismiss) { Text("閉じる") }
            }
        }
    }
}
