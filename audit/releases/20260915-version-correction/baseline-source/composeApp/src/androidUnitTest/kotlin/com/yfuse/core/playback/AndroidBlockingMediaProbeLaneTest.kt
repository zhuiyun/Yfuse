package com.yfuse.core.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidBlockingMediaProbeLaneTest {
    @Test
    fun inline_continuation_can_start_the_next_probe_without_skipping_or_losing_ownership() =
        runBlocking {
            val tasks = LinkedBlockingQueue<Runnable>()
            val lane = AndroidBlockingMediaProbeLane(Executor { tasks.add(it) })
            val results =
                async(Dispatchers.Unconfined) {
                    listOf(
                        lane.run(1_000L, { "busy" }) { "first" },
                        lane.run(1_000L, { "busy" }) { "second" },
                    )
                }

            tasks.remove().run()
            // The first caller resumed inline, and its next task now owns the lane. The first
            // executor wrapper's finally must not clear that new ownership.
            assertEquals("busy", lane.run(1_000L, { "busy" }) { "unexpected third probe" })
            tasks.poll()?.run()
            assertEquals(listOf("first", "second"), withTimeout(2_000L) { results.await() })
        }

    @Test
    fun timeout_returns_while_vendor_is_blocked_and_repeated_requests_never_add_workers() =
        runBlocking {
            val executor = Executors.newSingleThreadExecutor()
            val lane = AndroidBlockingMediaProbeLane(executor)
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val cleaned = CountDownLatch(1)
            val calls = AtomicInteger()
            try {
                val first =
                    async {
                        lane.run(500L, { "timeout" }) {
                            calls.incrementAndGet()
                            entered.complete(Unit)
                            try {
                                waitIgnoringInterruption(release)
                                "late result"
                            } finally {
                                cleaned.countDown()
                            }
                        }
                    }
                withTimeout(2_000L) { entered.await() }
                assertEquals("timeout", withTimeout(2_000L) { first.await() })
                repeat(20) {
                    assertEquals(
                        "busy",
                        lane.run(150L, { "busy" }) {
                            calls.incrementAndGet()
                            "unexpected"
                        },
                    )
                }
                assertEquals(1, calls.get())
                assertEquals(1L, cleaned.count, "Caller timeout must not release vendor resources from another thread")
                release.countDown()
                assertTrue(cleaned.await(2, TimeUnit.SECONDS))
                executor.submit {}.get(2, TimeUnit.SECONDS)
                assertEquals("new result", lane.run(1_000L, { "busy" }) { "new result" })
            } finally {
                release.countDown()
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
            }
        }

    @Test
    fun caller_cancellation_does_not_wait_for_native_cleanup_and_late_results_are_discarded() =
        runBlocking {
            val executor = Executors.newSingleThreadExecutor()
            val lane = AndroidBlockingMediaProbeLane(executor)
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val cleaned = CountDownLatch(1)
            var delivered = false
            try {
                val first =
                    async {
                        lane.run(10_000L, { "timeout" }) {
                            entered.complete(Unit)
                            try {
                                waitIgnoringInterruption(release)
                                "late"
                            } finally {
                                cleaned.countDown()
                            }
                        }
                        delivered = true
                    }
                withTimeout(2_000L) { entered.await() }
                withTimeout(1_000L) { first.cancelAndJoin() }
                assertEquals(1L, cleaned.count)
                assertEquals("busy", lane.run(100L, { "busy" }) { "unexpected" })
                release.countDown()
                assertTrue(cleaned.await(2, TimeUnit.SECONDS))
                executor.submit {}.get(2, TimeUnit.SECONDS)
                assertEquals(false, delivered)
            } finally {
                release.countDown()
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
            }
        }

    private fun waitIgnoringInterruption(latch: CountDownLatch) {
        while (latch.count > 0L) {
            try {
                latch.await()
            } catch (_: InterruptedException) {
                // Models MediaExtractor/vendor IO that cannot be interrupted safely.
            }
        }
    }
}
