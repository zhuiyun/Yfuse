package com.yfuse.watch.account

import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class AccountExecutionPolicy(
    val workerThreads: Int = 4,
    /** Includes running work; defaults to no executor-side queue. */
    val maxConcurrentOperations: Int = workerThreads,
) {
    init {
        require(workerThreads in 1..64)
        require(maxConcurrentOperations in 1..256)
        require(maxConcurrentOperations >= workerThreads)
    }
}

/** Keeps blocking JDBC and CPU-heavy password KDF work off Ktor CIO event threads. */
class AccountWorkExecutor(
    policy: AccountExecutionPolicy = AccountExecutionPolicy(),
) : AutoCloseable {
    private val permits = Semaphore(policy.maxConcurrentOperations, true)
    private val executorService =
        Executors.newFixedThreadPool(
            policy.workerThreads,
            AccountThreadFactory,
        )
    private val dispatcher: ExecutorCoroutineDispatcher = executorService.asCoroutineDispatcher()

    internal suspend fun <T> execute(block: () -> T): T {
        if (!permits.tryAcquire()) throw AccountWorkRejectedException()
        return try {
            try {
                withContext(dispatcher) { block() }
            } catch (_: RejectedExecutionException) {
                throw AccountWorkRejectedException()
            }
        } finally {
            permits.release()
        }
    }

    internal fun availablePermits(): Int = permits.availablePermits()

    override fun close() {
        dispatcher.close()
        executorService.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)
    }

    private object AccountThreadFactory : ThreadFactory {
        private val nextId = AtomicInteger()

        override fun newThread(task: Runnable): Thread =
            Thread(
                task,
                "yfuse-account-${nextId.incrementAndGet()}",
            ).apply { isDaemon = true }
    }

    companion object {
        private const val SHUTDOWN_WAIT_SECONDS = 5L
    }
}

internal class AccountWorkRejectedException : RuntimeException()
