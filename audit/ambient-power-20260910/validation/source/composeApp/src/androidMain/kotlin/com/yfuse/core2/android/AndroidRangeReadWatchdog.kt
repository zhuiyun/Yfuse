package com.yfuse.core2.android

import com.yfuse.core2.network.YMediaTransport
import kotlinx.coroutines.runBlocking
import java.net.SocketTimeoutException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One deadline covers prefetch promotion, opening, body reads and every retry. */
internal class YRangeReadBudget(
    private val maximumMs: Long = 30_000L,
    private val nowNs: () -> Long = System::nanoTime,
) {
    init {
        require(maximumMs > 0L)
    }

    private val startedNs = nowNs()

    fun remainingMs(): Long = (maximumMs - (nowNs() - startedNs) / 1_000_000L).coerceAtLeast(0L)

    fun checkRemaining() {
        if (remainingMs() == 0L) throw SocketTimeoutException("Media range exhausted its total retry budget")
    }
}

/**
 * Blocking transports do not necessarily observe coroutine cancellation during open/read.
 * Close the active exchange from a separate thread when it stops receiving bytes. Joining this
 * small critical section on close prevents a late watchdog from cancelling the next attempt.
 */
internal class AndroidRangeReadWatchdog(
    private val transport: YMediaTransport,
    private val budget: YRangeReadBudget,
    private val idleBudgetMs: () -> Long,
    pollMs: Long = 250L,
) : AutoCloseable {
    private val lock = Any()
    private var active = true
    private var lastProgressNs = System.nanoTime()
    private var timedOut = false
    private val future = scheduler.scheduleWithFixedDelay(::checkTimeout, pollMs, pollMs, TimeUnit.MILLISECONDS)

    fun progressed() = synchronized(lock) { lastProgressNs = System.nanoTime() }

    fun checkFailure() =
        synchronized(lock) {
            if (timedOut) throw SocketTimeoutException("Media range stopped progressing or exhausted its retry budget")
        }

    private fun checkTimeout() {
        synchronized(lock) {
            if (!active || timedOut) return
            val idleMs = (System.nanoTime() - lastProgressNs) / 1_000_000L
            if (budget.remainingMs() > 0L && idleMs < idleBudgetMs()) return
            timedOut = true
            runCatching { runBlocking { transport.close() } }
        }
    }

    override fun close() {
        synchronized(lock) { active = false }
        future.cancel(false)
    }

    private companion object {
        val scheduler =
            ScheduledThreadPoolExecutor(2) { runnable ->
                Thread(runnable, "YCore-RangeWatchdog").apply { isDaemon = true }
            }.apply { removeOnCancelPolicy = true }
    }
}
