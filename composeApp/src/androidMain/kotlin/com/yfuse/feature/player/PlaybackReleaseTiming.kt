package com.yfuse.feature.player

import android.os.Looper
import com.yfuse.core.logging.AppLog

/** Release-only measurements: no timers, background jobs, source identifiers, or per-frame work. */
internal class PlaybackReleaseTiming(
    private val nowNs: () -> Long = System::nanoTime,
) {
    private val startedNs = nowNs()
    private val stages = linkedMapOf<String, Long>()
    private val failedStages = mutableListOf<String>()

    fun <T> stage(
        name: String,
        block: () -> T,
    ): T {
        val started = nowNs()
        return try {
            block()
        } catch (error: Throwable) {
            failedStages += name
            throw error
        } finally {
            stages[name] = (nowNs() - started).coerceAtLeast(0L) / 1_000_000L
        }
    }

    fun attributes(): Map<String, String> =
        stages.mapKeys { (stage, _) -> "${stage}Ms" }.mapValues { (_, elapsed) -> elapsed.toString() } +
            ("totalMs" to ((nowNs() - startedNs).coerceAtLeast(0L) / 1_000_000L).toString()) +
            if (failedStages.isEmpty()) emptyMap() else mapOf("failedStages" to failedStages.joinToString(","))
}

internal fun tracePlaybackRelease(
    engine: String,
    block: PlaybackReleaseTiming.() -> Unit,
) {
    val timing = PlaybackReleaseTiming()
    val thread = Thread.currentThread()
    val context =
        mapOf(
            "engine" to engine,
            "thread" to thread.name,
            "mainThread" to (Looper.myLooper() == Looper.getMainLooper()).toString(),
        )
    AppLog.info("player.release", "release_started", "Playback release started on its existing owner", context)
    var completed = false
    try {
        timing.block()
        completed = true
    } finally {
        AppLog.info(
            category = "player.release",
            event = "release_finished",
            message = "Playback release stage timings",
            attributes = context + timing.attributes() + ("completed" to completed.toString()),
        )
    }
}
