package io.github.shiokunn.trpg.core.state

import io.github.shiokunn.trpg.core.domain.Character
import io.github.shiokunn.trpg.core.domain.CombatState
import io.github.shiokunn.trpg.core.domain.Item
import io.github.shiokunn.trpg.core.domain.Location
import io.github.shiokunn.trpg.core.domain.Npc
import io.github.shiokunn.trpg.core.domain.Quest
import io.github.shiokunn.trpg.core.domain.QuestStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Every mutation the GM is allowed to request. */
@Serializable
enum class StateChangeType {
    @SerialName("HP_DELTA")
    HP_DELTA,

    @SerialName("XP_DELTA")
    XP_DELTA,

    @SerialName("ITEM_ADD")
    ITEM_ADD,

    @SerialName("ITEM_REMOVE")
    ITEM_REMOVE,

    @SerialName("NPC_RELATION_DELTA")
    NPC_RELATION_DELTA,

    @SerialName("QUEST_OBJECTIVE_PROGRESS")
    QUEST_OBJECTIVE_PROGRESS,

    @SerialName("QUEST_STATUS")
    QUEST_STATUS,

    @SerialName("LOCATION_CHANGE")
    LOCATION_CHANGE,

    @SerialName("FLAG_SET")
    FLAG_SET,

    @SerialName("COMBAT_START")
    COMBAT_START,

    @SerialName("COMBAT_END")
    COMBAT_END,
}

/**
 * A single declarative mutation proposed by the AI.
 *
 * The AI never writes state directly: the app validates the whole list and only then
 * applies it inside one database transaction. Field meaning depends on [type]:
 * - HP_DELTA / XP_DELTA / NPC_RELATION_DELTA: [amount]
 * - ITEM_ADD: [item]; ITEM_REMOVE: [targetId] and [amount] (defaults to 1)
 * - QUEST_OBJECTIVE_PROGRESS: [targetId], [objectiveIndex], [amount] (>0 complete)
 * - QUEST_STATUS: [targetId], [questStatus]
 * - LOCATION_CHANGE: [targetId] (location id)
 * - FLAG_SET: [targetId] (key), [value]
 * - COMBAT_START: [targetId] (NPC id); COMBAT_END: no target
 */
@Serializable
data class StateChange(
    val type: StateChangeType,
    val targetId: String = "",
    val amount: Int = 0,
    val value: String = "",
    val questStatus: QuestStatus? = null,
    val objectiveIndex: Int = -1,
    val item: Item? = null,
    /** Short player-visible explanation, used by the journal. */
    val reason: String = "",
)

/** Immutable view of the state a turn starts from. */
data class GameSnapshot(
    val character: Character,
    val npcs: List<Npc> = emptyList(),
    val quests: List<Quest> = emptyList(),
    val locations: List<Location> = emptyList(),
    val flags: Map<String, String> = emptyMap(),
    val currentLocationId: String? = null,
    val activeCombat: CombatState? = null,
    val turn: Int = 0,
)

sealed interface ValidationResult {
    data object Valid : ValidationResult
    data class Invalid(val errors: List<String>) : ValidationResult
}

sealed interface MutationResult {
    data class Applied(val snapshot: GameSnapshot) : MutationResult
    data class Rejected(val errors: List<String>) : MutationResult
}

object StateChangeLimits {
    const val MAX_CHANGES_PER_TURN = 32
    const val MAX_HP_DELTA = 100
    const val MAX_XP_DELTA = 1000
    const val MAX_RELATION_DELTA = 100
    const val MAX_FLAG_KEY_LENGTH = 64
    const val MAX_FLAG_VALUE_LENGTH = 200
    const val MAX_ITEM_NAME_LENGTH = 80
    const val MAX_ITEM_DESCRIPTION_LENGTH = 500
    const val MAX_INVENTORY_SIZE = 100
}
