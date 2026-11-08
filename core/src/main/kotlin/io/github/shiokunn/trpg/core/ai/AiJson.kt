package io.github.shiokunn.trpg.core.ai

import kotlinx.serialization.json.Json

/** Shared strict JSON configuration for every AI contract. */
internal object AiJson {
    val strict: Json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        explicitNulls = false
    }

    /**
     * Models often wrap JSON in a Markdown fence or add a short preamble.
     * Extract the first `{` through the last `}` before strict parsing.
     */
    fun extractObject(raw: String): String? {
        val cleaned = stripFences(raw)
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        return if (start >= 0 && end > start) cleaned.substring(start, end + 1) else null
    }

    private fun stripFences(raw: String): String {
        val text = raw.trim()
        if (!text.startsWith("```")) return text
        val firstNewline = text.indexOf('\n')
        if (firstNewline < 0) {
            return text.removePrefix("```").removeSuffix("```").trim()
        }
        var body = text.substring(firstNewline + 1)
        val closing = body.lastIndexOf("```")
        if (closing >= 0) {
            body = body.substring(0, closing)
        }
        return body.trim()
    }
}
