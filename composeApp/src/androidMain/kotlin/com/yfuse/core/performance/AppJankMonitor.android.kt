package com.yfuse.core.performance

import android.app.Activity
import android.content.ContextWrapper
import android.os.Build
import android.os.SystemClock
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.metrics.performance.FrameData
import androidx.metrics.performance.FrameDataApi31
import androidx.metrics.performance.JankStats
import androidx.metrics.performance.PerformanceMetricsState
import com.yfuse.core.logging.AppLog
import com.yfuse.core.logging.DiagnosticLogStore
import java.util.Locale
import kotlin.math.ceil

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
    private val overrun = FrameOverrunRegistry.statsFor(activityContext)

    // Tracking starts inside createAndTrack and frames arrive on JankStats' own thread before
    // init turns it off: everything onFrame reads is initialised above this line.
    private val stats = JankStats.createAndTrack(window, ::onFrame)

    // JankStats installed this window's state holder above; JankStatsState writes through it.
    private val metricsState = PerformanceMetricsState.getHolderForHierarchy(window.decorView)
    private val taggedKeys = LinkedHashSet<String>()
    private val stateSink =
        object : JankStatsState.Sink {
            override fun put(
                key: String,
                value: String,
            ) {
                synchronized(taggedKeys) { taggedKeys += key }
                metricsState.state?.putState(key, value)
            }

            override fun remove(key: String) {
                synchronized(taggedKeys) { taggedKeys -= key }
                metricsState.state?.removeState(key)
            }
        }

    init {
        stats.isTrackingEnabled = false
    }

    fun start() {
        frames.start(SystemClock.elapsedRealtime(), System.nanoTime())
        stats.isTrackingEnabled = true
        JankStatsState.attach(stateSink)
    }

    fun stop() {
        JankStatsState.detach(stateSink)
        // A gesture still running when the window stopped can no longer reach it to remove its
        // state; left in place, every frame of the next visit would be filed under it.
        val unfinished = synchronized(taggedKeys) { taggedKeys.toList().also { taggedKeys.clear() } }
        unfinished.forEach { key -> metricsState.state?.removeState(key) }
        stats.isTrackingEnabled = false
        frames.stop(SystemClock.elapsedRealtime())?.let { report(it, "activity_stopped") }
    }

    private fun onFrame(frameData: FrameData) {
        // Overrun is measured against the frame's own deadline through the whole pipeline
        // (UI thread, RenderThread and GPU), which UI-thread duration alone cannot show.
        if (frameData is FrameDataApi31) {
            val overrunNanos = frameData.frameOverrunNanos
            overrun.record(overrunNanos)
            // Indexed: this runs for every frame and must not allocate an iterator.
            val states = frameData.states
            for (index in states.indices) {
                val state = states[index]
                overrun.record(state.key, state.value, overrunNanos)
            }
        }
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

    companion object {
        /**
         * Tracks [activity]'s window while it is started, for the rest of its life: one call from
         * onCreate after setContent (JankStats needs the decor view). Returns null, after logging
         * why, when JankStats cannot attach; the activity works the same either way.
         */
        fun attach(activity: ComponentActivity): AppJankMonitor? {
            val monitor =
                runCatching { AppJankMonitor(activity.window) }
                    .onFailure { error ->
                        AppLog.warning(
                            category = "performance.ui",
                            event = "jank_monitor_unavailable",
                            message = "JankStats could not attach to the activity window",
                            throwable = error,
                            attributes = mapOf("activity" to activity.javaClass.simpleName),
                        )
                    }.getOrNull() ?: return null
            // Added late, the observer is still replayed ON_START when the activity already is.
            activity.lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_START -> monitor.start()
                        Lifecycle.Event.ON_STOP -> monitor.stop()
                        else -> Unit
                    }
                },
            )
            return monitor
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

/** One row of the frame-overrun report. Overrun is frame end minus deadline; negative met it. */
internal data class FrameOverrunSummary(
    /** `null` for every frame of the window, otherwise the JankStats state as `key:value`. */
    val state: String?,
    val frames: Long,
    val missedDeadline: Long,
    val kept: Int,
    val p50Nanos: Long,
    val p90Nanos: Long,
    val p99Nanos: Long,
    val worstNanos: Long,
)

/**
 * Frame overrun of one window, overall and per JankStats state.
 *
 * Each row keeps its most recent [samplesPerRow] overruns, so the percentiles describe the latest
 * frames spent in that state rather than a whole session's average, plus lifetime frame and
 * missed-deadline counts. Past [maxStateRows] distinct states new ones are only counted.
 * The frame callback and the export thread both use it.
 */
internal class FrameOverrunStats(
    private val samplesPerRow: Int = 600,
    private val maxStateRows: Int = 32,
) {
    private class Row(
        capacity: Int,
    ) {
        val samples = LongArray(capacity)
        var next = 0
        var size = 0
        var frames = 0L
        var missed = 0L
        var worst = Long.MIN_VALUE

        fun add(overrunNanos: Long) {
            samples[next] = overrunNanos
            next = (next + 1) % samples.size
            if (size < samples.size) size++
            frames++
            if (overrunNanos > 0L) missed++
            if (overrunNanos > worst) worst = overrunNanos
        }

        fun summary(state: String?): FrameOverrunSummary {
            val sorted = samples.copyOf(size).apply { sort() }
            return FrameOverrunSummary(
                state = state,
                frames = frames,
                missedDeadline = missed,
                kept = size,
                p50Nanos = sorted.nearestRank(50),
                p90Nanos = sorted.nearestRank(90),
                p99Nanos = sorted.nearestRank(99),
                worstNanos = worst,
            )
        }
    }

    private val all = Row(samplesPerRow)

    // key -> value -> row, so a frame's states are looked up without building a label.
    private val byState = HashMap<String, HashMap<String, Row>>()
    private var stateRows = 0
    private var untrackedStateFrames = 0L

    @Synchronized
    fun record(overrunNanos: Long) = all.add(overrunNanos)

    @Synchronized
    fun record(
        key: String,
        value: String,
        overrunNanos: Long,
    ) {
        val values = byState.getOrPut(key) { HashMap() }
        val row =
            values[value]
                ?: if (stateRows < maxStateRows) {
                    stateRows++
                    Row(samplesPerRow).also { values[value] = it }
                } else {
                    untrackedStateFrames++
                    return
                }
        row.add(overrunNanos)
    }

    /** Overall row first (when any frame was seen), then states by key and value. */
    @Synchronized
    fun snapshot(): List<FrameOverrunSummary> =
        buildList {
            if (all.frames > 0L) add(all.summary(null))
            byState.toSortedMap().forEach { (key, values) ->
                values.toSortedMap().forEach { (value, row) -> add(row.summary("$key:$value")) }
            }
        }

    @Synchronized
    fun untrackedStateFrames(): Long = untrackedStateFrames
}

/** Nearest-rank percentile of an ascending array; 0 when it is empty. */
internal fun LongArray.nearestRank(percent: Int): Long {
    if (isEmpty()) return 0L
    val rank = ceil(percent / 100.0 * size).toInt().coerceIn(1, size)
    return this[rank - 1]
}

/** Every monitored window's overrun, rendered into the diagnostic export as `reports/ui-frame-overrun.txt`. */
internal object FrameOverrunRegistry {
    private val windows = LinkedHashMap<String, FrameOverrunStats>()

    init {
        DiagnosticLogStore.registerExportArtifact("ui-frame-overrun.txt") { render() }
    }

    /** One row set per activity class: a recreated activity keeps adding to the same rows. */
    fun statsFor(window: String): FrameOverrunStats =
        synchronized(windows) { windows.getOrPut(window) { FrameOverrunStats() } }

    fun render(): String =
        buildString {
            appendLine("Yfuse UI frame overrun")
            appendLine("scope=current process; latest 600 frames per row; counts since the window was first monitored")
            appendLine("overrun=frame end minus its deadline through UI thread, RenderThread and GPU; negative met it")
            appendLine("api=${Build.VERSION.SDK_INT}")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                appendLine("Frame overrun needs Android 12 (API 31); nothing is measured on this device.")
                return@buildString
            }
            val snapshot = synchronized(windows) { windows.toMap() }
            if (snapshot.isEmpty()) appendLine("No monitored window has rendered a frame in this process.")
            snapshot.forEach { (window, stats) ->
                stats.snapshot().forEach { row ->
                    appendLine(
                        "window=$window state=${row.state ?: "*"} frames=${row.frames} " +
                            "missed=${row.missedDeadline} kept=${row.kept} p50_ms=${row.p50Nanos.ms()} " +
                            "p90_ms=${row.p90Nanos.ms()} p99_ms=${row.p99Nanos.ms()} worst_ms=${row.worstNanos.ms()}",
                    )
                }
                stats.untrackedStateFrames().takeIf { it > 0L }?.let {
                    appendLine("window=$window untracked_state_frames=$it (state row limit reached)")
                }
            }
        }

    private fun Long.ms(): String = String.format(Locale.ROOT, "%.1f", this / 1_000_000.0)
}
