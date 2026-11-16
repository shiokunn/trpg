package io.github.shiokunn.trpg.ai.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoProgressTimerTest {

    @Test
    fun timesOutAfterTheConfiguredSilence() {
        var now = 1_000L
        val timer = NoProgressTimer(timeoutNanos = 100L) { now }

        now = 1_099L
        assertFalse(timer.timedOut())

        now = 1_100L
        assertTrue(timer.timedOut())
    }

    @Test
    fun touchingResetsTheDeadline() {
        var now = 0L
        val timer = NoProgressTimer(timeoutNanos = 100L) { now }

        now = 90L
        assertFalse(timer.timedOut())
        timer.touch()

        now = 150L
        assertFalse(timer.timedOut())

        now = 190L
        assertTrue(timer.timedOut())
    }
}
