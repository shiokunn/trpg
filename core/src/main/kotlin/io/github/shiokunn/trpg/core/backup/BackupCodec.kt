package io.github.shiokunn.trpg.core.backup

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

sealed interface BackupDecodeResult {
    data class Success(val bundle: BackupBundle) : BackupDecodeResult
    data class Failure(val errors: List<String>) : BackupDecodeResult
}

/** Strict JSON codec for backups. Unknown keys and unknown enum values are rejected. */
object BackupCodec {
    val json: Json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        explicitNulls = false
    }

    fun encode(bundle: BackupBundle): String = json.encodeToString(BackupBundle.serializer(), bundle)

    fun decode(raw: String): BackupDecodeResult {
        if (raw.isBlank()) return BackupDecodeResult.Failure(listOf("ファイルが空です。"))
        val bundle = try {
            json.decodeFromString(BackupBundle.serializer(), raw)
        } catch (t: SerializationException) {
            return BackupDecodeResult.Failure(listOf("バックアップJSONを解析できません: ${t.message ?: "形式が不正です"}"))
        } catch (t: IllegalArgumentException) {
            return BackupDecodeResult.Failure(listOf("バックアップの値が不正です: ${t.message ?: "形式が不正です"}"))
        }
        val errors = BackupValidator.validate(bundle)
        return if (errors.isEmpty()) {
            BackupDecodeResult.Success(bundle)
        } else {
            BackupDecodeResult.Failure(errors)
        }
    }
}
