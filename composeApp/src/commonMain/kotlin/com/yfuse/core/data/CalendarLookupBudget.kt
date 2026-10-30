package com.yfuse.core.data

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/** Queue admission is cancellable, but does not consume a server's network deadline. */
internal suspend fun <T> calendarLibraryLookup(
    permits: Semaphore?,
    timeoutMs: Long,
    lookup: suspend () -> T,
): T? =
    if (permits == null) {
        withTimeoutOrNull(timeoutMs) { lookup() }
    } else {
        permits.withPermit { withTimeoutOrNull(timeoutMs) { lookup() } }
    }
