package io.github.shiokunn.trpg.core.domain

import kotlinx.serialization.Serializable

@Serializable
data class CombatStats(
    val attackPower: Int = 1,
    val defense: Int = 0,
    val attackSkillLevel: Int = 0,
    val defenseSkillLevel: Int = 0,
    /** Starting/remaining HP. Stored inside the NPC JSON column. */
    val hp: Int = DEFAULT_HP,
) {
    fun normalized(): CombatStats = copy(
        attackPower = attackPower.coerceIn(0, 20),
        defense = defense.coerceIn(0, 20),
        attackSkillLevel = attackSkillLevel.coerceIn(0, Skill.MAX_LEVEL),
        defenseSkillLevel = defenseSkillLevel.coerceIn(0, Skill.MAX_LEVEL),
        hp = hp.coerceIn(0, MAX_HP),
    )

    companion object {
        const val DEFAULT_HP = 10
        const val MAX_HP = 999
    }
}

@Serializable
data class Npc(
    val id: String,
    val name: String,
    val description: String = "",
    val personality: String = "",
    val occupation: String = "",
    /** -100..100. */
    val relationship: Int = 0,
    val disposition: String = "",
    val secrets: List<String> = emptyList(),
    val isAlive: Boolean = true,
    val isDiscovered: Boolean = false,
    val locationId: String? = null,
    val combat: CombatStats? = null,
) {
    fun clamped(): Npc = copy(relationship = relationship.coerceIn(MIN_RELATION, MAX_RELATION))

    companion object {
        const val MIN_RELATION = -100
        const val MAX_RELATION = 100
    }
}
