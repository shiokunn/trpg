package io.github.shiokunn.trpg.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Character chat lives in its own database so the game saves in trpg.db are never
 * touched by this feature (no migration of the existing schema is needed).
 */
@Database(
    entities = [ChatCharacterEntity::class, ChatTurnEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        const val NAME = "chat.db"

        fun build(context: Context): ChatDatabase =
            Room.databaseBuilder(context.applicationContext, ChatDatabase::class.java, NAME).build()
    }
}

@Entity(tableName = "chat_characters")
data class ChatCharacterEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val personality: String,
    val scenario: String,
    val firstMessage: String,
    val avatarPath: String?,
    val createdAt: Long,
)

@Entity(
    tableName = "chat_turns",
    foreignKeys = [
        ForeignKey(
            entity = ChatCharacterEntity::class,
            parentColumns = ["id"],
            childColumns = ["characterId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("characterId")],
)
data class ChatTurnEntity(
    @PrimaryKey val id: String,
    val characterId: String,
    val role: String,
    val content: String,
    val createdAt: Long,
)

@Dao
interface ChatDao {
    @Upsert
    suspend fun upsertCharacter(character: ChatCharacterEntity)

    @Query("SELECT * FROM chat_characters ORDER BY createdAt DESC")
    fun observeCharacters(): Flow<List<ChatCharacterEntity>>

    @Delete
    suspend fun deleteCharacter(character: ChatCharacterEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTurn(turn: ChatTurnEntity)

    @Query("SELECT * FROM chat_turns WHERE characterId = :characterId ORDER BY createdAt ASC")
    fun observeTurns(characterId: String): Flow<List<ChatTurnEntity>>

    @Query("DELETE FROM chat_turns WHERE characterId = :characterId")
    suspend fun clearTurns(characterId: String)
}
