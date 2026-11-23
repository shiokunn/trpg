package io.github.shiokunn.trpg.game

import io.github.shiokunn.trpg.ai.engine.GenerationFinishReason
import io.github.shiokunn.trpg.ai.engine.GenerationOutcome
import io.github.shiokunn.trpg.core.ai.AiError
import io.github.shiokunn.trpg.core.ai.AiErrorKind
import io.github.shiokunn.trpg.core.ai.GmErrorCategory
import io.github.shiokunn.trpg.core.ai.GmRepair
import io.github.shiokunn.trpg.core.domain.Character
import io.github.shiokunn.trpg.core.domain.GameSession
import io.github.shiokunn.trpg.core.domain.World
import io.github.shiokunn.trpg.core.state.GameSnapshot
import io.github.shiokunn.trpg.data.repository.SessionBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure validation and repair-prompt helpers need no engine or repositories. */
class GmTurnServiceTest {

    private val character = Character(id = "char-1", name = "テスト")
    private val bundle = SessionBundle(
        session = GameSession(
            id = "session-1",
            title = "テストセッション",
            worldId = "world-1",
            characterId = character.id,
            summary = "あらすじ",
        ),
        world = World(id = "world-1", name = "テスト世界", genre = "ファンタジー"),
        character = character,
        npcs = emptyList(),
        locations = emptyList(),
        quests = emptyList(),
        flags = emptyMap(),
    )
    private val snapshot = GameSnapshot(character = character)

    /** Parses cleanly, but the skillId does not exist in the live snapshot. */
    private val semanticFailure = """
        {
          "narrative": "森の入口に立っている。",
          "choices": [
            {"id": "c1", "text": "進む", "check": {"attribute": "PER", "skillId": "missing", "difficulty": 8}},
            {"id": "c2", "text": "待つ"},
            {"id": "c3", "text": "戻る"}
          ]
        }
    """.trimIndent()

    @Test
    fun semanticRejectionIsClassifiedAsGameRuleAndKeepsRawJson() {
        val rejected = evaluateTurn(
            raw = semanticFailure,
            snapshot = snapshot,
            bundle = bundle,
            allowLocalRepair = true,
        ).attempt as Attempt.Rejected

        assertTrue(rejected.issues.any { it.category == GmErrorCategory.GAME_RULE })
        assertEquals(semanticFailure, rejected.raw)
        assertTrue(rejected.errors.any { it.contains("skillId") })
    }

    @Test
    fun truncationIsRejectedBeforeParsingAndKeepsRawJson() {
        val outcome = GenerationOutcome.Success(
            text = "{\"narrative\":\"途中で切れた",
            finishReason = GenerationFinishReason.LENGTH,
            limitReached = true,
            generatedTokens = 50,
            budget = 50,
        )
        val rejected = evaluateTurn(
            raw = outcome.text,
            snapshot = snapshot,
            bundle = bundle,
            allowLocalRepair = true,
            truncated = true,
            truncationDetail = GmTurnRepair.truncationMessage(outcome),
        ).attempt as Attempt.Rejected

        assertEquals(GmErrorCategory.TRUNCATED, rejected.issues.single().category)
        assertEquals(outcome.text, rejected.raw)
        assertTrue(rejected.errors.single().contains("途中に切れました"))
    }

    @Test
    fun contentRepairReceivesTheRawJson() {
        val raw = semanticFailure.replace("\"difficulty\": 8", "\"difficulty\": 9")
        val rejected = Attempt.Rejected(
            errors = listOf("choices[0].check.difficultyが不正です"),
            issues = GmRepair.analyzeGameRule(listOf("choices[0].check.difficultyが不正です")),
            raw = raw,
        )

        val userMessage = buildRepairMessages(
            mode = RepairMode.CONTENT,
            rejected = rejected,
            bundle = bundle,
            recentChat = emptyList(),
            snapshot = snapshot,
        ).last().content

        assertTrue(userMessage.contains("前回の応答（JSON）"))
        assertTrue(userMessage.contains(raw))
        assertTrue(userMessage.contains("現在のゲーム状態"))
        assertTrue(userMessage.contains("完全なJSON"))
    }

    @Test
    fun structureRepairReceivesTheRawJsonToo() {
        val raw = "{\"narrative\":\"途中\", \"id\":\"bad\""
        val rejected = Attempt.Rejected(
            errors = listOf("JSONが途中で終わっています"),
            issues = GmRepair.analyze(
                listOf(AiError(AiErrorKind.JSON_TRUNCATED, "JSONが途中で終わっています")),
            ),
            raw = raw,
        )

        val userMessage = buildRepairMessages(
            mode = RepairMode.STRUCTURE,
            rejected = rejected,
            bundle = bundle,
            recentChat = emptyList(),
            snapshot = snapshot,
        ).last().content

        assertTrue(userMessage.contains("前回の応答（JSON）"))
        assertTrue(userMessage.contains(raw))
        assertTrue(userMessage.contains("構造"))
    }
}
