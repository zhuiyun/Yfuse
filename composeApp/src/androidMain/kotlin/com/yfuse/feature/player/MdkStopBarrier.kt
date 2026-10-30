package com.yfuse.feature.player

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Invoke from a worker dispatcher: each native wait is bounded, and cancellation stays cooperative. */
internal suspend fun awaitMdkStopped(
    waitForStopped: (Long) -> Boolean,
    timeoutMs: Long = 3000L,
): Boolean =
    withTimeoutOrNull(timeoutMs) {
        while (!waitForStopped(20L)) delay(10L)
        true
    } == true
