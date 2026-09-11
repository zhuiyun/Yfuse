package com.yfuse.core2.android

import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AndroidProbeBudgetTest {
    @Test
    fun `blocked platform owner does not suppress an independent enhanced probe`() {
        val caller = Executors.newSingleThreadExecutor()
        val vendorEntered = CountDownLatch(1)
        val returnFromVendor = CountDownLatch(1)
        val vendorFinished = CountDownLatch(1)
        try {
            AndroidProbeBudget().use { budget ->
                val platform =
                    caller.submit<Int> {
                        AndroidMetadataProbeLane.platform.run(30_000L, { -1 }, budget) {
                            try {
                                vendorEntered.countDown()
                                while (true) {
                                    try {
                                        returnFromVendor.await()
                                        break
                                    } catch (_: InterruptedException) {
                                    }
                                }
                                1
                            } finally {
                                vendorFinished.countDown()
                            }
                        }
                    }
                assertTrue(vendorEntered.await(2L, TimeUnit.SECONDS))
                assertEquals(42, AndroidMetadataProbeLane.enhanced.run(1_000L, { -1 }, budget) { 42 })
                returnFromVendor.countDown()
                assertEquals(1, platform.get(2L, TimeUnit.SECONDS))
            }
        } finally {
            returnFromVendor.countDown()
            caller.shutdownNow()
            assertTrue(vendorFinished.await(2L, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `deadline cancels suspended manifest discovery without cancelling router`() =
        runBlocking {
            AndroidProbeBudget(timeoutMs = 40L).use { budget ->
                assertEquals(
                    "deadline",
                    assertFailsWith<AndroidProbeAbortedException> {
                        budget.await { delay(5_000L) }
                    }.reason,
                )
                assertTrue(coroutineContext.isActive)
            }
        }

    @Test
    fun `release timeout stays a barrier across retries until old codec owner finishes`() =
        runBlocking {
            var done = false
            var waits = 0
            val old =
                object : AndroidSerializedPlayerRelease {
                    override val releaseCompleted: Boolean get() = done

                    override suspend fun releaseAndJoin() {
                        waits++
                        check(done) { "Simulated vendor release timeout" }
                    }
                }
            val barrier = AndroidPlayerReleaseBarrier()
            barrier.retire(old)
            repeat(2) { assertFailsWith<IllegalStateException> { barrier.await() } }
            assertEquals(2, waits)
            done = true
            barrier.await()
            barrier.await()
            assertEquals(2, waits)
        }

    @Test
    fun `invalidating old generation cannot cancel or complete a concurrently begun generation`() {
        val controller = AndroidProbeController()
        val old = controller.begin()
        val enteredCancellation = CountDownLatch(1)
        val finishCancellation = CountDownLatch(1)
        old.budget.onCancel {
            enteredCancellation.countDown()
            finishCancellation.await(2L, TimeUnit.SECONDS)
        }
        val worker = Executors.newSingleThreadExecutor()
        try {
            val cancellation = worker.submit { controller.invalidate("superseded") }
            assertTrue(enteredCancellation.await(1L, TimeUnit.SECONDS))
            val next = controller.begin()
            assertTrue(next.generation != old.generation)
            assertTrue(controller.isCurrent(next))
            finishCancellation.countDown()
            cancellation.get(1L, TimeUnit.SECONDS)
            next.budget.ensureActive()
            assertTrue(controller.budget() === next.budget)
            controller.complete(old.budget)
            assertTrue(controller.budget() === next.budget)
        } finally {
            finishCancellation.countDown()
            controller.invalidate("released")
            worker.shutdownNow()
        }
    }

    @Test
    fun `deadline closes a real stalled network socket`() {
        val loopback = InetAddress.getLoopbackAddress()
        ServerSocket(0, 1, loopback).use { listener ->
            Socket(loopback, listener.localPort).use { client ->
                listener.accept().use {
                    val owner = Executors.newSingleThreadExecutor()
                    try {
                        val started = CountDownLatch(1)
                        AndroidProbeBudget(timeoutMs = 100L).use { budget ->
                            budget.onCancel(client::close).use {
                                val failure =
                                    assertFailsWith<AndroidProbeAbortedException> {
                                        AndroidBoundedProbe(owner).run(
                                            timeoutMs = 30_000L,
                                            skipped = {
                                                budget.ensureActive()
                                                -2
                                            },
                                            budget = budget,
                                        ) {
                                            started.countDown()
                                            client.getInputStream().read()
                                        }
                                    }
                                assertEquals("deadline", failure.reason)
                                assertTrue(started.await(1L, TimeUnit.SECONDS))
                                assertTrue(client.isClosed)
                                owner.submit {}.get(1L, TimeUnit.SECONDS)
                            }
                        }
                    } finally {
                        owner.shutdownNow()
                    }
                }
            }
        }
    }

    @Test
    fun `platform enhanced and recovery candidates spend one deadline`() {
        val now = AtomicLong(0L)
        AndroidProbeBudget(timeoutMs = 30_000L, clock = now::get).use { budget ->
            assertEquals(30_000L, budget.remainingMs())
            now.addAndGet(TimeUnit.SECONDS.toNanos(20L))
            assertEquals(10_000L, budget.remainingMs())
            now.addAndGet(TimeUnit.SECONDS.toNanos(9L))
            assertEquals(1_000L, budget.remainingMs())
            now.addAndGet(TimeUnit.SECONDS.toNanos(1L))
            assertEquals("deadline", assertFailsWith<AndroidProbeAbortedException> { budget.ensureActive() }.reason)
        }
    }

    @Test
    fun `exit interrupts actual source and rejects a late vendor result before reentry`() {
        val owner = Executors.newSingleThreadExecutor()
        val caller = Executors.newSingleThreadExecutor()
        try {
            val lane = AndroidBoundedProbe(owner)
            val opened = CountDownLatch(1)
            val sourceCancelled = CountDownLatch(1)
            val vendorFinished = CountDownLatch(1)
            val allowVendorReturn = CountDownLatch(1)
            val published = AtomicInteger()
            AndroidProbeBudget().use { old ->
                val waiting =
                    caller.submit<Boolean> {
                        try {
                            lane.run(timeoutMs = 30_000L, skipped = { false }, budget = old) {
                                val cancellation = old.onCancel { sourceCancelled.countDown() }
                                try {
                                    opened.countDown()
                                    // Model a vendor call that does not respond to Thread.interrupt.
                                    while (true) {
                                        try {
                                            allowVendorReturn.await()
                                            break
                                        } catch (_: InterruptedException) {
                                        }
                                    }
                                    old.ifActive {
                                        published.incrementAndGet()
                                        true
                                    }
                                } finally {
                                    cancellation.close()
                                    vendorFinished.countDown()
                                }
                            }
                            false
                        } catch (error: AndroidProbeAbortedException) {
                            error.reason == "superseded"
                        }
                    }
                assertTrue(opened.await(2L, TimeUnit.SECONDS))
                old.cancel("superseded")
                assertTrue(sourceCancelled.await(1L, TimeUnit.SECONDS))
                assertTrue(waiting.get(1L, TimeUnit.SECONDS))
                allowVendorReturn.countDown()
                assertTrue(vendorFinished.await(1L, TimeUnit.SECONDS))
                // Drain the wrapper's finally as well, before attempting the next owner.
                owner.submit {}.get(1L, TimeUnit.SECONDS)
                AndroidProbeBudget().use { next ->
                    assertEquals(7, lane.run(1_000L, skipped = { -1 }, budget = next) { 7 })
                }
                assertEquals(0, published.get())
            }
        } finally {
            caller.shutdownNow()
            owner.shutdownNow()
        }
    }

    @Test
    fun `deadline cancels a blocked source without waiting for its normal timeout`() {
        val sourceCancelled = CountDownLatch(1)
        val startNs = System.nanoTime()
        AndroidProbeBudget(timeoutMs = 60L).use { budget ->
            budget.onCancel { sourceCancelled.countDown() }.use {
                assertTrue(sourceCancelled.await(2L, TimeUnit.SECONDS))
                assertEquals("deadline", assertFailsWith<AndroidProbeAbortedException> { budget.ensureActive() }.reason)
            }
        }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs) < 2_000L)
    }

    @Test
    fun `completed preparation does not later cancel adopted playback resources`() {
        val now = AtomicLong(0L)
        val cancelled = AtomicInteger()
        val budget = AndroidProbeBudget(timeoutMs = 100L, clock = now::get)
        budget.onCancel { cancelled.incrementAndGet() }
        budget.close()
        now.addAndGet(TimeUnit.SECONDS.toNanos(1L))
        budget.ensureActive()
        budget.cancel("released")
        assertEquals(0, cancelled.get())
    }
}
