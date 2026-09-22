package com.yfuse.core2.android

import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

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
    private val busy = AtomicBoolean(false)

    fun <T> run(
        timeoutMs: Long,
        skipped: () -> T,
        block: (AtomicBoolean) -> T,
    ): T {
        if (!busy.compareAndSet(false, true)) return skipped()
        val expired = AtomicBoolean(false)
        val task = FutureTask { block(expired) }
        executor.execute {
            try {
                task.run()
            } finally {
                busy.set(false)
            }
        }
        return try {
            task.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            expired.set(true)
            task.cancel(true)
            skipped()
        } catch (error: InterruptedException) {
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
        }
    }
}

/** Both content and configuration preflights share the same scarce vendor-codec lane. */
internal object AndroidCodecProbeLane {
    val bounded = AndroidBoundedProbe()
}
