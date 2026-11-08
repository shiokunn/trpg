package io.github.shiokunn.trpg.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        WorldEntity::class,
        SessionEntity::class,
        CharacterEntity::class,
        SkillEntity::class,
        InventoryItemEntity::class,
        NpcEntity::class,
        NpcMemoryEntity::class,
        LocationEntity::class,
        QuestEntity::class,
        GameEventEntity::class,
        StoryMemoryEntity::class,
        ChatHistoryEntity::class,
        DiceRollEntity::class,
        ModelSettingsEntity::class,
        WorldFlagEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun worldDao(): WorldDao
    abstract fun sessionDao(): SessionDao
    abstract fun characterDao(): CharacterDao
    abstract fun skillDao(): SkillDao
    abstract fun inventoryDao(): InventoryDao
    abstract fun npcDao(): NpcDao
    abstract fun npcMemoryDao(): NpcMemoryDao
    abstract fun locationDao(): LocationDao
    abstract fun questDao(): QuestDao
    abstract fun gameEventDao(): GameEventDao
    abstract fun storyMemoryDao(): StoryMemoryDao
    abstract fun chatHistoryDao(): ChatHistoryDao
    abstract fun diceRollDao(): DiceRollDao
    abstract fun modelSettingsDao(): ModelSettingsDao
    abstract fun worldFlagDao(): WorldFlagDao

    companion object {
        const val NAME = "trpg.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                // Never wipe user saves. Future versions must add explicit migrations.
                .build()
    }
}
