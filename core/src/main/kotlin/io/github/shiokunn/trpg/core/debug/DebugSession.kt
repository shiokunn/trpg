package io.github.shiokunn.trpg.core.debug

/**
 * TEMPORARY DEBUG MODE — state machine and aggregation for a single generation.
 *
 * The session is mutated from the inference thread (token callbacks) and from the memory
 * sampler, and read by the UI, so every mutating/reading entry point is synchronized.
 *
 * Live phases are deliberately coarse: PREPARING covers template/tokenize/prefill because
 * the Kotlin side cannot observe those boundaries. The exact split is available from
 * [NativeMetrics] once the generation finishes.
 */
class DebugSession(
    val startedAtMs: Long,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val maxLogEntries: Int = MAX_LOG_ENTRIES,
) {
    enum class Phase {
        PREPARING,
        DECODE,
        COMPLETED,
        CANCELLED,
        ERROR,
    }

    @Synchronized
    fun log(level: DebugLogLevel, message: String) {
        if (logs.size >= maxLogEntries) {
            logs.removeFirst()
        }
        logs.addLast(DebugLogEntry(atMs = clock(), level = level, message = message))
    }

    @Synchronized
    fun onToken() {
        if (outcome != null) return
        val now = clock()
        if (firstTokenAtMs == null) {
            firstTokenAtMs = now
            phase = Phase.DECODE
            lastRateAtMs = now
            lastRateTokens = 0
            log(DebugLogLevel.INFO, "Decode開始（最初のトークンを受信）")
        }
        generatedPieces += 1
        val sinceWindow = generatedPieces - lastRateTokens
        if (sinceWindow >= RATE_WINDOW_TOKENS) {
            val windowMs = now - lastRateAtMs
            if (windowMs > 0) {
                tokenRates += TokenRateSample(
                    atToken = generatedPieces,
                    windowMs = windowMs,
                    tokensPerSecond = sinceWindow * 1000.0 / windowMs,
                )
            }
            lastRateAtMs = now
            lastRateTokens = generatedPieces
        }
    }

    @Synchronized
    fun attachNativeMetrics(raw: String?) {
        val parsed = DebugParsers.parseNativeMetrics(raw)
        nativeMetrics = parsed
        if (parsed == null) {
            log(DebugLogLevel.WARN, "ネイティブ metrics を取得できませんでした（結果JSONに metrics なし）")
        }
    }

    @Synchronized
    fun addMemorySample(sample: MemorySample) {
        memory += sample
        if (memory.size > MAX_MEMORY_SAMPLES) {
            memory.removeAt(0)
        }
    }

    @Synchronized
    fun finish(outcome: DebugOutcome, error: String? = null) {
        if (this.outcome != null) return
        endedAtMs = clock()
        this.outcome = outcome
        this.error = error?.takeIf { it.isNotBlank() }
        phase = when (outcome) {
            DebugOutcome.COMPLETED -> Phase.COMPLETED
            DebugOutcome.CANCELLED -> Phase.CANCELLED
            DebugOutcome.ERROR -> Phase.ERROR
        }
        val label = when (outcome) {
            DebugOutcome.COMPLETED -> "推論完了"
            DebugOutcome.CANCELLED -> "推論キャンセル"
            DebugOutcome.ERROR -> "推論エラー"
        }
        log(
            if (outcome == DebugOutcome.ERROR) DebugLogLevel.ERROR else DebugLogLevel.INFO,
            if (this.error != null) "$label: ${this.error}" else label,
        )
    }

    @Synchronized
    fun snapshot(now: Long = clock()): Snapshot = Snapshot(
        phase = phase,
        startedAtMs = startedAtMs,
        endedAtMs = endedAtMs,
        elapsedMs = (endedAtMs ?: now) - startedAtMs,
        generatedTokens = generatedPieces,
        tokensPerSecond = liveTokensPerSecond(now),
        nativeMetrics = nativeMetrics,
        outcome = outcome,
        error = error,
        memory = memory.toList(),
        tokenRates = tokenRates.toList(),
    )

    @Synchronized
    fun logsSnapshot(): List<DebugLogEntry> = logs.toList()

    @Synchronized
    fun toReport(
        runtime: RuntimeInfo,
        settings: DebugSettingsSnapshot,
        exportedAtMs: Long = clock(),
    ): DebugReport {
        val ended = endedAtMs ?: exportedAtMs
        val metrics = nativeMetrics
        val nativeTokens = metrics?.generatedTokens
        val generated = nativeTokens ?: generatedPieces
        val genMs = metrics?.genMs
        val rate = if (genMs != null && genMs > 0 && generated > 0) {
            generated * 1000.0 / genMs
        } else {
            liveTokensPerSecond(ended)
        }
        return DebugReport(
            exportedAtMs = exportedAtMs,
            startedAtMs = startedAtMs,
            endedAtMs = endedAtMs,
            outcome = outcome ?: DebugOutcome.ERROR,
            error = error,
            runtime = runtime,
            settings = settings,
            metrics = metrics,
            totalMs = ended - startedAtMs,
            generatedTokens = generated,
            tokensPerSecond = rate,
            tokenRates = tokenRates.toList(),
            memory = memory.toList(),
            logs = logs.toList(),
        )
    }

    private fun liveTokensPerSecond(now: Long): Double {
        val first = firstTokenAtMs ?: return 0.0
        if (generatedPieces <= 0) return 0.0
        val end = endedAtMs ?: now
        val span = end - first
        if (span <= 0) return 0.0
        return generatedPieces * 1000.0 / span
    }

    data class Snapshot(
        val phase: Phase,
        val startedAtMs: Long,
        val endedAtMs: Long?,
        val elapsedMs: Long,
        val generatedTokens: Int,
        val tokensPerSecond: Double,
        val nativeMetrics: NativeMetrics?,
        val outcome: DebugOutcome?,
        val error: String?,
        val memory: List<MemorySample>,
        val tokenRates: List<TokenRateSample>,
    )

    private val logs = ArrayDeque<DebugLogEntry>()
    private val memory = ArrayList<MemorySample>()
    private val tokenRates = ArrayList<TokenRateSample>()

    var phase: Phase = Phase.PREPARING
        private set
    var generatedPieces: Int = 0
        private set
    var firstTokenAtMs: Long? = null
        private set
    var endedAtMs: Long? = null
        private set
    var outcome: DebugOutcome? = null
        private set
    var error: String? = null
        private set
    var nativeMetrics: NativeMetrics? = null
        private set

    private var lastRateAtMs = 0L
    private var lastRateTokens = 0

    companion object {
        const val MAX_LOG_ENTRIES = 200
        const val MAX_MEMORY_SAMPLES = 300
        const val RATE_WINDOW_TOKENS = 16
    }
}
