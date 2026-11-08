package io.github.shiokunn.trpg.core.domain

import kotlinx.serialization.Serializable

@Serializable
enum class MemoryCategory(val label: String) {
    EVENT("重要な出来事"),
    NPC_RELATION("NPCとの関係"),
    DISCOVERY("発見した情報"),
    UNRESOLVED("未解決の問題"),
    FORESHADOWING("将来の伏線"),
    WORLD("世界設定に影響する出来事"),
}

@Serializable
enum class ChatRole {
    GM,
    PLAYER,
    SYSTEM,
}

@Serializable
data class StoryMemory(
    val id: String,
    val category: MemoryCategory,
    val content: String,
    /** 1..5 */
    val importance: Int = 3,
    /** GM-only information. Never rendered on player-facing screens. */
    val isSecret: Boolean = false,
    /** true: confirmed fact, false: inference/proposal. */
    val isConfirmed: Boolean = true,
    val turn: Int = 0,
    val relatedIds: List<String> = emptyList(),
    val createdAt: Long = 0L,
) {
    fun normalized(): StoryMemory = copy(importance = importance.coerceIn(MIN_IMPORTANCE, MAX_IMPORTANCE))

    companion object {
        const val MIN_IMPORTANCE = 1
        const val MAX_IMPORTANCE = 5
    }
}

@Serializable
data class NpcMemory(
    val id: String,
    val npcId: String,
    val content: String,
    val importance: Int = 3,
    val isSecret: Boolean = false,
    val turn: Int = 0,
    val createdAt: Long = 0L,
) {
    fun normalized(): NpcMemory = copy(importance = importance.coerceIn(1, 5))
}

@Serializable
data class ChatMessage(
    val id: String,
    val role: ChatRole,
    val content: String,
    val npcId: String? = null,
    val turn: Int = 0,
    val createdAt: Long = 0L,
)

@Serializable
data class GameEvent(
    val id: String,
    val turn: Int,
    val type: String,
    val summary: String,
    val payloadJson: String = "{}",
    val createdAt: Long = 0L,
)
