package com.yfuse.core2.android

import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One monotonic deadline for a start and all of its candidate probes. Callbacks must only signal I/O. */
internal class AndroidProbeBudget(
    timeoutMs: Long = 30_000L,
    private val clock: () -> Long = System::nanoTime,
) : AutoCloseable {
    private val lock = Any()
    private val deadlineNs = clock() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(1L))
    private var stopped: String? = null
    private var completed = false
    private val cancellations = linkedSetOf<() -> Unit>()
    private val timer = watchdog.schedule({ cancel("deadline") }, timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)

    fun remainingMs(): Long {
        ensureActive()
        return ((deadlineNs - clock() + 999_999L) / 1_000_000L).coerceAtLeast(1L)
    }

    fun ensureActive() {
        if (clock() >= deadlineNs) cancel("deadline")
        synchronized(lock) { stopped?.let { throw AndroidProbeAbortedException(it) } }
    }

    fun onCancel(callback: () -> Unit): AutoCloseable {
        val runNow =
            synchronized(lock) {
                if (stopped != null) {
                    true
                } else {
                    if (!completed) cancellations.add(callback)
                    false
                }
            }
        if (runNow) runCatching(callback)
        return AutoCloseable { synchronized(lock) { cancellations.remove(callback) } }
    }

    fun cancel(reason: String = "cancelled") {
        val callbacks =
            synchronized(lock) {
                if (completed || stopped != null) return
                stopped = reason
                cancellations.toList().also { cancellations.clear() }
            }
        // Signal real sockets/native interrupt tokens as well as the waiting Future.
        callbacks.forEach { runCatching(it) }
    }

    /** Atomically transfers probe-owned resources before the deadline can invalidate the result. */
    fun <T> ifActive(block: () -> T): T =
        synchronized(lock) {
            ensureActive()
            block()
        }

    override fun close() {
        synchronized(lock) {
            completed = true
            cancellations.clear()
        }
        timer.cancel(false)
    }

    private companion object {
        val watchdog =
            Executors.newScheduledThreadPool(2) { task ->
                Thread(task, "YCore-ProbeDeadline").apply { isDaemon = true }
            }
    }
}

internal class AndroidProbeAbortedException(
    val reason: String,
) : IOException("YCore probe $reason")

/** Generation and its budget move together; resource cancellation always runs outside this lock. */
internal class AndroidProbeController {
    data class Ticket(
        val generation: Long,
        val budget: AndroidProbeBudget,
    )

    private val lock = Any()
    private var generation = 0L
    private var active: Ticket? = null

    fun begin(): Ticket =
        synchronized(lock) {
            active ?: Ticket(generation, AndroidProbeBudget()).also { active = it }
        }

    fun budget(): AndroidProbeBudget? = synchronized(lock) { active?.budget }

    fun generation(): Long = synchronized(lock) { generation }

    fun isCurrent(ticket: Ticket): Boolean = synchronized(lock) { generation == ticket.generation }

    fun invalidate(reason: String) {
        val previous =
            synchronized(lock) {
                generation++
                active.also { active = null }
            }
        previous?.budget?.let {
            it.cancel(reason)
            it.close()
        }
    }

    fun complete(budget: AndroidProbeBudget) {
        synchronized(lock) { if (active?.budget === budget) active = null }
        budget.close()
    }
}

/** Each backend has one bounded lane; a hung vendor extractor cannot suppress FFmpeg on reentry. */
internal object AndroidMetadataProbeLane {
    val platform = AndroidBoundedProbe()
    val enhanced = AndroidBoundedProbe()
}
