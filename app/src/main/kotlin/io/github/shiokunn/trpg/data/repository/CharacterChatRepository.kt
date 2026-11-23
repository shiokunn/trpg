package io.github.shiokunn.trpg.data.repository

import io.github.shiokunn.trpg.core.card.CharacterCard
import io.github.shiokunn.trpg.data.db.ChatCharacterEntity
import io.github.shiokunn.trpg.data.db.ChatDatabase
import io.github.shiokunn.trpg.data.db.ChatTurnEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.UUID

data class ChatCharacter(
    val id: String,
    val name: String,
    val description: String,
    val personality: String,
    val scenario: String,
    val firstMessage: String,
    val avatarPath: String?,
) {
    val systemPrompt: String
        get() = CharacterCard.Parsed(
            name = name,
            description = description,
            personality = personality,
            scenario = scenario,
            firstMessage = firstMessage,
        ).systemPrompt
}

data class ChatTurn(
    val id: String,
    val characterId: String,
    val role: String,
    val content: String,
    val createdAt: Long,
)

/** Stores imported character cards and their conversations. */
class CharacterChatRepository(
    private val database: ChatDatabase,
    filesDir: File,
) {
    private val dao = database.chatDao()
    private val avatarDir = File(filesDir, "chat_avatars").apply { mkdirs() }

    fun observeCharacters(): Flow<List<ChatCharacter>> = dao.observeCharacters().map { rows ->
        rows.map { row ->
            ChatCharacter(
                id = row.id,
                name = row.name,
                description = row.description,
                personality = row.personality,
                scenario = row.scenario,
                firstMessage = row.firstMessage,
                avatarPath = row.avatarPath?.takeIf { File(it).isFile },
            )
        }
    }

    fun observeTurns(characterId: String): Flow<List<ChatTurn>> =
        dao.observeTurns(characterId).map { rows ->
            rows.map { row ->
                ChatTurn(row.id, row.characterId, row.role, row.content, row.createdAt)
            }
        }

    /** Accepts a Character Card V2/V3 JSON or a PNG with the card in a text chunk. */
    suspend fun importCard(bytes: ByteArray, displayName: String): ChatCharacter? {
        val png = bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()
        val card = if (png) {
            CharacterCard.parsePng(bytes)
        } else {
            CharacterCard.parseJson(bytes.decodeToString())
        } ?: return null

        val avatar = (card.avatarBytes ?: if (png) bytes else null)
        return saveCharacter(card, avatar, displayName.substringBeforeLast('.'))
    }

    /** Saves an AI-generated or hand-written card (no image required). */
    suspend fun saveCharacter(
        card: CharacterCard.Parsed,
        avatarBytes: ByteArray? = null,
        fallbackName: String = "",
    ): ChatCharacter {
        val id = UUID.randomUUID().toString()
        val avatarPath = avatarBytes?.let { image ->
            val file = File(avatarDir, "$id.png")
            runCatching { file.writeBytes(image); file.absolutePath }.getOrNull()
        }
        val entity = ChatCharacterEntity(
            id = id,
            name = card.name.ifBlank { fallbackName }.take(200),
            description = card.description,
            personality = card.personality,
            scenario = card.scenario,
            firstMessage = card.firstMessage,
            avatarPath = avatarPath,
            createdAt = System.currentTimeMillis(),
        )
        dao.upsertCharacter(entity)
        if (card.firstMessage.isNotBlank()) {
            dao.insertTurn(
                ChatTurnEntity(
                    id = UUID.randomUUID().toString(),
                    characterId = id,
                    role = "assistant",
                    content = card.firstMessage,
                    createdAt = System.currentTimeMillis() + 1,
                ),
            )
        }
        return entity.toDomain()
    }

    suspend fun addTurn(characterId: String, role: String, content: String): ChatTurn {
        val turn = ChatTurnEntity(
            id = UUID.randomUUID().toString(),
            characterId = characterId,
            role = role,
            content = content,
            createdAt = System.currentTimeMillis(),
        )
        dao.insertTurn(turn)
        return ChatTurn(turn.id, turn.characterId, turn.role, turn.content, turn.createdAt)
    }

    suspend fun countTurns(characterId: String): Int = dao.countTurns(characterId)

    suspend fun clearTurns(characterId: String) = dao.clearTurns(characterId)

    suspend fun deleteCharacter(character: ChatCharacter) {
        clearTurns(character.id)
        dao.deleteCharacter(
            ChatCharacterEntity(
                id = character.id,
                name = character.name,
                description = character.description,
                personality = character.personality,
                scenario = character.scenario,
                firstMessage = character.firstMessage,
                avatarPath = character.avatarPath,
                createdAt = 0L,
            ),
        )
        character.avatarPath?.let { runCatching { File(it).delete() } }
    }

    private fun ChatCharacterEntity.toDomain() = ChatCharacter(
        id = id,
        name = name,
        description = description,
        personality = personality,
        scenario = scenario,
        firstMessage = firstMessage,
        avatarPath = avatarPath?.takeIf { File(it).isFile },
    )
}
