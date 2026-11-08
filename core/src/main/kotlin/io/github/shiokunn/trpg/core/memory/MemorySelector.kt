package io.github.shiokunn.trpg.core.memory

import io.github.shiokunn.trpg.core.domain.MemoryCategory
import io.github.shiokunn.trpg.core.domain.StoryMemory

/**
 * Picks which long-term memories enter the next prompt. The full memory store is never
 * sent: the budget is limited, and relevance is a mix of importance, recency,
 * unresolved threads and keyword overlap with the player's action.
 *
 * Secrets are allowed here because this list is only used inside the GM prompt;
 * player-facing screens filter them out separately.
 */
object MemorySelector {

    const val DEFAULT_LIMIT = 16

    fun select(
        memories: List<StoryMemory>,
        query: String,
        currentTurn: Int,
        limit: Int = DEFAULT_LIMIT,
    ): List<StoryMemory> {
        if (memories.isEmpty() || limit <= 0) return emptyList()
        val keywords = extractKeywords(query)

        return memories
            .distinctBy { it.id }
            .sortedWith(
                compareByDescending<StoryMemory> { score(it, keywords, currentTurn) }
                    .thenByDescending { it.importance }
                    .thenByDescending { it.turn },
            )
            .take(limit)
    }

    fun score(memory: StoryMemory, keywords: List<String>, currentTurn: Int): Int {
        var score = memory.importance * 2
        if (memory.isConfirmed) score += 1 else score -= 1
        if (memory.category == MemoryCategory.UNRESOLVED) score += 3
        if (memory.category == MemoryCategory.FORESHADOWING) score += 2
        val age = (currentTurn - memory.turn).coerceAtLeast(0)
        score += when {
            age <= 1 -> 5
            age <= 3 -> 3
            age <= 6 -> 1
            else -> 0
        }
        if (keywords.isNotEmpty()) {
            val hits = keywords.count { keyword -> matches(memory.content, keyword) }
            score += hits * 4
        }
        return score
    }

    /** Very small keyword extractor: no dictionary, but enough for Japanese and ASCII nouns. */
    fun extractKeywords(query: String, max: Int = 6): List<String> {
        if (query.isBlank()) return emptyList()
        val parts = query.split(SPLIT_REGEX)
            .map { it.trim() }
            .filter { part ->
                // Keep single-character CJK nouns (港, 霧), drop single Latin letters.
                part.length >= 2 || (part.length == 1 && part[0].code > 0x2E80)
            }
        return parts
            .distinct()
            .take(max)
    }

    /**
     * Japanese has no spaces, so a whole clause rarely appears verbatim. Fall back to
     * shared character bigrams, which is cheap and good enough for retrieval.
     */
    private fun matches(content: String, keyword: String): Boolean {
        if (content.contains(keyword, ignoreCase = true)) return true
        if (keyword.length < 2) return false
        return keyword.windowed(2).any { gram -> content.contains(gram, ignoreCase = true) }
    }

    private val SPLIT_REGEX = Regex("[\\s、。,.!?！？・:：;；\\[\\]【】「」『』（）()]+")
}
