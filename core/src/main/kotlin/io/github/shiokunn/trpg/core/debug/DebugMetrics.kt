package io.github.shiokunn.trpg.core.debug

import kotlinx.serialization.Serializable

/**
 * TEMPORARY DEBUG MODE — measurement data models.
 *
 * All "unknown" values are `null` so the UI can render 取得不可 instead of inventing a
 * number. This keeps the report honest when a stage did not run (e.g. the chat template
 * failed before prefill).
 */
@Serializable
data class NativeMetrics(
    val applyMs: Long? = null,
    val tokenizeMs: Long? = null,
    val clearMs: Long? = null,
    val promptMs: Long? = null,
    val genMs: Long? = null,
    val ttftMs: Long? = null,
    val promptTokens: Int? = null,
    val generatedTokens: Int? = null,
    val budget: Int? = null,
    val threads: Int? = null,
    val nBatch: Int? = null,
    val kvValid: Boolean? = null,
    val kvHit: Boolean? = null,
    val kvReusedTokens: Int? = null,
    val kvComputedPromptTokens: Int? = null,
    val kvCachedTokens: Int? = null,
    val kvInvalidationReason: String? = null,
)

/** Values reported by the native layer. Requested and actual values are kept apart. */
@Serializable
data class RuntimeInfo(
    val appVersion: String = "取得不可",
    val llamaVersion: String? = null,
    val llamaCommit: String? = null,
    val modelFileName: String? = null,
    val modelLoaded: Boolean? = null,
    val nCtx: Int? = null,
    val nCtxRequested: Int? = null,
    val nCtxTrain: Int? = null,
    val nBatch: Int? = null,
    val nBatchRequested: Int? = null,
    val nUbatch: Int? = null,
    val nUbatchRequested: Int? = null,
    val nSeqMax: Int? = null,
    val nThreads: Int? = null,
    val nThreadsRequested: Int? = null,
    val nThreadsBatch: Int? = null,
    val threadpool: String? = null,
    val allowedCpus: String? = null,
    val flashAttn: String? = null,
    val kvTypeK: String? = null,
    val kvTypeV: String? = null,
    val gpuLayers: Int? = null,
    val backends: List<String> = emptyList(),
    val gpuOffload: Boolean? = null,
)

/** The sampling settings that were actually used for this generation. */
@Serializable
data class DebugSettingsSnapshot(
    val maxTokens: Int = 0,
    val temperature: Float = 0f,
    val topP: Float = 0f,
    val topK: Int = 0,
    val repeatPenalty: Float = 0f,
    val seed: Int = 0,
    val messageCount: Int = 0,
)

@Serializable
data class MemorySample(
    val atMs: Long,
    val vmRssKb: Long? = null,
    val vmSwapKb: Long? = null,
    val memAvailableKb: Long? = null,
    val swapFreeKb: Long? = null,
)

@Serializable
enum class DebugLogLevel {
    INFO,
    WARN,
    ERROR,
}

@Serializable
data class DebugLogEntry(
    val atMs: Long,
    val level: DebugLogLevel,
    val message: String,
)

/** Measured speed over a fixed window (every 16 generated pieces by default). */
@Serializable
data class TokenRateSample(
    val atToken: Int,
    val windowMs: Long,
    val tokensPerSecond: Double,
)

@Serializable
enum class DebugOutcome {
    COMPLETED,
    CANCELLED,
    ERROR,
}

/**
 * One exported analysis unit. Intentionally contains no prompt text, no generated text
 * and no message contents: only counts, timings and device state.
 */
@Serializable
data class DebugReport(
    val exportedAtMs: Long,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val outcome: DebugOutcome,
    val error: String? = null,
    val runtime: RuntimeInfo,
    val settings: DebugSettingsSnapshot,
    val metrics: NativeMetrics? = null,
    val totalMs: Long = 0,
    val generatedTokens: Int = 0,
    val tokensPerSecond: Double = 0.0,
    val tokenRates: List<TokenRateSample> = emptyList(),
    val memory: List<MemorySample> = emptyList(),
    val logs: List<DebugLogEntry> = emptyList(),
)
