package io.github.shiokunn.trpg.ai.engine

import io.github.shiokunn.trpg.BuildConfig
import io.github.shiokunn.trpg.core.domain.ModelSettings
import android.util.Log
// DEBUG MODE (temporary)
import io.github.shiokunn.trpg.debug.DebugRecorder
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
    private val nextGenerationId = AtomicLong(1)
    private val activeGenerationId = AtomicLong(0)
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

    override suspend fun formatChat(messages: List<PromptMessage>, addAssistant: Boolean): String? {
        if (messages.isEmpty()) return null
        return withContext(Dispatchers.Default) {
            runCatching {
                LlamaCppNative.ensureInitialized(nativeLibraryDir)
                LlamaCppNative.nativeFormatChat(
                    roles = messages.map { it.role }.toTypedArray(),
                    contents = messages.map { it.content }.toTypedArray(),
                    addAssistant = addAssistant,
                )?.takeIf { it.isNotBlank() }
            }.getOrNull()
        }
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
        onProgress: (GenerationProgress) -> Unit,
    ): GenerationOutcome {
        // Allocate the generation id before waiting on the mutex so a cancel request
        // issued while another generation still owns the engine is not lost.
        val id = nextGenerationId.getAndIncrement()
        activeGenerationId.set(id)
        // DEBUG MODE (temporary) — stays null unless the debug toggle is ON.
        var recording: DebugRecorder.Recording? = null
        var reporter: ProgressReporter? = null
        try {
            return mutex.withLock {
                if (_status.value !is EngineStatus.Ready) {
                    return@withLock GenerationOutcome.Failure("モデルが読み込まれていません")
                }
                val params = request.params
                val progress = ProgressReporter(id, params.maxTokens, onProgress)
                reporter = progress
                progress.start()
                recording = DebugRecorder.begin(
                    modelFileName = (_status.value as? EngineStatus.Ready)?.modelFileName ?: "",
                    params = params,
                    messageCount = request.messages.size,
                )
                val roles = request.messages.map { it.role }.toTypedArray()
                val contents = request.messages.map { it.content }.toTypedArray()

                withContext(Dispatchers.Default) {
                    coroutineScope {
                        val pieces = Channel<GeneratedPiece>(Channel.UNLIMITED)
                        val consumer = launch {
                            for (piece in pieces) {
                                progress.onToken(piece.generatedTokens)
                                if (piece.text.isNotEmpty()) onToken(piece.text)
                            }
                        }

                        var thrown: Throwable? = null
                        val raw = try {
                            if (request.prompt.isNullOrBlank()) {
                                // Fail closed on an unsupported template: never silently
                                // fall back to ChatML when a template exists but cannot
                                // be applied. A model with no template at all is allowed
                                // to use the explicit ChatML fallback (status "none").
                                val formatResult = runCatching {
                                    LlamaCppNative.parseFormatResult(
                                        LlamaCppNative.nativeFormatChatEx(
                                            roles = roles,
                                            contents = contents,
                                            addAssistant = true,
                                        ),
                                    )
                                }.getOrNull()
                                val formatFailure = when {
                                    formatResult == null -> "チャットテンプレートを適用できません"
                                    !formatResult.ok -> formatResult.error
                                        ?: "チャットテンプレートを適用できません"
                                    formatResult.prompt.isBlank() ->
                                        "チャットテンプレートによるプロンプト生成が空です"
                                    else -> null
                                }
                                if (formatFailure != null) {
                                    pieces.close()
                                    consumer.join()
                                    progress.finish(
                                        generatedTokens = 0,
                                        finishReason = GenerationFinishReason.ERROR,
                                        limitReached = false,
                                    )
                                    // DEBUG MODE (temporary)
                                    recording?.finish(GenerationOutcome.Failure(formatFailure))
                                    return@coroutineScope GenerationOutcome.Failure(formatFailure)
                                }
                                LlamaCppNative.nativeGenerateChatEx(
                                    roles = roles,
                                    contents = contents,
                                    addAssistant = true,
                                    maxTokens = params.maxTokens,
                                    temperature = params.temperature,
                                    topP = params.topP,
                                    topK = params.topK,
                                    repeatPenalty = params.repeatPenalty,
                                    seed = params.seed,
                                    generationId = id,
                                    callback = TokenCallback { piece, generated ->
                                        pieces.trySend(GeneratedPiece(piece, generated))
                                        recording?.onToken() // DEBUG MODE (temporary)
                                    },
                                )
                            } else {
                                LlamaCppNative.nativeGenerateEx(
                                    prompt = request.prompt,
                                    maxTokens = params.maxTokens,
                                    temperature = params.temperature,
                                    topP = params.topP,
                                    topK = params.topK,
                                    repeatPenalty = params.repeatPenalty,
                                    seed = params.seed,
                                    generationId = id,
                                    callback = TokenCallback { piece, generated ->
                                        pieces.trySend(GeneratedPiece(piece, generated))
                                        recording?.onToken() // DEBUG MODE (temporary)
                                    },
                                    stops = emptyArray(),
                                    addSpecial = true,
                                )
                            }
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
                            val outcome = GenerationOutcome.Failure(
                                when {
                                    failure is OutOfMemoryError -> OOM_GENERATE_MESSAGE
                                    else -> failure.message?.takeIf { it.isNotBlank() }
                                        ?: "推論中にエラーが発生しました"
                                },
                            )
                            progress.finish(
                                finishReason = GenerationFinishReason.ERROR,
                                limitReached = false,
                            )
                            recording?.finish(outcome) // DEBUG MODE (temporary)
                            return@coroutineScope outcome
                        }

                        // DEBUG MODE (temporary) — parses the metrics object only.
                        recording?.attachResult(raw)
                        val result = LlamaCppNative.parseGeneration(raw)
                        progress.finish(
                            generatedTokens = result.generatedTokens.takeIf { it >= 0 }
                                ?: progress.lastGenerated,
                            promptTokens = result.promptTokens.takeIf { it >= 0 },
                            budget = result.budget.takeIf { it > 0 },
                            finishReason = when {
                                result.error != null -> GenerationFinishReason.ERROR
                                result.cancelled -> GenerationFinishReason.CANCELLED
                                else -> GenerationFinishReason.fromWire(result.finishReason)
                            },
                            limitReached = if (result.error != null || result.cancelled) {
                                false
                            } else {
                                result.limitReached
                            },
                        )
                        val outcome = when {
                            result.cancelled -> GenerationOutcome.Cancelled
                            result.error != null -> GenerationOutcome.Failure(result.error)
                            else -> GenerationOutcome.Success(result.text)
                        }
                        recording?.finish(outcome)
                        outcome
                    }
                }
            }
        } catch (t: Throwable) {
            // DEBUG MODE (temporary) — close the session on cancelled/failed runs.
            if (t is CancellationException) {
                reporter?.finish(
                    finishReason = GenerationFinishReason.CANCELLED,
                    limitReached = false,
                )
                recording?.finish(GenerationOutcome.Cancelled)
            } else {
                reporter?.finish(
                    finishReason = GenerationFinishReason.ERROR,
                    limitReached = false,
                )
                recording?.finish(
                    GenerationOutcome.Failure(
                        t.message?.takeIf { it.isNotBlank() } ?: "推論中にエラーが発生しました",
                    ),
                )
            }
            throw t
        } finally {
            if (activeGenerationId.get() == id) {
                activeGenerationId.set(0)
            }
        }
    }

    override fun cancel() {
        if (!LlamaCppNative.isInitialized()) return
        val id = activeGenerationId.get()
        runCatching {
            if (id != 0L) {
                LlamaCppNative.nativeCancelGeneration(id)
            } else {
                LlamaCppNative.nativeCancel()
            }
        }
    }

    override fun release() {
        runCatching {
            if (LlamaCppNative.isInitialized()) {
                LlamaCppNative.nativeCancelGeneration(0)
                LlamaCppNative.nativeUnloadModel()
            }
        }
        _status.value = EngineStatus.Unloaded
        activeGenerationId.set(0)
    }

    /**
     * Use the build-time thread profile: profile A uses 4 threads, B/C use 6.
     * The native side applies CPU affinity for the selected profile.
     */
    private fun defaultThreads(): Int {
        val profileThreads = when (BuildConfig.THREAD_PROFILE) {
            "B", "C" -> 6
            else -> 4
        }
        return profileThreads.coerceAtLeast(2)
    }

    private companion object {
        const val OOM_LOAD_MESSAGE =
            "メモリが不足してモデルを読み込めませんでした。コンテキストサイズを下げるか、より小さいGGUFモデルを使用してください。"
        const val OOM_GENERATE_MESSAGE =
            "メモリが不足して生成を継続できませんでした。コンテキストサイズを下げるか、より小さいモデルを使用してください。"
    }
}

/** One generated piece plus the exact llama.cpp token count after that piece. */
private data class GeneratedPiece(val text: String, val generatedTokens: Int)

/**
 * Bridges native token callbacks to [onProgress]. UI updates and logs are throttled
 * independently, and the final event is emitted exactly once so a cancellation race
 * cannot overwrite the real finish reason.
 */
private class ProgressReporter(
    private val id: Long,
    private val maxTokens: Int,
    private val onProgress: (GenerationProgress) -> Unit,
) {
    private val generatedCount = AtomicInteger(0)
    private val finished = AtomicBoolean(false)
    private var lastEmitAt = 0L
    private var lastLogAt = 0L

    /** Last count delivered by the native callback (used when the result JSON is lost). */
    val lastGenerated: Int get() = generatedCount.get()

    fun start() {
        lastEmitAt = System.nanoTime()
        emit(GenerationProgress(generationId = id, generatedTokens = 0, maxTokens = maxTokens))
    }

    fun onToken(generatedTokens: Int) {
        generatedCount.set(generatedTokens)
        val now = System.nanoTime()
        if (now - lastEmitAt >= PROGRESS_UPDATE_INTERVAL_NANOS) {
            lastEmitAt = now
            emit(
                GenerationProgress(
                    generationId = id,
                    generatedTokens = generatedTokens,
                    maxTokens = maxTokens,
                ),
            )
        }
        if (now - lastLogAt >= PROGRESS_LOG_INTERVAL_NANOS) {
            lastLogAt = now
            Log.i(
                TAG,
                "[TokenCounter] progress generation=$id generated=$generatedTokens maxTokens=$maxTokens",
            )
        }
    }

    fun finish(
        generatedTokens: Int = lastGenerated,
        promptTokens: Int? = null,
        budget: Int? = null,
        finishReason: GenerationFinishReason,
        limitReached: Boolean?,
    ) {
        if (!finished.compareAndSet(false, true)) return
        emit(
            GenerationProgress(
                generationId = id,
                promptTokens = promptTokens,
                generatedTokens = generatedTokens,
                maxTokens = maxTokens,
                budget = budget,
                finishReason = finishReason,
                limitReached = limitReached,
            ),
        )
    }

    private fun emit(progress: GenerationProgress) {
        if (finished.get() && progress.finishReason == null) return
        runCatching { onProgress(progress) }
    }
}

private const val TAG = "TrpgLlama"
private const val PROGRESS_UPDATE_INTERVAL_NANOS = 150_000_000L
private const val PROGRESS_LOG_INTERVAL_NANOS = 1_000_000_000L
