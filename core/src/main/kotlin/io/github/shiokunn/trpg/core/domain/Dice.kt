package io.github.shiokunn.trpg.core.domain

import kotlinx.serialization.Serializable

@Serializable
enum class RollMode(val label: String) {
    NORMAL("通常"),
    ADVANTAGE("有利"),
    DISADVANTAGE("不利"),
}

@Serializable
enum class CheckOutcome(val label: String) {
    CRITICAL_SUCCESS("大成功"),
    SUCCESS("成功"),
    PARTIAL_SUCCESS("部分的成功"),
    FAILURE("失敗"),
}

@Serializable
data class DiceResult(
    val attributeId: AttributeId,
    val skillId: String? = null,
    val skillName: String = "",
    /** One die normally, two dice for advantage/disadvantage. */
    val dice: List<Int>,
    val rollMode: RollMode = RollMode.NORMAL,
    val attributeValue: Int,
    val skillLevel: Int = 0,
    val modifiers: Int = 0,
    val total: Int,
    val difficulty: Int,
    val outcome: CheckOutcome,
    val reason: String = "",
    val turn: Int = 0,
    val createdAt: Long = 0L,
) {
    val usedDie: Int
        get() = when {
            dice.isEmpty() -> 0
            rollMode == RollMode.ADVANTAGE -> dice.max()
            rollMode == RollMode.DISADVANTAGE -> dice.min()
            else -> dice.first()
        }
}

/** Standard difficulty presets from the specification. */
enum class Difficulty(val value: Int, val label: String) {
    EASY(4, "簡単"),
    RATHER_EASY(6, "やや簡単"),
    NORMAL(8, "普通"),
    HARD(10, "難しい"),
    VERY_HARD(12, "非常に難しい"),
    EXTREME(14, "極めて難しい"),
    ;

    companion object {
        fun labelFor(value: Int): String = entries.firstOrNull { it.value == value }?.label ?: "難易度 $value"
    }
}
