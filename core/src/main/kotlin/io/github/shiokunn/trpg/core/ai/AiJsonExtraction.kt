package io.github.shiokunn.trpg.core.ai

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement

/**
 * The outcome of extracting and parsing one AI reply.
 *
 * The variants keep the different failure modes apart so the UI can explain what
 * went wrong instead of showing one generic "invalid JSON" message:
 *
 * - [NotFound]: no JSON object was present at all.
 * - [Truncated]: an object was started but never closed, i.e. the reply was cut off.
 * - [Malformed]: a candidate was found but it is not valid JSON.
 * - [Found]: the reply was parsed into a JSON element. It may still violate the
 *   schema or the content contract.
 */
internal sealed interface AiJsonExtraction {
    /** The parsed JSON element. */
    data class Found(val element: JsonElement) : AiJsonExtraction

    /** No JSON object could be located in the reply. */
    data class NotFound(val message: String) : AiJsonExtraction

    /** A JSON object was started but the reply ended before it was closed. */
    data class Truncated(val message: String) : AiJsonExtraction

    /** A candidate was found but it is not valid JSON. */
    data class Malformed(val message: String) : AiJsonExtraction
}

/**
 * Why an AI contract could not be satisfied.
 *
 * The distinction mirrors the pipeline in [AiJson]: a syntax error, a schema/type
 * error and a contract violation are three different problems with three
 * different fixes, so they are reported separately.
 */
enum class AiErrorKind {
    /** The reply contained no JSON object. */
    JSON_EXTRACTION,

    /** The reply was cut off in the middle of a JSON object. */
    JSON_TRUNCATED,

    /** The reply was not valid JSON. */
    JSON_SYNTAX,

    /** The JSON was valid but did not match the expected schema or types. */
    JSON_SCHEMA,

    /** The JSON matched the schema but violated a content rule. */
    CONTRACT,
}

/** A single failure with a machine-readable [kind] and a human-readable [message]. */
data class AiError(
    val kind: AiErrorKind,
    val message: String,
) {
    override fun toString(): String = message
}

/** Maps a kotlinx.serialization failure onto an [AiError]. */
internal fun SerializationException.toAiError(defaultMessage: String): AiError {
    val detail = message?.substringBefore('\n')?.takeIf { it.isNotBlank() }
    return AiError(AiErrorKind.JSON_SCHEMA, detail ?: defaultMessage)
}
