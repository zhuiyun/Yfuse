package com.yfuse.feature.detail

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** One bounded write batch; its children and counters belong to the caller's operation. */
internal class DetailProgressBatch(
    private val concurrency: Int = 4,
) {
    init {
        require(concurrency > 0)
    }

    suspend fun <T> write(
        targets: List<T>,
        onProgress: (Int) -> Unit = {},
        persist: suspend (T) -> Result<Unit>,
    ): Int =
        coroutineScope {
            val permits = Semaphore(concurrency)
            val counters = Mutex()
            var queued = 0
            var completed = 0
            coroutineScope {
                targets.forEach { target ->
                    launch {
                        val result = permits.withPermit { persist(target) }
                        currentCoroutineContext().ensureActive()
                        (result.exceptionOrNull() as? CancellationException)?.let { throw it }
                        counters.withLock {
                            if (result.isFailure) queued++
                            onProgress(++completed)
                        }
                    }
                }
            }
            queued
        }
}
