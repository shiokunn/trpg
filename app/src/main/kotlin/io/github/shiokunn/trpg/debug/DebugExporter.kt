package io.github.shiokunn.trpg.debug

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.github.shiokunn.trpg.core.debug.DebugJson
import io.github.shiokunn.trpg.core.debug.DebugReport
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TEMPORARY DEBUG MODE — writes the report as JSON and shares it.
 *
 * Destination: app-specific external storage (`Android/data/<pkg>/files/debug`), so no
 * storage permission is required and `adb pull` can retrieve it. The file contains no
 * prompt or generated text (see [DebugReport]).
 */
object DebugExporter {
    const val AUTHORITY_SUFFIX = ".debugfiles"

    fun save(context: Context, report: DebugReport): File {
        val dir = (context.getExternalFilesDir("debug") ?: File(context.filesDir, "debug"))
            .apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(report.exportedAtMs))
        val file = File(dir, "trpg-debug-$stamp.json")
        file.writeText(DebugJson.encode(report))
        return file
    }

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + AUTHORITY_SUFFIX,
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, "デバッグJSONを共有")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
