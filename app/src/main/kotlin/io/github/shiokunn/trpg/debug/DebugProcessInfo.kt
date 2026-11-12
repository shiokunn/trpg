package io.github.shiokunn.trpg.debug

import io.github.shiokunn.trpg.core.debug.MemorySample
import java.io.File

/**
 * TEMPORARY DEBUG MODE — reads VmRSS / VmSwap for this process.
 *
 * Reading `/proc/self/status` never needs a permission and never throws: any failure
 * returns `null` so the inference path keeps running.
 */
object DebugProcessInfo {
    fun read(nowMs: Long = System.currentTimeMillis()): MemorySample? = runCatching {
        val file = File("/proc/self/status")
        if (!file.canRead()) return null
        var rss: Long? = null
        var swap: Long? = null
        file.forEachLine { line ->
            when {
                line.startsWith("VmRSS:") -> rss = line.valueKb()
                line.startsWith("VmSwap:") -> swap = line.valueKb()
            }
        }
        if (rss == null && swap == null) {
            null
        } else {
            MemorySample(atMs = nowMs, vmRssKb = rss, vmSwapKb = swap)
        }
    }.getOrNull()

    private fun String.valueKb(): Long? =
        removePrefix("VmRSS:").removePrefix("VmSwap:")
            .trim()
            .substringBefore(' ')
            .toLongOrNull()
}
