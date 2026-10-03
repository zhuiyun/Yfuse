package com.yfuse.feature.player

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * A provider may ignore interruption. Its optional metadata must neither hold up the caller's
 * coroutine nor fill an unbounded IO queue. At most two reads may remain blocked process-wide;
 * further callers immediately use their fallback until a worker becomes available.
 */
internal class ExternalMetadataLookup(
    private val executor: ExecutorService = metadataExecutor,
) {
    suspend fun <T> read(
        timeoutMs: Long = 1_500L,
        query: () -> T,
    ): T? =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val future =
                    try {
                        executor.submit {
                            if (continuation.isActive) {
                                val value =
                                    try {
                                        query()
                                    } catch (_: Exception) {
                                        null
                                    }
                                continuation.resume(value)
                            }
                        }
                    } catch (_: RejectedExecutionException) {
                        continuation.resume(null)
                        null
                    }
                continuation.invokeOnCancellation { future?.cancel(true) }
            }
        }
}

private val metadataExecutor =
    ThreadPoolExecutor(
        0,
        2,
        30L,
        TimeUnit.SECONDS,
        SynchronousQueue(),
        { task -> Thread(task, "yfuse-external-metadata").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
