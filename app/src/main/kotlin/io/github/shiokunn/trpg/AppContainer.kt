package io.github.shiokunn.trpg

import android.content.Context
import io.github.shiokunn.trpg.ai.engine.InferenceEngine
import io.github.shiokunn.trpg.ai.engine.LlamaCppEngine
import io.github.shiokunn.trpg.ai.model.ModelFileManager
import io.github.shiokunn.trpg.backup.BackupFileManager
import io.github.shiokunn.trpg.data.db.AppDatabase
import io.github.shiokunn.trpg.data.repository.BackupRepository
import io.github.shiokunn.trpg.data.repository.CharacterRepository
import io.github.shiokunn.trpg.data.repository.MemoryRepository
import io.github.shiokunn.trpg.data.repository.ModelRepository
import io.github.shiokunn.trpg.data.repository.SessionRepository
import io.github.shiokunn.trpg.data.repository.SettingsRepository
import io.github.shiokunn.trpg.data.repository.WorldRepository
import io.github.shiokunn.trpg.creation.AdventureCreator
import io.github.shiokunn.trpg.game.CombatService
import io.github.shiokunn.trpg.game.GmTurnService

/**
 * Manual dependency container. Keeps Android specifics out of the core module
 * and makes the inference engine replaceable.
 */
class AppContainer(context: Context) {
    val database: AppDatabase = AppDatabase.build(context)
    val worlds = WorldRepository(database)
    val characters = CharacterRepository(database)
    val sessions = SessionRepository(database)
    val memories = MemoryRepository(database)
    val models = ModelRepository(database)
    val settings = SettingsRepository(context)
    val modelFiles = ModelFileManager(context)
    val engine: InferenceEngine by lazy {
        LlamaCppEngine(context.applicationInfo.nativeLibraryDir.orEmpty())
    }
    val gmTurns: GmTurnService by lazy {
        GmTurnService(sessions, memories, models, engine)
    }
    val adventureCreator: AdventureCreator by lazy {
        AdventureCreator(models, engine)
    }
    val combat: CombatService by lazy { CombatService(sessions) }
    val backups = BackupRepository(database)
    val backupFiles = BackupFileManager(context)
    val appContext: Context = context.applicationContext
}
