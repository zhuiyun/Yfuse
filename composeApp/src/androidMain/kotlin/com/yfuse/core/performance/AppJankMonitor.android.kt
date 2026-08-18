package com.yfuse.core.performance

import android.os.SystemClock
import android.view.Window
import androidx.metrics.performance.FrameData
import androidx.metrics.performance.JankStats
import com.yfuse.core.logging.AppLog

/** Records compact jank summaries without doing disk work for every rendered frame. */
class AppJankMonitor(
    window: Window,
) {
    private var jankFrames = 0
    private var longestFrameNanos = 0L
    private var reportWindowStartedMs = SystemClock.elapsedRealtime()
    private val stats = JankStats.createAndTrack(window, ::onFrame)

    init {
        stats.isTrackingEnabled = false
    }

    fun start() {
        reportWindowStartedMs = SystemClock.elapsedRealtime()
        stats.isTrackingEnabled = true
    }

    fun stop() {
        stats.isTrackingEnabled = false
        report("activity_stopped")
    }

    private fun onFrame(frameData: FrameData) {
        if (!frameData.isJank) return
        jankFrames += 1
        longestFrameNanos = maxOf(longestFrameNanos, frameData.frameDurationUiNanos)
        if (SystemClock.elapsedRealtime() - reportWindowStartedMs >= REPORT_INTERVAL_MS) {
            report("interval_elapsed")
        }
    }

    private fun report(reason: String) {
        if (jankFrames > 0) {
            AppLog.warning(
                category = "performance.ui",
                event = "jank_summary",
                message = "Slow UI frames detected",
                attributes =
                    mapOf(
                        "frames" to jankFrames.toString(),
                        "longest_ms" to (longestFrameNanos / NANOS_PER_MILLISECOND).toString(),
                        "reason" to reason,
                    ),
            )
        }
        jankFrames = 0
        longestFrameNanos = 0L
        reportWindowStartedMs = SystemClock.elapsedRealtime()
    }

    private companion object {
        const val REPORT_INTERVAL_MS = 10_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
