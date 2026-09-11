package com.yfuse.core2.android

import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Bounds the caller's entire preflight, including vendor configure/source reads and cleanup.
 * Native work owns its resources until its worker exits; timed-out work cannot return an extractor
 * to playback. One lane prevents repeated retries accumulating hung codec instances or threads.
 */
internal class AndroidBoundedProbe(
    private val executor: Executor =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "YCore-CodecPreflight").apply { isDaemon = true }
        },
) {
    private val owner = AtomicReference<Any?>(null)

    fun <T> run(
        timeoutMs: Long,
        skipped: () -> T,
        budget: AndroidProbeBudget? = null,
        block: (AtomicBoolean) -> T,
    ): T {
        val ticket = Any()
        if (!owner.compareAndSet(null, ticket)) return skipped()
        val expired = AtomicBoolean(false)
        val task =
            FutureTask {
                try {
                    block(expired)
                } finally {
                    // FutureTask wakes get() as soon as this callable returns. Release the lane
                    // first, or a following enhanced probe can wrongly skip a completed platform probe.
                    owner.compareAndSet(ticket, null)
                }
            }
        try {
            executor.execute {
                try {
                    task.run()
                } finally {
                    // A queued task cancelled before starting never enters the callable. Identity
                    // matching prevents this old wrapper from clearing a following stage's owner.
                    owner.compareAndSet(ticket, null)
                }
            }
        } catch (failure: Throwable) {
            owner.compareAndSet(ticket, null)
            throw failure
        }
        val cancellation =
            budget?.onCancel {
                expired.set(true)
                task.cancel(true)
            }
        return try {
            task.get(minOf(timeoutMs, budget?.remainingMs() ?: timeoutMs), TimeUnit.MILLISECONDS).also {
                budget?.ensureActive()
            }
        } catch (_: TimeoutException) {
            expired.set(true)
            task.cancel(true)
            skipped()
        } catch (error: java.util.concurrent.CancellationException) {
            budget?.ensureActive()
            throw error
        } catch (error: InterruptedException) {
            budget?.cancel("cancelled")
            expired.set(true)
            task.cancel(true)
            try {
                skipped()
            } finally {
                Thread.currentThread().interrupt()
            }
            throw error
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        } finally {
            cancellation?.close()
        }
    }
}

/** Both content and configuration preflights share the same scarce vendor-codec lane. */
internal object AndroidCodecProbeLane {
    val bounded = AndroidBoundedProbe()
}
