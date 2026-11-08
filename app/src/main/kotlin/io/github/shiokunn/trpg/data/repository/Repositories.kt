package io.github.shiokunn.trpg.data.repository

import androidx.room.withTransaction
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
import io.github.shiokunn.trpg.data.db.AppDatabase
import io.github.shiokunn.trpg.data.db.QuestEntity
import io.github.shiokunn.trpg.data.db.WorldFlagEntity
import io.github.shiokunn.trpg.data.db.toDomain
import io.github.shiokunn.trpg.data.db.toEntity
import io.github.shiokunn.trpg.data.db.toPairValue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.util.UUID

data class SessionBundle(
    val session: GameSession,
    val world: World,
    val character: Character,
    val npcs: List<Npc>,
    val locations: List<Location>,
    val quests: List<Quest>,
    val flags: Map<String, String>,
)

data class NewAdventure(
    val world: World,
    val character: Character,
    val sessionTitle: String,
    val openingLocationId: String?,
    val locations: List<Location>,
    val npcs: List<Npc>,
    val quests: List<Quest>,
    val flags: Map<String, String> = emptyMap(),
)

/** Everything produced by one successful turn, committed atomically. */
data class TurnCommit(
    val session: GameSession,
    val character: Character,
    val npcs: List<Npc> = emptyList(),
    val quests: List<Quest> = emptyList(),
    val locations: List<Location> = emptyList(),
    val flags: Map<String, String> = emptyMap(),
    val chatMessages: List<ChatMessage> = emptyList(),
    val diceRolls: List<DiceResult> = emptyList(),
    val memories: List<StoryMemory> = emptyList(),
    val npcMemories: List<NpcMemory> = emptyList(),
    val events: List<GameEvent> = emptyList(),
)

private data class PartialBundle(
    val session: GameSession,
    val world: World,
    val character: Character,
    val npcs: List<Npc>,
    val locations: List<Location>,
)

class WorldRepository(private val db: AppDatabase) {
    fun observeAll(): Flow<List<World>> = db.worldDao().observeAll().map { list -> list.map { it.toDomain() } }

    fun observe(id: String): Flow<World?> = db.worldDao().observe(id).map { it?.toDomain() }

    suspend fun get(id: String): World? = db.worldDao().get(id)?.toDomain()

    suspend fun save(world: World) {
        db.worldDao().upsert(world.toEntity())
    }

    suspend fun delete(id: String) {
        db.worldDao().delete(id)
    }
}

class CharacterRepository(private val db: AppDatabase) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(sessionId: String): Flow<Character?> =
        db.characterDao().observeBySession(sessionId).flatMapLatest { entity ->
            if (entity == null) {
                flowOf(null)
            } else {
                combine(
                    db.skillDao().observeByCharacter(entity.id),
                    db.inventoryDao().observeByCharacter(entity.id),
                ) { skills, items -> entity.toDomain(skills, items) }
            }
        }

    suspend fun get(sessionId: String): Character? {
        val entity = db.characterDao().getBySession(sessionId) ?: return null
        return entity.toDomain(
            db.skillDao().getByCharacter(entity.id),
            db.inventoryDao().getByCharacter(entity.id),
        )
    }

    suspend fun save(sessionId: String, character: Character) {
        db.withTransaction {
            db.characterDao().upsert(character.toEntity(sessionId))
            db.skillDao().deleteForCharacter(character.id)
            db.skillDao().upsertAll(character.skills.map { it.toEntity(character.id) })
            db.inventoryDao().deleteForCharacter(character.id)
            db.inventoryDao().upsertAll(character.inventory.map { it.toEntity(character.id) })
        }
    }

    suspend fun updateHp(characterId: String, hp: Int) {
        val entity = db.characterDao().getById(characterId) ?: return
        db.characterDao().upsert(entity.copy(hp = hp.coerceIn(0, entity.maxHp)))
    }
}

class SessionRepository(private val db: AppDatabase) {
    fun observeRecent(limit: Int = 10): Flow<List<GameSession>> =
        db.sessionDao().observeRecent(limit).map { list -> list.map { it.toDomain() } }

    fun observeAll(): Flow<List<GameSession>> =
        db.sessionDao().observeAll().map { list -> list.map { it.toDomain() } }

    fun observe(id: String): Flow<GameSession?> = db.sessionDao().observe(id).map { it?.toDomain() }

    suspend fun get(id: String): GameSession? = db.sessionDao().get(id)?.toDomain()

    fun observeRecentDetails(limit: Int = 10) = db.sessionDao().observeRecentDetails(limit)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeBundle(sessionId: String): Flow<SessionBundle?> {
        val sessionFlow = observe(sessionId)
        val worldFlow = sessionFlow.flatMapLatest { session ->
            if (session == null) flowOf(null) else db.worldDao().observe(session.worldId).map { it?.toDomain() }
        }
        val characterFlow = db.characterDao().observeBySession(sessionId).flatMapLatest { entity ->
            if (entity == null) {
                flowOf(null)
            } else {
                combine(
                    db.skillDao().observeByCharacter(entity.id),
                    db.inventoryDao().observeByCharacter(entity.id),
                ) { skills, items -> entity.toDomain(skills, items) }
            }
        }
        val npcsFlow = db.npcDao().observeBySession(sessionId).map { list -> list.map { it.toDomain() } }
        val locationsFlow = db.locationDao().observeBySession(sessionId).map { list -> list.map { it.toDomain() } }
        val questsFlow = db.questDao().observeBySession(sessionId).map { list -> list.map { it.toDomain() } }
        val flagsFlow = db.worldFlagDao().observeBySession(sessionId)

        return combine(
            sessionFlow,
            worldFlow,
            characterFlow,
            npcsFlow,
            locationsFlow,
        ) { session, world, character, npcs, locations ->
            if (session == null || world == null || character == null) {
                null
            } else {
                PartialBundle(session, world, character, npcs, locations)
            }
        }.flatMapLatest { partial ->
            if (partial == null) {
                flowOf(null)
            } else {
                combine(questsFlow, flagsFlow) { quests: List<QuestEntity>, flags: List<WorldFlagEntity> ->
                    SessionBundle(
                        session = partial.session,
                        world = partial.world,
                        character = partial.character,
                        npcs = partial.npcs,
                        locations = partial.locations,
                        quests = quests.map { it.toDomain() },
                        flags = flags.associate { it.toPairValue() },
                    )
                }
            }
        }
    }

    suspend fun create(newAdventure: NewAdventure): String = db.withTransaction {
        val now = System.currentTimeMillis()
        val sessionId = UUID.randomUUID().toString()
        val character = newAdventure.character.copy(id = newAdventure.character.id.ifBlank { UUID.randomUUID().toString() })
        val session = GameSession(
            id = sessionId,
            title = newAdventure.sessionTitle,
            worldId = newAdventure.world.id,
            characterId = character.id,
            currentLocationId = newAdventure.openingLocationId,
            currentChapter = 1,
            turnCount = 0,
            summary = newAdventure.world.scenarioPremise.take(600),
            createdAt = now,
            updatedAt = now,
            lastPlayedAt = now,
        )

        db.worldDao().upsert(newAdventure.world.toEntity())
        db.sessionDao().upsert(session.toEntity())
        db.characterDao().upsert(character.toEntity(sessionId))
        db.skillDao().upsertAll(character.skills.map { it.toEntity(character.id) })
        db.inventoryDao().upsertAll(character.inventory.map { it.toEntity(character.id) })
        db.locationDao().upsertAll(newAdventure.locations.map { it.toEntity(sessionId) })
        db.npcDao().upsertAll(newAdventure.npcs.map { it.toEntity(sessionId) })
        db.questDao().upsertAll(newAdventure.quests.map { it.toEntity(sessionId) })
        db.worldFlagDao().upsertAll(
            newAdventure.flags.map { (key, value) ->
                WorldFlagEntity(
                    id = "$sessionId::$key",
                    sessionId = sessionId,
                    key = key,
                    value = value,
                    updatedAt = now,
                )
            },
        )
        sessionId
    }

    suspend fun loadBundle(sessionId: String): SessionBundle? {
        val session = db.sessionDao().get(sessionId)?.toDomain() ?: return null
        val world = db.worldDao().get(session.worldId)?.toDomain() ?: return null
        val characterEntity = db.characterDao().getBySession(sessionId) ?: return null
        val character = characterEntity.toDomain(
            db.skillDao().getByCharacter(characterEntity.id),
            db.inventoryDao().getByCharacter(characterEntity.id),
        )
        return SessionBundle(
            session = session,
            world = world,
            character = character,
            npcs = db.npcDao().getBySession(sessionId).map { it.toDomain() },
            locations = db.locationDao().getBySession(sessionId).map { it.toDomain() },
            quests = db.questDao().getBySession(sessionId).map { it.toDomain() },
            flags = db.worldFlagDao().getBySession(sessionId).associate { it.toPairValue() },
        )
    }

    suspend fun commitTurn(sessionId: String, commit: TurnCommit) = db.withTransaction {
        db.sessionDao().upsert(commit.session.toEntity())
        db.characterDao().upsert(commit.character.toEntity(sessionId))
        db.skillDao().deleteForCharacter(commit.character.id)
        db.skillDao().upsertAll(commit.character.skills.map { it.toEntity(commit.character.id) })
        db.inventoryDao().deleteForCharacter(commit.character.id)
        db.inventoryDao().upsertAll(commit.character.inventory.map { it.toEntity(commit.character.id) })
        if (commit.npcs.isNotEmpty()) db.npcDao().upsertAll(commit.npcs.map { it.toEntity(sessionId) })
        if (commit.quests.isNotEmpty()) db.questDao().upsertAll(commit.quests.map { it.toEntity(sessionId) })
        if (commit.locations.isNotEmpty()) {
            db.locationDao().upsertAll(commit.locations.map { it.toEntity(sessionId) })
        }
        if (commit.flags.isNotEmpty()) {
            val now = System.currentTimeMillis()
            db.worldFlagDao().upsertAll(
                commit.flags.map { (key, value) ->
                    WorldFlagEntity(
                        id = "$sessionId::$key",
                        sessionId = sessionId,
                        key = key,
                        value = value,
                        updatedAt = now,
                    )
                },
            )
        }
        if (commit.chatMessages.isNotEmpty()) {
            db.chatHistoryDao().insertAll(commit.chatMessages.map { it.toEntity(sessionId) })
        }
        commit.diceRolls.forEach { roll ->
            db.diceRollDao().insert(roll.toEntity(sessionId, UUID.randomUUID().toString()))
        }
        if (commit.memories.isNotEmpty()) {
            db.storyMemoryDao().upsertAll(commit.memories.map { it.toEntity(sessionId) })
        }
        if (commit.npcMemories.isNotEmpty()) {
            db.npcMemoryDao().upsertAll(commit.npcMemories.map { it.toEntity(sessionId) })
        }
        if (commit.events.isNotEmpty()) {
            db.gameEventDao().insertAll(commit.events.map { it.toEntity(sessionId) })
        }
    }

    suspend fun touch(sessionId: String) {
        val session = get(sessionId) ?: return
        val now = System.currentTimeMillis()
        db.sessionDao().upsert(session.copy(lastPlayedAt = now, updatedAt = now).toEntity())
    }

    suspend fun delete(sessionId: String) {
        // Cascades remove characters, skills, items, NPCs, quests, memories and logs.
        db.sessionDao().delete(sessionId)
    }
}

class MemoryRepository(private val db: AppDatabase) {
    suspend fun recentChat(sessionId: String, limit: Int): List<ChatMessage> =
        db.chatHistoryDao().getRecent(sessionId, limit)
            .map { it.toDomain() }
            .reversed()

    fun observeChat(sessionId: String): Flow<List<ChatMessage>> =
        db.chatHistoryDao().observeBySession(sessionId).map { list -> list.map { it.toDomain() } }

    suspend fun topMemories(sessionId: String, limit: Int): List<StoryMemory> =
        db.storyMemoryDao().getTop(sessionId, limit).map { it.toDomain() }

    suspend fun searchMemories(sessionId: String, keyword: String, limit: Int): List<StoryMemory> =
        db.storyMemoryDao().search(sessionId, keyword, limit).map { it.toDomain() }

    fun observeMemories(sessionId: String): Flow<List<StoryMemory>> =
        db.storyMemoryDao().observeBySession(sessionId).map { list -> list.map { it.toDomain() } }

    suspend fun npcMemories(sessionId: String): List<NpcMemory> =
        db.npcMemoryDao().getBySession(sessionId).map { it.toDomain() }

    fun observeNpcMemories(npcId: String): Flow<List<NpcMemory>> =
        db.npcMemoryDao().observeByNpc(npcId).map { list -> list.map { it.toDomain() } }

    fun observeEvents(sessionId: String): Flow<List<GameEvent>> =
        db.gameEventDao().observeBySession(sessionId).map { list -> list.map { it.toDomain() } }

    suspend fun recentEvents(sessionId: String, limit: Int): List<GameEvent> =
        db.gameEventDao().getRecent(sessionId, limit).map { it.toDomain() }

    fun observeDice(sessionId: String): Flow<List<DiceResult>> =
        db.diceRollDao().observeBySession(sessionId).map { list -> list.map { it.toDomain() } }

    suspend fun addChat(sessionId: String, message: ChatMessage) {
        db.chatHistoryDao().insert(message.toEntity(sessionId))
    }

    suspend fun addMemories(sessionId: String, memories: List<StoryMemory>) {
        db.storyMemoryDao().upsertAll(memories.map { it.toEntity(sessionId) })
    }

    suspend fun addEvent(sessionId: String, event: GameEvent) {
        db.gameEventDao().insert(event.toEntity(sessionId))
    }
}

class ModelRepository(private val db: AppDatabase) {
    fun observeSettings(): Flow<io.github.shiokunn.trpg.core.domain.ModelSettings?> =
        db.modelSettingsDao().observe().map { it?.toDomain() }

    suspend fun settings(): io.github.shiokunn.trpg.core.domain.ModelSettings? =
        db.modelSettingsDao().get()?.toDomain()

    suspend fun save(settings: io.github.shiokunn.trpg.core.domain.ModelSettings) {
        db.modelSettingsDao().upsert(settings.copy(updatedAt = System.currentTimeMillis()).toEntity())
    }
}
