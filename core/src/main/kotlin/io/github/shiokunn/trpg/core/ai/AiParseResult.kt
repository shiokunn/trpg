package io.github.shiokunn.trpg.core.ai

/** Result of parsing one AI reply. Never throws; callers may retry once with [errors]. */
sealed interface AiParseResult<out T> {
    data class Success<T>(val value: T) : AiParseResult<T>
    data class Failure(val errors: List<String>) : AiParseResult<Nothing>
}
