package io.github.shiokunn.trpg.core.ai

import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonObject

/** Broad category used to choose a repair strategy and to present the error in the UI. */
enum class GmErrorCategory {
    JSON_SYNTAX,
    JSON_SCHEMA,
    GAME_RULE,
    OTHER,
}

/** A classified validation issue, with as much location detail as the parser exposes. */
data class GmRepairIssue(
    val category: GmErrorCategory,
    val message: String,
    val key: String? = null,
    val path: String? = null,
)

/**
 * Deterministic, side-effect-free helpers for recovering from a broken GM response.
 *
 * Local repair is deliberately narrow: only an unknown **top-level** key is removed,
 * and only when the resulting JSON passes the original strict validation. Nested keys,
 * values, types and game rules are never silently rewritten.
 */
object GmRepair {

    private val UNKNOWN_KEY_REGEX = Regex("unknown key '([^']+)'", RegexOption.IGNORE_CASE)
    private val PATH_REGEX = Regex("at element: (.+)$")

    /** Maps the parser's error kinds onto repair strategies. */
    fun analyze(errors: List<AiError>): List<GmRepairIssue> = errors.map { error ->
        val category = when (error.kind) {
            AiErrorKind.JSON_EXTRACTION,
            AiErrorKind.JSON_TRUNCATED,
            AiErrorKind.JSON_SYNTAX,
            -> GmErrorCategory.JSON_SYNTAX

            AiErrorKind.JSON_SCHEMA -> GmErrorCategory.JSON_SCHEMA
            AiErrorKind.CONTRACT -> GmErrorCategory.GAME_RULE
        }
        GmRepairIssue(
            category = category,
            message = error.message,
            key = UNKNOWN_KEY_REGEX.find(error.message)?.groupValues?.getOrNull(1),
            path = PATH_REGEX.find(error.message)?.groupValues?.getOrNull(1)?.trim(),
        )
    }

    /**
     * Removes unknown top-level keys when that alone makes the response valid.
     *
     * Returns null when nothing could be repaired safely; the caller must then fall back
     * to an LLM repair. No nested object is touched and no allowed value is rewritten.
     */
    fun repairTopLevelUnknownKeys(raw: String): String? {
        val element = when (val extraction = AiJson.extractObject(raw)) {
            is AiJsonExtraction.Found -> extraction.element
            else -> return null
        }
        if (element !is JsonObject) return null

        val allowed = GmResponse.serializer().descriptor.elementNames.toSet()
        if (allowed.isEmpty() || element.keys.all { it in allowed }) return null

        val repaired = JsonObject(element.filterKeys { it in allowed })
        if (repaired == element) return null

        val candidate = AiJson.strict.encodeToString(JsonObject.serializer(), repaired)
        // The repaired JSON must pass the exact same validation as a normal response.
        return if (GmResponseParser.parse(candidate) is AiParseResult.Success) candidate else null
    }

    /** Exact list of keys the GM response may contain at the top level. */
    fun allowedTopLevelKeys(): String =
        GmResponse.serializer().descriptor.elementNames.joinToString(", ")

    /** Prompt suffix for syntax/schema problems: no story changes, only structure. */
    fun structuralInstruction(issues: List<GmRepairIssue>): String = buildString {
        append("前回の応答はJSON構造に問題がありました。\n")
        issues.take(10).forEach { issue ->
            append("- ").append(issue.message)
            if (issue.key != null) append("（問題のキー: ").append(issue.key).append('）')
            append('\n')
        }
        append("\n許可されるトップレベルキーは次のみです: ").append(allowedTopLevelKeys()).append('\n')
        append("- 契約にないキー（例: id）は削除してください。\n")
        append("- narrative と choices の文章・選択肢は変更しないでください。\n")
        append("- 新しいキーを追加しないでください。\n")
        append("- JSON以外の説明文・コードフェンスを出力しないでください。\n")
    }

    /** Prompt suffix for game-rule problems: state and already resolved checks stay untouched. */
    fun contentInstruction(issues: List<GmRepairIssue>): String = buildString {
        append("前回の応答はゲームルールに違反していました。\n")
        issues.take(10).forEach { issue ->
            append("- ").append(issue.message)
            if (issue.path != null) append("（場所: ").append(issue.path).append('）')
            append('\n')
        }
        append("\n- 違反している値だけをルールに合わせて修正してください。\n")
        append("- 物語の情景描写（narrative）は書き直さないでください。\n")
        append("- 出目・判定結果・確定済みのゲーム状態は変更しないでください。\n")
        append("- 選択肢を減らさず、id と text を維持してください。\n")
        append("- 契約にないキーを追加せず、JSON以外を出力しないでください。\n")
    }
}
