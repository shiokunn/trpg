package io.github.shiokunn.trpg.core.rules

import io.github.shiokunn.trpg.core.domain.AttributeId
import io.github.shiokunn.trpg.core.domain.CheckOutcome
import io.github.shiokunn.trpg.core.domain.DiceResult
import io.github.shiokunn.trpg.core.domain.RollMode
import kotlin.random.Random

/** Source of D6 rolls. Injected so tests are deterministic. */
fun interface DiceSource {
    fun rollD6(): Int
}

class RandomDiceSource(private val random: Random = Random.Default) : DiceSource {
    override fun rollD6(): Int = random.nextInt(1, 7)
}

data class CheckRequest(
    val attributeId: AttributeId,
    val attributeValue: Int,
    val skillId: String? = null,
    val skillName: String = "",
    val skillLevel: Int = 0,
    val difficulty: Int,
    val rollMode: RollMode = RollMode.NORMAL,
    val modifiers: Int = 0,
    val reason: String = "",
    val turn: Int = 0,
)

/**
 * The only component allowed to roll dice. The AI never rolls; it only receives
 * the resolved [DiceResult].
 */
class DiceEngine(private val dice: DiceSource = RandomDiceSource()) {

    fun roll(request: CheckRequest): DiceResult {
        val rolls = when (request.rollMode) {
            RollMode.NORMAL -> listOf(dice.rollD6())
            RollMode.ADVANTAGE, RollMode.DISADVANTAGE -> listOf(dice.rollD6(), dice.rollD6())
        }.map { it.coerceIn(1, 6) }

        val used = when (request.rollMode) {
            RollMode.ADVANTAGE -> rolls.max()
            RollMode.DISADVANTAGE -> rolls.min()
            RollMode.NORMAL -> rolls.first()
        }
        val total = used + request.attributeValue + request.skillLevel + request.modifiers
        return DiceResult(
            attributeId = request.attributeId,
            skillId = request.skillId,
            skillName = request.skillName,
            dice = rolls,
            rollMode = request.rollMode,
            attributeValue = request.attributeValue,
            skillLevel = request.skillLevel,
            modifiers = request.modifiers,
            total = total,
            difficulty = request.difficulty,
            outcome = classify(total, request.difficulty),
            reason = request.reason,
            turn = request.turn,
        )
    }

    companion object {
        fun classify(total: Int, difficulty: Int): CheckOutcome = when {
            total >= difficulty + 3 -> CheckOutcome.CRITICAL_SUCCESS
            total >= difficulty -> CheckOutcome.SUCCESS
            total >= difficulty - 2 -> CheckOutcome.PARTIAL_SUCCESS
            else -> CheckOutcome.FAILURE
        }
    }
}
