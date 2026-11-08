package io.github.shiokunn.trpg.core.rules

import io.github.shiokunn.trpg.core.domain.Attributes
import io.github.shiokunn.trpg.core.domain.Skill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterRulesTest {

    @Test
    fun totalEighteenIsValid() {
        val attributes = Attributes(5, 4, 3, 2, 2, 2)
        assertEquals(18, attributes.total())
        assertTrue(CharacterRules.validateAttributes(attributes).isEmpty())
    }

    @Test
    fun wrongTotalIsRejected() {
        val errors = CharacterRules.validateAttributes(Attributes(3, 3, 3, 3, 3, 2))
        assertFalse(errors.isEmpty())
    }

    @Test
    fun outOfRangeAttributeIsRejected() {
        val errors = CharacterRules.validateAttributes(Attributes(6, 3, 3, 3, 3, 0))
        assertTrue(errors.any { it.contains("1〜5") })
    }

    @Test
    fun skillCreationRequiresSixPointsAndMaxTwo() {
        val ok = listOf(
            Skill("s1", "調査", 2),
            Skill("s2", "交渉", 2),
            Skill("s3", "隠密", 2),
        )
        assertTrue(CharacterRules.validateSkillsForCreation(ok).isEmpty())

        val tooHigh = listOf(Skill("s1", "調査", 3), Skill("s2", "交渉", 3))
        assertFalse(CharacterRules.validateSkillsForCreation(tooHigh).isEmpty())

        val wrongTotal = listOf(Skill("s1", "調査", 2), Skill("s2", "交渉", 2))
        assertFalse(CharacterRules.validateSkillsForCreation(wrongTotal).isEmpty())
    }

    @Test
    fun duplicateSkillNamesAreRejected() {
        val duplicates = listOf(Skill("s1", "調査", 2), Skill("s2", "調査", 2), Skill("s3", "隠密", 2))
        assertTrue(CharacterRules.validateSkillsForCreation(duplicates).any { it.contains("同じ名前") })
    }

    @Test
    fun levelProgressionUsesTenXpSteps() {
        assertEquals(1, CharacterRules.levelForXp(0))
        assertEquals(1, CharacterRules.levelForXp(9))
        assertEquals(2, CharacterRules.levelForXp(10))
        assertEquals(3, CharacterRules.levelForXp(25))
        assertEquals(5, CharacterRules.xpIntoLevel(15))
        assertEquals(20, CharacterRules.nextLevelXp(15))
    }

    @Test
    fun skillCannotExceedThreeOnGrowth() {
        assertTrue(CharacterRules.canRaiseSkill(Skill("s", "調査", 2)))
        assertFalse(CharacterRules.canRaiseSkill(Skill("s", "調査", 3)))
    }
}
