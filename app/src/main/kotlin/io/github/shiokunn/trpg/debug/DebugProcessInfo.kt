package io.github.shiokunn.trpg.debug

import io.github.shiokunn.trpg.core.debug.MemorySample
import java.io.File

/**
 * TEMPORARY DEBUG MODE — reads VmRSS / VmSwap for this process and the device-wide
 * MemAvailable / SwapFree from /proc/meminfo.
 *
 * Reading `/proc` never needs a permission and never throws: any failure returns
 * `null` so the inference path keeps running.
 */
object DebugProcessInfo {
    fun read(nowMs: Long = System.currentTimeMillis()): MemorySample? = runCatching {
        val rss = readKb("/proc/self/status", "VmRSS")
        val swap = readKb("/proc/self/status", "VmSwap")
        val memAvailable = readKb("/proc/meminfo", "MemAvailable")
        val swapFree = readKb("/proc/meminfo", "SwapFree")
        if (rss == null && swap == null && memAvailable == null && swapFree == null) {
            null
        } else {
            MemorySample(
                atMs = nowMs,
                vmRssKb = rss,
                vmSwapKb = swap,
                memAvailableKb = memAvailable,
                swapFreeKb = swapFree,
            )
        }
    }.getOrNull()

    private fun readKb(path: String, key: String): Long? = runCatching {
        val file = File(path)
        if (!file.canRead()) return@runCatching null
        file.useLines { lines ->
            lines.firstOrNull { it.startsWith("$key:") }
                ?.removePrefix("$key:")
                ?.trim()
                ?.substringBefore(' ')
                ?.toLongOrNull()
        }
    }.getOrNull()
}
