package io.github.shiokunn.trpg.core.ai

/**
 * Result of parsing one AI reply. Never throws; callers may retry once with [errors].
 *
 * [Failure] carries the individual [AiError]s so the caller can tell a syntax
 * problem from a schema problem from a contract violation, and can show the
 * specific reason in debug mode instead of one generic message.
 */
sealed interface AiParseResult<out T> {
    data class Success<T>(val value: T) : AiParseResult<T>
    data class Failure(val errors: List<AiError>) : AiParseResult<Nothing>
}
