package io.github.shiokunn.trpg.game

import io.github.shiokunn.trpg.ai.engine.PromptMessage
import io.github.shiokunn.trpg.core.ai.AiParseResult
import io.github.shiokunn.trpg.core.ai.GmRepair
import io.github.shiokunn.trpg.core.ai.GmRepairIssue
import io.github.shiokunn.trpg.core.ai.GmResponse
import io.github.shiokunn.trpg.core.ai.GmResponseParser
import io.github.shiokunn.trpg.core.ai.GmResponseRules
import io.github.shiokunn.trpg.core.domain.ChatMessage
import io.github.shiokunn.trpg.core.domain.ChatRole
import io.github.shiokunn.trpg.core.domain.World
import io.github.shiokunn.trpg.core.prompt.PromptBuilder
import io.github.shiokunn.trpg.core.state.GameSnapshot
import io.github.shiokunn.trpg.core.state.MutationResult
import io.github.shiokunn.trpg.core.state.StateChangeEngine
import io.github.shiokunn.trpg.data.repository.SessionBundle
import kotlinx.serialization.json.Json

/** Which single repair retry to run after a rejected reply. */
enum class RepairMode { STRUCTURE, CONTENT }

/** Outcome of strict validation plus the optional deterministic pre-pass. */
sealed interface Attempt {
    data class Accepted(
        val response: GmResponse,
        val snapshot: GameSnapshot,
    ) : Attempt

    data class Rejected(
        val errors: List<String>,
        val issues: List<GmRepairIssue> = emptyList(),
        /** Truncated raw is kept so the repair prompt can work from the actual reply. */
        val raw: String = "",
    ) : Attempt
}

data class Evaluation(
    val attempt: Attempt,
    val localRepairApplied: Boolean = false,
)

/**
 * Strict validation with an optional deterministic pre-pass. Local repair is never
 * counted as an LLM retry and is used only when the repaired JSON passes the exact
 * same validation as a normal response.
 *
 * A LENGTH/CONTEXT finish is handled before parsing: truncated text is never treated
 * as a complete JSON reply, and its raw text plus classification survive for repair.
 */
fun evaluateTurn(
    raw: String,
    snapshot: GameSnapshot,
    bundle: SessionBundle,
    allowLocalRepair: Boolean,
    truncated: Boolean = false,
    truncationDetail: String? = null,
): Evaluation {
    if (truncated) {
        val message = truncationDetail ?: "応答がトークン上限で途中に切れました。"
        return Evaluation(
            Attempt.Rejected(
                errors = listOf(message),
                issues = listOf(GmRepair.analyzeTruncation(message)),
                raw = raw.take(GmTurnService.MAX_REPAIR_JSON_CHARS),
            ),
        )
    }

    val parsed = GmResponseParser.parse(raw)
    if (parsed is AiParseResult.Success) {
        return Evaluation(validateSemantics(parsed.value, snapshot, raw))
    }

    val failure = parsed as AiParseResult.Failure
    val issues = GmRepair.analyze(failure.errors)
    if (allowLocalRepair) {
        val repaired = GmRepair.repairTopLevelUnknownKeys(raw)
        if (repaired != null) {
            val reparsed = GmResponseParser.parse(repaired)
            if (reparsed is AiParseResult.Success) {
                val result = validateSemantics(reparsed.value, snapshot, repaired)
                if (result is Attempt.Accepted) {
                    return Evaluation(result, localRepairApplied = true)
                }
            }
        }
    }
    return Evaluation(
        Attempt.Rejected(
            errors = failure.errors.map { it.message },
            issues = issues,
            raw = raw.take(GmTurnService.MAX_REPAIR_JSON_CHARS),
        ),
    )
}

/** Game-specific validation that cannot be expressed in the JSON schema alone. */
private fun validateSemantics(
    response: GmResponse,
    snapshot: GameSnapshot,
    raw: String,
): Attempt {
    val referenceErrors = validateReferences(response, snapshot)
    if (referenceErrors.isNotEmpty()) return rejectSemantic(referenceErrors, raw)

    return when (val mutation = StateChangeEngine.apply(snapshot, response.stateChanges)) {
        is MutationResult.Rejected -> rejectSemantic(mutation.errors, raw)
        is MutationResult.Applied -> Attempt.Accepted(response, mutation.snapshot)
    }
}

/**
 * Semantic failures are game-rule violations even though they never reach the JSON
 * parser. Keep the raw reply and the GAME_RULE classification for the repair prompt.
 */
private fun rejectSemantic(errors: List<String>, raw: String): Attempt.Rejected = Attempt.Rejected(
    errors = errors,
    issues = GmRepair.analyzeGameRule(errors),
    raw = raw.take(GmTurnService.MAX_REPAIR_JSON_CHARS),
)

/** Checks the IDs the pure state validator cannot see (skills, NPC memories). */
private fun validateReferences(response: GmResponse, snapshot: GameSnapshot): List<String> {
    val errors = mutableListOf<String>()
    val skillIds = snapshot.character.skills.map { it.id }.toSet()
    response.choices.forEachIndexed { index, choice ->
        val skillId = choice.check?.skillId ?: return@forEachIndexed
        if (skillId !in skillIds) {
            errors += "choices[$index].check.skillId「$skillId」は所持スキルに存在しません"
        }
    }
    val npcIds = snapshot.npcs.map { it.id }.toSet()
    response.npcMemories.forEachIndexed { index, memory ->
        if (memory.npcId !in npcIds) {
            errors += "npcMemories[$index].npcId「${memory.npcId}」は存在しません"
        }
    }
    return errors
}

/** World information the AI produced when the adventure was created. */
private fun initialScenarioFrom(world: World, summary: String): String = buildString {
    append("世界名: ").append(world.name)
    if (world.genre.isNotBlank()) append(" / ジャンル: ").append(world.genre)
    if (world.era.isNotBlank()) append(" / 時代: ").append(world.era)
    if (world.setting.isNotBlank()) append("\n設定: ").append(world.setting)
    if (world.atmosphere.isNotBlank()) append("\n雰囲気: ").append(world.atmosphere)
    if (world.history.isNotBlank()) append("\n歴史: ").append(world.history)
    if (world.objective.isNotBlank()) append("\n目的: ").append(world.objective)
    if (world.problem.isNotBlank()) append("\n問題: ").append(world.problem)
    if (world.scenarioPremise.isNotBlank()) append("\nシナリオ前提: ").append(world.scenarioPremise)
    if (summary.isNotBlank()) append("\n冒険のあらすじ: ").append(summary)
}

/** The exchange immediately before the current turn. */
private fun previousChatFrom(chat: List<ChatMessage>): String = chat.takeLast(2)
    .joinToString("\n") { message ->
        val label = when (message.role) {
            ChatRole.PLAYER -> "プレイヤー"
            ChatRole.GM -> "GM"
            ChatRole.SYSTEM -> "システム"
        }
        "$label: ${message.content}"
    }

/**
 * Both repair modes receive the raw reply: structure repairs need the broken JSON,
 * content repairs need the actual field values to correct. Content repairs also get
 * the scenario, the previous exchange and the current game state as context.
 */
fun buildRepairMessages(
    mode: RepairMode,
    rejected: Attempt.Rejected,
    bundle: SessionBundle,
    recentChat: List<ChatMessage>,
    snapshot: GameSnapshot,
): List<PromptMessage> {
    val userMessage = buildString {
        if (mode == RepairMode.CONTENT) {
            append("最初のシナリオ（冒険開始時に生成された世界情報）:\n")
            append(initialScenarioFrom(bundle.world, bundle.session.summary))
            append("\n\n")
            val previousChat = previousChatFrom(recentChat)
            if (previousChat.isNotBlank()) {
                append("一つ前の会話履歴:\n").append(previousChat).append("\n\n")
            }
            append("現在のゲーム状態:\n").append(currentStateFrom(snapshot)).append("\n\n")
        }

        // Shared label: both instructions refer to "前回の応答（JSON）".
        append("前回の応答（JSON）:\n").append(rejected.raw).append("\n\n")

        val narrativeViolation = rejected.issues.any {
            it.message.contains("narrative", ignoreCase = true)
        }
        val narrative = validNarrativeFrom(rejected.raw)
            .take(GmResponseRules.MAX_NARRATIVE_CHARS)
        if (narrative.isNotBlank() && !narrativeViolation) {
            append("契約に違反していない情景描写（変更しないでください）:\n")
                .append(narrative).append("\n\n")
        }

        append(
            if (mode == RepairMode.STRUCTURE) {
                GmRepair.structuralInstruction(rejected.issues)
            } else {
                GmRepair.contentInstruction(rejected.issues)
            },
        )
    }
    return listOf(
        PromptMessage("system", PromptBuilder.repairSystemPrompt()),
        PromptMessage("user", userMessage),
    )
}

/** Compact snapshot summary for content repairs; no private conversation is logged. */
private fun currentStateFrom(snapshot: GameSnapshot): String = buildString {
    append("ターン: ").append(snapshot.turn + 1)
    val location = snapshot.locations.firstOrNull { it.id == snapshot.currentLocationId }
    if (location != null) append("\n現在地: ").append(location.name)
    append("\nHP: ").append(snapshot.character.hp).append('/').append(snapshot.character.maxHp)
    append("\nレベル: ").append(io.github.shiokunn.trpg.core.rules.CharacterRules.levelForXp(snapshot.character.experience))
    if (snapshot.activeCombat != null) append("\n戦闘中: はい")
}

/** Keeps only the narrative text; drops every other (possibly invalid) JSON field. */
private fun validNarrativeFrom(raw: String): String {
    val parsed = GmResponseParser.parse(raw)
    if (parsed is AiParseResult.Success) return parsed.value.narrative
    val match = NARRATIVE_REGEX.find(raw) ?: return ""
    val escaped = match.groupValues[1]
    return runCatching { Json.decodeFromString<String>("\"$escaped\"") }
        .getOrDefault(escaped)
}

private val NARRATIVE_REGEX = Regex("\"narrative\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
