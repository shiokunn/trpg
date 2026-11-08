package io.github.shiokunn.trpg.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface WorldDao {
    @Upsert
    suspend fun upsert(world: WorldEntity)

    @Query("SELECT * FROM worlds WHERE id = :id")
    suspend fun get(id: String): WorldEntity?

    @Query("SELECT * FROM worlds ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<WorldEntity>>

    @Query("SELECT * FROM worlds WHERE id = :id")
    fun observe(id: String): Flow<WorldEntity?>

    @Query("DELETE FROM worlds WHERE id = :id")
    suspend fun delete(id: String)
}

data class RecentSessionRow(
    val id: String,
    val title: String,
    val worldName: String,
    val characterName: String?,
    val updatedAt: Long,
    val lastPlayedAt: Long,
)

@Dao
interface SessionDao {
    @Upsert
    suspend fun upsert(session: SessionEntity)

    @Query("SELECT * FROM game_sessions WHERE id = :id")
    suspend fun get(id: String): SessionEntity?

    @Query("SELECT * FROM game_sessions WHERE id = :id")
    fun observe(id: String): Flow<SessionEntity?>

    @Query("SELECT * FROM game_sessions ORDER BY lastPlayedAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM game_sessions ORDER BY lastPlayedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SessionEntity>>

    @Query(
        "SELECT s.id AS id, s.title AS title, w.name AS worldName, c.name AS characterName, " +
            "s.updatedAt AS updatedAt, s.lastPlayedAt AS lastPlayedAt " +
            "FROM game_sessions s JOIN worlds w ON w.id = s.worldId " +
            "LEFT JOIN characters c ON c.sessionId = s.id " +
            "ORDER BY s.lastPlayedAt DESC LIMIT :limit",
    )
    fun observeRecentDetails(limit: Int): Flow<List<RecentSessionRow>>

    @Query("DELETE FROM game_sessions WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface CharacterDao {
    @Upsert
    suspend fun upsert(character: CharacterEntity)

    @Query("SELECT * FROM characters WHERE sessionId = :sessionId")
    suspend fun getBySession(sessionId: String): CharacterEntity?

    @Query("SELECT * FROM characters WHERE sessionId = :sessionId")
    fun observeBySession(sessionId: String): Flow<CharacterEntity?>

    @Query("SELECT * FROM characters WHERE id = :id")
    suspend fun getById(id: String): CharacterEntity?

    @Query("DELETE FROM characters WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)
}

@Dao
interface SkillDao {
    @Upsert
    suspend fun upsertAll(skills: List<SkillEntity>)

    @Query("SELECT * FROM skills WHERE characterId = :characterId ORDER BY name")
    suspend fun getByCharacter(characterId: String): List<SkillEntity>

    @Query("SELECT * FROM skills WHERE characterId = :characterId ORDER BY name")
    fun observeByCharacter(characterId: String): Flow<List<SkillEntity>>

    @Query("DELETE FROM skills WHERE characterId = :characterId")
    suspend fun deleteForCharacter(characterId: String)
}

@Dao
interface InventoryDao {
    @Upsert
    suspend fun upsertAll(items: List<InventoryItemEntity>)

    @Query("SELECT * FROM inventory_items WHERE characterId = :characterId ORDER BY name")
    suspend fun getByCharacter(characterId: String): List<InventoryItemEntity>

    @Query("SELECT * FROM inventory_items WHERE characterId = :characterId ORDER BY name")
    fun observeByCharacter(characterId: String): Flow<List<InventoryItemEntity>>

    @Query("DELETE FROM inventory_items WHERE characterId = :characterId")
    suspend fun deleteForCharacter(characterId: String)
}

@Dao
interface NpcDao {
    @Upsert
    suspend fun upsertAll(npcs: List<NpcEntity>)

    @Upsert
    suspend fun upsert(npc: NpcEntity)

    @Query("SELECT * FROM npcs WHERE sessionId = :sessionId ORDER BY name")
    suspend fun getBySession(sessionId: String): List<NpcEntity>

    @Query("SELECT * FROM npcs WHERE sessionId = :sessionId ORDER BY name")
    fun observeBySession(sessionId: String): Flow<List<NpcEntity>>

    @Query("SELECT * FROM npcs WHERE id = :id")
    suspend fun getById(id: String): NpcEntity?

    @Query("DELETE FROM npcs WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: String)
}

@Dao
interface NpcMemoryDao {
    @Upsert
    suspend fun upsertAll(memories: List<NpcMemoryEntity>)

    @Query("SELECT * FROM npc_memories WHERE npcId = :npcId ORDER BY createdAt DESC")
    suspend fun getByNpc(npcId: String): List<NpcMemoryEntity>

    @Query("SELECT * FROM npc_memories WHERE sessionId = :sessionId ORDER BY importance DESC, createdAt DESC")
    suspend fun getBySession(sessionId: String): List<NpcMemoryEntity>

    @Query("SELECT * FROM npc_memories WHERE npcId = :npcId ORDER BY createdAt DESC")
    fun observeByNpc(npcId: String): Flow<List<NpcMemoryEntity>>
}

@Dao
interface LocationDao {
    @Upsert
    suspend fun upsertAll(locations: List<LocationEntity>)

    @Query("SELECT * FROM locations WHERE sessionId = :sessionId ORDER BY name")
    suspend fun getBySession(sessionId: String): List<LocationEntity>

    @Query("SELECT * FROM locations WHERE sessionId = :sessionId ORDER BY name")
    fun observeBySession(sessionId: String): Flow<List<LocationEntity>>

    @Query("SELECT * FROM locations WHERE id = :id")
    suspend fun getById(id: String): LocationEntity?

    @Query("DELETE FROM locations WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: String)
}

@Dao
interface QuestDao {
    @Upsert
    suspend fun upsertAll(quests: List<QuestEntity>)

    @Upsert
    suspend fun upsert(quest: QuestEntity)

    @Query("SELECT * FROM quests WHERE sessionId = :sessionId ORDER BY title")
    suspend fun getBySession(sessionId: String): List<QuestEntity>

    @Query("SELECT * FROM quests WHERE sessionId = :sessionId ORDER BY title")
    fun observeBySession(sessionId: String): Flow<List<QuestEntity>>

    @Query("SELECT * FROM quests WHERE id = :id")
    suspend fun getById(id: String): QuestEntity?
}

@Dao
interface GameEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: GameEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<GameEventEntity>)

    @Query("SELECT * FROM game_events WHERE sessionId = :sessionId ORDER BY turn DESC, createdAt DESC LIMIT :limit")
    suspend fun getRecent(sessionId: String, limit: Int): List<GameEventEntity>

    @Query("SELECT * FROM game_events WHERE sessionId = :sessionId ORDER BY turn ASC, createdAt ASC")
    fun observeBySession(sessionId: String): Flow<List<GameEventEntity>>
}

@Dao
interface StoryMemoryDao {
    @Upsert
    suspend fun upsertAll(memories: List<StoryMemoryEntity>)

    @Query(
        "SELECT * FROM story_memories WHERE sessionId = :sessionId " +
            "ORDER BY importance DESC, createdAt DESC LIMIT :limit",
    )
    suspend fun getTop(sessionId: String, limit: Int): List<StoryMemoryEntity>

    @Query("SELECT * FROM story_memories WHERE sessionId = :sessionId ORDER BY turn DESC, importance DESC")
    suspend fun getBySession(sessionId: String): List<StoryMemoryEntity>

    @Query("SELECT * FROM story_memories WHERE sessionId = :sessionId ORDER BY turn DESC")
    fun observeBySession(sessionId: String): Flow<List<StoryMemoryEntity>>

    @Query(
        "SELECT * FROM story_memories WHERE sessionId = :sessionId AND content LIKE '%' || :keyword || '%' " +
            "ORDER BY importance DESC LIMIT :limit",
    )
    suspend fun search(sessionId: String, keyword: String, limit: Int): List<StoryMemoryEntity>
}

@Dao
interface ChatHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: ChatHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<ChatHistoryEntity>)

    @Query("SELECT * FROM chat_history WHERE sessionId = :sessionId ORDER BY turn DESC, createdAt DESC LIMIT :limit")
    suspend fun getRecent(sessionId: String, limit: Int): List<ChatHistoryEntity>

    @Query("SELECT * FROM chat_history WHERE sessionId = :sessionId ORDER BY turn ASC, createdAt ASC")
    fun observeBySession(sessionId: String): Flow<List<ChatHistoryEntity>>
}

@Dao
interface DiceRollDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(roll: DiceRollEntity)

    @Query("SELECT * FROM dice_roll_logs WHERE sessionId = :sessionId ORDER BY turn DESC, createdAt DESC LIMIT :limit")
    suspend fun getRecent(sessionId: String, limit: Int): List<DiceRollEntity>

    @Query("SELECT * FROM dice_roll_logs WHERE sessionId = :sessionId ORDER BY turn DESC, createdAt DESC")
    fun observeBySession(sessionId: String): Flow<List<DiceRollEntity>>
}

@Dao
interface ModelSettingsDao {
    @Upsert
    suspend fun upsert(settings: ModelSettingsEntity)

    @Query("SELECT * FROM model_settings WHERE id = 1")
    suspend fun get(): ModelSettingsEntity?

    @Query("SELECT * FROM model_settings WHERE id = 1")
    fun observe(): Flow<ModelSettingsEntity?>
}

@Dao
interface WorldFlagDao {
    @Upsert
    suspend fun upsert(flag: WorldFlagEntity)

    @Upsert
    suspend fun upsertAll(flags: List<WorldFlagEntity>)

    @Query("SELECT * FROM world_flags WHERE sessionId = :sessionId ORDER BY key")
    suspend fun getBySession(sessionId: String): List<WorldFlagEntity>

    @Query("SELECT * FROM world_flags WHERE sessionId = :sessionId ORDER BY key")
    fun observeBySession(sessionId: String): Flow<List<WorldFlagEntity>>

    @Query("DELETE FROM world_flags WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: String)
}
