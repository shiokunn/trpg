package io.github.shiokunn.trpg.ai.engine

import io.github.shiokunn.trpg.core.domain.ModelSettings
import kotlinx.coroutines.flow.StateFlow

/** One chat turn handed to the local model. */
data class PromptMessage(
    val role: String,
    val content: String,
)

/** Sampling parameters for a single generation. */
data class SamplingParams(
    val maxTokens: Int = ModelSettings.DEFAULT_MAX_TOKENS,
    val temperature: Float = ModelSettings.DEFAULT_TEMPERATURE,
    val topP: Float = ModelSettings.DEFAULT_TOP_P,
    val topK: Int = ModelSettings.DEFAULT_TOP_K,
    val repeatPenalty: Float = ModelSettings.DEFAULT_REPEAT_PENALTY,
    val seed: Int = -1,
) {
    companion object {
        fun from(settings: ModelSettings): SamplingParams = SamplingParams(
            maxTokens = settings.maxTokens,
            temperature = settings.temperature,
            topP = settings.topP,
            topK = settings.topK,
            repeatPenalty = settings.repeatPenalty,
        )
    }
}

/**
 * A generation request. When [prompt] is null the engine formats [messages] with the
 * model's own chat template (falling back to a plain ChatML-style layout).
 *
 * [grammar] is an optional GBNF grammar (llama.cpp syntax) applied while the tokens
 * are sampled. It constrains the *syntax* of the output, not its meaning: the
 * contract checks stay in the parser. When the grammar cannot be applied the
 * generation fails instead of silently producing an unconstrained answer.
 */
data class GenerationRequest(
    val messages: List<PromptMessage>,
    val prompt: String? = null,
    val params: SamplingParams = SamplingParams(),
    val grammar: String? = null,
)

data class BackendInfo(
    val system: String = "",
    val backends: List<String> = emptyList(),
    val gpuOffload: Boolean = false,
    val available: Boolean = true,
)

data class ModelInfo(
    val description: String,
    val nParams: Long,
    val sizeBytes: Long,
    val nCtxTrain: Int,
    val nCtx: Int,
    val hasChatTemplate: Boolean,
    val chatTemplateStatus: String = "unknown",
    val chatTemplateError: String = "",
    val chatTemplateSupportsThinking: Boolean = false,
    val chatTemplateAddBos: Boolean = false,
)

sealed interface EngineStatus {
    data object Unloaded : EngineStatus
    data class Loading(val modelFileName: String) : EngineStatus
    data class Ready(val modelFileName: String, val info: ModelInfo) : EngineStatus
    data class Failed(val message: String) : EngineStatus
}

/** Native prefix-cache accounting for one generation. */
data class KvCacheStats(
    val valid: Boolean = false,
    val hit: Boolean = false,
    val reusedTokens: Int = 0,
    val computedPromptTokens: Int = 0,
    val cachedTokens: Int = 0,
    val invalidationReason: String = "",
)

sealed interface GenerationOutcome {
    /**
     * The generation produced text.
     *
     * [finishReason] tells the caller *why* the generation stopped. A
     * [GenerationFinishReason.LENGTH] or [GenerationFinishReason.CONTEXT] result is
     * truncated output: the text may be incomplete and must not be treated as a
     * finished answer.
     */
    data class Success(
        val text: String,
        val finishReason: GenerationFinishReason = GenerationFinishReason.UNKNOWN,
        val limitReached: Boolean = false,
        val generatedTokens: Int = 0,
        val promptTokens: Int = 0,
        val budget: Int = 0,
        val kvCache: KvCacheStats = KvCacheStats(),
    ) : GenerationOutcome

    data object Cancelled : GenerationOutcome

    /** The engine itself failed; this is not a contract violation of the reply. */
    data class Failure(
        val message: String,
        val kind: GenerationFailureKind = GenerationFailureKind.INFERENCE,
    ) : GenerationOutcome
}

/**
 * Why the engine failed. Kept separate from a contract violation so the UI can
 * tell "the model could not run" from "the model answered with the wrong shape".
 */
enum class GenerationFailureKind {
    /** The engine reported an error (memory, sampler, template, ...). */
    INFERENCE,

    /** A JSON grammar was requested but could not be applied. */
    GRAMMAR,
}

/** Why a generation stopped. Mirrors the finish reason reported by llama.cpp. */
enum class GenerationFinishReason(val wire: String) {
    LENGTH("length"),
    EOS("eos"),
    STOP("stop"),
    CANCELLED("cancelled"),
    ERROR("error"),
    CONTEXT("context"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromWire(value: String?): GenerationFinishReason =
            entries.firstOrNull { it.wire == value } ?: UNKNOWN
    }
}

/**
 * Token accounting for one generation. [finishReason] is null while the generation is
 * still running. Counts come from llama.cpp itself, never from the text length.
 */
data class GenerationProgress(
    val generationId: Long,
    val promptTokens: Int? = null,
    val generatedTokens: Int = 0,
    val maxTokens: Int,
    val budget: Int? = null,
    val finishReason: GenerationFinishReason? = null,
    val limitReached: Boolean? = null,
)

/**
 * Abstraction over the on-device inference backend.
 *
 * Implementations must never touch game state: they only turn a prompt into text.
 * Cancellation and failures are reported to the caller so an interrupted turn can be
 * discarded without writing anything to the database.
 */
interface InferenceEngine {
    val status: StateFlow<EngineStatus>

    suspend fun load(modelPath: String, modelFileName: String, settings: ModelSettings): Result<ModelInfo>

    suspend fun unload()

    /** Applies the model's chat template. Returns null when the model has no template. */
    suspend fun formatChat(messages: List<PromptMessage>, addAssistant: Boolean = true): String?

    suspend fun countTokens(text: String): Int

    suspend fun backendInfo(): BackendInfo

    /**
     * Streams generated pieces through [onToken] and returns the final outcome.
     * After [cancel] the engine stops within roughly one token.
     *
     * [onProgress] receives live token counts and a final event carrying the finish
     * reason. It is throttled and invoked off the main thread; callers must not block.
     */
    suspend fun generate(
        request: GenerationRequest,
        onToken: suspend (String) -> Unit,
        onProgress: (GenerationProgress) -> Unit = {},
    ): GenerationOutcome

    /** Requests that an in-flight generation stop. Safe to call from any thread. */
    fun cancel()

    /** Releases native resources. The engine can be re-initialised afterwards. */
    fun release()
}
