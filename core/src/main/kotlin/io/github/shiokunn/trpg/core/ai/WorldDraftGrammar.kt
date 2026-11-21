package io.github.shiokunn.trpg.core.ai

/**
 * GBNF grammar for the [WorldDraft] JSON contract.
 *
 * The grammar constrains the *syntax* of a world draft while the model generates
 * it: every key appears exactly once, in the same order as the data class, and
 * every value has the type the decoder expects. It deliberately does not encode
 * the content rules (non-blank names, list lengths, combat ranges): those stay
 * in [WorldDraftParser.validate] so the two concerns cannot drift into each other.
 *
 * The syntax is llama.cpp's GBNF dialect. Two constraints of its parser matter
 * when editing this file:
 *
 *  - every rule body must stay on a single line. A newline is only allowed
 *    inside a rule when the rule is nested in parentheses, so a wrapped rule body
 *    is rejected with "expecting name at ...".
 *  - a JSON key is written as `"\"name\":"`, i.e. the embedded quote is escaped
 *    once. `"\"name\""` without the trailing colon does not parse.
 *
 * The grammar is checked structurally in
 * `core/src/test/kotlin/io/github/shiokunn/trpg/core/ai/WorldDraftGrammarTest.kt`
 * (keys, rule references, single-line bodies). Whether llama.cpp itself accepts it
 * is only exercised by a real generation on a device.
 */
object WorldDraftGrammar {

    /**
     * The grammar text handed to llama.cpp. `root` is the entry rule llama.cpp
     * starts from.
     */
    const val GBNF: String = """
        # WorldDraft JSON grammar (llama.cpp GBNF).
        # Mirrors io.github.shiokunn.trpg.core.ai.WorldDraft.
        # Every key appears exactly once, in data-class order, so the grammar cannot
        # produce a document the strict decoder would reject.
        # `combat` is nullable: either null or an object with the five numeric fields.
        #
        # NOTE: every rule body stays on one line. llama.cpp's GBNF parser only allows
        # newlines inside a rule when the rule is nested in parentheses, so multi-line
        # rule bodies would be rejected.

        root ::= object

        object ::= "{" ws "\"name\":" ws string "," ws "\"genre\":" ws string "," ws "\"era\":" ws string "," ws "\"setting\":" ws string "," ws "\"atmosphere\":" ws string "," ws "\"history\":" ws string "," ws "\"geography\":" ws string "," ws "\"socialStructure\":" ws string "," ws "\"factions\":" ws array "," ws "\"techMagic\":" ws string "," ws "\"specialSettings\":" ws array "," ws "\"objective\":" ws string "," ws "\"problem\":" ws string "," ws "\"secrets\":" ws array "," ws "\"scenario\":" ws scenario "," ws "\"opening\":" ws opening "}" ws

        scenario ::= "{" ws "\"title\":" ws string "," ws "\"premise\":" ws string "," ws "\"length\":" ws string "," ws "\"difficulty\":" ws string "," ws "\"openingHook\":" ws string "}" ws

        opening ::= "{" ws "\"location\":" ws location "," ws "\"npcs\":" ws npcs "," ws "\"quests\":" ws quests "}" ws

        location ::= "{" ws "\"name\":" ws string "," ws "\"description\":" ws string "," ws "\"connections\":" ws array "," ws "\"secrets\":" ws array "}" ws

        npcs ::= "[" ws (npc ("," ws npc)*)? "]" ws

        npc ::= "{" ws "\"name\":" ws string "," ws "\"description\":" ws string "," ws "\"personality\":" ws string "," ws "\"occupation\":" ws string "," ws "\"disposition\":" ws string "," ws "\"locationName\":" ws string "," ws "\"secrets\":" ws array "," ws "\"combat\":" ws combat "}" ws

        combat ::= "null" ws | "{" ws "\"attackPower\":" ws integer "," ws "\"defense\":" ws integer "," ws "\"attackSkillLevel\":" ws integer "," ws "\"defenseSkillLevel\":" ws integer "," ws "\"hp\":" ws integer "}" ws

        quests ::= "[" ws (quest ("," ws quest)*)? "]" ws

        quest ::= "{" ws "\"title\":" ws string "," ws "\"description\":" ws string "," ws "\"objectives\":" ws array "," ws "\"rewards\":" ws array "}" ws

        array ::= "[" ws (string ("," ws string)*)? "]" ws

        string ::= "\"" ( [^"\\\x7F\x00-\x1F] | "\\" (["\\bfnrt] | "u" [0-9a-fA-F]{4}) )* "\"" ws

        integer ::= "-"? ("0" | [1-9] [0-9]*) ws

        ws ::= | " " | "\n" [ \t]{0,20}
    """

    /**
     * The JSON keys the grammar requires, in the order it emits them. The test
     * compares this list against the [WorldDraft] serializer descriptor so a
     * renamed field cannot silently break the grammar.
     */
    val KEYS: List<String> = listOf(
        "name",
        "genre",
        "era",
        "setting",
        "atmosphere",
        "history",
        "geography",
        "socialStructure",
        "factions",
        "techMagic",
        "specialSettings",
        "objective",
        "problem",
        "secrets",
        "scenario",
        "opening",
    )

    /** Keys of the nested `scenario` object. */
    val SCENARIO_KEYS: List<String> = listOf(
        "title",
        "premise",
        "length",
        "difficulty",
        "openingHook",
    )

    /** Keys of the nested `opening` object. */
    val OPENING_KEYS: List<String> = listOf(
        "location",
        "npcs",
        "quests",
    )

    /** Keys of the nested `opening.location` object. */
    val LOCATION_KEYS: List<String> = listOf(
        "name",
        "description",
        "connections",
        "secrets",
    )

    /** Keys of each `opening.npcs[]` object. */
    val NPC_KEYS: List<String> = listOf(
        "name",
        "description",
        "personality",
        "occupation",
        "disposition",
        "locationName",
        "secrets",
        "combat",
    )

    /** Keys of each `opening.quests[]` object. */
    val QUEST_KEYS: List<String> = listOf(
        "title",
        "description",
        "objectives",
        "rewards",
    )

    /** Keys of the nested `combat` object. */
    val COMBAT_KEYS: List<String> = listOf(
        "attackPower",
        "defense",
        "attackSkillLevel",
        "defenseSkillLevel",
        "hp",
    )
}
