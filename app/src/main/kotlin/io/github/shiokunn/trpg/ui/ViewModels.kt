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
import io.github.shiokunn.trpg.core.ai.CharacterDraft
import io.github.shiokunn.trpg.core.ai.GmChoice
import io.github.shiokunn.trpg.core.ai.TurnChoices
import io.github.shiokunn.trpg.core.ai.WorldDraft
import io.github.shiokunn.trpg.creation.AdventureAssembler
import io.github.shiokunn.trpg.creation.CreationInput
import io.github.shiokunn.trpg.creation.CreationMode
import io.github.shiokunn.trpg.creation.CreationResult
import io.github.shiokunn.trpg.core.domain.DiceResult
import io.github.shiokunn.trpg.core.domain.ModelSettings
import io.github.shiokunn.trpg.data.db.RecentSessionRow
import io.github.shiokunn.trpg.data.repository.AppPreferences
import io.github.shiokunn.trpg.data.repository.SessionBundle
import io.github.shiokunn.trpg.data.repository.ThemeMode
import io.github.shiokunn.trpg.game.GameEventTypes
import io.github.shiokunn.trpg.game.GmTurnListener
import io.github.shiokunn.trpg.game.GmTurnPhase
import io.github.shiokunn.trpg.game.GmTurnResult
import io.github.shiokunn.trpg.game.PlayerTurnAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

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

/** UI state for one GM turn. Nothing here is written to the database until the turn succeeds. */
data class GameTurnUiState(
    val running: Boolean = false,
    val phase: GmTurnPhase? = null,
    val streamedText: String = "",
    val error: String? = null,
    val errorDetails: List<String> = emptyList(),
    val lastCheck: DiceResult? = null,
)

class GameViewModel(
    private val container: AppContainer,
    val sessionId: String,
) : ViewModel() {
    val bundle: StateFlow<SessionBundle?> = container.sessions.observeBundle(sessionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val chat = container.memories.observeChat(sessionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val choices: StateFlow<TurnChoices?> = container.memories.observeEvents(sessionId)
        .map { events ->
            events.lastOrNull { it.type == GameEventTypes.CHOICES }?.let { event ->
                runCatching {
                    ChoicesJson.decodeFromString(TurnChoices.serializer(), event.payloadJson)
                }.getOrNull()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val engineStatus: StateFlow<EngineStatus> = container.engine.status

    private val _turn = MutableStateFlow(GameTurnUiState())
    val turn: StateFlow<GameTurnUiState> = _turn.asStateFlow()

    private var turnJob: Job? = null

    fun beginAdventure() {
        startTurn(PlayerTurnAction("冒険を開始する"))
    }

    fun act(text: String) {
        startTurn(PlayerTurnAction(text))
    }

    fun choose(choice: GmChoice) {
        startTurn(PlayerTurnAction(choice.text, choice.check))
    }

    fun cancelGeneration() {
        container.engine.cancel()
    }

    fun clearError() {
        _turn.update { it.copy(error = null, errorDetails = emptyList()) }
    }

    private fun startTurn(action: PlayerTurnAction) {
        if (turnJob?.isActive == true) return
        if (action.text.isBlank()) return
        _turn.value = GameTurnUiState(running = true, phase = GmTurnPhase.Preparing)
        turnJob = viewModelScope.launch {
            val listener = object : GmTurnListener {
                override suspend fun onPhase(phase: GmTurnPhase) {
                    _turn.update {
                        val restart = phase is GmTurnPhase.Generating || phase is GmTurnPhase.Repairing
                        it.copy(phase = phase, streamedText = if (restart) "" else it.streamedText)
                    }
                }

                override suspend fun onToken(piece: String) {
                    _turn.update { it.copy(streamedText = it.streamedText + piece) }
                }
            }
            val result = container.gmTurns.playTurn(sessionId, action, listener)
            _turn.update { previous ->
                when (result) {
                    is GmTurnResult.Success -> GameTurnUiState(lastCheck = result.dice)
                    is GmTurnResult.Cancelled -> previous.copy(
                        running = false,
                        phase = null,
                        streamedText = "",
                        error = "生成をキャンセルしました。このターンは保存されていません。",
                    )
                    is GmTurnResult.Failed -> previous.copy(
                        running = false,
                        phase = null,
                        streamedText = "",
                        error = result.message,
                        errorDetails = result.errors,
                    )
                }
            }
        }
    }

    private companion object {
        val ChoicesJson = Json { ignoreUnknownKeys = true }
    }
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
    val npcMemories = container.memories.observeNpcMemoriesBySession(sessionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

class JournalViewModel(
    private val container: AppContainer,
    val sessionId: String,
) : ViewModel() {
    val chat = container.memories.observeChat(sessionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val events = container.memories.observeEvents(sessionId)
        .map { list -> list.filter { it.type != GameEventTypes.CHOICES } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val memories = container.memories.observeMemories(sessionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val dice = container.memories.observeDice(sessionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

enum class CreationStage { FORM, GENERATING, PREVIEW }

data class NewAdventureUiState(
    val mode: CreationMode = CreationMode.AUTO,
    val keywords: String = "",
    val setting: String = "",
    val protagonistWish: String = "",
    val tone: String = "",
    val difficulty: String = "",
    val stage: CreationStage = CreationStage.FORM,
    val statusText: String = "",
    val world: WorldDraft? = null,
    val character: CharacterDraft? = null,
    val error: String? = null,
    val details: List<String> = emptyList(),
    val saving: Boolean = false,
    val createdSessionId: String? = null,
)

class NewAdventureViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(NewAdventureUiState())
    val state: StateFlow<NewAdventureUiState> = _state.asStateFlow()

    val engineStatus: StateFlow<EngineStatus> = container.engine.status

    private var job: Job? = null

    fun setMode(mode: CreationMode) = _state.update { it.copy(mode = mode) }

    fun setKeywords(value: String) = _state.update { it.copy(keywords = value) }

    fun setSetting(value: String) = _state.update { it.copy(setting = value) }

    fun setProtagonistWish(value: String) = _state.update { it.copy(protagonistWish = value) }

    fun setTone(value: String) = _state.update { it.copy(tone = value) }

    fun setDifficulty(value: String) = _state.update { it.copy(difficulty = value) }

    fun clearError() = _state.update { it.copy(error = null, details = emptyList()) }

    fun cancelGeneration() {
        container.engine.cancel()
    }

    fun generate() {
        if (job?.isActive == true) return
        val current = _state.value
        if (current.mode == CreationMode.GUIDED &&
            current.setting.isBlank() && current.keywords.isBlank()
        ) {
            _state.update {
                it.copy(error = "世界設定かキーワードを入力してください。", details = emptyList())
            }
            return
        }

        _state.update {
            it.copy(
                stage = CreationStage.GENERATING,
                statusText = "世界とシナリオを生成しています…",
                world = null,
                character = null,
                error = null,
                details = emptyList(),
            )
        }
        val input = CreationInput(
            mode = current.mode,
            keywords = current.keywords.trim(),
            setting = current.setting.trim(),
            protagonistWish = current.protagonistWish.trim(),
            tone = current.tone.trim(),
            difficulty = current.difficulty.trim(),
        )
        job = viewModelScope.launch {
            val worldResult = container.adventureCreator.generateWorld(input)
            val world = when (worldResult) {
                is CreationResult.Success -> worldResult.value
                is CreationResult.Cancelled -> {
                    failGeneration("生成をキャンセルしました。", emptyList())
                    return@launch
                }
                is CreationResult.Failed -> {
                    failGeneration(worldResult.message, worldResult.errors)
                    return@launch
                }
            }
            _state.update { it.copy(world = world, statusText = "主人公を生成しています…") }
            when (val characterResult = container.adventureCreator.generateCharacter(world, input)) {
                is CreationResult.Success -> _state.update {
                    it.copy(
                        stage = CreationStage.PREVIEW,
                        character = characterResult.value,
                        statusText = "",
                    )
                }
                is CreationResult.Cancelled -> failGeneration("生成をキャンセルしました。", emptyList())
                is CreationResult.Failed -> failGeneration(characterResult.message, characterResult.errors)
            }
        }
    }

    fun backToForm() {
        _state.update { it.copy(stage = CreationStage.FORM, error = null, details = emptyList()) }
    }

    fun createAdventure() {
        val current = _state.value
        val world = current.world ?: return
        val character = current.character ?: return
        if (current.saving) return
        _state.update { it.copy(saving = true, error = null, details = emptyList()) }
        viewModelScope.launch {
            runCatching {
                container.sessions.create(AdventureAssembler.assemble(world, character))
            }.onSuccess { sessionId ->
                _state.update { it.copy(saving = false, createdSessionId = sessionId) }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        saving = false,
                        error = error.message ?: "冒険の保存に失敗しました。",
                    )
                }
            }
        }
    }

    private fun failGeneration(message: String, details: List<String>) {
        _state.update {
            it.copy(
                stage = CreationStage.FORM,
                statusText = "",
                error = message,
                details = details,
            )
        }
    }
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
