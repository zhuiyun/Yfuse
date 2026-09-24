package com.yfuse.core2.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidCore2RouterDispatcherTest {
    @Test
    fun the_command_loop_and_child_collector_never_touch_router_state_at_once() =
        runBlocking {
            val dispatcher = androidCore2RouterDispatcher()
            val inside = AtomicInteger()
            val overlaps = AtomicInteger()
            // The router's recovery counters are a plain LinkedHashMap shared by both coroutines.
            val recoveryAttempts = mutableMapOf<Int, Int>()
            val coroutines =
                (0 until 4).map { worker ->
                    launch(dispatcher) {
                        repeat(50) { step ->
                            if (inside.incrementAndGet() != 1) overlaps.incrementAndGet()
                            recoveryAttempts[step % 8] = (recoveryAttempts[step % 8] ?: 0) + 1
                            recoveryAttempts.keys.removeAll { it == (worker + step) % 8 }
                            // Hold the section long enough that a parallel dispatcher would overlap.
                            Thread.sleep(1L)
                            inside.decrementAndGet()
                            yield()
                        }
                    }
                }
            withTimeout(10_000L) { coroutines.joinAll() }
            assertEquals(0, overlaps.get())
        }

    @Test
    fun route_construction_moved_to_io_leaves_the_router_free_for_the_collector() =
        runBlocking {
            val dispatcher = androidCore2RouterDispatcher()
            val collectorRan = CountDownLatch(1)
            // Blocks its thread until a coroutine on the router has run, as a probe blocks on I/O.
            val rebuild =
                launch(dispatcher) {
                    withContext(Dispatchers.IO) { assertTrue(collectorRan.await(5L, TimeUnit.SECONDS)) }
                }
            val collector = launch(dispatcher) { collectorRan.countDown() }
            withTimeout(10_000L) { joinAll(rebuild, collector) }
        }
}
