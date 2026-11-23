package io.github.shiokunn.trpg.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GmRepairTest {

    private val validJson = """
        {
          "narrative": "森の入口に立っている。湿った土の匂いがする。",
          "sceneTitle": "森の入口",
          "choices": [
            {"id": "c1", "text": "森へ進む", "check": {"attribute": "PER", "skillId": null, "difficulty": 8, "rollMode": "NORMAL"}},
            {"id": "c2", "text": "引き返す"},
            {"id": "c3", "text": "耳を澄ます", "check": {"attribute": "PER", "difficulty": 6}}
          ],
          "stateChanges": [],
          "memories": [],
          "npcMemories": [],
          "eventSummary": "森の入口に到着した"
        }
    """.trimIndent()

    private fun parseSuccess(raw: String): GmResponse {
        val result = GmResponseParser.parse(raw)
        assertTrue("expected success but was $result", result is AiParseResult.Success)
        return (result as AiParseResult.Success).value
    }

    @Test
    fun validJsonNeedsNoRepair() {
        assertNull(GmRepair.repairTopLevelUnknownKeys(validJson))
    }

    @Test
    fun removesTopLevelUnknownId() {
        val broken = validJson.replaceFirst("{", "{\"id\": \"extra\",")
        val repaired = GmRepair.repairTopLevelUnknownKeys(broken)

        assertNotNull(repaired)
        val response = parseSuccess(repaired!!)
        assertEquals("森の入口に立っている。湿った土の匂いがする。", response.narrative)
        assertEquals("森の入口", response.sceneTitle)
        assertEquals(3, response.choices.size)
    }

    @Test
    fun doesNotRemoveNestedUnknownKey() {
        val broken = validJson.replace(
            "\"id\": \"c1\", \"text\": \"森へ進む\"",
            "\"id\": \"c1\", \"nestedId\": \"bad\", \"text\": \"森へ進む\"",
        )

        assertNull(GmRepair.repairTopLevelUnknownKeys(broken))
    }

    @Test
    fun removesMultipleTopLevelUnknownKeys() {
        val broken = validJson.replaceFirst("{", "{\"id\": \"a\", \"extra\": 1,")
        val repaired = GmRepair.repairTopLevelUnknownKeys(broken)

        assertNotNull(repaired)
        assertEquals("森の入口", parseSuccess(repaired!!).sceneTitle)
    }

    @Test
    fun preservesAllowedFieldValues() {
        val broken = validJson.replaceFirst("{", "{\"id\": \"drop-me\",")
        val repaired = GmRepair.repairTopLevelUnknownKeys(broken)!!

        val before = parseSuccess(validJson)
        val after = parseSuccess(repaired)
        assertEquals(before.narrative, after.narrative)
        assertEquals(before.choices.map { it.id }, after.choices.map { it.id })
        assertEquals(before.choices.map { it.text }, after.choices.map { it.text })
        assertEquals(before.stateChanges, after.stateChanges)
        assertEquals(before.eventSummary, after.eventSummary)
    }

    @Test
    fun syntaxErrorIsNotRepairedLocally() {
        assertNull(GmRepair.repairTopLevelUnknownKeys("{ \"narrative\": \"a\", "))
    }

    @Test
    fun truncatedJsonIsNotRepairedLocally() {
        assertNull(GmRepair.repairTopLevelUnknownKeys("{\"narrative\":\"a\",\"id\":\"x\""))
    }

    @Test
    fun missingRequiredContentIsNotRepairedLocally() {
        val broken = validJson.replace("\"narrative\": \"森の入口に立っている。湿った土の匂いがする。\",", "\"narrative\": \"\",")
        assertNull(GmRepair.repairTopLevelUnknownKeys(broken))
    }

    @Test
    fun typeMismatchIsNotRepairedLocally() {
        val broken = validJson.replace("\"choices\": [", "\"choices\": \"not an array\", \"unused\": [")
        assertNull(GmRepair.repairTopLevelUnknownKeys(broken))
    }

    @Test
    fun gameRuleViolationIsNotRepairedLocally() {
        val broken = validJson.replace("\"difficulty\": 8", "\"difficulty\": 5")
        assertNull(GmRepair.repairTopLevelUnknownKeys(broken))
    }

    @Test
    fun analyzeClassifiesSchemaErrorAndExtractsKey() {
        val issues = GmRepair.analyze(
            listOf(
                AiError(
                    AiErrorKind.JSON_SCHEMA,
                    "Encountered an unknown key 'id' at element: \$",
                ),
            ),
        )

        val issue = issues.single()
        assertEquals(GmErrorCategory.JSON_SCHEMA, issue.category)
        assertEquals("id", issue.key)
        assertEquals("$", issue.path)
    }

    @Test
    fun analyzeClassifiesContractAsGameRule() {
        val issues = GmRepair.analyze(
            listOf(AiError(AiErrorKind.CONTRACT, "choices[0].check.difficultyは4/6/8/10/12/14のいずれか")),
        )
        assertEquals(GmErrorCategory.GAME_RULE, issues.single().category)
    }

    @Test
    fun structuralInstructionListsAllowedKeysAndForbidsRewritingNarrative() {
        val text = GmRepair.structuralInstruction(
            GmRepair.analyze(listOf(AiError(AiErrorKind.JSON_SCHEMA, "unknown key 'id'"))),
        )

        assertTrue(text.contains(GmRepair.allowedTopLevelKeys()))
        assertTrue(text.contains("id"))
        assertTrue(text.contains("narrative"))
        assertTrue(text.contains("変更しない"))
    }

    @Test
    fun contentInstructionProtectsResolvedChecksAndState() {
        val text = GmRepair.contentInstruction(
            GmRepair.analyze(listOf(AiError(AiErrorKind.CONTRACT, "stateChanges不正"))),
        )

        assertTrue(text.contains("出目"))
        assertTrue(text.contains("ゲーム状態"))
        assertTrue(text.contains("選択肢"))
        assertFalse(text.contains("id\""))
    }

    @Test
    fun analyzeClassifiesTruncatedJsonAsTruncated() {
        val issues = GmRepair.analyze(
            listOf(AiError(AiErrorKind.JSON_TRUNCATED, "JSONが途中で終わっています")),
        )

        assertEquals(GmErrorCategory.TRUNCATED, issues.single().category)
    }

    @Test
    fun analyzeGameRuleClassifiesSemanticErrorsAndExtractsPath() {
        val issues = GmRepair.analyzeGameRule(
            listOf("choices[0].check.skillId「x」は所持スキルに存在しません"),
        )

        val issue = issues.single()
        assertEquals(GmErrorCategory.GAME_RULE, issue.category)
        assertEquals("choices[0].check.skillId", issue.path)
    }

    @Test
    fun truncationIssueKeepsItsMessage() {
        val issue = GmRepair.analyzeTruncation("応答がトークン上限で途中に切れました。")

        assertEquals(GmErrorCategory.TRUNCATED, issue.category)
        assertEquals("応答がトークン上限で途中に切れました。", issue.message)
    }

    @Test
    fun structuralInstructionFixesStructureFromTheSameRawJson() {
        val text = GmRepair.structuralInstruction(
            GmRepair.analyze(listOf(AiError(AiErrorKind.JSON_SCHEMA, "unknown key 'id'"))),
        )

        assertTrue(text.contains("構造"))
        assertTrue(text.contains("値そのものは変更しない"))
        assertTrue(text.contains("前回の応答（JSON）"))
        assertTrue(text.contains("完全なJSON"))
        assertTrue(text.contains("narrative"))
    }

    @Test
    fun contentInstructionAllowsFixingTheReportedField() {
        val text = GmRepair.contentInstruction(
            GmRepair.analyzeGameRule(
                listOf("choices[0].check.difficultyは4/6/8/10/12/14のいずれか"),
            ),
        )

        assertTrue(text.contains("違反している値だけ"))
        assertTrue(text.contains("difficulty"))
        assertTrue(text.contains("選択肢の個数・id・text"))
        assertTrue(text.contains("stateChanges"))
        assertTrue(text.contains("narrative 自体が違反"))
        assertTrue(text.contains("前回の応答（JSON）"))
        assertTrue(text.contains("完全なJSON"))
    }
}
