package io.github.shiokunn.trpg.core.memory

import io.github.shiokunn.trpg.core.domain.MemoryCategory
import io.github.shiokunn.trpg.core.domain.Npc
import io.github.shiokunn.trpg.core.domain.NpcMemory
import io.github.shiokunn.trpg.core.domain.StoryMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemorySelectorTest {

    @Test
    fun keywordMatchesRaiseAnOlderMemory() {
        val matching = memory("m1", "灯台の鍵を見つけた", importance = 3, turn = 1)
        val unrelated = memory("m2", "港の天気は晴れだった", importance = 4, turn = 1)

        val selected = MemorySelector.select(listOf(unrelated, matching), "灯台の鍵を調べる", currentTurn = 10)

        assertEquals(listOf("m1", "m2"), selected.map { it.id })
    }

    @Test
    fun unresolvedThreadsAndRecentEventsRankFirst() {
        val recent = memory("recent", "直前の戦い", importance = 2, turn = 9)
        val unresolved = memory(
            "unresolved",
            "行方不明の船",
            importance = 2,
            turn = 1,
            category = MemoryCategory.UNRESOLVED,
        )
        val old = memory(
            "old",
            "昔の祭り",
            importance = 2,
            turn = 0,
            category = MemoryCategory.FORESHADOWING,
        )

        val selected = MemorySelector.select(listOf(old, unresolved, recent), "", currentTurn = 10)

        assertTrue(selected.indexOfFirst { it.id == "unresolved" } < selected.indexOfFirst { it.id == "old" })
        assertTrue(selected.indexOfFirst { it.id == "recent" } < selected.indexOfFirst { it.id == "old" })
    }

    @Test
    fun limitIsRespectedAndDuplicatesRemoved() {
        val memories = (1..30).map { memory("m$it", "記憶$it", importance = 3, turn = it) }
        val selected = MemorySelector.select(memories + memories.first(), "", currentTurn = 30, limit = 4)

        assertEquals(4, selected.size)
        assertEquals(selected.map { it.id }.distinct().size, selected.size)
    }

    @Test
    fun secretMemoriesStayAvailableForTheGmPrompt() {
        val secret = memory("secret", "黒幕は灯台守", importance = 5, turn = 3, secret = true)
        val selected = MemorySelector.select(listOf(secret), "", currentTurn = 3)
        assertEquals(listOf("secret"), selected.map { it.id })
    }

    @Test
    fun confirmedFactsOutrankInferences() {
        val confirmed = memory("confirmed", "噂", importance = 3, turn = 5)
        val inference = memory("inference", "噂", importance = 3, turn = 5, confirmed = false)

        val selected = MemorySelector.select(listOf(inference, confirmed), "", currentTurn = 5)

        assertEquals(listOf("confirmed", "inference"), selected.map { it.id })
    }

    @Test
    fun keywordsAreSplitAndFiltered() {
        val keywords = MemorySelector.extractKeywords("灯台の鍵を、調べる。a 港 【霧】")
        assertTrue("灯台の鍵を" in keywords)
        assertTrue("調べる" in keywords)
        assertTrue("港" in keywords)
        assertTrue(keywords.none { it == "a" })
        assertTrue(keywords.none { it.startsWith("【") })
    }

    @Test
    fun irrelevantOldMemoriesDoNotCrowdOutQueryMatches() {
        val matching = memory("hit", "灯台の鍵を見つけた", importance = 1, turn = 1)
        val irrelevant = (1..30).map {
            memory("noise$it", "港の天気は晴れだった$it", importance = 2, turn = 1)
        }

        val selected = MemorySelector.select(irrelevant + matching, "灯台の鍵を調べる", currentTurn = 20, limit = 5)

        assertEquals(listOf("hit"), selected.map { it.id })
    }

    @Test
    fun nearDuplicateMemoriesAreCollapsed() {
        val first = memory("first", "灯台の鍵を見つけた", importance = 5, turn = 2)
        val duplicate = memory("duplicate", "灯台の鍵を見つけた（重要）", importance = 5, turn = 2)
        val distinct = memory("distinct", "灯台の鍵を失った", importance = 3, turn = 2)

        val selected = MemorySelector.select(listOf(first, duplicate, distinct), "", currentTurn = 2)

        assertEquals(listOf("first", "distinct"), selected.map { it.id })
    }

    @Test
    fun characterBudgetIsRespected() {
        val memories = (1..20).map { memory("m$it", "記憶内容$it".padEnd(60, 'あ'), importance = 3, turn = it) }

        val selected = MemorySelector.select(memories, "", currentTurn = 20, maxCharacters = 130)

        assertTrue(selected.isNotEmpty())
        assertTrue(selected.sumOf { it.content.length } <= 130)
    }

    @Test
    fun singleStoryMemoryOverLimitIsTruncatedToBudget() {
        val over = memory(
            "over",
            "あ".repeat(MemorySelector.DEFAULT_MAX_CHARACTERS + 25),
            importance = 5,
            turn = 1,
        )

        val selected = MemorySelector.select(listOf(over), "", currentTurn = 1)

        assertEquals(listOf("over"), selected.map { it.id })
        assertEquals(MemorySelector.DEFAULT_MAX_CHARACTERS, selected.first().content.length)
        assertTrue(selected.all { it.content.length <= MemorySelector.DEFAULT_MAX_CHARACTERS })
    }

    @Test
    fun storyMemoryJustBelowLimitIsKeptIntact() {
        val almost = memory(
            "almost",
            "い".repeat(MemorySelector.DEFAULT_MAX_CHARACTERS - 1),
            importance = 5,
            turn = 1,
        )

        val selected = MemorySelector.select(listOf(almost), "", currentTurn = 1)

        assertEquals(listOf("almost"), selected.map { it.id })
        assertEquals(MemorySelector.DEFAULT_MAX_CHARACTERS - 1, selected.first().content.length)
    }

    @Test
    fun multipleStoryMemoriesAreTruncatedToCombinedBudget() {
        val first = memory("first", "あ".repeat(1000), importance = 5, turn = 2)
        val second = memory("second", "い".repeat(1000), importance = 4, turn = 1)

        val selected = MemorySelector.select(listOf(first, second), "", currentTurn = 2)

        assertEquals(listOf("first", "second"), selected.map { it.id })
        assertEquals(MemorySelector.DEFAULT_MAX_CHARACTERS, selected.sumOf { it.content.length })
        assertEquals(1000, selected[0].content.length)
        assertEquals(MemorySelector.DEFAULT_MAX_CHARACTERS - 1000, selected[1].content.length)
        assertTrue(selected.all { it.content.length <= MemorySelector.DEFAULT_MAX_CHARACTERS })
    }

    @Test
    fun npcMemoriesAreSeparatedByNpcAndRespectPerNpcLimit() {
        val npcs = listOf(
            Npc("n1", "マリナ", isDiscovered = true),
            Npc("n2", "灯台守", isDiscovered = true),
            Npc("n3", "謎の男", isDiscovered = false),
        )
        val memories = listOf(
            NpcMemory("a1", "n1", "マリナは酒場の主人", importance = 3, turn = 3),
            NpcMemory("a2", "n1", "マリナは密輸に関与", importance = 3, turn = 2),
            NpcMemory("a3", "n1", "マリナの過去", importance = 3, turn = 1),
            NpcMemory("b1", "n2", "灯台守が姿を消した", importance = 3, turn = 3),
            NpcMemory("c1", "n3", "未発見のNPCの記憶", importance = 5, turn = 3),
        )

        val selected = MemorySelector.selectNpcMemories(memories, npcs, "", currentTurn = 3)

        assertEquals(listOf("a1", "a2", "a3", "b1"), selected.map { it.id })
        assertTrue(selected.all { it.npcId != "n3" })
    }

    @Test
    fun npcMemoriesRespectCharacterBudget() {
        val npcs = listOf(Npc("n1", "マリナ", isDiscovered = true))
        val memories = (1..10).map {
            NpcMemory("m$it", "n1", "非常に長い記憶内容$it".padEnd(60, 'あ'), importance = 3, turn = it)
        }

        val selected = MemorySelector.selectNpcMemories(memories, npcs, "", currentTurn = 10, maxCharacters = 130)

        assertTrue(selected.isNotEmpty())
        assertTrue(selected.sumOf { it.content.length } <= 130)
    }

    @Test
    fun singleNpcMemoryOverLimitIsTruncatedToBudget() {
        val npcs = listOf(Npc("n1", "マリナ", isDiscovered = true))
        val over = NpcMemory(
            "over",
            "n1",
            "あ".repeat(MemorySelector.NPC_MEMORY_MAX_CHARACTERS + 25),
            importance = 5,
            turn = 1,
        )

        val selected = MemorySelector.selectNpcMemories(listOf(over), npcs, "", currentTurn = 1)

        assertEquals(listOf("over"), selected.map { it.id })
        assertEquals(MemorySelector.NPC_MEMORY_MAX_CHARACTERS, selected.first().content.length)
        assertTrue(selected.all { it.content.length <= MemorySelector.NPC_MEMORY_MAX_CHARACTERS })
    }

    @Test
    fun npcMemoryJustBelowLimitIsKeptIntact() {
        val npcs = listOf(Npc("n1", "マリナ", isDiscovered = true))
        val almost = NpcMemory(
            "almost",
            "n1",
            "い".repeat(MemorySelector.NPC_MEMORY_MAX_CHARACTERS - 1),
            importance = 5,
            turn = 1,
        )

        val selected = MemorySelector.selectNpcMemories(listOf(almost), npcs, "", currentTurn = 1)

        assertEquals(listOf("almost"), selected.map { it.id })
        assertEquals(MemorySelector.NPC_MEMORY_MAX_CHARACTERS - 1, selected.first().content.length)
    }

    @Test
    fun multipleNpcMemoriesAreTruncatedToCombinedBudget() {
        val npcs = listOf(Npc("n1", "マリナ", isDiscovered = true))
        val first = NpcMemory("first", "n1", "あ".repeat(600), importance = 5, turn = 2)
        val second = NpcMemory("second", "n1", "い".repeat(600), importance = 4, turn = 1)

        val selected = MemorySelector.selectNpcMemories(
            listOf(first, second),
            npcs,
            "",
            currentTurn = 2,
        )

        assertEquals(listOf("first", "second"), selected.map { it.id })
        assertEquals(MemorySelector.NPC_MEMORY_MAX_CHARACTERS, selected.sumOf { it.content.length })
        assertEquals(600, selected[0].content.length)
        assertEquals(MemorySelector.NPC_MEMORY_MAX_CHARACTERS - 600, selected[1].content.length)
        assertTrue(selected.all { it.content.length <= MemorySelector.NPC_MEMORY_MAX_CHARACTERS })
    }

    @Test
    fun truncationKeepsNpcSeparationAndDuplicateSuppression() {
        val npcs = listOf(
            Npc("n1", "マリナ", isDiscovered = true),
            Npc("n2", "灯台守", isDiscovered = true),
        )
        val base = "長い記憶内容".padEnd(1000, 'あ')
        val first = NpcMemory("a1", "n1", base, importance = 5, turn = 2)
        val nearDuplicate = NpcMemory("a2", "n1", base + "補足", importance = 4, turn = 1)
        val other = NpcMemory("b1", "n2", "別のNPCの記憶", importance = 3, turn = 2)

        val selected = MemorySelector.selectNpcMemories(
            listOf(first, nearDuplicate, other),
            npcs,
            "",
            currentTurn = 2,
            maxCharacters = 1100,
        )

        assertEquals(listOf("a1", "b1"), selected.map { it.id })
        assertEquals(1000, selected[0].content.length)
        assertTrue(selected.all { it.content.length <= 1100 })
    }

    private fun memory(
        id: String,
        content: String,
        importance: Int,
        turn: Int,
        category: MemoryCategory = MemoryCategory.EVENT,
        secret: Boolean = false,
        confirmed: Boolean = true,
    ) = StoryMemory(
        id = id,
        category = category,
        content = content,
        importance = importance,
        isSecret = secret,
        isConfirmed = confirmed,
        turn = turn,
    )
}
