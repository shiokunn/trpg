package io.github.shiokunn.trpg.core.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeClock(var now: Long = 1_000L) {
    fun advance(ms: Long) {
        now += ms
    }
}

class DebugSessionTest {

    private fun session(clock: FakeClock): DebugSession =
        DebugSession(startedAtMs = clock.now, clock = { clock.now })

    @Test
    fun startsInPreparingPhase() {
        val clock = FakeClock()
        val session = session(clock)
        assertEquals(DebugSession.Phase.PREPARING, session.phase)
        assertNull(session.outcome)
        assertNull(session.firstTokenAtMs)
        assertEquals(0, session.generatedPieces)
    }

    @Test
    fun firstTokenSwitchesToDecode() {
        val clock = FakeClock()
        val session = session(clock)
        clock.advance(500)
        session.onToken()

        assertEquals(DebugSession.Phase.DECODE, session.phase)
        assertEquals(1, session.generatedPieces)
        assertEquals(1_500L, session.firstTokenAtMs)
    }

    @Test
    fun recordsRateEverySixteenTokens() {
        val clock = FakeClock()
        val session = session(clock)
        clock.advance(100)
        session.onToken() // first token at 1100

        repeat(15) {
            clock.advance(100)
            session.onToken()
        }

        val snapshot = session.snapshot(clock.now)
        // 16 tokens over 1500 ms -> one rate window
        val report = session.toReport(RuntimeInfo(), DebugSettingsSnapshot(), clock.now)
        assertEquals(16, snapshot.generatedTokens)
        assertEquals(1, report.tokenRates.size)
        assertEquals(16, report.tokenRates.first().atToken)
        val rate = snapshot.tokensPerSecond
        assertTrue("expected a positive rate, was $rate", rate > 0.0)
    }

    @Test
    fun tokensPerSecondUsesElapsedSinceFirstToken() {
        val clock = FakeClock()
        val session = session(clock)
        clock.advance(1_000)
        session.onToken()
        repeat(15) {
            clock.advance(100)
            session.onToken()
        }
        // 16 tokens, 1500 ms after the first token
        val snapshot = session.snapshot(clock.now)
        assertEquals(16, snapshot.generatedTokens)
        assertEquals(16 * 1000.0 / 1_500.0, snapshot.tokensPerSecond, 0.0001)
    }

    @Test
    fun zeroSpeedBeforeFirstToken() {
        val clock = FakeClock()
        val session = session(clock)
        clock.advance(5_000)
        assertEquals(0.0, session.snapshot(clock.now).tokensPerSecond, 0.0)
    }

    @Test
    fun finishCompletedSetsPhaseAndTimestamp() {
        val clock = FakeClock()
        val session = session(clock)
        clock.advance(2_000)
        session.finish(DebugOutcome.COMPLETED)

        assertEquals(DebugSession.Phase.COMPLETED, session.phase)
        assertEquals(DebugOutcome.COMPLETED, session.outcome)
        assertEquals(3_000L, session.endedAtMs)
        assertTrue(session.logsSnapshot().any { it.message.contains("推論完了") })
    }

    @Test
    fun finishCancelledAndError() {
        val cancelled = session(FakeClock())
        cancelled.finish(DebugOutcome.CANCELLED)
        assertEquals(DebugOutcome.CANCELLED, cancelled.outcome)
        assertEquals(DebugSession.Phase.CANCELLED, cancelled.phase)

        val failed = session(FakeClock())
        failed.finish(DebugOutcome.ERROR, "メモリ不足")
        assertEquals(DebugOutcome.ERROR, failed.outcome)
        assertEquals(DebugSession.Phase.ERROR, failed.phase)
        assertEquals("メモリ不足", failed.error)
        assertTrue(
            failed.logsSnapshot().any {
                it.level == DebugLogLevel.ERROR && it.message.contains("メモリ不足")
            },
        )
    }

    @Test
    fun finishIsIdempotent() {
        val clock = FakeClock()
        val session = session(clock)
        clock.advance(100)
        session.finish(DebugOutcome.COMPLETED)
        val ended = session.endedAtMs
        clock.advance(5_000)
        session.finish(DebugOutcome.ERROR, "late")
        assertEquals(ended, session.endedAtMs)
        assertEquals(DebugOutcome.COMPLETED, session.outcome)
    }

    @Test
    fun tokensAreIgnoredAfterFinish() {
        val clock = FakeClock()
        val session = session(clock)
        session.onToken()
        session.finish(DebugOutcome.COMPLETED)
        clock.advance(500)
        session.onToken()
        assertEquals(1, session.generatedPieces)
    }

    @Test
    fun logKeepsNewestEntriesWithinLimit() {
        val clock = FakeClock()
        val session = DebugSession(startedAtMs = clock.now, clock = { clock.now }, maxLogEntries = 3)
        session.log(DebugLogLevel.INFO, "one")
        session.log(DebugLogLevel.INFO, "two")
        session.log(DebugLogLevel.INFO, "three")
        session.log(DebugLogLevel.WARN, "four")

        val logs = session.logsSnapshot()
        assertEquals(3, logs.size)
        assertEquals(listOf("two", "three", "four"), logs.map { it.message })
    }

    @Test
    fun attachNativeMetricsParsesPayload() {
        val session = session(FakeClock())
        session.attachNativeMetrics(
            """{"ok":true,"text":"hidden","metrics":{"applyMs":13,"tokenizeMs":48,
               "clearMs":1,"promptMs":1000,"genMs":2000,"ttftMs":1010,
               "promptTokens":735,"generatedTokens":100,"budget":100,"threads":4,"nBatch":1024,
               "kvValid":true,"kvHit":true,"kvReusedTokens":600,
               "kvComputedPromptTokens":135,"kvCachedTokens":835,
               "kvInvalidationReason":"prefix_reuse"}}""",
        )
        val metrics = session.nativeMetrics
        assertNotNull(metrics)
        assertEquals(13L, metrics?.applyMs)
        assertEquals(48L, metrics?.tokenizeMs)
        assertEquals(735, metrics?.promptTokens)
        assertEquals(4, metrics?.threads)
        assertEquals(true, metrics?.kvValid)
        assertEquals(true, metrics?.kvHit)
        assertEquals(600, metrics?.kvReusedTokens)
        assertEquals(135, metrics?.kvComputedPromptTokens)
        assertEquals(835, metrics?.kvCachedTokens)
        assertEquals("prefix_reuse", metrics?.kvInvalidationReason)
    }

    @Test
    fun attachNativeMetricsHandlesMissingMetrics() {
        val session = session(FakeClock())
        session.attachNativeMetrics("""{"ok":false,"error":"テンプレート失敗"}""")
        assertNull(session.nativeMetrics)
        assertTrue(session.logsSnapshot().any { it.level == DebugLogLevel.WARN })
    }

    @Test
    fun attachNativeMetricsHandlesBrokenJson() {
        val session = session(FakeClock())
        session.attachNativeMetrics("not json at all")
        assertNull(session.nativeMetrics)
    }

    @Test
    fun memorySamplesAreCollected() {
        val session = session(FakeClock())
        session.addMemorySample(MemorySample(atMs = 1, vmRssKb = 100, vmSwapKb = 5))
        session.addMemorySample(MemorySample(atMs = 2, vmRssKb = 120, vmSwapKb = 6))
        assertEquals(2, session.snapshot().memory.size)
    }

    @Test
    fun reportPrefersNativeTokenCountAndRate() {
        val clock = FakeClock()
        val session = session(clock)
        session.onToken()
        clock.advance(10_000)
        session.attachNativeMetrics(
            """{"metrics":{"promptMs":500,"genMs":4000,"ttftMs":600,
               "promptTokens":200,"generatedTokens":40,"budget":40}}""",
        )
        session.finish(DebugOutcome.COMPLETED)

        val report = session.toReport(
            runtime = RuntimeInfo(),
            settings = DebugSettingsSnapshot(maxTokens = 40),
            exportedAtMs = clock.now,
        )
        assertEquals(40, report.generatedTokens)
        assertEquals(10.0, report.tokensPerSecond, 0.0001)
        assertEquals(10_000L, report.totalMs)
    }

    @Test
    fun reportWithoutMetricsFallsBackToPieceCount() {
        val clock = FakeClock()
        val session = session(clock)
        clock.advance(1_000)
        session.onToken()
        repeat(3) {
            clock.advance(1_000)
            session.onToken()
        }
        session.finish(DebugOutcome.CANCELLED)
        val report = session.toReport(RuntimeInfo(), DebugSettingsSnapshot(), clock.now)
        assertEquals(4, report.generatedTokens)
        assertEquals(DebugOutcome.CANCELLED, report.outcome)
        assertNull(report.metrics)
    }

    @Test
    fun reportDefaultsToErrorWhenNeverFinished() {
        val session = session(FakeClock())
        val report = session.toReport(RuntimeInfo(), DebugSettingsSnapshot(), 9_000L)
        assertEquals(DebugOutcome.ERROR, report.outcome)
        // The session never ended: keep the end timestamp unknown instead of inventing one.
        assertNull(report.endedAtMs)
        assertEquals(8_000L, report.totalMs)
    }
}
