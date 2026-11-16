package io.github.shiokunn.trpg.ai.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaCppNativeTest {

    @Test
    fun parsesKvCacheAccountingFromGenerationResult() {
        val result = LlamaCppNative.parseGeneration(
            """
            {
              "ok": true,
              "text": "{}",
              "promptTokens": 735,
              "generatedTokens": 100,
              "kvCache": {
                "valid": true,
                "hit": true,
                "reusedTokens": 600,
                "computedPromptTokens": 135,
                "cachedTokens": 835,
                "invalidationReason": "prefix_reuse"
              }
            }
            """.trimIndent(),
        )

        assertEquals(735, result.promptTokens)
        assertTrue(result.kvCache.valid)
        assertTrue(result.kvCache.hit)
        assertEquals(600, result.kvCache.reusedTokens)
        assertEquals(135, result.kvCache.computedPromptTokens)
        assertEquals(835, result.kvCache.cachedTokens)
        assertEquals("prefix_reuse", result.kvCache.invalidationReason)
    }

    @Test
    fun missingKvCacheDoesNotFailParsing() {
        val result = LlamaCppNative.parseGeneration(
            """{"ok":true,"text":"hello","promptTokens":12,"generatedTokens":2}""",
        )

        assertFalse(result.kvCache.valid)
        assertFalse(result.kvCache.hit)
        assertEquals(0, result.kvCache.reusedTokens)
    }
}
