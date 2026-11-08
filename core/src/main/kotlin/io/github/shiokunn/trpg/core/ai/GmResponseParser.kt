package io.github.shiokunn.trpg.core.ai

import kotlinx.serialization.SerializationException

/**
 * Strict parser for one GM turn. Decoding rejects unknown keys, unknown enum values
 * and wrong types; [validate] then enforces the game-specific contract.
 *
 * State changes are validated separately against the live game snapshot so that
 * ID existence and range rules are applied atomically.
 */
object GmResponseParser {

    fun parse(raw: String): AiParseResult<GmResponse> {
        val candidate = AiJson.extractObject(raw)
            ?: return AiParseResult.Failure(
                listOf("JSONオブジェクトが見つかりません。JSONのみを出力してください。"),
            )

        val response = try {
            AiJson.strict.decodeFromString(GmResponse.serializer(), candidate)
        } catch (t: SerializationException) {
            return AiParseResult.Failure(
                listOf("JSONスキーマが不正です: ${t.message ?: "解析に失敗しました"}"),
            )
        } catch (t: IllegalArgumentException) {
            return AiParseResult.Failure(
                listOf("JSONの値が不正です: ${t.message ?: "解析に失敗しました"}"),
            )
        }

        val errors = validate(response)
        return if (errors.isEmpty()) {
            AiParseResult.Success(response)
        } else {
            AiParseResult.Failure(errors)
        }
    }

    fun validate(response: GmResponse): List<String> {
        val errors = mutableListOf<String>()

        if (response.narrative.isBlank()) {
            errors += "narrative（情景描写）が空です"
        } else if (response.narrative.length > GmResponseRules.MAX_NARRATIVE_CHARS) {
            errors += "narrativeが長すぎます（最大${GmResponseRules.MAX_NARRATIVE_CHARS}文字）"
        }

        if (response.sceneTitle.length > GmResponseRules.MAX_SCENE_TITLE_CHARS) {
            errors += "sceneTitleが長すぎます（最大${GmResponseRules.MAX_SCENE_TITLE_CHARS}文字）"
        }
        if (response.eventSummary.length > GmResponseRules.MAX_EVENT_SUMMARY_CHARS) {
            errors += "eventSummaryが長すぎます（最大${GmResponseRules.MAX_EVENT_SUMMARY_CHARS}文字）"
        }

        if (response.choices.size !in GmResponseRules.MIN_CHOICES..GmResponseRules.MAX_CHOICES) {
            errors += "choicesは${GmResponseRules.MIN_CHOICES}〜${GmResponseRules.MAX_CHOICES}個にしてください" +
                "（現在${response.choices.size}個）"
        }

        val ids = mutableSetOf<String>()
        response.choices.forEachIndexed { index, choice ->
            val label = "choices[$index]"
            if (choice.id.isBlank()) {
                errors += "$label.idが空です"
            } else if (!ids.add(choice.id)) {
                errors += "$label.idが重複しています（${choice.id}）"
            }
            if (choice.text.isBlank()) {
                errors += "$label.textが空です"
            } else if (choice.text.length > GmResponseRules.MAX_CHOICE_TEXT_CHARS) {
                errors += "$label.textが長すぎます（最大${GmResponseRules.MAX_CHOICE_TEXT_CHARS}文字）"
            }
            choice.check?.let { check ->
                if (check.difficulty !in GmResponseRules.ALLOWED_DIFFICULTIES) {
                    errors += "$label.check.difficultyは" +
                        "${GmResponseRules.ALLOWED_DIFFICULTIES.sorted().joinToString("/")}のいずれかにしてください" +
                        "（現在${check.difficulty}）"
                }
                if (check.skillId != null && check.skillId.isBlank()) {
                    errors += "$label.check.skillIdが空文字です。使わない場合はnullにしてください"
                }
            }
        }

        if (response.stateChanges.size > io.github.shiokunn.trpg.core.state.StateChangeLimits.MAX_CHANGES_PER_TURN) {
            errors += "stateChangesが多すぎます" +
                "（最大${io.github.shiokunn.trpg.core.state.StateChangeLimits.MAX_CHANGES_PER_TURN}件）"
        }

        if (response.memories.size > GmResponseRules.MAX_MEMORIES_PER_TURN) {
            errors += "memoriesが多すぎます（最大${GmResponseRules.MAX_MEMORIES_PER_TURN}件）"
        }
        response.memories.forEachIndexed { index, memory ->
            val label = "memories[$index]"
            if (memory.content.isBlank()) {
                errors += "$label.contentが空です"
            } else if (memory.content.length > GmResponseRules.MAX_MEMORY_CHARS) {
                errors += "$label.contentが長すぎます（最大${GmResponseRules.MAX_MEMORY_CHARS}文字）"
            }
            if (memory.importance !in 1..5) {
                errors += "$label.importanceは1〜5にしてください（現在${memory.importance}）"
            }
            if (memory.relatedIds.size > 20) {
                errors += "$label.relatedIdsが多すぎます（最大20件）"
            }
        }

        if (response.npcMemories.size > GmResponseRules.MAX_NPC_MEMORIES_PER_TURN) {
            errors += "npcMemoriesが多すぎます（最大${GmResponseRules.MAX_NPC_MEMORIES_PER_TURN}件）"
        }
        response.npcMemories.forEachIndexed { index, memory ->
            val label = "npcMemories[$index]"
            if (memory.npcId.isBlank()) {
                errors += "$label.npcIdが空です"
            }
            if (memory.content.isBlank()) {
                errors += "$label.contentが空です"
            } else if (memory.content.length > GmResponseRules.MAX_MEMORY_CHARS) {
                errors += "$label.contentが長すぎます（最大${GmResponseRules.MAX_MEMORY_CHARS}文字）"
            }
            if (memory.importance !in 1..5) {
                errors += "$label.importanceは1〜5にしてください（現在${memory.importance}）"
            }
        }

        return errors
    }

    /** Prompt suffix used for the single repair retry. */
    fun repairInstruction(errors: List<String>): String = buildString {
        append("前回の応答は次の理由で契約に違反していました。\n")
        errors.take(10).forEach { error -> append("- ").append(error).append('\n') }
        append("説明・コードフェンス・JSON以外の文字を含めず、同じJSON契約で1つだけ出力してください。")
    }
}
