package com.yfuse.core2.android

import com.yfuse.core.logging.AppLog

/** A slow extractor must leave time for another backend and the decoder preflight. */
internal fun <T> runMetadataProbeStage(
    parent: AndroidProbeBudget,
    lane: AndroidBoundedProbe,
    limitMs: Long,
    reserveMs: Long,
    unavailable: () -> T,
    stageName: String = "metadata",
    block: (AndroidProbeBudget) -> T,
): T {
    val availableMs = parent.remainingMs() - reserveMs
    if (availableMs <= 0L) return unavailable()
    val stage = AndroidProbeBudget(minOf(limitMs, availableMs))
    val cancellation = parent.onCancel { stage.cancel("parent cancelled") }
    val startedNs = System.nanoTime()
    var outcome = "completed"
    return try {
        lane.run(
            timeoutMs = stage.remainingMs(),
            budget = stage,
            skipped = {
                // Invalidate resources before the worker can publish a late prepared source.
                stage.cancel("stage deadline or lane unavailable")
                outcome = "deadline_or_busy"
                parent.ensureActive()
                unavailable()
            },
        ) { block(stage) }
    } catch (_: AndroidProbeAbortedException) {
        outcome = "cancelled_or_deadline"
        parent.ensureActive()
        unavailable()
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
