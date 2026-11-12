package io.github.shiokunn.trpg.core.debug

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * TEMPORARY DEBUG MODE — defensive parsers for the native JSON payloads.
 *
 * Native returns either a generation result that may contain a `metrics` object, or the
 * runtime info document. Both are parsed with `ignoreUnknownKeys`; anything missing stays
 * `null` / 取得不可.
 */
object DebugParsers {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parseNativeMetrics(raw: String?): NativeMetrics? {
        if (raw.isNullOrBlank()) return null
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val obj = root["metrics"] as? JsonObject ?: return null
        return NativeMetrics(
            applyMs = obj.nonNegativeLong("applyMs"),
            tokenizeMs = obj.nonNegativeLong("tokenizeMs"),
            clearMs = obj.nonNegativeLong("clearMs"),
            promptMs = obj.nonNegativeLong("promptMs"),
            genMs = obj.nonNegativeLong("genMs"),
            ttftMs = obj.nonNegativeLong("ttftMs"),
            promptTokens = obj.nonNegativeInt("promptTokens"),
            generatedTokens = obj.nonNegativeInt("generatedTokens"),
            budget = obj.nonNegativeInt("budget"),
            threads = obj.nonNegativeInt("threads"),
            nBatch = obj.nonNegativeInt("nBatch"),
        )
    }

    fun parseRuntimeInfo(raw: String?): RuntimeInfo? {
        if (raw.isNullOrBlank()) return null
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val backends = runCatching {
            (obj["backends"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?: emptyList()
        }.getOrDefault(emptyList())
        return RuntimeInfo(
            appVersion = obj.string("appVersion") ?: "取得不可",
            llamaVersion = obj.string("llamaVersion"),
            llamaCommit = obj.string("llamaCommit"),
            modelFileName = obj.string("modelFileName"),
            modelLoaded = obj.boolean("modelLoaded"),
            nCtx = obj.nonNegativeInt("nCtx"),
            nCtxRequested = obj.nonNegativeInt("nCtxRequested"),
            nCtxTrain = obj.nonNegativeInt("nCtxTrain"),
            nBatch = obj.nonNegativeInt("nBatch"),
            nBatchRequested = obj.nonNegativeInt("nBatchRequested"),
            nUbatch = obj.nonNegativeInt("nUbatch"),
            nUbatchRequested = obj.nonNegativeInt("nUbatchRequested"),
            nSeqMax = obj.nonNegativeInt("nSeqMax"),
            nThreads = obj.nonNegativeInt("nThreads"),
            nThreadsRequested = obj.nonNegativeInt("nThreadsRequested"),
            nThreadsBatch = obj.nonNegativeInt("nThreadsBatch"),
            threadpool = obj.string("threadpool"),
            allowedCpus = obj.string("allowedCpus"),
            flashAttn = obj.string("flashAttn"),
            kvTypeK = obj.string("kvTypeK"),
            kvTypeV = obj.string("kvTypeV"),
            gpuLayers = obj.intOrNull("gpuLayers"),
            backends = backends,
            gpuOffload = obj.boolean("gpuOffload"),
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.boolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.intOrNull(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.nonNegativeInt(key: String): Int? =
        intOrNull(key)?.takeIf { it >= 0 }

    private fun JsonObject.nonNegativeLong(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
}
