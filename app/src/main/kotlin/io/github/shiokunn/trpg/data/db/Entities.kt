package io.github.shiokunn.trpg.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "worlds")
data class WorldEntity(
    @PrimaryKey val id: String,
    val name: String,
    val genre: String,
    val era: String,
    val setting: String,
    val atmosphere: String,
    val history: String,
    val geography: String,
    val socialStructure: String,
    val factionsJson: String,
    val techMagic: String,
    val specialSettingsJson: String,
    val objective: String,
    val problem: String,
    val secretsJson: String,
    val scenarioTitle: String,
    val scenarioPremise: String,
    val scenarioLength: String,
    val difficulty: String,
    val createdAt: Long,
)

@Entity(
    tableName = "game_sessions",
    foreignKeys = [
        ForeignKey(
            entity = WorldEntity::class,
            parentColumns = ["id"],
            childColumns = ["worldId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("worldId"), Index("lastPlayedAt"), Index("status")],
)
data class SessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val worldId: String,
    val characterId: String,
    val currentLocationId: String?,
    val currentChapter: Int,
    val status: String,
    val turnCount: Int,
    val summary: String,
    val activeCombatJson: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val lastPlayedAt: Long,
)

@Entity(
    tableName = "characters",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sessionId"], unique = true)],
)
data class CharacterEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val name: String,
    val species: String,
    val occupation: String,
    val background: String,
    val personality: String,
    val attributesJson: String,
    val hp: Int,
    val maxHp: Int,
    val experience: Int,
)

@Entity(
    tableName = "skills",
    foreignKeys = [
        ForeignKey(
            entity = CharacterEntity::class,
            parentColumns = ["id"],
            childColumns = ["characterId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("characterId")],
)
data class SkillEntity(
    @PrimaryKey val id: String,
    val characterId: String,
    val name: String,
    val level: Int,
    val description: String,
)

@Entity(
    tableName = "inventory_items",
    foreignKeys = [
        ForeignKey(
            entity = CharacterEntity::class,
            parentColumns = ["id"],
            childColumns = ["characterId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("characterId")],
)
data class InventoryItemEntity(
    @PrimaryKey val id: String,
    val characterId: String,
    val name: String,
    val description: String,
    val quantity: Int,
    val type: String,
    val equipped: Boolean,
    val power: Int,
    val defense: Int,
    val tagsJson: String,
)

@Entity(
    tableName = "npcs",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId"), Index("locationId")],
)
data class NpcEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val name: String,
    val description: String,
    val personality: String,
    val occupation: String,
    val relationship: Int,
    val disposition: String,
    val secretsJson: String,
    val isAlive: Boolean,
    val isDiscovered: Boolean,
    val locationId: String?,
    val combatJson: String?,
)

@Entity(
    tableName = "npc_memories",
    foreignKeys = [
        ForeignKey(
            entity = NpcEntity::class,
            parentColumns = ["id"],
            childColumns = ["npcId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("npcId"), Index("sessionId")],
)
data class NpcMemoryEntity(
    @PrimaryKey val id: String,
    val npcId: String,
    val sessionId: String,
    val content: String,
    val importance: Int,
    val isSecret: Boolean,
    val turn: Int,
    val createdAt: Long,
)

@Entity(
    tableName = "locations",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class LocationEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val name: String,
    val description: String,
    val isDiscovered: Boolean,
    val connectionsJson: String,
    val secretsJson: String,
)

@Entity(
    tableName = "quests",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class QuestEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val title: String,
    val description: String,
    val objectivesJson: String,
    val status: String,
    val rewardsJson: String,
)

@Entity(
    tableName = "game_events",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sessionId", "turn"])],
)
data class GameEventEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val turn: Int,
    val type: String,
    val summary: String,
    val payloadJson: String,
    val createdAt: Long,
)

@Entity(
    tableName = "story_memories",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId"), Index(value = ["sessionId", "importance"])],
)
data class StoryMemoryEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val category: String,
    val content: String,
    val importance: Int,
    val isSecret: Boolean,
    val isConfirmed: Boolean,
    val turn: Int,
    val relatedIdsJson: String,
    val createdAt: Long,
)

@Entity(
    tableName = "chat_history",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sessionId", "turn"]), Index("npcId")],
)
data class ChatHistoryEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val turn: Int,
    val role: String,
    val content: String,
    val npcId: String?,
    val createdAt: Long,
)

@Entity(
    tableName = "dice_roll_logs",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sessionId", "turn"])],
)
data class DiceRollEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val turn: Int,
    val attributeId: String,
    val skillId: String?,
    val skillName: String,
    val diceJson: String,
    val rollMode: String,
    val attributeValue: Int,
    val skillLevel: Int,
    val modifiers: Int,
    val total: Int,
    val difficulty: Int,
    val outcome: String,
    val reason: String,
    val createdAt: Long,
)

@Entity(tableName = "model_settings")
data class ModelSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val modelFileName: String,
    val modelDisplayName: String,
    val nCtx: Int,
    val temperature: Float,
    val topP: Float,
    val topK: Int,
    val repeatPenalty: Float,
    val maxTokens: Int,
    val gpuLayers: Int,
    val backend: String,
    val updatedAt: Long,
)

@Entity(
    tableName = "world_flags",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class WorldFlagEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val key: String,
    val value: String,
    val updatedAt: Long,
)
