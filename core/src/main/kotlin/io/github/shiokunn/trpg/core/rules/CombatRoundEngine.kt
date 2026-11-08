package io.github.shiokunn.trpg.core.rules

import io.github.shiokunn.trpg.core.domain.Character
import io.github.shiokunn.trpg.core.domain.CombatState
import io.github.shiokunn.trpg.core.domain.CombatStats

data class CombatRoundOutcome(
    val playerAttack: CombatRoundResult,
    val enemyAttack: CombatRoundResult?,
    val playerHpAfter: Int,
    val enemyHpAfter: Int,
    val enemyDefeated: Boolean,
    val description: String,
)

/**
 * One deterministic combat round:
 * player attack -> enemy defence -> damage -> enemy counterattack (if alive).
 *
 * The AI never participates; it may only narrate the returned description.
 */
class CombatRoundEngine(private val dice: DiceSource = RandomDiceSource()) {

    private val resolver = CombatResolver(dice)

    fun resolve(
        character: Character,
        combat: CombatState,
        useDexterity: Boolean = false,
    ): CombatRoundOutcome {
        val playerStats = playerCombatStats(character)
        val player = Combatant(
            id = character.id,
            name = character.name,
            hp = character.hp,
            maxHp = character.maxHp,
            str = character.attributes.str,
            dex = character.attributes.dex,
            stats = playerStats,
        )
        val enemyStats = CombatStats(
            attackPower = combat.enemyAttackPower,
            defense = combat.enemyDefense,
            attackSkillLevel = combat.enemyAttackSkill,
            defenseSkillLevel = combat.enemyDefenseSkill,
            hp = combat.enemyHp,
        ).normalized()
        val enemyCombatant = Combatant(
            id = combat.enemyNpcId,
            name = combat.enemyName,
            hp = combat.enemyHp,
            maxHp = combat.enemyMaxHp,
            str = 3,
            dex = 3,
            stats = enemyStats,
        )

        val attack = resolver.resolveAttack(player, enemyCombatant, useDexterity)
        val enemyHpAfter = attack.defenderHpAfter.coerceAtLeast(0)
        val defeated = enemyHpAfter <= 0
        val counter = if (defeated) {
            null
        } else {
            resolver.resolveAttack(
                enemyCombatant.copy(hp = enemyHpAfter),
                player,
            )
        }
        val playerHpAfter = counter?.defenderHpAfter ?: character.hp

        val description = buildString {
            append(attack.description)
            if (counter != null) {
                append('\n').append(counter.description)
                append("\n残りHP: ").append(character.name).append(' ').append(playerHpAfter)
                append(" / ").append(combat.enemyName).append(' ').append(enemyHpAfter)
            } else {
                append('\n').append(combat.enemyName).append(" を倒した！")
            }
        }

        return CombatRoundOutcome(
            playerAttack = attack,
            enemyAttack = counter,
            playerHpAfter = playerHpAfter,
            enemyHpAfter = enemyHpAfter,
            enemyDefeated = defeated,
            description = description,
        )
    }

    companion object {
        /** Weapon power and armour defence come from equipped items; skills are free-form. */
        fun playerCombatStats(character: Character): CombatStats {
            val equipped = character.inventory.filter { it.id in character.equipment }
            return CombatStats(
                attackPower = 1 + (equipped.maxOfOrNull { it.power } ?: 0),
                defense = equipped.sumOf { it.defense },
                attackSkillLevel = 0,
                defenseSkillLevel = 0,
                hp = character.hp,
            ).normalized()
        }
    }
}
