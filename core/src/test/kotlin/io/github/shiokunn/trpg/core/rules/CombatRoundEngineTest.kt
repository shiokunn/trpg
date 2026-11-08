package io.github.shiokunn.trpg.core.rules

import io.github.shiokunn.trpg.core.domain.Attributes
import io.github.shiokunn.trpg.core.domain.Character
import io.github.shiokunn.trpg.core.domain.CombatState
import io.github.shiokunn.trpg.core.domain.CombatStats
import io.github.shiokunn.trpg.core.domain.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CombatRoundEngineTest {

    @Test
    fun playerAttackAndCounterattackApplyDeterministicDamage() {
        val engine = CombatRoundEngine(ScriptedDice(6, 1, 5, 1))
        val outcome = engine.resolve(character(), combat(hp = 6))

        assertEquals(1, outcome.enemyHpAfter)
        assertEquals(8, outcome.playerHpAfter)
        assertFalse(outcome.enemyDefeated)
        assertNotNull(outcome.enemyAttack)
        assertTrue(outcome.description.contains("残りHP"))
    }

    @Test
    fun defeatedEnemyDoesNotCounterattack() {
        val engine = CombatRoundEngine(ScriptedDice(6, 1))
        val outcome = engine.resolve(character(), combat(hp = 3))

        assertEquals(0, outcome.enemyHpAfter)
        assertEquals(10, outcome.playerHpAfter)
        assertTrue(outcome.enemyDefeated)
        assertNull(outcome.enemyAttack)
        assertTrue(outcome.description.contains("を倒した"))
    }

    @Test
    fun missedAttackDealsNoDamageButEnemyStillActs() {
        val engine = CombatRoundEngine(ScriptedDice(1, 6, 2, 6))
        val outcome = engine.resolve(character(), combat(hp = 5))

        assertEquals(5, outcome.enemyHpAfter)
        assertFalse(outcome.playerAttack.hit)
        assertEquals(10, outcome.playerHpAfter)
        assertNotNull(outcome.enemyAttack)
    }

    @Test
    fun dexterityAttackUsesDexterity() {
        val engine = CombatRoundEngine(ScriptedDice(6, 1, 1, 6))
        val outcome = engine.resolve(character(), combat(hp = 5), useDexterity = true)

        assertEquals(4, outcome.playerAttack.attackTotal - 6)
    }

    @Test
    fun playerCombatStatsUseOnlyEquippedItemsAndClampValues() {
        val character = character().copy(
            inventory = listOf(
                Item(id = "sword", name = "剣", power = 3, equipped = true),
                Item(id = "armor", name = "鎧", defense = 4, equipped = true),
                Item(id = "spare", name = "予備の剣", power = 9, equipped = false),
                Item(id = "shield", name = "大盾", defense = 30, equipped = true),
            ),
            equipment = listOf("sword", "armor", "shield"),
        )
        val stats = CombatRoundEngine.playerCombatStats(character)

        assertEquals(4, stats.attackPower)
        assertEquals(20, stats.defense)
        assertEquals(0, stats.attackSkillLevel)
    }

    private fun character() = Character(
        id = "c1",
        name = "ミナ",
        attributes = Attributes(str = 5, dex = 4, int = 3, wil = 3, per = 2, cha = 1),
        hp = 10,
        maxHp = 10,
        inventory = listOf(
            Item(id = "sword", name = "剣", power = 3, defense = 0, equipped = true),
        ),
        equipment = listOf("sword"),
    )

    private fun combat(hp: Int) = CombatState(
        enemyNpcId = "n1",
        enemyName = "沼の怪物",
        enemyHp = hp,
        enemyMaxHp = 10,
        enemyAttackPower = 2,
        enemyDefense = 1,
        enemyAttackSkill = 0,
        enemyDefenseSkill = 0,
        round = 1,
    )

    private class ScriptedDice(private vararg val values: Int) : DiceSource {
        private var index = 0

        override fun rollD6(): Int {
            val value = values[index % values.size]
            index++
            return value
        }
    }
}
