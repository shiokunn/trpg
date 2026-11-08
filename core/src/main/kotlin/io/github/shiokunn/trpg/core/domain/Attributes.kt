package io.github.shiokunn.trpg.core.domain

import kotlinx.serialization.Serializable

/** The six core attributes of the game's D6 system. */
@Serializable
enum class AttributeId(val label: String, val meaning: String) {
    STR("筋力", "肉体的な力"),
    DEX("敏捷", "身体操作・器用さ"),
    INT("知力", "知識・分析"),
    WIL("意志", "精神力・忍耐"),
    PER("感覚", "観察・気付き"),
    CHA("魅力", "交渉・対人能力"),
    ;

    companion object {
        fun fromId(raw: String): AttributeId? =
            entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
    }
}

/**
 * Character attributes. Creation rules live in [io.github.shiokunn.trpg.core.rules.CharacterRules]:
 * each value is 1..5 and the sum is exactly 18.
 */
@Serializable
data class Attributes(
    val str: Int = 1,
    val dex: Int = 1,
    val int: Int = 1,
    val wil: Int = 1,
    val per: Int = 1,
    val cha: Int = 1,
) {
    fun valueOf(id: AttributeId): Int = when (id) {
        AttributeId.STR -> str
        AttributeId.DEX -> dex
        AttributeId.INT -> int
        AttributeId.WIL -> wil
        AttributeId.PER -> per
        AttributeId.CHA -> cha
    }

    fun with(id: AttributeId, value: Int): Attributes = when (id) {
        AttributeId.STR -> copy(str = value)
        AttributeId.DEX -> copy(dex = value)
        AttributeId.INT -> copy(int = value)
        AttributeId.WIL -> copy(wil = value)
        AttributeId.PER -> copy(per = value)
        AttributeId.CHA -> copy(cha = value)
    }

    fun total(): Int = str + dex + int + wil + per + cha

    fun allInRange(): Boolean = entries().all { it in MIN..MAX }

    fun entries(): List<Int> = listOf(str, dex, int, wil, per, cha)

    companion object {
        const val MIN = 1
        const val MAX = 5
        const val TOTAL_POINTS = 18

        fun balanced(): Attributes = Attributes(3, 3, 3, 3, 3, 3)
    }
}
