package io.github.shiokunn.trpg.core.rules

import io.github.shiokunn.trpg.core.domain.CombatStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FixedDice(vararg rolls: Int) : DiceSource {
    private val values = rolls.toMutableList()
    override fun rollD6(): Int = values.removeFirst()
}

class CombatResolverTest {

    private fun combatant(
        name: String,
        hp: Int = 10,
        str: Int = 3,
        dex: Int = 3,
        attackPower: Int = 2,
        defense: Int = 1,
        attackSkill: Int = 1,
        defenseSkill: Int = 1,
    ) = Combatant(
        id = name,
        name = name,
        hp = hp,
        maxHp = 10,
        str = str,
        dex = dex,
        stats = CombatStats(attackPower, defense, attackSkill, defenseSkill),
    )

    @Test
    fun damageFormulaUsesDifferenceAndDefense() {
        // 5 + STR3 + skill1 = 9 vs 2 + DEX3 + skill1 = 6 -> damage 2 + floor(2/3) - 1 = 1
        val result = CombatResolver(FixedDice(5, 2)).resolveAttack(
            attacker = combatant("A"),
            defender = combatant("B", hp = 10, defense = 1),
        )
        assertTrue(result.hit)
        assertEquals(1, result.damage)
        assertEquals(9, result.defenderHpAfter)
    }

    @Test
    fun damageIsAtLeastOneEvenAgainstHighDefense() {
        assertEquals(1, CombatResolver.calculateDamage(attackPower = 1, attackTotal = 10, defenseTotal = 9, defense = 5))
    }

    @Test
    fun missDealsNoDamage() {
        val result = CombatResolver(FixedDice(1, 6)).resolveAttack(
            attacker = combatant("A"),
            defender = combatant("B"),
        )
        assertFalse(result.hit)
        assertEquals(0, result.damage)
        assertEquals(10, result.defenderHpAfter)
    }

    @Test
    fun hpNeverGoesBelowZero() {
        val result = CombatResolver(FixedDice(6, 1)).resolveAttack(
            attacker = combatant("A", attackPower = 10),
            defender = combatant("B", hp = 2, defense = 0),
        )
        assertTrue(result.hit)
        assertEquals(0, result.defenderHpAfter)
    }

    @Test
    fun dexterityCanBeUsedForAttack() {
        val result = CombatResolver(FixedDice(6, 1)).resolveAttack(
            attacker = combatant("A", str = 1, dex = 5),
            defender = combatant("B"),
            useDexterity = true,
        )
        assertEquals(12, result.attackTotal)
    }

    @Test
    fun npcCombatantRequiresCombatStats() {
        val npc = io.github.shiokunn.trpg.core.domain.Npc(id = "n1", name = "盗賊")
        assertEquals(null, CombatResolver.npcCombatant(npc))
    }
}
