package com.yfuse.core2.android

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidBoundedProbeTest {
    @Test
    fun source_failure_after_budget_cancellation_preserves_the_abort_reason() {
        AndroidProbeBudget().use { budget ->
            val probe = AndroidBoundedProbe(Executor { it.run() })
            val failure =
                assertFailsWith<AndroidProbeAbortedException> {
                    probe.run(1_000L, { -1 }, budget) {
                        budget.cancel("superseded")
                        throw java.net.SocketException("Socket closed")
                    }
                }
            assertEquals("superseded", failure.reason)
        }
    }

    @Test
    fun source_failure_without_cancellation_keeps_its_original_cause() {
        AndroidProbeBudget().use { budget ->
            val probe = AndroidBoundedProbe(Executor { it.run() })
            val failure =
                assertFailsWith<java.net.SocketException> {
                    probe.run(1_000L, { -1 }, budget) { throw java.net.SocketException("Connection reset") }
                }
            assertEquals("Connection reset", failure.message)
        }
    }

    @Test
    fun sequential_candidate_probes_never_skip_an_already_completed_owner() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val probe = AndroidBoundedProbe(executor)
            repeat(2_000) { candidate ->
                assertEquals(candidate, probe.run(1_000L, { -1 }) { candidate })
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun interrupted_caller_returns_unclaimed_resources_before_a_queued_worker_starts() {
        var queued: Runnable? = null
        val probe = AndroidBoundedProbe(Executor { queued = it })
        var cleaned = 0
        var entered = false
        Thread.currentThread().interrupt()
        try {
            assertFailsWith<InterruptedException> {
                probe.run(1_000L, {
                    cleaned++
                    "released"
                }) {
                    entered = true
                    "opened"
                }
            }
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(1, cleaned)
            checkNotNull(queued).run()
            assertFalse(entered)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun a_held_lane_answers_speculative_work_busy_at_once_and_apart_from_a_deadline() {
        val probe = AndroidBoundedProbe()
        val holder = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val held =
                holder.submit<String> {
                    probe.run(5_000L, { "deadline" }) {
                        entered.countDown()
                        release.await()
                        "held"
                    }
                }
            assertTrue(entered.await(1L, TimeUnit.SECONDS))
            AndroidProbeBudget().use { preparation ->
                val startedNs = System.nanoTime()
                assertEquals(
                    "busy",
                    probe.run(1_000L, { "deadline" }, preparation, busy = { "busy" }) { error("Lane is held") },
                )
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs) < 500L)
            }
            release.countDown()
            assertEquals("held", held.get(2L, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            holder.shutdownNow()
        }
    }

    @Test
    fun playback_waits_for_a_lane_its_previous_owner_is_about_to_release() {
        val probe = AndroidBoundedProbe()
        val holder = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        try {
            // No budget: nothing can cancel this owner, playback can only outwait it.
            val held =
                holder.submit<Int> {
                    probe.run(5_000L, { -1 }) {
                        entered.countDown()
                        Thread.sleep(200L)
                        1
                    }
                }
            assertTrue(entered.await(1L, TimeUnit.SECONDS))
            AndroidProbeBudget(foreground = true).use { playback ->
                assertEquals(7, probe.run(5_000L, { -1 }, playback, laneWaitMs = 1_500L, busy = { -2 }) { 7 })
            }
            assertEquals(1, held.get(2L, TimeUnit.SECONDS))
        } finally {
            holder.shutdownNow()
        }
    }

    @Test
    fun playback_makes_speculative_work_give_its_lane_up() {
        val probe = AndroidBoundedProbe()
        val holder = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val preparation = AndroidProbeBudget()
        try {
            val speculative =
                holder.submit<String> {
                    try {
                        probe.run(30_000L, { "deadline" }, preparation, busy = { "busy" }) {
                            val readsCancelled = CountDownLatch(1)
                            preparation.onCancel { readsCancelled.countDown() }.use {
                                entered.countDown()
                                // Model an extractor that returns only once its reads are cancelled.
                                while (true) {
                                    try {
                                        if (readsCancelled.await(1L, TimeUnit.SECONDS)) break
                                    } catch (_: InterruptedException) {
                                    }
                                }
                            }
                            "finished"
                        }
                    } catch (aborted: AndroidProbeAbortedException) {
                        aborted.reason
                    }
                }
            assertTrue(entered.await(2L, TimeUnit.SECONDS))
            val startedNs = System.nanoTime()
            AndroidProbeBudget(foreground = true).use { playback ->
                assertEquals(7, probe.run(5_000L, { -1 }, playback, laneWaitMs = 1_500L, busy = { -2 }) { 7 })
            }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs) < 1_500L)
            assertEquals(PROBE_LANE_YIELD_REASON, speculative.get(2L, TimeUnit.SECONDS))
        } finally {
            preparation.close()
            holder.shutdownNow()
        }
    }

    @Test
    fun a_cancelled_playback_start_stops_waiting_for_the_lane() {
        val probe = AndroidBoundedProbe()
        val holder = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val held =
                holder.submit<Int> {
                    probe.run(5_000L, { -1 }) {
                        entered.countDown()
                        release.await()
                        1
                    }
                }
            assertTrue(entered.await(1L, TimeUnit.SECONDS))
            AndroidProbeBudget(timeoutMs = 100L, foreground = true).use { superseded ->
                val startedNs = System.nanoTime()
                val failure =
                    assertFailsWith<AndroidProbeAbortedException> {
                        probe.run(5_000L, { -1 }, superseded, laneWaitMs = 5_000L, busy = { -2 }) { 7 }
                    }
                assertEquals("deadline", failure.reason)
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs) < 2_000L)
            }
            release.countDown()
            assertEquals(1, held.get(2L, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            holder.shutdownNow()
        }
    }

    @Test
    fun timeout_includes_blocking_open_and_does_not_accumulate_probe_workers() {
        val probe = AndroidBoundedProbe()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cleaned = CountDownLatch(1)
        val expiredObserved = AtomicBoolean(false)
        try {
            assertEquals(
                "timeout",
                probe.run(100L, { "timeout" }) { expired ->
                    started.countDown()
                    try {
                        // Model a vendor call that ignores interruption until the driver returns.
                        while (release.count > 0L) {
                            try {
                                release.await()
                            } catch (_: InterruptedException) {
                            }
                        }
                        expiredObserved.set(expired.get())
                        "late result"
                    } finally {
                        cleaned.countDown()
                    }
                },
            )
            assertTrue(started.await(1L, TimeUnit.SECONDS))
            assertFalse(cleaned.await(10L, TimeUnit.MILLISECONDS))
            assertEquals("busy", probe.run(100L, { "busy" }) { error("A second native worker must not start") })
        } finally {
            release.countDown()
        }
        assertTrue(cleaned.await(1L, TimeUnit.SECONDS))
        assertTrue(expiredObserved.get())
    }
}
