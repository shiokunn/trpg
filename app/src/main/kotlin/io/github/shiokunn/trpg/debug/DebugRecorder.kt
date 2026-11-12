package io.github.shiokunn.trpg.debug

import io.github.shiokunn.trpg.BuildConfig
import io.github.shiokunn.trpg.ai.engine.GenerationOutcome
import io.github.shiokunn.trpg.ai.engine.LlamaCppNative
import io.github.shiokunn.trpg.ai.engine.SamplingParams
import io.github.shiokunn.trpg.core.debug.DebugConfig
import io.github.shiokunn.trpg.core.debug.DebugLogEntry
import io.github.shiokunn.trpg.core.debug.DebugLogLevel
import io.github.shiokunn.trpg.core.debug.DebugOutcome
import io.github.shiokunn.trpg.core.debug.DebugParsers
import io.github.shiokunn.trpg.core.debug.DebugReport
import io.github.shiokunn.trpg.core.debug.DebugSession
import io.github.shiokunn.trpg.core.debug.DebugSettingsSnapshot
import io.github.shiokunn.trpg.core.debug.RuntimeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TEMPORARY DEBUG MODE — the only bridge between the inference engine and the debug UI.
 *
 * When [DebugConfig] is OFF, [begin] returns `null` immediately: no session, no memory
 * sampling, no log accumulation and no state flow emission. The engine treats the `null`
 * return as "do nothing", so normal inference is unaffected.
 *
 * The recorder never receives prompt text or generated text: only sizes, counts, timings
 * and process memory.
 */
object DebugRecorder {
    private const val MEMORY_INTERVAL_MS = 2_000L
    private const val STATE_THROTTLE_MS = 250L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()

    private val _state = MutableStateFlow(DebugViewState())
    val state: StateFlow<DebugViewState> = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<DebugLogEntry>>(emptyList())
    val logs: StateFlow<List<DebugLogEntry>> = _logs.asStateFlow()

    @Volatile
    var lastReport: DebugReport? = null
        private set

    private var session: DebugSession? = null
    private var runtimeInfo: RuntimeInfo = RuntimeInfo()
    private var settingsSnapshot: DebugSettingsSnapshot = DebugSettingsSnapshot()
    private var sampler: Job? = null
    private var lastEmitMs = 0L

    fun begin(modelFileName: String, params: SamplingParams, messageCount: Int): Recording? {
        if (!DebugConfig.isEnabled) return null
        return try {
            val now = System.currentTimeMillis()
            val newSession = DebugSession(startedAtMs = now)
            newSession.log(
                DebugLogLevel.INFO,
                "推論開始（messages=$messageCount, maxTokens=${params.maxTokens}）",
            )
            val runtime = loadRuntimeInfo(modelFileName)
            val settings = DebugSettingsSnapshot(
                maxTokens = params.maxTokens,
                temperature = params.temperature,
                topP = params.topP,
                topK = params.topK,
                repeatPenalty = params.repeatPenalty,
                seed = params.seed,
                messageCount = messageCount,
            )
            synchronized(lock) {
                if (session != null) {
                    newSession.log(DebugLogLevel.WARN, "前回のセッションが未完了のまま新しい推論が開始されました")
                }
                session = newSession
                runtimeInfo = runtime
                settingsSnapshot = settings
                lastEmitMs = 0L
                lastReport = null
            }
            emit(newSession, force = true)

            sampler?.cancel()
            sampler = scope.launch {
                while (isActive) {
                    DebugProcessInfo.read()?.let { sample ->
                        val current = synchronized(lock) { session }
                        current?.addMemorySample(sample)
                        if (current != null) emit(current, force = false)
                    }
                    delay(MEMORY_INTERVAL_MS)
                }
            }
            Recording()
        } catch (_: Throwable) {
            // Debug instrumentation must never break inference.
            null
        }
    }

    private fun onToken() {
        val current = synchronized(lock) { session } ?: return
        runCatching {
            current.onToken()
            emit(current, force = false)
        }
    }

    private fun attachResult(raw: String?) {
        val current = synchronized(lock) { session } ?: return
        runCatching { current.attachNativeMetrics(raw) }
    }

    private fun finish(outcome: GenerationOutcome, error: String?) {
        val current = synchronized(lock) { session } ?: return
        val coreOutcome = when (outcome) {
            is GenerationOutcome.Success -> DebugOutcome.COMPLETED
            GenerationOutcome.Cancelled -> DebugOutcome.CANCELLED
            is GenerationOutcome.Failure -> DebugOutcome.ERROR
        }
        val message = when (outcome) {
            is GenerationOutcome.Failure -> outcome.message
            else -> error
        }
        runCatching {
            current.finish(coreOutcome, message)
            sampler?.cancel()
            sampler = null
            DebugProcessInfo.read()?.let { current.addMemorySample(it) }
            val report = current.toReport(runtimeInfo, settingsSnapshot, System.currentTimeMillis())
            lastReport = report
            synchronized(lock) { session = null }
            emit(current, force = true)
        }
    }

    private fun emit(s: DebugSession, force: Boolean) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (!force && now - lastEmitMs < STATE_THROTTLE_MS) return
            lastEmitMs = now
        }
        val snapshot = s.snapshot(now)
        _state.value = DebugViewState(
            phase = snapshot.phase,
            startedAtMs = snapshot.startedAtMs,
            elapsedMs = snapshot.elapsedMs,
            promptTokens = snapshot.nativeMetrics?.promptTokens,
            generatedTokens = snapshot.generatedTokens,
            maxTokens = settingsSnapshot.maxTokens,
            tokensPerSecond = snapshot.tokensPerSecond,
            metrics = snapshot.nativeMetrics,
            runtime = runtimeInfo,
            memory = snapshot.memory,
            tokenRates = snapshot.tokenRates,
            outcome = snapshot.outcome,
            error = snapshot.error,
            hasReport = lastReport != null,
        )
        _logs.value = s.logsSnapshot()
    }

    private fun loadRuntimeInfo(modelFileName: String): RuntimeInfo {
        val base = if (LlamaCppNative.isInitialized()) {
            runCatching {
                DebugParsers.parseRuntimeInfo(LlamaCppNative.nativeRuntimeInfo())
            }.getOrNull()
        } else {
            null
        }
        val backend = if (LlamaCppNative.isInitialized()) {
            runCatching {
                LlamaCppNative.parseBackendInfo(LlamaCppNative.nativeBackendInfo())
            }.getOrNull()
        } else {
            null
        }
        val info = base ?: RuntimeInfo()
        return info.copy(
            appVersion = BuildConfig.VERSION_NAME,
            modelFileName = modelFileName.ifBlank { info.modelFileName },
            backends = backend?.backends ?: info.backends,
            gpuOffload = backend?.gpuOffload ?: info.gpuOffload,
        )
    }

    /** Handle returned to the engine; all calls are no-ops once finished. */
    class Recording internal constructor() {
        private val finished = AtomicBoolean(false)

        fun onToken() {
            if (!finished.get()) DebugRecorder.onToken()
        }

        fun attachResult(raw: String?) {
            if (!finished.get()) DebugRecorder.attachResult(raw)
        }

        fun finish(outcome: GenerationOutcome, error: String? = null) {
            if (finished.compareAndSet(false, true)) {
                DebugRecorder.finish(outcome, error)
            }
        }
    }
}
