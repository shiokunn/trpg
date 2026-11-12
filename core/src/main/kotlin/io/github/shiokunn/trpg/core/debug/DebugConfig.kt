package io.github.shiokunn.trpg.core.debug

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * TEMPORARY DEBUG MODE.
 *
 * Single in-memory source of truth for the debug panel. Deliberately not persisted:
 * the feature is a one-off investigation aid, and a fresh process must start with
 * debugging disabled. Toggling it never touches the inference engine, the KV cache or
 * an in-flight generation.
 */
object DebugConfig {
    private val _enabled = MutableStateFlow(false)

    /** Observable ON/OFF state for the settings row and the debug screen. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    val isEnabled: Boolean
        get() = _enabled.value

    fun setEnabled(value: Boolean) {
        _enabled.value = value
    }

    fun toggle() {
        _enabled.value = !_enabled.value
    }
}
