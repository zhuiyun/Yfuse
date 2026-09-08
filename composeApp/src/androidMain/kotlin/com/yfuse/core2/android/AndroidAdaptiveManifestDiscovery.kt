package com.yfuse.core2.android

import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Optional manifest work must never delay the selected rendition or occupy unbounded workers. */
internal class AndroidAdaptiveManifestDiscovery(
    concurrency: Int = 2,
    queueCapacity: Int = 8,
    private val budgetMs: Long = 5_000L,
) : Closeable {
    init {
        require(concurrency > 0 && queueCapacity > 0 && budgetMs > 0L)
    }

    private val closed = AtomicBoolean(false)
    private val jobs = ConcurrentHashMap<Any, DiscoveryTask>()
    private val workers =
        ThreadPoolExecutor(
            concurrency,
            concurrency,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(queueCapacity),
            { work -> Thread(work, "YCore-manifest-discovery").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )
    private val deadlines =
        Executors.newSingleThreadScheduledExecutor { work ->
            Thread(work, "YCore-manifest-deadline").apply { isDaemon = true }
        }

    fun submit(
        key: Any,
        discover: (YManifestDiscoveryBudget) -> Unit,
    ): Boolean {
        if (closed.get()) return false
        val task = DiscoveryTask(key, discover)
        if (jobs.putIfAbsent(key, task) != null) return false
        try {
            workers.execute(task)
            val deadline = deadlines.schedule({ task.expire() }, budgetMs, TimeUnit.MILLISECONDS)
            task.deadline.set(deadline)
            if (task.isDone) deadline.cancel(false)
            if (closed.get()) task.expire()
            return true
        } catch (_: RejectedExecutionException) {
            task.expire()
            return false
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        jobs.values.forEach(DiscoveryTask::expire)
        workers.shutdownNow()
        deadlines.shutdownNow()
    }

    private inner class DiscoveryTask(
        private val key: Any,
        discover: (YManifestDiscoveryBudget) -> Unit,
        private val budget: YManifestDiscoveryBudget = YManifestDiscoveryBudget { !closed.get() },
    ) : FutureTask<Unit>({
            budget.checkActive()
            discover(budget)
        }, Unit) {
        val deadline = AtomicReference<ScheduledFuture<*>?>()

        fun expire() {
            budget.cancel()
            cancel(false)
            workers.remove(this)
        }

        override fun done() {
            deadline.get()?.cancel(false)
            jobs.remove(key, this)
        }
    }
}

/** Closing a transport also aborts an in-progress open/read, unlike a coroutine timeout alone. */
internal class YManifestDiscoveryBudget(
    private val ownerActive: () -> Boolean = { true },
) {
    private val lock = Any()
    private var cancelled = false
    private val cancellationActions = mutableSetOf<() -> Unit>()

    fun checkActive() {
        synchronized(lock) {
            if (cancelled || !ownerActive()) throw CancellationException("Optional manifest discovery budget expired")
        }
    }

    fun onCancel(action: () -> Unit): Closeable {
        val invokeNow =
            synchronized(lock) {
                if (cancelled) {
                    true
                } else {
                    cancellationActions.add(action)
                    false
                }
            }
        if (invokeNow) action()
        return Closeable { synchronized(lock) { cancellationActions.remove(action) } }
    }

    fun publishIfActive(publish: () -> Unit) {
        synchronized(lock) {
            checkActive()
            publish()
        }
    }

    fun cancel() {
        val actions =
            synchronized(lock) {
                if (cancelled) return
                cancelled = true
                cancellationActions.toList().also { cancellationActions.clear() }
            }
        actions.forEach { runCatching(it) }
    }
}
