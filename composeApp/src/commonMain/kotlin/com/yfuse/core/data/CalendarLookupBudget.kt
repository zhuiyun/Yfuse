package com.yfuse.core.data

import kotlinx.coroutines.CancellationException
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

/** Local cache failures may degrade gracefully; a cancelled load must stop immediately. */
internal inline fun <T> calendarAttempt(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Result.failure(error)
    }
