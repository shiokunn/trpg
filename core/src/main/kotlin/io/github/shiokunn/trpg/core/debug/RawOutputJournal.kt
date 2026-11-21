package io.github.shiokunn.trpg.core.debug

import java.io.File
import java.util.UUID

/**
 * TEMPORARY DEBUG MODE — stores the *raw* model replies of one generation session.
 *
 * This is deliberately separate from [DebugSession]: the normal debug recorder only
 * keeps counts and timings and must never contain generated text. When debug mode is
 * OFF, [begin] returns `null` and no file or directory is created anywhere.
 *
 * One journal corresponds to one logical generation (for world creation: the initial
 * attempt plus its single repair). The two replies go to different files so a
 * truncated initial answer cannot be mistaken for the repair.
 *
 * Layout under the configured root:
 * ```
 * raw/<timestamp>-<sessionId>/initial.txt
 * raw/<timestamp>-<sessionId>/initial.meta.txt
 * raw/<timestamp>-<sessionId>/repair.txt
 * raw/<timestamp>-<sessionId>/repair.meta.txt
 * ```
 *
 * Every write is best-effort: instrumentation must never break generation.
 */
class RawOutputJournal private constructor(private val dir: File) {

    /** Records the first reply of the session. */
    fun writeInitial(
        text: String,
        finishReason: String,
        generatedTokens: Int,
        grammarApplied: Boolean,
    ) = write("initial", text, finishReason, generatedTokens, grammarApplied)

    /** Records the one repair attempt. */
    fun writeRepair(
        text: String,
        finishReason: String,
        generatedTokens: Int,
        grammarApplied: Boolean,
    ) = write("repair", text, finishReason, generatedTokens, grammarApplied)

    /** Absolute directory of this session, for logging/reporting only. */
    val directory: String get() = dir.absolutePath

    private fun write(
        stage: String,
        text: String,
        finishReason: String,
        generatedTokens: Int,
        grammarApplied: Boolean,
    ) {
        runCatching {
            dir.mkdirs()
            File(dir, "$stage.txt").writeText(text)
            File(dir, "$stage.meta.txt").writeText(
                buildString {
                    append("stage=").append(stage).append('\n')
                    append("finishReason=").append(finishReason).append('\n')
                    append("generatedTokens=").append(generatedTokens).append('\n')
                    append("grammarApplied=").append(grammarApplied).append('\n')
                    append("textChars=").append(text.length).append('\n')
                },
            )
        }
    }

    companion object {
        /**
         * Returns a journal for a new generation session, or `null` when debug mode
         * is OFF. [root] is the debug directory the app already owns.
         */
        fun begin(
            root: File?,
            enabled: Boolean,
            label: String,
            now: Long = System.currentTimeMillis(),
            sessionId: String = UUID.randomUUID().toString(),
        ): RawOutputJournal? {
            if (!enabled || root == null) return null
            return runCatching {
                val dir = File(root, "$label-$now-$sessionId")
                RawOutputJournal(dir)
            }.getOrNull()
        }
    }
}
