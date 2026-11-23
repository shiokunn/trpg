package io.github.shiokunn.trpg.core.ai

import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonObject

/** Broad category used to choose a repair strategy and to present the error in the UI. */
enum class GmErrorCategory {
    JSON_SYNTAX,
    JSON_SCHEMA,

    /** A game-rule/references violation, including semantic state validation. */
    GAME_RULE,

    /** Generation stopped before a complete reply (LENGTH / CONTEXT finish reason). */
    TRUNCATED,

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
    private val FIELD_PATH_REGEX = Regex("[A-Za-z_][A-Za-z0-9_]*(\\[[0-9]+\\])?(\\.[A-Za-z_][A-Za-z0-9_]*(\\[[0-9]+\\])?)+")

    private const val OUTPUT_RULE =
        "- 説明・コードフェンス・JSON以外の文字を含めず、完全なJSONオブジェクトを1つだけ出力してください。"

    /** Maps the parser's error kinds onto repair strategies. */
    fun analyze(errors: List<AiError>): List<GmRepairIssue> = errors.map { error ->
        val category = when (error.kind) {
            AiErrorKind.JSON_EXTRACTION,
            AiErrorKind.JSON_SYNTAX,
            -> GmErrorCategory.JSON_SYNTAX

            AiErrorKind.JSON_TRUNCATED -> GmErrorCategory.TRUNCATED
            AiErrorKind.JSON_SCHEMA -> GmErrorCategory.JSON_SCHEMA
            AiErrorKind.CONTRACT -> GmErrorCategory.GAME_RULE
        }
        GmRepairIssue(
            category = category,
            message = error.message,
            key = UNKNOWN_KEY_REGEX.find(error.message)?.groupValues?.getOrNull(1),
            path = pathFrom(error.message),
        )
    }

    /**
     * Classifies plain (already-formatted) semantic validation errors as game-rule
     * violations. These messages come from reference/state validation rather than the
     * JSON parser, so they have no [AiErrorKind] and must not fall back to OTHER.
     */
    fun analyzeGameRule(errors: List<String>): List<GmRepairIssue> = errors.map { message ->
        GmRepairIssue(
            category = GmErrorCategory.GAME_RULE,
            message = message,
            path = pathFrom(message),
        )
    }

    /** Classifies truncated-generation reports so the UI and the repair log agree. */
    fun analyzeTruncation(message: String): GmRepairIssue = GmRepairIssue(
        category = GmErrorCategory.TRUNCATED,
        message = message,
    )

    private fun pathFrom(message: String): String? =
        PATH_REGEX.find(message)?.groupValues?.getOrNull(1)?.trim()
            ?: FIELD_PATH_REGEX.find(message)?.value

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

    /**
     * Both repair prompts work from the same raw reply. Without this shared rule one
     * prompt could ask for a "continuation" while the other asks for a single complete
     * JSON object, which is exactly the ambiguity that produced another invalid reply.
     */
    private const val RAW_BASE_RULE =
        "- 下記の「前回の応答（JSON）」をベースに、報告された箇所だけを直した完全なJSONを出力してください。"

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
        append("- 直せるのは構造（キー・型・配列の形）だけです。値そのものは変更しないでください。\n")
        append("- narrative の本文と choices の id / text は維持してください。\n")
        append("- 新しいキーを追加しないでください。\n")
        append(RAW_BASE_RULE).append('\n')
        append(OUTPUT_RULE)
    }

    /** Prompt suffix for game-rule problems: only the reported values may change. */
    fun contentInstruction(issues: List<GmRepairIssue>): String = buildString {
        append("前回の応答はゲームルールに違反していました。\n")
        issues.take(10).forEach { issue ->
            append("- ").append(issue.message)
            if (issue.path != null) append("（場所: ").append(issue.path).append('）')
            append('\n')
        }
        append("\n- 違反している値だけをルールに合わせて修正してください。\n")
        // A literal "never touch narrative/choices/state" would forbid the very fix
        // requested when the violation is in one of those fields.
        append("- narrative の本文は維持してください。ただし narrative 自体が違反として報告されている場合は、その指示に従ってください。\n")
        append("- 出目・判定結果と「現在のゲーム状態」の確定値は変更しないでください。違反した stateChanges の値は修正してください。\n")
        append("- 選択肢の個数・id・text は維持し、違反している check の値（例: difficulty）だけを修正してください。\n")
        append("- 契約にないキーを追加しないでください。\n")
        append(RAW_BASE_RULE).append('\n')
        append(OUTPUT_RULE)
    }
}
