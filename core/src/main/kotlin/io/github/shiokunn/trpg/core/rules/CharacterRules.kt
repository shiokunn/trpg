package io.github.shiokunn.trpg.core.rules

import io.github.shiokunn.trpg.core.domain.Attributes
import io.github.shiokunn.trpg.core.domain.Skill

/** Validation for character creation and growth. All limits come from the specification. */
object CharacterRules {
    const val TOTAL_ATTRIBUTE_POINTS = 18
    const val ATTRIBUTE_MIN = 1
    const val ATTRIBUTE_MAX = 5
    const val SKILL_CREATION_POINTS = 6
    const val SKILL_CREATION_MAX = 2
    const val SKILL_MAX = 3
    const val INITIAL_HP = 10

    fun validateAttributes(attributes: Attributes): List<String> {
        val errors = mutableListOf<String>()
        if (!attributes.allInRange()) {
            errors += "能力値はそれぞれ ${ATTRIBUTE_MIN}〜${ATTRIBUTE_MAX} の範囲で入力してください"
        }
        if (attributes.total() != TOTAL_ATTRIBUTE_POINTS) {
            errors += "能力値の合計は ${TOTAL_ATTRIBUTE_POINTS} にしてください（現在 ${attributes.total()}）"
        }
        return errors
    }

    fun validateSkillsForCreation(skills: List<Skill>): List<String> {
        val errors = mutableListOf<String>()
        if (skills.any { it.name.isBlank() }) {
            errors += "スキル名を入力してください"
        }
        if (skills.map { it.name.trim() }.distinct().size != skills.size) {
            errors += "同じ名前のスキルは登録できません"
        }
        if (skills.any { it.level !in 0..SKILL_CREATION_MAX }) {
            errors += "作成時のスキルレベルは 0〜${SKILL_CREATION_MAX} です"
        }
        val total = skills.sumOf { it.level }
        if (total != SKILL_CREATION_POINTS) {
            errors += "スキルポイントの合計は ${SKILL_CREATION_POINTS} にしてください（現在 $total）"
        }
        return errors
    }

    fun validateSkillLevel(level: Int): Boolean = level in 0..SKILL_MAX

    /** XP threshold for reaching [level] (level starts at 1). */
    fun xpForLevel(level: Int): Int = ((level - 1).coerceAtLeast(0)) * 10

    fun levelForXp(xp: Int): Int = (xp.coerceAtLeast(0) / 10) + 1

    fun xpIntoLevel(xp: Int): Int = xp.coerceAtLeast(0) % 10

    /** Returns the skills that may be raised by one point at the given level. */
    fun canRaiseSkill(skill: Skill): Boolean = skill.level < SKILL_MAX

    fun nextLevelXp(xp: Int): Int = xpForLevel(levelForXp(xp) + 1)
}
