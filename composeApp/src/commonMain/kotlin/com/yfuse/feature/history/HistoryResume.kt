package com.yfuse.feature.history

internal fun historyResumeTicks(
    positionMs: Long,
    completed: Boolean,
): Long = if (completed) 0L else positionMs.coerceIn(0L, Long.MAX_VALUE / 10_000) * 10_000
