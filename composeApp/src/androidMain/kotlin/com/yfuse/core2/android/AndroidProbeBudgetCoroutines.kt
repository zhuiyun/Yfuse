package com.yfuse.core2.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job

/** A separate structured child lets superseding a probe cancel network discovery without killing the router. */
internal suspend fun <T> AndroidProbeBudget.await(block: suspend () -> T): T =
    try {
        coroutineScope {
            val job = coroutineContext.job
            val cancellation = onCancel { job.cancel() }
            try {
                ensureActive()
                block().also { ensureActive() }
            } finally {
                cancellation.close()
            }
        }
    } catch (cancelled: CancellationException) {
        ensureActive()
        throw cancelled
    }
