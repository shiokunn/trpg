package io.github.shiokunn.trpg.core.inference

import io.github.shiokunn.trpg.core.domain.ModelSettings

/**
 * Heuristic context-size guidance for a phone-sized device.
 *
 * The exact KV size depends on the model architecture (layers, KV heads, head
 * dimension, KV type), which is not available before the model is loaded. This
 * advisor therefore uses model file size and total device RAM as conservative
 * proxies. It never silently changes a saved setting; the UI shows the advice and
 * offers a one-tap override.
 */
data class ContextAdvice(
    val recommendedNCtx: Int,
    val requestedNCtx: Int,
    val modelSizeBytes: Long,
    val totalRamBytes: Long,
) {
    val warning: String?
        get() = if (requestedNCtx > recommendedNCtx) {
            "このモデルと端末RAMでは ${requestedNCtx} トークンはメモリ不足の可能性があります。" +
                "推奨は ${recommendedNCtx} トークンです。"
        } else {
            null
        }
}

object MemoryAdvisor {

    private const val GIB = 1024L * 1024L * 1024L

    fun advise(
        modelSizeBytes: Long,
        totalRamBytes: Long,
        requestedNCtx: Int,
        nCtxTrain: Int = ModelSettings.MAX_N_CTX,
    ): ContextAdvice {
        val modelTier = when {
            modelSizeBytes <= 1_500_000_000L -> 16_384
            modelSizeBytes <= 2_500_000_000L -> 12_288
            modelSizeBytes <= 3_500_000_000L -> 8_192
            else -> 4_096
        }
        val ramTier = when {
            totalRamBytes < 6L * GIB -> 4_096
            totalRamBytes < 8L * GIB -> 8_192
            totalRamBytes < 12L * GIB -> 12_288
            else -> 16_384
        }
        val trainCap = if (nCtxTrain > 0) nCtxTrain else ModelSettings.MAX_N_CTX
        val recommended = minOf(minOf(modelTier, ramTier), minOf(trainCap, ModelSettings.MAX_N_CTX))
            .coerceAtLeast(ModelSettings.MIN_N_CTX)
        return ContextAdvice(
            recommendedNCtx = recommended,
            requestedNCtx = requestedNCtx,
            modelSizeBytes = modelSizeBytes,
            totalRamBytes = totalRamBytes,
        )
    }
}
