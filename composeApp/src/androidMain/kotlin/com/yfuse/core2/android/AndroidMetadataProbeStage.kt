package com.yfuse.core2.android

import com.yfuse.core.logging.AppLog

/**
 * A slow extractor must leave time for another backend and the decoder preflight.
 *
 * [busy] answers a lane that stayed occupied, or that this speculative stage gave up to playback.
 * It is kept apart from [unavailable], the deadline answer, because a busy lane says nothing about
 * the source and must never be remembered as a verdict on it.
 */
internal fun <T> runMetadataProbeStage(
    parent: AndroidProbeBudget,
    lane: AndroidBoundedProbe,
    limitMs: Long,
    reserveMs: Long,
    unavailable: () -> T,
    stageName: String = "metadata",
    busy: () -> T = unavailable,
    block: (AndroidProbeBudget) -> T,
): T {
    val availableMs = parent.remainingMs() - reserveMs
    if (availableMs <= 0L) return unavailable()
    val stage = AndroidProbeBudget(minOf(limitMs, availableMs), foreground = parent.foreground)
    val cancellation = parent.onCancel { stage.cancel("parent cancelled") }
    val startedNs = System.nanoTime()
    var outcome = "completed"
    return try {
        lane.run(
            timeoutMs = stage.remainingMs(),
            budget = stage,
            // A just-cancelled preparation still holds the lane while its extractor unwinds. Playback
            // waits that out, making speculative holders yield, instead of skipping its own probe.
            laneWaitMs = if (parent.foreground) FOREGROUND_PROBE_LANE_WAIT_MS else 0L,
            skipped = {
                // Invalidate resources before the worker can publish a late prepared source.
                stage.cancel("stage deadline")
                outcome = "deadline"
                parent.ensureActive()
                unavailable()
            },
            busy = {
                stage.cancel("lane unavailable")
                outcome = "busy"
                parent.ensureActive()
                busy()
            },
        ) { block(stage) }
    } catch (aborted: AndroidProbeAbortedException) {
        // Work that gave its lane to playback learned nothing about the source either.
        val yielded = aborted.reason == PROBE_LANE_YIELD_REASON
        outcome = if (yielded) "yielded" else "cancelled_or_deadline"
        parent.ensureActive()
        if (yielded) busy() else unavailable()
    } catch (failure: Throwable) {
        outcome = "failed"
        throw failure
    } finally {
        cancellation.close()
        stage.close()
        AppLog.info(
            "player.core2",
            "metadata_probe_budget",
            "Bounded metadata stage finished",
            attributes =
                mapOf(
                    "stage" to stageName,
                    "outcome" to outcome,
                    "elapsedMs" to ((System.nanoTime() - startedNs) / 1_000_000L).toString(),
                    "limitMs" to minOf(limitMs, availableMs).toString(),
                    "reservedMs" to reserveMs.toString(),
                ),
        )
    }
}

/** Long enough for a cancelled probe to unwind its extractor, short enough to stay inside the start. */
internal const val FOREGROUND_PROBE_LANE_WAIT_MS = 1_500L
