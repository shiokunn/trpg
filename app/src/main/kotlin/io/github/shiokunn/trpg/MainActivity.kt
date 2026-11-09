package io.github.shiokunn.trpg

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.shiokunn.trpg.data.repository.AppPreferences
import io.github.shiokunn.trpg.data.repository.ThemeMode
import io.github.shiokunn.trpg.ui.navigation.TrpgNavHost
import io.github.shiokunn.trpg.ui.theme.TrpgTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as TrpgApplication).container
        setContent {
            val preferences by container.settings.preferences
                .collectAsStateWithLifecycle(initialValue = AppPreferences())
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (preferences.themeMode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> systemDark
            }
            TrpgTheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    TrpgNavHost(container = container)
                }
            }
        }
    }
}
