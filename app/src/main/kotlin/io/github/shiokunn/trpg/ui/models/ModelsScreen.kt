package io.github.shiokunn.trpg.ui.models

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.shiokunn.trpg.ui.ModelsViewModel
import io.github.shiokunn.trpg.ui.components.EmptyState
import io.github.shiokunn.trpg.ui.components.TrpgScaffold

@Composable
fun ModelsScreen(viewModel: ModelsViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    TrpgScaffold(title = "モデル管理", onBack = onBack) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (settings == null || settings?.modelFileName.isNullOrBlank()) {
                EmptyState(
                    icon = Icons.Default.SmartToy,
                    title = "モデルが読み込まれていません",
                    message = "GGUF形式のモデルを端末内から選択すると、ここに一覧と推論設定が表示されます。",
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                EmptyState(
                    icon = Icons.Default.SmartToy,
                    title = settings?.modelDisplayName ?: "モデル",
                    message = "読み込み済みモデルの設定を表示します。",
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
