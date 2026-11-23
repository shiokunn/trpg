package io.github.shiokunn.trpg.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.shiokunn.trpg.ai.engine.GenerationOutcome
import io.github.shiokunn.trpg.ai.engine.GenerationRequest
import io.github.shiokunn.trpg.ai.engine.InferenceEngine
import io.github.shiokunn.trpg.ai.engine.PromptMessage
import io.github.shiokunn.trpg.ai.engine.SamplingParams
import io.github.shiokunn.trpg.chat.CharacterCreator
import io.github.shiokunn.trpg.core.card.CharacterCard
import io.github.shiokunn.trpg.core.domain.ModelSettings
import io.github.shiokunn.trpg.data.repository.ChatCharacter
import io.github.shiokunn.trpg.data.repository.ChatTurn
import io.github.shiokunn.trpg.data.repository.CharacterChatRepository
import io.github.shiokunn.trpg.data.repository.ModelRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** One-on-one character chat, reusing the on-device inference engine. */
class CharacterChatViewModel(
    private val chats: CharacterChatRepository,
    private val models: ModelRepository,
    private val engine: InferenceEngine,
    private val creator: CharacterCreator,
) : ViewModel() {

    private val _characters = MutableStateFlow<List<ChatCharacter>>(emptyList())
    val characters: StateFlow<List<ChatCharacter>> = _characters

    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId

    private val _turns = MutableStateFlow<List<ChatTurn>>(emptyList())
    val turns: StateFlow<List<ChatTurn>> = _turns

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _creating = MutableStateFlow(false)
    val creating: StateFlow<Boolean> = _creating

    init {
        viewModelScope.launch {
            chats.observeCharacters().collect { _characters.value = it }
        }
        viewModelScope.launch {
            _selectedId.flatMapLatest { id ->
                if (id == null) flowOf(emptyList<ChatTurn>()) else chats.observeTurns(id)
            }.collect { _turns.value = it }
        }
    }

    fun select(id: String?) {
        _selectedId.value = id
        _message.value = null
    }

    fun dismissMessage() {
        _message.value = null
    }

    fun importCard(bytes: ByteArray, displayName: String) {
        viewModelScope.launch {
            val character = chats.importCard(bytes, displayName)
            if (character == null) {
                _message.value = "キャラクターカードを読み込めませんでした（JSON / PNGに対応）。"
            } else {
                _selectedId.value = character.id
                _message.value = "「${character.name}」を追加しました。"
            }
        }
    }

    /** AI creation. A seed card lets hand-written fields be mixed into the result. */
    fun createWithAi(
        keywords: String,
        wish: String,
        seed: CharacterCard.Parsed? = null,
    ) {
        if (_creating.value) return
        _creating.value = true
        viewModelScope.launch {
            creator.create(keywords, wish, seed)
                .onSuccess { card ->
                    val saved = chats.saveCharacter(card)
                    _selectedId.value = saved.id
                    _message.value = "「${saved.name}」を作成しました。"
                }
                .onFailure { error ->
                    _message.value = error.message ?: "キャラクターを生成できませんでした。"
                }
            _creating.value = false
        }
    }

    fun createManual(
        name: String,
        description: String,
        personality: String,
        scenario: String,
        firstMessage: String,
    ) {
        if (name.isBlank()) {
            _message.value = "名前を入力してください。"
            return
        }
        viewModelScope.launch {
            val card = CharacterCard.Parsed(
                name = name.trim(),
                description = description.trim(),
                personality = personality.trim(),
                scenario = scenario.trim(),
                firstMessage = firstMessage.trim(),
            )
            val saved = chats.saveCharacter(card)
            _selectedId.value = saved.id
            _message.value = "「${saved.name}」を作成しました。"
        }
    }

    fun deleteCharacter(character: ChatCharacter) {
        viewModelScope.launch {
            chats.deleteCharacter(character)
            if (_selectedId.value == character.id) _selectedId.value = null
        }
    }

    fun clearChat() {
        val id = _selectedId.value ?: return
        viewModelScope.launch { chats.clearTurns(id) }
    }

    fun send(text: String) {
        val characterId = _selectedId.value ?: return
        val character = _characters.value.firstOrNull { it.id == characterId } ?: return
        val input = text.trim()
        if (input.isBlank() || _busy.value) return

        _busy.value = true
        viewModelScope.launch {
            val history = _turns.value.takeLast(12).map { turn ->
                PromptMessage(
                    role = if (turn.role == "user") "user" else "assistant",
                    content = turn.content,
                )
            }
            chats.addTurn(characterId, "user", input)

            val settings = (models.settings() ?: ModelSettings()).normalized()
            val messages = listOf(PromptMessage("system", character.systemPrompt)) +
                history + PromptMessage("user", input)
            val result = try {
                engine.generate(
                    GenerationRequest(messages = messages, params = SamplingParams.from(settings)),
                    onToken = {},
                    onProgress = {},
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                GenerationOutcome.Failure(error.message ?: "推論中にエラーが発生しました")
            }
            when (result) {
                is GenerationOutcome.Success -> {
                    val reply = result.text.trim()
                    if (reply.isNotBlank()) chats.addTurn(characterId, "assistant", reply)
                }
                is GenerationOutcome.Failure -> _message.value = result.message
                is GenerationOutcome.Cancelled -> Unit
            }
            _busy.value = false
        }
    }
}
