package io.github.shiokunn.trpg.debug

import io.github.shiokunn.trpg.core.debug.DebugOutcome
import io.github.shiokunn.trpg.core.debug.DebugSession
import io.github.shiokunn.trpg.core.debug.MemorySample
import io.github.shiokunn.trpg.core.debug.NativeMetrics
import io.github.shiokunn.trpg.core.debug.RuntimeInfo
import io.github.shiokunn.trpg.core.debug.TokenRateSample

/**
 * TEMPORARY DEBUG MODE — immutable snapshot consumed by the debug screen.
 *
 * `phase == null` means no generation has been recorded in this process yet.
 */
data class DebugViewState(
    val phase: DebugSession.Phase? = null,
    val startedAtMs: Long? = null,
    val elapsedMs: Long = 0L,
    val promptTokens: Int? = null,
    val generatedTokens: Int = 0,
    val maxTokens: Int = 0,
    val tokensPerSecond: Double = 0.0,
    val metrics: NativeMetrics? = null,
    val runtime: RuntimeInfo? = null,
    val memory: List<MemorySample> = emptyList(),
    val tokenRates: List<TokenRateSample> = emptyList(),
    val outcome: DebugOutcome? = null,
    val error: String? = null,
    val hasReport: Boolean = false,
) {
    val isActive: Boolean
        get() = phase == DebugSession.Phase.PREPARING || phase == DebugSession.Phase.DECODE
}
