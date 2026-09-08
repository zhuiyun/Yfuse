package com.yfuse.core2.android

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidBoundedProbeTest {
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
