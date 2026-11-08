package io.github.shiokunn.trpg.ai.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.parseToJsonElement

/** Callback invoked from the native decode loop for every generated piece. */
fun interface TokenCallback {
    fun onToken(token: String)
}

internal data class NativeGeneration(
    val text: String,
    val cancelled: Boolean,
    val error: String?,
)

/**
 * Thin JNI bridge. Every method maps 1:1 to `app/src/main/cpp/llama_jni.cpp`.
 * The library is loaded lazily so a build without native code still starts and can
 * explain the problem in the UI instead of crashing at launch.
 */
internal object LlamaCppNative {
    private val loadLock = Any()
    private var initialized = false

    fun ensureInitialized(nativeLibraryDir: String) {
        synchronized(loadLock) {
            if (initialized) return
            System.loadLibrary("trpg_llama")
            nativeInit(nativeLibraryDir)
            initialized = true
        }
    }

    fun isInitialized(): Boolean = synchronized(loadLock) { initialized }

    external fun nativeInit(nativeLibraryDir: String)

    external fun nativeLoadModel(
        path: String,
        nCtx: Int,
        nThreads: Int,
        nGpuLayers: Int,
    ): String?

    external fun nativeUnloadModel()

    external fun nativeFormatChat(
        roles: Array<String>,
        contents: Array<String>,
        addAssistant: Boolean,
    ): String?

    external fun nativeCountTokens(text: String): Int

    external fun nativeGenerate(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float,
        seed: Int,
        callback: TokenCallback?,
    ): String?

    external fun nativeCancel()

    external fun nativeBackendInfo(): String?

    external fun nativeModelInfo(): String?

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parseModelInfo(raw: String?): ModelInfo? {
        if (raw.isNullOrBlank()) return null
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        if (obj.boolean("ok") == false) return null
        return ModelInfo(
            description = obj.string("description") ?: "",
            nParams = obj.long("nParams") ?: 0L,
            sizeBytes = obj.long("sizeBytes") ?: 0L,
            nCtxTrain = obj.int("nCtxTrain") ?: 0,
            nCtx = obj.int("nCtx") ?: 0,
            hasChatTemplate = obj.boolean("hasChatTemplate") ?: false,
        )
    }

    fun parseGeneration(raw: String?): NativeGeneration {
        if (raw.isNullOrBlank()) {
            return NativeGeneration("", false, "推論エンジンから応答がありませんでした")
        }
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return NativeGeneration("", false, "推論結果を解析できませんでした")
        val error = obj.string("error")?.takeIf { it.isNotBlank() }
            ?: if (obj.boolean("ok") == false) "推論に失敗しました" else null
        return NativeGeneration(
            text = obj.string("text") ?: "",
            cancelled = obj.boolean("cancelled") ?: false,
            error = error,
        )
    }

    fun parseError(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            json.parseToJsonElement(raw).jsonObject.string("error")?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun parseBackendInfo(raw: String?): BackendInfo {
        if (raw.isNullOrBlank()) {
            return BackendInfo(system = "ネイティブエンジンから情報を取得できませんでした", available = false)
        }
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return BackendInfo(system = raw, available = false)
        val backends = (obj["backends"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: emptyList()
        return BackendInfo(
            system = obj.string("system") ?: "",
            backends = backends,
            gpuOffload = obj.boolean("gpuOffload") ?: false,
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.boolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull
}
