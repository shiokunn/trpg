package io.github.shiokunn.trpg.core.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugJsonTest {

    private fun sampleReport(): DebugReport = DebugReport(
        exportedAtMs = 1_700_000_000_000L,
        startedAtMs = 1_699_999_999_000L,
        endedAtMs = 1_700_000_000_000L,
        outcome = DebugOutcome.COMPLETED,
        error = null,
        runtime = RuntimeInfo(
            appVersion = "0.1.0",
            llamaVersion = "0.9.0",
            llamaCommit = "cd959ba",
            modelFileName = "qwen3-4b.Q4_K_M.gguf",
            modelLoaded = true,
            nCtx = 8192,
            nCtxRequested = 8192,
            nBatch = 1024,
            nUbatch = 512,
            nThreads = 4,
            threadpool = "profile=A threads=4 strict_cpu=true cpus=7,4,5,6",
            allowedCpus = "0-6",
            flashAttn = "AUTO",
            kvTypeK = "f16",
            kvTypeV = "f16",
            gpuLayers = 0,
            backends = listOf("CPU"),
            gpuOffload = false,
        ),
        settings = DebugSettingsSnapshot(
            maxTokens = 1000,
            temperature = 0.85f,
            topP = 0.9f,
            topK = 40,
            repeatPenalty = 1.1f,
            seed = -1,
            messageCount = 7,
        ),
        metrics = NativeMetrics(
            applyMs = 13,
            tokenizeMs = 48,
            clearMs = 1,
            promptMs = 355_704,
            genMs = 2_000,
            ttftMs = 355_750,
            promptTokens = 735,
            generatedTokens = 100,
            budget = 100,
            threads = 4,
            nBatch = 1024,
        ),
        totalMs = 1_000,
        generatedTokens = 100,
        tokensPerSecond = 50.0,
        tokenRates = listOf(TokenRateSample(atToken = 16, windowMs = 400, tokensPerSecond = 40.0)),
        memory = listOf(MemorySample(atMs = 1, vmRssKb = 477_000, vmSwapKb = 3_957_008)),
        logs = listOf(
            DebugLogEntry(atMs = 1, level = DebugLogLevel.INFO, message = "推論開始"),
            DebugLogEntry(atMs = 2, level = DebugLogLevel.WARN, message = "ネイティブ metrics を取得できませんでした"),
        ),
    )

    @Test
    fun encodeContainsRequiredSections() {
        val text = DebugJson.encode(sampleReport())
        listOf(
            "\"appVersion\"",
            "\"llamaVersion\"",
            "\"llamaCommit\"",
            "\"metrics\"",
            "\"promptMs\"",
            "\"genMs\"",
            "\"promptTokens\"",
            "\"generatedTokens\"",
            "\"tokensPerSecond\"",
            "\"memory\"",
            "\"vmRssKb\"",
            "\"threadpool\"",
            "\"allowedCpus\"",
            "\"logs\"",
            "\"outcome\"",
        ).forEach { key ->
            assertTrue("missing key $key", text.contains(key))
        }
    }

    @Test
    fun encodeDoesNotContainConversationText() {
        val text = DebugJson.encode(sampleReport())
        // The report model has no field for prompt/generated text; guard against regressions.
        assertFalse(text.contains("\"prompt\""))
        assertFalse(text.contains("\"text\""))
        assertFalse(text.contains("\"content\""))
        assertFalse(text.contains("\"messages\""))
    }

    @Test
    fun roundTripPreservesValues() {
        val original = sampleReport()
        val restored = DebugJson.decode(DebugJson.encode(original))
        assertEquals(original, restored)
    }

    @Test
    fun missingMetricsEncodeAsNull() {
        val report = sampleReport().copy(metrics = null, error = null)
        val restored = DebugJson.decode(DebugJson.encode(report))
        assertEquals(null, restored.metrics)
        assertEquals(null, restored.error)
    }

    @Test
    fun unknownFieldsAreIgnoredOnDecode() {
        val text = DebugJson.encode(sampleReport()).replaceFirst("{", "{\"futureField\":123,")
        val restored = DebugJson.decode(text)
        assertEquals(DebugOutcome.COMPLETED, restored.outcome)
    }
}
