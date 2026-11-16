package io.github.shiokunn.trpg.core.domain

import kotlinx.serialization.Serializable

/** Inference parameters and context configuration for the loaded GGUF model. */
@Serializable
data class ModelSettings(
    val modelFileName: String = "",
    val modelDisplayName: String = "",
    val nCtx: Int = DEFAULT_N_CTX,
    val temperature: Float = DEFAULT_TEMPERATURE,
    val topP: Float = DEFAULT_TOP_P,
    val topK: Int = DEFAULT_TOP_K,
    val repeatPenalty: Float = DEFAULT_REPEAT_PENALTY,
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    val gpuLayers: Int = 0,
    val backend: String = "CPU",
    val updatedAt: Long = 0L,
) {
    fun normalized(): ModelSettings = copy(
        nCtx = nCtx.coerceIn(MIN_N_CTX, MAX_N_CTX),
        temperature = temperature.coerceIn(0f, 2f),
        topP = topP.coerceIn(0.05f, 1f),
        topK = topK.coerceIn(1, 200),
        repeatPenalty = repeatPenalty.coerceIn(1f, 2f),
        maxTokens = maxTokens.coerceIn(MIN_MAX_TOKENS, MAX_MAX_TOKENS),
        gpuLayers = gpuLayers.coerceIn(0, 200),
    )

    companion object {
        // A portable default for phones: large-context KV caches are the main
        // source of zram/swap pressure on 8-12GB devices. Users can raise it.
        const val DEFAULT_N_CTX = 8192
        const val MIN_N_CTX = 512
        const val MAX_N_CTX = 32768
        const val DEFAULT_TEMPERATURE = 0.75f
        const val DEFAULT_TOP_P = 0.9f
        const val DEFAULT_TOP_K = 40
        const val DEFAULT_REPEAT_PENALTY = 1.1f
        const val DEFAULT_MAX_TOKENS = 4096
        const val MIN_MAX_TOKENS = 64
        const val MAX_MAX_TOKENS = 16384
    }
}
