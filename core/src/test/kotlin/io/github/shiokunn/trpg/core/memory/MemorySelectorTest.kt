package io.github.shiokunn.trpg.core.memory

import io.github.shiokunn.trpg.core.domain.MemoryCategory
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
        val old = memory("old", "昔の祭り", importance = 2, turn = 0)

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
