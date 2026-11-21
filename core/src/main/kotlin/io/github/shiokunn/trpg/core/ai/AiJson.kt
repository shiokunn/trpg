package io.github.shiokunn.trpg.core.ai

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Strict JSON configuration for every AI contract.
 *
 * The configuration is intentionally unforgiving: unknown keys, unquoted values and
 * explicit nulls for non-nullable fields are all rejected. [AiJsonExtraction] tells
 * the caller *which* rule was broken instead of collapsing everything into one
 * generic "invalid JSON" message.
 */
internal object AiJson {
    val strict: Json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        explicitNulls = false
    }

    /**
     * Extracts the JSON object from [raw].
     *
     * Unlike a naive "first `{` to last `}`" slice this handles the cases a model
     * actually produces: prose around the object, Markdown fences, braces inside
     * string values, escaped quotes, several candidate objects and a response that
     * was cut off mid-object.
     *
     * The first candidate that parses as a JSON object wins. A later candidate is
     * never preferred: a trailing example object must not replace the real answer.
     */
    fun extractObject(raw: String): AiJsonExtraction {
        val text = stripFences(raw)
        if (text.isBlank()) {
            return AiJsonExtraction.NotFound("応答が空です")
        }

        val candidates = mutableListOf<String>()
        var sawTruncated = false
        var index = 0
        while (index < text.length) {
            val start = text.indexOf('{', index)
            if (start < 0) break
            val end = findObjectEnd(text, start)
            if (end < 0) {
                // The object was never closed: the response was cut off.
                sawTruncated = true
                break
            }
            candidates += text.substring(start, end + 1)
            index = end + 1
        }

        if (candidates.isEmpty()) {
            return if (sawTruncated) {
                AiJsonExtraction.Truncated("JSONが途中で切れています（閉じ括弧がありません）")
            } else {
                AiJsonExtraction.NotFound("JSONオブジェクトが見つかりません")
            }
        }

        var firstError: String? = null
        for (candidate in candidates) {
            try {
                val element = strict.parseToJsonElement(candidate)
                if (element is JsonObject) {
                    return AiJsonExtraction.Found(element)
                }
                firstError = firstError
                    ?: "JSONのルートがオブジェクトではありません（配列やスカラーです）"
            } catch (t: SerializationException) {
                val detail = t.message?.substringBefore('\n')?.takeIf { it.isNotBlank() }
                firstError = firstError
                    ?: "JSONの構文が不正です: ${detail ?: "解析に失敗しました"}"
            }
        }

        // Every candidate was syntactically broken. Report the syntax problem
        // instead of letting the schema stage blame the wrong thing.
        return AiJsonExtraction.Malformed(
            firstError ?: "JSONの構文が不正です: 解析に失敗しました",
        )
    }

    /**
     * Finds the `}` that closes the object starting at [start], honouring JSON
     * string literals and escape sequences. Returns -1 when the object is not
     * closed.
     */
    private fun findObjectEnd(text: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        var i = start
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            i++
        }
        return -1
    }

    /** Removes a Markdown fence and any one-line intro before it. */
    private fun stripFences(raw: String): String {
        val text = raw.trim()
        // A fenced block may be preceded by a one-line intro such as "Here is the JSON:".
        val fenceIndex = text.indexOf("```")
        if (fenceIndex >= 0) {
            val afterFence = text.substring(fenceIndex)
            val firstNewline = afterFence.indexOf('\n')
            if (firstNewline >= 0) {
                var body = afterFence.substring(firstNewline + 1)
                val closing = body.indexOf("```")
                if (closing >= 0) {
                    body = body.substring(0, closing)
                }
                return body.trim()
            }
        }
        return text
    }
}
