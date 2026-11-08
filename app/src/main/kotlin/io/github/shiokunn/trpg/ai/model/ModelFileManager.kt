package io.github.shiokunn.trpg.ai.model

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale

/** Imported GGUF models live in app-private storage so no storage permission is needed. */
class ModelFileManager(private val context: Context) {

    data class ModelFile(
        val fileName: String,
        val displayName: String,
        val sizeBytes: Long,
        val lastModified: Long,
    )

    val modelsDirectory: File
        get() = File(context.filesDir, "models")

    fun listModels(): List<ModelFile> {
        val dir = modelsDirectory
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".gguf", ignoreCase = true) }
            ?.map { it.toModelFile() }
            ?.sortedBy { it.displayName.lowercase(Locale.ROOT) }
            ?: emptyList()
    }

    fun resolve(fileName: String): File? {
        if (fileName.isBlank()) return null
        return runCatching {
            val dir = modelsDirectory.canonicalFile
            val file = File(dir, fileName).canonicalFile
            if (file.parentFile == dir && file.isFile) file else null
        }.getOrNull()
    }

    fun freeBytes(): Long {
        val dir = modelsDirectory
        return if (dir.isDirectory) dir.usableSpace else context.filesDir.usableSpace
    }

    /**
     * Copies the picked document into private storage with progress reporting.
     * The temporary `.part` file never appears in the model list, so a cancelled or
     * failed import leaves the existing library untouched.
     */
    suspend fun importModel(
        uri: Uri,
        onProgress: (copied: Long, total: Long) -> Unit = { _, _ -> },
    ): Result<ModelFile> = withContext(Dispatchers.IO) {
        val dir = modelsDirectory
        if (!dir.isDirectory && !dir.mkdirs()) {
            return@withContext Result.failure(IOException("モデル保存用フォルダを作成できませんでした"))
        }

        var temp: File? = null
        try {
            val displayName = queryDisplayName(uri) ?: "model-${System.currentTimeMillis()}.gguf"
            val fileName = uniqueFileName(sanitizeFileName(displayName))
            val total = querySize(uri)

            if (total > 0) {
                val required = total + RESERVE_BYTES
                val free = dir.usableSpace
                if (free < required) {
                    return@withContext Result.failure(
                        IOException(
                            "空き容量が不足しています（必要: ${formatFileSize(required)} / 空き: ${formatFileSize(free)}）",
                        ),
                    )
                }
            }

            val part = File(dir, "$fileName.part")
            temp = part
            part.delete()

            val source = context.contentResolver.openInputStream(uri)
                ?: return@withContext Result.failure(IOException("選択したファイルを開けませんでした"))

            source.use { input ->
                FileOutputStream(part).use { output ->
                    val buffer = ByteArray(COPY_BUFFER)
                    var copied = 0L
                    var lastReport = 0L
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (copied - lastReport >= REPORT_INTERVAL) {
                            lastReport = copied
                            onProgress(copied, total)
                        }
                    }
                    output.flush()
                    output.fd.sync()
                    onProgress(copied, if (total > 0) total else copied)
                }
            }

            if (!isGguf(part)) {
                part.delete()
                temp = null
                return@withContext Result.failure(
                    IOException("選択したファイルはGGUF形式ではありません。GGUFモデルを選んでください"),
                )
            }

            val target = File(dir, fileName)
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            temp = null
            Result.success(target.toModelFile())
        } catch (t: Throwable) {
            temp?.delete()
            if (t is CancellationException) throw t
            Result.failure(t)
        }
    }

    suspend fun delete(fileName: String): Result<Unit> = withContext(Dispatchers.IO) {
        val file = resolve(fileName)
            ?: return@withContext Result.failure(IOException("モデルファイルが見つかりません"))
        if (file.delete()) Result.success(Unit)
        else Result.failure(IOException("モデルファイルを削除できませんでした"))
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun querySize(uri: Uri): Long = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
        } ?: -1L
    }.getOrDefault(-1L)

    private fun sanitizeFileName(raw: String): String {
        val leaf = raw.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = leaf
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim()
            .trimEnd('.')
            .take(120)
        val stem = if (cleaned.endsWith(".gguf", ignoreCase = true)) {
            cleaned.dropLast(5)
        } else {
            cleaned
        }
        return (stem.ifBlank { "model-${System.currentTimeMillis()}" }) + ".gguf"
    }

    private fun uniqueFileName(fileName: String): String {
        val dir = modelsDirectory
        if (!File(dir, fileName).exists()) return fileName
        val stem = fileName.removeSuffix(".gguf")
        var index = 2
        while (true) {
            val candidate = "$stem ($index).gguf"
            if (!File(dir, candidate).exists()) return candidate
            index += 1
        }
    }

    private fun isGguf(file: File): Boolean = runCatching {
        FileInputStream(file).use { input ->
            val magic = ByteArray(4)
            input.read(magic) == 4 &&
                magic[0] == 'G'.code.toByte() &&
                magic[1] == 'G'.code.toByte() &&
                magic[2] == 'U'.code.toByte() &&
                magic[3] == 'F'.code.toByte()
        }
    }.getOrDefault(false)

    private fun File.toModelFile(): ModelFile = ModelFile(
        fileName = name,
        displayName = name.removeSuffix(".gguf"),
        sizeBytes = length(),
        lastModified = lastModified(),
    )

    companion object {
        /** Keep enough free space for the runtime and database after importing. */
        private const val RESERVE_BYTES = 256L * 1024 * 1024
        private const val COPY_BUFFER = 1024 * 1024
        private const val REPORT_INTERVAL = 4L * 1024 * 1024
    }
}

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "不明"
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index += 1
    }
    return String.format(Locale.US, "%.1f %s", value, units[index])
}
