package io.github.shiokunn.trpg.core.backup

import io.github.shiokunn.trpg.core.domain.Character
import io.github.shiokunn.trpg.core.domain.ChatMessage
import io.github.shiokunn.trpg.core.domain.DiceResult
import io.github.shiokunn.trpg.core.domain.GameEvent
import io.github.shiokunn.trpg.core.domain.GameSession
import io.github.shiokunn.trpg.core.domain.Location
import io.github.shiokunn.trpg.core.domain.Npc
import io.github.shiokunn.trpg.core.domain.NpcMemory
import io.github.shiokunn.trpg.core.domain.Quest
import io.github.shiokunn.trpg.core.domain.StoryMemory
import io.github.shiokunn.trpg.core.domain.World
import kotlinx.serialization.Serializable

/**
 * One self-contained save. Everything a session needs to resume is grouped here so an
 * import can never leave a session without its world, character or log.
 */
@Serializable
data class BackupSession(
    val session: GameSession,
    val character: Character,
    val npcs: List<Npc> = emptyList(),
    val locations: List<Location> = emptyList(),
    val quests: List<Quest> = emptyList(),
    val flags: Map<String, String> = emptyMap(),
    val chat: List<ChatMessage> = emptyList(),
    val dice: List<DiceResult> = emptyList(),
    val memories: List<StoryMemory> = emptyList(),
    val npcMemories: List<NpcMemory> = emptyList(),
    val events: List<GameEvent> = emptyList(),
)

/**
 * Full user-data export. Model settings and GGUF files are intentionally excluded:
 * they are device-specific and can be gigabytes in size.
 */
@Serializable
data class BackupBundle(
    val format: String = FORMAT,
    val version: Int = CURRENT_VERSION,
    val exportedAt: Long = 0L,
    val appVersion: String = "",
    val worlds: List<World> = emptyList(),
    val sessions: List<BackupSession> = emptyList(),
) {
    companion object {
        const val FORMAT = "io.github.shiokunn.trpg.backup"
        const val CURRENT_VERSION = 1
        const val FILE_NAME = "trpg-backup.json"
    }
}
