package io.github.shiokunn.trpg

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
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
                TrpgNavHost(container = container)
            }
        }
    }
}
