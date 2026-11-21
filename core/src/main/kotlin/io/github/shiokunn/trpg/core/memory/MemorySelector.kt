package io.github.shiokunn.trpg.core.memory

import io.github.shiokunn.trpg.core.domain.MemoryCategory
import io.github.shiokunn.trpg.core.domain.Npc
import io.github.shiokunn.trpg.core.domain.NpcMemory
import io.github.shiokunn.trpg.core.domain.StoryMemory

/**
 * Picks which long-term memories enter the next prompt. The full memory store is never
 * sent: the budget is limited, and relevance is a mix of importance, recency,
 * unresolved threads and keyword overlap with the player's action.
 *
 * Ranking is deterministic and query-aware:
 * 1. memories that share an exact term (or a strong bigram overlap) with the action,
 * 2. important / unresolved / recent memories,
 * 3. other memories only when the query has no relevant candidates.
 *
 * Near-duplicates are collapsed, and both a count and a character budget are enforced.
 * Secrets are allowed here because this list is only used inside the GM prompt;
 * player-facing screens filter them out separately.
 */
object MemorySelector {

    const val DEFAULT_LIMIT = 12
    const val DEFAULT_MAX_CHARACTERS = 1_800

    const val NPC_MEMORIES_PER_NPC = 3
    const val NPC_MEMORY_MAX_CHARACTERS = 900

    fun select(
        memories: List<StoryMemory>,
        query: String,
        currentTurn: Int,
        limit: Int = DEFAULT_LIMIT,
        maxCharacters: Int = DEFAULT_MAX_CHARACTERS,
    ): List<StoryMemory> {
        if (memories.isEmpty() || limit <= 0 || maxCharacters <= 0) return emptyList()
        val keywords = extractKeywords(query)
        val ranked = memories
            .distinctBy { it.id }
            .map { memory ->
                RankedMemory(
                    memory = memory,
                    score = score(memory, keywords, currentTurn),
                    relevance = relevanceTier(memory.content, keywords),
                )
            }
            .sortedWith(
                compareByDescending<RankedMemory> { it.relevance }
                    .thenByDescending { it.score }
                    .thenByDescending { it.memory.importance }
                    .thenByDescending { it.memory.turn },
            )

        val ordered = if (keywords.isEmpty()) {
            ranked
        } else {
            ranked.filter { it.relevance > 0 } +
                ranked.filter { it.relevance == 0 && isFallbackWorthy(it.memory, currentTurn) }
        }
        return takeBounded(ordered, limit, maxCharacters)
    }

    /**
     * Selects NPC memories per NPC. A memory is never attached to a different NPC, and
     * undiscovered NPCs do not consume prompt space. The per-NPC cap and the global
     * character cap keep a large cast from crowding out the current scene.
     */
    fun selectNpcMemories(
        memories: List<NpcMemory>,
        npcs: List<Npc>,
        query: String,
        currentTurn: Int,
        perNpcLimit: Int = NPC_MEMORIES_PER_NPC,
        maxCharacters: Int = NPC_MEMORY_MAX_CHARACTERS,
    ): List<NpcMemory> {
        if (memories.isEmpty() || npcs.isEmpty() || perNpcLimit <= 0 || maxCharacters <= 0) {
            return emptyList()
        }
        val keywords = extractKeywords(query)
        val visibleIds = npcs.asSequence().filter { it.isDiscovered }.map { it.id }.toSet()
        if (visibleIds.isEmpty()) return emptyList()

        val byNpc = memories
            .distinctBy { it.id }
            .filter { it.npcId in visibleIds }
            .groupBy { it.npcId }

        val selected = mutableListOf<NpcMemory>()
        // Keep the untruncated rows for duplicate checks: truncation must not change
        // which memories are considered duplicates.
        val originals = mutableListOf<NpcMemory>()
        var characters = 0
        for (npcId in visibleIds) {
            val ranked = byNpc[npcId].orEmpty()
                .map { memory ->
                    RankedNpcMemory(
                        memory = memory,
                        score = npcScore(memory, keywords, currentTurn),
                        relevance = relevanceTier(memory.content, keywords),
                    )
                }
                .sortedWith(
                    compareByDescending<RankedNpcMemory> { it.relevance }
                        .thenByDescending { it.score }
                        .thenByDescending { it.memory.importance }
                        .thenByDescending { it.memory.turn },
                )

            var takenForNpc = 0
            for (entry in ranked) {
                if (takenForNpc >= perNpcLimit) break
                val memory = entry.memory
                if (originals.any {
                        it.npcId == npcId && it.isSecret == memory.isSecret &&
                            isNearDuplicate(it.content, memory.content)
                    }
                ) {
                    continue
                }
                val remaining = maxCharacters - characters
                if (remaining <= 0) continue
                val content = truncateToBudget(memory.content, remaining)
                if (content.isEmpty() && memory.content.isNotEmpty()) continue
                selected += if (content == memory.content) memory else memory.copy(content = content)
                originals += memory
                characters += content.length
                takenForNpc++
            }
        }
        return selected
    }

    fun score(memory: StoryMemory, keywords: List<String>, currentTurn: Int): Int {
        var score = memory.importance * 2
        if (memory.isConfirmed) score += 1 else score -= 1
        if (memory.category == MemoryCategory.UNRESOLVED) score += 3
        if (memory.category == MemoryCategory.FORESHADOWING) score += 2
        score += recencyBonus(memory.turn, currentTurn)
        score += relevanceScore(memory.content, keywords)
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

    private fun takeBounded(
        ordered: List<RankedMemory>,
        limit: Int,
        maxCharacters: Int,
    ): List<StoryMemory> {
        val selected = ArrayList<StoryMemory>(minOf(limit, ordered.size))
        val originals = ArrayList<StoryMemory>(minOf(limit, ordered.size))
        var characters = 0
        for (entry in ordered) {
            if (selected.size >= limit) break
            val memory = entry.memory
            if (originals.any { isDuplicate(it, memory) }) continue
            val remaining = maxCharacters - characters
            if (remaining <= 0) break
            val content = truncateToBudget(memory.content, remaining)
            if (content.isEmpty() && memory.content.isNotEmpty()) continue
            selected += if (content == memory.content) memory else memory.copy(content = content)
            originals += memory
            characters += content.length
        }
        return selected
    }

    private fun isFallbackWorthy(memory: StoryMemory, currentTurn: Int): Boolean {
        if (memory.importance >= 4) return true
        if (memory.category == MemoryCategory.UNRESOLVED) return true
        if (memory.category == MemoryCategory.FORESHADOWING) return true
        return currentTurn - memory.turn <= 3
    }

    private fun npcScore(memory: NpcMemory, keywords: List<String>, currentTurn: Int): Int {
        var score = memory.importance * 2
        score += recencyBonus(memory.turn, currentTurn)
        score += relevanceScore(memory.content, keywords)
        return score
    }

    private fun recencyBonus(turn: Int, currentTurn: Int): Int {
        val age = (currentTurn - turn).coerceAtLeast(0)
        return when {
            age <= 1 -> 5
            age <= 3 -> 3
            age <= 6 -> 1
            else -> 0
        }
    }

    /**
     * 2 = the whole query term appears, 1 = at least half of the term's bigrams appear
     * (2 or more bigrams), 0 = only a single common bigram or no overlap. A single
     * shared bigram is too noisy to count as relevance.
     */
    private fun relevanceTier(content: String, keywords: List<String>): Int {
        if (keywords.isEmpty()) return 0
        var best = 0
        for (keyword in keywords) {
            if (content.contains(keyword, ignoreCase = true)) return 2
            if (keyword.length < 3) continue
            val grams = keyword.windowed(2).toSet()
            val hits = grams.count { gram -> content.contains(gram, ignoreCase = true) }
            if (hits >= 2 && hits * 2 >= grams.size) {
                if (best < 1) best = 1
            }
        }
        return best
    }

    private fun relevanceScore(content: String, keywords: List<String>): Int {
        var score = 0
        for (keyword in keywords) {
            score += when (relevanceTier(content, listOf(keyword))) {
                2 -> 8
                1 -> 3
                else -> 0
            }
        }
        return score
    }

    /**
     * Cuts [text] to at most [maxLength] UTF-16 code units without splitting a surrogate
     * pair. The caller counts the returned length, so the budget is exact even when the
     * first (most relevant) memory is longer than the whole budget.
     */
    private fun truncateToBudget(text: String, maxLength: Int): String {
        if (text.length <= maxLength) return text
        if (maxLength <= 0) return ""
        var end = maxLength
        if (end < text.length &&
            Character.isHighSurrogate(text[end - 1]) &&
            Character.isLowSurrogate(text[end])
        ) {
            end--
        }
        return text.substring(0, end)
    }

    /** Same text but a different truth status / category / secrecy is not a duplicate. */
    private fun isDuplicate(a: StoryMemory, b: StoryMemory): Boolean {
        if (a.category != b.category || a.isConfirmed != b.isConfirmed || a.isSecret != b.isSecret) {
            return false
        }
        return isNearDuplicate(a.content, b.content)
    }

    /**
     * Compares normalized content. Exact matches and a shorter memory contained in a
     * longer one are duplicates. For similar-length text, a high bigram Jaccard
     * similarity is used. Short strings are not merged on similarity alone.
     */
    private fun isNearDuplicate(a: String, b: String): Boolean {
        val normalizedA = normalize(a)
        val normalizedB = normalize(b)
        if (normalizedA.isEmpty() || normalizedB.isEmpty()) return false
        if (normalizedA == normalizedB) return true
        val shorter = if (normalizedA.length <= normalizedB.length) normalizedA else normalizedB
        val longer = if (shorter === normalizedA) normalizedB else normalizedA
        if (shorter.length >= 8 && longer.contains(shorter)) return true
        if (shorter.length < 8) return false

        val gramsA = normalizedA.windowed(2).toSet()
        val gramsB = normalizedB.windowed(2).toSet()
        if (gramsA.isEmpty() || gramsB.isEmpty()) return false
        val intersection = gramsA.count { it in gramsB }
        if (intersection < 6) return false
        val union = gramsA.size + gramsB.size - intersection
        return intersection.toDouble() / union >= 0.8
    }

    private fun normalize(text: String): String = buildString {
        for (char in text) {
            if (char.isLetterOrDigit()) append(char.lowercaseChar())
        }
    }

    private data class RankedMemory(
        val memory: StoryMemory,
        val score: Int,
        val relevance: Int,
    )

    private data class RankedNpcMemory(
        val memory: NpcMemory,
        val score: Int,
        val relevance: Int,
    )

    private val SPLIT_REGEX = Regex("[\\s、。,.!?！？・:：;；\\[\\]【】「」『』（）()]+")
}
