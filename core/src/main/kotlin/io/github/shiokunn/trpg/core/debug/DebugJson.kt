package io.github.shiokunn.trpg.core.debug

import kotlinx.serialization.json.Json

/**
 * TEMPORARY DEBUG MODE — JSON encoding for the exported report.
 *
 * The report models contain no prompt text or generated text, so encoding it cannot leak
 * conversation content. `prettyPrint` keeps the file readable when pulled from the device.
 */
object DebugJson {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(report: DebugReport): String = json.encodeToString(DebugReport.serializer(), report)

    fun decode(text: String): DebugReport = json.decodeFromString(DebugReport.serializer(), text)
}
