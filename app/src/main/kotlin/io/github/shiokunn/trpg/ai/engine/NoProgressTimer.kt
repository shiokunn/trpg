package io.github.shiokunn.trpg.ai.engine

/**
 * Tracks how long the current generation has gone without a token. The native
 * decode call is synchronous, so a watchdog coroutine polls this timer and asks
 * the native side to abort when the model stops making progress.
 *
 * The clock is injectable so the timeout can be unit-tested without waiting.
 */
internal class NoProgressTimer(
    private val timeoutNanos: Long,
    private val now: () -> Long = System::nanoTime,
) {
    private var lastActivityNanos = now()

    fun touch() {
        lastActivityNanos = now()
    }

    fun timedOut(): Boolean = now() - lastActivityNanos >= timeoutNanos
}
