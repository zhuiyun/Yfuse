package com.yfuse.core2.android

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/** The view replaces its SurfaceView when the retired decoder's producer type changes. */
internal interface AndroidVideoOutputRenewal {
    val videoOutputGeneration: StateFlow<Long>
}

/**
 * The old producer must already be released before requesting a replacement. Surface callbacks
 * complete this rendezvous directly: queuing them behind the waiting player worker would deadlock.
 */
internal class AndroidVideoOutputReplacement<T : Any> {
    private class Request<T>(
        val previous: T,
        val result: CompletableDeferred<T>,
    )

    private val pending = AtomicReference<Request<T>?>(null)
    private val mutableGeneration = MutableStateFlow(0L)
    val generation = mutableGeneration.asStateFlow()
    val awaitingReplacement: Boolean get() = pending.get() != null

    suspend fun awaitReplacement(previous: T): T {
        val request = Request(previous, CompletableDeferred<T>())
        check(pending.compareAndSet(null, request)) { "Video output replacement is already pending" }
        return try {
            mutableGeneration.update { it + 1L }
            checkNotNull(withTimeoutOrNull(OUTPUT_REPLACEMENT_TIMEOUT_MS) { request.result.await() }) {
                "Video output replacement timed out"
            }
        } finally {
            pending.compareAndSet(request, null)
            request.result.cancel()
        }
    }

    /** Returns true only for callbacks belonging to an outstanding replacement. */
    fun accept(output: T?): Boolean {
        val request = pending.get() ?: return false
        if (output == null || output === request.previous) return true
        if (!pending.compareAndSet(request, null)) return false
        request.result.complete(output)
        return true
    }
}

private const val OUTPUT_REPLACEMENT_TIMEOUT_MS = 5_000L
