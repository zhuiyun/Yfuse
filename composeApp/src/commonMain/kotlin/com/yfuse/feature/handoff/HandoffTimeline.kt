package com.yfuse.feature.handoff

/** Different server copies need comparable durations before reusing an absolute position. */
internal fun compatibleHandoffTimeline(
    sourceDurationMs: Long,
    targetDurationMs: Long,
    sameVersion: Boolean,
): Boolean {
    if (sameVersion) return true
    if (sourceDurationMs <= 0 || targetDurationMs <= 0) return false
    val toleranceMs = (sourceDurationMs / 100).coerceIn(2_000L, 30_000L)
    return kotlin.math.abs(sourceDurationMs - targetDurationMs) <= toleranceMs
}
