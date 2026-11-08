package io.github.shiokunn.trpg.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.shiokunn.trpg.AppContainer
import io.github.shiokunn.trpg.core.domain.ModelSettings
import io.github.shiokunn.trpg.data.db.RecentSessionRow
import io.github.shiokunn.trpg.data.repository.AppPreferences
import io.github.shiokunn.trpg.data.repository.SessionBundle
import io.github.shiokunn.trpg.data.repository.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

inline fun <reified VM : ViewModel> createFactory(crossinline create: () -> VM): ViewModelProvider.Factory =
    viewModelFactory { initializer { create() } }

class HomeViewModel(private val container: AppContainer) : ViewModel() {
    val recent: StateFlow<List<RecentSessionRow>> = container.sessions.observeRecentDetails(8)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

class SavesViewModel(private val container: AppContainer) : ViewModel() {
    val sessions: StateFlow<List<RecentSessionRow>> = container.sessions.observeRecentDetails(200)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(sessionId: String) {
        viewModelScope.launch { container.sessions.delete(sessionId) }
    }
}

class GameViewModel(
    private val container: AppContainer,
    val sessionId: String,
) : ViewModel() {
    val bundle: StateFlow<SessionBundle?> = container.sessions.observeBundle(sessionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val chat = container.memories.observeChat(sessionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

class CharacterViewModel(
    private val container: AppContainer,
    val sessionId: String,
) : ViewModel() {
    val bundle: StateFlow<SessionBundle?> = container.sessions.observeBundle(sessionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

class WorldViewModel(
    private val container: AppContainer,
    val sessionId: String,
) : ViewModel() {
    val bundle: StateFlow<SessionBundle?> = container.sessions.observeBundle(sessionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val memories = container.memories.observeMemories(sessionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

class JournalViewModel(
    private val container: AppContainer,
    val sessionId: String,
) : ViewModel() {
    val chat = container.memories.observeChat(sessionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val events = container.memories.observeEvents(sessionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val memories = container.memories.observeMemories(sessionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val dice = container.memories.observeDice(sessionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val preferences: StateFlow<AppPreferences> = container.settings.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppPreferences())

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { container.settings.setThemeMode(mode) }
    }
}

class ModelsViewModel(private val container: AppContainer) : ViewModel() {
    val settings: StateFlow<ModelSettings?> = container.models.observeSettings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
