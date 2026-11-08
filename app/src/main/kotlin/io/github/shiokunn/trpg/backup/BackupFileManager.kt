package io.github.shiokunn.trpg.backup

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** SAF reader/writer for JSON backups. Bounded so a huge file cannot exhaust memory. */
class BackupFileManager(private val context: Context) {

    suspend fun write(uri: Uri, content: String): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val stream = resolver.openOutputStream(uri, "wt")
                ?: error("保存先を開けませんでした")
            stream.use { output ->
                val bytes = content.toByteArray(Charsets.UTF_8)
                output.write(bytes)
                output.flush()
                bytes.size.toLong()
            }
        }
    }

    suspend fun read(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val stream = context.contentResolver.openInputStream(uri)
                ?: error("ファイルを開けませんでした")
            stream.use { input ->
                val buffer = ByteArray(64 * 1024)
                val output = java.io.ByteArrayOutputStream()
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_BYTES) {
                        error("ファイルが大きすぎます（上限${MAX_BYTES / 1024 / 1024}MB）")
                    }
                    output.write(buffer, 0, read)
                }
                output.toString(Charsets.UTF_8.name())
            }
        }
    }

    companion object {
        const val MAX_BYTES = 50L * 1024 * 1024
        const val MIME_TYPE = "application/json"
    }
}
