package com.yfuse.core2.android

import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Never waits forever for a driver call; shutdown must leave its leased resources with the worker. */
internal fun awaitRenderFence(
    executor: ExecutorService,
    timeoutMs: Long,
): Throwable? {
    val fence =
        try {
            executor.submit { Unit }
        } catch (error: RuntimeException) {
            return error
        }
    return try {
        fence.get(timeoutMs, TimeUnit.MILLISECONDS)
        null
    } catch (error: Exception) {
        fence.cancel(false)
        if (error is InterruptedException) Thread.currentThread().interrupt()
        error.cause ?: error
    }
}
