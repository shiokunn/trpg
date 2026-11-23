package io.github.shiokunn.trpg.game

import io.github.shiokunn.trpg.ai.engine.GenerationFinishReason
import io.github.shiokunn.trpg.ai.engine.GenerationOutcome

/**
 * Pure guards around one GM generation. They keep token budgeting and truncated-output
 * detection in one place so a repair retry can never silently accept a half-written
 * JSON reply, and a requested `maxTokens` can never exceed the loaded context.
 */
object GmTurnRepair {

    /** Room kept for the prompt itself plus native headroom / tokenizer estimate error. */
    const val MIN_INPUT_TOKENS = 256
    const val OUTPUT_HEADROOM_TOKENS = 256
    const val MIN_OUTPUT_TOKENS = 128

    /** Extra room for the single repair retry (it rewrites the whole JSON object). */
    const val REPAIR_TOKEN_BONUS = 256

    /** Largest output the context can actually hold. */
    fun maxOutputFor(contextTokens: Int): Int =
        (contextTokens - MIN_INPUT_TOKENS - OUTPUT_HEADROOM_TOKENS)
            .coerceAtLeast(MIN_OUTPUT_TOKENS)

    /**
     * Clamps the configured `maxTokens` to the loaded context. Without this, a prompt
     * that fits `nCtx` but leaves fewer tokens than `maxTokens` makes llama.cpp report
     * `finishReason=context` and hand back a truncated reply.
     */
    fun effectiveMaxTokens(requested: Int, contextTokens: Int): Int {
        if (contextTokens <= 0) return requested.coerceAtLeast(MIN_OUTPUT_TOKENS)
        return requested.coerceAtMost(maxOutputFor(contextTokens)).coerceAtLeast(MIN_OUTPUT_TOKENS)
    }

    /** Repair rewrites the full JSON, so give it a little more room than the first try. */
    fun repairMaxTokens(requested: Int, contextTokens: Int): Int =
        effectiveMaxTokens(requested + REPAIR_TOKEN_BONUS, contextTokens)

    /** LENGTH / CONTEXT (or a reached limit) means the text is incomplete. */
    fun isTruncated(outcome: GenerationOutcome.Success): Boolean =
        outcome.finishReason == GenerationFinishReason.LENGTH ||
            outcome.finishReason == GenerationFinishReason.CONTEXT ||
            outcome.limitReached

    fun truncationMessage(outcome: GenerationOutcome.Success): String {
        val budget = if (outcome.budget > 0) "（予算 ${outcome.budget} トークン）" else ""
        return "応答がトークン上限${budget}で途中に切れました。" +
            "生成 ${outcome.generatedTokens} トークン、終了理由 ${outcome.finishReason.name.lowercase()}。"
    }
}
