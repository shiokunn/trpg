package io.github.shiokunn.trpg

import android.content.Context
import io.github.shiokunn.trpg.ai.engine.InferenceEngine
import io.github.shiokunn.trpg.ai.engine.LlamaCppEngine
import io.github.shiokunn.trpg.ai.model.ModelFileManager
import io.github.shiokunn.trpg.data.db.AppDatabase
import io.github.shiokunn.trpg.data.repository.CharacterRepository
import io.github.shiokunn.trpg.data.repository.MemoryRepository
import io.github.shiokunn.trpg.data.repository.ModelRepository
import io.github.shiokunn.trpg.data.repository.SessionRepository
import io.github.shiokunn.trpg.data.repository.SettingsRepository
import io.github.shiokunn.trpg.data.repository.WorldRepository

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
    val appContext: Context = context.applicationContext
}
