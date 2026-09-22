package com.yfuse.core.performance

import android.app.Activity
import android.content.ContextWrapper
import android.os.SystemClock
import android.view.Window
import androidx.metrics.performance.FrameData
import androidx.metrics.performance.JankStats
import com.yfuse.core.logging.AppLog

/** Records compact jank summaries without doing disk work for every rendered frame. */
class AppJankMonitor(
    window: Window,
) {
    private val activityContext =
        generateSequence(window.context) { context ->
            (context as? ContextWrapper)?.baseContext?.takeUnless {
                it ===
                    context
            }
        }.take(8)
            .filterIsInstance<Activity>()
            .firstOrNull()
            ?.javaClass
            ?.simpleName
            ?: "unknown"
    private val frames = JankFrameWindow()
    private val stats = JankStats.createAndTrack(window, ::onFrame)

    init {
        stats.isTrackingEnabled = false
    }

    fun start() {
        frames.start(SystemClock.elapsedRealtime(), System.nanoTime())
        stats.isTrackingEnabled = true
    }

    fun stop() {
        stats.isTrackingEnabled = false
        frames.stop(SystemClock.elapsedRealtime())?.let { report(it, "activity_stopped") }
    }

    private fun onFrame(frameData: FrameData) {
        frames
            .record(
                nowMs = SystemClock.elapsedRealtime(),
                frameStartNanos = frameData.frameStartNanos,
                durationNanos = frameData.frameDurationUiNanos,
                isJank = frameData.isJank,
            )?.let { report(it, "interval_elapsed") }
    }

    private fun report(
        summary: JankFrameSummary,
        reason: String,
    ) {
        val attributes =
            mapOf(
                "frames" to summary.jankFrames.toString(),
                "totalFrames" to summary.totalFrames.toString(),
                "windowMs" to summary.windowMs.toString(),
                "longest_ms" to (summary.longestFrameNanos / 1_000_000L).toString(),
                "activity" to activityContext,
                "reason" to reason,
            )
        if (summary.jankFrames > 0) {
            AppLog.warning(
                category = "performance.ui",
                event = "jank_summary",
                message = "Slow UI frames detected",
                attributes = attributes,
            )
        } else {
            AppLog.info(
                category = "performance.ui",
                event = "frame_summary",
                message = "UI frame observation window completed",
                attributes = attributes,
            )
        }
    }
}

internal data class JankFrameSummary(
    val totalFrames: Long,
    val jankFrames: Long,
    val longestFrameNanos: Long,
    val windowMs: Long,
)

/** Callback and lifecycle threads share one atomic window; reporting happens after it is detached. */
internal class JankFrameWindow(
    private val intervalMs: Long = 10_000L,
) {
    private var active = false
    private var activeStartedNanos = 0L
    private var windowStartedMs = 0L
    private var totalFrames = 0L
    private var jankFrames = 0L
    private var longestFrameNanos = 0L

    @Synchronized
    fun start(
        nowMs: Long,
        nowNanos: Long,
    ) {
        if (active) return
        active = true
        activeStartedNanos = nowNanos
        windowStartedMs = nowMs
    }

    @Synchronized
    fun record(
        nowMs: Long,
        frameStartNanos: Long,
        durationNanos: Long,
        isJank: Boolean,
    ): JankFrameSummary? {
        // A queued callback may arrive after stop or even after a subsequent start. Choreographer
        // frame starts and System.nanoTime share the monotonic clock (unlike elapsedRealtime).
        if (!active || frameStartNanos < activeStartedNanos) return null
        totalFrames++
        if (isJank) jankFrames++
        longestFrameNanos = maxOf(longestFrameNanos, durationNanos)
        return if (nowMs - windowStartedMs >= intervalMs) drain(nowMs) else null
    }

    @Synchronized
    fun stop(nowMs: Long): JankFrameSummary? {
        if (!active) return null
        active = false
        return drain(nowMs)
    }

    private fun drain(nowMs: Long): JankFrameSummary? {
        val summary =
            if (totalFrames > 0) {
                JankFrameSummary(
                    totalFrames,
                    jankFrames,
                    longestFrameNanos,
                    (nowMs - windowStartedMs).coerceAtLeast(0L),
                )
            } else {
                null
            }
        totalFrames = 0L
        jankFrames = 0L
        longestFrameNanos = 0L
        windowStartedMs = nowMs
        return summary
    }
}
