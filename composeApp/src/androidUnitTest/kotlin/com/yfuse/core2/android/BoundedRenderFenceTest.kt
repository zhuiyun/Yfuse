package com.yfuse.core2.android

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BoundedRenderFenceTest {
    @Test
    fun aBlockedBackendTimesOutAndItsLeaseSurvivesUntilTheWorkerReturns() {
        val worker = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val disposed = AtomicBoolean()
        val pool = BoundedFrameLeasePool<Int, Int>(1, { it }, { disposed.set(true) })
        val lease = requireNotNull(pool.acquire(1))
        try {
            worker.execute {
                entered.countDown()
                try {
                    unblock.await()
                } finally {
                    lease.close()
                }
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertIs<TimeoutException>(awaitRenderFence(worker, 20))
            pool.clear()
            assertFalse(disposed.get())
            worker.shutdown()
            unblock.countDown()
            assertTrue(worker.awaitTermination(1, TimeUnit.SECONDS))
            assertTrue(disposed.get())
        } finally {
            unblock.countDown()
            worker.shutdownNow()
        }
    }
}
