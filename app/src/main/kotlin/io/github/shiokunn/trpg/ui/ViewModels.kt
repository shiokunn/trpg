package io.github.shiokunn.trpg.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.shiokunn.trpg.AppContainer
import io.github.shiokunn.trpg.ai.engine.BackendInfo
import io.github.shiokunn.trpg.ai.engine.EngineStatus
import io.github.shiokunn.trpg.ai.model.ModelFileManager
import io.github.shiokunn.trpg.core.domain.ModelSettings
import io.github.shiokunn.trpg.data.db.RecentSessionRow
import io.github.shiokunn.trpg.data.repository.AppPreferences
import io.github.shiokunn.trpg.data.repository.SessionBundle
import io.github.shiokunn.trpg.data.repository.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

data class ImportProgress(
    val copied: Long = 0L,
    val total: Long = -1L,
) {
    val fraction: Float?
        get() = if (total > 0L) (copied.toFloat() / total.toFloat()).coerceIn(0f, 1f) else null
}

class ModelsViewModel(private val container: AppContainer) : ViewModel() {
    val settings: StateFlow<ModelSettings?> = container.models.observeSettings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val engineStatus: StateFlow<EngineStatus> = container.engine.status

    private val _models = MutableStateFlow(container.modelFiles.listModels())
    val models: StateFlow<List<ModelFileManager.ModelFile>> = _models.asStateFlow()

    private val _backend = MutableStateFlow(BackendInfo())
    val backend: StateFlow<BackendInfo> = _backend.asStateFlow()

    private val _importProgress = MutableStateFlow<ImportProgress?>(null)
    val importProgress: StateFlow<ImportProgress?> = _importProgress.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var importJob: Job? = null

    init {
        viewModelScope.launch { _backend.value = container.engine.backendInfo() }
    }

    fun refreshModels() {
        _models.value = container.modelFiles.listModels()
    }

    fun importModel(uri: Uri) {
        if (importJob?.isActive == true) return
        importJob = viewModelScope.launch {
            _importProgress.value = ImportProgress()
            try {
                container.modelFiles.importModel(uri) { copied, total ->
                    _importProgress.value = ImportProgress(copied, total)
                }.fold(
                    onSuccess = { file ->
                        refreshModels()
                        val current = container.models.settings() ?: ModelSettings()
                        container.models.save(
                            current.normalized().copy(
                                modelFileName = file.fileName,
                                modelDisplayName = file.displayName,
                            ),
                        )
                        container.settings.setLastModel(file.fileName)
                        _message.value = "「${file.displayName}」を取り込みました。「読み込む」で使用できます。"
                    },
                    onFailure = { error ->
                        _message.value = error.message ?: "モデルの取り込みに失敗しました"
                    },
                )
            } catch (t: CancellationException) {
                _message.value = "取り込みをキャンセルしました"
            } finally {
                _importProgress.value = null
            }
        }
    }

    fun cancelImport() {
        importJob?.cancel()
    }

    fun selectModel(model: ModelFileManager.ModelFile) {
        viewModelScope.launch {
            val current = container.models.settings() ?: ModelSettings()
            container.models.save(
                current.normalized().copy(
                    modelFileName = model.fileName,
                    modelDisplayName = model.displayName,
                ),
            )
            container.settings.setLastModel(model.fileName)
            _message.value = "「${model.displayName}」を選択しました。"
        }
    }

    fun loadModel(model: ModelFileManager.ModelFile) {
        viewModelScope.launch {
            if (container.engine.status.value is EngineStatus.Loading) return@launch
            val current = (container.models.settings() ?: ModelSettings()).normalized()
            val path = container.modelFiles.resolve(model.fileName)
            if (path == null) {
                _message.value = "モデルファイルが見つかりません。取り込み直してください。"
                refreshModels()
                return@launch
            }
            container.engine.load(path.absolutePath, model.fileName, current).fold(
                onSuccess = { _message.value = "モデルを読み込みました。" },
                onFailure = { error ->
                    _message.value = error.message ?: "モデルの読み込みに失敗しました"
                },
            )
        }
    }

    fun unloadModel() {
        viewModelScope.launch {
            container.engine.unload()
            _message.value = "モデルを解放しました。"
        }
    }

    fun saveParams(modelSettings: ModelSettings) {
        viewModelScope.launch {
            container.models.save(modelSettings.normalized())
            _message.value = "推論設定を保存しました。コンテキストサイズとGPUレイヤーは再読み込みで反映されます。"
        }
    }

    fun deleteModel(model: ModelFileManager.ModelFile) {
        viewModelScope.launch {
            container.modelFiles.delete(model.fileName).fold(
                onSuccess = {
                    refreshModels()
                    val current = container.models.settings()
                    if (current?.modelFileName == model.fileName) {
                        container.models.save(
                            current.copy(modelFileName = "", modelDisplayName = ""),
                        )
                        container.settings.setLastModel("")
                        container.engine.unload()
                    }
                    _message.value = "「${model.displayName}」を削除しました。"
                },
                onFailure = { error ->
                    _message.value = error.message ?: "モデルの削除に失敗しました"
                },
            )
        }
    }

    fun dismissMessage() {
        _message.value = null
    }
}
