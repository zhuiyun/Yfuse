package com.yfuse.core.offline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfflineCommandQueueTest {
    @Test
    fun accepted_ui_commands_run_later_in_order_and_a_worker_waits_for_them() =
        runTest {
            val events = mutableListOf<String>()
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            try {
                val queue = OfflineCommandQueue(scope) { throw AssertionError(it) }
                assertTrue(queue.submit { events += "initialize" })
                assertTrue(queue.submit { events += "enqueue season" })
                assertTrue(queue.submit { events += "pause" })
                assertTrue(events.isEmpty(), "Submission must never execute disk work on the caller")
                queue.awaitPending()
                events += "worker reads index"
                assertEquals(listOf("initialize", "enqueue season", "pause", "worker reads index"), events)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun one_failed_write_is_reported_without_losing_following_commands() =
        runTest {
            val errors = mutableListOf<Throwable>()
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            try {
                val queue = OfflineCommandQueue(scope, onFailure = errors::add)
                var applied = false
                queue.submit { error("disk full") }
                queue.submit { applied = true }
                queue.awaitPending()
                assertTrue(applied)
                assertEquals("disk full", errors.single().message)
                assertFailsWith<IllegalStateException> { queue.execute { error("worker write failed") } }
                queue.awaitPending()
                assertEquals(2, errors.size)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun a_full_queue_rejects_with_feedback_instead_of_growing_or_running_on_the_caller() =
        runTest {
            val errors = mutableListOf<Throwable>()
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            try {
                val queue = OfflineCommandQueue(scope, capacity = 2, onFailure = errors::add)
                val applied = mutableListOf<Int>()
                assertTrue(queue.submit { applied += 1 })
                assertTrue(queue.submit { applied += 2 })
                assertFalse(queue.submit { applied += 3 })
                assertTrue(applied.isEmpty())
                assertEquals(1, errors.size)
                queue.awaitPending()
                assertEquals(listOf(1, 2), applied)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun cancelled_consumer_does_not_leave_worker_acknowledgements_hanging() =
        runTest {
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val queue = OfflineCommandQueue(scope) {}
            val pending = async { queue.awaitPending() }
            scope.cancel()
            assertFailsWith<CancellationException> { pending.await() }
            assertFalse(queue.submit {})
        }
}
