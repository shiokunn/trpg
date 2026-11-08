package io.github.shiokunn.trpg.core.domain

import kotlinx.serialization.Serializable

@Serializable
data class Character(
    val id: String,
    val name: String,
    val species: String = "",
    val occupation: String = "",
    val background: String = "",
    val personality: String = "",
    val attributes: Attributes = Attributes(),
    val skills: List<Skill> = emptyList(),
    val hp: Int = INITIAL_HP,
    val maxHp: Int = INITIAL_HP,
    val inventory: List<Item> = emptyList(),
    /** IDs of equipped items. */
    val equipment: List<String> = emptyList(),
    val experience: Int = 0,
) {
    fun skillById(id: String?): Skill? = skills.firstOrNull { it.id == id }

    fun skillLevelOf(id: String?): Int = skillById(id)?.level ?: 0

    fun itemById(id: String): Item? = inventory.firstOrNull { it.id == id }

    fun hpClamped(): Int = hp.coerceIn(0, maxHp.coerceAtLeast(1))

    fun isDown(): Boolean = hpClamped() <= 0

    companion object {
        const val INITIAL_HP = 10
    }
}
