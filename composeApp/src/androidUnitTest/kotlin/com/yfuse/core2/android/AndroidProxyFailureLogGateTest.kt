package com.yfuse.core2.android

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidProxyFailureLogGateTest {
    @Test
    fun first_failure_of_a_kind_is_written_and_repeats_are_counted_until_the_interval_ends() {
        val gate = AndroidProxyFailureLogGate(intervalMs = 1_000L)
        assertEquals(0, gate.admit("502:SocketException", nowMs = 100L))
        assertNull(gate.admit("502:SocketException", nowMs = 200L))
        assertNull(gate.admit("502:SocketException", nowMs = 1_099L))
        assertEquals(2, gate.admit("502:SocketException", nowMs = 1_100L))
        assertNull(gate.admit("502:SocketException", nowMs = 1_200L))
    }

    @Test
    fun kinds_are_limited_independently() {
        val gate = AndroidProxyFailureLogGate(intervalMs = 1_000L)
        assertEquals(0, gate.admit("403", nowMs = 0L))
        assertEquals(0, gate.admit("502", nowMs = 1L))
        assertNull(gate.admit("403", nowMs = 2L))
        assertNull(gate.admit("502", nowMs = 3L))
    }

    @Test
    fun the_oldest_kind_is_forgotten_beyond_the_bound() {
        val gate = AndroidProxyFailureLogGate(intervalMs = 1_000L, maxKinds = 2)
        assertEquals(0, gate.admit("a", nowMs = 0L))
        assertEquals(0, gate.admit("b", nowMs = 1L))
        assertEquals(0, gate.admit("c", nowMs = 2L))
        // "a" was evicted, so it is written again at once; "c" is still inside its interval.
        assertEquals(0, gate.admit("a", nowMs = 3L))
        assertNull(gate.admit("c", nowMs = 4L))
    }

    @Test
    fun concurrent_failures_of_one_kind_are_written_once_and_the_rest_counted() {
        val gate = AndroidProxyFailureLogGate(intervalMs = 60_000L)
        val ready = CountDownLatch(1)
        val written = AtomicInteger()
        val workers =
            (0 until 8).map {
                thread {
                    ready.await()
                    repeat(100) { if (gate.admit("502", nowMs = 5L) != null) written.incrementAndGet() }
                }
            }
        ready.countDown()
        workers.forEach { it.join() }
        assertEquals(1, written.get())
        assertEquals(799, gate.admit("502", nowMs = 60_005L))
    }
}
