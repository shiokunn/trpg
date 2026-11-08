package io.github.shiokunn.trpg.core.ai

import io.github.shiokunn.trpg.core.domain.Attributes
import io.github.shiokunn.trpg.core.domain.CombatStats
import kotlinx.serialization.Serializable

@Serializable
data class ScenarioDraft(
    val title: String = "",
    val premise: String = "",
    val length: String = "",
    val difficulty: String = "",
    val openingHook: String = "",
)

@Serializable
data class LocationDraft(
    val name: String = "",
    val description: String = "",
    val connections: List<String> = emptyList(),
    val secrets: List<String> = emptyList(),
)

@Serializable
data class NpcDraft(
    val name: String = "",
    val description: String = "",
    val personality: String = "",
    val occupation: String = "",
    val disposition: String = "",
    val locationName: String = "",
    val secrets: List<String> = emptyList(),
    val combat: CombatStats? = null,
)

@Serializable
data class QuestDraft(
    val title: String = "",
    val description: String = "",
    val objectives: List<String> = emptyList(),
    val rewards: List<String> = emptyList(),
)

@Serializable
data class OpeningDraft(
    val location: LocationDraft = LocationDraft(),
    val npcs: List<NpcDraft> = emptyList(),
    val quests: List<QuestDraft> = emptyList(),
)

/** One combined world + scenario + opening draft generated before play starts. */
@Serializable
data class WorldDraft(
    val name: String = "",
    val genre: String = "",
    val era: String = "",
    val setting: String = "",
    val atmosphere: String = "",
    val history: String = "",
    val geography: String = "",
    val socialStructure: String = "",
    val factions: List<String> = emptyList(),
    val techMagic: String = "",
    val specialSettings: List<String> = emptyList(),
    val objective: String = "",
    val problem: String = "",
    val secrets: List<String> = emptyList(),
    val scenario: ScenarioDraft = ScenarioDraft(),
    val opening: OpeningDraft = OpeningDraft(),
)

@Serializable
data class SkillDraft(
    val name: String = "",
    val level: Int = 0,
    val description: String = "",
)

@Serializable
data class ItemDraft(
    val name: String = "",
    val description: String = "",
    val quantity: Int = 1,
    val type: String = "misc",
    val power: Int = 0,
    val defense: Int = 0,
    val tags: List<String> = emptyList(),
)

/** A complete starting character proposal. Attributes/skills are validated by CharacterRules. */
@Serializable
data class CharacterDraft(
    val name: String = "",
    val species: String = "",
    val occupation: String = "",
    val background: String = "",
    val personality: String = "",
    val attributes: Attributes = Attributes(),
    val skills: List<SkillDraft> = emptyList(),
    val items: List<ItemDraft> = emptyList(),
)

object DraftLimits {
    const val MAX_NAME = 80
    const val MAX_SHORT_TEXT = 500
    const val MAX_LONG_TEXT = 4000
    const val MAX_LIST_ITEMS = 12
    const val MAX_NPCS = 8
    const val MAX_QUESTS = 8
    const val MAX_OBJECTIVES = 8
    const val MAX_ITEMS = 20
    const val MAX_ITEM_TAGS = 8
}
