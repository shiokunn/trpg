package io.github.shiokunn.trpg.core.ai

import io.github.shiokunn.trpg.core.domain.AttributeId
import io.github.shiokunn.trpg.core.domain.MemoryCategory
import io.github.shiokunn.trpg.core.domain.RollMode
import io.github.shiokunn.trpg.core.state.StateChange
import kotlinx.serialization.Serializable

/**
 * A check the AI asks the app to resolve. The AI never rolls: it only describes
 * the attribute/skill/difficulty, and the app rolls with [io.github.shiokunn.trpg.core.rules.DiceEngine].
 */
@Serializable
data class CheckRequest(
    val attribute: AttributeId,
    val skillId: String? = null,
    val difficulty: Int = 8,
    val rollMode: RollMode = RollMode.NORMAL,
)

@Serializable
data class GmChoice(
    val id: String = "",
    val text: String = "",
    val check: CheckRequest? = null,
)

@Serializable
data class MemoryDraft(
    val category: MemoryCategory,
    val content: String,
    val importance: Int = 3,
    val isSecret: Boolean = false,
    val isConfirmed: Boolean = true,
    val relatedIds: List<String> = emptyList(),
)

@Serializable
data class NpcMemoryDraft(
    val npcId: String,
    val content: String,
    val importance: Int = 3,
    val isSecret: Boolean = false,
)

/**
 * Choices persisted for the current scene so they survive app restarts.
 * Stored as the payload of a `CHOICES` game event.
 */
@Serializable
data class TurnChoices(
    val sceneTitle: String = "",
    val choices: List<GmChoice> = emptyList(),
)

/** The single JSON contract for one GM turn. */
@Serializable
data class GmResponse(
    val narrative: String = "",
    val sceneTitle: String = "",
    val choices: List<GmChoice> = emptyList(),
    val stateChanges: List<StateChange> = emptyList(),
    val memories: List<MemoryDraft> = emptyList(),
    val npcMemories: List<NpcMemoryDraft> = emptyList(),
    val eventSummary: String = "",
)

object GmResponseRules {
    const val MIN_CHOICES = 3
    const val MAX_CHOICES = 4
    const val MAX_NARRATIVE_CHARS = 8000
    const val MAX_CHOICE_TEXT_CHARS = 200
    const val MAX_SCENE_TITLE_CHARS = 60
    const val MAX_EVENT_SUMMARY_CHARS = 300
    const val MAX_MEMORIES_PER_TURN = 10
    const val MAX_MEMORY_CHARS = 500
    const val MAX_NPC_MEMORIES_PER_TURN = 10

    /** Only the six difficulties from the specification are legal. */
    val ALLOWED_DIFFICULTIES = setOf(4, 6, 8, 10, 12, 14)
}
