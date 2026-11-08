package io.github.shiokunn.trpg.core.rules

import io.github.shiokunn.trpg.core.domain.AttributeId
import io.github.shiokunn.trpg.core.domain.CheckOutcome
import io.github.shiokunn.trpg.core.domain.RollMode
import org.junit.Assert.assertEquals
import org.junit.Test

private class SequenceDice(vararg rolls: Int) : DiceSource {
    private val values = rolls.toMutableList()
    override fun rollD6(): Int = values.removeFirst()
}

class DiceEngineTest {

    @Test
    fun normalRollTotalsAttributeAndSkill() {
        val result = DiceEngine(SequenceDice(4)).roll(
            CheckRequest(
                attributeId = AttributeId.STR,
                attributeValue = 3,
                skillLevel = 2,
                skillName = "運動",
                difficulty = 8,
            ),
        )
        assertEquals(listOf(4), result.dice)
        assertEquals(9, result.total)
        assertEquals(CheckOutcome.SUCCESS, result.outcome)
    }

    @Test
    fun classifiesCriticalSuccessAtDifficultyPlusThree() {
        assertEquals(CheckOutcome.CRITICAL_SUCCESS, DiceEngine.classify(11, 8))
        assertEquals(CheckOutcome.SUCCESS, DiceEngine.classify(10, 8))
    }

    @Test
    fun classifiesPartialAndFailureAtBoundaries() {
        assertEquals(CheckOutcome.SUCCESS, DiceEngine.classify(8, 8))
        assertEquals(CheckOutcome.PARTIAL_SUCCESS, DiceEngine.classify(7, 8))
        assertEquals(CheckOutcome.PARTIAL_SUCCESS, DiceEngine.classify(6, 8))
        assertEquals(CheckOutcome.FAILURE, DiceEngine.classify(5, 8))
    }

    @Test
    fun advantageUsesHigherDie() {
        val result = DiceEngine(SequenceDice(2, 5)).roll(
            CheckRequest(
                attributeId = AttributeId.DEX,
                attributeValue = 2,
                difficulty = 8,
                rollMode = RollMode.ADVANTAGE,
            ),
        )
        assertEquals(listOf(2, 5), result.dice)
        assertEquals(5, result.usedDie)
        assertEquals(7, result.total)
    }

    @Test
    fun disadvantageUsesLowerDie() {
        val result = DiceEngine(SequenceDice(6, 1)).roll(
            CheckRequest(
                attributeId = AttributeId.PER,
                attributeValue = 3,
                difficulty = 8,
                rollMode = RollMode.DISADVANTAGE,
            ),
        )
        assertEquals(1, result.usedDie)
        assertEquals(4, result.total)
        assertEquals(CheckOutcome.FAILURE, result.outcome)
    }

    @Test
    fun modifiersAreApplied() {
        val result = DiceEngine(SequenceDice(3)).roll(
            CheckRequest(
                attributeId = AttributeId.INT,
                attributeValue = 4,
                skillLevel = 3,
                modifiers = -2,
                difficulty = 8,
            ),
        )
        assertEquals(8, result.total)
        assertEquals(CheckOutcome.SUCCESS, result.outcome)
    }

    @Test
    fun diceAreClampedToD6Range() {
        val result = DiceEngine(SequenceDice(0, 9)).roll(
            CheckRequest(
                attributeId = AttributeId.WIL,
                attributeValue = 1,
                difficulty = 8,
                rollMode = RollMode.ADVANTAGE,
            ),
        )
        assertEquals(listOf(1, 6), result.dice)
        assertEquals(6, result.usedDie)
    }
}
