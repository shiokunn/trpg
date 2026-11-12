package io.github.shiokunn.trpg.debug

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.shiokunn.trpg.core.debug.DebugLogEntry
import io.github.shiokunn.trpg.core.debug.DebugReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** TEMPORARY DEBUG MODE — export progress for the debug screen. */
sealed interface DebugExportState {
    data object Idle : DebugExportState
    data object Running : DebugExportState
    data class Saved(val file: File) : DebugExportState
    data class Failed(val message: String) : DebugExportState
}

/** TEMPORARY DEBUG MODE — screen state holder. It only reads [DebugRecorder]. */
class DebugViewModel : ViewModel() {
    val state: StateFlow<DebugViewState> = DebugRecorder.state
    val logs: StateFlow<List<DebugLogEntry>> = DebugRecorder.logs

    private val _export = MutableStateFlow<DebugExportState>(DebugExportState.Idle)
    val export: StateFlow<DebugExportState> = _export.asStateFlow()

    fun export(context: Context) {
        val report: DebugReport = DebugRecorder.lastReport ?: return
        if (_export.value is DebugExportState.Running) return
        _export.value = DebugExportState.Running
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { DebugExporter.save(context, report) }
            }.onSuccess { file ->
                _export.value = DebugExportState.Saved(file)
            }.onFailure { error ->
                _export.value = DebugExportState.Failed(error.message ?: "JSONの保存に失敗しました")
            }
        }
    }

    fun share(context: Context) {
        val saved = _export.value as? DebugExportState.Saved ?: return
        runCatching { DebugExporter.share(context, saved.file) }
            .onFailure { error ->
                _export.value = DebugExportState.Failed(error.message ?: "共有に失敗しました")
            }
    }

    fun clearMessage() {
        if (_export.value !is DebugExportState.Running) {
            _export.value = DebugExportState.Idle
        }
    }
}
