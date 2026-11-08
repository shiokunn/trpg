package io.github.shiokunn.trpg.ai.engine

import io.github.shiokunn.trpg.core.domain.ModelSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * llama.cpp backed engine. The native layer is fully synchronous, so every call is
 * moved to [Dispatchers.Default] and serialised with a mutex. Cancellation is handled
 * inside the native decode loop and never throws away a partially committed turn by
 * itself (the caller decides what to do with the outcome).
 */
class LlamaCppEngine(
    private val nativeLibraryDir: String,
) : InferenceEngine {

    private val mutex = Mutex()
    private val _status = MutableStateFlow<EngineStatus>(EngineStatus.Unloaded)

    override val status: StateFlow<EngineStatus> = _status.asStateFlow()

    override suspend fun load(
        modelPath: String,
        modelFileName: String,
        settings: ModelSettings,
    ): Result<ModelInfo> = mutex.withLock {
        _status.value = EngineStatus.Loading(modelFileName)
        withContext(Dispatchers.Default) {
            try {
                LlamaCppNative.ensureInitialized(nativeLibraryDir)
                val raw = LlamaCppNative.nativeLoadModel(
                    path = modelPath,
                    nCtx = settings.normalized().nCtx,
                    nThreads = defaultThreads(),
                    nGpuLayers = settings.normalized().gpuLayers,
                )
                val info = LlamaCppNative.parseModelInfo(raw)
                    ?: throw IllegalStateException(
                        LlamaCppNative.parseError(raw) ?: "モデルの読み込みに失敗しました",
                    )
                _status.value = EngineStatus.Ready(modelFileName, info)
                Result.success(info)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                val message = when {
                    t is OutOfMemoryError -> OOM_LOAD_MESSAGE
                    else -> t.message?.takeIf { it.isNotBlank() }
                        ?: "モデルの読み込みに失敗しました（ネイティブライブラリまたはメモリを確認してください）"
                }
                _status.value = EngineStatus.Failed(message)
                Result.failure(IllegalStateException(message, t))
            }
        }
    }

    override suspend fun unload() {
        mutex.withLock {
            withContext(Dispatchers.Default) {
                runCatching {
                    if (LlamaCppNative.isInitialized()) LlamaCppNative.nativeUnloadModel()
                }
            }
            _status.value = EngineStatus.Unloaded
        }
    }

    override fun formatChat(messages: List<PromptMessage>, addAssistant: Boolean): String? {
        if (messages.isEmpty()) return null
        return runCatching {
            LlamaCppNative.ensureInitialized(nativeLibraryDir)
            LlamaCppNative.nativeFormatChat(
                roles = messages.map { it.role }.toTypedArray(),
                contents = messages.map { it.content }.toTypedArray(),
                addAssistant = addAssistant,
            )?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    override suspend fun countTokens(text: String): Int {
        if (_status.value !is EngineStatus.Ready) return -1
        return withContext(Dispatchers.Default) {
            runCatching { LlamaCppNative.nativeCountTokens(text) }.getOrDefault(-1)
        }
    }

    override suspend fun backendInfo(): BackendInfo = mutex.withLock {
        withContext(Dispatchers.Default) {
            try {
                LlamaCppNative.ensureInitialized(nativeLibraryDir)
                LlamaCppNative.parseBackendInfo(LlamaCppNative.nativeBackendInfo())
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                BackendInfo(
                    system = "ネイティブエンジンを利用できません: ${t.message ?: t.javaClass.simpleName}",
                    available = false,
                )
            }
        }
    }

    override suspend fun generate(
        request: GenerationRequest,
        onToken: suspend (String) -> Unit,
    ): GenerationOutcome = mutex.withLock {
        if (_status.value !is EngineStatus.Ready) {
            return@withLock GenerationOutcome.Failure("モデルが読み込まれていません")
        }
        val prompt = request.prompt?.takeIf { it.isNotBlank() }
            ?: formatChat(request.messages, addAssistant = true)?.takeIf { it.isNotBlank() }
            ?: fallbackChatPrompt(request.messages)
        val params = request.params

        withContext(Dispatchers.Default) {
            coroutineScope {
                val pieces = Channel<String>(Channel.UNLIMITED)
                val consumer = launch {
                    for (piece in pieces) {
                        onToken(piece)
                    }
                }

                var thrown: Throwable? = null
                val raw = try {
                    LlamaCppNative.nativeGenerate(
                        prompt = prompt,
                        maxTokens = params.maxTokens,
                        temperature = params.temperature,
                        topP = params.topP,
                        topK = params.topK,
                        repeatPenalty = params.repeatPenalty,
                        seed = params.seed,
                        callback = TokenCallback { piece -> pieces.trySend(piece) },
                    )
                } catch (t: Throwable) {
                    thrown = t
                    null
                } finally {
                    pieces.close()
                }

                consumer.join()

                val failure = thrown
                if (failure != null) {
                    if (failure is CancellationException) throw failure
                    return@coroutineScope GenerationOutcome.Failure(
                        when {
                            failure is OutOfMemoryError -> OOM_GENERATE_MESSAGE
                            else -> failure.message?.takeIf { it.isNotBlank() }
                                ?: "推論中にエラーが発生しました"
                        },
                    )
                }

                val result = LlamaCppNative.parseGeneration(raw)
                when {
                    result.cancelled -> GenerationOutcome.Cancelled
                    result.error != null -> GenerationOutcome.Failure(result.error)
                    else -> GenerationOutcome.Success(result.text)
                }
            }
        }
    }

    override fun cancel() {
        if (!LlamaCppNative.isInitialized()) return
        runCatching { LlamaCppNative.nativeCancel() }
    }

    override fun release() {
        runCatching {
            if (LlamaCppNative.isInitialized()) LlamaCppNative.nativeUnloadModel()
        }
        _status.value = EngineStatus.Unloaded
    }

    private fun defaultThreads(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(2, 8)

    private companion object {
        const val OOM_LOAD_MESSAGE =
            "メモリが不足してモデルを読み込めませんでした。コンテキストサイズを下げるか、より小さいGGUFモデルを使用してください。"
        const val OOM_GENERATE_MESSAGE =
            "メモリが不足して生成を継続できませんでした。コンテキストサイズを下げるか、より小さいモデルを使用してください。"
    }

    private fun fallbackChatPrompt(messages: List<PromptMessage>): String = buildString {
        for (message in messages) {
            append("<|im_start|>").append(message.role).append('\n')
            append(message.content.trimEnd()).append("<|im_end|>\n")
        }
        append("<|im_start|>assistant\n")
    }
}
