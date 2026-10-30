package com.yfuse.feature.player

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Poll actual state off the UI thread: SDK waitFor can block beyond its own timeout. */
internal suspend fun awaitMdkStopped(
    isStopped: () -> Boolean,
    timeoutMs: Long = 3000L,
): Boolean =
    withTimeoutOrNull(timeoutMs) {
        while (!isStopped()) delay(10L)
        true
    } == true
