package com.yfuse.core.playback

import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A cancelled caller never waits for vendor IO or resource destruction. The single owner keeps the
 * lane occupied until its finally blocks finish, even if a platform call ignores interruption.
 * Further probes skip instead of queueing more extractors or creating replacement threads.
 */
internal class AndroidBlockingMediaProbeLane(
    private val executor: Executor =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "Yfuse-MediaProbe").apply { isDaemon = true }
        },
) {
    private val busy = AtomicBoolean(false)

    suspend fun <T : Any> run(
        timeoutMs: Long,
        unavailable: () -> T,
        inspect: suspend () -> T,
    ): T =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<T> { caller ->
                if (!busy.compareAndSet(false, true)) {
                    caller.resume(unavailable())
                    return@suspendCancellableCoroutine
                }
                // Deliberately independent: structured child cancellation would make the UI await
                // a blocked extractor. Only this owner job accesses and releases native resources.
                val owner = Job()
                val task =
                    FutureTask {
                        val outcome: Result<T> =
                            try {
                                Result.success(runBlocking(owner) { inspect() })
                            } catch (error: Throwable) {
                                Result.failure(error)
                            } finally {
                                owner.complete()
                            }
                        outcome.fold(
                            onSuccess = { caller.resume(it) },
                            onFailure = { caller.resumeWithException(it) },
                        )
                    }
                caller.invokeOnCancellation {
                    owner.cancel()
                    task.cancel(true)
                }
                try {
                    executor.execute {
                        try {
                            task.run()
                        } finally {
                            owner.cancel()
                            Thread.interrupted()
                            busy.set(false)
                        }
                    }
                } catch (error: Exception) {
                    owner.cancel()
                    busy.set(false)
                    caller.resumeWithException(error)
                }
            }
        } ?: unavailable()
}

/** Shared across service recreation; timed-out vendor calls cannot accumulate lanes. */
internal object AndroidMediaProbeLane {
    val shared = AndroidBlockingMediaProbeLane()
}
