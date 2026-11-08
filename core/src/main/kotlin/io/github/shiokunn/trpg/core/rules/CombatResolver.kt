package io.github.shiokunn.trpg.core.rules

import io.github.shiokunn.trpg.core.domain.AttributeId
import io.github.shiokunn.trpg.core.domain.CombatStats
import io.github.shiokunn.trpg.core.domain.Npc
import kotlin.math.floor

data class Combatant(
    val id: String,
    val name: String,
    val hp: Int,
    val maxHp: Int,
    val str: Int,
    val dex: Int,
    val stats: CombatStats,
) {
    fun hpClamped(): Int = hp.coerceIn(0, maxHp.coerceAtLeast(1))
    fun isDown(): Boolean = hpClamped() <= 0
}

data class CombatRoundResult(
    val attackRoll: Int,
    val defenseRoll: Int,
    val attackTotal: Int,
    val defenseTotal: Int,
    val hit: Boolean,
    val damage: Int,
    val defenderHpAfter: Int,
    val description: String,
)

/**
 * Combat maths from the specification. The AI may describe the fight but never
 * decides hit or damage.
 */
class CombatResolver(private val dice: DiceSource = RandomDiceSource()) {

    fun resolveAttack(
        attacker: Combatant,
        defender: Combatant,
        useDexterity: Boolean = false,
    ): CombatRoundResult {
        val attackAttribute = if (useDexterity) attacker.dex else attacker.str
        val attackRoll = dice.rollD6().coerceIn(1, 6)
        val defenseRoll = dice.rollD6().coerceIn(1, 6)

        val attackTotal = attackRoll + attackAttribute + attacker.stats.attackSkillLevel
        val defenseTotal = defenseRoll + defender.dex + defender.stats.defenseSkillLevel
        val hit = attackTotal > defenseTotal

        val damage = if (hit) {
            calculateDamage(
                attackPower = attacker.stats.attackPower,
                attackTotal = attackTotal,
                defenseTotal = defenseTotal,
                defense = defender.stats.defense,
            )
        } else {
            0
        }
        val hpAfter = (defender.hpClamped() - damage).coerceAtLeast(0)
        val description = buildString {
            append(attacker.name)
            append(" の攻撃: 1D6($attackRoll) + ")
            append(if (useDexterity) "DEX" else "STR")
            append("($attackAttribute) + 技能(")
            append(attacker.stats.attackSkillLevel)
            append(") = $attackTotal / ")
            append(defender.name)
            append(" の防御: 1D6($defenseRoll) + DEX(${defender.dex}) + 技能(")
            append(defender.stats.defenseSkillLevel)
            append(") = $defenseTotal → ")
            if (hit) append("命中、$damage ダメージ") else append("回避")
        }
        return CombatRoundResult(
            attackRoll = attackRoll,
            defenseRoll = defenseRoll,
            attackTotal = attackTotal,
            defenseTotal = defenseTotal,
            hit = hit,
            damage = damage,
            defenderHpAfter = hpAfter,
            description = description,
        )
    }

    companion object {
        fun calculateDamage(attackPower: Int, attackTotal: Int, defenseTotal: Int, defense: Int): Int =
            maxOf(1, attackPower + floor((attackTotal - defenseTotal - 1) / 3.0).toInt() - defense)

        fun playerCombatant(
            id: String,
            name: String,
            hp: Int,
            maxHp: Int,
            str: Int,
            dex: Int,
            combat: CombatStats,
        ): Combatant = Combatant(id, name, hp, maxHp, str, dex, combat)

        fun npcCombatant(npc: Npc): Combatant? {
            val stats = npc.combat ?: return null
            return Combatant(
                id = npc.id,
                name = npc.name,
                hp = INITIAL_NPC_HP,
                maxHp = INITIAL_NPC_HP,
                str = 3,
                dex = 3,
                stats = stats.normalized(),
            )
        }

        const val INITIAL_NPC_HP = 10
    }
}

/** Attack attribute used by a check, mapped from the AI's requested attribute. */
fun AttributeId.isPhysicalAttack(): Boolean = this == AttributeId.STR || this == AttributeId.DEX
