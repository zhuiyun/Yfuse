package com.yfuse.watch.account

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountWorkCancellationTest {
    @Test
    fun cancellingAuthenticationWaitDoesNotReleaseAStillRunningWorkerPermit() =
        runBlocking {
            val executor = AccountWorkExecutor(AccountExecutionPolicy(workerThreads = 1))
            val entered = CountDownLatch(1)
            val finish = CountDownLatch(1)
            try {
                val waiting =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        executor.executeCancellable {
                            entered.countDown()
                            check(finish.await(5, TimeUnit.SECONDS))
                            42
                        }
                    }
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                withTimeout(1_000) { waiting.cancelAndJoin() }
                assertEquals(0, executor.availablePermits())
                assertFailsWith<AccountWorkRejectedException> { executor.executeCancellable { 7 } }
                finish.countDown()
                withTimeout(2_000) { while (executor.availablePermits() == 0) delay(5) }
                assertEquals(7, executor.executeCancellable { 7 })
                assertEquals(1, executor.availablePermits())
            } finally {
                finish.countDown()
                executor.close()
            }
        }

    @Test
    fun cancelledQueuedAuthenticationNeverRunsAndReturnsItsPermitOnce() =
        runBlocking {
            val executor = AccountWorkExecutor(AccountExecutionPolicy(workerThreads = 1, maxConcurrentOperations = 2))
            val entered = CountDownLatch(1)
            val finish = CountDownLatch(1)
            val queuedRan = AtomicBoolean()
            try {
                val active =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        executor.execute {
                            entered.countDown()
                            check(finish.await(5, TimeUnit.SECONDS))
                        }
                    }
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                val queued =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        executor.executeCancellable { queuedRan.set(true) }
                    }
                assertEquals(0, executor.availablePermits())
                withTimeout(1_000) { queued.cancelAndJoin() }
                assertEquals(1, executor.availablePermits())
                finish.countDown()
                active.await()
                assertEquals(42, executor.executeCancellable { 42 })
                assertFalse(queuedRan.get())
                assertEquals(2, executor.availablePermits())
            } finally {
                finish.countDown()
                executor.close()
            }
        }
}
