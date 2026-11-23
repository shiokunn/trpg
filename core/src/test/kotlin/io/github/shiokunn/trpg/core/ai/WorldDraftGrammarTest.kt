package io.github.shiokunn.trpg.core.ai

import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.elementNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldDraftGrammarTest {

    private fun names(descriptor: SerialDescriptor): List<String> =
        descriptor.elementNames.toList()

    @Test
    fun `root keys match WorldDraft in declaration order`() {
        assertEquals(names(WorldDraft.serializer().descriptor), WorldDraftGrammar.KEYS)
    }

    @Test
    fun `nested keys match their drafts`() {
        assertEquals(names(ScenarioDraft.serializer().descriptor), WorldDraftGrammar.SCENARIO_KEYS)
        assertEquals(names(LocationDraft.serializer().descriptor), WorldDraftGrammar.LOCATION_KEYS)
        assertEquals(names(NpcDraft.serializer().descriptor), WorldDraftGrammar.NPC_KEYS)
        assertEquals(names(QuestDraft.serializer().descriptor), WorldDraftGrammar.QUEST_KEYS)
        assertEquals(names(OpeningDraft.serializer().descriptor), WorldDraftGrammar.OPENING_KEYS)
    }

    @Test
    fun `every key appears as a quoted JSON member in the grammar`() {
        val keys = WorldDraftGrammar.KEYS +
            WorldDraftGrammar.SCENARIO_KEYS +
            WorldDraftGrammar.LOCATION_KEYS +
            WorldDraftGrammar.NPC_KEYS +
            WorldDraftGrammar.QUEST_KEYS +
            WorldDraftGrammar.OPENING_KEYS +
            WorldDraftGrammar.COMBAT_KEYS
        keys.distinct().forEach { key ->
            assertTrue(
                "grammar is missing key \"$key\"",
                WorldDraftGrammar.GBNF.contains("\\\"$key\\\":"),
            )
        }
    }

    @Test
    fun `all referenced rules are defined`() {
        val rules = parseRules()
        val defined = rules.keys
        assertTrue(defined.contains("root"))
        assertEquals(1, rules.getValue("root").size)

        val referenced = rules.values.flatten()
            .flatMap { referencedNames(it) }
            .toSet()
        val missing = referenced - defined
        assertTrue("undefined rules: $missing", missing.isEmpty())
    }

    @Test
    fun `every rule body is on a single line`() {
        // Rebuild the rule bodies exactly as written: a line that does not start a
        // new `name ::= ...` rule is a continuation and llama.cpp rejects it.
        val continuation = WorldDraftGrammar.GBNF
            .lines()
            .dropWhile { !it.trimStart().startsWith("root ") }
            .filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
            .filterNot { RULE_START.containsMatchIn(it) }
        assertTrue(
            "multi-line rule bodies are rejected by llama.cpp's GBNF parser: $continuation",
            continuation.isEmpty(),
        )
    }

    @Test
    fun `grammar does not grant unknown top-level keys`() {
        // The strict decoder rejects unknown keys, so the grammar must not emit them.
        assertFalse(WorldDraftGrammar.GBNF.contains("\\\"unexpected\\\":"))
        assertTrue(WorldDraftGrammar.GBNF.contains("root ::= object"))
    }

    @Test
    fun `grammar accepts minified and indented JSON shapes`() {
        // The arrays are optional but present arrays must stay arrays, and strings
        // may contain escaped quotes/backslashes.
        val stringRule = parseRules().getValue("string").single()
        assertTrue(stringRule.contains("[^\"\\\\"))
        assertTrue(stringRule.contains("\"u\""))
        val arrayRule = parseRules().getValue("array").single()
        assertTrue(arrayRule.startsWith("\"[\" ws"))
        assertTrue(arrayRule.contains("(string (\",\" ws string)*)?"))
    }

    private fun parseRules(): Map<String, List<String>> =
        WorldDraftGrammar.GBNF.lines()
            .mapNotNull { line ->
                val match = RULE_START.find(line) ?: return@mapNotNull null
                match.groupValues[1] to line.substring(match.range.last + 1).trim()
            }
            .groupBy({ it.first }, { it.second })

    /** Extracts rule names from a GBNF body, ignoring string literals and char classes. */
    private fun referencedNames(body: String): List<String> {
        val withoutLiterals = body
            .replace(STRING_LITERAL, " ")
            .replace(CHAR_CLASS, " ")
        return IDENTIFIER.findAll(withoutLiterals).map { it.value }.toList()
    }

    private companion object {
        val RULE_START = Regex("""^\s*([a-zA-Z_][a-zA-Z0-9_]*)\s*::=""")
        val IDENTIFIER = Regex("""[a-zA-Z_][a-zA-Z0-9_]*""")
        val STRING_LITERAL = Regex(""""(?:[^"\\]|\\.)*"""")
        val CHAR_CLASS = Regex("""\[(?:[^\]\\]|\\.)*\]""")
    }
}
