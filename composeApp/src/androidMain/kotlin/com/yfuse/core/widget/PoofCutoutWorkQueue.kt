package com.yfuse.core.widget

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Bounded single-flight work, owned by the process so a paid response can finish caching. */
internal class PoofCutoutWorkQueue<T>(
    private val scope: CoroutineScope,
    private val maxPending: Int = 3,
) {
    private val gate = Semaphore(1)
    private val pending = mutableMapOf<String, Deferred<T?>>()

    suspend fun run(
        key: String,
        work: suspend () -> T?,
    ): T? {
        val job =
            synchronized(pending) {
                pending[key] ?: if (pending.size >= maxPending) {
                    null
                } else {
                    scope
                        .async(start = CoroutineStart.LAZY) { gate.withPermit { work() } }
                        .also { pending[key] = it }
                }
            } ?: return null
        job.invokeOnCompletion { synchronized(pending) { if (pending[key] === job) pending.remove(key) } }
        job.start()
        return job.await()
    }
}
