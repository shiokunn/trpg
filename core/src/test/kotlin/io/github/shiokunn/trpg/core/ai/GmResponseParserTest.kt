package io.github.shiokunn.trpg.core.ai

import io.github.shiokunn.trpg.core.domain.AttributeId
import io.github.shiokunn.trpg.core.domain.RollMode
import io.github.shiokunn.trpg.core.state.StateChangeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GmResponseParserTest {

    private val validJson = """
        {
          "narrative": "森の入口に立っている。湿った土の匂いがする。",
          "sceneTitle": "森の入口",
          "choices": [
            {"id": "c1", "text": "森へ進む", "check": {"attribute": "PER", "skillId": null, "difficulty": 8, "rollMode": "NORMAL"}},
            {"id": "c2", "text": "引き返す"},
            {"id": "c3", "text": "耳を澄ます", "check": {"attribute": "PER", "difficulty": 6}}
          ],
          "stateChanges": [
            {"type": "HP_DELTA", "amount": -1, "reason": "棘に引っかかれた"}
          ],
          "memories": [
            {"category": "EVENT", "content": "森に足を踏み入れた", "importance": 4, "isSecret": false, "isConfirmed": true, "relatedIds": []}
          ],
          "npcMemories": [],
          "eventSummary": "森の入口に到着した"
        }
    """.trimIndent()

    private fun success(raw: String): GmResponse {
        val result = GmResponseParser.parse(raw)
        assertTrue("expected success but was $result", result is AiParseResult.Success)
        return (result as AiParseResult.Success).value
    }

    private fun failure(raw: String): List<String> {
        val result = GmResponseParser.parse(raw)
        assertTrue("expected failure but was $result", result is AiParseResult.Failure)
        return (result as AiParseResult.Failure).errors.map { it.message }
    }

    @Test
    fun parsesFullTurn() {
        val response = success(validJson)
        assertEquals("森の入口", response.sceneTitle)
        assertEquals(3, response.choices.size)
        assertEquals(AttributeId.PER, response.choices[0].check?.attribute)
        assertEquals(RollMode.NORMAL, response.choices[0].check?.rollMode)
        assertEquals(6, response.choices[2].check?.difficulty)
        assertEquals(StateChangeType.HP_DELTA, response.stateChanges.single().type)
        assertEquals(4, response.memories.single().importance)
    }

    @Test
    fun acceptsMarkdownFenceAndPreamble() {
        val raw = "以下が結果です。\n```json\n$validJson\n```\n"
        assertEquals("森の入口", success(raw).sceneTitle)
    }

    @Test
    fun rejectsMissingJson() {
        val errors = failure("申し訳ありませんが、生成できませんでした。")
        assertTrue(errors.any { it.contains("JSON") })
    }

    @Test
    fun rejectsUnknownTopLevelKey() {
        val raw = validJson.replaceFirst("{", "{\"unexpected\": true,")
        val errors = failure(raw)
        assertTrue(errors.any { it.contains("スキーマ") || it.contains("unexpected") })
    }

    @Test
    fun rejectsUnknownNestedKey() {
        val raw = validJson.replaceFirst("\"c2\", \"text\"", "\"c2\", \"danger\": 1, \"text\"")
        val errors = failure(raw)
        assertTrue(errors.isNotEmpty())
    }

    @Test
    fun rejectsBlankNarrative() {
        val raw = validJson.replaceFirst("森の入口に立っている。湿った土の匂いがする。", " ")
        val errors = failure(raw)
        assertTrue(errors.any { it.contains("narrative") })
    }

    @Test
    fun rejectsTooFewChoices() {
        val raw = """
            {
              "narrative": "静かな部屋。",
              "choices": [
                {"id": "c1", "text": "待つ"},
                {"id": "c2", "text": "出る"}
              ]
            }
        """.trimIndent()
        val errors = failure(raw)
        assertTrue(errors.any { it.contains("choices") })
    }

    @Test
    fun rejectsDuplicateChoiceIds() {
        val raw = validJson.replaceFirst("\"c3\"", "\"c1\"")
        val errors = failure(raw)
        assertTrue(errors.any { it.contains("重複") })
    }

    @Test
    fun rejectsInvalidDifficulty() {
        val raw = validJson.replaceFirst("\"difficulty\": 8", "\"difficulty\": 9")
        val errors = failure(raw)
        assertTrue(errors.any { it.contains("difficulty") })
    }

    @Test
    fun rejectsUnknownAttribute() {
        val raw = validJson.replaceFirst("\"attribute\": \"PER\"", "\"attribute\": \"LUCK\"")
        val errors = failure(raw)
        assertTrue(errors.isNotEmpty())
    }

    @Test
    fun rejectsMemoryImportanceOutOfRange() {
        val raw = validJson.replaceFirst("\"importance\": 4", "\"importance\": 9")
        val errors = failure(raw)
        assertTrue(errors.any { it.contains("importance") })
    }

    @Test
    fun rejectsBlankNpcMemoryId() {
        val raw = validJson.replaceFirst(
            "\"npcMemories\": []",
            "\"npcMemories\": [{\"npcId\": \"\", \"content\": \"覚えている\", \"importance\": 3}]",
        )
        val errors = failure(raw)
        assertTrue(errors.any { it.contains("npcId") })
    }

    @Test
    fun repairInstructionListsErrors() {
        val text = GmResponseParser.repairInstruction(listOf("choicesが足りません", "difficultyが不正です"))
        assertTrue(text.contains("choicesが足りません"))
        assertTrue(text.contains("JSON"))
    }
}
