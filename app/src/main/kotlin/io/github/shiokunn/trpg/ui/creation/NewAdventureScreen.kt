package io.github.shiokunn.trpg.ui.creation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.shiokunn.trpg.ui.components.TrpgScaffold

@Composable
fun NewAdventureScreen(onBack: () -> Unit) {
    TrpgScaffold(title = "新しい冒険", onBack = onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(20.dp)) {
            Text("冒険の作成", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "世界設定を決め、AIによる自動生成またはユーザー設定＋AI補完でシナリオを作成します。",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
