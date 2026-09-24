package com.yfuse.core2.android

import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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
    /**
     * The probe holding the lane. Release paths match it by identity, so a stale wrapper never
     * clears a following owner. [yieldLane] is set only for speculative work with a budget.
     */
    private class Owner(
        val yieldLane: (() -> Unit)?,
    )

    private val owner = AtomicReference<Owner?>(null)
    private val laneLock = ReentrantLock()
    private val laneReleased = laneLock.newCondition()

    /**
     * [laneWaitMs] is how long to wait for a lane another probe still holds. Only playback waits,
     * and while it does, speculative work holding the lane is cancelled so it gives the lane up;
     * preparation keeps 0 and never delays anything. [busy] answers a lane that stayed occupied and
     * [skipped] a deadline, so a caller can tell "not tried" from "ran out of time".
     */
    fun <T> run(
        timeoutMs: Long,
        skipped: () -> T,
        budget: AndroidProbeBudget? = null,
        laneWaitMs: Long = 0L,
        busy: () -> T = skipped,
        block: (AtomicBoolean) -> T,
    ): T {
        val ticket =
            Owner(
                // Cancelling a speculative budget runs its own I/O cancellations and fails its wait
                // below, so the lane frees as soon as its native work unwinds.
                yieldLane =
                    budget?.takeUnless { it.foreground }?.let { speculative ->
                        { speculative.cancel(PROBE_LANE_YIELD_REASON) }
                    },
            )
        val acquired =
            try {
                acquire(ticket, laneWaitMs, budget)
            } catch (error: InterruptedException) {
                budget?.cancel("cancelled")
                try {
                    busy()
                } finally {
                    Thread.currentThread().interrupt()
                }
                throw error
            }
        if (!acquired) return busy()
        val expired = AtomicBoolean(false)
        val task =
            FutureTask {
                try {
                    block(expired)
                } finally {
                    // FutureTask wakes get() as soon as this callable returns. Release the lane
                    // first, or a following enhanced probe can wrongly skip a completed platform probe.
                    release(ticket)
                }
            }
        try {
            executor.execute {
                try {
                    task.run()
                } finally {
                    // A queued task cancelled before starting never enters the callable. Identity
                    // matching prevents this old wrapper from clearing a following stage's owner.
                    release(ticket)
                }
            }
        } catch (failure: Throwable) {
            release(ticket)
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
            // Closing the source can complete the worker before cancellation reaches its
            // Future. Preserve the budget's cause instead of reporting that close as I/O failure.
            budget?.ensureActive()
            throw error.cause ?: error
        } finally {
            cancellation?.close()
        }
    }

    /**
     * Takes the lane, waiting up to [waitMs] for its owner to leave. A detail-page or next-item
     * probe that was just cancelled still holds the lane while its extractor unwinds; answering
     * playback "busy" at once sent the start down an inconclusive route for nothing.
     */
    private fun acquire(
        ticket: Owner,
        waitMs: Long,
        budget: AndroidProbeBudget?,
    ): Boolean {
        if (owner.compareAndSet(null, ticket)) return true
        if (waitMs <= 0L) return false
        val foreground = budget?.foreground == true
        val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs)
        // A superseded or expired caller must not sit out the rest of the wait.
        val wake = budget?.onCancel { laneLock.withLock { laneReleased.signalAll() } }
        try {
            while (true) {
                val current = owner.get()
                if (current == null) {
                    if (owner.compareAndSet(null, ticket)) return true
                    continue
                }
                if (foreground) current.yieldLane?.invoke()
                budget?.ensureActive()
                val remainingNs = deadlineNs - System.nanoTime()
                if (remainingNs <= 0L) return false
                laneLock.withLock {
                    // A release between the read above and this wait has already signalled.
                    if (owner.get() === current) laneReleased.awaitNanos(remainingNs)
                }
            }
        } finally {
            wake?.close()
        }
    }

    private fun release(ticket: Owner) {
        if (owner.compareAndSet(ticket, null)) laneLock.withLock { laneReleased.signalAll() }
    }
}

/** Stop reason of speculative probe work that gave its lane to playback. */
internal const val PROBE_LANE_YIELD_REASON = "yielded to playback"

/** Both content and configuration preflights share the same scarce vendor-codec lane. */
internal object AndroidCodecProbeLane {
    val bounded = AndroidBoundedProbe()
}
