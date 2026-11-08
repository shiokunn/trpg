package io.github.shiokunn.trpg.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "trpg_settings")

enum class ThemeMode(val storageValue: String, val label: String) {
    SYSTEM("system", "システム"),
    DARK("dark", "ダーク"),
    LIGHT("light", "ライト"),
    ;

    companion object {
        fun from(raw: String?): ThemeMode = entries.firstOrNull { it.storageValue == raw } ?: SYSTEM
    }
}

data class AppPreferences(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val lastModelFileName: String = "",
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val LAST_MODEL = stringPreferencesKey("last_model")
    }

    val preferences: Flow<AppPreferences> = context.settingsDataStore.data.map { prefs ->
        AppPreferences(
            themeMode = ThemeMode.from(prefs[Keys.THEME]),
            lastModelFileName = prefs[Keys.LAST_MODEL] ?: "",
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { it[Keys.THEME] = mode.storageValue }
    }

    suspend fun setLastModel(fileName: String) {
        context.settingsDataStore.edit { it[Keys.LAST_MODEL] = fileName }
    }
}
