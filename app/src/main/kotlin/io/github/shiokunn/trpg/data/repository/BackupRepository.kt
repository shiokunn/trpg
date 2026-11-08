package io.github.shiokunn.trpg.data.repository

import androidx.room.withTransaction
import io.github.shiokunn.trpg.core.backup.BackupBundle
import io.github.shiokunn.trpg.core.backup.BackupSession
import io.github.shiokunn.trpg.data.db.AppDatabase
import io.github.shiokunn.trpg.data.db.WorldFlagEntity
import io.github.shiokunn.trpg.data.db.toDomain
import io.github.shiokunn.trpg.data.db.toEntity
import io.github.shiokunn.trpg.data.db.toPairValue
import java.util.UUID

data class ImportSummary(
    val worldsImported: Int,
    val sessionsImported: Int,
    val sessionsReplaced: Int,
)

class BackupImportException(val errors: List<String>) :
    Exception(errors.firstOrNull() ?: "バックアップを読み込めませんでした")

/**
 * Full user-data export/import.
 *
 * Import is all-or-nothing: collisions are checked first, existing sessions with the
 * same ID are replaced together with their children, and every write happens in one
 * Room transaction. A failure leaves the previous database untouched.
 */
class BackupRepository(private val db: AppDatabase) {

    suspend fun exportBundle(appVersion: String): BackupBundle {
        val worlds = db.worldDao().getAll().map { it.toDomain() }
        val sessions = db.sessionDao().getAll().map { sessionEntity ->
            val session = sessionEntity.toDomain()
            val characterEntity = db.characterDao().getBySession(session.id)
                ?: throw IllegalStateException("セッション「${session.title}」のキャラクターが見つかりません")
            BackupSession(
                session = session,
                character = characterEntity.toDomain(
                    db.skillDao().getByCharacter(characterEntity.id),
                    db.inventoryDao().getByCharacter(characterEntity.id),
                ),
                npcs = db.npcDao().getBySession(session.id).map { it.toDomain() },
                locations = db.locationDao().getBySession(session.id).map { it.toDomain() },
                quests = db.questDao().getBySession(session.id).map { it.toDomain() },
                flags = db.worldFlagDao().getBySession(session.id).associate { it.toPairValue() },
                chat = db.chatHistoryDao().getAll(session.id).map { it.toDomain() },
                dice = db.diceRollDao().getAll(session.id).map { it.toDomain() },
                memories = db.storyMemoryDao().getBySession(session.id).map { it.toDomain() },
                npcMemories = db.npcMemoryDao().getBySession(session.id).map { it.toDomain() },
                events = db.gameEventDao().getAll(session.id).map { it.toDomain() },
            )
        }
        return BackupBundle(
            exportedAt = System.currentTimeMillis(),
            appVersion = appVersion,
            worlds = worlds,
            sessions = sessions,
        )
    }

    suspend fun importBundle(bundle: BackupBundle): ImportSummary = db.withTransaction {
        val collisions = findCollisions(bundle)
        if (collisions.isNotEmpty()) throw BackupImportException(collisions)

        val replaced = bundle.sessions.count { db.sessionDao().get(it.session.id) != null }
        // Cascade delete keeps a replacement consistent: no orphaned child rows.
        bundle.sessions.forEach { db.sessionDao().delete(it.session.id) }
        bundle.worlds.forEach { db.worldDao().upsert(it.toEntity()) }

        val now = System.currentTimeMillis()
        bundle.sessions.forEach { backup ->
            val sessionId = backup.session.id
            db.sessionDao().upsert(backup.session.toEntity())
            db.characterDao().upsert(backup.character.toEntity(sessionId))
            db.skillDao().upsertAll(backup.character.skills.map { it.toEntity(backup.character.id) })
            db.inventoryDao().upsertAll(backup.character.inventory.map { it.toEntity(backup.character.id) })
            db.npcDao().upsertAll(backup.npcs.map { it.toEntity(sessionId) })
            db.locationDao().upsertAll(backup.locations.map { it.toEntity(sessionId) })
            db.questDao().upsertAll(backup.quests.map { it.toEntity(sessionId) })
            db.worldFlagDao().upsertAll(
                backup.flags.map { (key, value) ->
                    WorldFlagEntity(
                        id = "$sessionId::$key",
                        sessionId = sessionId,
                        key = key,
                        value = value,
                        updatedAt = now,
                    )
                },
            )
            db.chatHistoryDao().insertAll(backup.chat.map { it.toEntity(sessionId) })
            backup.dice.forEach { roll ->
                db.diceRollDao().insert(roll.toEntity(sessionId, UUID.randomUUID().toString()))
            }
            db.storyMemoryDao().upsertAll(backup.memories.map { it.toEntity(sessionId) })
            db.npcMemoryDao().upsertAll(backup.npcMemories.map { it.toEntity(sessionId) })
            db.gameEventDao().insertAll(backup.events.map { it.toEntity(sessionId) })
        }

        ImportSummary(
            worldsImported = bundle.worlds.size,
            sessionsImported = bundle.sessions.size,
            sessionsReplaced = replaced,
        )
    }

    /**
     * Child tables use global primary keys. If an incoming ID belongs to a different
     * session we refuse the import instead of silently moving or overwriting that row.
     */
    private suspend fun findCollisions(bundle: BackupBundle): List<String> {
        val errors = mutableListOf<String>()
        bundle.sessions.forEach { backup ->
            val sessionId = backup.session.id
            db.characterDao().getById(backup.character.id)?.let { existing ->
                if (existing.sessionId != sessionId) {
                    errors += "キャラクターID「${backup.character.id}」が別のセーブで使われています"
                }
            }
            backup.npcs.forEach { npc ->
                db.npcDao().getById(npc.id)?.let { existing ->
                    if (existing.sessionId != sessionId) {
                        errors += "NPC ID「${npc.id}」が別のセーブで使われています"
                    }
                }
            }
            backup.locations.forEach { location ->
                db.locationDao().getById(location.id)?.let { existing ->
                    if (existing.sessionId != sessionId) {
                        errors += "場所ID「${location.id}」が別のセーブで使われています"
                    }
                }
            }
            backup.quests.forEach { quest ->
                db.questDao().getById(quest.id)?.let { existing ->
                    if (existing.sessionId != sessionId) {
                        errors += "クエストID「${quest.id}」が別のセーブで使われています"
                    }
                }
            }
            backup.chat.forEach { message ->
                if (db.chatHistoryDao().countForeign(message.id, sessionId) > 0) {
                    errors += "メッセージID「${message.id}」が別のセーブで使われています"
                }
            }
            backup.memories.forEach { memory ->
                if (db.storyMemoryDao().countForeign(memory.id, sessionId) > 0) {
                    errors += "記憶ID「${memory.id}」が別のセーブで使われています"
                }
            }
            backup.npcMemories.forEach { memory ->
                if (db.npcMemoryDao().countForeign(memory.id, sessionId) > 0) {
                    errors += "NPC記憶ID「${memory.id}」が別のセーブで使われています"
                }
            }
            backup.events.forEach { event ->
                if (db.gameEventDao().countForeign(event.id, sessionId) > 0) {
                    errors += "イベントID「${event.id}」が別のセーブで使われています"
                }
            }
        }
        return errors.take(20)
    }
}
