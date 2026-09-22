package com.yfuse.core2.android

import com.yfuse.core.logging.AppLog
import java.util.concurrent.atomic.AtomicLong

/** One range attempt, including time blocked inside open/read. Numeric facts only. */
internal class AndroidRangeReadDiagnostics(
    private val sourceTrace: String,
    private val rangeStart: Long,
    private val rangeEnd: Long,
    private val retry: Int,
    private val foreground: Boolean,
    private val sourceInstance: String = "",
) {
    private val id = sequence.incrementAndGet()
    private val startedNs = System.nanoTime()
    private var lastReportNs = startedNs
    val bytes = AtomicLong()

    @Volatile var status = 0

    @Volatile var phase = "opening"
    @Volatile var reason = ""
    @Volatile var queueWaitMs = 0L
    @Volatile var headersMs = -1L
    @Volatile var firstByteMs = -1L

    @Synchronized
    fun tick() {
        val now = System.nanoTime()
        if (now - lastReportNs < 5_000_000_000L) return
        lastReportNs = now
        report(now, "waiting")
    }

    @Synchronized
    fun finish() {
        val now = System.nanoTime()
        if (now - startedNs >= 1_000_000_000L || phase == "failed" || phase == "cancelled") report(now, "finished")
    }

    private fun report(
        nowNs: Long,
        state: String,
    ) {
        val elapsedMs = (nowNs - startedNs) / 1_000_000L
        AppLog.info(
            "player.core2",
            "transport_range_progress",
            "Media range $id $state at ${elapsedMs / 1_000L}s",
            attributes =
                mapOf(
                    "sourceTrace" to sourceTrace,
                    "sourceInstance" to sourceInstance,
                    "rangeId" to id.toString(),
                    "rangeStart" to rangeStart.toString(),
                    "rangeEnd" to rangeEnd.toString(),
                    "retry" to retry.toString(),
                    "foreground" to foreground.toString(),
                    "phase" to phase,
                    "reason" to reason,
                    "queueWaitMs" to queueWaitMs.toString(),
                    "headersMs" to headersMs.toString(),
                    "firstByteMs" to firstByteMs.toString(),
                    "status" to status.toString(),
                    "bytes" to bytes.get().toString(),
                    "elapsedMs" to elapsedMs.toString(),
                ),
        )
    }

    private companion object {
        val sequence = AtomicLong()
    }
}
