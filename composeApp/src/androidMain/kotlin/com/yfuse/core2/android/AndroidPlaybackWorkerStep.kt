package com.yfuse.core2.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/** Every media iteration, including snapshot collection, reports through the same recovery edge. */
internal inline fun <T> playbackWorkerStep(
    onFailure: (Throwable) -> Unit,
    work: () -> T,
): T? =
    try {
        work()
    } catch (failure: Throwable) {
        if (failure is CancellationException) throw failure
        onFailure(failure)
        null
    }

internal fun createPlaybackWorkerDispatcher(name: String): ExecutorCoroutineDispatcher =
    Executors
        .newSingleThreadExecutor { task ->
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
                task.run()
            }, name)
        }.asCoroutineDispatcher()
