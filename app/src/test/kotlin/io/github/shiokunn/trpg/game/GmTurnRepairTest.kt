package io.github.shiokunn.trpg.game

import io.github.shiokunn.trpg.ai.engine.GenerationFinishReason
import io.github.shiokunn.trpg.ai.engine.GenerationOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GmTurnRepairTest {

    private fun outcome(
        reason: GenerationFinishReason,
        limitReached: Boolean = false,
    ) = GenerationOutcome.Success(
        text = "{\"narrative\":\"途中",
        finishReason = reason,
        limitReached = limitReached,
        generatedTokens = 100,
        budget = 100,
    )

    @Test
    fun lengthFinishIsTruncated() {
        assertTrue(GmTurnRepair.isTruncated(outcome(GenerationFinishReason.LENGTH)))
    }

    @Test
    fun contextFinishIsTruncated() {
        assertTrue(GmTurnRepair.isTruncated(outcome(GenerationFinishReason.CONTEXT)))
    }

    @Test
    fun reachedLimitIsTruncatedEvenWhenReasonIsUnknown() {
        assertTrue(
            GmTurnRepair.isTruncated(
                outcome(GenerationFinishReason.UNKNOWN, limitReached = true),
            ),
        )
    }

    @Test
    fun eosFinishIsNotTruncated() {
        assertFalse(GmTurnRepair.isTruncated(outcome(GenerationFinishReason.EOS)))
    }

    @Test
    fun truncationMessageReportsBudgetAndReason() {
        val message = GmTurnRepair.truncationMessage(outcome(GenerationFinishReason.LENGTH))

        assertTrue(message.contains("予算 100"))
        assertTrue(message.contains("生成 100"))
        assertTrue(message.contains("length"))
    }

    @Test
    fun maxTokensIsClampedToTheLoadedContext() {
        assertEquals(1_000, GmTurnRepair.effectiveMaxTokens(1_000, 8_192))
        assertEquals(300, GmTurnRepair.effectiveMaxTokens(1_000, 812))
        assertEquals(128, GmTurnRepair.effectiveMaxTokens(1_000, 128))
        assertEquals(128, GmTurnRepair.effectiveMaxTokens(0, 0))
    }

    @Test
    fun repairGetsMoreRoomButNeverExceedsTheContext() {
        assertEquals(1_256, GmTurnRepair.repairMaxTokens(1_000, 8_192))
        assertEquals(300, GmTurnRepair.repairMaxTokens(1_000, 812))
    }
}
